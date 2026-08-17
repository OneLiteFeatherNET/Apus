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
package net.onelitefeather.apus.api.directory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import net.onelitefeather.apus.operator.api.Tenant;
import org.junit.jupiter.api.Test;

class TenantGroupIndexTest {

    private static Tenant tenant(String name, String groupId) {
        Tenant tenant = new Tenant();
        tenant.getMetadata().setName(name);
        tenant.getSpec().getIdentity().setGroupId(groupId);
        return tenant;
    }

    @Test
    void mapsAGroupToItsTenant() {
        TenantGroupIndex index = TenantGroupIndex.of(List.of(tenant("acme", "g-acme")));

        assertEquals("acme", index.tenantForGroups(List.of("g-acme")).orElseThrow());
        assertEquals("g-acme", index.groupForTenant("acme").orElseThrow());
        assertEquals(Set.of("g-acme"), index.managedGroups());
    }

    @Test
    void skipsATenantWithNoGroupRatherThanMappingABlankKey() {
        // An unconfigured tenant must not become the one every unmatched group falls into.
        TenantGroupIndex index = TenantGroupIndex.of(List.of(tenant("acme", null), tenant("globex", "  ")));

        assertTrue(index.managedGroups().isEmpty());
        assertTrue(index.groupForTenant("acme").isEmpty());
    }

    @Test
    void ignoresGroupsNoTenantClaims() {
        TenantGroupIndex index = TenantGroupIndex.of(List.of(tenant("acme", "g-acme")));

        assertTrue(index.tenantForGroups(List.of("g-someone-else")).isEmpty());
    }

    @Test
    void aUserInNoGroupAtAllHasNoTenant() {
        TenantGroupIndex index = TenantGroupIndex.of(List.of(tenant("acme", "g-acme")));

        assertTrue(index.tenantForGroups(List.of()).isEmpty());
    }

    @Test
    void resolvesTheSameWayEveryTimeWhenAUserIsInSeveralTenantsGroups() {
        // Multi-tenant membership is not modelled. Picking arbitrarily would make somebody's
        // tenant change between two requests, which is far worse than picking one and sticking
        // to it.
        TenantGroupIndex index =
                TenantGroupIndex.of(List.of(tenant("zeta", "g-zeta"), tenant("acme", "g-acme")));

        assertEquals("acme", index.tenantForGroups(List.of("g-zeta", "g-acme")).orElseThrow());
        assertEquals("acme", index.tenantForGroups(List.of("g-acme", "g-zeta")).orElseThrow());
    }

    @Test
    void survivesTwoTenantsClaimingTheSameGroup() {
        // Nothing prevents it -- the field is free text on a custom resource. Taking the API down
        // over a typo in one tenant would be a much worse answer than picking deterministically.
        TenantGroupIndex index =
                TenantGroupIndex.of(List.of(tenant("zeta", "shared"), tenant("acme", "shared")));

        assertEquals("acme", index.tenantForGroups(List.of("shared")).orElseThrow());
        assertEquals(Set.of("shared"), index.managedGroups());
        assertTrue(index.groupForTenant("zeta").isEmpty(), "the loser must not keep a mapping back to the group");
    }

    @Test
    void anEmptyIndexRecognisesNobodyAndManagesNothing() {
        assertTrue(TenantGroupIndex.empty().managedGroups().isEmpty());
        assertTrue(TenantGroupIndex.empty().tenantForGroups(List.of("g-acme")).isEmpty());
    }
}
