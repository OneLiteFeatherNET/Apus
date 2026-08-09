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
package net.onelitefeather.apus.telemetry;

import java.util.function.Function;

/**
 * Telemetry settings, read from the environment.
 *
 * <p>Environment variables rather than a config file: the addon lives inside BlueMap's
 * config folder, and adding a parallel config format there would be one more thing for
 * the operator to template.
 */
public record TelemetryConfig(String bindAddress, int port, boolean enabled) {

    /**
     * Mirrored as a private constant in {@code
     * net.onelitefeather.apus.operator.render.BlueMapRenderReconciler} ({@code
     * HttpProgressFetcher.TELEMETRY_PORT}): the {@code operator} module has no compile
     * dependency on this one, so it cannot reference this constant directly and duplicates the
     * value instead. If this default ever changes, that constant must change with it.
     */
    public static final int DEFAULT_PORT = 8099;

    public static final String DEFAULT_BIND = "0.0.0.0";

    public static TelemetryConfig fromEnvironment(Function<String, String> env) {
        return new TelemetryConfig(
                valueOrDefault(env.apply("APUS_TELEMETRY_BIND"), DEFAULT_BIND),
                parsePort(env.apply("APUS_TELEMETRY_PORT")),
                !"false".equalsIgnoreCase(env.apply("APUS_TELEMETRY_ENABLED")));
    }

    private static String valueOrDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static int parsePort(String value) {
        if (value == null || value.isBlank()) {
            return DEFAULT_PORT;
        }
        try {
            int parsed = Integer.parseInt(value.trim());
            return parsed > 0 && parsed <= 65535 ? parsed : DEFAULT_PORT;
        } catch (NumberFormatException e) {
            return DEFAULT_PORT;
        }
    }
}
