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

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Fetches raw Minecraft world data from one specific kind of source into a local work directory.
 *
 * <p>A connector's responsibility ends at "raw bytes under {@code workDir}". It never interprets
 * the resulting directory structure -- that is the layout detector's job -- and never writes a
 * bundle -- that is the bundle writer's job. Keeping connectors this narrow means a new source
 * type costs exactly these two methods.
 */
public interface WorldSourceConnector {

    /** The {@code WorldSourceSpec.type} value this connector handles, e.g. {@code "s3"}. */
    String type();

    /**
     * Lists versions available at the source. Pull sources (S3, Pterodactyl) report every version
     * they can currently see; push sources have nothing to discover on their own and return an
     * empty list.
     *
     * @param config source-specific connection details (endpoint, credentials, ...); the set of
     *     recognised keys is defined by each implementation
     */
    List<SourceVersion> discover(Map<String, String> config);

    /**
     * Fetches the raw data for one version into {@code workDir}. Only raw bytes are placed on
     * disk here -- no layout interpretation happens in a connector.
     *
     * @param config source-specific connection details (endpoint, credentials, ...); the set of
     *     recognised keys is defined by each implementation
     * @param version the version to fetch, as previously returned by {@link #discover}
     * @param workDir an existing, writable directory to fetch into
     */
    void fetch(Map<String, String> config, SourceVersion version, Path workDir);
}
