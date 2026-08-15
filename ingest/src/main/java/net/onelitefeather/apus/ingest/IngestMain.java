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

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Stream;
import net.onelitefeather.apus.ingest.connector.PterodactylConnector;
import net.onelitefeather.apus.ingest.connector.PushSourceConnector;
import net.onelitefeather.apus.ingest.connector.S3SourceConnector;
import net.onelitefeather.apus.ingest.connector.SourceVersion;
import net.onelitefeather.apus.ingest.connector.UploadSourceConnector;
import net.onelitefeather.apus.ingest.connector.WorldSourceConnector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;

/**
 * Entry point of the ingest job: fetch raw world data from one configured source, detect its
 * layout, and write it to S3 as a versioned bundle.
 *
 * <p>Analogous to {@code runner}'s {@code entrypoint.sh}/BlueMap CLI pairing, but as a single Java
 * process rather than a shell script driving a separate JVM: read and validate every required
 * environment variable up front (nothing is downloaded until that succeeds), pick the connector
 * matching {@code APUS_SOURCE_TYPE}, fetch into a work directory, detect the layout, then hand
 * both off to {@link BundleWriter}. See {@code ingest/README.md} for the full environment
 * variable contract and exit code meanings.
 *
 * <p><b>The run is one trace.</b> A run can take forty minutes and fail in the middle, and the
 * question that follows is always "where did the time go". So the whole run is one span, with a
 * child span per phase -- {@code ingest extract}, {@code ingest transform}, {@code ingest load}
 * (the last one opened by {@link BundleWriter}, which also opens one per dimension it writes).
 * Nothing finer: a trace with a span per method call hides the one step that took nine seconds.
 *
 * <p>The phase log lines are a contract of their own. The operator cannot see inside a running
 * Job, so {@code WorldIngestReconciler} reads {@code phase=<...>}, {@code progress: NN.N%
 * (done/total bytes)} and {@code dimensions=[...]} back out of this pod's log (see its {@code
 * IngestLogProgress}). Logback's console layout is free to change; those substrings are not.
 */
public final class IngestMain {

    private static final Logger LOGGER = LoggerFactory.getLogger(IngestMain.class);

    /** Configuration invalid or a required variable missing; nothing was fetched. */
    static final int EXIT_CONFIGURATION_ERROR = 1;

    /** No known world layout could be recognized in the fetched source data. */
    static final int EXIT_LAYOUT_ERROR = 2;

    /** Any other failure while fetching the source or writing the bundle. */
    static final int EXIT_RUNTIME_ERROR = 3;

    private static final int EXIT_SUCCESS = 0;

    private static final Path DEFAULT_WORK_DIR = Path.of("/work/source");

    private IngestMain() {}

    public static void main(String[] args) {
        System.exit(run(System.getenv(), DEFAULT_WORK_DIR));
    }

    /**
     * Runs the full ingest flow and returns the process exit code, without calling {@link
     * System#exit}. Public so tests can drive it against an arbitrary environment map and work
     * directory instead of the real process environment and {@code /work} -- including, from
     * {@code runner}'s {@code :runner:integrationTest} task, the end-to-end proof that a bundle
     * this method writes is exactly what the render container expects to read (see
     * {@code IngestRenderContractTest}).
     */
    public static int run(Map<String, String> env, Path workDir) {
        return run(env, workDir, IngestTelemetry::install, IngestMain::selectConnector);
    }

    /**
     * Same, with the two things a test needs to supply itself: the telemetry (so spans land in an
     * {@code InMemorySpanExporter} rather than a collector) and the connector lookup (so a run can
     * reach the transform and load phases without a real source behind it).
     *
     * <p>The telemetry is closed in a {@code finally}, on every path out of the run. This is a Job:
     * whatever the batching processors still hold when the process exits is lost, and the run that
     * failed is exactly the one whose trace someone will go looking for.
     */
    static int run(
            Map<String, String> env,
            Path workDir,
            Supplier<IngestTelemetry> telemetryFactory,
            Function<String, WorldSourceConnector> connectors) {
        try (IngestTelemetry telemetry = telemetryFactory.get()) {
            return runTraced(env, workDir, telemetry.tracer(), connectors);
        }
    }

    private static int runTraced(
            Map<String, String> env,
            Path workDir,
            Tracer tracer,
            Function<String, WorldSourceConnector> connectors) {
        IngestConfig config;
        try {
            config = IngestConfig.fromEnv(env);
        } catch (IngestConfig.ConfigurationException e) {
            // Reached before any connector is touched or any directory is created -- see
            // IngestConfig.fromEnv's contract. No run span either: there is no run to describe,
            // and deliberately no phase= marker -- the operator reads those back out of the log
            // (IngestLogProgress) and a job that never started has no phase to report.
            LOGGER.error("ERROR: {}", e.getMessage());
            return EXIT_CONFIGURATION_ERROR;
        }

        Span runSpan = tracer.spanBuilder("ingest run")
                .setAttribute("apus.source.type", config.sourceType())
                .setAttribute("apus.tenant", config.bundleTenant())
                .setAttribute("apus.source.name", config.bundleSourceName())
                .setAttribute("apus.world.id", config.bundleWorldId())
                .setAttribute("apus.world.name", config.worldName())
                .setAttribute("apus.bundle.version", config.bundleVersion())
                .setAttribute("apus.source.version", config.sourceVersionId())
                .startSpan();
        try (Scope ignored = runSpan.makeCurrent()) {
            LOGGER.info(
                    "phase=Pending source={} world={} bundle={}/{}/{}",
                    config.sourceType(),
                    config.worldName(),
                    config.bundleTenant(),
                    config.bundleWorldId(),
                    config.bundleVersion());

            Files.createDirectories(workDir);

            extract(config, workDir, tracer, connectors);
            WorldLayout layout = transform(config, workDir, tracer);
            String bundlePath = load(config, layout, tracer);

            LOGGER.info("phase=Succeeded bundlePath={}", bundlePath);
            return EXIT_SUCCESS;
        } catch (LayoutDetector.LayoutDetectionException e) {
            // The message already names the paths that were actually found -- see
            // LayoutDetector's Javadoc: detection fails loudly rather than guessing.
            fail(runSpan, e);
            LOGGER.error("phase=Failed ERROR: {}", e.getMessage(), e);
            return EXIT_LAYOUT_ERROR;
        } catch (Exception e) {
            fail(runSpan, e);
            LOGGER.error("phase=Failed ERROR: {}", e.getMessage(), e);
            return EXIT_RUNTIME_ERROR;
        } finally {
            runSpan.end();
        }
    }

