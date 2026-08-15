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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The load phase's half of the trace: {@code ingest load}, one span per dimension written, and the
 * manifest write that commits the bundle.
 *
 * <p>Separate from {@link IngestTracingTest} because this is the only place a <em>successful</em>
 * load can be exercised without a container: {@link BundleWriter} takes an {@link S3Client}, so a
 * fake one is enough to write a whole bundle and watch the spans come out.
 */
class BundleWriterTracingTest {

    private static final String BUCKET = "test-bucket";

    private final InMemorySpanExporter exporter = InMemorySpanExporter.create();
    private final SdkTracerProvider tracerProvider = SdkTracerProvider.builder()
            .addSpanProcessor(SimpleSpanProcessor.create(exporter))
            .build();

    @AfterEach
    void closeTracerProvider() {
        tracerProvider.close();
    }

    private Tracer tracer() {
        return tracerProvider.get(IngestTelemetry.SCOPE_NAME);
    }

    private record FakeLayout(String kind, Map<String, Path> dimensions) implements BundleWriter.WorldLayoutLike {}

    /** Accepts every write; the point here is the trace, not the storage. */
    private static final class AcceptingS3Client implements S3Client {
        @Override
        public void putObject(String bucket, String key, byte[] content) {}
    }

    private static Map<String, Path> twoDimensions(Path tempDir) throws IOException {
        Path overworld = tempDir.resolve("world/region");
        Path nether = tempDir.resolve("world/DIM-1/region");
        Files.createDirectories(overworld);
        Files.createDirectories(nether);
        Files.write(overworld.resolve("r.0.0.mca"), "ow-0-0".getBytes(StandardCharsets.UTF_8));
        Files.write(overworld.resolve("r.0.1.mca"), "ow-0-1".getBytes(StandardCharsets.UTF_8));
        Files.write(nether.resolve("r.0.0.mca"), "nether".getBytes(StandardCharsets.UTF_8));

        Map<String, Path> dimensions = new LinkedHashMap<>();
        dimensions.put("overworld", overworld);
        dimensions.put("the_nether", nether);
        return dimensions;
    }

    private String write(S3Client s3, Map<String, Path> dimensions) {
        return new BundleWriter(s3, BUCKET, tracer())
                .write(
                        "acme",
                        "survival-source",
                        "spawn",
                        "v1",
                        "s3",
                        "src-ref-1",
                        "1.21.10",
                        new FakeLayout("vanilla", dimensions),
                        null);
    }

    @Test
    void aWrittenBundleIsOneLoadSpanWithASpanPerDimensionAndOneForTheManifest(@TempDir Path tempDir)
            throws IOException {
        write(new AcceptingS3Client(), twoDimensions(tempDir));

        List<SpanData> spans = exporter.getFinishedSpanItems();
        SpanData load = span(spans, "ingest load");
        SpanData overworld = span(spans, "ingest dimension overworld");
        SpanData nether = span(spans, "ingest dimension the_nether");
        SpanData manifest = span(spans, "ingest manifest");

        assertEquals(load.getSpanId(), overworld.getParentSpanId());
        assertEquals(load.getSpanId(), nether.getParentSpanId());
        assertEquals(load.getSpanId(), manifest.getParentSpanId());
        assertEquals(4, spans.size(), "no span per method call: " + names(spans));
    }

    @Test
    void theCountsThatMakeASlowLoadExplainableAreOnTheSpans(@TempDir Path tempDir) throws IOException {
        write(new AcceptingS3Client(), twoDimensions(tempDir));

        List<SpanData> spans = exporter.getFinishedSpanItems();
        SpanData load = span(spans, "ingest load");
        assertEquals("acme", attribute(load, "apus.tenant"));
        assertEquals("spawn", attribute(load, "apus.world.id"));
        assertEquals("v1", attribute(load, "apus.bundle.version"));
        assertEquals("acme/survival-source/spawn/v1", attribute(load, "apus.bundle.path"));
        assertEquals(3L, attribute(load, "apus.bundle.regions"), "two overworld regions and one nether region");
        assertEquals(18L, attribute(load, "apus.bundle.bytes"), "6 + 6 + 6 bytes of regions, no level.dat in this fixture");

        SpanData overworld = span(spans, "ingest dimension overworld");
        assertEquals("overworld", attribute(overworld, "apus.dimension"));
        assertEquals(2L, attribute(overworld, "apus.dimension.regions"));
        assertEquals(12L, attribute(overworld, "apus.dimension.bytes"));
    }

    @Test
    void aFailedWriteMarksTheDimensionSpanAndTheLoadSpan(@TempDir Path tempDir) throws IOException {
        S3Client failing = (bucket, key, content) -> {
            throw new IllegalStateException("simulated failure writing " + key);
        };

        assertThrows(IllegalStateException.class, () -> write(failing, twoDimensions(tempDir)));

        List<SpanData> spans = exporter.getFinishedSpanItems();
        assertEquals(
                StatusCode.ERROR,
                span(spans, "ingest dimension overworld").getStatus().getStatusCode());
        assertEquals(StatusCode.ERROR, span(spans, "ingest load").getStatus().getStatusCode());
        assertTrue(
                spans.stream().noneMatch(span -> span.getName().equals("ingest manifest")),
                "the manifest is the commit point -- it was never written, so there is no span for it");
    }

    private static List<String> names(List<SpanData> spans) {
        return spans.stream().map(SpanData::getName).toList();
    }

    private static SpanData span(List<SpanData> spans, String name) {
        Optional<SpanData> match =
                spans.stream().filter(span -> span.getName().equals(name)).findFirst();
        assertTrue(match.isPresent(), "expected a span named '" + name + "', got: " + names(spans));
        return match.get();
    }

    private static Object attribute(SpanData span, String key) {
        return span.getAttributes().asMap().entrySet().stream()
                .filter(entry -> entry.getKey().getKey().equals(key))
                .map(Map.Entry::getValue)
                .findFirst()
                .orElseThrow(() -> new AssertionError("span '" + span.getName() + "' has no attribute '" + key + "'"));
    }
}
