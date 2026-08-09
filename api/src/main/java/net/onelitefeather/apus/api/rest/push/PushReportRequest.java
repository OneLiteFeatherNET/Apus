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
package net.onelitefeather.apus.api.rest.push;

import io.micronaut.serde.annotation.Serdeable;

/**
 * Request body for {@code POST /api/push/{token}}. Deliberately carries no tenant/namespace field
 * -- that comes only from the token (see {@code PushTokenRepository}'s Javadoc). {@code
 * sourceName} names one of the token's own tenant's {@code WorldSource} resources (of type {@code
 * push}) -- a tenant may run more than one Paper server/world, each pushing to a different
 * source, so the token alone (tenant-bound, not source-bound, design spec §10.3) is not enough to
 * pick one. {@code version} is the identifier the plugin used as the staged object's key suffix
 * when it wrote the data directly to S3 -- becomes {@code WorldIngest.spec.sourceVersion}, and
 * from there the {@code SourceVersion.id()} the ingest job's {@code PushSourceConnector} (module
 * {@code ingest}) fetches by.
 */
@Serdeable
public record PushReportRequest(String sourceName, String version) {}
