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

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Map;
import org.junit.jupiter.api.Test;

class OperatorConfigTest {

    @Test
    void defaultsMatchTheFeatherCoreCluster() {
        OperatorConfig config = OperatorConfig.defaults();

        assertEquals("rook-ceph-fr01", config.rookNamespace());
        assertEquals("feather-s3", config.cephObjectStore());
        assertEquals("ceph-bucket-fr01", config.bucketStorageClass());
        assertEquals("apus/runner:dev", config.runnerImage());
        assertEquals("apus/ingest:dev", config.ingestImage());
        assertEquals("apus-bundles", config.bundleBucket());
        assertEquals("us-east-1", config.bundleS3Region());
        assertEquals("apus-bundle-credentials", config.bundleCredentialsSecretName());
    }

    @Test
    void fromEnvironmentFallsBackToDefaultsWhenUnset() {
        OperatorConfig config = OperatorConfig.fromEnvironment(name -> null);

        assertEquals(OperatorConfig.defaults(), config);
    }

    @Test
    void fromEnvironmentFallsBackToDefaultsWhenBlank() {
        OperatorConfig config = OperatorConfig.fromEnvironment(name -> "   ");

        assertEquals(OperatorConfig.defaults(), config);
    }

    @Test
    void fromEnvironmentReadsAllVariables() {
        Map<String, String> env = Map.ofEntries(
                Map.entry("APUS_ROOK_NAMESPACE", "rook-ceph-de01"),
                Map.entry("APUS_CEPH_OBJECT_STORE", "feather-s3-de"),
                Map.entry("APUS_BUCKET_STORAGE_CLASS", "ceph-bucket-de01"),
                Map.entry("APUS_RUNNER_IMAGE", "apus/runner:1.2.3"),
                Map.entry("APUS_INGEST_IMAGE", "apus/ingest:1.2.3"),
                Map.entry("APUS_BUNDLE_BUCKET", "bundles-de"),
                Map.entry("APUS_BUNDLE_S3_ENDPOINT", "http://rgw.de.svc:80"),
                Map.entry("APUS_BUNDLE_S3_REGION", "eu-central-1"),
                Map.entry("APUS_BUNDLE_CREDENTIALS_SECRET", "bundle-creds-de"));

        OperatorConfig config = OperatorConfig.fromEnvironment(env::get);

        assertEquals("rook-ceph-de01", config.rookNamespace());
        assertEquals("feather-s3-de", config.cephObjectStore());
        assertEquals("ceph-bucket-de01", config.bucketStorageClass());
        assertEquals("apus/runner:1.2.3", config.runnerImage());
        assertEquals("apus/ingest:1.2.3", config.ingestImage());
        assertEquals("bundles-de", config.bundleBucket());
        assertEquals("http://rgw.de.svc:80", config.bundleS3Endpoint());
        assertEquals("eu-central-1", config.bundleS3Region());
        assertEquals("bundle-creds-de", config.bundleCredentialsSecretName());
    }
}
