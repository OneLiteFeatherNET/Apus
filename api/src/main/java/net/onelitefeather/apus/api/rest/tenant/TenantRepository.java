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

import java.util.List;
import java.util.Optional;
import net.onelitefeather.apus.operator.api.Tenant;

/**
 * Read/write access to {@link Tenant} custom resources. {@code Tenant} is cluster-scoped (design
 * spec §8.1), so unlike every other repository in {@code rest/} this one carries no namespace
 * parameter -- there is deliberately no per-tenant filtering here, because {@link
 * net.onelitefeather.apus.api.rest.tenant.TenantController} only reaches this repository once it
 * has already confirmed the caller is a {@code platform-admin} with cluster-wide reach (design
 * spec §10.3).
 *
 * <p>An interface, not a concrete fabric8-backed class directly, so controller tests can supply
 * an in-memory fake instead of needing a live or mocked Kubernetes API server -- neither
 * {@code kubernetes-server-mock} nor {@code micronaut-test-junit5} is on this module's test
 * classpath (see task-1-report.md's "Concerns" section on the missing dependencies this task
 * would otherwise need).
 */
public interface TenantRepository {

    List<Tenant> list();

    Optional<Tenant> findByName(String name);

    Tenant create(Tenant tenant);

    /**
     * Persists changes to an already-existing {@link Tenant} (design spec §10.3: {@code
     * platform-admin} may "Tenants anlegen/ändern/löschen, Quotas"). {@code tenant} must be one
     * previously returned by {@link #findByName(String)} (or {@link #list()}) with its fields
     * mutated -- this method does not create a new resource if the name does not already exist.
     */
    Tenant update(Tenant tenant);
}
