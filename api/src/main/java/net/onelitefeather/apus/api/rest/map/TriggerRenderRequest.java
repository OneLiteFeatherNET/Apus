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

import io.micronaut.serde.annotation.Serdeable;

/**
 * Optional request body for {@code POST /api/maps/{id}/render}, mirroring {@code
 * BlueMapRenderSpec#isForce()} ("entspricht {@code --force-render}", design spec §8.5). The
 * request deliberately carries nothing else -- in particular no {@code bundleVersion}: which
 * bundle a render picks up is resolved from the map's source, not supplied by the caller.
 */
@Serdeable
public record TriggerRenderRequest(boolean force) {}
