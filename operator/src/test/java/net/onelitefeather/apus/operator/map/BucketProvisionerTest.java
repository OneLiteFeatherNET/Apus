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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.server.mock.EnableKubernetesMockClient;
import java.util.Optional;
import net.onelitefeather.apus.operator.OperatorConfig;
import net.onelitefeather.apus.operator.api.BlueMapMap;
import net.onelitefeather.apus.operator.rook.ObjectBucketClaim;
import org.junit.jupiter.api.Test;

@EnableKubernetesMockClient(crud = true)
class BucketProvisionerTest {

    KubernetesClient client;

    private BlueMapMap map() {
        BlueMapMap map = new BlueMapMap();
        map.setMetadata(new ObjectMetaBuilder()
                .withName("survival-overworld")
                .withNamespace("bluemap-friends")
                .build());
        return map;
    }

    @Test
    void createsAClaimInTheTenantNamespaceNotTheRookNamespace() {
        BucketProvisioner provisioner = new BucketProvisioner(client, OperatorConfig.defaults());

        provisioner.ensureBucket(map(), "apus-friends");

        ObjectBucketClaim claim = client.resources(ObjectBucketClaim.class)
                .inNamespace("bluemap-friends")
                .withName("survival-overworld")
                .get();

        // Rook writes the credentials Secret into the claim's namespace, so the claim
        // must live where the render job runs — not centrally in the Rook namespace.
        assertNotNull(claim, "claim must be created in the tenant namespace");
        assertEquals("ceph-bucket-fr01", claim.getSpec().getStorageClassName());
        assertEquals("apus-friends", claim.getSpec().getAdditionalConfig().get("bucketOwner"));
    }

    @Test
    void reportsNothingWhileRookIsStillProvisioning() {
        BucketProvisioner provisioner = new BucketProvisioner(client, OperatorConfig.defaults());

        Optional<ObjectBucketClaim> bound = provisioner.ensureBucket(map(), "apus-friends");

        assertTrue(bound.isEmpty(), "an unbound claim must not be reported as ready");
    }

    @Test
    void reportsTheClaimOnceRookHasBoundIt() {
        BucketProvisioner provisioner = new BucketProvisioner(client, OperatorConfig.defaults());
        provisioner.ensureBucket(map(), "apus-friends");

        ObjectBucketClaim claim = client.resources(ObjectBucketClaim.class)
                .inNamespace("bluemap-friends")
                .withName("survival-overworld")
                .get();
        claim.getStatus().setPhase("Bound");
        client.resources(ObjectBucketClaim.class)
                .inNamespace("bluemap-friends")
                .resource(claim)
                .updateStatus();

        Optional<ObjectBucketClaim> bound = provisioner.ensureBucket(map(), "apus-friends");

        assertTrue(bound.isPresent(), "a bound claim must be reported");
    }
}
