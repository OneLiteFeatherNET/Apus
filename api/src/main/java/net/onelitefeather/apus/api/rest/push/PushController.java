/**
 * Apus - render and host BlueMap maps on Kubernetes.
 * Copyright (C) 2026 OneLiteFeather and contributors
 * <p>
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * <p>
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 * <p>
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package net.onelitefeather.apus.api.rest.push;

import io.micronaut.core.annotation.Nullable;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.annotation.Body;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.PathVariable;
import io.micronaut.http.annotation.Post;
import io.micronaut.security.annotation.Secured;
import io.micronaut.security.rules.SecurityRule;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.onelitefeather.apus.api.rest.ingest.WorldIngestRepository;
import net.onelitefeather.apus.api.rest.support.BadRequestException;
import net.onelitefeather.apus.api.rest.support.NotFoundException;
import net.onelitefeather.apus.api.rest.worldsource.WorldSourceRepository;
import net.onelitefeather.apus.operator.api.WorldIngest;
import net.onelitefeather.apus.operator.api.WorldSource;

/**
 * {@code POST /api/push/{token}} -- the completion report a {@code push}-type {@code
 * WorldSource}'s owner (the Paper server plugin) sends once it has finished writing world data
 * directly into its tenant's staging prefix in S3. Creates one {@link WorldIngest} per world the
 * target source has configured (design spec §8.3, mirroring {@code
 * WorldSourceReconciler#triggerIngests}'s per-world loop for pull sources -- §6.4's "beide Wege
 * münden in denselben Code-Pfad").
 *
 * <p><b>The one endpoint in this module that is not JWT-authenticated.</b> {@code
 * @Secured(SecurityRule.IS_ANONYMOUS)} is deliberate, not an oversight: this call carries a
 * service token in the path, never a bearer JWT (see {@link PushTokenRepository}'s Javadoc for
 * why), so Micronaut Security's JWT filter has nothing to validate here -- authentication is this
 * controller's own job, done entirely by {@link PushTokenRepository#resolveNamespace}.
 *
 * <p><b>The namespace always comes from the token, never from the request body</b> (task brief's
 * central rule for this endpoint, mirroring {@code TenantResolver}'s for JWT-authenticated ones).
 * {@code request.sourceName()} only selects *which* of the token's own tenant's sources to target
 * -- {@link WorldSourceRepository#find} is already scoped to that token-resolved namespace before
 * {@code sourceName} is ever looked at, so a source name that happens to exist in a different
 * tenant cannot be reached this way.
 *
 * <p><b>Every failure path is a plain 404 or 400, uniformly.</b> An unknown/wrong-tenant token, an
 * unknown source name, and a source that is not of type {@code push} all produce {@link
 * NotFoundException} -- never a distinct status or message that would let a caller tell "this
 * token is wrong" apart from "this token is right but that source doesn't exist" apart from "that
 * tenant doesn't exist at all". A malformed request body (missing {@code sourceName}/{@code
 * version}, or a source with no worlds configured) is the caller's own request being invalid,
 * which is safe to report distinctly (400) -- it happens only *after* the token has already
 * proven the caller belongs to a real tenant, so it discloses nothing about any other one.
 */
@Controller("/api/push")
@Secured(SecurityRule.IS_ANONYMOUS)
public class PushController {

    private static final String TYPE_PUSH = "push";

    private final PushTokenRepository tokenRepository;
    private final WorldSourceRepository sourceRepository;
    private final WorldIngestRepository ingestRepository;

    public PushController(
            PushTokenRepository tokenRepository,
            WorldSourceRepository sourceRepository,
            WorldIngestRepository ingestRepository) {
        this.tokenRepository = tokenRepository;
        this.sourceRepository = sourceRepository;
        this.ingestRepository = ingestRepository;
    }

    @Post("/{token}")
    public HttpResponse<PushReportResponse> report(
            @PathVariable String token, @Nullable @Body PushReportRequest request) {
        // Resolved before the request body is even inspected: an invalid token must fail
        // identically regardless of what (if anything) the body contains.
        String namespace = tokenRepository
                .resolveNamespace(token)
                .orElseThrow(() -> new NotFoundException("no push source authorized for this token"));

        if (request == null || isBlank(request.sourceName()) || isBlank(request.version())) {
            throw new BadRequestException("sourceName and version are both required");
        }

        WorldSource source = sourceRepository
                .find(namespace, request.sourceName())
                .filter(s -> TYPE_PUSH.equals(s.getSpec().getType()))
                .orElseThrow(() -> new NotFoundException(
                        "no push source '" + request.sourceName() + "' in namespace '" + namespace + "'"));

        List<WorldSource.WorldSelector> worlds = source.getSpec().getWorlds();
        if (worlds.isEmpty()) {
            throw new BadRequestException("source '" + request.sourceName() + "' has no configured worlds");
        }

        List<String> created = new ArrayList<>();
        for (WorldSource.WorldSelector selector : worlds) {
            WorldIngest ingest = new WorldIngest();
            ingest.getMetadata()
                    .setGenerateName(
                            sanitize(request.sourceName()) + "-" + sanitize(selector.getName()) + "-push-");
            ingest.getSpec().getSourceRef().setName(request.sourceName());
            ingest.getSpec().setSourceVersion(request.version());
            ingest.getSpec().setWorldName(selector.getName());

            WorldIngest result = ingestRepository.create(namespace, ingest);
            created.add(result.getMetadata().getName());
        }

        return HttpResponse.created(new PushReportResponse(created));
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    /**
     * Kubernetes {@code generateName} prefixes must be valid RFC 1123 DNS subdomain label
     * fragments (lowercase alphanumeric and {@code -}); neither a {@code WorldSource} name nor,
     * especially, a Minecraft world name (e.g. {@code world_nether}) is guaranteed to already be
     * one.
     */
    private static String sanitize(String value) {
        String lower = value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9-]", "-");
        String trimmed = lower.replaceAll("^-+", "").replaceAll("-+$", "");
        return trimmed.isEmpty() ? "x" : trimmed;
    }
}
