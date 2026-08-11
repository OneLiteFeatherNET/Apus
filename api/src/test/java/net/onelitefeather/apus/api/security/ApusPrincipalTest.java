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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.EnumSet;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ApusPrincipalTest {

    @Test
    void tenantOwnerCanWrite() {
        ApusPrincipal owner = new ApusPrincipal("alice", "acme", Set.of(Role.TENANT_OWNER));
        assertTrue(owner.canWrite());
    }

    @Test
    void tenantOperatorCanWrite() {
        ApusPrincipal operator = new ApusPrincipal("bob", "acme", Set.of(Role.TENANT_OPERATOR));
        assertTrue(operator.canWrite());
    }

    @Test
    void tenantViewerCannotWrite() {
        ApusPrincipal viewer = new ApusPrincipal("carol", "acme", Set.of(Role.TENANT_VIEWER));
        assertFalse(viewer.canWrite());
    }

    @Test
    void principalWithNoRolesCannotWrite() {
        ApusPrincipal noRoles = new ApusPrincipal("dave", "acme", Set.of());
        assertFalse(noRoles.canWrite());
    }

    @Test
    void platformAdminAloneCannotWriteWithinATenant() {
        // canWrite() is specifically "owner or operator" (see the Javadoc on the interface this
        // was built from) -- platform-admin's write access is to platform-level resources
        // (tenants, quotas), never to a tenant's own sources/maps/renders. A platform-admin
        // that also needs to write inside a tenant must hold tenant-owner/-operator too.
        ApusPrincipal admin = new ApusPrincipal("root", "acme", Set.of(Role.PLATFORM_ADMIN));
        assertFalse(admin.canWrite());
        assertTrue(admin.isPlatformAdmin());
    }

    @Test
    void platformAdminIsRecognisedRegardlessOfOtherRolesPresent() {
        ApusPrincipal admin = new ApusPrincipal("root", null, EnumSet.of(Role.PLATFORM_ADMIN, Role.TENANT_VIEWER));
        assertTrue(admin.isPlatformAdmin());
    }

    @Test
    void nonAdminIsNeverReportedAsPlatformAdmin() {
        ApusPrincipal owner = new ApusPrincipal("alice", "acme", Set.of(Role.TENANT_OWNER));
        assertFalse(owner.isPlatformAdmin());
    }

    @Test
    void tenantMayBeAbsentForAPlatformAdmin() {
        // A platform-admin is not necessarily a member of any tenant -- this must construct
        // without complaint. Whether a namespace can be resolved for such a principal is
        // TenantResolver's decision, not this record's.
        ApusPrincipal admin = new ApusPrincipal("root", null, Set.of(Role.PLATFORM_ADMIN));
        assertNull(admin.tenant());
    }

    @Test
    void blankTenantIsNormalizedToNull() {
        // A blank tenant claim is exactly as absent as no claim at all -- there is no
        // distinction an attacker (or a misbehaving broker) could use to sneak past the "no
        // default tenant" rule via an empty-but-present claim.
        ApusPrincipal principal = new ApusPrincipal("alice", "   ", Set.of(Role.TENANT_VIEWER));
        assertNull(principal.tenant());
    }

    @Test
    void subjectMustNotBeNull() {
        assertThrows(NullPointerException.class, () -> new ApusPrincipal(null, "acme", Set.of()));
    }

    @Test
    void rolesMustNotBeNull() {
        assertThrows(NullPointerException.class, () -> new ApusPrincipal("alice", "acme", null));
    }

    @Test
    void rolesAreDefensivelyCopiedAndImmutable() {
        Set<Role> mutable = new HashSet<>(Set.of(Role.TENANT_VIEWER));
        ApusPrincipal principal = new ApusPrincipal("alice", "acme", mutable);

        // Mutating the caller's original set afterwards must not retroactively change what
        // this principal was constructed with.
        mutable.add(Role.PLATFORM_ADMIN);
        assertFalse(principal.isPlatformAdmin());

        assertThrows(UnsupportedOperationException.class, () -> principal.roles().add(Role.PLATFORM_ADMIN));
    }

    @Test
    void equalPrincipalsAreEqual() {
        ApusPrincipal a = new ApusPrincipal("alice", "acme", Set.of(Role.TENANT_VIEWER));
        ApusPrincipal b = new ApusPrincipal("alice", "acme", Set.of(Role.TENANT_VIEWER));
        assertEquals(a, b);
    }
}