    /** Fetches the configured source version into {@code workDir}. */
    private static void extract(
            IngestConfig config, Path workDir, Tracer tracer, Function<String, WorldSourceConnector> connectors) {
        Span span = tracer.spanBuilder("ingest extract")
                .setAttribute("apus.source.type", config.sourceType())
                .setAttribute("apus.source.version", config.sourceVersionId())
                .startSpan();
        try (Scope ignored = span.makeCurrent()) {
            LOGGER.info("phase=Extracting");
            WorldSourceConnector connector = connectors.apply(config.sourceType());
            SourceVersion version =
                    new SourceVersion(config.sourceVersionId(), config.sourceVersionId(), Instant.EPOCH, -1);
            connector.fetch(config.sourceConfig(), version, workDir);
            recordFetchedSize(span, workDir);
        } catch (RuntimeException e) {
            fail(span, e);
            throw e;
        } finally {
            span.end();
        }
    }

    /** Detects which known world layout the fetched data is in. */
    private static WorldLayout transform(IngestConfig config, Path workDir, Tracer tracer) {
        Span span = tracer.spanBuilder("ingest transform").startSpan();
        try (Scope ignored = span.makeCurrent()) {
            LOGGER.info("phase=Transforming");
            WorldLayout layout = LayoutDetector.detect(workDir, config.worldName(), config.forcedLayout());
            span.setAttribute("apus.layout.kind", layout.kind());
            span.setAttribute("apus.layout.dimensions", layout.dimensions().size());
            LOGGER.info(
                    "detected layout kind={} dimensions={}",
                    layout.kind(),
                    layout.dimensions().keySet());
            return layout;
        } catch (RuntimeException e) {
            fail(span, e);
            throw e;
        } finally {
            span.end();
        }
    }

    /** Writes the bundle. The {@code ingest load} span itself belongs to {@link BundleWriter}. */
    private static String load(IngestConfig config, WorldLayout layout, Tracer tracer) {
        LOGGER.info("phase=Loading");
        try (software.amazon.awssdk.services.s3.S3Client awsClient = buildBundleS3Client(config)) {
            S3Client bundleS3 = S3Client.wrapping(awsClient);
            BundleWriter writer = new BundleWriter(bundleS3, config.bundleBucket(), tracer);
            ThrottledProgressSink progress = new ThrottledProgressSink(config.progressInterval());
            return writer.write(
                    config.bundleTenant(),
                    config.bundleSourceName(),
                    config.bundleWorldId(),
                    config.bundleVersion(),
                    config.sourceType(),
                    config.sourceVersionId(),
                    config.minecraftVersion(),
                    layout,
                    progress);
        }
    }

    /**
     * Puts the size of what was actually fetched on the extract span. Together with the load span's
     * byte count this is what separates "the source was huge" from "the upload was slow" after the
     * fact, so it is worth one walk of a directory tree that was just written anyway. Best effort:
     * a run must not fail because a measurement did.
     */
    private static void recordFetchedSize(Span span, Path workDir) {
        long files = 0;
        long bytes = 0;
        try (Stream<Path> tree = Files.walk(workDir)) {
            for (Path path : (Iterable<Path>) tree::iterator) {
                if (Files.isRegularFile(path)) {
                    files++;
                    bytes += Files.size(path);
                }
            }
        } catch (IOException | RuntimeException e) {
            LOGGER.debug("could not measure the fetched source data below {}", workDir, e);
            return;
        }
        span.setAttribute("apus.source.files", files);
        span.setAttribute("apus.source.bytes", bytes);
        LOGGER.info("fetched source data: {} files, {} bytes", files, bytes);
    }

    private static void fail(Span span, Throwable e) {
        span.recordException(e);
        span.setStatus(StatusCode.ERROR, String.valueOf(e.getMessage()));
    }

    private static WorldSourceConnector selectConnector(String sourceType) {
        return switch (sourceType) {
            case "s3" -> new S3SourceConnector();
            case "pterodactyl" -> new PterodactylConnector();
            case "push" -> new PushSourceConnector();
            case "upload" -> new UploadSourceConnector();
            // IngestConfig.fromEnv already rejects any other value; reaching this would mean the
            // two disagree about which source types are supported.
            default -> throw new IllegalStateException("unsupported source type: " + sourceType);
        };
    }

    private static software.amazon.awssdk.services.s3.S3Client buildBundleS3Client(IngestConfig config) {
        return software.amazon.awssdk.services.s3.S3Client.builder()
                .region(Region.of(config.s3Region()))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(config.s3AccessKey(), config.s3SecretKey())))
                .endpointOverride(URI.create(config.s3Endpoint()))
                // Bundle destinations are S3-compatible stores (MinIO, Rook/Ceph, ...), not real
                // AWS -- see S3SourceConnector.buildClient for the same reasoning.
                .forcePathStyle(true)
                .build();
    }
}
