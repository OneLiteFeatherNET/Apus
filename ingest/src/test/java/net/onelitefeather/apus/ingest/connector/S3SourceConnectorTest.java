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
package net.onelitefeather.apus.ingest.connector;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

/**
 * Exercises {@link S3SourceConnector} against a real MinIO instance -- listing/versioning
 * semantics (delimiter-scoped listing, last-modified/size metadata) are exactly the kind of
 * behaviour a hand-rolled stub would get subtly wrong.
 *
 * <p>The brief for this task asks for this to run against "a MinIO Testcontainer". This class
 * drives MinIO through the plain {@code docker} CLI via {@link ProcessBuilder} instead of the
 * {@code org.testcontainers} library: that library is not a dependency of this module, and this
 * task's file restriction (only {@code ingest/.../connector/*} and its tests -- no build files)
 * means the dependency cannot be added from here. See {@code task-4-report.md} for the exact
 * one-line fix and why it was not applied. Functionally this achieves the same thing the brief
 * asks for -- a real, disposable MinIO container, started and torn down per test run -- just
 * without the Testcontainers library itself. The container binds its published port to {@code
 * 127.0.0.1} only, and is force-removed in {@link #stopMinio()} regardless of individual test
 * outcomes.
 */
class S3SourceConnectorTest {

    private static final String IMAGE = "minio/minio:RELEASE.2024-11-07T00-52-20Z";
    private static final String BUCKET = "worlds";
    private static final String ACCESS_KEY = "minioadmin";
    private static final String SECRET_KEY = "minioadmin";
    private static final Duration READY_TIMEOUT = Duration.ofSeconds(30);

    private static String containerName;
    private static S3Client sharedClient;

    @BeforeAll
    static void startMinioAndClient() throws IOException, InterruptedException {
        assumeTrue(isDockerAvailable(), "docker CLI is required for this test and is not available here");

        containerName = "apus-ingest-test-minio-" + System.nanoTime();
        int exitCode = runDocker(
                "run",
                "-d",
                "--rm",
                "--name",
                containerName,
                "-p",
                "127.0.0.1::9000",
                "-e",
                "MINIO_ROOT_USER=" + ACCESS_KEY,
                "-e",
                "MINIO_ROOT_PASSWORD=" + SECRET_KEY,
                IMAGE,
                "server",
                "/data");
        if (exitCode != 0) {
            containerName = null;
            throw new IllegalStateException("docker run failed for the MinIO test container, exit code " + exitCode);
        }

        try {
            String endpoint = "http://" + publishedAddress();
            awaitReady(endpoint);

            sharedClient = S3Client.builder()
                    .endpointOverride(URI.create(endpoint))
                    .region(Region.US_EAST_1)
                    .credentialsProvider(
                            StaticCredentialsProvider.create(AwsBasicCredentials.create(ACCESS_KEY, SECRET_KEY)))
                    .forcePathStyle(true)
                    .build();
            sharedClient.createBucket(
                    CreateBucketRequest.builder().bucket(BUCKET).build());
        } catch (RuntimeException | IOException | InterruptedException e) {
            stopMinio();
            throw e;
        }
    }

    @AfterAll
    static void stopMinio() throws IOException, InterruptedException {
        if (sharedClient != null) {
            sharedClient.close();
            sharedClient = null;
        }
        if (containerName != null) {
            runDocker("stop", containerName);
            containerName = null;
        }
    }

    private final S3SourceConnector connector = new S3SourceConnector();

    @Test
    void discoverReportsOneVersionPerObjectDirectlyUnderThePrefix() {
        String prefix = "discover-test/";
        putObject(prefix + "2026-08-01T00-00-00Z.zip", "zip-bytes-placeholder");
        putObject(prefix + "2026-08-02T00-00-00Z.tar.gz", "tar-gz-bytes-placeholder");
        // an object one level deeper must not be mistaken for a version of its own
        putObject(prefix + "2026-08-02T00-00-00Z.tar.gz/unexpected-nested-object", "noise");

        List<SourceVersion> versions = connector.discover(sharedClient, config(prefix));

        Set<String> ids = versions.stream().map(SourceVersion::id).collect(Collectors.toSet());
        assertEquals(Set.of("2026-08-01T00-00-00Z.zip", "2026-08-02T00-00-00Z.tar.gz"), ids);
    }

