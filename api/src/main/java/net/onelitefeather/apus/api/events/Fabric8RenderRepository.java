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

import io.fabric8.kubernetes.api.model.ListOptionsBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.Watch;
import io.fabric8.kubernetes.client.Watcher;
import jakarta.inject.Singleton;
import java.util.Optional;
import net.onelitefeather.apus.operator.api.BlueMapRender;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Thin {@link RenderRepository} adapter over the fabric8 {@link KubernetesClient}. */
@Singleton
final class Fabric8RenderRepository implements RenderRepository {

    private static final Logger LOGGER = LoggerFactory.getLogger(Fabric8RenderRepository.class);

    private final KubernetesClient client;

    Fabric8RenderRepository(KubernetesClient client) {
        this.client = client;
    }

    @Override
    public Optional<BlueMapRender> find(String namespace, String name) {
        return Optional.ofNullable(
                client.resources(BlueMapRender.class).inNamespace(namespace).withName(name).get());
    }

    @Override
    public Watch watch(String namespace, String name, String resourceVersion, Watcher<BlueMapRender> watcher) {
        LOGGER.debug("watching BlueMapRender '{}' in namespace '{}' from resourceVersion {}", name, namespace, resourceVersion);
        return client.resources(BlueMapRender.class)
                .inNamespace(namespace)
                .withName(name)
                .watch(new ListOptionsBuilder().withResourceVersion(resourceVersion).build(), watcher);
    }
}
