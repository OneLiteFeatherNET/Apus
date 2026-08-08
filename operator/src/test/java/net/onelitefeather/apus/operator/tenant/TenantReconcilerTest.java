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
package net.onelitefeather.apus.operator.tenant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.fabric8.kubernetes.api.model.Namespace;
import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;
import io.fabric8.kubernetes.api.model.ResourceQuota;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.server.mock.EnableKubernetesMockClient;
import io.fabric8.kubernetes.client.server.mock.KubernetesMockServer;
import net.onelitefeather.apus.operator.OperatorConfig;
import net.onelitefeather.apus.operator.api.Tenant;
import net.onelitefeather.apus.operator.rook.CephObjectStoreUser;
import org.junit.jupiter.api.Test;

@EnableKubernetesMockClient(crud = true)
class TenantReconcilerTest {

    KubernetesClient client;
    KubernetesMockServer server;

    private Tenant tenant(String name, String quota) {
        Tenant tenant = new Tenant();
        tenant.setMetadata(new ObjectMetaBuilder().withName(name).build());
        tenant.getSpec().setDisplayName(name);
        tenant.getSpec().getStorage().setQuota(quota);
        return tenant;
    }

    @Test
    void createsTheNamespaceForANewTenant() {
        TenantReconciler reconciler = new TenantReconciler(client, OperatorConfig.defaults());

        reconciler.reconcile(tenant("friends", "500Gi"), null);

        Namespace ns = client.namespaces().withName("bluemap-friends").get();
        assertNotNull(ns, "tenant namespace must be created");
        assertEquals("friends", ns.getMetadata().getLabels().get("apus.onelitefeather.net/tenant"));
    }

    @Test
    void appliesTheComputeQuotaToTheNamespace() {
        TenantReconciler reconciler = new TenantReconciler(client, OperatorConfig.defaults());

        reconciler.reconcile(tenant("friends", "500Gi"), null);

        ResourceQuota quota =
                client.resourceQuotas().inNamespace("bluemap-friends").withName("apus-tenant").get();
        assertNotNull(quota, "resource quota must be created");
    }

    @Test
    void createsACephUserCarryingTheStorageQuota() {
        TenantReconciler reconciler = new TenantReconciler(client, OperatorConfig.defaults());

        reconciler.reconcile(tenant("friends", "500Gi"), null);

        var user = client.resources(CephObjectStoreUser.class)
                .inNamespace(OperatorConfig.defaults().rookNamespace())
                .withName("apus-friends")
                .get();

        assertNotNull(user, "ceph object store user must be created");
        assertEquals("500Gi", user.getSpec().getQuotas().getMaxSize());
    }

    @Test
    void isIdempotent() {
        TenantReconciler reconciler = new TenantReconciler(client, OperatorConfig.defaults());
        Tenant tenant = tenant("friends", "500Gi");

        reconciler.reconcile(tenant, null);
        reconciler.reconcile(tenant, null);

        assertNotNull(client.namespaces().withName("bluemap-friends").get());
    }

    @Test
    void reportsTheNamespaceInStatus() {
        TenantReconciler reconciler = new TenantReconciler(client, OperatorConfig.defaults());
        Tenant tenant = tenant("friends", "500Gi");

        var control = reconciler.reconcile(tenant, null);

        assertEquals("bluemap-friends", tenant.getStatus().getNamespace());
        assertEquals("apus-friends", tenant.getStatus().getObjectStoreUser());
        assertTrue(control.isPatchStatus(), "status must be patched so the user can see the namespace");
    }
}
