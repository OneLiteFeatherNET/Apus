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
package net.onelitefeather.apus.api.rest.upload;

import io.micronaut.http.HttpResponse;
import io.micronaut.http.annotation.Body;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.PathVariable;
import io.micronaut.http.annotation.Post;
import io.micronaut.security.annotation.Secured;
import io.micronaut.security.authentication.Authentication;
import io.micronaut.security.rules.SecurityRule;
import net.onelitefeather.apus.api.rest.support.BadRequestException;
import net.onelitefeather.apus.api.rest.support.NotFoundException;
import net.onelitefeather.apus.api.rest.worldsource.WorldSourceRepository;
import net.onelitefeather.apus.api.security.ApusPrincipal;
import net.onelitefeather.apus.api.security.ForbiddenException;
import net.onelitefeather.apus.api.security.TenantResolver;
import net.onelitefeather.apus.api.support.PrincipalResolver;

/**
 * {@code POST /api/uploads} and {@code POST /api/uploads/{uploadId}/complete} -- the caller's own
 * tenant only (design spec §10.3, §11.1), exactly like every other JWT-authenticated controller in
 * this module. The namespace always comes from {@link TenantResolver}, never from the request
 * body; {@link MultipartUploadService} then derives the S3 key from that namespace alone, so a
 * caller can never reach outside their own tenant's staging prefix regardless of what {@code
 * sourceName}/{@code version}/{@code fileName} they supply (see that class's Javadoc).
 *
 * <p>Requires {@link ApusPrincipal#canWrite()} for both operations -- initiating and completing an
 * upload are both writes, exactly like {@code POST /api/sources} and {@code POST
 * /api/maps/{id}/render}.
 */
@Controller("/api/uploads")
@Secured(SecurityRule.IS_AUTHENTICATED)
public class UploadController {

    private static final String TYPE_UPLOAD = "upload";

    private final WorldSourceRepository sourceRepository;
    private final MultipartUploadService uploadService;
    private final PrincipalResolver principalResolver;
    private final TenantResolver tenantResolver;

    public UploadController(
            WorldSourceRepository sourceRepository,
            MultipartUploadService uploadService,
            PrincipalResolver principalResolver,
            TenantResolver tenantResolver) {
        this.sourceRepository = sourceRepository;
        this.uploadService = uploadService;
        this.principalResolver = principalResolver;
        this.tenantResolver = tenantResolver;
    }

    @Post
    public HttpResponse<CreateUploadResponse> create(Authentication authentication, @Body CreateUploadRequest request) {
        ApusPrincipal principal = principalResolver.resolve(authentication);
        requireWrite(principal);
        String namespace = tenantResolver.namespaceFor(principal);

        if (request == null || isBlank(request.sourceName())) {
            throw new BadRequestException("sourceName is required");
        }
        findOwnUploadSource(namespace, request.sourceName());

        CreateUploadResponse response =
                uploadService.createUpload(namespace, request.sourceName(), request.fileName(), request.sizeBytes());
        return HttpResponse.created(response);
    }

    @Post("/{uploadId}/complete")
    public HttpResponse<CompleteUploadResponse> complete(
            Authentication authentication, @PathVariable String uploadId, @Body CompleteUploadRequest request) {
        ApusPrincipal principal = principalResolver.resolve(authentication);
        requireWrite(principal);
        String namespace = tenantResolver.namespaceFor(principal);

        if (request == null || isBlank(request.sourceName())) {
            throw new BadRequestException("sourceName is required");
        }
        findOwnUploadSource(namespace, request.sourceName());

        CompleteUploadResponse response = uploadService.completeUpload(
                namespace, request.sourceName(), request.version(), request.fileName(), uploadId);
        return HttpResponse.ok(response);
    }

    /**
     * Confirmed to exist, as an {@code upload}-type source, in the caller's own namespace --
     * exactly like {@code BlueMapMapController.findOwnMap} does before creating a {@code
     * BlueMapRender} referencing it, and for the same reason: a foreign tenant's source name
     * must fail exactly like a non-existent one (404), never leaking that it exists elsewhere.
     */
    private void findOwnUploadSource(String namespace, String sourceName) {
        sourceRepository
                .find(namespace, sourceName)
                .filter(s -> TYPE_UPLOAD.equals(s.getSpec().getType()))
                .orElseThrow(() ->
                        new NotFoundException("no upload source '" + sourceName + "' in namespace '" + namespace + "'"));
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private void requireWrite(ApusPrincipal principal) {
        if (!principal.canWrite()) {
            throw new ForbiddenException("principal '" + principal.subject() + "' is not tenant-owner/tenant-operator");
        }
    }
}
