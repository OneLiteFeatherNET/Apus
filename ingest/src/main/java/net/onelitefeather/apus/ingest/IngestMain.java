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

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import net.onelitefeather.apus.ingest.connector.PterodactylConnector;
import net.onelitefeather.apus.ingest.connector.S3SourceConnector;
import net.onelitefeather.apus.ingest.connector.SourceVersion;
import net.onelitefeather.apus.ingest.connector.WorldSourceConnector;
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
 */
public final class IngestMain {

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
        IngestConfig config;
        try {
            config = IngestConfig.fromEnv(env);
        } catch (IngestConfig.ConfigurationException e) {
            // Reached before any connector is touched or any directory is created -- see
            // IngestConfig.fromEnv's contract.
            System.err.println("[apus-ingest] ERROR: " + e.getMessage());
            return EXIT_CONFIGURATION_ERROR;
        }

        log("phase=Pending source=%s world=%s bundle=%s/%s/%s"
                .formatted(
                        config.sourceType(),
                        config.worldName(),
                        config.bundleTenant(),
                        config.bundleWorldId(),
                        config.bundleVersion()));

        try {
            Files.createDirectories(workDir);

            log("phase=Extracting");
            WorldSourceConnector connector = selectConnector(config.sourceType());
            SourceVersion version =
                    new SourceVersion(config.sourceVersionId(), config.sourceVersionId(), Instant.EPOCH, -1);
            connector.fetch(config.sourceConfig(), version, workDir);

            log("phase=Transforming");
            WorldLayout layout = LayoutDetector.detect(workDir, config.worldName(), config.forcedLayout());
            log("detected layout kind=%s dimensions=%s".formatted(layout.kind(), layout.dimensions().keySet()));

            log("phase=Loading");
            String bundlePath = writeBundle(config, layout);

            log("phase=Succeeded bundlePath=" + bundlePath);
            return EXIT_SUCCESS;
        } catch (LayoutDetector.LayoutDetectionException e) {
            // The message already names the paths that were actually found -- see
            // LayoutDetector's Javadoc: detection fails loudly rather than guessing.
            System.err.println("[apus-ingest] phase=Failed ERROR: " + e.getMessage());
            return EXIT_LAYOUT_ERROR;
        } catch (Exception e) {
            System.err.println("[apus-ingest] phase=Failed ERROR: " + e.getMessage());
            return EXIT_RUNTIME_ERROR;
        }
    }

    private static String writeBundle(IngestConfig config, WorldLayout layout) {
        try (software.amazon.awssdk.services.s3.S3Client awsClient = buildBundleS3Client(config)) {
            S3Client bundleS3 = S3Client.wrapping(awsClient);
            BundleWriter writer = new BundleWriter(bundleS3, config.bundleBucket());
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

    private static WorldSourceConnector selectConnector(String sourceType) {
        return switch (sourceType) {
            case "s3" -> new S3SourceConnector();
            case "pterodactyl" -> new PterodactylConnector();
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

    private static void log(String message) {
        System.out.println("[apus-ingest] " + message);
    }
}
