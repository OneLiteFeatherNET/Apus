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

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import net.onelitefeather.apus.api.security.ApusPrincipal;
import net.onelitefeather.apus.api.security.ForbiddenException;
import net.onelitefeather.apus.api.security.Role;
import org.junit.jupiter.api.Test;

/**
 * Written from the attacker's side. The Graph permissions behind these operations are
 * directory-wide -- Entra offers no "these groups only" variant of {@code Group.ReadWrite.All} --
 * so this guard is the only thing between a bug in a controller and every account in the
 * organisation. Each test names what someone would be trying to do, not what the happy path is.
 */
class DirectoryGuardTest {

    private static final String ACME_GROUP = "11111111-1111-1111-1111-111111111111";
    private static final String GLOBEX_GROUP = "22222222-2222-2222-2222-222222222222";
    private static final String UNCLAIMED_GROUP = "99999999-9999-9999-9999-999999999999";

    private final DirectoryGuard guard = new DirectoryGuard();

    private static ApusPrincipal owner(String tenant) {
        return new ApusPrincipal("alice", tenant, Set.of(Role.TENANT_OWNER));
    }

    private static ApusPrincipal platformAdmin() {
        return new ApusPrincipal("root", null, Set.of(Role.PLATFORM_ADMIN));
    }

    private static ApusPrincipal viewer(String tenant) {
        return new ApusPrincipal("bob", tenant, Set.of(Role.TENANT_VIEWER));
    }

    // --- groups Apus has no business touching -------------------------------------------------

    @Test
    void refusesAGroupNoTenantClaims() {
        // The single most important rule here. A group nobody named in a Tenant is not Apus's to
        // read, rename or add anyone to -- and with a directory-wide permission, "not ours" is
        // otherwise indistinguishable from "ours".
        assertThrows(ForbiddenException.class, () -> guard.requireManagedGroup(platformAdmin(), UNCLAIMED_GROUP));
    }

    @Test
    void refusesAnEmptyOrNullGroup() {
        // An unconfigured tenant must not become the widest one on the platform.
        assertThrows(ForbiddenException.class, () -> guard.requireManagedGroup(platformAdmin(), null));
        assertThrows(ForbiddenException.class, () -> guard.requireManagedGroup(platformAdmin(), "  "));
    }

    @Test
    void allowsAGroupThatATenantClaims() {
        guard.setManagedGroups(Set.of(ACME_GROUP, GLOBEX_GROUP));

        assertDoesNotThrow(() -> guard.requireManagedGroup(platformAdmin(), ACME_GROUP));
    }

    // --- one tenant reaching into another ------------------------------------------------------

    @Test
    void refusesATenantOwnerReachingIntoAnotherTenantsGroup() {
        guard.setManagedGroups(Set.of(ACME_GROUP, GLOBEX_GROUP));

        assertThrows(
                ForbiddenException.class, () -> guard.requireTenantAccess(owner("acme"), "globex", GLOBEX_GROUP));
    }

    @Test
    void allowsATenantOwnerWithinItsOwnTenant() {
        guard.setManagedGroups(Set.of(ACME_GROUP));

        assertDoesNotThrow(() -> guard.requireTenantAccess(owner("acme"), "acme", ACME_GROUP));
    }

    @Test
    void allowsAPlatformAdminAnywhereAmongManagedGroups() {
        guard.setManagedGroups(Set.of(ACME_GROUP, GLOBEX_GROUP));

        assertDoesNotThrow(() -> guard.requireTenantAccess(platformAdmin(), "globex", GLOBEX_GROUP));
    }

    @Test
    void refusesAViewerEvenInsideItsOwnTenant() {
        // Reading is one thing; these endpoints change the directory. A viewer has no business
        // creating teams or resetting anyone's password.
        guard.setManagedGroups(Set.of(ACME_GROUP));

        assertThrows(ForbiddenException.class, () -> guard.requireTenantWrite(viewer("acme"), "acme", ACME_GROUP));
    }

    @Test
    void refusesAPrincipalWithNoTenantAndNoPlatformRole() {
        guard.setManagedGroups(Set.of(ACME_GROUP));
        ApusPrincipal stranger = new ApusPrincipal("nobody", null, Set.of(Role.TENANT_OWNER));

        assertThrows(ForbiddenException.class, () -> guard.requireTenantAccess(stranger, "acme", ACME_GROUP));
    }

    // --- password reset, where the damage is worst ---------------------------------------------

    @Test
    void refusesResettingThePasswordOfAGlobalAdministrator() {
        // The escalation this whole design exists to prevent: an Apus tenant-owner taking over a
        // directory administrator by resetting their password through a console button.
        guard.setManagedGroups(Set.of(ACME_GROUP));
        DirectoryUser admin =
                new DirectoryUser("u-admin", "Root", "root@example.net", Set.of("Global Administrator"));

        ForbiddenException thrown = assertThrows(
                ForbiddenException.class, () -> guard.requirePasswordResetAllowed(owner("acme"), admin));
        assertTrue(thrown.getMessage().toLowerCase().contains("privileged"));
    }

    @Test
    void refusesResettingAnyPrivilegedRoleNotJustGlobalAdministrator() {
        guard.setManagedGroups(Set.of(ACME_GROUP));
        DirectoryUser helpdesk =
                new DirectoryUser("u-help", "Helpdesk", "help@example.net", Set.of("User Administrator"));

        assertThrows(ForbiddenException.class, () -> guard.requirePasswordResetAllowed(owner("acme"), helpdesk));
    }

    @Test
    void refusesResettingYourOwnPassword() {
        // Not an escalation, but not what this permission was granted for either: a self-service
        // password change goes through the identity provider, where it is challenged properly.
        guard.setManagedGroups(Set.of(ACME_GROUP));
        DirectoryUser self = DirectoryUser.member("alice", "Alice", "alice@example.net");

        assertThrows(ForbiddenException.class, () -> guard.requirePasswordResetAllowed(owner("acme"), self));
    }

    @Test
    void allowsResettingAnOrdinaryMember() {
        guard.setManagedGroups(Set.of(ACME_GROUP));
        DirectoryUser member = DirectoryUser.member("u-1", "Carol", "carol@example.net");

        assertDoesNotThrow(() -> guard.requirePasswordResetAllowed(owner("acme"), member));
    }

    // --- the default, which must be closed ------------------------------------------------------

    @Test
    void managesNothingUntilToldOtherwise() {
        // A guard that has not been given the managed-group set yet must refuse everything, not
        // permit everything. This is the state it is in for the first moments after startup, and
        // the state it stays in if the tenant index ever fails to load.
        assertThrows(ForbiddenException.class, () -> guard.requireManagedGroup(platformAdmin(), ACME_GROUP));
    }
}