    @Test
    void fetchOfAZipKeyExtractsItsEntriesIntoTheWorkDirectory(@TempDir Path workDir) throws IOException {
        String prefix = "zip-test/";
        byte[] zip = buildZip(Map.of(
                "level.dat", "level-data",
                "region/r.0.0.mca", "region-data"));
        putObject(prefix + "v1.zip", zip);

        connector.fetch(sharedClient, config(prefix), new SourceVersion("v1.zip", "v1.zip", Instant.now(), zip.length), workDir);

        assertEquals("level-data", Files.readString(workDir.resolve("level.dat")));
        assertEquals("region-data", Files.readString(workDir.resolve("region/r.0.0.mca")));
    }

    @Test
    void fetchOfATarGzKeyExtractsItsEntriesIntoTheWorkDirectory(@TempDir Path workDir) throws IOException {
        String prefix = "targz-test/";
        byte[] tarGz = new TestTarBuilder()
                .addFile("level.dat", "level-data")
                .addFile("region/r.0.0.mca", "region-data")
                .toGzippedTarBytes();
        putObject(prefix + "v2.tar.gz", tarGz);

        connector.fetch(
                sharedClient, config(prefix), new SourceVersion("v2.tar.gz", "v2.tar.gz", Instant.now(), tarGz.length), workDir);

        assertEquals("level-data", Files.readString(workDir.resolve("level.dat")));
        assertEquals("region-data", Files.readString(workDir.resolve("region/r.0.0.mca")));
    }

    @Test
    void fetchOfAPlainKeyWritesItAsASingleRawFile(@TempDir Path workDir) throws IOException {
        String prefix = "raw-test/";
        putObject(prefix + "raw-dump.bin", "not-an-archive");

        connector.fetch(
                sharedClient, config(prefix), new SourceVersion("raw-dump.bin", "raw-dump.bin", Instant.now(), 14), workDir);

        assertTrue(Files.exists(workDir.resolve("raw-dump.bin")));
        assertEquals("not-an-archive", Files.readString(workDir.resolve("raw-dump.bin")));
    }

    private Map<String, String> config(String prefix) {
        Map<String, String> config = new HashMap<>();
        config.put(S3SourceConnector.CONFIG_BUCKET, BUCKET);
        config.put(S3SourceConnector.CONFIG_PREFIX, prefix);
        return config;
    }

    private void putObject(String key, String content) {
        putObject(key, content.getBytes(StandardCharsets.UTF_8));
    }

    private void putObject(String key, byte[] content) {
        sharedClient.putObject(
                PutObjectRequest.builder().bucket(BUCKET).key(key).build(), RequestBody.fromBytes(content));
    }

    private static byte[] buildZip(Map<String, String> entries) throws IOException {
        var buffer = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(buffer)) {
            for (var entry : entries.entrySet().stream()
                    .sorted(Map.Entry.comparingByKey(Comparator.naturalOrder()))
                    .toList()) {
                zip.putNextEntry(new ZipEntry(entry.getKey()));
                zip.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        return buffer.toByteArray();
    }

    private static boolean isDockerAvailable() {
        try {
            return runDocker("info") == 0;
        } catch (IOException | InterruptedException e) {
            return false;
        }
    }

    private static String publishedAddress() throws IOException, InterruptedException {
        Process process = new ProcessBuilder("docker", "port", containerName, "9000/tcp")
                .redirectErrorStream(true)
                .start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int exit = process.waitFor();
        if (exit != 0) {
            throw new IllegalStateException("docker port failed to resolve the MinIO container's published port: " + output);
        }
        // Bound to 127.0.0.1 explicitly at `docker run` time, so exactly one "127.0.0.1:PORT" line.
        return output.lines().findFirst().orElseThrow(() -> new IllegalStateException("docker port returned no mapping"));
    }

    private static void awaitReady(String endpoint) throws InterruptedException {
        HttpClient httpClient = HttpClient.newHttpClient();
        HttpRequest healthRequest = HttpRequest.newBuilder(URI.create(endpoint + "/minio/health/live"))
                .timeout(Duration.ofSeconds(2))
                .GET()
                .build();

        Instant deadline = Instant.now().plus(READY_TIMEOUT);
        while (Instant.now().isBefore(deadline)) {
            try {
                HttpResponse<Void> response = httpClient.send(healthRequest, HttpResponse.BodyHandlers.discarding());
                if (response.statusCode() == 200) {
                    return;
                }
            } catch (IOException e) {
                // not ready yet -- keep polling until the deadline
            }
            Thread.sleep(300);
        }
        throw new IllegalStateException("MinIO test container did not become ready within " + READY_TIMEOUT);
    }

    private static int runDocker(String... args) throws IOException, InterruptedException {
        String[] command = new String[args.length + 1];
        command[0] = "docker";
        System.arraycopy(args, 0, command, 1, args.length);
        Process process = new ProcessBuilder(command).inheritIO().start();
        return process.waitFor();
    }
}
