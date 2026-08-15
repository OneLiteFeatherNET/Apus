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
package net.onelitefeather.apus.ingest;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.instrumentation.logback.appender.v1_0.OpenTelemetryAppender;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.autoconfigure.AutoConfiguredOpenTelemetrySdk;
import java.util.Map;

/**
 * The ingest job's OpenTelemetry SDK, from construction to the shutdown that must happen before the
 * process exits.
 *
 * <p><b>Why this is a resource and not a singleton.</b> {@code ingest} is a Kubernetes Job, not a
 * daemon: it runs one ETL pass and exits. Both the span processor and the log record processor
 * batch by default, so whatever they are still holding when {@link System#exit} runs is simply
 * lost -- and that is exactly the trace of the run that just failed, the one an operator wants to
 * look at. {@link #close()} therefore shuts the SDK down (which flushes both), and {@link
 * IngestMain} calls it from a try-with-resources so the failure path flushes just like the happy
 * path does.
 *
 * <p><b>Why no endpoint means no SDK.</b> {@code AutoConfiguredOpenTelemetrySdk} defaults {@code
 * otel.traces.exporter} to {@code otlp} pointing at {@code localhost:4317}. Left alone, a job run
 * on a cluster without a collector would build real exporters, fail to connect once per batch and
 * say so in its own log -- noise where there used to be none. {@code docs/logging-and-tracing.md}
 * promises that an unset {@code OTEL_EXPORTER_OTLP_ENDPOINT} makes the SDK a no-op; this class
 * makes that true by setting {@code otel.sdk.disabled} when no endpoint (general or signal
 * specific) is configured. Properties returned from a properties customizer override the
 * environment, so an explicit {@code OTEL_SDK_DISABLED=false} does <em>not</em> resurrect an
 * exporter that has nowhere to send data -- configure an endpoint for that.
 */
final class IngestTelemetry implements AutoCloseable {

    /**
     * The instrumentation scope every span this module records is attributed to. Shows up in
     * Grafana as the library that produced the span.
     */
    static final String SCOPE_NAME = "net.onelitefeather.apus.ingest";

    private static final String PROPERTY_SDK_DISABLED = "otel.sdk.disabled";
    private static final String PROPERTY_SERVICE_NAME = "otel.service.name";
    private static final String DEFAULT_SERVICE_NAME = "apus-ingest";

    /**
     * Any one of these being set means "there is a collector"; the SDK is built for real. Matches
     * the variables {@code AutoConfiguredOpenTelemetrySdk} itself reads, in their property spelling
     * (the env var {@code OTEL_EXPORTER_OTLP_ENDPOINT} and the property {@code
     * otel.exporter.otlp.endpoint} are the same knob).
     */
    private static final String[] ENDPOINT_PROPERTIES = {
        "otel.exporter.otlp.endpoint", "otel.exporter.otlp.traces.endpoint", "otel.exporter.otlp.logs.endpoint"
    };

    private final OpenTelemetrySdk sdk;

    private IngestTelemetry(OpenTelemetrySdk sdk) {
        this.sdk = sdk;
    }

    /**
     * Builds the SDK from the {@code OTEL_*} environment and hands it to the Logback appender.
     * Called once, before the first log line of the run -- records logged before the appender is
     * installed carry no trace context.
     */
    static IngestTelemetry install() {
        return install(buildSdk());
    }

    /**
     * Builds the SDK without touching the Logback appender -- separate from {@link #install()} so a
     * test can assert what the environment produced without redirecting this JVM's log records
     * into it.
     */
    static OpenTelemetrySdk buildSdk() {
        return AutoConfiguredOpenTelemetrySdk.builder()
                .addPropertiesSupplier(() -> Map.of(PROPERTY_SERVICE_NAME, DEFAULT_SERVICE_NAME))
                .addPropertiesCustomizer(config -> {
                    for (String property : ENDPOINT_PROPERTIES) {
                        if (config.getString(property) != null) {
                            return Map.of();
                        }
                    }
                    return Map.of(PROPERTY_SDK_DISABLED, "true");
                })
                .build()
                .getOpenTelemetrySdk();
    }

    /**
     * Same, for an already-built SDK -- how a test drives a run against an {@code
     * InMemorySpanExporter} instead of a collector.
     */
    static IngestTelemetry install(OpenTelemetrySdk sdk) {
        OpenTelemetryAppender.install(sdk);
        return new IngestTelemetry(sdk);
    }

    OpenTelemetry openTelemetry() {
        return sdk;
    }

    Tracer tracer() {
        return sdk.getTracer(SCOPE_NAME);
    }

    /**
     * Shuts the SDK down, flushing every span and log record still queued. {@link
     * OpenTelemetrySdk#close()} blocks for up to ten seconds waiting for that to finish; a job that
     * returned before it completed would drop the tail of its own trace.
     */
    @Override
    public void close() {
        sdk.close();
    }
}
