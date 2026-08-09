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
package net.onelitefeather.apus.api.rest.map;

import io.micronaut.context.annotation.Replaces;
import io.micronaut.context.annotation.Requires;
import jakarta.inject.Singleton;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.onelitefeather.apus.operator.api.BlueMapMap;

/**
 * An in-memory, namespace-partitioned {@link BlueMapMapRepository}, wired into the Micronaut
 * context in place of {@link FabricBlueMapMapRepository} only under the {@code apitest}
 * environment ({@link BlueMapMapControllerHttpTest}) -- the HTTP-level 401/403/404 tests exercise
 * the real embedded server and security filter chain, but do not need a real Kubernetes API
 * server behind it; that real-cluster proof is {@code TenantIsolationIntegrationTest}'s job
 * instead (environment {@code k3s}), which leaves this bean unreplaced so its repositories stay
 * the real, cluster-backed ones. See {@code InMemoryBlueMapMapRepository} in this same package
 * for the equivalent non-DI fake the direct-call controller tests use.
 */
@Singleton
@Requires(env = "apitest")
@Replaces(FabricBlueMapMapRepository.class)
public class TestBlueMapMapRepository implements BlueMapMapRepository {

    private final List<Namespaced> items = new ArrayList<>();

    public void put(String namespace, BlueMapMap map) {
        items.add(new Namespaced(namespace, map));
    }

    public void clear() {
        items.clear();
    }

    @Override
    public List<BlueMapMap> list(String namespace) {
        return items.stream()
                .filter(item -> item.namespace().equals(namespace))
                .map(Namespaced::resource)
                .toList();
    }

    @Override
    public Optional<BlueMapMap> find(String namespace, String name) {
        return items.stream()
                .filter(item -> item.namespace().equals(namespace)
                        && item.resource().getMetadata().getName().equals(name))
                .map(Namespaced::resource)
                .findFirst();
    }

    private record Namespaced(String namespace, BlueMapMap resource) {}
}
