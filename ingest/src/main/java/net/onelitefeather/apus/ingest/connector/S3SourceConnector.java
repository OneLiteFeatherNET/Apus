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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.S3Object;

/**
 * A pull source backed by an S3-compatible bucket. Each object found directly under {@code
 * config.get(CONFIG_PREFIX)} (no further path segments) is treated as one fetchable version,
 * identified by its key relative to the prefix -- new object, new version. If the key ends with a
 * recognised archive extension it is unpacked into the work directory; otherwise the raw object is
 * written as a single file.
 */
public final class S3SourceConnector implements WorldSourceConnector {

    /**
     * The access key and secret in this connector's config are credentials and never appear in a
     * log line, a span attribute or an exception message -- see {@code
     * docs/logging-and-tracing.md}. Endpoint, bucket and object key are not secrets and are what
     * makes a failed fetch diagnosable, so those are logged.
     */
    private static final Logger LOGGER = LoggerFactory.getLogger(S3SourceConnector.class);

    public static final String CONFIG_ENDPOINT = "endpoint";
    public static final String CONFIG_BUCKET = "bucket";
    public static final String CONFIG_PREFIX = "prefix";
    public static final String CONFIG_ACCESS_KEY_ID = "accessKeyId";
    public static final String CONFIG_SECRET_ACCESS_KEY = "secretAccessKey";
    public static final String CONFIG_REGION = "region";

    private static final String DEFAULT_REGION = "us-east-1";

    @Override
    public String type() {
        return "s3";
    }

    @Override
    public List<SourceVersion> discover(Map<String, String> config) {
        try (S3Client client = buildClient(config)) {
            return discover(client, config);
        }
    }

    @Override
    public void fetch(Map<String, String> config, SourceVersion version, Path workDir) {
        try (S3Client client = buildClient(config)) {
            fetch(client, config, version, workDir);
        }
    }

    /** Same as {@link #discover(Map)} but against an already-built client, for testing. */
    List<SourceVersion> discover(S3Client client, Map<String, String> config) {
        String bucket = require(config, CONFIG_BUCKET);
        String prefix = normalisePrefix(config.get(CONFIG_PREFIX));

        ListObjectsV2Request request = ListObjectsV2Request.builder()
                .bucket(bucket)
                .prefix(prefix)
                .delimiter("/")
                .build();

        List<SourceVersion> versions = new ArrayList<>();
        for (S3Object object : client.listObjectsV2Paginator(request).contents()) {
            String key = object.key();
            if (key.equals(prefix)) {
                continue; // a zero-byte "directory marker" object, not a version
            }
            String id = key.substring(prefix.length());
            versions.add(new SourceVersion(id, id, object.lastModified(), object.size()));
        }
        LOGGER.info("discovered {} version(s) under s3://{}/{}", versions.size(), bucket, prefix);
        return versions;
    }

    /** Same as {@link #fetch(Map, SourceVersion, Path)} but against an already-built client, for testing. */
    void fetch(S3Client client, Map<String, String> config, SourceVersion version, Path workDir) {
        String bucket = require(config, CONFIG_BUCKET);
        String prefix = normalisePrefix(config.get(CONFIG_PREFIX));
        String key = prefix + version.id();

        GetObjectRequest request =
                GetObjectRequest.builder().bucket(bucket).key(key).build();
        LOGGER.info("fetching s3://{}/{} into {}", bucket, key, workDir);
        try (ResponseInputStream<GetObjectResponse> object = client.getObject(request)) {
            if (Archives.isArchive(key)) {
                Archives.extract(key, object, workDir, Archives.limitsFrom(config));
            } else {
                Path target = workDir.resolve(fileNameOf(key));
                LOGGER.debug("{} is not a recognised archive; copying it verbatim", key);
                Files.copy(object, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("failed to fetch S3 object " + key, e);
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
            // style, which only real S3 DNS resolves.
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
            throw new IllegalArgumentException("missing required S3 source config key: " + key);
        }
        return value;
    }
}
