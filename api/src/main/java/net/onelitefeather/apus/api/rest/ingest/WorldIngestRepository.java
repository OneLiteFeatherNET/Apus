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

import net.onelitefeather.apus.operator.api.WorldIngest;

/**
 * Write access to {@link WorldIngest} custom resources, always scoped to a single namespace --
 * the caller never gets to pick which one. Used by {@code
 * net.onelitefeather.apus.api.rest.push.PushController} to create the {@link WorldIngest}(s) a
 * {@code POST /api/push/{token}} report triggers (design spec §6.4, §11.1): a push source's
 * {@code WorldIngest} is created directly by that HTTP call rather than by a poll/reconcile loop,
 * exactly the "same code path" §6.4 describes pull and push sources converging on -- just
 * triggered from a different place. An interface so controller tests can supply an in-memory
 * fake; see {@code net.onelitefeather.apus.api.rest.tenant.TenantRepository}'s Javadoc for why.
 */
public interface WorldIngestRepository {

    WorldIngest create(String namespace, WorldIngest ingest);
}
