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

import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

/**
 * The one S3 write operation {@link BundleWriter} needs, kept deliberately narrow so tests can
 * substitute an in-memory fake instead of talking to real S3-compatible storage.
 */
public interface S3Client {

    /**
     * Uploads {@code content} to {@code bucket} under {@code key}, overwriting any object
     * already there.
     */
    void putObject(String bucket, String key, byte[] content);

    /**
     * Wraps a real AWS SDK v2 {@link software.amazon.awssdk.services.s3.S3Client} so it can be
     * passed to {@link BundleWriter}.
     */
    static S3Client wrapping(software.amazon.awssdk.services.s3.S3Client delegate) {
        return (bucket, key, content) -> delegate.putObject(
                PutObjectRequest.builder().bucket(bucket).key(key).build(), RequestBody.fromBytes(content));
    }
}
