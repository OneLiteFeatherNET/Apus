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

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;

/**
 * Shared extract logic for the two push-style sources ({@link PushSourceConnector}, {@link
 * UploadSourceConnector}). Both stage their payload as a single object under a prefix in S3
 * before an ingest job ever starts -- a browser upload completing a presigned multipart upload,
 * or a Paper server writing directly with its own tenant-scoped credentials (design spec §6.1,
 * §11.1). Neither reports versions of its own: whatever created the {@code WorldIngest} (the
 * {@code POST /api/uploads}/{@code POST /api/push/{token}} endpoints under the {@code api}
 * module) already knows the version id, because it is the very same id it used as the staged
 * object's key suffix when it wrote (or arranged for the client to write) the data.
 *
 * <p>{@link #discover} therefore always returns an empty list, matching the {@link
 * WorldSourceConnector#discover} contract for push sources verbatim. {@link #fetch} is otherwise
 * identical to {@link S3SourceConnector#fetch}: the staged object at {@code prefix +
 * version.id()} is either extracted (recognised archive extension) or copied as a single raw
 * file -- "the path is similar, only the origin of the version differs" (phase 6 task
 * brief). This class intentionally duplicates that small amount of S3-plumbing from {@link
 * S3SourceConnector} rather than refactoring it to share code: {@link S3SourceConnector} already
 * ships with its own passing test suite, and reaching into it here would risk that class for the
 * sake of ~40 lines saved.
 */
abstract class AbstractStagedSourceConnector implements WorldSourceConnector {

    public static final String CONFIG_ENDPOINT = "endpoint";
    public static final String CONFIG_BUCKET = "bucket";
    public static final String CONFIG_PREFIX = "prefix";
    public static final String CONFIG_ACCESS_KEY_ID = "accessKeyId";
    public static final String CONFIG_SECRET_ACCESS_KEY = "secretAccessKey";
    public static final String CONFIG_REGION = "region";

    private static final String DEFAULT_REGION = "us-east-1";

    @Override
    public final List<SourceVersion> discover(Map<String, String> config) {
        return Collections.emptyList();
    }

    @Override
    public final void fetch(Map<String, String> config, SourceVersion version, Path workDir) {
        try (S3Client client = buildClient(config)) {
            fetch(client, config, version, workDir);
        }
    }

    /** Same as {@link #fetch(Map, SourceVersion, Path)} but against an already-built client, for testing. */
    void fetch(S3Client client, Map<String, String> config, SourceVersion version, Path workDir) {
        String bucket = require(config, CONFIG_BUCKET);
        String prefix = normalisePrefix(config.get(CONFIG_PREFIX));
        String key = prefix + version.id();

        GetObjectRequest request =
                GetObjectRequest.builder().bucket(bucket).key(key).build();
        try (ResponseInputStream<GetObjectResponse> object = client.getObject(request)) {
            if (Archives.isArchive(key)) {
                Archives.extract(key, object, workDir, Archives.limitsFrom(config));
            } else {
                Path target = workDir.resolve(fileNameOf(key));
                Files.copy(object, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("failed to fetch staged object " + key, e);
        }
    }

    private static S3Client buildClient(Map<String, String> config) {
        S3ClientBuilder builder = S3Client.builder()
                .region(Region.of(config.getOrDefault(CONFIG_REGION, DEFAULT_REGION)))
                .credentialsProvider(credentialsProvider(config));
        String endpoint = config.get(CONFIG_ENDPOINT);
        if (endpoint != null && !endpoint.isBlank()) {
            // S3-compatible stores (MinIO, Rook/Ceph, R2, ...) are reached through an endpoint
            // override and need path-style bucket addressing rather than AWS's virtual-hosted
            // style, which only real S3 DNS resolves -- see S3SourceConnector.buildClient for
            // the same reasoning.
            builder = builder.endpointOverride(URI.create(endpoint)).forcePathStyle(true);
        }
        return builder.build();
    }

    private static AwsCredentialsProvider credentialsProvider(Map<String, String> config) {
        String accessKeyId = config.get(CONFIG_ACCESS_KEY_ID);
        String secretAccessKey = config.get(CONFIG_SECRET_ACCESS_KEY);
        if (accessKeyId != null && secretAccessKey != null) {
            return StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKeyId, secretAccessKey));
        }
        return DefaultCredentialsProvider.builder().build();
    }

    private static String normalisePrefix(String prefix) {
        if (prefix == null || prefix.isBlank()) {
            return "";
        }
        return prefix.endsWith("/") ? prefix : prefix + "/";
    }

    private static String fileNameOf(String key) {
        int lastSlash = key.lastIndexOf('/');
        return lastSlash < 0 ? key : key.substring(lastSlash + 1);
    }

    private static String require(Map<String, String> config, String key) {
        String value = config.get(key);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("missing required staging source config key: " + key);
        }
        return value;
    }
}
