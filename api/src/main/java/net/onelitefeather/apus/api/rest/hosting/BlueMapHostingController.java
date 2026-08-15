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
package net.onelitefeather.apus.api.rest.hosting;

import io.micronaut.http.HttpResponse;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.security.annotation.Secured;
import io.micronaut.security.authentication.Authentication;
import io.micronaut.security.rules.SecurityRule;
import java.util.List;
import net.onelitefeather.apus.api.rest.support.TenantAccess;
import net.onelitefeather.apus.api.security.ApusPrincipal;
import net.onelitefeather.apus.api.security.ForbiddenException;
import net.onelitefeather.apus.api.security.TenantResolver;
import net.onelitefeather.apus.api.support.PrincipalResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** {@code GET /api/hostings} -- read-only, the caller's own tenant only. */
@Controller("/api/hostings")
@Secured(SecurityRule.IS_AUTHENTICATED)
public class BlueMapHostingController {

    private static final Logger LOGGER = LoggerFactory.getLogger(BlueMapHostingController.class);

    private final BlueMapHostingRepository repository;
    private final PrincipalResolver principalResolver;
    private final TenantResolver tenantResolver;

    public BlueMapHostingController(
            BlueMapHostingRepository repository, PrincipalResolver principalResolver, TenantResolver tenantResolver) {
        this.repository = repository;
        this.principalResolver = principalResolver;
        this.tenantResolver = tenantResolver;
    }

    @Get
    public HttpResponse<List<BlueMapHostingResponse>> list(Authentication authentication) {
        ApusPrincipal principal = principalResolver.resolve(authentication);
        if (!TenantAccess.canRead(principal)) {
            LOGGER.warn("principal '{}' denied read access to /api/hostings: no tenant role", principal.subject());
            throw new ForbiddenException("principal '" + principal.subject() + "' has no tenant role");
        }
        String namespace = tenantResolver.namespaceFor(principal);

        List<BlueMapHostingResponse> hostings =
                repository.list(namespace).stream().map(BlueMapHostingResponse::from).toList();
        return HttpResponse.ok(hostings);
    }
}
