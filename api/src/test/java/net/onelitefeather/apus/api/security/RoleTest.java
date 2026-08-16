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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class RoleTest {

    @ParameterizedTest
    @CsvSource({
        "platform-admin, PLATFORM_ADMIN",
        "tenant-owner, TENANT_OWNER",
        "tenant-operator, TENANT_OPERATOR",
        "tenant-viewer, TENANT_VIEWER",
        // Case-insensitive, exactly the four spec §10.3 role names -- nothing more is invented.
        "Tenant-Viewer, TENANT_VIEWER",
    })
    void fromClaimParsesTheFourSpecRoles(String claim, Role expected) {
        assertEquals(Optional.of(expected), Role.fromClaim(claim));
    }

    @ParameterizedTest
    @ValueSource(strings = {"admin", "owner", "platform_admin", "tenant-manager", "platform-administrator"})
    void fromClaimRejectsUnknownRoleNames(String claim) {
        // An unrecognised role string never silently maps onto one of the four real roles --
        // near-miss spellings ("tenant-manager") and separator variants ("platform_admin") must
        // not accidentally grant a role nobody issued.
        assertTrue(Role.fromClaim(claim).isEmpty(), claim + " must not resolve to a Role");
    }

    @Test
    void fromClaimTrimsSurroundingWhitespace() {
        assertEquals(Optional.of(Role.PLATFORM_ADMIN), Role.fromClaim("  platform-admin  "));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   "})
    void fromClaimRejectsNullBlankAndEmpty(String claim) {
        assertTrue(Role.fromClaim(claim).isEmpty());
    }

    /**
     * Every role must survive a round trip, for every role there is. Impersonation builds an
     * authentication of its own and spells roles with {@code claimValue()}; if one of them did
     * not read back, that session would silently hold fewer roles than it was granted -- and the
     * failure would look like a permission problem, not like a spelling one.
     */
    @ParameterizedTest
    @EnumSource(Role.class)
    void claimValueIsTheExactInverseOfFromClaim(Role role) {
        assertEquals(Optional.of(role), Role.fromClaim(role.claimValue()));
    }
}
