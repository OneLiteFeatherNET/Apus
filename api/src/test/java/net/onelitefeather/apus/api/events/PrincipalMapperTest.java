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
package net.onelitefeather.apus.api.events;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.micronaut.security.authentication.Authentication;
import java.util.List;
import java.util.Map;
import net.onelitefeather.apus.api.security.ApusPrincipal;
import net.onelitefeather.apus.api.security.Role;
import org.junit.jupiter.api.Test;

class PrincipalMapperTest {

    @Test
    void mapsNameRolesAndTheOrganizationClaim() {
        Authentication authentication = Authentication.build(
                "carol", List.of("tenant-operator"), Map.of("organization", "acme"));

        ApusPrincipal principal = PrincipalMapper.from(authentication);

        assertEquals("carol", principal.subject());
        assertEquals("acme", principal.tenant());
        assertEquals(java.util.Set.of(Role.TENANT_OPERATOR), principal.roles());
    }

    @Test
    void missingOrganizationClaimBecomesNullTenant() {
        Authentication authentication = Authentication.build("root", List.of("platform-admin"), Map.of());

        ApusPrincipal principal = PrincipalMapper.from(authentication);

        assertNull(principal.tenant());
    }

    @Test
    void unknownRoleClaimsAreDroppedNotRejected() {
        // Mirrors Role.fromClaim's own contract: one unrecognised role in the claim must not
        // blow up the whole mapping, it is simply not part of the resulting principal's roles.
        Authentication authentication = Authentication.build(
                "dave", List.of("tenant-viewer", "some-future-role"), Map.of("organization", "acme"));

        ApusPrincipal principal = PrincipalMapper.from(authentication);

        assertEquals(java.util.Set.of(Role.TENANT_VIEWER), principal.roles());
    }

    @Test
    void noRolesAtAllMapsToAnEmptySet() {
        Authentication authentication = Authentication.build("eve", List.of(), Map.of("organization", "acme"));

        ApusPrincipal principal = PrincipalMapper.from(authentication);

        assertTrue(principal.roles().isEmpty());
    }
}
