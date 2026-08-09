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
import net.onelitefeather.apus.api.rest.support.NotFoundException;
import net.onelitefeather.apus.api.rest.support.PrincipalResolver;
import net.onelitefeather.apus.api.rest.support.TenantAccess;
import net.onelitefeather.apus.api.security.ApusPrincipal;
import net.onelitefeather.apus.api.security.ForbiddenException;
import net.onelitefeather.apus.api.security.TenantResolver;

/**
 * {@code GET /api/renders} and {@code GET /api/renders/{id}} -- read-only, the caller's own
 * tenant only. A render belonging to a different tenant looks up empty in this tenant's
 * namespace and, per task-2-brief.md's central rule, produces the exact same 404 as a render
 * that does not exist anywhere -- see {@link NotFoundException}'s Javadoc.
 */
@Controller("/api/renders")
@Secured(SecurityRule.IS_AUTHENTICATED)
public class BlueMapRenderController {

    private final BlueMapRenderRepository repository;
    private final PrincipalResolver principalResolver;
    private final TenantResolver tenantResolver;

    public BlueMapRenderController(
            BlueMapRenderRepository repository, PrincipalResolver principalResolver, TenantResolver tenantResolver) {
        this.repository = repository;
        this.principalResolver = principalResolver;
        this.tenantResolver = tenantResolver;
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
