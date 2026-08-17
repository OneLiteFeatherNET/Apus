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
package net.onelitefeather.apus.api.support;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.micronaut.security.authentication.Authentication;
import java.util.List;
import java.util.Map;
import net.onelitefeather.apus.api.directory.TenantGroupIndex;
import net.onelitefeather.apus.api.security.ApusPrincipal;
import net.onelitefeather.apus.operator.api.Tenant;
import org.junit.jupiter.api.Test;

/**
 * Resolving a tenant from the {@code groups} claim.
 *
 * <p>This is the fix for a bug that made the tenant application show "No tenant to show" for
 * every user since single sign-on was set up: the resolver read a claim named {@code
 * organization} which the app registration never emitted, because neither {@code
 * groupMembershipClaims} nor {@code optionalClaims} was ever configured on it. The claim was not
 * mis-mapped -- it did not exist.
 */
class PrincipalResolverGroupsTest {

    private static Tenant tenant(String name, String groupId) {
        Tenant tenant = new Tenant();
        tenant.getMetadata().setName(name);
        tenant.getSpec().getIdentity().setGroupId(groupId);
        return tenant;
    }

    private static PrincipalResolver resolverFor(Tenant... tenants) {
        PrincipalResolver resolver = new PrincipalResolver();
        resolver.setGroupIndex(TenantGroupIndex.of(List.of(tenants)));
        return resolver;
    }

    private static Authentication withGroups(Object groups) {
        return Authentication.build("alice", List.of("tenant-owner"), Map.of("groups", groups));
    }

    @Test
    void resolvesTheTenantFromTheGroupsClaim() {
        ApusPrincipal principal = resolverFor(tenant("acme", "g-acme")).resolve(withGroups(List.of("g-acme")));

        assertEquals("acme", principal.tenant());
    }

    @Test
    void stillPrefersAnExplicitOrganizationClaimWhenOneIsPresent() {
        // Some brokers do emit it, and a platform that configured one should not have its
        // meaning quietly overridden by group membership.
        Authentication authentication = Authentication.build(
                "alice", List.of("tenant-owner"), Map.of("organization", "globex", "groups", List.of("g-acme")));

        assertEquals(
                "globex", resolverFor(tenant("acme", "g-acme")).resolve(authentication).tenant());
    }

    @Test
    void hasNoTenantWhenNoGroupMatches() {
        // The failure mode does not change -- only the success case starts working.
        assertNull(resolverFor(tenant("acme", "g-acme"))
                .resolve(withGroups(List.of("g-unrelated")))
                .tenant());
    }

    @Test
    void hasNoTenantWhenTheTokenCarriesNoGroupsAtAll() {
        Authentication bare = Authentication.build("alice", List.of("tenant-owner"), Map.of());

        assertNull(resolverFor(tenant("acme", "g-acme")).resolve(bare).tenant());
    }

    @Test
    void ignoresAGroupsClaimThatIsNotAListOfStrings() {
        // A claim of an unexpected shape must not throw on every request; it must simply fail to
        // identify a tenant, which is the same outcome as having no groups.
        assertNull(resolverFor(tenant("acme", "g-acme")).resolve(withGroups("g-acme")).tenant());
        assertNull(resolverFor(tenant("acme", "g-acme"))
                .resolve(withGroups(List.of(1, 2, 3)))
                .tenant());
    }

    @Test
    void resolvesNothingBeforeTheIndexHasBeenLoaded() {
        // The state the resolver is in for the first moments after startup. Failing to identify a
        // tenant is right here; inventing one would not be.
        assertNull(new PrincipalResolver().resolve(withGroups(List.of("g-acme"))).tenant());
    }
}
