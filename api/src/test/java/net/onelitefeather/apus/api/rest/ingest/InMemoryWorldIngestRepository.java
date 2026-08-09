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
package net.onelitefeather.apus.api.rest.ingest;

import java.util.ArrayList;
import java.util.List;
import net.onelitefeather.apus.operator.api.WorldIngest;

/**
 * An in-memory, namespace-partitioned {@link WorldIngestRepository} fake -- emulates Kubernetes'
 * {@code generateName} behaviour (a unique {@code name} is assigned on {@link #create} whenever
 * only {@code generateName} was set) closely enough for {@code PushControllerTest} to assert on
 * the created resources without a real or mocked cluster. Public (unlike most of this module's
 * in-memory fakes) because {@code net.onelitefeather.apus.api.rest.push.PushControllerTest} is in
 * a different package and needs it too.
 */
public final class InMemoryWorldIngestRepository implements WorldIngestRepository {

    private final List<Namespaced> items = new ArrayList<>();
    private int counter = 0;

    @Override
    public WorldIngest create(String namespace, WorldIngest ingest) {
        if (ingest.getMetadata().getName() == null) {
            String generateName = ingest.getMetadata().getGenerateName();
            ingest.getMetadata().setName((generateName == null ? "ingest-" : generateName) + (counter++));
        }
        items.add(new Namespaced(namespace, ingest));
        return ingest;
    }

    public List<WorldIngest> forNamespace(String namespace) {
        return items.stream()
                .filter(item -> item.namespace().equals(namespace))
                .map(Namespaced::resource)
                .toList();
    }

    private record Namespaced(String namespace, WorldIngest resource) {}
}
