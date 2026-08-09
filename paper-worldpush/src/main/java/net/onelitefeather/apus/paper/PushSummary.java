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
package net.onelitefeather.apus.paper;

/**
 * What one completed push cycle reports to the Apus API, so {@code POST /api/push/{token}} has
 * enough context to create a {@code WorldIngest} plus log/display without a round-trip back to
 * this server.
 *
 * <p>{@code sourceName} and {@code version} are exactly the two fields {@code
 * PushReportRequest} (module {@code api}, package {@code
 * net.onelitefeather.apus.api.rest.push}) deserializes the request body into -- {@link
 * HttpPushNotifier} sends only those two on the wire. {@code worldName}/{@code fileCount}/{@code
 * bytesUploaded} are not part of that contract (the API already knows the world name from the
 * target {@code WorldSource}'s own configured worlds, and file/byte counts are this plugin's own
 * telemetry, not the API's concern); they stay on this record purely so a {@link PushNotifier}
 * implementation can log/display them locally without a second parameter list.
 *
 * @param sourceName the target {@code push}-type {@code WorldSource}'s name, from {@code
 *     WorldPushConfig#sourceName()} -- becomes {@code PushReportRequest.sourceName()}
 * @param version this push cycle's identifier -- becomes {@code PushReportRequest.version()} and,
 *     from there, {@code WorldIngest.spec.sourceVersion}
 */
public record PushSummary(String sourceName, String version, String worldName, int fileCount, long bytesUploaded) {}
