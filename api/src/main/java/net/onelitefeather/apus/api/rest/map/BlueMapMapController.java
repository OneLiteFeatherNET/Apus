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
package net.onelitefeather.apus.api.rest.map;

import io.micronaut.core.annotation.Nullable;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.annotation.Body;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.PathVariable;
import io.micronaut.http.annotation.Post;
import io.micronaut.security.annotation.Secured;
import io.micronaut.security.authentication.Authentication;
import io.micronaut.security.rules.SecurityRule;
import java.util.List;
import net.onelitefeather.apus.api.rest.render.BlueMapRenderRepository;
import net.onelitefeather.apus.api.rest.render.BlueMapRenderResponse;
import net.onelitefeather.apus.api.rest.support.NotFoundException;
import net.onelitefeather.apus.api.rest.support.TenantAccess;
import net.onelitefeather.apus.api.security.ApusPrincipal;
import net.onelitefeather.apus.api.security.ForbiddenException;
import net.onelitefeather.apus.api.security.TenantResolver;
import net.onelitefeather.apus.api.support.PrincipalResolver;
import net.onelitefeather.apus.operator.api.BlueMapMap;
import net.onelitefeather.apus.operator.api.BlueMapRender;
import net.onelitefeather.apus.operator.api.Ref;

/**
 * {@code GET /api/maps}, {@code GET /api/maps/{id}}, and {@code POST /api/maps/{id}/render} --
 * the caller's own tenant only (design spec §10.3, §11.1). The namespace always comes from
 * {@link TenantResolver}, never from a request parameter.
 *
 * <p>{@code POST /api/maps/{id}/render} looks the map up in the caller's own namespace first,
 * exactly like {@code getById} -- so triggering a render against a foreign tenant's map ID
 * fails with the same 404 a plain lookup would, rather than either leaking that the map exists
 * elsewhere or creating a {@code BlueMapRender} whose {@code mapRef} dangles. Only once that
 * lookup succeeds does it create the {@code BlueMapRender}, in the same namespace as the map it
 * refers to (design spec §10.1: a resource may only reference something in its own namespace).
 */
@Controller("/api/maps")
@Secured(SecurityRule.IS_AUTHENTICATED)
public class BlueMapMapController {

    private final BlueMapMapRepository mapRepository;
    private final BlueMapRenderRepository renderRepository;
    private final PrincipalResolver principalResolver;
    private final TenantResolver tenantResolver;

    public BlueMapMapController(
            BlueMapMapRepository mapRepository,
            BlueMapRenderRepository renderRepository,
            PrincipalResolver principalResolver,
            TenantResolver tenantResolver) {
        this.mapRepository = mapRepository;
        this.renderRepository = renderRepository;
        this.principalResolver = principalResolver;
        this.tenantResolver = tenantResolver;
    }

    @Get
    public HttpResponse<List<BlueMapMapResponse>> list(Authentication authentication) {
        ApusPrincipal principal = principalResolver.resolve(authentication);
        requireRead(principal);
        String namespace = tenantResolver.namespaceFor(principal);

        List<BlueMapMapResponse> maps =
                mapRepository.list(namespace).stream().map(BlueMapMapResponse::from).toList();
        return HttpResponse.ok(maps);
    }

    @Get("/{id}")
    public HttpResponse<BlueMapMapResponse> getById(Authentication authentication, @PathVariable String id) {
        ApusPrincipal principal = principalResolver.resolve(authentication);
        requireRead(principal);
        String namespace = tenantResolver.namespaceFor(principal);

        BlueMapMap map = findOwnMap(namespace, id);
        return HttpResponse.ok(BlueMapMapResponse.from(map));
    }

    @Post("/{id}/render")
    public HttpResponse<BlueMapRenderResponse> triggerRender(
            Authentication authentication, @PathVariable String id, @Nullable @Body TriggerRenderRequest request) {
        ApusPrincipal principal = principalResolver.resolve(authentication);
        requireWrite(principal);
        String namespace = tenantResolver.namespaceFor(principal);

        // Confirmed to exist in the caller's own namespace before anything is created -- see
        // this class's Javadoc for why a foreign-tenant map ID must fail exactly like a
        // non-existent one, before a BlueMapRender referencing it is ever created.
        findOwnMap(namespace, id);

        BlueMapRender render = new BlueMapRender();
        render.getMetadata().setGenerateName(id + "-");
        Ref mapRef = new Ref();
        mapRef.setName(id);
        render.getSpec().setMapRef(mapRef);
        render.getSpec().setForce(request != null && request.force());

        BlueMapRender created = renderRepository.create(namespace, render);
        return HttpResponse.created(BlueMapRenderResponse.from(created));
    }

    private BlueMapMap findOwnMap(String namespace, String id) {
        return mapRepository
                .find(namespace, id)
                .orElseThrow(() -> new NotFoundException("no map '" + id + "' in namespace '" + namespace + "'"));
    }

    private void requireRead(ApusPrincipal principal) {
        if (!TenantAccess.canRead(principal)) {
            throw new ForbiddenException("principal '" + principal.subject() + "' has no tenant role");
        }
    }

    private void requireWrite(ApusPrincipal principal) {
        if (!principal.canWrite()) {
            throw new ForbiddenException("principal '" + principal.subject() + "' is not tenant-owner/tenant-operator");
        }
    }
}
