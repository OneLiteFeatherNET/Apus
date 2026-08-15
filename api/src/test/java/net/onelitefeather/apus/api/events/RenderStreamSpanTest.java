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
package net.onelitefeather.apus.api.events;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;
import io.fabric8.kubernetes.client.Watch;
import io.fabric8.kubernetes.client.Watcher;
import io.micronaut.http.sse.Event;
import io.micronaut.security.authentication.Authentication;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.data.StatusData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.onelitefeather.apus.api.security.TenantResolver;
import net.onelitefeather.apus.api.support.PrincipalResolver;
import net.onelitefeather.apus.operator.api.BlueMapRender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

/**
 * Proves the one span this module creates by hand actually behaves: it covers the SSE stream's
 * lifetime (not the request that opened it), it ends on every way a stream can end, and it
 * carries the attributes {@code docs/logging-and-tracing.md} asks for.
 *
 * <p>Spans are collected through a {@link SpanExporter} written here rather than {@code
 * opentelemetry-sdk-testing}'s {@code InMemorySpanExporter}: that artifact has no entry in this
 * project's version catalog, and adding one means editing {@code settings.gradle.kts}, which is
 * outside this change's scope (see the task report). The interface is three methods, and using
 * it directly costs less than the dependency would.
 *
 * <p>The "ends exactly once, on every path" property is the one worth guarding hardest. An SSE
 * stream ends in three different ways -- the render reaches a terminal phase, the producer
 * fails, or the client disconnects -- and each of them travels a different route through {@code
 * SseSource}. A span that is only ended on the happy path leaks one unclosed span per abandoned
 * browser tab, which is invisible until a trace backend starts dropping them.
 */
class RenderStreamSpanTest {

    private static final Authentication VIEWER =
            Authentication.build("carol", List.of("tenant-viewer"), Map.of("organization", "acme"));

    private static final AttributeKey<String> STREAM = AttributeKey.stringKey("apus.sse.stream");
    private static final AttributeKey<String> NAMESPACE = AttributeKey.stringKey("k8s.namespace.name");
    private static final AttributeKey<String> RENDER = AttributeKey.stringKey("apus.render");
    private static final AttributeKey<String> PHASE = AttributeKey.stringKey("apus.render.phase");

    private final RecordingSpanExporter exporter = new RecordingSpanExporter();
    private final SdkTracerProvider tracerProvider = SdkTracerProvider.builder()
            .addSpanProcessor(SimpleSpanProcessor.create(exporter))
            .build();
    private final Tracer tracer = tracerProvider.get("test");

    @AfterEach
    void closeTracerProvider() {
        tracerProvider.close();
    }

    @Test
    void theProgressStreamsSpanCarriesTheNamespaceRenderAndPhase() {
        FakeRenderRepository repository = new FakeRenderRepository();
        repository.put("bluemap-acme", "render-1", render("bluemap-acme", "render-1", "Rendering"));
        RenderStreamController controller = controller(repository);

        RecordingSubscriber<RenderProgress> subscriber = new RecordingSubscriber<>();
        controller.events(VIEWER, "render-1").subscribe(subscriber);
        subscriber.subscription.request(Long.MAX_VALUE);

        // Still open: the render is not terminal, so the watch -- and the span -- are live.
        assertTrue(exporter.finished.isEmpty(), "the span must not end while the stream is still open");

        subscriber.subscription.cancel();

        SpanData span = exporter.single();
        assertEquals("render events stream", span.getName());
        assertEquals("events", span.getAttributes().get(STREAM));
        assertEquals("bluemap-acme", span.getAttributes().get(NAMESPACE));
        assertEquals("render-1", span.getAttributes().get(RENDER));
        assertEquals("Rendering", span.getAttributes().get(PHASE));
    }

    @Test
    void theLogStreamsSpanIsDistinguishableFromTheProgressStreams() {
        FakeRenderRepository repository = new FakeRenderRepository();
        repository.put("bluemap-acme", "render-1", render("bluemap-acme", "render-1", "Rendering"));
        RenderStreamController controller = controller(repository);

        RecordingSubscriber<String> subscriber = new RecordingSubscriber<>();
        controller.logs(VIEWER, "render-1").subscribe(subscriber);
        subscriber.subscription.request(Long.MAX_VALUE);
        subscriber.subscription.cancel();

        SpanData span = exporter.single();
        assertEquals("render logs stream", span.getName());
        assertEquals("logs", span.getAttributes().get(STREAM));
        assertEquals("render-1", span.getAttributes().get(RENDER));
    }

