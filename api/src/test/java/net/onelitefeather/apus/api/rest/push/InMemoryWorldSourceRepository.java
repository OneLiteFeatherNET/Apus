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
package net.onelitefeather.apus.api.rest.push;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.onelitefeather.apus.api.rest.worldsource.WorldSourceRepository;
import net.onelitefeather.apus.operator.api.WorldSource;

/**
 * An in-memory, namespace-partitioned {@link WorldSourceRepository} fake for {@code
 * PushControllerTest} -- a separate, local copy of the equivalent fake in the {@code
 * worldsource} test package rather than reusing it, since that one is package-private there.
 */
final class InMemoryWorldSourceRepository implements WorldSourceRepository {

    private final List<Namespaced> items = new ArrayList<>();

    void put(String namespace, WorldSource source) {
        items.add(new Namespaced(namespace, source));
    }

    @Override
    public List<WorldSource> list(String namespace) {
        return items.stream()
                .filter(item -> item.namespace().equals(namespace))
                .map(Namespaced::resource)
                .toList();
    }

    @Override
    public Optional<WorldSource> find(String namespace, String name) {
        return items.stream()
                .filter(item -> item.namespace().equals(namespace)
                        && item.resource().getMetadata().getName().equals(name))
                .map(Namespaced::resource)
                .findFirst();
    }

    @Override
    public WorldSource create(String namespace, WorldSource source) {
        put(namespace, source);
        return source;
    }

    private record Namespaced(String namespace, WorldSource resource) {}
}
