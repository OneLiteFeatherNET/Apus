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
package net.onelitefeather.apus.api.observability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Pins the one property of this module's OpenTelemetry setup that a deployment actually depends
 * on: <b>with nothing configured, nothing is exported</b>.
 *
 * <p>This is not a theoretical concern. The OpenTelemetry Java SDK's own defaults are {@code
 * otel.traces.exporter=otlp} and {@code http://localhost:4318} -- so the naive setup (build the
 * autoconfigured SDK, ship it) turns every local run, every CI run and every cluster without a
 * collector into a process that retries failing exports forever. {@link
 * OpenTelemetryFactory#exporterDefaults} is the guard against that, and these tests are what stop
 * someone removing it because "autoconfigure handles it".
 */
class OpenTelemetryFactoryTest {

    /** Stands in for a completely unconfigured environment -- every {@code otel.*} lookup misses. */
    private static final Map<String, String> NOTHING_CONFIGURED = Map.of();

    @Test
    void withNothingConfiguredEverySignalIsTurnedOff() {
        Map<String, String> defaults = OpenTelemetryFactory.exporterDefaults(NOTHING_CONFIGURED::get);

        assertEquals("none", defaults.get("otel.traces.exporter"));
        assertEquals("none", defaults.get("otel.logs.exporter"));
        assertEquals("none", defaults.get("otel.metrics.exporter"));
    }

    @Test
    void anOtlpEndpointAloneIsEnoughToEnableExport() {
        // The switch docs/logging-and-tracing.md's configuration table names.
        Map<String, String> config = Map.of("otel.exporter.otlp.endpoint", "http://collector:4317");

        Map<String, String> defaults = OpenTelemetryFactory.exporterDefaults(config::get);

        assertTrue(defaults.isEmpty(), "an endpoint is configured, so the SDK's own otlp defaults must stand");
    }

    @Test
    void aSignalSpecificEndpointAloneIsEnoughToEnableExport() {
        Map<String, String> config = Map.of("otel.exporter.otlp.traces.endpoint", "http://collector:4318/v1/traces");

        assertTrue(OpenTelemetryFactory.exporterDefaults(config::get).isEmpty());
    }

    @Test
    void anExplicitExporterSelectionIsNeverOverridden() {
        // The switch the OneLiteFeather micronaut-standards:observability baseline names
        // (OTEL_TRACES_EXPORTER=otlp). It must survive even with no endpoint set, where it means
        // "export to the OTLP default endpoint".
        Map<String, String> config = Map.of("otel.traces.exporter", "otlp");

        Map<String, String> defaults = OpenTelemetryFactory.exporterDefaults(config::get);

        assertNull(defaults.get("otel.traces.exporter"), "an explicit exporter choice must not be overridden");
        // The signals the deployment did *not* ask for stay off, rather than silently coming
        // along for the ride.
        assertEquals("none", defaults.get("otel.logs.exporter"));
        assertEquals("none", defaults.get("otel.metrics.exporter"));
    }
}
