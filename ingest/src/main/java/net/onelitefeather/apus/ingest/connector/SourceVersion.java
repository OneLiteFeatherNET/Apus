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
package net.onelitefeather.apus.ingest.connector;

import java.time.Instant;

/**
 * One version of raw world data that a {@link WorldSourceConnector} can fetch: a backup, an
 * object generation, or any other source-specific notion of "a point in time we can pull".
 *
 * @param id source-specific identifier, opaque to callers; passed back into {@link
 *     WorldSourceConnector#fetch} to fetch this exact version again
 * @param label human-readable label for display; may equal {@code id} if the source has nothing
 *     nicer to offer
 * @param createdAt when this version was produced at the source
 * @param sizeBytes total size of the raw payload in bytes, or {@code -1} if the source does not
 *     report it up front
 */
public record SourceVersion(String id, String label, Instant createdAt, long sizeBytes) {}
