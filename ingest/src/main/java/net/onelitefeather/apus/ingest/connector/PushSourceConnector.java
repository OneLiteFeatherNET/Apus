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

/**
 * A push source: the Paper server plugin ({@code paper-worldpush}) writes its world data
 * directly into a staging prefix in S3 using its own tenant-scoped credentials, then calls
 * {@code POST /api/push/{token}} to report completion, which creates the {@code WorldIngest}
 * this connector's {@link #fetch} eventually runs for.
 *
 * <p>All behaviour lives in {@link AbstractStagedSourceConnector}; this class only supplies the
 * {@code WorldSourceSpec.type} discriminator.
 */
public final class PushSourceConnector extends AbstractStagedSourceConnector {

    @Override
    public String type() {
        return "push";
    }
}
