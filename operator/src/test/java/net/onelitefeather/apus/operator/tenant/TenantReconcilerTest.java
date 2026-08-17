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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.fabric8.kubernetes.api.model.Namespace;
import io.fabric8.kubernetes.api.model.NamespaceBuilder;
import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;
import io.fabric8.kubernetes.api.model.ResourceQuota;
import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.api.model.SecretBuilder;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.server.mock.EnableKubernetesMockClient;
import io.fabric8.kubernetes.client.server.mock.KubernetesMockServer;
import io.javaoperatorsdk.operator.api.reconciler.UpdateControl;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.onelitefeather.apus.operator.OperatorConfig;
import net.onelitefeather.apus.operator.TenantUiConfig;
import net.onelitefeather.apus.operator.api.Conditions;
import net.onelitefeather.apus.operator.api.Labels;
import net.onelitefeather.apus.operator.api.Tenant;
import net.onelitefeather.apus.operator.rook.CephObjectStoreUser;
import org.junit.jupiter.api.Test;

@EnableKubernetesMockClient(crud = true)
class TenantReconcilerTest {

    KubernetesClient client;
    KubernetesMockServer server;

    private Tenant tenant(String name, String quota) {
        Tenant tenant = new Tenant();
        // A real API server always assigns a UID before a reconciler ever sees the resource;
        // the ownership check the reconciler performs relies on it, so tests must supply one
        // too rather than leaving reconcile() to see a tenant with no identity of its own.
        tenant.setMetadata(new ObjectMetaBuilder()
                .withName(name)
                .withUid(UUID.randomUUID().toString())
                .build());
        tenant.getSpec().setDisplayName(name);
        tenant.getSpec().getStorage().setQuota(quota);
        return tenant;
    }

    /**
     * The fabric8 CRUD mock server does not simulate the real API server's {@code stringData} ->
     * base64 {@code data} merge on write, so a Secret created via {@code withStringData(...)}
     * (as {@code TenantReconciler} does) is read back with the value still under {@code
     * stringData}, not {@code data}, here -- unlike a real cluster. Reading either map keeps
     * these tests meaningful under both.
     */
    private static String tokenValue(Secret secret) {
        if (secret.getStringData() != null && secret.getStringData().get(PushTokenSecrets.TOKEN_KEY) != null) {
            return secret.getStringData().get(PushTokenSecrets.TOKEN_KEY);
        }
        return secret.getData() == null ? null : secret.getData().get(PushTokenSecrets.TOKEN_KEY);
    }

