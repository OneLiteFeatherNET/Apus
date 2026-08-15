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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.sdk.trace.data.SpanData;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.onelitefeather.apus.ingest.connector.SourceVersion;
import net.onelitefeather.apus.ingest.connector.WorldSourceConnector;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * What an ingest run leaves behind as a trace.
 *
 * <p>A run can take forty minutes and die in the middle, and the question that follows is always
 * "where did the time go". The answer has to be the span tree, so these tests assert the tree
 * itself -- one span for the run, one per phase, one per dimension -- and the attributes that make
 * a slow run explainable (how much was fetched, how much was written). They also assert that the
 * SDK is flushed before the run returns, which for a Job is the difference between having that
 * trace and not.
 *
 * <p>Both connectors here are fakes and the bundle destination is a closed port, so every run below
 * fails somewhere: no container, no network, no Docker (the MinIO-backed tests are a separate task
 * for that reason). The failure is a feature -- it is the run whose trace matters most.
 */
class IngestTracingTest {

    private static final String CLOSED_PORT_ENDPOINT = "http://127.0.0.1:1";

    @Test
    void aRunIsOneSpanPerPhaseWithASpanPerDimension(@TempDir Path tempDir) throws IOException {
        RecordingTelemetry telemetry = new RecordingTelemetry();
        Path workDir = tempDir.resolve("source");

        int exitCode = IngestMain.run(env(), workDir, telemetry::telemetry, type -> vanillaWorldConnector());

        assertEquals(
                IngestMain.EXIT_RUNTIME_ERROR, exitCode, "nothing is listening on the bundle endpoint, so load fails");

        List<SpanData> spans = telemetry.spans();
        SpanData run = span(spans, "ingest run");
        SpanData extract = span(spans, "ingest extract");
        SpanData transform = span(spans, "ingest transform");
        SpanData load = span(spans, "ingest load");
        // The load deliberately fails against a closed port, so it aborts after the first
        // dimension it happens to reach -- which one that is depends on iteration order and is
        // not the point. What must hold is the shape: a dimension is written inside the load.
        SpanData dimension = spans.stream()
                .filter(span -> span.getName().startsWith("ingest dimension "))
                .findFirst()
                .orElseGet(() -> fail("expected at least one dimension span, got: "
                        + spans.stream().map(SpanData::getName).toList()));

        assertEquals(run.getSpanId(), extract.getParentSpanId());
        assertEquals(run.getSpanId(), transform.getParentSpanId());
        assertEquals(run.getSpanId(), load.getParentSpanId());
        assertEquals(load.getSpanId(), dimension.getParentSpanId(), "a dimension is written as part of the load");
        assertEquals(run.getTraceId(), dimension.getTraceId(), "one run is one trace");
    }

    @Test
    void theRunSpanCarriesTheConnectorTypeAndWhatWasIngested(@TempDir Path tempDir) throws IOException {
        RecordingTelemetry telemetry = new RecordingTelemetry();

        IngestMain.run(env(), tempDir.resolve("source"), telemetry::telemetry, type -> vanillaWorldConnector());

        SpanData run = span(telemetry.spans(), "ingest run");
        assertEquals("s3", attribute(run, "apus.source.type"));
        assertEquals("acme", attribute(run, "apus.tenant"));
        assertEquals("survival-source", attribute(run, "apus.source.name"));
        assertEquals("spawn", attribute(run, "apus.world.id"));
        assertEquals("v1", attribute(run, "apus.bundle.version"));
    }

    @Test
    void theExtractAndTransformSpansExplainWhatWasFetchedAndRecognised(@TempDir Path tempDir) throws IOException {
        RecordingTelemetry telemetry = new RecordingTelemetry();

        IngestMain.run(env(), tempDir.resolve("source"), telemetry::telemetry, type -> vanillaWorldConnector());

        SpanData extract = span(telemetry.spans(), "ingest extract");
        // Two region files and a level.dat, as written by the fake connector below.
        assertEquals(3L, attribute(extract, "apus.source.files"));
        assertNotNull(attribute(extract, "apus.source.bytes"));
        assertNotEquals(StatusCode.ERROR, extract.getStatus().getStatusCode());

        SpanData transform = span(telemetry.spans(), "ingest transform");
        assertEquals("vanilla", attribute(transform, "apus.layout.kind"));
        assertEquals(2L, attribute(transform, "apus.layout.dimensions"), "overworld and the nether");
    }

    @Test
    void aFailedPhaseIsMarkedOnItsOwnSpanAndOnTheRun(@TempDir Path tempDir) {
        RecordingTelemetry telemetry = new RecordingTelemetry();

        // A connector that fetches nothing at all: there is no world to recognise, so the run dies
        // in transform rather than in load.
        int exitCode = IngestMain.run(
                env(), tempDir.resolve("source"), telemetry::telemetry, type -> emptyConnector());

        assertEquals(IngestMain.EXIT_LAYOUT_ERROR, exitCode);
        List<SpanData> spans = telemetry.spans();
        assertEquals(StatusCode.ERROR, span(spans, "ingest transform").getStatus().getStatusCode());
        assertEquals(StatusCode.ERROR, span(spans, "ingest run").getStatus().getStatusCode());
        assertFalse(
                spans.stream().anyMatch(span -> span.getName().equals("ingest load")),
                "the load phase never started, so it must not appear in the trace");
        assertFalse(
                span(spans, "ingest transform").getEvents().isEmpty(),
                "the exception that ended the phase belongs on the span");
    }

