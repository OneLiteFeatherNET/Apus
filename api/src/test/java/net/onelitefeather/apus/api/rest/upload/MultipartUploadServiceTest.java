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

import net.onelitefeather.apus.api.rest.support.BadRequestException;
import org.junit.jupiter.api.Test;

/**
 * Two kinds of proof, deliberately kept apart:
 *
 * <ul>
 *   <li>{@link #stagingKey} confinement -- pure-function tests, no S3/Micronaut involved at all,
 *       proving the structural guarantee that a staged object's key can never leave {@code
 *       <prefix>/<namespace>/<sourceName>/...} no matter what {@code version}/{@code fileName} an
 *       adversarial caller supplies.
 *   <li>request validation -- {@link #createUpload}/{@link #completeUpload} reject malformed
 *       input *before* ever calling the injected {@link software.amazon.awssdk.services.s3.S3Client}/
 *       {@link software.amazon.awssdk.services.s3.presigner.S3Presigner}, so these tests
 *       construct the service with {@code null} for both -- exercising exactly the code paths
 *       that never dereference them, without needing a mocking framework this project does not
 *       otherwise depend on.
 * </ul>
 *
 * <p>The good-path proof that a presigned URL really is confined to its signed key/size against a
 * real S3-compatible backend is {@code MultipartUploadServiceIntegrationTest}'s job instead (real
 * MinIO via Testcontainers, Docker required, excluded from this module's default {@code test}
 * task exactly like {@code TenantIsolationIntegrationTest}).
 */
class MultipartUploadServiceTest {

    @Test
    void stagingKeyIsAlwaysConfinedToThePrefixNamespaceAndSourceSubtree() {
        String key = MultipartUploadService.stagingKey("staging/", "bluemap-acme", "survival", "v1", "world.zip");

        assertEquals("staging/bluemap-acme/survival/v1/world.zip", key);
    }

    @Test
    void stagingKeyNormalisesAPrefixMissingItsTrailingSlash() {
        String key = MultipartUploadService.stagingKey("staging", "bluemap-acme", "survival", "v1", "world.zip");

        assertEquals("staging/bluemap-acme/survival/v1/world.zip", key);
    }

    @Test
    void stagingKeyCannotBeEscapedByAnAdversarialVersionOrFileName() {
        // S3 keys have no ".."-traversal semantics, so these are just unusual literal key
        // segments -- but the property under test is that the namespace segment is untouched
        // regardless: whatever a caller supplies for version/fileName, the object still lives
        // strictly under this tenant's own namespace/sourceName subtree, never another tenant's.
        String key = MultipartUploadService.stagingKey(
                "staging/", "bluemap-acme", "survival", "../../bluemap-globex/other-source", "../../evil.zip");

        assertTrue(
                key.startsWith("staging/bluemap-acme/survival/"),
                "key must stay under the caller's own namespace/source subtree, was: " + key);
    }

    @Test
    void namespaceIsTheOnlyInputThatDeterminesTheTenantPrefix() {
        String acmeKey = MultipartUploadService.stagingKey("staging/", "bluemap-acme", "survival", "v1", "world.zip");
        String globexKey = MultipartUploadService.stagingKey("staging/", "bluemap-globex", "survival", "v1", "world.zip");

        assertTrue(acmeKey.startsWith("staging/bluemap-acme/"));
        assertTrue(globexKey.startsWith("staging/bluemap-globex/"));
        assertTrue(!acmeKey.equals(globexKey));
    }

    private MultipartUploadService serviceWithoutS3() {
        return new MultipartUploadService(null, null, "staging-bucket", "staging/", 67_108_864L, 10_737_418_240L, 900L);
    }

    @Test
    void createUploadRejectsANonPositiveDeclaredSize() {
        MultipartUploadService service = serviceWithoutS3();

        assertThrows(
                BadRequestException.class, () -> service.createUpload("bluemap-acme", "survival", "world.zip", 0));
        assertThrows(
                BadRequestException.class, () -> service.createUpload("bluemap-acme", "survival", "world.zip", -1));
    }

    @Test
    void createUploadRejectsADeclaredSizeAboveTheConfiguredMaximum() {
        MultipartUploadService service = serviceWithoutS3();

        assertThrows(
                BadRequestException.class,
                () -> service.createUpload("bluemap-acme", "survival", "world.zip", 10_737_418_240L + 1));
    }

    @Test
    void createUploadRejectsAFileNameWithAPathSeparator() {
        MultipartUploadService service = serviceWithoutS3();

        assertThrows(
                BadRequestException.class,
                () -> service.createUpload("bluemap-acme", "survival", "../evil/world.zip", 1024));
    }

    @Test
    void createUploadRejectsABlankFileName() {
        MultipartUploadService service = serviceWithoutS3();

        assertThrows(BadRequestException.class, () -> service.createUpload("bluemap-acme", "survival", "", 1024));
        assertThrows(BadRequestException.class, () -> service.createUpload("bluemap-acme", "survival", null, 1024));
    }

    @Test
    void completeUploadRejectsAMalformedVersion() {
        MultipartUploadService service = serviceWithoutS3();

        assertThrows(
                BadRequestException.class,
                () -> service.completeUpload("bluemap-acme", "survival", "../../bluemap-globex", "world.zip", "up-1"));
    }

    @Test
    void completeUploadRejectsABlankUploadId() {
        MultipartUploadService service = serviceWithoutS3();

        assertThrows(
                BadRequestException.class,
                () -> service.completeUpload("bluemap-acme", "survival", "v1", "world.zip", ""));
    }
}