    private String readyReason(Tenant tenant) {
        return tenant.getStatus().getConditions().stream()
                .filter(condition -> Conditions.READY.equals(condition.getType()))
                .findFirst()
                .orElseThrow()
                .getReason();
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

    @Test
    void everyCreatedResourceCarriesTheManagedByLabel() {
        TenantReconciler reconciler = new TenantReconciler(client, OperatorConfig.defaults());

        reconciler.reconcile(tenant("friends", "500Gi"), null);

        assertEquals(
                Labels.MANAGED_BY_VALUE,
                client.namespaces()
                        .withName("bluemap-friends")
                        .get()
                        .getMetadata()
                        .getLabels()
                        .get(Labels.MANAGED_BY));
        assertEquals(
                Labels.MANAGED_BY_VALUE,
                client.resourceQuotas()
                        .inNamespace("bluemap-friends")
                        .withName("apus-tenant")
                        .get()
                        .getMetadata()
                        .getLabels()
                        .get(Labels.MANAGED_BY));
        assertEquals(
                Labels.MANAGED_BY_VALUE,
                client.limitRanges()
                        .inNamespace("bluemap-friends")
                        .withName("apus-tenant")
                        .get()
                        .getMetadata()
                        .getLabels()
                        .get(Labels.MANAGED_BY));
        assertEquals(
                Labels.MANAGED_BY_VALUE,
                client.resources(CephObjectStoreUser.class)
                        .inNamespace(OperatorConfig.defaults().rookNamespace())
                        .withName("apus-friends")
                        .get()
                        .getMetadata()
                        .getLabels()
                        .get(Labels.MANAGED_BY));
    }

    @Test
    void refusesToAdoptAnUnlabelledPreExistingNamespace() {
        client.namespaces()
                .resource(new NamespaceBuilder()
                        .withNewMetadata()
                        .withName("bluemap-friends")
                        .endMetadata()
                        .build())
                .create();
        TenantReconciler reconciler = new TenantReconciler(client, OperatorConfig.defaults());
        Tenant tenant = tenant("friends", "500Gi");

        UpdateControl<Tenant> control = reconciler.reconcile(tenant, null);

        assertTrue(control.isPatchStatus());
        assertEquals(TenantReconciler.RESOURCE_CONFLICT_REASON, readyReason(tenant));
        Map<String, String> labels =
                client.namespaces().withName("bluemap-friends").get().getMetadata().getLabels();
        assertTrue(
                labels == null || !labels.containsKey(Labels.TENANT),
                "the pre-existing namespace must not be silently adopted");
    }

    @Test
    void refusesToAdoptANamespaceOwnedByAnotherTenant() {
        client.namespaces()
                .resource(new NamespaceBuilder()
                        .withNewMetadata()
                        .withName("bluemap-friends")
                        .withLabels(Map.of(
                                Labels.TENANT, "friends",
                                Labels.TENANT_UID, UUID.randomUUID().toString()))
                        .endMetadata()
                        .build())
                .create();
        TenantReconciler reconciler = new TenantReconciler(client, OperatorConfig.defaults());
        Tenant tenant = tenant("friends", "500Gi");

        UpdateControl<Tenant> control = reconciler.reconcile(tenant, null);

        assertTrue(control.isPatchStatus());
        assertEquals(TenantReconciler.RESOURCE_CONFLICT_REASON, readyReason(tenant));
    }

    @Test
    void updatesANamespaceAlreadyOwnedByTheSameTenantIdempotently() {
        Tenant tenant = tenant("friends", "500Gi");
        client.namespaces()
                .resource(new NamespaceBuilder()
                        .withNewMetadata()
                        .withName("bluemap-friends")
                        .withLabels(Map.of(
                                Labels.TENANT,
                                tenant.getMetadata().getName(),
                                Labels.TENANT_UID,
                                tenant.getMetadata().getUid()))
                        .endMetadata()
                        .build())
                .create();
        TenantReconciler reconciler = new TenantReconciler(client, OperatorConfig.defaults());

        UpdateControl<Tenant> control = reconciler.reconcile(tenant, null);

        assertTrue(control.isPatchStatus());
        assertEquals("Provisioned", readyReason(tenant));
        assertNotNull(client.resourceQuotas().inNamespace("bluemap-friends").withName("apus-tenant").get());
    }

    @Test
    void refusesToAdoptAnUnlabelledPreExistingCephUser() {
        CephObjectStoreUser existing = new CephObjectStoreUser();
        existing.getMetadata().setName("apus-friends");
        existing.getMetadata().setNamespace(OperatorConfig.defaults().rookNamespace());
        client.resources(CephObjectStoreUser.class)
                .inNamespace(OperatorConfig.defaults().rookNamespace())
                .resource(existing)
                .create();
        TenantReconciler reconciler = new TenantReconciler(client, OperatorConfig.defaults());
        Tenant tenant = tenant("friends", "500Gi");

        UpdateControl<Tenant> control = reconciler.reconcile(tenant, null);

        assertTrue(control.isPatchStatus());
        assertEquals(TenantReconciler.RESOURCE_CONFLICT_REASON, readyReason(tenant));
    }

    @Test
    void refusesToAdoptACephUserOwnedByAnotherTenant() {
        CephObjectStoreUser existing = new CephObjectStoreUser();
        existing.getMetadata().setName("apus-friends");
        existing.getMetadata().setNamespace(OperatorConfig.defaults().rookNamespace());
        existing.getMetadata()
                .setLabels(Map.of(
                        Labels.TENANT, "friends",
                        Labels.TENANT_UID, UUID.randomUUID().toString()));
        client.resources(CephObjectStoreUser.class)
                .inNamespace(OperatorConfig.defaults().rookNamespace())
                .resource(existing)
                .create();
        TenantReconciler reconciler = new TenantReconciler(client, OperatorConfig.defaults());
        Tenant tenant = tenant("friends", "500Gi");

        UpdateControl<Tenant> control = reconciler.reconcile(tenant, null);

        assertTrue(control.isPatchStatus());
        assertEquals(TenantReconciler.RESOURCE_CONFLICT_REASON, readyReason(tenant));
    }

    @Test
    void updatesACephUserAlreadyOwnedByTheSameTenantIdempotently() {
        Tenant tenant = tenant("friends", "500Gi");
        CephObjectStoreUser existing = new CephObjectStoreUser();
        existing.getMetadata().setName("apus-friends");
        existing.getMetadata().setNamespace(OperatorConfig.defaults().rookNamespace());
        existing.getMetadata()
                .setLabels(Map.of(
                        Labels.TENANT,
                        tenant.getMetadata().getName(),
                        Labels.TENANT_UID,
                        tenant.getMetadata().getUid()));
        client.resources(CephObjectStoreUser.class)
                .inNamespace(OperatorConfig.defaults().rookNamespace())
                .resource(existing)
                .create();
        TenantReconciler reconciler = new TenantReconciler(client, OperatorConfig.defaults());

        UpdateControl<Tenant> control = reconciler.reconcile(tenant, null);

        assertTrue(control.isPatchStatus());
        assertEquals("Provisioned", readyReason(tenant));
        assertEquals(
                "500Gi",
                client.resources(CephObjectStoreUser.class)
                        .inNamespace(OperatorConfig.defaults().rookNamespace())
                        .withName("apus-friends")
                        .get()
                        .getSpec()
                        .getQuotas()
                        .getMaxSize());
    }

    @Test
    void setsAnOwnerReferenceOnTheNamespacePointingAtTheTenant() {
        TenantReconciler reconciler = new TenantReconciler(client, OperatorConfig.defaults());

        reconciler.reconcile(tenant("friends", "500Gi"), null);

        Namespace ns = client.namespaces().withName("bluemap-friends").get();
        assertTrue(
                ns.getMetadata().getOwnerReferences().stream()
                        .anyMatch(ref -> "Tenant".equals(ref.getKind())),
                "namespace must be owned by its Tenant so it is garbage-collected on deletion");
    }

    @Test
    void createsAPushTokenSecretForANewTenant() {
        TenantReconciler reconciler = new TenantReconciler(client, OperatorConfig.defaults());

        reconciler.reconcile(tenant("friends", "500Gi"), null);

        Secret secret = client.secrets()
                .inNamespace("bluemap-friends")
                .withName(PushTokenSecrets.SECRET_NAME)
                .get();
        assertNotNull(secret, "push-token secret must be created");
        assertEquals(
                PushTokenSecrets.LABEL_VALUE,
                secret.getMetadata().getLabels().get(PushTokenSecrets.LABEL_KEY),
                "must carry the label FabricPushTokenRepository queries by");
        assertNotNull(tokenValue(secret), "token data must be present");
    }

    @Test
    void reportsThePushTokenSecretNameInStatus() {
        TenantReconciler reconciler = new TenantReconciler(client, OperatorConfig.defaults());
        Tenant tenant = tenant("friends", "500Gi");

        reconciler.reconcile(tenant, null);

        assertEquals(PushTokenSecrets.SECRET_NAME, tenant.getStatus().getPushTokenSecret());
    }

    @Test
    void neverRegeneratesAnExistingPushTokenOnLaterReconciles() {
        TenantReconciler reconciler = new TenantReconciler(client, OperatorConfig.defaults());
        Tenant tenant = tenant("friends", "500Gi");

        reconciler.reconcile(tenant, null);
        String firstToken = tokenValue(client.secrets()
                .inNamespace("bluemap-friends")
                .withName(PushTokenSecrets.SECRET_NAME)
                .get());
        assertNotNull(firstToken, "token data must be present after the first reconcile");

        // A second reconcile (the operator's regular resync, or any spec change) must not
        // invalidate a token paper-worldpush may already be configured with.
        reconciler.reconcile(tenant, null);
        String secondToken = tokenValue(client.secrets()
                .inNamespace("bluemap-friends")
                .withName(PushTokenSecrets.SECRET_NAME)
                .get());

        assertEquals(firstToken, secondToken, "an already-provisioned push token must never be regenerated");
    }

    @Test
    void refusesToAdoptAPushTokenSecretOwnedByAnotherTenant() {
        // The namespace itself must already be correctly owned by this exact tenant (same UID),
        // so the earlier namespace-ownership check does not fire first -- this test is only
        // about the push-token secret's own, independent ownership check.
        Tenant tenant = tenant("friends", "500Gi");
        client.namespaces()
                .resource(new NamespaceBuilder()
                        .withNewMetadata()
                        .withName("bluemap-friends")
                        .withLabels(Map.of(
                                Labels.TENANT,
                                tenant.getMetadata().getName(),
                                Labels.TENANT_UID,
                                tenant.getMetadata().getUid()))
                        .endMetadata()
                        .build())
                .create();
        client.secrets()
                .inNamespace("bluemap-friends")
                .resource(new SecretBuilder()
                        .withNewMetadata()
                        .withName(PushTokenSecrets.SECRET_NAME)
                        .withNamespace("bluemap-friends")
                        .withLabels(Map.of(
                                Labels.TENANT, "friends",
                                Labels.TENANT_UID, UUID.randomUUID().toString()))
                        .endMetadata()
                        .withStringData(Map.of(PushTokenSecrets.TOKEN_KEY, "someone-elses-token"))
                        .build())
                .create();
        TenantReconciler reconciler = new TenantReconciler(client, OperatorConfig.defaults());

        UpdateControl<Tenant> control = reconciler.reconcile(tenant, null);

        assertTrue(control.isPatchStatus());
        assertEquals(TenantReconciler.RESOURCE_CONFLICT_REASON, readyReason(tenant));
    }

    /**
     * The defaults with the per-tenant application instance switched on. Everything else is copied
     * from {@link OperatorConfig#defaults()} so this helper cannot drift from it.
     */
    private static OperatorConfig configWithTenantUi() {
        OperatorConfig defaults = OperatorConfig.defaults();
        return new OperatorConfig(
                defaults.rookNamespace(),
                defaults.cephObjectStore(),
                defaults.bucketStorageClass(),
                defaults.runnerImage(),
                defaults.ingestImage(),
                defaults.hostingImage(),
                defaults.bundleBucket(),
                defaults.bundleS3Endpoint(),
                defaults.bundleS3Region(),
                defaults.bundleCredentialsSecretName(),
                new TenantUiConfig(
                        "apus.example.dev",
                        "apus/ui:1.2.3",
                        "cloudflare-tunnel",
                        "https://apus.example.dev",
                        "https://issuer.example/v2.0",
                        "client-id",
                        "api://client-id/access_as_user openid"));
    }

    private Deployment tenantUiDeployment(String namespace) {
        return client.apps()
                .deployments()
                .inNamespace(namespace)
                .withName(TenantUiResourceBuilder.RESOURCE_NAME)
                .get();
    }

    /**
     * The default, and the reason it is asserted first: a platform that has not opted in must get
     * no per-tenant instance at all. An operator that started provisioning a Deployment per tenant
     * on a plain upgrade would be a surprise nobody asked for, and one that costs a pod per
     * tenant.
     */
    @Test
    void provisionsNoApplicationInstanceWhenNoHostIsConfigured() {
        TenantReconciler reconciler = new TenantReconciler(client, OperatorConfig.defaults());
        Tenant tenant = tenant("friends", "500Gi");

        reconciler.reconcile(tenant, null);

        assertNull(tenantUiDeployment("bluemap-friends"));
        assertNull(client.services()
                .inNamespace("bluemap-friends")
                .withName(TenantUiResourceBuilder.RESOURCE_NAME)
                .get());
        assertNull(client.network()
                .v1()
                .ingresses()
                .inNamespace("bluemap-friends")
                .withName(TenantUiResourceBuilder.RESOURCE_NAME)
                .get());
        assertTrue(tenant.getStatus().getRedirectUris().isEmpty());
    }

    @Test
    void provisionsTheApplicationInstanceOnceAHostIsConfigured() {
        TenantReconciler reconciler = new TenantReconciler(client, configWithTenantUi());
        Tenant tenant = tenant("friends", "500Gi");

        reconciler.reconcile(tenant, null);

        Deployment deployment = tenantUiDeployment("bluemap-friends");
        assertNotNull(deployment);
        assertEquals(
                "apus/ui:1.2.3",
                deployment
                        .getSpec()
                        .getTemplate()
                        .getSpec()
                        .getContainers()
                        .get(0)
                        .getImage());
        assertNotNull(client.services()
                .inNamespace("bluemap-friends")
                .withName(TenantUiResourceBuilder.RESOURCE_NAME)
                .get());
        assertNotNull(client.network()
                .v1()
                .ingresses()
                .inNamespace("bluemap-friends")
                .withName(TenantUiResourceBuilder.RESOURCE_NAME)
                .get());
    }

    @Test
    void reportsTheRedirectUrisThatStillHaveToBeRegisteredByHand() {
        TenantReconciler reconciler = new TenantReconciler(client, configWithTenantUi());
        Tenant tenant = tenant("friends", "500Gi");

        reconciler.reconcile(tenant, null);

        assertEquals(
                List.of(
                        "https://apus.example.dev/t/friends/auth/callback",
                        "https://apus.example.dev/t/friends/auth/silent-renew"),
                tenant.getStatus().getRedirectUris());
    }

    @Test
    void reconcilingTwiceLeavesOneApplicationInstance() {
        TenantReconciler reconciler = new TenantReconciler(client, configWithTenantUi());
        Tenant tenant = tenant("friends", "500Gi");

        reconciler.reconcile(tenant, null);
        reconciler.reconcile(tenant, null);

        assertEquals(
                1,
                client.apps()
                        .deployments()
                        .inNamespace("bluemap-friends")
                        .list()
                        .getItems()
                        .size());
    }

    /**
     * The instance's labels double as the Deployment's pod selector, so they must not be the
     * plain tenant label set every other resource here carries -- two workloads in one namespace
     * sharing a selector would each take the other's pods.
     */
    @Test
    void theApplicationInstanceSelectorIsNotTheTenantsGenericLabelSet() {
        TenantReconciler reconciler = new TenantReconciler(client, configWithTenantUi());
        Tenant tenant = tenant("friends", "500Gi");

        reconciler.reconcile(tenant, null);

        Map<String, String> selector =
                tenantUiDeployment("bluemap-friends").getSpec().getSelector().getMatchLabels();
        assertEquals("tenant-ui", selector.get(Labels.NAME));
        assertEquals("friends", selector.get(Labels.TENANT));
        assertEquals(tenant.getMetadata().getUid(), selector.get(Labels.TENANT_UID));
    }
}
