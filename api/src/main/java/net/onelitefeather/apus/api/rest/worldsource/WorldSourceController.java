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
package net.onelitefeather.apus.api.rest.worldsource;

import io.micronaut.http.HttpResponse;
import io.micronaut.http.annotation.Body;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.Post;
import io.micronaut.security.annotation.Secured;
import io.micronaut.security.authentication.Authentication;
import io.micronaut.security.rules.SecurityRule;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import net.onelitefeather.apus.api.rest.support.BadRequestException;
import net.onelitefeather.apus.api.rest.support.TenantAccess;
import net.onelitefeather.apus.api.security.ApusPrincipal;
import net.onelitefeather.apus.api.security.ForbiddenException;
import net.onelitefeather.apus.api.security.TenantResolver;
import net.onelitefeather.apus.api.support.PrincipalResolver;
import net.onelitefeather.apus.operator.api.Ref;
import net.onelitefeather.apus.operator.api.WorldSource;
import net.onelitefeather.apus.operator.api.WorldSourceSpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@code GET /api/sources} and {@code POST /api/sources} -- the caller's own tenant only (design
 * spec §10.3, §11.1). The namespace always comes from {@link TenantResolver}, never from a
 * request parameter -- see task-2-brief.md's central rule. {@code list} requires any of the
 * three tenant roles; {@code create} requires {@link ApusPrincipal#canWrite()} (owner/operator).
 *
 * <p>See {@code TenantController}'s Javadoc for why the role gates below are manual checks
 * throwing {@link ForbiddenException} rather than {@code @Secured} role strings.
 */
@Controller("/api/sources")
@Secured(SecurityRule.IS_AUTHENTICATED)
public class WorldSourceController {

    private static final Logger LOGGER = LoggerFactory.getLogger(WorldSourceController.class);

    private static final Set<String> VALID_TYPES = Set.of("s3", "pterodactyl", "upload", "push");

    private final WorldSourceRepository repository;
    private final PrincipalResolver principalResolver;
    private final TenantResolver tenantResolver;

    public WorldSourceController(
            WorldSourceRepository repository, PrincipalResolver principalResolver, TenantResolver tenantResolver) {
        this.repository = repository;
        this.principalResolver = principalResolver;
        this.tenantResolver = tenantResolver;
    }

    @Get
    public HttpResponse<List<WorldSourceResponse>> list(Authentication authentication) {
        ApusPrincipal principal = principalResolver.resolve(authentication);
        requireRead(principal);
        String namespace = tenantResolver.namespaceFor(principal);

        List<WorldSourceResponse> sources = repository.list(namespace).stream()
                .map(WorldSourceResponse::from)
                .toList();
        return HttpResponse.ok(sources);
    }

    @Post
    public HttpResponse<WorldSourceResponse> create(
            Authentication authentication, @Body CreateWorldSourceRequest request) {
        ApusPrincipal principal = principalResolver.resolve(authentication);
        requireWrite(principal);
        String namespace = tenantResolver.namespaceFor(principal);

        if (request.name() == null || request.name().isBlank()) {
            throw new BadRequestException("name must not be blank");
        }
        if (request.type() == null || !VALID_TYPES.contains(request.type())) {
            throw new BadRequestException("type must be one of " + VALID_TYPES);
        }

        WorldSource source = new WorldSource();
        source.getMetadata().setName(request.name());
        WorldSourceSpec spec = source.getSpec();
        spec.setType(request.type());
        spec.setPoll(request.poll());
        if (request.keepVersions() != null) {
            spec.getRetention().setKeepVersions(request.keepVersions());
        }
        if (request.s3() != null) {
            spec.getS3().setEndpoint(request.s3().endpoint());
            spec.getS3().setBucket(request.s3().bucket());
            spec.getS3().setPrefix(request.s3().prefix());
            if (request.s3().credentialsSecretName() != null) {
                Ref ref = new Ref();
                ref.setName(request.s3().credentialsSecretName());
                spec.getS3().setCredentialsSecretRef(ref);
            }
        }
        if (request.pterodactyl() != null) {
            spec.getPterodactyl().setPanelUrl(request.pterodactyl().panelUrl());
            spec.getPterodactyl().setServerId(request.pterodactyl().serverId());
            if (request.pterodactyl().select() != null) {
                spec.getPterodactyl().setSelect(request.pterodactyl().select());
            }
            if (request.pterodactyl().credentialsSecretName() != null) {
                Ref ref = new Ref();
                ref.setName(request.pterodactyl().credentialsSecretName());
                spec.getPterodactyl().setCredentialsSecretRef(ref);
            }
        }
        if (request.worlds() != null) {
            List<WorldSource.WorldSelector> worlds = new ArrayList<>();
            for (var w : request.worlds()) {
                WorldSource.WorldSelector selector = new WorldSource.WorldSelector();
                selector.setName(w.name());
                if (w.layout() != null) {
                    selector.setLayout(w.layout());
                }
                selector.setMinecraftVersion(w.minecraftVersion());
                worlds.add(selector);
            }
            spec.setWorlds(worlds);
        }

        WorldSource created = repository.create(namespace, source);
        // The source's type and name only; an s3/pterodactyl source's credentials live in a
        // referenced Secret and are never read, let alone logged, here.
        LOGGER.info(
                "world source '{}' of type '{}' created in namespace '{}'",
                created.getMetadata().getName(),
                request.type(),
                namespace);
        return HttpResponse.created(WorldSourceResponse.from(created));
    }

    private void requireRead(ApusPrincipal principal) {
        if (!TenantAccess.canRead(principal)) {
            LOGGER.warn("principal '{}' denied read access to /api/sources: no tenant role", principal.subject());
            throw new ForbiddenException("principal '" + principal.subject() + "' has no tenant role");
        }
    }

    private void requireWrite(ApusPrincipal principal) {
        if (!principal.canWrite()) {
            LOGGER.warn("principal '{}' denied write access to /api/sources", principal.subject());
            throw new ForbiddenException("principal '" + principal.subject() + "' is not tenant-owner/tenant-operator");
        }
    }
}
