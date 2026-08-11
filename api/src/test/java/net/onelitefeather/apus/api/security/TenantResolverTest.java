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
package net.onelitefeather.apus.api.security;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import net.onelitefeather.apus.operator.api.Tenant;
import net.onelitefeather.apus.operator.tenant.TenantReconciler;
import org.junit.jupiter.api.Test;

/**
 * The core safety net for design spec §10.3: the namespace a caller may act in comes solely
 * from their token's tenant claim, never from anything else. Every test here is written to
 * catch exactly the regression it is named for.
 */
class TenantResolverTest {

    private final TenantResolver resolver = new TenantResolver();

    @Test
    void namespaceForRejectsAPrincipalWithoutATenant() {
        ApusPrincipal noTenant = new ApusPrincipal("alice", null, Set.of(Role.TENANT_VIEWER));
        assertThrows(ForbiddenException.class, () -> resolver.namespaceFor(noTenant));
    }

    @Test
    void namespaceForRejectsAPlatformAdminWithoutATenantToo() {
        // Platform-admin's cross-tenant reach (design spec §10.3: "clusterweite Sicht") is a
        // decision the REST layer makes elsewhere, by routing to a platform-level endpoint that
        // never calls namespaceFor at all -- not a bypass built into this method. If it ever
        // silently defaulted an admin token to some namespace, that would be exactly the "no
        // default tenant" rule broken for the one role most dangerous to break it for.
        ApusPrincipal admin = new ApusPrincipal("root", null, Set.of(Role.PLATFORM_ADMIN));
        assertThrows(ForbiddenException.class, () -> resolver.namespaceFor(admin));
    }

    @Test
    void namespaceForUsesTheTenantOnThePrincipal() {
        ApusPrincipal viewer = new ApusPrincipal("carol", "acme", Set.of(Role.TENANT_VIEWER));
        assertEquals("bluemap-acme", resolver.namespaceFor(viewer));
    }

    @Test
    void namespaceForIsTheSameForEveryRole() {
        // Role gates *what* a caller may do inside a namespace (see ApusPrincipalTest); it must
        // never change *which* namespace resolution produces.
        for (Role role : Role.values()) {
            ApusPrincipal principal = new ApusPrincipal("user", "acme", Set.of(role));
            assertEquals("bluemap-acme", resolver.namespaceFor(principal), () -> role + " changed the resolved namespace");
        }
    }

    @Test
    void namespaceForMatchesTheOperatorsOwnNamingConvention() {
        // Cross-checked against the real TenantReconciler instead of duplicating "bluemap-" as
        // a second, independent source of truth: this fails the moment the operator's naming
        // convention changes and this resolver is not updated to match, instead of the two
        // silently drifting apart and namespaceFor pointing at a namespace the operator never
        // actually provisions.
        Tenant tenant = new Tenant();
        tenant.getMetadata().setName("acme");
        String expected = TenantReconciler.namespaceFor(tenant);

        ApusPrincipal principal = new ApusPrincipal("carol", "acme", Set.of(Role.TENANT_VIEWER));
        assertEquals(expected, resolver.namespaceFor(principal));
    }

    @Test
    void differentTenantsResolveToDifferentNamespaces() {
        ApusPrincipal acme = new ApusPrincipal("a", "acme", Set.of(Role.TENANT_VIEWER));
        ApusPrincipal globex = new ApusPrincipal("b", "globex", Set.of(Role.TENANT_VIEWER));
        assertNotEquals(resolver.namespaceFor(acme), resolver.namespaceFor(globex));
    }

    @Test
    void namespaceForRequiresANonNullPrincipal() {
        assertThrows(NullPointerException.class, () -> resolver.namespaceFor(null));
    }

    @Test
    void namespaceForHasExactlyOnePublicMethodAndItTakesOnlyAPrincipal() {
        // The load-bearing test for the brief's central rule: there is no path -- no overload,
        // no extra parameter -- through which anything other than the validated principal's own
        // tenant claim can influence the resolved namespace. If a future change adds e.g.
        // namespaceFor(ApusPrincipal, String namespaceOverride) "for platform-admin" or "for
        // testing", this test fails the build before any endpoint gets to use it.
        List<Method> publicMethods = Arrays.stream(TenantResolver.class.getDeclaredMethods())
                .filter(method -> Modifier.isPublic(method.getModifiers()))
                .toList();

        assertEquals(1, publicMethods.size(), () -> "expected exactly one public method on TenantResolver, found: "
                + publicMethods);

        Method namespaceFor = publicMethods.get(0);
        assertEquals("namespaceFor", namespaceFor.getName());
        assertArrayEquals(new Class<?>[] {ApusPrincipal.class}, namespaceFor.getParameterTypes());
        assertEquals(String.class, namespaceFor.getReturnType());
    }

    @Test
    void tenantResolverIsFinal() {
        // Not subclassable to add a second, overriding namespaceFor with a different signature
        // or a loosened contract.
        assertTrue(Modifier.isFinal(TenantResolver.class.getModifiers()));
    }
}
