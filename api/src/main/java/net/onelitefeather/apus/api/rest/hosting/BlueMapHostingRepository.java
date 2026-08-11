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
package net.onelitefeather.apus.api.rest.hosting;

import java.util.List;
import net.onelitefeather.apus.operator.api.BlueMapHosting;

/**
 * Read access to {@link BlueMapHosting} custom resources, always scoped to a single namespace.
 * task-2-brief.md's endpoint table lists only {@code GET /api/hostings} (no by-id lookup, no
 * write), so unlike the other repositories in {@code rest/} this one is list-only. See {@code
 * TenantRepository}'s Javadoc for why this is an interface.
 */
public interface BlueMapHostingRepository {

    List<BlueMapHosting> list(String namespace);
}
