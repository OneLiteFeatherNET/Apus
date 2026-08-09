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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

/**
 * Proves the push/upload ingest path end to end, against a real MinIO instance: world data
 * staged in a prefix (as {@code paper-worldpush} or the UI's presigned upload would leave it),
 * an ingest job of type {@code push}/{@code upload} run via {@link IngestMain#run}, and a valid
 * bundle with a manifest produced from it -- the same "start MinIO via Testcontainers" pattern
 * {@code S3SourceConnectorTest} and {@code AbstractStagedSourceConnectorTest} already use in this
 * module (see {@code connector/} package), just driving the whole job rather than one connector
 * method.
 *
 * <p>Before this test existed, {@code IngestConfig} rejected both source types outright (see the
 * removed "have no connector yet" message) -- {@code PushSourceConnector} and {@code
 * UploadSourceConnector} existed but nothing ever reached them from a real {@code
 * APUS_SOURCE_TYPE} value. This is the proof that the wiring (this module's {@code IngestConfig}
 * and {@code IngestMain}) now carries a push/upload ingest all the way to a valid bundle, not
 * just that the connector class itself behaves correctly in isolation.
 *
 * <p>Needs Docker; excluded from {@code :ingest:test} and run only via {@code
 * :ingest:integrationTest} -- see {@code ingest/build.gradle.kts} and {@code ingest/README.md}.
 */
@Testcontainers
class PushIngestEndToEndTest {

    private static final String STAGING_BUCKET = "staging";
    private static final String BUNDLE_BUCKET = "bundles";

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
    static void createClientAndBuckets() {
        sharedClient = S3Client.builder()
                .endpointOverride(URI.create(MINIO.getS3URL()))
                .region(Region.US_EAST_1)
                .credentialsProvider(
                        StaticCredentialsProvider.create(AwsBasicCredentials.create(ACCESS_KEY, SECRET_KEY)))
                .forcePathStyle(true)
                .build();
        sharedClient.createBucket(CreateBucketRequest.builder().bucket(STAGING_BUCKET).build());
        sharedClient.createBucket(CreateBucketRequest.builder().bucket(BUNDLE_BUCKET).build());
    }

    @AfterAll
    static void closeClient() {
        sharedClient.close();
    }

    /**
     * Runs the same scenario for both push-style source types: a Paper server ({@code push}) and
     * a browser upload ({@code upload}) stage their world data identically (design spec §6.1,
     * §11.1) and are handled by the same {@code AbstractStagedSourceConnector} logic, so both
     * must come out the other end of {@code IngestMain} the same way.
     */
    @ParameterizedTest(name = "sourceType={0}")
    @ValueSource(strings = {"push", "upload"})
    void stagedWorldDataProducesAValidBundleWithManifest(String sourceType, @TempDir Path workDir) throws IOException {
        String tenant = "acme";
        String sourceName = "survival-source-" + sourceType;
        String worldId = "survival";
        String bundleVersion = "v1";
        String stagingPrefix = tenant + "/" + sourceName + "/";
        String sourceVersionId = "2026-08-09T00-00-00Z.zip";

        byte[] zip = buildZip(Map.of(
                "world/level.dat", "level-dat-bytes",
                "world/region/r.0.0.mca", "region-data-bytes"));
        sharedClient.putObject(
                PutObjectRequest.builder()
                        .bucket(STAGING_BUCKET)
                        .key(stagingPrefix + sourceVersionId)
                        .build(),
                RequestBody.fromBytes(zip));

        Map<String, String> env = new LinkedHashMap<>();
        env.put(IngestConfig.ENV_SOURCE_TYPE, sourceType);
        env.put(IngestConfig.ENV_WORLD_NAME, "world");
        env.put(IngestConfig.ENV_SOURCE_VERSION, sourceVersionId);
        env.put(IngestConfig.ENV_BUNDLE_BUCKET, BUNDLE_BUCKET);
        env.put(IngestConfig.ENV_BUNDLE_TENANT, tenant);
        env.put(IngestConfig.ENV_BUNDLE_SOURCE_NAME, sourceName);
        env.put(IngestConfig.ENV_BUNDLE_WORLD_ID, worldId);
        env.put(IngestConfig.ENV_BUNDLE_VERSION, bundleVersion);
        env.put(IngestConfig.ENV_S3_ENDPOINT, MINIO.getS3URL());
        env.put(IngestConfig.ENV_S3_ACCESS_KEY, ACCESS_KEY);
        env.put(IngestConfig.ENV_S3_SECRET_KEY, SECRET_KEY);
        env.put(IngestConfig.ENV_MC_VERSION, "1.21.10");
        env.put(IngestConfig.ENV_SOURCE_STAGING_ENDPOINT, MINIO.getS3URL());
        env.put(IngestConfig.ENV_SOURCE_STAGING_BUCKET, STAGING_BUCKET);
        env.put(IngestConfig.ENV_SOURCE_STAGING_PREFIX, stagingPrefix);
        env.put(IngestConfig.ENV_SOURCE_STAGING_ACCESS_KEY, ACCESS_KEY);
        env.put(IngestConfig.ENV_SOURCE_STAGING_SECRET_KEY, SECRET_KEY);

        int exitCode = IngestMain.run(env, workDir.resolve("work"));

        assertEquals(0, exitCode, "ingest of a staged " + sourceType + " source must succeed end to end");

        String bundlePath = BundlePath.of(tenant, sourceName, worldId, bundleVersion);
        String manifestJson = getObjectAsString(BUNDLE_BUCKET, bundlePath + "/manifest.json");
        BundleManifest manifest = BundleManifest.fromJson(manifestJson);

        assertEquals(tenant, manifest.tenant());
        assertEquals(worldId, manifest.worldId());
        assertEquals(bundleVersion, manifest.version());
        assertEquals(sourceType, manifest.source().type());
        assertEquals(sourceVersionId, manifest.source().ref());
        assertEquals("vanilla", manifest.source().detectedLayout());
        assertEquals("1.21.10", manifest.minecraftVersion());
        assertEquals(1, manifest.dimensions().size());
        assertTrue(manifest.sizeBytes() > 0);
        assertNotNull(manifest.checksums().manifest());

        // The region file the manifest describes must actually be present under the bundle path
        // -- the manifest is only the commit point, not proof on its own that the data exists.
        String regionObject = getObjectAsString(
                BUNDLE_BUCKET, manifest.dimensions().get(0).path() + "/region/r.0.0.mca");
        assertEquals("region-data-bytes", regionObject);
    }

    private static String getObjectAsString(String bucket, String key) throws IOException {
        try (var object = sharedClient.getObject(
                GetObjectRequest.builder().bucket(bucket).key(key).build())) {
            return new String(object.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static byte[] buildZip(Map<String, String> entries) throws IOException {
        var buffer = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(buffer)) {
            for (var entry : entries.entrySet()) {
                zip.putNextEntry(new ZipEntry(entry.getKey()));
                zip.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        return buffer.toByteArray();
    }
}
