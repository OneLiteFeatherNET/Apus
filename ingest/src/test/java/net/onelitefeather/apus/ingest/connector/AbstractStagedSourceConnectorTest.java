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
import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
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
 * Behaviour shared by {@link PushSourceConnectorTest} and {@link UploadSourceConnectorTest}: both
 * connectors delegate everything to {@link AbstractStagedSourceConnector}, so both need to prove
 * the same three things against a real MinIO instance -- {@code discover} is always empty (push
 * semantics), a staged archive is extracted, and a staged plain file is copied as-is. Only the
 * {@code type()} discriminator differs between the two connectors, which each subclass's own
 * (much smaller) test class asserts on top of what is proven here.
 *
 * <p>Mirrors {@code S3SourceConnectorTest}'s own Testcontainers setup deliberately -- see that
 * class's Javadoc for why a real MinIO instance is used instead of a hand-rolled stub.
 */
@Testcontainers
abstract class AbstractStagedSourceConnectorTest {

    private static final String BUCKET = "worlds";
    private static final String ACCESS_KEY = "minioadmin";
    private static final String SECRET_KEY = "minioadmin";

    @Container
    private static final MinIOContainer MINIO =
            new MinIOContainer(DockerImageName.parse("minio/minio:RELEASE.2024-11-07T00-52-20Z"))
                    .withUserName(ACCESS_KEY)
                    .withPassword(SECRET_KEY);

    private static S3Client sharedClient;

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

    /** The connector under test -- supplied by the concrete subclass. */
    abstract AbstractStagedSourceConnector connector();

    @Test
    void discoverAlwaysReportsNoVersionsRegardlessOfWhatIsStaged() {
        String prefix = "discover-test/" + getClass().getSimpleName() + "/";
        putObject(prefix + "some-staged-object.zip", "irrelevant");

        assertEquals(java.util.List.of(), connector().discover(config(prefix)));
    }

    @Test
    void fetchOfAZipVersionExtractsItsEntriesIntoTheWorkDirectory(@TempDir Path workDir) throws IOException {
        String prefix = "zip-test/" + getClass().getSimpleName() + "/";
        byte[] zip = buildZip(Map.of(
                "level.dat", "level-data",
                "region/r.0.0.mca", "region-data"));
        putObject(prefix + "v1.zip", zip);

        connector()
                .fetch(
                        sharedClient,
                        config(prefix),
                        new SourceVersion("v1.zip", "v1.zip", Instant.now(), zip.length),
                        workDir);

        assertEquals("level-data", Files.readString(workDir.resolve("level.dat")));
        assertEquals("region-data", Files.readString(workDir.resolve("region/r.0.0.mca")));
    }

    @Test
    void fetchOfATarGzVersionExtractsItsEntriesIntoTheWorkDirectory(@TempDir Path workDir) throws IOException {
        String prefix = "targz-test/" + getClass().getSimpleName() + "/";
        byte[] tarGz = new TestTarBuilder()
                .addFile("level.dat", "level-data")
                .addFile("region/r.0.0.mca", "region-data")
                .toGzippedTarBytes();
        putObject(prefix + "v2.tar.gz", tarGz);

        connector()
                .fetch(
                        sharedClient,
                        config(prefix),
                        new SourceVersion("v2.tar.gz", "v2.tar.gz", Instant.now(), tarGz.length),
                        workDir);

        assertEquals("level-data", Files.readString(workDir.resolve("level.dat")));
        assertEquals("region-data", Files.readString(workDir.resolve("region/r.0.0.mca")));
    }

    @Test
    void fetchOfAPlainVersionWritesItAsASingleRawFile(@TempDir Path workDir) throws IOException {
        String prefix = "raw-test/" + getClass().getSimpleName() + "/";
        putObject(prefix + "raw-dump.bin", "not-an-archive");

        connector()
                .fetch(
                        sharedClient,
                        config(prefix),
                        new SourceVersion("raw-dump.bin", "raw-dump.bin", Instant.now(), 14),
                        workDir);

        assertTrue(Files.exists(workDir.resolve("raw-dump.bin")));
        assertEquals("not-an-archive", Files.readString(workDir.resolve("raw-dump.bin")));
    }

    private Map<String, String> config(String prefix) {
        Map<String, String> config = new HashMap<>();
        config.put(AbstractStagedSourceConnector.CONFIG_BUCKET, BUCKET);
        config.put(AbstractStagedSourceConnector.CONFIG_PREFIX, prefix);
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
