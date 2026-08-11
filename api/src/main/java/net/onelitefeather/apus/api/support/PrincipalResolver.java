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
 * (design spec §15), which had not happened yet. That is also why this bridge is not part of
 * the {@code security} package alongside {@link ApusPrincipal}/{@link
 * net.onelitefeather.apus.api.security.TenantResolver}: task 1's scope there was deliberately
 * only the pure security-invariant classes, not the Micronaut Security-specific translation.
 *
 * <p><b>Phase 5a consolidation:</b> task 2 ({@code rest/}) and task 3 ({@code events/}) each
 * built this exact bridge independently in their own parallel worktree -- {@code
 * rest.support.PrincipalResolver} and {@code events.PrincipalMapper} -- and picked two
 * <em>different</em> tenant claim names ({@code "org"} vs. {@code "organization"}). That
 * divergence was the dangerous half of the duplication: every controller under {@code rest/}
 * and the SSE endpoints under {@code events/} would have resolved the very same token's tenant
 * differently depending on which package happened to handle the request. This class merges both
 * into the one place every controller and SSE endpoint in this module now goes through.
 *
 * <p><b>Tenant claim key: {@code organization}.</b> Not fixed anywhere else yet at the time of
 * writing (identity broker undecided, design spec §15) -- picked to match the vocabulary the
 * design spec itself already uses for this exact concept: {@code Tenant.spec.auth.organization}
 * (§8.1's example manifest) and "der Organisations-Claim im Token bestimmt den Mandanten"
 * (§10.3). This is the single place that constant is declared; nowhere else in this module may
 * duplicate the literal.
 */
@Singleton
public class PrincipalResolver {

    /** See the class Javadoc for why this specific claim name. */
    public static final String TENANT_CLAIM = "organization";

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
