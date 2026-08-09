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

import java.util.List;
import java.util.Optional;
import net.onelitefeather.apus.operator.api.BlueMapMap;

/**
 * Read access to {@link BlueMapMap} custom resources, always scoped to a single namespace. This
 * task does not add write endpoints for maps themselves (only {@code POST
 * /api/maps/{id}/render}, which creates a {@code BlueMapRender}, not a {@code BlueMapMap} --
 * see task-2-brief.md's endpoint table), so unlike the other repositories in {@code rest/} this
 * one has no {@code create}. See {@code TenantRepository}'s Javadoc for why this is an
 * interface.
 */
public interface BlueMapMapRepository {

    List<BlueMapMap> list(String namespace);

    Optional<BlueMapMap> find(String namespace, String name);
}
