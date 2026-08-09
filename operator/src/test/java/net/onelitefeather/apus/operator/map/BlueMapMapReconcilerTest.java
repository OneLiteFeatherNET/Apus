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
package net.onelitefeather.apus.operator.map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.fabric8.kubernetes.api.model.ConfigMapBuilder;
import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.server.mock.EnableKubernetesMockClient;
import io.javaoperatorsdk.operator.api.reconciler.UpdateControl;
import java.util.Map;
import java.util.UUID;
import net.onelitefeather.apus.operator.OperatorConfig;
import net.onelitefeather.apus.operator.api.BlueMapMap;
import net.onelitefeather.apus.operator.api.Conditions;
import net.onelitefeather.apus.operator.api.Labels;
import net.onelitefeather.apus.operator.rook.ObjectBucketClaim;
import org.junit.jupiter.api.Test;

@EnableKubernetesMockClient(crud = true)
class BlueMapMapReconcilerTest {

    KubernetesClient client;

    private BlueMapMap map(String name) {
        BlueMapMap map = new BlueMapMap();
        // A real API server always assigns a UID before a reconciler ever sees the resource;
        // the ownership check performed here relies on it.
        map.setMetadata(new ObjectMetaBuilder()
                .withName(name)
                .withNamespace("bluemap-friends")
                .withUid(UUID.randomUUID().toString())
                .build());
        map.getSpec().getSource().setDimension("minecraft:overworld");
        return map;
    }

    private String readyReason(BlueMapMap map) {
        return map.getStatus().getConditions().stream()
                .filter(condition -> Conditions.READY.equals(condition.getType()))
                .findFirst()
                .orElseThrow()
                .getReason();
    }

    /** Simulates Rook binding the claim: sets phase Bound and drops the endpoint ConfigMap. */
    private void bindClaim(String namespace, String name) {
        ObjectBucketClaim claim =
                client.resources(ObjectBucketClaim.class).inNamespace(namespace).withName(name).get();
        claim.getStatus().setPhase("Bound");
        client.resources(ObjectBucketClaim.class).inNamespace(namespace).resource(claim).updateStatus();

        client.configMaps()
                .inNamespace(namespace)
                .resource(new ConfigMapBuilder()
                        .withNewMetadata()
                        .withName(name)
                        .withNamespace(namespace)
                        .endMetadata()
                        .withData(Map.of("BUCKET_HOST", "rook-ceph-rgw.example.svc", "BUCKET_PORT", "80"))
                        .build())
                .create();
    }

    @Test
    void waitsForRookBeforeMarkingTheMapReady() {
        BlueMapMapReconciler reconciler = new BlueMapMapReconciler(client, OperatorConfig.defaults());
        BlueMapMap map = map("survival-overworld");

        UpdateControl<BlueMapMap> control = reconciler.reconcile(map, null);

        assertTrue(control.isPatchStatus());
        assertTrue(control.getScheduleDelay().isPresent(), "an unbound claim must be rechecked later");
        assertNull(map.getStatus().getBucket().getName(), "no bucket may be reported before Rook binds the claim");
        assertEquals("BucketPending", readyReason(map));
    }

    @Test
    void copiesTheBucketNameAndEndpointIntoStatusOnceBound() {
        BlueMapMapReconciler reconciler = new BlueMapMapReconciler(client, OperatorConfig.defaults());
        BlueMapMap map = map("survival-overworld");

        reconciler.reconcile(map, null);
        bindClaim("bluemap-friends", "survival-overworld");
        UpdateControl<BlueMapMap> control = reconciler.reconcile(map, null);

        // RenderJobBuilder reads exactly these two fields; an empty one means an empty bucket
        // name on the render pod, which is exactly the bug this reconciler exists to prevent.
        assertEquals("apus-friends-survival-overworld", map.getStatus().getBucket().getName());
        assertEquals(
                "http://rook-ceph-rgw.example.svc:80", map.getStatus().getBucket().getEndpoint());
        assertEquals("survival-overworld", map.getStatus().getBucket().getSecretName());
        assertEquals("BucketProvisioned", readyReason(map));
        assertTrue(control.isPatchStatus());
    }

