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

import java.util.List;
import java.util.Optional;
import net.onelitefeather.apus.operator.api.WorldSource;

/**
 * Read/write access to {@link WorldSource} custom resources, always scoped to a single
 * namespace -- the caller never gets to pick which one (see {@code TenantResolver}). An
 * interface so controller tests can supply an in-memory fake; see {@link
 * net.onelitefeather.apus.api.rest.tenant.TenantRepository}'s Javadoc for why.
 */
public interface WorldSourceRepository {

    List<WorldSource> list(String namespace);

    Optional<WorldSource> find(String namespace, String name);

    WorldSource create(String namespace, WorldSource source);
}
