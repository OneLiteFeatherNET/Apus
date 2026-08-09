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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.micronaut.security.authentication.Authentication;
import java.util.List;
import java.util.Map;
import net.onelitefeather.apus.api.security.ApusPrincipal;
import net.onelitefeather.apus.api.security.Role;
import org.junit.jupiter.api.Test;

/**
 * {@link Authentication#build} gives us a real, spec-compliant {@link Authentication} without
 * needing a mocking library or a running Micronaut context -- neither is on this module's test
 * classpath (see task-1-report.md's "Concerns" section).
 */
class PrincipalResolverTest {

    private final PrincipalResolver resolver = new PrincipalResolver();

    @Test
    void resolvesSubjectAndRolesAndTenant() {
        Authentication auth = Authentication.build(
                "alice", List.of("tenant-owner", "tenant-viewer"), Map.of(PrincipalResolver.TENANT_CLAIM, "acme"));

        ApusPrincipal principal = resolver.resolve(auth);

        assertEquals("alice", principal.subject());
        assertEquals("acme", principal.tenant());
        assertEquals(java.util.Set.of(Role.TENANT_OWNER, Role.TENANT_VIEWER), principal.roles());
    }

    @Test
    void unrecognisedRoleClaimsAreDroppedNotRejected() {
        Authentication auth = Authentication.build(
                "bob", List.of("tenant-viewer", "some-future-role"), Map.of(PrincipalResolver.TENANT_CLAIM, "acme"));

        ApusPrincipal principal = resolver.resolve(auth);

        assertEquals(java.util.Set.of(Role.TENANT_VIEWER), principal.roles());
    }

    @Test
    void missingTenantClaimResolvesToNullNotADefault() {
        Authentication auth = Authentication.build("root", List.of("platform-admin"), Map.of());

        ApusPrincipal principal = resolver.resolve(auth);

        assertNull(principal.tenant());
        assertTrue(principal.isPlatformAdmin());
    }

    @Test
    void nonStringTenantClaimResolvesToNullRatherThanThrowing() {
        Authentication auth = Authentication.build(
                "carol", List.of("tenant-viewer"), Map.of(PrincipalResolver.TENANT_CLAIM, 42));

        ApusPrincipal principal = resolver.resolve(auth);

        assertNull(principal.tenant());
    }
}