    @Test
    void derivesTheCephUserFromTheMapsNamespace() {
        // "bluemap-friends" -> tenant "friends" -> ceph user "apus-friends", the same
        // convention TenantReconciler.cephUserFor applies -- there is no other link from a
        // namespaced BlueMapMap back to its owning Tenant.
        BlueMapMapReconciler reconciler = new BlueMapMapReconciler(client, OperatorConfig.defaults());

        reconciler.reconcile(map("survival-overworld"), null);

        ObjectBucketClaim claim = client.resources(ObjectBucketClaim.class)
                .inNamespace("bluemap-friends")
                .withName("survival-overworld")
                .get();
        assertEquals("apus-friends", claim.getSpec().getAdditionalConfig().get("bucketOwner"));
    }

    @Test
    void everyCreatedResourceCarriesTheManagedByLabel() {
        BlueMapMapReconciler reconciler = new BlueMapMapReconciler(client, OperatorConfig.defaults());

        reconciler.reconcile(map("survival-overworld"), null);

        ObjectBucketClaim claim = client.resources(ObjectBucketClaim.class)
                .inNamespace("bluemap-friends")
                .withName("survival-overworld")
                .get();
        assertEquals(Labels.MANAGED_BY_VALUE, claim.getMetadata().getLabels().get(Labels.MANAGED_BY));
    }

    @Test
    void isIdempotentAcrossRepeatedBoundReconciles() {
        BlueMapMapReconciler reconciler = new BlueMapMapReconciler(client, OperatorConfig.defaults());
        BlueMapMap map = map("survival-overworld");

        reconciler.reconcile(map, null);
        bindClaim("bluemap-friends", "survival-overworld");
        reconciler.reconcile(map, null);
        UpdateControl<BlueMapMap> control = reconciler.reconcile(map, null);

        assertEquals("BucketProvisioned", readyReason(map));
        assertTrue(control.isPatchStatus());
    }

    @Test
    void refusesToAdoptAnUnlabelledPreExistingClaim() {
        client.resources(ObjectBucketClaim.class)
                .inNamespace("bluemap-friends")
                .resource(preExistingClaim("survival-overworld", null))
                .create();
        BlueMapMapReconciler reconciler = new BlueMapMapReconciler(client, OperatorConfig.defaults());
        BlueMapMap map = map("survival-overworld");

        UpdateControl<BlueMapMap> control = reconciler.reconcile(map, null);

        assertTrue(control.isPatchStatus());
        assertEquals(BlueMapMapReconciler.RESOURCE_CONFLICT_REASON, readyReason(map));
        assertNull(map.getStatus().getBucket().getName(), "must not report a bucket it does not own");
    }

    @Test
    void refusesToAdoptAClaimOwnedByAnotherMap() {
        client.resources(ObjectBucketClaim.class)
                .inNamespace("bluemap-friends")
                .resource(preExistingClaim("survival-overworld", UUID.randomUUID().toString()))
                .create();
        BlueMapMapReconciler reconciler = new BlueMapMapReconciler(client, OperatorConfig.defaults());
        BlueMapMap map = map("survival-overworld");

        UpdateControl<BlueMapMap> control = reconciler.reconcile(map, null);

        assertTrue(control.isPatchStatus());
        assertEquals(BlueMapMapReconciler.RESOURCE_CONFLICT_REASON, readyReason(map));
    }

    private ObjectBucketClaim preExistingClaim(String name, String foreignUid) {
        ObjectBucketClaim claim = new ObjectBucketClaim();
        claim.getMetadata().setName(name);
        claim.getMetadata().setNamespace("bluemap-friends");
        if (foreignUid != null) {
            claim.getMetadata()
                    .setLabels(Map.of(
                            Labels.MAP, name,
                            Labels.MAP_UID, foreignUid));
        }
        return claim;
    }

    @Test
    void reportsNoBucketWhileTheClaimIsStillUnbound() {
        BlueMapMapReconciler reconciler = new BlueMapMapReconciler(client, OperatorConfig.defaults());

        reconciler.reconcile(map("survival-overworld"), null);

        assertNotNull(client.resources(ObjectBucketClaim.class)
                .inNamespace("bluemap-friends")
                .withName("survival-overworld")
                .get());
        assertFalse("Bound"
                .equals(client.resources(ObjectBucketClaim.class)
                        .inNamespace("bluemap-friends")
                        .withName("survival-overworld")
                        .get()
                        .getStatus()
                        .getPhase()));
    }
}
