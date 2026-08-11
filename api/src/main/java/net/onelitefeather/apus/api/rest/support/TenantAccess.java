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
package net.onelitefeather.apus.api.rest.support;

import net.onelitefeather.apus.api.security.ApusPrincipal;
import net.onelitefeather.apus.api.security.Role;

/**
 * Role gates for tenant-scoped endpoints (sources, maps, renders, hostings) that {@link
 * ApusPrincipal} itself does not expose. {@link ApusPrincipal#canWrite()} already covers the
 * write gate; this class adds the read gate -- "does this caller hold any of the three
 * tenant-level roles at all" -- which is deliberately not a method on {@code ApusPrincipal}
 * itself (task 1's report and its unchanged signature) so it lives with the callers that need
 * it instead.
 *
 * <p>A caller with a tenant claim but zero recognised roles (for example a §10.3 service token
 * scoped only to {@code world:push}) resolves a namespace fine via {@code TenantResolver} but
 * fails both gates here -- by design: a narrow-scope service token must not gain general
 * read/write access to the tenant's REST API just because it is tied to a tenant.
 */
public final class TenantAccess {

    private TenantAccess() {}

    /** Whether {@code principal} holds any of the three tenant-level roles (read access). */
    public static boolean canRead(ApusPrincipal principal) {
        return principal.roles().contains(Role.TENANT_OWNER)
                || principal.roles().contains(Role.TENANT_OPERATOR)
                || principal.roles().contains(Role.TENANT_VIEWER);
    }
}
