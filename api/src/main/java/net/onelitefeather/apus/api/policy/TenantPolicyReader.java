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
package net.onelitefeather.apus.api.policy;

import jakarta.inject.Singleton;
import java.util.List;
import net.onelitefeather.apus.api.rest.tenant.TenantRepository;
import net.onelitefeather.apus.api.security.ApusPrincipal;

/**
 * The one place that turns a caller into that caller's policy.
 *
 * <p>The tenant is taken from the token and from nowhere else, exactly as every other
 * tenant-scoped read in this module does — there is no parameter here for a caller to point at
 * somebody else's policy.
 *
 * <p>A principal with no tenant gets an empty list rather than an error: a platform admin
 * browsing the tenant application is an ordinary visitor, not a fault, and "no tenant" and "no
 * policy" mean the same thing to every reader downstream — unregulated.
 */
@Singleton
public class TenantPolicyReader {

    private final TenantRepository repository;

    public TenantPolicyReader(TenantRepository repository) {
        this.repository = repository;
    }

    public List<PolicyEntryView> forPrincipal(ApusPrincipal principal) {
        if (principal == null || principal.tenant() == null || principal.tenant().isBlank()) {
            return List.of();
        }
        return repository
                .findByName(principal.tenant())
                .map(tenant -> tenant.getSpec().getPolicy().stream()
                        .map(entry -> new PolicyEntryView(
                                entry.getKey(), entry.getType(), entry.getValue(), entry.isLocked()))
                        .toList())
                .orElse(List.of());
    }
}
