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

import io.micronaut.security.authentication.Authentication;
import jakarta.inject.Singleton;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import net.onelitefeather.apus.api.security.ApusPrincipal;
import net.onelitefeather.apus.api.security.Role;

/**
 * Bridges Micronaut Security's validated {@link Authentication} to this module's own {@link
 * ApusPrincipal}. Task 1 (see its report, "Concerns for Task 2 / Task 3") deliberately left this
 * bridge unbuilt: it depends on details -- which claim carries the tenant, whether roles arrive
 * as a flat list or something richer -- that are downstream of picking an identity broker
 * (design spec §15), which had not happened yet. This is the first controller-facing code to
 * need it, so it lands here.
 *
 * <p>Every controller in {@code rest/} goes through this class instead of reading {@link
 * Authentication} itself, so there is exactly one place to update once a broker is chosen and
 * the placeholder claim key below turns out to be wrong.
 */
@Singleton
public class PrincipalResolver {

    /**
     * The organisation/tenant claim key read from {@link Authentication#getAttributes()}.
     *
     * <p><b>This is a placeholder, not a confirmed contract.</b> Design spec §15 leaves the
     * choice between Keycloak (26+, with Organizations) and Zitadel open, and with it the exact
     * claim name each would put the caller's organisation under -- Keycloak's Organizations
     * feature and Zitadel's org model do not share a claim name. "org" is short, broker-neutral,
     * and easy to grep for; whoever wires the chosen broker's token mapping into this module
     * should update this single constant rather than hunt for scattered literals.
     *
     * <p>Public (not package-private) so every controller test across {@code rest/}'s
     * sub-packages can build a realistic {@link Authentication} against the same claim key
     * this class actually reads, instead of duplicating the literal "org" in each test package.
     */
    public static final String TENANT_CLAIM = "org";

    /**
     * @param authentication the token-derived authentication Micronaut Security already
     *     validated (signature, issuer) before this method ever sees it
     * @return the equivalent {@link ApusPrincipal}, with unrecognised role claims silently
     *     dropped (see {@link Role#fromClaim(String)}) and a missing/non-string tenant claim
     *     mapped to {@code null} -- never to a default tenant
     */
    public ApusPrincipal resolve(Authentication authentication) {
        Objects.requireNonNull(authentication, "authentication must not be null");

        Set<Role> roles = new LinkedHashSet<>();
        for (String rawRole : authentication.getRoles()) {
            Role.fromClaim(rawRole).ifPresent(roles::add);
        }

        Object tenantClaim = authentication.getAttributes().get(TENANT_CLAIM);
        String tenant = tenantClaim instanceof String value ? value : null;

        return new ApusPrincipal(authentication.getName(), tenant, roles);
    }
}
