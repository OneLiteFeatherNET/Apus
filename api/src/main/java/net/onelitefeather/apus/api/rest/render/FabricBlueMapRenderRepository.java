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
package net.onelitefeather.apus.api.rest.render;

import io.fabric8.kubernetes.client.KubernetesClient;
import jakarta.inject.Singleton;
import java.util.List;
import java.util.Optional;
import net.onelitefeather.apus.api.rest.support.RestKubernetesClient;
import net.onelitefeather.apus.operator.api.BlueMapRender;

/** {@link BlueMapRenderRepository} backed by a real {@link KubernetesClient}. */
@Singleton
public class FabricBlueMapRenderRepository implements BlueMapRenderRepository {

    private final KubernetesClient client;

    public FabricBlueMapRenderRepository(RestKubernetesClient restKubernetesClient) {
        this.client = restKubernetesClient.get();
    }

    @Override
    public List<BlueMapRender> list(String namespace) {
        return client.resources(BlueMapRender.class).inNamespace(namespace).list().getItems();
    }

    @Override
    public Optional<BlueMapRender> find(String namespace, String name) {
        return Optional.ofNullable(
                client.resources(BlueMapRender.class).inNamespace(namespace).withName(name).get());
    }

    @Override
    public BlueMapRender create(String namespace, BlueMapRender render) {
        return client.resources(BlueMapRender.class).inNamespace(namespace).resource(render).create();
    }
}
