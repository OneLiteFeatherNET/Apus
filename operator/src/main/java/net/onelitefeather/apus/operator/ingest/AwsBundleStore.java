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
package net.onelitefeather.apus.operator.ingest;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import net.onelitefeather.apus.ingest.BundlePath;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CommonPrefix;
import software.amazon.awssdk.services.s3.model.Delete;
import software.amazon.awssdk.services.s3.model.DeleteObjectsRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ObjectIdentifier;
import software.amazon.awssdk.services.s3.model.S3Object;

/**
 * Real {@link BundleStore} backed by an AWS SDK v2 {@link S3Client} -- the same client family
 * {@code net.onelitefeather.apus.ingest.S3Client} wraps for writing bundles, used here in the
 * operator to enforce {@code WorldSource.spec.retention}.
 *
 * <p>Deliberately has no dedicated unit test: it is a thin, directly-inspectable wrapper around
 * two SDK calls, exactly the same shape (and the same reasoning) as {@code
 * net.onelitefeather.apus.ingest.S3Client#wrapping} -- the interface {@link BundleStore} is
 * fully exercised by {@link WorldIngestReconcilerTest} through an in-memory fake instead.
 *
 * <p><b>"Version" = the common prefix directly under {@link BundlePath#prefix}.</b> {@link
 * net.onelitefeather.apus.ingest.BundleWriter#write} always writes a bundle under exactly that
 * shape ({@code <tenant>/<sourceName>/<worldId>/<version>/...}), so listing common prefixes one
 * level deep is enough to enumerate every version without inspecting individual object keys.
 */
public final class AwsBundleStore implements BundleStore {

    private static final Logger LOGGER = LoggerFactory.getLogger(AwsBundleStore.class);

    /** S3's own limit on how many keys a single {@code DeleteObjects} call may name. */
    private static final int DELETE_BATCH_SIZE = 1000;

    private final S3Client client;

    public AwsBundleStore(S3Client client) {
        this.client = client;
    }

    @Override
    public List<BundleVersion> listVersions(String tenant, String sourceName, String worldId, String bundleBucket) {
        String prefix = BundlePath.prefix(tenant, sourceName, worldId);
        ListObjectsV2Request request = ListObjectsV2Request.builder()
                .bucket(bundleBucket)
                .prefix(prefix)
                .delimiter("/")
                .build();

        List<BundleVersion> versions = new ArrayList<>();
        for (CommonPrefix commonPrefix : client.listObjectsV2Paginator(request).commonPrefixes()) {
            String versionPrefix = commonPrefix.prefix(); // "<tenant>/<sourceName>/<worldId>/<version>/"
            String version = versionPrefix.substring(prefix.length(), versionPrefix.length() - 1);
            versions.add(new BundleVersion(version, lastModifiedOf(bundleBucket, versionPrefix)));
        }
        return versions;
    }

    /**
     * The version's own "last written" timestamp is taken from its {@code manifest.json} --
     * written last by {@link net.onelitefeather.apus.ingest.BundleWriter#write}, so its
     * timestamp is also the version's actual completion time, not merely the first region file
     * uploaded.
     */
    private Instant lastModifiedOf(String bundleBucket, String versionPrefix) {
        ListObjectsV2Request request = ListObjectsV2Request.builder()
                .bucket(bundleBucket)
                .prefix(versionPrefix + "manifest.json")
                .build();
        return client.listObjectsV2(request).contents().stream()
                .findFirst()
                .map(S3Object::lastModified)
                .orElse(Instant.EPOCH);
    }

    @Override
    public void deleteVersion(String tenant, String sourceName, String worldId, String version, String bundleBucket) {
        String prefix = BundlePath.of(tenant, sourceName, worldId, version) + "/";
        List<ObjectIdentifier> keys = new ArrayList<>();
        ListObjectsV2Request listRequest =
                ListObjectsV2Request.builder().bucket(bundleBucket).prefix(prefix).build();
        for (S3Object object : client.listObjectsV2Paginator(listRequest).contents()) {
            keys.add(ObjectIdentifier.builder().key(object.key()).build());
        }

        // Deleting a bundle version is irreversible and driven by a retention policy rather
        // than by anyone asking for it -- exactly the kind of thing that must be visible in the
        // log afterwards.
        LOGGER.info(
                "retention: deleting {} object(s) of bundle version '{}' from bucket '{}'",
                keys.size(),
                prefix,
                bundleBucket);

        for (int start = 0; start < keys.size(); start += DELETE_BATCH_SIZE) {
            List<ObjectIdentifier> batch = keys.subList(start, Math.min(start + DELETE_BATCH_SIZE, keys.size()));
            client.deleteObjects(DeleteObjectsRequest.builder()
                    .bucket(bundleBucket)
                    .delete(Delete.builder().objects(batch).build())
                    .build());
        }
    }
}
