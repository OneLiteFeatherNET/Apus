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

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import net.onelitefeather.apus.operator.api.Tenant;

/**
 * Which identity-provider group belongs to which tenant, in both directions.
 *
 * <p>This is what turns a token into a tenant. Before it existed, {@code PrincipalResolver} read
 * a claim named {@code organization} that the app registration never emitted -- neither {@code
 * groupMembershipClaims} nor {@code optionalClaims} was configured on it -- so every signed-in
 * user resolved to "no tenant" and the tenant application had nothing to show anyone. A group id
 * is something the provider genuinely puts in a token.
 *
 * <p>It is also the source of {@link DirectoryGuard}'s managed-group set, and that is not a
 * coincidence worth undoing: the groups Apus will act on and the groups Apus recognises members
 * of must be the same set, or one of them would drift into being wider than the other.
 *
 * <p>Immutable. Rebuilt wholesale from the tenant list rather than mutated, so a reader either
 * sees the whole old index or the whole new one.
 */
public final class TenantGroupIndex {

    private final Map<String, String> tenantByGroup;
    private final Map<String, String> groupByTenant;

    private TenantGroupIndex(Map<String, String> tenantByGroup, Map<String, String> groupByTenant) {
        this.tenantByGroup = Map.copyOf(tenantByGroup);
        this.groupByTenant = Map.copyOf(groupByTenant);
    }

    /** An index over no tenants: recognises nobody, manages nothing. */
    public static TenantGroupIndex empty() {
        return new TenantGroupIndex(Map.of(), Map.of());
    }

    /**
     * Builds the index from the tenants that currently exist.
     *
     * <p>Tenants without a configured group are skipped rather than mapped to a blank key: an
     * unconfigured tenant must not become the one every group without a match falls into.
     *
     * <p>If two tenants somehow claim the same group -- which nothing prevents, since the field
     * is free text on a custom resource -- the first by name wins and is stable across restarts.
     * Deliberately not "last wins", which would make membership depend on list ordering, and
     * deliberately not an exception, which would take the API down over a typo in one tenant.
     */
    public static TenantGroupIndex of(Iterable<Tenant> tenants) {
        Map<String, String> tenantByGroup = new LinkedHashMap<>();
        Map<String, String> groupByTenant = new LinkedHashMap<>();
        for (Tenant tenant : tenants) {
            String name = tenant.getMetadata().getName();
            String group = tenant.getSpec().getIdentity().getGroupId();
            if (group == null || group.isBlank()) {
                continue;
            }
            String existing = tenantByGroup.get(group);
            if (existing != null && existing.compareTo(name) <= 0) {
                continue;
            }
            if (existing != null) {
                groupByTenant.remove(existing);
            }
            tenantByGroup.put(group, name);
            groupByTenant.put(name, group);
        }
        return new TenantGroupIndex(tenantByGroup, groupByTenant);
    }

    /**
     * The tenant a signed-in user belongs to, given the groups their token carries.
     *
     * <p>A user in several mapped groups resolves to the alphabetically first tenant, which is at
     * least stable; multi-tenant membership is not a thing this platform models, and picking
     * arbitrarily would make someone's tenant change between requests.
     */
    public Optional<String> tenantForGroups(Iterable<String> groupIds) {
        String best = null;
        for (String groupId : groupIds) {
            String tenant = tenantByGroup.get(groupId);
            if (tenant != null && (best == null || tenant.compareTo(best) < 0)) {
                best = tenant;
            }
        }
        return Optional.ofNullable(best);
    }

    /** The group belonging to a tenant, or empty when it has none configured. */
    public Optional<String> groupForTenant(String tenant) {
        return Optional.ofNullable(groupByTenant.get(tenant));
    }

    /** Every group some tenant claims -- exactly what {@link DirectoryGuard} may act on. */
    public Set<String> managedGroups() {
        return tenantByGroup.keySet();
    }
}
