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
package net.onelitefeather.apus.api.rest.upload;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.SecureRandom;
import net.onelitefeather.apus.api.rest.support.BadRequestException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MinIOContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.NoSuchUploadException;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

/**
 * Drives real HTTP requests against a real MinIO instance to answer, empirically rather than by
 * reading AWS SDK documentation, the question the phase 6 task brief poses: which of {@code POST
 * /api/uploads}'s stated limits ("eng begrenzt: auf das Staging-Präfix genau dieses Mandanten,
 * mit kurzer Gültigkeit und einer Größenbegrenzung") actually hold up against an adversarial
 * client, and which do not. See the phase 6 task report for how each result here is interpreted.
 *
 * <p>Excluded from {@code :api:test} (matches {@code TenantIsolationIntegrationTest}'s own
 * Docker/{@code *IntegrationTest} exclusion in {@code build.gradle.kts}) -- run explicitly via
 * {@code ./gradlew :api:integrationTest}.
 */
@Testcontainers
class MultipartUploadServiceIntegrationTest {

    private static final String BUCKET = "staging";

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

    private static S3Client s3Client;
    private static S3Presigner presigner;
    private static final HttpClient HTTP = HttpClient.newHttpClient();

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
    static void createClientsAndBucket() {
        var credentials = StaticCredentialsProvider.create(AwsBasicCredentials.create(ACCESS_KEY, SECRET_KEY));
        s3Client = S3Client.builder()
                .endpointOverride(URI.create(MINIO.getS3URL()))
                .region(Region.US_EAST_1)
                .credentialsProvider(credentials)
                .forcePathStyle(true)
                .build();
        presigner = S3Presigner.builder()
                .endpointOverride(URI.create(MINIO.getS3URL()))
                .region(Region.US_EAST_1)
                .credentialsProvider(credentials)
                .serviceConfiguration(S3Configuration.builder()
                        .pathStyleAccessEnabled(true)
                        .checksumValidationEnabled(false)
                        .build())
                .build();
        s3Client.createBucket(CreateBucketRequest.builder().bucket(BUCKET).build());
    }

    @AfterAll
    static void closeClients() {
        s3Client.close();
        presigner.close();
    }

    /** 5 MiB -- the S3/Ceph minimum part size (except the last part), kept small so tests stay fast. */
    private static final long PART_SIZE = 5L * 1024 * 1024;

    private MultipartUploadService service(long maxUploadBytes) {
        return new MultipartUploadService(s3Client, presigner, BUCKET, "staging/", PART_SIZE, maxUploadBytes, 900);
    }

    @Test
    void endToEndUploadLandsExactlyAtTheExpectedKeyWithTheRealUploadedBytes() throws Exception {
        long totalSize = PART_SIZE + 1024; // forces two parts: one full 5 MiB, one small tail
        var created = service(10L * 1024 * 1024 * 1024).createUpload("bluemap-acme", "survival", "world.zip", totalSize);

        assertEquals("staging/bluemap-acme/survival/" + created.version() + "/world.zip", created.key());
        assertEquals(2, created.parts().size());

        for (var part : created.parts()) {
            putPart(part.url(), randomBytes((int) part.sizeBytes()));
        }

        var completed = service(10L * 1024 * 1024 * 1024)
                .completeUpload("bluemap-acme", "survival", created.version(), "world.zip", created.uploadId());

        assertEquals(totalSize, completed.totalBytes());
        assertEquals(created.key(), completed.key());
        var stored = s3Client.getObject(b -> b.bucket(BUCKET).key(created.key()));
        assertEquals(totalSize, stored.response().contentLength());
    }

