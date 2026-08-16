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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Impersonation is a feature whose failure mode is somebody quietly acting with authority that
 * is not theirs, so these tests are almost entirely about what it refuses and what it strips.
 */
class ImpersonationPolicyTest {

    private final ImpersonationPolicy policy = new ImpersonationPolicy();

    private static ApusPrincipal platformAdmin() {
        return new ApusPrincipal("root", null, Set.of(Role.PLATFORM_ADMIN));
    }

    private static ApusPrincipal owner(String tenant) {
        return new ApusPrincipal("alice", tenant, Set.of(Role.TENANT_OWNER));
    }

    private static ApusPrincipal viewer(String tenant) {
        return new ApusPrincipal("bob", tenant, Set.of(Role.TENANT_VIEWER));
    }

    @Test
    void aPlatformAdminCanActWithinAnyTenant() {
        Impersonation impersonation = policy.resolve(platformAdmin(), "acme", "u-alice");

        assertEquals("acme", impersonation.effective().tenant());
        assertEquals("u-alice", impersonation.effective().subject());
    }

    @Test
    void theImpersonatedSessionNeverCarriesThePlatformRole() {
        // The rule the whole feature rests on. A session that kept platform-admin would let
        // someone reach every other tenant while wearing a tenant member's name -- the exact
        // opposite of what an audit trail is for.
        Impersonation impersonation = policy.resolve(platformAdmin(), "acme", "u-alice");

        assertFalse(impersonation.effective().isPlatformAdmin());
        assertEquals(Set.of(Role.TENANT_OWNER), impersonation.effective().roles());
    }

    @Test
    void theRealSubjectSurvivesForTheAuditTrail() {
        // "Someone did X" is useless if the someone is the person they were pretending to be.
        Impersonation impersonation = policy.resolve(platformAdmin(), "acme", "u-alice");

        assertEquals("root", impersonation.realSubject());
        assertTrue(impersonation.describe().contains("root"));
        assertTrue(impersonation.describe().contains("acme"));
    }

    @Test
    void aTenantOwnerCanActWithinItsOwnTenant() {
        // The "as org admin" case, and it grants nothing they did not already have there.
        Impersonation impersonation = policy.resolve(owner("acme"), "acme", "u-carol");

        assertEquals("acme", impersonation.effective().tenant());
        assertEquals("alice", impersonation.realSubject());
    }

    @Test
    void aTenantOwnerCannotActInSomebodyElsesTenant() {
        assertThrows(ForbiddenException.class, () -> policy.resolve(owner("acme"), "globex", "u-dana"));
    }

    @Test
    void aViewerCannotActAsAnybody() {
        // Impersonation is not a way to gain a role. A viewer has nothing to narrow down from.
        assertThrows(ForbiddenException.class, () -> policy.resolve(viewer("acme"), "acme", "u-carol"));
    }

    @Test
    void aPrincipalWithNoTenantAndNoPlatformRoleCannotActAnywhere() {
        ApusPrincipal stranger = new ApusPrincipal("nobody", null, Set.of(Role.TENANT_OWNER));

        assertThrows(ForbiddenException.class, () -> policy.resolve(stranger, "acme", null));
    }

    @Test
    void actingAsTheTenantItselfKeepsYourOwnName() {
        // "As org admin" rather than as a named person: nothing is gained by inventing a subject,
        // and the audit trail is clearer when the name is the real one.
        Impersonation impersonation = policy.resolve(platformAdmin(), "acme", null);

        assertEquals("root", impersonation.effective().subject());
        assertEquals("acme", impersonation.effective().tenant());
    }

    @Test
    void refusesWithoutATenantToActIn() {
        assertThrows(ForbiddenException.class, () -> policy.resolve(platformAdmin(), null, "u-alice"));
        assertThrows(ForbiddenException.class, () -> policy.resolve(platformAdmin(), "  ", "u-alice"));
    }
}
