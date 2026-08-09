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
package net.onelitefeather.apus.paper;

import java.net.URI;
import java.nio.file.Path;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

/**
 * Real {@link WorldUploader}, writing region files into the tenant's staging prefix via the AWS
 * SDK v2 S3 client (the same client family {@code ingest.S3Client} wraps, path-style access so
 * this also works unchanged against Rook/Ceph -- see {@code settings.gradle.kts}'s {@code
 * aws-sdk} version comment for the shared rationale).
 *
 * <p>Never logs {@link WorldPushConfig#s3AccessKey()}/{@link WorldPushConfig#s3SecretKey()}; they
 * are only ever passed into {@link StaticCredentialsProvider}, never rendered to a string.
 */
public final class S3WorldUploader implements WorldUploader, AutoCloseable {

    private final S3Client delegate;
    private final String bucket;

    private S3WorldUploader(S3Client delegate, String bucket) {
        this.delegate = delegate;
        this.bucket = bucket;
    }

    /** Builds a real uploader from {@code config}'s S3 settings. */
    public static S3WorldUploader create(WorldPushConfig config) {
        S3Client client = S3Client.builder()
                .region(Region.of(config.s3Region()))
                .endpointOverride(URI.create(config.s3Endpoint()))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(config.s3AccessKey(), config.s3SecretKey())))
                .forcePathStyle(true)
                .build();
        return new S3WorldUploader(client, config.s3Bucket());
    }

    @Override
    public void upload(Path localFile, String s3Key) {
        delegate.putObject(
                PutObjectRequest.builder().bucket(bucket).key(s3Key).build(),
                software.amazon.awssdk.core.sync.RequestBody.fromFile(localFile));
    }

    @Override
    public void close() {
        delegate.close();
    }
}
