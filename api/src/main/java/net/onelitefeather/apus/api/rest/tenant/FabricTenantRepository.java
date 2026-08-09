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

import io.fabric8.kubernetes.client.KubernetesClient;
import jakarta.inject.Singleton;
import java.util.List;
import java.util.Optional;
import net.onelitefeather.apus.api.rest.support.RestKubernetesClient;
import net.onelitefeather.apus.operator.api.Tenant;

/** {@link TenantRepository} backed by a real {@link KubernetesClient}. */
@Singleton
public class FabricTenantRepository implements TenantRepository {

    private final KubernetesClient client;

    public FabricTenantRepository(RestKubernetesClient restKubernetesClient) {
        this.client = restKubernetesClient.get();
    }

    @Override
    public List<Tenant> list() {
        return client.resources(Tenant.class).list().getItems();
    }

    @Override
    public Optional<Tenant> findByName(String name) {
        return Optional.ofNullable(client.resources(Tenant.class).withName(name).get());
    }

    @Override
    public Tenant create(Tenant tenant) {
        return client.resource(tenant).create();
    }
}
