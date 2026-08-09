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
 * Request body for {@code POST /api/uploads/{uploadId}/complete} -- {@code sourceName}, {@code
 * version} and {@code fileName} must match what {@code POST /api/uploads} originally returned
 * (they are what {@link MultipartUploadService#stagingKey} recomputes the object key from); no
 * part list is required, since {@link MultipartUploadService#completeUpload} reads the
 * authoritative part sizes/ETags back from S3 itself via {@code ListParts} rather than trusting
 * anything the caller claims about what it uploaded.
 */
@Serdeable
public record CompleteUploadRequest(String sourceName, String version, String fileName) {}
