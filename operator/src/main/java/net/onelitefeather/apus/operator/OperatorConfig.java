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
 * installation to talk to, and which runner image to schedule. Every reconciler in this
 * module shares one instance, so it is modelled once here rather than re-created (and
 * possibly re-defaulted differently) inside each of them.
 *
 * @param rookNamespace the namespace Rook's CRDs (ObjectBucketClaim, CephObjectStoreUser) live in
 * @param cephObjectStore the name of the Ceph object store to provision users/buckets against
 * @param bucketStorageClass the StorageClass used for {@code ObjectBucketClaim}s
 * @param runnerImage the container image running BlueMap renders
 */
public record OperatorConfig(String rookNamespace, String cephObjectStore, String bucketStorageClass,
        String runnerImage) {

    private static final String DEFAULT_ROOK_NAMESPACE = "rook-ceph-fr01";
    private static final String DEFAULT_CEPH_OBJECT_STORE = "feather-s3";
    private static final String DEFAULT_BUCKET_STORAGE_CLASS = "ceph-bucket-fr01";
    private static final String DEFAULT_RUNNER_IMAGE = "apus/runner:dev";

    /** The feather-core cluster's actual values. */
    public static OperatorConfig defaults() {
        return new OperatorConfig(
                DEFAULT_ROOK_NAMESPACE, DEFAULT_CEPH_OBJECT_STORE, DEFAULT_BUCKET_STORAGE_CLASS, DEFAULT_RUNNER_IMAGE);
    }

    /**
     * Builds a config from environment variables, falling back to {@link #defaults()} for any
     * that are unset or blank.
     *
     * <p>Takes a {@code Function<String, String>} rather than reading {@link System#getenv()}
     * directly so tests can supply a fake environment instead of mutating the real one.
     *
     * <p>Recognised variables: {@code APUS_ROOK_NAMESPACE}, {@code APUS_CEPH_OBJECT_STORE},
     * {@code APUS_BUCKET_STORAGE_CLASS}, {@code APUS_RUNNER_IMAGE}.
     */
    public static OperatorConfig fromEnvironment(Function<String, String> env) {
        return new OperatorConfig(
                valueOrDefault(env.apply("APUS_ROOK_NAMESPACE"), DEFAULT_ROOK_NAMESPACE),
                valueOrDefault(env.apply("APUS_CEPH_OBJECT_STORE"), DEFAULT_CEPH_OBJECT_STORE),
                valueOrDefault(env.apply("APUS_BUCKET_STORAGE_CLASS"), DEFAULT_BUCKET_STORAGE_CLASS),
                valueOrDefault(env.apply("APUS_RUNNER_IMAGE"), DEFAULT_RUNNER_IMAGE));
    }

    private static String valueOrDefault(String value, String defaultValue) {
        return (value == null || value.isBlank()) ? defaultValue : value;
    }
}
