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
package net.onelitefeather.apus.operator.rook;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.fabric8.kubernetes.client.utils.Serialization;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RookResourceSerialisationTest {

    @Test
    void objectBucketClaimMatchesTheClusterSchema() {
        ObjectBucketClaim claim = new ObjectBucketClaim();
        claim.getMetadata().setName("apus-friends-survival");
        claim.getMetadata().setNamespace("bluemap-friends");
        claim.getSpec().setBucketName("apus-friends-survival");
        claim.getSpec().setStorageClassName("ceph-bucket-fr01");
        claim.getSpec().setAdditionalConfig(Map.of("bucketOwner", "apus-friends"));

        String yaml = Serialization.asYaml(claim);

        assertTrue(
                yaml.contains("apiVersion: \"objectbucket.io/v1alpha1\"")
                        || yaml.contains("apiVersion: objectbucket.io/v1alpha1"),
                yaml);
        assertTrue(yaml.contains("kind: \"ObjectBucketClaim\"") || yaml.contains("kind: ObjectBucketClaim"), yaml);
        assertTrue(yaml.contains("storageClassName"), yaml);
        assertTrue(yaml.contains("bucketOwner"), yaml);
    }

    @Test
    void cephObjectStoreUserCarriesTheQuota() {
        CephObjectStoreUser user = new CephObjectStoreUser();
        user.getMetadata().setName("apus-friends");
        user.getMetadata().setNamespace("rook-ceph-fr01");
        user.getSpec().setStore("feather-s3");
        user.getSpec().setDisplayName("apus-friends");
        user.getSpec().getQuotas().setMaxSize("500Gi");
        user.getSpec().getQuotas().setMaxObjects(5_000_000L);

        String yaml = Serialization.asYaml(user);

        assertTrue(yaml.contains("ceph.rook.io/v1"), yaml);
        assertTrue(yaml.contains("CephObjectStoreUser"), yaml);
        assertTrue(yaml.contains("500Gi"), yaml);
        assertTrue(yaml.contains("5000000"), yaml);
    }

    @Test
    void deserialisesAClaimStatusFromTheCluster() {
        String yaml =
                """
                apiVersion: objectbucket.io/v1alpha1
                kind: ObjectBucketClaim
                metadata:
                  name: apus-friends-survival
                  namespace: bluemap-friends
                spec:
                  bucketName: apus-friends-survival
                  storageClassName: ceph-bucket-fr01
                status:
                  phase: Bound
                """;

        ObjectBucketClaim claim = Serialization.unmarshal(yaml, ObjectBucketClaim.class);

        assertEquals("Bound", claim.getStatus().getPhase());
        assertEquals("apus-friends-survival", claim.getSpec().getBucketName());
    }

    /**
     * Rook owns these CRDs and may add a field to them whenever it likes -- and it did.
     *
     * <p>The JSON below is copied verbatim from a live {@code CephObjectStoreUser} in
     * {@code rook-ceph-fr01}. Only {@code phase} was modelled here, so reading that object threw
     * {@code UnrecognizedPropertyException: Unrecognized field "info"} -- inside {@code
     * TenantReconciler}, which reads the existing user before touching it. Every reconciliation of
     * every tenant failed from the moment Rook populated {@code status.info}, and because a tenant
     * only reconciles when something changes, nothing appeared broken until a release needed one.
     * The operator then exhausted its retries and the tenant silently never got its application
     * instance.
     *
     * <p>The fix is not to add {@code info}: it is to stop caring what else Rook sends. A model of
     * somebody else's resource has no business failing on a field it does not read.
     */
    @Test
    void aRookStatusWithFieldsWeDoNotModelIsReadAnyway() {
        String json =
                """
                {"apiVersion":"ceph.rook.io/v1","kind":"CephObjectStoreUser",\
                "metadata":{"name":"apus-onelitefeather-dev","namespace":"rook-ceph-fr01"},\
                "spec":{"store":"feather-s3","displayName":"apus-onelitefeather-dev"},\
                "status":{"info":{"secretName":"rook-ceph-object-user-feather-s3-apus-onelitefeather-dev"},\
                "observedGeneration":1,"phase":"Ready"}}""";

        CephObjectStoreUser user = Serialization.unmarshal(json, CephObjectStoreUser.class);

        assertEquals("Ready", user.getStatus().getPhase());
        assertEquals("feather-s3", user.getSpec().getStore());
    }

    /** The same tolerance on the other Rook resource, before it costs anything to learn. */
    @Test
    void anObjectBucketClaimWithFieldsWeDoNotModelIsReadAnyway() {
        String json =
                """
                {"apiVersion":"objectbucket.io/v1alpha1","kind":"ObjectBucketClaim",\
                "metadata":{"name":"apus-friends-survival","namespace":"bluemap-friends"},\
                "spec":{"bucketName":"apus-friends-survival","storageClassName":"ceph-bucket-fr01",\
                "objectBucketName":"obc-bluemap-friends-apus-friends-survival"},\
                "status":{"phase":"Bound","conditions":[],"somethingRookAddsLater":true}}""";

        ObjectBucketClaim claim = Serialization.unmarshal(json, ObjectBucketClaim.class);

        assertEquals("Bound", claim.getStatus().getPhase());
        assertEquals("apus-friends-survival", claim.getSpec().getBucketName());
    }
}