    @Test
    void aStreamOpenedOnAnAlreadyTerminalRenderStillEndsItsSpan() {
        // The path that would leak: the sink completes from inside SseSource's wiring callback,
        // before SseSource has taken ownership of the cleanup handle -- so the handle is never
        // closed and a span ended only there would stay open forever.
        FakeRenderRepository repository = new FakeRenderRepository();
        repository.put("bluemap-acme", "render-1", render("bluemap-acme", "render-1", "Succeeded"));
        RenderStreamController controller = controller(repository);

        RecordingSubscriber<RenderProgress> subscriber = new RecordingSubscriber<>();
        controller.events(VIEWER, "render-1").subscribe(subscriber);
        subscriber.subscription.request(Long.MAX_VALUE);

        assertTrue(subscriber.completed);
        assertEquals("Succeeded", exporter.single().getAttributes().get(PHASE));
    }

    @Test
    void aFailedStreamMarksItsSpanAsErrored() {
        FakeRenderRepository repository = new FakeRenderRepository();
        repository.put("bluemap-acme", "render-1", render("bluemap-acme", "render-1", "Rendering"));
        FakeLogSource logSource = new FakeLogSource();
        RenderStreamController controller = new RenderStreamController(
                repository, new TenantResolver(), logSource, new PrincipalResolver(), tracer);

        RecordingSubscriber<String> subscriber = new RecordingSubscriber<>();
        controller.logs(VIEWER, "render-1").subscribe(subscriber);
        subscriber.subscription.request(Long.MAX_VALUE);
        logSource.capturedSink.error(new IOException("loki unreachable"));

        SpanData span = exporter.single();
        assertEquals(StatusData.error(), span.getStatus());
        assertEquals(1, span.getEvents().size(), "the exception is recorded on the span");
    }

    // -- fixtures -------------------------------------------------------------------------

    private RenderStreamController controller(RenderRepository repository) {
        return new RenderStreamController(
                repository, new TenantResolver(), new FakeLogSource(), new PrincipalResolver(), tracer);
    }

    private static BlueMapRender render(String namespace, String name, String phase) {
        BlueMapRender render = new BlueMapRender();
        render.setMetadata(new ObjectMetaBuilder()
                .withName(name)
                .withNamespace(namespace)
                .withResourceVersion("1")
                .build());
        render.getStatus().setPhase(phase);
        render.getStatus().setJobName(name);
        return render;
    }

    private static final class RecordingSpanExporter implements SpanExporter {
        private final List<SpanData> finished = new ArrayList<>();

        SpanData single() {
            assertEquals(1, finished.size(), "expected exactly one ended span, got " + finished);
            return finished.get(0);
        }

        @Override
        public CompletableResultCode export(Collection<SpanData> spans) {
            finished.addAll(spans);
            return CompletableResultCode.ofSuccess();
        }

        @Override
        public CompletableResultCode flush() {
            return CompletableResultCode.ofSuccess();
        }

        @Override
        public CompletableResultCode shutdown() {
            return CompletableResultCode.ofSuccess();
        }
    }

    private static final class RecordingSubscriber<T> implements Subscriber<Event<T>> {
        boolean completed;
        Subscription subscription;

        @Override
        public void onSubscribe(Subscription s) {
            subscription = s;
        }

        @Override
        public void onNext(Event<T> event) {
            // Values are RenderStreamControllerTest's subject, not this class's.
        }

        @Override
        public void onError(Throwable t) {
            // Ditto -- what matters here is what the span looks like afterwards.
        }

        @Override
        public void onComplete() {
            completed = true;
        }
    }

    private static final class FakeRenderRepository implements RenderRepository {
        private final Map<String, BlueMapRender> renders = new HashMap<>();

        void put(String namespace, String name, BlueMapRender render) {
            renders.put(namespace + "/" + name, render);
        }

        @Override
        public Optional<BlueMapRender> find(String namespace, String name) {
            return Optional.ofNullable(renders.get(namespace + "/" + name));
        }

        @Override
        public Watch watch(String namespace, String name, String resourceVersion, Watcher<BlueMapRender> watcher) {
            return () -> {};
        }
    }

    private static final class FakeLogSource implements LogSource {
        SseSource.Sink<String> capturedSink;

        @Override
        public AutoCloseable tail(String namespace, String jobName, SseSource.Sink<String> sink) {
            capturedSink = sink;
            return () -> {};
        }
    }
}
