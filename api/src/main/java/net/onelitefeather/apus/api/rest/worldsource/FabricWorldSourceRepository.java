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
package net.onelitefeather.apus.api.rest.worldsource;

import io.fabric8.kubernetes.client.KubernetesClient;
import jakarta.inject.Singleton;
import java.util.List;
import java.util.Optional;
import net.onelitefeather.apus.api.rest.support.RestKubernetesClient;
import net.onelitefeather.apus.operator.api.WorldSource;

/** {@link WorldSourceRepository} backed by a real {@link KubernetesClient}. */
@Singleton
public class FabricWorldSourceRepository implements WorldSourceRepository {

    private final KubernetesClient client;

    public FabricWorldSourceRepository(RestKubernetesClient restKubernetesClient) {
        this.client = restKubernetesClient.get();
    }

    @Override
    public List<WorldSource> list(String namespace) {
        return client.resources(WorldSource.class).inNamespace(namespace).list().getItems();
    }

    @Override
    public Optional<WorldSource> find(String namespace, String name) {
        return Optional.ofNullable(
                client.resources(WorldSource.class).inNamespace(namespace).withName(name).get());
    }

    @Override
    public WorldSource create(String namespace, WorldSource source) {
        return client.resources(WorldSource.class).inNamespace(namespace).resource(source).create();
    }
}
