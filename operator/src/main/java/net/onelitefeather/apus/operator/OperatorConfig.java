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
package net.onelitefeather.apus.operator;

import java.util.function.Function;

/**
 * Site-specific settings the operator cannot derive from a Custom Resource: which Rook
 * installation to talk to, which runner/ingest image to schedule, and where ingested world
 * bundles are stored. Every reconciler in this module shares one instance, so it is modelled
 * once here rather than re-created (and possibly re-defaulted differently) inside each of them.
 *
 * @param rookNamespace the namespace Rook's CRDs (ObjectBucketClaim, CephObjectStoreUser) live in
 * @param cephObjectStore the name of the Ceph object store to provision users/buckets against
 * @param bucketStorageClass the StorageClass used for {@code ObjectBucketClaim}s
 * @param runnerImage the container image running BlueMap renders
 * @param ingestImage the container image running world ingest jobs (see {@code ingest/README.md})
 * @param hostingImage the container image running the long-lived {@code BlueMapHosting}
 *     webserver (see {@code hosting/README.md}); wired into {@code
 *     net.onelitefeather.apus.operator.hosting.HostingResourceBuilder#deployment}
 * @param bundleBucket the S3-compatible bucket every ingested world bundle is written to.
 *     Deliberately operator-wide rather than a {@code WorldSource}/{@code WorldIngest} spec
 *     field: neither phase 2b CRD carries a bundle-destination field of its own (only
 *     {@code WorldSourceSpec.s3}/{@code .pterodactyl}, which describe the raw data *source*),
 *     so this follows the same pattern {@link #runnerImage} already established for "a setting
 *     every tenant shares, not something a tenant configures per resource"
 * @param bundleS3Endpoint the S3-compatible endpoint the bundle bucket above is reachable at
 * @param bundleS3Region the region passed to the bundle destination's S3 client
 * @param bundleCredentialsSecretName name of the {@code Secret} -- expected to exist in the
 *     same namespace as the {@code WorldSource}/{@code WorldIngest} being reconciled, mirroring
 *     how {@code RenderJobBuilder} is handed a bucket secret name already scoped to the map's
 *     namespace -- carrying the bundle bucket's {@code AWS_ACCESS_KEY_ID}/{@code
 *     AWS_SECRET_ACCESS_KEY}
 */
public record OperatorConfig(
        String rookNamespace,
        String cephObjectStore,
        String bucketStorageClass,
        String runnerImage,
        String ingestImage,
        String hostingImage,
        String bundleBucket,
        String bundleS3Endpoint,
        String bundleS3Region,
        String bundleCredentialsSecretName) {

    private static final String DEFAULT_ROOK_NAMESPACE = "rook-ceph-fr01";
    private static final String DEFAULT_CEPH_OBJECT_STORE = "feather-s3";
    private static final String DEFAULT_BUCKET_STORAGE_CLASS = "ceph-bucket-fr01";
    private static final String DEFAULT_RUNNER_IMAGE = "apus/runner:dev";
    private static final String DEFAULT_INGEST_IMAGE = "apus/ingest:dev";
    private static final String DEFAULT_HOSTING_IMAGE = "apus/hosting:dev";
    private static final String DEFAULT_BUNDLE_BUCKET = "apus-bundles";
    private static final String DEFAULT_BUNDLE_S3_ENDPOINT = "http://rgw.rook-ceph-fr01.svc:80";
    private static final String DEFAULT_BUNDLE_S3_REGION = "us-east-1";
    private static final String DEFAULT_BUNDLE_CREDENTIALS_SECRET = "apus-bundle-credentials";

    /** The feather-core cluster's actual values. */
    public static OperatorConfig defaults() {
        return new OperatorConfig(
                DEFAULT_ROOK_NAMESPACE,
                DEFAULT_CEPH_OBJECT_STORE,
                DEFAULT_BUCKET_STORAGE_CLASS,
                DEFAULT_RUNNER_IMAGE,
                DEFAULT_INGEST_IMAGE,
                DEFAULT_HOSTING_IMAGE,
                DEFAULT_BUNDLE_BUCKET,
                DEFAULT_BUNDLE_S3_ENDPOINT,
                DEFAULT_BUNDLE_S3_REGION,
                DEFAULT_BUNDLE_CREDENTIALS_SECRET);
    }

    /**
     * Builds a config from environment variables, falling back to {@link #defaults()} for any
     * that are unset or blank.
     *
     * <p>Takes a {@code Function<String, String>} rather than reading {@link System#getenv()}
     * directly so tests can supply a fake environment instead of mutating the real one.
     *
     * <p>Recognised variables: {@code APUS_ROOK_NAMESPACE}, {@code APUS_CEPH_OBJECT_STORE},
     * {@code APUS_BUCKET_STORAGE_CLASS}, {@code APUS_RUNNER_IMAGE}, {@code APUS_INGEST_IMAGE},
     * {@code APUS_HOSTING_IMAGE}, {@code APUS_BUNDLE_BUCKET}, {@code APUS_BUNDLE_S3_ENDPOINT},
     * {@code APUS_BUNDLE_S3_REGION}, {@code APUS_BUNDLE_CREDENTIALS_SECRET}.
     */
    public static OperatorConfig fromEnvironment(Function<String, String> env) {
        return new OperatorConfig(
                valueOrDefault(env.apply("APUS_ROOK_NAMESPACE"), DEFAULT_ROOK_NAMESPACE),
                valueOrDefault(env.apply("APUS_CEPH_OBJECT_STORE"), DEFAULT_CEPH_OBJECT_STORE),
                valueOrDefault(env.apply("APUS_BUCKET_STORAGE_CLASS"), DEFAULT_BUCKET_STORAGE_CLASS),
                valueOrDefault(env.apply("APUS_RUNNER_IMAGE"), DEFAULT_RUNNER_IMAGE),
                valueOrDefault(env.apply("APUS_INGEST_IMAGE"), DEFAULT_INGEST_IMAGE),
                valueOrDefault(env.apply("APUS_HOSTING_IMAGE"), DEFAULT_HOSTING_IMAGE),
                valueOrDefault(env.apply("APUS_BUNDLE_BUCKET"), DEFAULT_BUNDLE_BUCKET),
                valueOrDefault(env.apply("APUS_BUNDLE_S3_ENDPOINT"), DEFAULT_BUNDLE_S3_ENDPOINT),
                valueOrDefault(env.apply("APUS_BUNDLE_S3_REGION"), DEFAULT_BUNDLE_S3_REGION),
                valueOrDefault(env.apply("APUS_BUNDLE_CREDENTIALS_SECRET"), DEFAULT_BUNDLE_CREDENTIALS_SECRET));
    }

    private static String valueOrDefault(String value, String defaultValue) {
        return (value == null || value.isBlank()) ? defaultValue : value;
    }
}