    @Test
    void aPresignedPartUrlCannotBeRedirectedToADifferentTenantsKey() throws Exception {
        var created = service(10L * 1024 * 1024 * 1024)
                .createUpload("bluemap-acme", "survival", "world.zip", PART_SIZE);
        String legitimateUrl = created.parts().get(0).url();

        // Swap the tenant namespace segment in the signed URL's path while keeping every other
        // character -- including the whole SigV4 query string -- untouched. If prefix confinement
        // were only advisory (e.g. enforced solely by application logic that a modified request
        // never goes through), this would succeed. It must instead fail the signature check.
        String tamperedUrl = legitimateUrl.replace("/bluemap-acme/", "/bluemap-globex/");
        assertTrue(!tamperedUrl.equals(legitimateUrl), "the tamper must actually change the URL");

        HttpResponse<String> response = putPartExpectingFailure(tamperedUrl, randomBytes((int) PART_SIZE));

        assertEquals(
                403,
                response.statusCode(),
                "S3 must reject a presigned URL whose key was altered after signing, got body: " + response.body());
    }

    @Test
    void completeUploadAbortsAndRejectsWhenTheActualUploadedTotalExceedsTheConfiguredMaximum() throws Exception {
        // maxUploadBytes deliberately smaller than what actually gets uploaded below -- proves
        // enforcement happens against the real ListParts total, not the originally declared size.
        long generousDeclaredSize = 3 * PART_SIZE;
        long tinyMax = PART_SIZE; // one part's worth -- the upload below will exceed this

        var created = service(generousDeclaredSize).createUpload("bluemap-acme", "survival", "world.zip", generousDeclaredSize);
        for (var part : created.parts()) {
            putPart(part.url(), randomBytes((int) part.sizeBytes()));
        }

        MultipartUploadService strict = service(tinyMax);
        assertThrows(
                BadRequestException.class,
                () -> strict.completeUpload("bluemap-acme", "survival", created.version(), "world.zip", created.uploadId()));

        // The upload must actually be gone (aborted), not just rejected at the API layer --
        // otherwise the uploaded-but-oversized parts would sit in the bucket indefinitely.
        assertThrows(
                NoSuchUploadException.class,
                () -> s3Client.listParts(
                        b -> b.bucket(BUCKET).key(created.key()).uploadId(created.uploadId())));
    }

    /**
     * Confirms empirically (against real MinIO, 2026-08-09) that {@code Content-Length} pinning
     * on a presigned {@code UploadPart} request genuinely constrains the byte count a client can
     * send for that part: sending more bytes than the part was presigned/sized for is rejected
     * with HTTP 403 {@code SignatureDoesNotMatch} before those extra bytes are accepted --
     * {@code Content-Length} was a signed header, and the actual request no longer matches what
     * was signed. See the phase 6 task report for the full write-up (including the caveat that
     * this was verified against MinIO specifically, not independently against Ceph RGW, the
     * production backend design spec §9.1 names -- both implement SigV4 the same way, but that is
     * an inference, not a second empirical confirmation).
     */
    @Test
    void aPartExceedingItsPresignedSizeIsRejectedBeforeItIsAccepted() throws Exception {
        long declaredPartSize = PART_SIZE;
        var created = service(10L * 1024 * 1024 * 1024).createUpload("bluemap-acme", "survival", "world.zip", declaredPartSize);
        String partUrl = created.parts().get(0).url();

        byte[] oversizedBody = randomBytes((int) (declaredPartSize + 1024));
        HttpResponse<String> response = putPartExpectingFailure(partUrl, oversizedBody);

        assertEquals(
                403,
                response.statusCode(),
                "an oversized part must be rejected by the signature check, got body: " + response.body());
        assertTrue(response.body().contains("SignatureDoesNotMatch"));
    }

    private void putPart(String url, byte[] body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .PUT(HttpRequest.BodyPublishers.ofByteArray(body))
                .build();
        HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() / 100 != 2) {
            throw new AssertionError("part upload failed: " + response.statusCode() + " " + response.body());
        }
    }

    private HttpResponse<String> putPartExpectingFailure(String url, byte[] body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .PUT(HttpRequest.BodyPublishers.ofByteArray(body))
                .build();
        return HTTP.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static byte[] randomBytes(int size) {
        byte[] data = new byte[size];
        new java.util.Random(42).nextBytes(data);
        return data;
    }
}
