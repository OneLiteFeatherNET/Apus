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
import java.util.List;
import java.util.Map;
import net.onelitefeather.apus.operator.api.BlueMapMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Generates the complete BlueMap configuration for a map.
 *
 * <p>Nobody writes HOCON by hand — that is the point of Apus. Credentials are deliberately
 * absent from every generated file: they come from the Rook-managed Secret as environment
 * variables at pod start (the runner's entrypoint from Phase 1 writes them into the
 * configuration then), because a ConfigMap is readable by anything in the namespace.
 *
 * <p><b>Not wired into any reconciler yet — this is intentional, not an oversight.</b> The
 * Phase 1 runner image is configured exclusively through environment variables (design spec
 * §7.4, verified against a real render), so {@link
 * net.onelitefeather.apus.operator.render.RenderJobBuilder} never mounts a ConfigMap and never
 * calls this class. It exists for Phase 3 ({@code BlueMapHosting}): the long-running webserver
 * pod that serves already-rendered maps needs a full {@code webserver.conf} and the storage
 * config this class builds, and that surface is not covered by the render env-var contract.
 * Do not delete this class as dead code — it is future-phase code, staged ahead of its wiring.
 *
 * <p><b>{@code webserver.conf} format, verified against the real file.</b> Running the BlueMap
 * CLI ({@code apus/runner:dev}'s {@code /opt/bluemap/cli.jar}, BlueMap 5.23) with {@code -c} on
 * an empty config folder and no action flag writes every default config file, including {@code
 * webserver.conf}. That generated file has no bind-address/{@code ip} setting at all — only
 * {@code enabled}, {@code webroot}, {@code port}, {@code sse-enabled}, and an optional {@code
 * log} block. The webserver always listens on all interfaces; there is no key to restrict it to
 * localhost, so {@link #buildForHosting} does not emit one either, and instead documents this in
 * a comment in the generated file.
 */
public final class BlueMapConfigBuilder {

    private static final Logger LOGGER = LoggerFactory.getLogger(BlueMapConfigBuilder.class);

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

    /**
     * Builds every config file a hosting webserver needs to display several maps at once, keyed
     * by the path it should occupy relative to the BlueMap working directory.
     *
     * <p>Unlike {@link #build}, which renders exactly one map from local world data, this method
     * never emits a {@code world}/{@code dimension} key in a map's config: per BlueMap's own
     * documentation for that setting, omitting it means "the map will be only registered to the
     * webserver and the webapp but not rendered or loaded by BlueMap" -- used to display a map
     * that has already been rendered somewhere else, exactly the hosting pod's job.
     *
     * <p>{@code maps} and {@code bindings} are matched positionally: {@code bindings.get(i)} is
     * the bucket backing {@code maps.get(i)}. Each map gets its own {@code maps/<id>.conf} and
     * its own {@code storages/<id>.conf} (not a single shared {@code storages/s3.conf} like
     * {@link #build}), because different maps can live in different buckets.
     *
     * @return file name → file content, ready to become a ConfigMap
     */
    public static Map<String, String> buildForHosting(
            List<BlueMapMap> maps, List<BucketBinding> bindings, int webserverPort) {
        if (maps.size() != bindings.size()) {
            throw new IllegalArgumentException("maps and bindings must be matched positionally, got %d maps and %d bindings"
                    .formatted(maps.size(), bindings.size()));
        }

        Map<String, String> files = new LinkedHashMap<>();

        files.put("webserver.conf", webserverConfig(webserverPort));
        LOGGER.debug("generating a hosting configuration for {} map(s)", maps.size());

        for (int i = 0; i < maps.size(); i++) {
            BlueMapMap map = maps.get(i);
            BucketBinding binding = bindings.get(i);
            String mapId = map.getMetadata().getName();

            files.put("maps/" + mapId + ".conf", hostingMapConfig(mapId));
            files.put("storages/" + mapId + ".conf", hostingStorageConfig(map, binding));
        }

        return files;
    }

    private static String webserverConfig(int port) {
        return """
                enabled: true
                webroot: "web"
                port: %d
                sse-enabled: true

                # BlueMap 5.23's default webserver.conf has no bind-address/ip setting -- verified
                # by running the CLI against an empty config folder (see this class's Javadoc).
                # The webserver always listens on all interfaces (0.0.0.0), which a pod needs: it
                # must accept connections from the Service, not just from localhost.
                """
                .formatted(port);
    }

    private static String hostingMapConfig(String mapId) {
        // No world/dimension key: this pod only serves what was already rendered elsewhere.
        return """
                name: "%s"
                sorting: 0
                storage: "%s"
                """
                .formatted(mapId, mapId);
    }

    // No credentials here either: the hosting image's entrypoint fills access-key-id/
    // secret-access-key in from the Rook-managed Secret's environment variables before
    // starting BlueMap, exactly like the runner's entrypoint does for build().
    private static String hostingStorageConfig(BlueMapMap map, BucketBinding binding) {
        return """
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
                        map.getSpec().getStorage().getPrefix());
    }

    private static int renderThreads(BlueMapMap map) {
        return 2;
    }
}
