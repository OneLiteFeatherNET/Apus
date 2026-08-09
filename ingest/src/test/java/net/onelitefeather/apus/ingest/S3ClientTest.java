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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;

class S3ClientTest {

    /** A hand-written double for the AWS SDK's own client: only {@code putObject} is overridden. */
    private static final class RecordingSdkS3Client implements software.amazon.awssdk.services.s3.S3Client {
        private PutObjectRequest lastRequest;
        private byte[] lastBody;

        @Override
        public PutObjectResponse putObject(PutObjectRequest putObjectRequest, RequestBody requestBody) {
            this.lastRequest = putObjectRequest;
            try {
                this.lastBody = requestBody.contentStreamProvider().newStream().readAllBytes();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            return PutObjectResponse.builder().build();
        }

        @Override
        public String serviceName() {
            return "s3";
        }

        @Override
        public void close() {}
    }

    @Test
    void wrappingDelegatesBucketKeyAndContentToTheSdkClient() {
        RecordingSdkS3Client delegate = new RecordingSdkS3Client();
        S3Client facade = S3Client.wrapping(delegate);
        byte[] content = "region bytes".getBytes(StandardCharsets.UTF_8);

        facade.putObject("my-bucket", "acme/world/v1/manifest.json", content);

        assertEquals("my-bucket", delegate.lastRequest.bucket());
        assertEquals("acme/world/v1/manifest.json", delegate.lastRequest.key());
        assertArrayEquals(content, delegate.lastBody);
    }
}
