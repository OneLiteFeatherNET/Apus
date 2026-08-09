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
package net.onelitefeather.apus.operator.render;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Optional;

/**
 * Parses the {@code /progress} JSON payload the Phase 1 telemetry addon serves from the render
 * pod (design spec §7.3/§9.1), e.g.:
 *
 * <pre>{@code
 * {"state":"rendering","currentMap":"overworld","progress":0.72232,"etaSeconds":28,
 *  "queuedTasks":-1,"renderThreads":-1,"degraded":false,"description":"..."}
 * }</pre>
 *
 * <p>The exact shape is fixed by a contract test in the {@code telemetry-addon} module; unknown
 * numeric values are reported as {@code -1} there rather than omitted, and this parser passes
 * them through unchanged instead of trying to "fix" them into some other sentinel.
 *
 * <p>Jackson is not declared as a direct dependency of this module -- it already arrives
 * transitively through the fabric8 Kubernetes client that JOSDK depends on ({@code
 * io.fabric8.kubernetes.client.utils.Serialization} exposes an {@code ObjectMapper} in its own
 * public API, so the type is guaranteed to be on the compile classpath). A plain {@link
 * ObjectMapper} is used here rather than {@code Serialization.jsonMapper()} because that method
 * is deprecated in fabric8 7.8.0 and this class parses an unrelated, non-Kubernetes payload
 * anyway.
 */
public final class ProgressPoller {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ProgressPoller() {}

    /**
     * Parses a {@code /progress} response body.
     *
     * <p>Never throws: the pod may still be starting up (nothing listening yet, or an empty
     * body), or something entirely unrelated may be answering on that port. Either case must
     * leave the caller free to simply try again later rather than fail the reconciliation.
     *
     * @param json the raw response body, possibly blank or not JSON at all
     * @return the parsed progress, or empty if {@code json} could not be interpreted as one
     */
    public static Optional<RenderProgress> parse(String json) {
        if (json == null || json.isBlank()) {
            return Optional.empty();
        }
        try {
            JsonNode node = MAPPER.readTree(json);
            if (node == null || !node.isObject()) {
                return Optional.empty();
            }
            String state = textOrNull(node, "state");
            if (state == null) {
                return Optional.empty();
            }
            String currentMap = textOrNull(node, "currentMap");
            double progress = node.path("progress").asDouble(-1);
            long etaSeconds = node.path("etaSeconds").asLong(-1);
            boolean degraded = node.path("degraded").asBoolean(false);
            return Optional.of(new RenderProgress(state, currentMap, progress, etaSeconds, degraded));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    private static String textOrNull(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return (value == null || value.isNull()) ? null : value.asText();
    }

    /** One snapshot of render progress, as reported by the telemetry addon. */
    public record RenderProgress(String state, String currentMap, double progress, long etaSeconds, boolean degraded) {}
}