    /**
     * The one that would otherwise be found out in production: a Job exits when its run ends, and
     * an SDK that was never shut down takes the batched spans with it.
     *
     * <p>The probe span makes the proof direct. {@link RecordingTelemetry}'s batch processor has an
     * hour-long schedule delay, so an ended span sitting in the queue is invisible until something
     * flushes it -- as the first assertion below shows. If it is visible after the run, the run
     * flushed it.
     */
    @Test
    void theRunFlushesEverythingBeforeItReturns(@TempDir Path tempDir) throws IOException {
        RecordingTelemetry telemetry = new RecordingTelemetry();
        Span probe = telemetry.sdk().getTracer("test").spanBuilder("probe").startSpan();
        probe.end();
        assertTrue(
                telemetry.spans().isEmpty(),
                "an ended span must still be queued in the batch processor, or this test proves nothing");

        IngestMain.run(env(), tempDir.resolve("source"), telemetry::telemetry, type -> vanillaWorldConnector());

        List<String> names = telemetry.spans().stream().map(SpanData::getName).toList();
        assertTrue(names.contains("probe"), "the queued span was exported, so the SDK was flushed");
        assertTrue(names.contains("ingest run"), "including the run's own spans");
    }

    /** A connector that materialises a minimal vanilla world: an overworld, a nether, a level.dat. */
    private static WorldSourceConnector vanillaWorldConnector() {
        return new WorldSourceConnector() {
            @Override
            public String type() {
                return "s3";
            }

            @Override
            public List<SourceVersion> discover(Map<String, String> config) {
                return List.of();
            }

            @Override
            public void fetch(Map<String, String> config, SourceVersion version, Path workDir) {
                try {
                    Path world = workDir.resolve("world");
                    Files.createDirectories(world.resolve("region"));
                    Files.write(world.resolve("region").resolve("r.0.0.mca"), "ow".getBytes(StandardCharsets.UTF_8));
                    Files.createDirectories(world.resolve("DIM-1").resolve("region"));
                    Files.write(
                            world.resolve("DIM-1").resolve("region").resolve("r.0.0.mca"),
                            "nether".getBytes(StandardCharsets.UTF_8));
                    Files.write(world.resolve("level.dat"), "level".getBytes(StandardCharsets.UTF_8));
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }
        };
    }

    /** A connector that succeeds without producing anything a layout could be detected in. */
    private static WorldSourceConnector emptyConnector() {
        return new WorldSourceConnector() {
            @Override
            public String type() {
                return "s3";
            }

            @Override
            public List<SourceVersion> discover(Map<String, String> config) {
                return List.of();
            }

            @Override
            public void fetch(Map<String, String> config, SourceVersion version, Path workDir) {
                // deliberately nothing
            }
        };
    }

    private static Map<String, String> env() {
        Map<String, String> env = new LinkedHashMap<>();
        env.put(IngestConfig.ENV_SOURCE_TYPE, "s3");
        env.put(IngestConfig.ENV_WORLD_NAME, "world");
        env.put(IngestConfig.ENV_SOURCE_VERSION, "src-1");
        env.put(IngestConfig.ENV_BUNDLE_BUCKET, "bundles");
        env.put(IngestConfig.ENV_BUNDLE_TENANT, "acme");
        env.put(IngestConfig.ENV_BUNDLE_SOURCE_NAME, "survival-source");
        env.put(IngestConfig.ENV_BUNDLE_WORLD_ID, "spawn");
        env.put(IngestConfig.ENV_BUNDLE_VERSION, "v1");
        env.put(IngestConfig.ENV_S3_ENDPOINT, CLOSED_PORT_ENDPOINT);
        env.put(IngestConfig.ENV_S3_ACCESS_KEY, "access");
        env.put(IngestConfig.ENV_S3_SECRET_KEY, "secret");
        env.put(IngestConfig.ENV_SOURCE_S3_BUCKET, "source-bucket");
        return env;
    }

    private static SpanData span(List<SpanData> spans, String name) {
        Optional<SpanData> match =
                spans.stream().filter(span -> span.getName().equals(name)).findFirst();
        assertTrue(
                match.isPresent(),
                "expected a span named '" + name + "', got: "
                        + spans.stream().map(SpanData::getName).toList());
        return match.get();
    }

    private static Object attribute(SpanData span, String key) {
        return span.getAttributes().asMap().entrySet().stream()
                .filter(entry -> entry.getKey().getKey().equals(key))
                .map(Map.Entry::getValue)
                .findFirst()
                .orElseThrow(() -> new AssertionError("span '" + span.getName() + "' has no attribute '" + key
                        + "', only: " + span.getAttributes().asMap().keySet().stream()
                                .map(AttributeKey::getKey)
                                .toList()));
    }
}
