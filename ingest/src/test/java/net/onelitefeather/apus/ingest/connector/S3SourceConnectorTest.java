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

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
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
import org.testcontainers.containers.MinIOContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
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
 * <p>MinIO is started via Testcontainers ({@link MinIOContainer}), the same mechanism the
 * {@code operator} and {@code runner} modules already use for their own container-based tests.
 * Testcontainers reaps the container even if a test crashes, which the previous {@code
 * ProcessBuilder}-driven {@code docker run}/{@code docker stop} pairing here could not guarantee.
 */
@Testcontainers
class S3SourceConnectorTest {

    private static final String BUCKET = "worlds";

    // Generated fresh per test run rather than pinned to a fixed literal, so nothing checked
    // into source ever looks like a real credential. Lengths follow MinIO's own
    // accessKeyMinLen/secretKeyMinLen (3 / 8 characters) with generous headroom.
    private static final String ACCESS_KEY = randomAlphanumeric(20);
    private static final String SECRET_KEY = randomAlphanumeric(40);

    @Container
    private static final MinIOContainer MINIO =
            new MinIOContainer(DockerImageName.parse("minio/minio:RELEASE.2024-11-07T00-52-20Z"))
                    .withUserName(ACCESS_KEY)
                    .withPassword(SECRET_KEY);

    private static S3Client sharedClient;

    /** Generates a random alphanumeric string of {@code length} characters. */
    private static String randomAlphanumeric(int length) {
        String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
        SecureRandom random = new SecureRandom();
        StringBuilder value = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            value.append(alphabet.charAt(random.nextInt(alphabet.length())));
        }
        return value.toString();
    }

    @BeforeAll
    static void createClientAndBucket() {
        sharedClient = S3Client.builder()
                .endpointOverride(URI.create(MINIO.getS3URL()))
                .region(Region.US_EAST_1)
                .credentialsProvider(
                        StaticCredentialsProvider.create(AwsBasicCredentials.create(ACCESS_KEY, SECRET_KEY)))
                .forcePathStyle(true)
                .build();
        sharedClient.createBucket(CreateBucketRequest.builder().bucket(BUCKET).build());
    }

    @AfterAll
    static void closeClient() {
        sharedClient.close();
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
}
