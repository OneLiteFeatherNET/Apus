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
package net.onelitefeather.apus.operator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import net.onelitefeather.apus.operator.telemetry.Tracing;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Covers the startup half of the observability wiring: that building the SDK, installing the
 * Logback appender and emitting a span all work in the state <em>every</em> developer machine and
 * <em>every</em> test run is in -- no {@code OTEL_EXPORTER_OTLP_ENDPOINT}, no collector anywhere.
 *
 * <p>That state is not incidental, it is the one that must never regress: an operator whose
 * startup depends on a collector being reachable would be unbootable on a laptop, and one that
 * quietly retries a connection to {@code localhost:4317} forever burns a thread and fills the log
 * of every single pod. Both are exactly what {@link ApusOperator#exporterDefaults} exists to
 * prevent, so both are asserted here rather than assumed.
 *
 * <p>The environment is injected rather than read from the real one: a developer who <em>does</em>
 * export {@code OTEL_EXPORTER_OTLP_ENDPOINT} must not make this test behave differently.
 */
class OperatorTelemetryTest {

    private static final Logger LOGGER = LoggerFactory.getLogger(OperatorTelemetryTest.class);

    /** An environment with nothing at all set -- the common case. */
    private static final java.util.function.Function<String, String> EMPTY_ENV = name -> null;

    @AfterEach
    void resetTracer() {
        // Tracing holds the SDK this test installed; leave it inert so no later test in the same
        // JVM emits spans through an SDK this one has already closed.
        Tracing.use(OpenTelemetry.noop());
    }

    @Test
    void withNoEndpointConfiguredNoExporterIsAttached() {
        Map<String, String> defaults = ApusOperator.exporterDefaults(EMPTY_ENV);

        assertEquals("none", defaults.get("otel.traces.exporter"));
        assertEquals("none", defaults.get("otel.logs.exporter"));
        assertEquals("none", defaults.get("otel.metrics.exporter"));
    }

    @Test
    void configuringAnEndpointLeavesTheExporterDefaultsAlone() {
        assertTrue(
                ApusOperator.exporterDefaults(name -> "OTEL_EXPORTER_OTLP_ENDPOINT".equals(name)
                                ? "http://collector.observability.svc:4318"
                                : null)
                        .isEmpty(),
                "with an endpoint configured the SDK must use its own defaults, not be forced to 'none'");
        assertTrue(
                ApusOperator.exporterDefaults(name -> "OTEL_EXPORTER_OTLP_TRACES_ENDPOINT".equals(name)
                                ? "http://collector.observability.svc:4318/v1/traces"
                                : null)
                        .isEmpty(),
                "a per-signal endpoint override counts as an endpoint too");
    }

    /**
     * The whole startup path in one go: build the SDK, install the appender, log, trace, shut
     * down. A failure here is a pod that cannot start, so the timeout is deliberately tight --
     * an SDK that blocks trying to reach a collector would hang rather than fail fast.
     */
    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void startupWiringWorksWithoutACollector() {
        OpenTelemetrySdk telemetry = ApusOperator.initTelemetry(EMPTY_ENV);
        try {
            assertNotNull(telemetry, "initTelemetry must always produce an SDK, collector or not");

            // No span processor at all -- nothing is buffering spans for an exporter that does
            // not exist. This is the property that makes shipping tracing enabled by default safe.
            assertTrue(
                    telemetry.getSdkTracerProvider().toString().contains("NoopSpanProcessor"),
                    "expected no span processor with no endpoint configured, but the tracer provider was: "
                            + telemetry.getSdkTracerProvider());

            // A log line after install() must go through the appender without a collector.
            LOGGER.info("telemetry wiring smoke test");

            // And a span must be creatable through the tracer the reconcilers use.
            Span span = Tracing.tracer().spanBuilder("startup smoke test").startSpan();
            span.end();
        } finally {
            telemetry.close();
        }
    }
}
