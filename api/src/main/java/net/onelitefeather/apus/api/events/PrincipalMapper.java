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

import io.micronaut.security.authentication.Authentication;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import net.onelitefeather.apus.api.security.ApusPrincipal;
import net.onelitefeather.apus.api.security.Role;

/**
 * Builds an {@link ApusPrincipal} from Micronaut Security's validated {@link Authentication} --
 * the bridge task 1's report flagged as missing (its scope was deliberately only the pure
 * security-invariant classes), to be added "in its own directory ... by whichever task builds
 * the first controller".
 *
 * <p><b>Tenant claim key: {@code organization}.</b> Not fixed anywhere yet at the time of
 * writing (identity broker undecided, design spec §15) -- picked here to match the vocabulary
 * the design spec itself already uses for this exact concept: {@code Tenant.spec.auth
 * .organization} (§8.1) and "der Organisations-Claim im Token bestimmt den Mandanten" (§10.3).
 * Task 2's {@code rest/} controllers need the identical bridge and, built in an isolated
 * worktree in parallel, may pick a different key -- if so, this needs reconciling to one value
 * once both are chosen for real against whichever broker (Keycloak/Zitadel) §15 lands on; see
 * the task 3 report.
 */
final class PrincipalMapper {

    /** See the class Javadoc for why this specific claim name. */
    static final String TENANT_CLAIM = "organization";

    private PrincipalMapper() {}

    static ApusPrincipal from(Authentication authentication) {
        Set<Role> roles = authentication.getRoles().stream()
                .map(Role::fromClaim)
                .flatMap(Optional::stream)
                .collect(Collectors.toUnmodifiableSet());
        Object tenant = authentication.getAttributes().get(TENANT_CLAIM);
        return new ApusPrincipal(authentication.getName(), tenant == null ? null : tenant.toString(), roles);
    }
}
