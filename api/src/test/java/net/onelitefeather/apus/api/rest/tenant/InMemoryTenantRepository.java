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
package net.onelitefeather.apus.api.rest.tenant;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.onelitefeather.apus.operator.api.Tenant;

/**
 * An in-memory {@link TenantRepository} fake for controller tests. Standing in for {@code
 * kubernetes-server-mock}/{@code micronaut-test-junit5}, neither of which is on this module's
 * test classpath (task-1-report.md's "Concerns" section) -- see {@code TenantRepository}'s
 * Javadoc for why the repository is an interface in the first place.
 *
 * <p>Public (not package-private): {@code BlueMapRenderControllerTest} (in the sibling {@code
 * rest.render} test package) also needs a {@code TenantRepository} fake for {@code
 * GET /api/renders/cluster}'s tests, and this is the one already exercised by {@code
 * TenantControllerTest} -- reusing it keeps there from being two divergent in-memory fakes for
 * the same interface.
 */
public final class InMemoryTenantRepository implements TenantRepository {

    private final Map<String, Tenant> byName = new LinkedHashMap<>();

    public void put(Tenant tenant) {
        byName.put(tenant.getMetadata().getName(), tenant);
    }

    @Override
    public List<Tenant> list() {
        return List.copyOf(byName.values());
    }

    @Override
    public Optional<Tenant> findByName(String name) {
        return Optional.ofNullable(byName.get(name));
    }

    @Override
    public Tenant create(Tenant tenant) {
        put(tenant);
        return tenant;
    }

    @Override
    public Tenant update(Tenant tenant) {
        put(tenant);
        return tenant;
    }
}
