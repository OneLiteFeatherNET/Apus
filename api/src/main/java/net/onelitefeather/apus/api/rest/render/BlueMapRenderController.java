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
package net.onelitefeather.apus.api.rest.render;

import io.micronaut.http.HttpResponse;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.PathVariable;
import io.micronaut.security.annotation.Secured;
import io.micronaut.security.authentication.Authentication;
import io.micronaut.security.rules.SecurityRule;
import java.util.List;
import java.util.stream.Stream;
import net.onelitefeather.apus.api.rest.support.NotFoundException;
import net.onelitefeather.apus.api.rest.support.TenantAccess;
import net.onelitefeather.apus.api.rest.tenant.TenantRepository;
import net.onelitefeather.apus.api.security.ApusPrincipal;
import net.onelitefeather.apus.api.security.ForbiddenException;
import net.onelitefeather.apus.api.security.TenantResolver;
import net.onelitefeather.apus.api.support.PrincipalResolver;

/**
 * {@code GET /api/renders} and {@code GET /api/renders/{id}} -- read-only, the caller's own
 * tenant only. A render belonging to a different tenant looks up empty in this tenant's
 * namespace and, per task-2-brief.md's central rule, produces the exact same 404 as a render
 * that does not exist anywhere -- see {@link NotFoundException}'s Javadoc.
 *
 * <p>{@code GET /api/renders/cluster} is the one deliberate exception to "the caller's own
 * tenant only": {@code platform-admin}'s cluster-wide view (design spec §10.3). It is a
 * literal route, checked before the {@code /{id}} route can match it, and its own method
 * ({@link #listCluster}) does not go through {@link TenantResolver} at all -- same reasoning as
 * {@code TenantController} not going through it (see that class's Javadoc): a platform-admin is
 * not necessarily a member of any tenant, so resolving *a* namespace for it would be wrong even
 * if one happened to exist. This is the only method on this controller allowed to see more than
 * one tenant's resources; everything else keeps the invariant that the tenant comes from the
 * token and the token alone.
 */
@Controller("/api/renders")
@Secured(SecurityRule.IS_AUTHENTICATED)
public class BlueMapRenderController {

    private final BlueMapRenderRepository repository;
    private final PrincipalResolver principalResolver;
    private final TenantResolver tenantResolver;
    private final TenantRepository tenantRepository;

    public BlueMapRenderController(
            BlueMapRenderRepository repository,
            PrincipalResolver principalResolver,
            TenantResolver tenantResolver,
            TenantRepository tenantRepository) {
        this.repository = repository;
        this.principalResolver = principalResolver;
        this.tenantResolver = tenantResolver;
        this.tenantRepository = tenantRepository;
    }

    @Get
    public HttpResponse<List<BlueMapRenderResponse>> list(Authentication authentication) {
        ApusPrincipal principal = principalResolver.resolve(authentication);
        requireRead(principal);
        String namespace = tenantResolver.namespaceFor(principal);

        List<BlueMapRenderResponse> renders = repository.list(namespace).stream()
                .map(BlueMapRenderResponse::from)
                .toList();
        return HttpResponse.ok(renders);
    }

    /**
     * The cluster-wide view (design spec §10.3, §11.2: "laufende Jobs clusterweit"),
     * {@code platform-admin} only. Walks every {@code Tenant} the platform-admin has cluster-wide
     * reach to (via {@link TenantRepository}, exactly like {@code TenantController} does), and
     * for each one lists renders in that tenant's own namespace -- the same {@link
     * BlueMapRenderRepository#list(String)} every tenant-scoped call already uses, just invoked
     * once per tenant instead of once for the caller's own. A tenant with no namespace recorded
     * yet in its status (freshly created, not yet reconciled) is skipped rather than failing the
     * whole call.
     */
    @Get("/cluster")
    public HttpResponse<List<ClusterRenderResponse>> listCluster(Authentication authentication) {
        ApusPrincipal principal = principalResolver.resolve(authentication);
        if (!principal.isPlatformAdmin()) {
            throw new ForbiddenException("principal '" + principal.subject() + "' is not a platform-admin");
        }

        List<ClusterRenderResponse> renders = tenantRepository.list().stream()
                .flatMap(tenant -> {
                    String namespace = tenant.getStatus().getNamespace();
                    if (namespace == null || namespace.isBlank()) {
                        return Stream.<ClusterRenderResponse>empty();
                    }
                    String tenantName = tenant.getMetadata().getName();
                    return repository.list(namespace).stream()
                            .map(render -> ClusterRenderResponse.from(tenantName, render));
                })
                .toList();
        return HttpResponse.ok(renders);
    }

    @Get("/{id}")
    public HttpResponse<BlueMapRenderResponse> getById(Authentication authentication, @PathVariable String id) {
        ApusPrincipal principal = principalResolver.resolve(authentication);
        requireRead(principal);
        String namespace = tenantResolver.namespaceFor(principal);

        var render = repository
                .find(namespace, id)
                .orElseThrow(() -> new NotFoundException("no render '" + id + "' in namespace '" + namespace + "'"));
        return HttpResponse.ok(BlueMapRenderResponse.from(render));
    }

    private void requireRead(ApusPrincipal principal) {
        if (!TenantAccess.canRead(principal)) {
            throw new ForbiddenException("principal '" + principal.subject() + "' has no tenant role");
        }
    }
}
