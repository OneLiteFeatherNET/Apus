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

import java.util.List;
import java.util.Optional;
import net.onelitefeather.apus.operator.api.BlueMapRender;

/**
 * Read/write access to {@link BlueMapRender} custom resources, always scoped to a single
 * namespace. Also used by {@code net.onelitefeather.apus.api.rest.map.BlueMapMapController} to
 * create the render {@code POST /api/maps/{id}/render} triggers -- a render is its own resource
 * kind (design spec §8.5), so creating one belongs here rather than being duplicated into the
 * map package. See {@code TenantRepository}'s Javadoc for why this is an interface.
 */
public interface BlueMapRenderRepository {

    List<BlueMapRender> list(String namespace);

    Optional<BlueMapRender> find(String namespace, String name);

    BlueMapRender create(String namespace, BlueMapRender render);
}
