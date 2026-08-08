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
package net.onelitefeather.apus.operator.map;

import java.util.LinkedHashMap;
import java.util.Map;
import net.onelitefeather.apus.operator.api.BlueMapMap;

/**
 * Generates the complete BlueMap configuration for a map.
 *
 * <p>Nobody writes HOCON by hand — that is the point of Apus. Credentials are deliberately
 * absent from every generated file: they come from the Rook-managed Secret as environment
 * variables at pod start (the runner's entrypoint from Phase 1 writes them into the
 * configuration then), because a ConfigMap is readable by anything in the namespace.
 */
public final class BlueMapConfigBuilder {

    private BlueMapConfigBuilder() {}

    /** The bucket a map's storage config resolves to, as bound by {@link BucketProvisioner}. */
    public record BucketBinding(String bucketName, String endpoint, String region) {}

    /**
     * Builds every config file BlueMap needs for a single render, keyed by the path it should
     * occupy relative to the BlueMap working directory (e.g. {@code storages/s3.conf}).
     *
     * @return file name → file content, ready to become a ConfigMap
     */
    public static Map<String, String> build(BlueMapMap map, BucketBinding binding) {
        Map<String, String> files = new LinkedHashMap<>();
        String mapId = map.getMetadata().getName();

        // accept-download is mandatory: without it BlueMap refuses to fetch Minecraft
        // resources and every render exits with code 2. Verified against a real render in
        // Phase 1 (spec §9.2) — do not drop this key.
        files.put(
                "core.conf",
                """
                accept-download: true
                data: "/work/data"
                render-thread-count: %d
                metrics: false
                scan-for-mod-resources: false
                """
                        .formatted(renderThreads(map)));

        files.put(
                "maps/" + mapId + ".conf",
                """
                world: "/work/world"
                dimension: "%s"
                name: "%s"
                sorting: 0
                storage: "s3"
                render-edges: true
                """
                        .formatted(map.getSpec().getSource().getDimension(), mapId));

        // No credentials here: the runner's entrypoint fills access-key-id/secret-access-key
        // in from the Rook-managed Secret's environment variables before starting BlueMap.
        files.put(
                "storages/s3.conf",
                """
                storage-type: "themeinerlp:s3"
                bucket-name: "%s"
                region: "%s"
                endpoint-url: "%s"
                compression: "gzip"
                root-path: "%s"
                force-path-style: true
                """
                        .formatted(
                                binding.bucketName(),
                                binding.region(),
                                binding.endpoint(),
                                map.getSpec().getStorage().getPrefix()));

        return files;
    }

    private static int renderThreads(BlueMapMap map) {
        return 2;
    }
}
