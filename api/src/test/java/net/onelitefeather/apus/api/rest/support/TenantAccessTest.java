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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import net.onelitefeather.apus.api.security.ApusPrincipal;
import net.onelitefeather.apus.api.security.Role;
import org.junit.jupiter.api.Test;

class TenantAccessTest {

    @Test
    void ownerCanRead() {
        assertTrue(TenantAccess.canRead(new ApusPrincipal("a", "acme", Set.of(Role.TENANT_OWNER))));
    }

    @Test
    void operatorCanRead() {
        assertTrue(TenantAccess.canRead(new ApusPrincipal("a", "acme", Set.of(Role.TENANT_OPERATOR))));
    }

    @Test
    void viewerCanRead() {
        assertTrue(TenantAccess.canRead(new ApusPrincipal("a", "acme", Set.of(Role.TENANT_VIEWER))));
    }

    @Test
    void noRolesCannotRead() {
        // The §10.3 service-token case: tenant claim present, but scoped to world:push only, so
        // it carries none of the four Role values -- must not gain general read access just
        // because it resolves a namespace fine.
        assertFalse(TenantAccess.canRead(new ApusPrincipal("service-token", "acme", Set.of())));
    }

    @Test
    void platformAdminAloneCannotReadATenant() {
        // Mirrors ApusPrincipal#canWrite()'s own deliberate exclusion of platform-admin: that
        // role's reach is platform-level, not into a specific tenant's sources/maps/renders.
        assertFalse(TenantAccess.canRead(new ApusPrincipal("root", "acme", Set.of(Role.PLATFORM_ADMIN))));
    }
}
