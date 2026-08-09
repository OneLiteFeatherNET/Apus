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

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.onelitefeather.apus.operator.api.BlueMapRender;

/** An in-memory, namespace-partitioned {@link BlueMapRenderRepository} fake. See {@code
 * InMemoryTenantRepository}'s Javadoc (in the {@code tenant} package) for why. */
final class InMemoryBlueMapRenderRepository implements BlueMapRenderRepository {

    private final List<Namespaced> items = new ArrayList<>();

    void put(String namespace, BlueMapRender render) {
        items.add(new Namespaced(namespace, render));
    }

    @Override
    public List<BlueMapRender> list(String namespace) {
        return items.stream()
                .filter(item -> item.namespace().equals(namespace))
                .map(Namespaced::resource)
                .toList();
    }

    @Override
    public Optional<BlueMapRender> find(String namespace, String name) {
        return items.stream()
                .filter(item -> item.namespace().equals(namespace)
                        && item.resource().getMetadata().getName().equals(name))
                .map(Namespaced::resource)
                .findFirst();
    }

    @Override
    public BlueMapRender create(String namespace, BlueMapRender render) {
        put(namespace, render);
        return render;
    }

    private record Namespaced(String namespace, BlueMapRender resource) {}
}
