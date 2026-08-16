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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.fabric8.kubernetes.api.model.Condition;
import io.fabric8.kubernetes.api.model.LimitRange;
import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;
import io.fabric8.kubernetes.api.model.ResourceQuota;
import io.fabric8.kubernetes.client.Config;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import io.javaoperatorsdk.operator.api.reconciler.UpdateControl;
import java.time.Duration;
import java.util.List;
import net.onelitefeather.apus.operator.api.BlueMapMap;
import net.onelitefeather.apus.operator.api.Conditions;
import net.onelitefeather.apus.operator.api.Labels;
import net.onelitefeather.apus.operator.api.Tenant;
import net.onelitefeather.apus.operator.map.BlueMapMapReconciler;
import net.onelitefeather.apus.operator.tenant.TenantReconciler;
import net.onelitefeather.apus.operator.tenant.TenantUiResourceBuilder;
import net.onelitefeather.apus.operator.testsupport.K3sCrdSupport;
import org.junit.jupiter.api.Test;
import org.testcontainers.k3s.K3sContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Proves the generated CRDs apply cleanly to a real Kubernetes API server, and that reconciling
 * a Tenant against that server produces the namespace, quota and limit range with the status
 * patched back onto the resource. The fabric8 mock server used by every other test in this
 * module cannot catch this class of bug: it accepts any well-formed request regardless of
 * whether the corresponding CRD schema would actually validate it, so a broken generated
 * manifest (a field the OpenAPI schema rejects, a scope mismatch, ...) would sail through the
 * rest of the suite unnoticed. Only a real API server enforces the CRD's schema.
 *
 * <p><b>Rook is not part of this test.</b> The cluster started here is a plain k3s node with no
 * storage operator installed, so Rook's {@code CephObjectStoreUser} CRD is never registered on
 * it -- there is no Testcontainers module for Rook, and standing up Ceph itself for a unit-level
 * integration test is out of proportion to what this test needs to prove. This is not worked
 * around by skipping the Ceph part of reconciliation: {@link TenantReconciler} is expected to
 * behave exactly this way against a real cluster whenever Rook has not (yet) been installed --
 * see its class Javadoc. So instead of asserting success there, this test asserts the documented
 * degraded behaviour: the namespace/quota/limit range are still created, and the {@code Ready}
 * condition reports {@link TenantReconciler#ROOK_UNAVAILABLE_REASON} rather than the reconciler
 * throwing. That is itself a meaningful thing to prove against a real API server, since it is
 * exactly the "supports() must correctly say no" half of the behaviour that the mock server
 * cannot exercise (it answers {@code supports()} with an unconditional {@code true} -- see
 * task-8-report.md). {@link BlueMapMapReconciler} adopted the identical pattern for Rook's
 * {@code ObjectBucketClaim} CRD (see {@link #reportsRookUnavailableForABlueMapMapWithoutThrowing()}),
 * so it needs the same real-cluster proof for the same reason.
 */
class OperatorIntegrationTest {

    private static final Duration CRD_REGISTRATION_TIMEOUT = Duration.ofMinutes(2);

    /**
     * How long to wait for the ReplicaSet controller to create a pod for the tenant's application
     * instance. Generous, because this waits on a real controller-manager inside a container; a
     * quota rejection would be immediate, so a timeout here really does mean "never admitted".
     */
    private static final Duration POD_ADMISSION_TIMEOUT = Duration.ofMinutes(2);

    /**
     * Polls until a pod carrying the tenant application instance's labels exists, or the timeout
     * passes. Same shape as {@code K3sCrdSupport#awaitCrdRegistration}, rather than pulling in an
     * Awaitility dependency for a single call site.
     */
    private static boolean awaitTenantUiPod(KubernetesClient client, Duration timeout) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeout.toMillis();
        while (System.currentTimeMillis() < deadline) {
            boolean admitted = !client.pods()
                    .inNamespace("bluemap-uitest")
                    .withLabel(Labels.NAME, "tenant-ui")
                    .list()
                    .getItems()
                    .isEmpty();
            if (admitted) {
                return true;
            }
            Thread.sleep(2000);
        }
        return false;
    }

    @Test
    void appliesGeneratedCrdsAndReconcilesATenant() throws Exception {
        try (K3sContainer k3s = new K3sContainer(DockerImageName.parse("rancher/k3s:v1.31.2-k3s1"))) {
            k3s.start();

            Config config = Config.fromKubeconfig(k3s.getKubeConfigYaml());
            try (KubernetesClient client =
                    new KubernetesClientBuilder().withConfig(config).build()) {

                K3sCrdSupport.applyGeneratedCrds(client);
                K3sCrdSupport.awaitCrdRegistration(
                        client, "tenants.bluemap.onelitefeather.net", CRD_REGISTRATION_TIMEOUT);
                K3sCrdSupport.awaitCrdRegistration(
                        client, "bluemapmaps.bluemap.onelitefeather.net", CRD_REGISTRATION_TIMEOUT);
                K3sCrdSupport.awaitCrdRegistration(
                        client, "bluemaprenders.bluemap.onelitefeather.net", CRD_REGISTRATION_TIMEOUT);

                Tenant tenant = new Tenant();
                tenant.setMetadata(
                        new ObjectMetaBuilder().withName("itest").build());
                tenant.getSpec().setDisplayName("itest");
                tenant.getSpec().getStorage().setQuota("10Gi");
                Tenant created =
                        client.resources(Tenant.class).resource(tenant).create();
                // The API server assigns the UID; TenantReconciler's ownership check depends on
                // it, so the reconciler must see the server-assigned object, not the one still
                // held locally.

                TenantReconciler reconciler = new TenantReconciler(client, OperatorConfig.defaults());
                reconciler.reconcile(created, null);

                assertNotNull(
                        client.namespaces().withName("bluemap-itest").get(),
                        "reconciling a tenant must create its namespace");

                ResourceQuota quota = client.resourceQuotas()
                        .inNamespace("bluemap-itest")
                        .withName("apus-tenant")
                        .get();
                assertNotNull(quota, "reconciling a tenant must create its compute quota");

                LimitRange limitRange = client.limitRanges()
                        .inNamespace("bluemap-itest")
                        .withName("apus-tenant")
                        .get();
                assertNotNull(limitRange, "reconciling a tenant must create its limit range");

                assertEquals(
                        "bluemap-itest",
                        created.getStatus().getNamespace(),
                        "status.namespace must be patched back onto the tenant");

                // No Rook on this cluster (see class Javadoc): the reconciler must not have
                // created -- or claimed to have created -- a CephObjectStoreUser.
                assertNull(
                        created.getStatus().getObjectStoreUser(),
                        "no CephObjectStoreUser CRD exists on this cluster, so status must not claim one");
                Condition ready = created.getStatus().getConditions().stream()
                        .filter(condition -> Conditions.READY.equals(condition.getType()))
                        .findFirst()
                        .orElseThrow(() -> new AssertionError("reconciler must set a Ready condition"));
                assertFalse(
                        Boolean.parseBoolean(ready.getStatus()),
                        "Ready must be False while the storage user could not be provisioned");
                assertEquals(TenantReconciler.ROOK_UNAVAILABLE_REASON, ready.getReason());
            }
        }
    }

    /**
     * Same shape as {@link #appliesGeneratedCrdsAndReconcilesATenant()}, but for {@link
     * BlueMapMapReconciler} and Rook's {@code ObjectBucketClaim} CRD instead of {@code
     * CephObjectStoreUser}: no Rook on this cluster, so reconciling a {@code BlueMapMap} must
     * report {@link BlueMapMapReconciler#ROOK_UNAVAILABLE_REASON} rather than throw when it
     * tries to touch a CRD the API server does not know about.
     */
    @Test
    void reportsRookUnavailableForABlueMapMapWithoutThrowing() throws Exception {
        try (K3sContainer k3s = new K3sContainer(DockerImageName.parse("rancher/k3s:v1.31.2-k3s1"))) {
            k3s.start();

            Config config = Config.fromKubeconfig(k3s.getKubeConfigYaml());
            try (KubernetesClient client =
                    new KubernetesClientBuilder().withConfig(config).build()) {

                K3sCrdSupport.applyGeneratedCrds(client);
                K3sCrdSupport.awaitCrdRegistration(
                        client, "bluemapmaps.bluemap.onelitefeather.net", CRD_REGISTRATION_TIMEOUT);

                BlueMapMap map = new BlueMapMap();
                map.setMetadata(new ObjectMetaBuilder()
                        .withName("survival-overworld")
                        .withNamespace("default")
                        .build());
                map.getSpec().getSource().setDimension("minecraft:overworld");
                map.getSpec().getBluemap().setMinecraftVersion("1.21.10");
                BlueMapMap created =
                        client.resources(BlueMapMap.class).inNamespace("default").resource(map).create();

                BlueMapMapReconciler reconciler = new BlueMapMapReconciler(client, OperatorConfig.defaults());
                UpdateControl<BlueMapMap> control = reconciler.reconcile(created, null);

                assertTrue(control.isPatchStatus(), "the missing-CRD outcome must still be reported in status");
                assertNull(
                        created.getStatus().getBucket().getName(),
                        "no bucket exists to report -- ObjectBucketClaim CRD is not registered on this cluster");

                Condition ready = created.getStatus().getConditions().stream()
                        .filter(condition -> Conditions.READY.equals(condition.getType()))
                        .findFirst()
                        .orElseThrow(() -> new AssertionError("reconciler must set a Ready condition"));
                assertFalse(
                        Boolean.parseBoolean(ready.getStatus()),
                        "Ready must be False while no bucket could be provisioned");
                assertEquals(BlueMapMapReconciler.ROOK_UNAVAILABLE_REASON, ready.getReason());
            }
        }
    }

    /**
     * The per-tenant application instance, against a real API server -- and specifically, against
     * a real <em>admission</em> path.
     *
     * <p>This is here because of one failure the mock server cannot reproduce at all. A tenant
     * namespace carries a {@code ResourceQuota} on {@code requests.cpu}/{@code requests.memory}
     * and a {@code LimitRange} with no spec, so the quota makes both requests mandatory for every
     * pod and nothing supplies a default. A Deployment whose container omits them is accepted
     * happily by the API server and then never produces a pod: the ReplicaSet controller's pod
     * creations are rejected by quota admission, and what an operator sees is a healthy-looking
     * Deployment stuck at zero replicas.
     *
     * <p>So this asserts a <b>Pod</b> exists, not a Deployment. k3s runs the real
     * kube-controller-manager, so the ReplicaSet controller and the quota admission plugin both
     * take part. The image itself never pulls here and the pod never becomes ready -- that is
     * fine and beside the point. A Pod object existing at all is the proof that quota admission
     * let it through.
     */
    @Test
    void aTenantApplicationInstanceGetsPastTheNamespaceQuota() throws Exception {
        try (K3sContainer k3s = new K3sContainer(DockerImageName.parse("rancher/k3s:v1.31.2-k3s1"))) {
            k3s.start();

            Config config = Config.fromKubeconfig(k3s.getKubeConfigYaml());
            try (KubernetesClient client =
                    new KubernetesClientBuilder().withConfig(config).build()) {

                K3sCrdSupport.applyGeneratedCrds(client);
                K3sCrdSupport.awaitCrdRegistration(
                        client, "tenants.bluemap.onelitefeather.net", CRD_REGISTRATION_TIMEOUT);

                Tenant tenant = new Tenant();
                tenant.setMetadata(
                        new ObjectMetaBuilder().withName("uitest").build());
                tenant.getSpec().setDisplayName("uitest");
                Tenant created =
                        client.resources(Tenant.class).resource(tenant).create();

                OperatorConfig defaults = OperatorConfig.defaults();
                OperatorConfig withUi = new OperatorConfig(
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
                                "apus/ui:dev",
                                "traefik",
                                "https://apus.example.dev",
                                "https://issuer.example/v2.0",
                                "client-id",
                                "api://client-id/access_as_user openid"));

                new TenantReconciler(client, withUi).reconcile(created, null);

                assertNotNull(
                        client.apps()
                                .deployments()
                                .inNamespace("bluemap-uitest")
                                .withName(TenantUiResourceBuilder.RESOURCE_NAME)
                                .get(),
                        "reconciling a tenant with a host configured must create its application instance");

                // The real point of this test. Only a cluster with quota admission enforces it.
                // Polled the same way K3sCrdSupport polls for CRD registration, rather than
                // adding an Awaitility dependency for one call site.
                assertTrue(
                        awaitTenantUiPod(client, POD_ADMISSION_TIMEOUT),
                        "no pod was admitted -- the namespace's ResourceQuota rejected it, which is exactly what"
                                + " happens when the container declares no resource requests");

                // The Ingress has to survive the API server's own validation, which the mock
                // server does not perform: a bad pathType or a missing backend port would only
                // ever show up here.
                assertNotNull(
                        client.network()
                                .v1()
                                .ingresses()
                                .inNamespace("bluemap-uitest")
                                .withName(TenantUiResourceBuilder.RESOURCE_NAME)
                                .get(),
                        "the per-tenant Ingress must be accepted by a real API server");

                assertEquals(
                        List.of(
                                "https://apus.example.dev/t/uitest/auth/callback",
                                "https://apus.example.dev/t/uitest/auth/silent-renew"),
                        created.getStatus().getRedirectUris(),
                        "the URIs an administrator still has to register must be patched back onto the tenant");
            }
        }
    }
}
