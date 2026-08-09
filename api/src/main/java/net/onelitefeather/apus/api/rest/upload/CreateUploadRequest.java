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
package net.onelitefeather.apus.api.rest.upload;

import io.micronaut.serde.annotation.Serdeable;

/**
 * Request body for {@code POST /api/uploads}. {@code sourceName} names one of the caller's own
 * tenant's {@code WorldSource} resources (of type {@code upload}) -- resolved against the
 * caller's namespace by the controller, exactly like every other write in this module (design
 * spec §10.3); this request carries no namespace/tenant field of its own.
 */
@Serdeable
public record CreateUploadRequest(String sourceName, String fileName, long sizeBytes) {}
