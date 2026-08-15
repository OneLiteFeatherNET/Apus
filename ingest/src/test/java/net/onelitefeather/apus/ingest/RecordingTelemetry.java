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

import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import java.time.Duration;
import java.util.Collection;
import java.util.List;

/**
 * An SDK that collects spans in memory, wired up the way the real one is: through a {@link
 * BatchSpanProcessor}.
 *
 * <p>The batching is the point, not an accident. Its schedule delay is set to an hour, so the
 * processor will never export on its own during a test. Anything that ends up in {@link #spans()}
 * therefore got there because something flushed the SDK -- which is exactly the property {@code
 * ingest} depends on: it is a Job, it exits when the run ends, and a run whose exporter never
 * flushed is a run whose trace was lost.
 *
 * <p>{@link SpanExporter#shutdown()} is deliberately not forwarded to the in-memory exporter, which
 * clears its collected spans when shut down -- the assertions run after the SDK has been closed, so
 * forwarding it would throw away the very evidence the test is about.
 */
final class RecordingTelemetry {

    private final InMemorySpanExporter exporter = InMemorySpanExporter.create();
    private final OpenTelemetrySdk sdk;

    RecordingTelemetry() {
        this.sdk = OpenTelemetrySdk.builder()
                .setTracerProvider(SdkTracerProvider.builder()
                        .addSpanProcessor(BatchSpanProcessor.builder(new NonClosingExporter(exporter))
                                .setScheduleDelay(Duration.ofHours(1))
                                .build())
                        .build())
                .build();
    }

    /** A fresh {@link IngestTelemetry} around this SDK, for {@code IngestMain.run} to close. */
    IngestTelemetry telemetry() {
        return IngestTelemetry.install(sdk);
    }

    OpenTelemetrySdk sdk() {
        return sdk;
    }

    /** Every span exported so far -- empty unless the SDK has been flushed or closed. */
    List<SpanData> spans() {
        return exporter.getFinishedSpanItems();
    }

    private record NonClosingExporter(SpanExporter delegate) implements SpanExporter {

        @Override
        public CompletableResultCode export(Collection<SpanData> spans) {
            return delegate.export(spans);
        }

        @Override
        public CompletableResultCode flush() {
            return delegate.flush();
        }

        @Override
        public CompletableResultCode shutdown() {
            // Not forwarded on purpose -- see the class Javadoc.
            return CompletableResultCode.ofSuccess();
        }
    }
}
