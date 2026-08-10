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
package net.onelitefeather.apus.operator.hosting;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;
import io.fabric8.kubernetes.api.model.Service;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.api.model.networking.v1.Ingress;
import io.fabric8.kubernetes.client.Config;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import java.time.Duration;
import java.util.List;
import net.onelitefeather.apus.operator.OperatorConfig;
import net.onelitefeather.apus.operator.api.BlueMapHosting;
import net.onelitefeather.apus.operator.api.BlueMapMap;
import net.onelitefeather.apus.operator.api.Conditions;
import net.onelitefeather.apus.operator.api.Ref;
import net.onelitefeather.apus.operator.api.Tenant;
import net.onelitefeather.apus.operator.tenant.TenantReconciler;
import net.onelitefeather.apus.operator.testsupport.K3sCrdSupport;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.k3s.K3sContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Proves {@link BlueMapHostingReconciler} end to end against a real Kubernetes API server
 * (k3s, started via Testcontainers), following the same rationale {@code OperatorIntegrationTest}
 * already established for {@code TenantReconciler}/{@code BlueMapMapReconciler}: the fabric8 mock
 * server used by {@link BlueMapHostingReconcilerTest} accepts any well-formed request regardless
 * of whether the generated {@code bluemaphostings} CRD schema would actually validate it, and
 * unconditionally answers {@link io.fabric8.kubernetes.client.Client#supports} with {@code true}
 * for any {@code CustomResource} -- so it can never prove the {@code client.supports(Certificate
 * .class)} branch actually returns {@code false} when cert-manager is genuinely absent. Only a
 * real API server can prove either of those.
 *
 * <p>One {@link K3sContainer} is shared across every test in this class (unlike {@code
 * OperatorIntegrationTest}, which starts a fresh one per test) -- this class needs several
 * independent scenarios, and starting a k3s node per scenario would multiply an already-expensive
 * setup for no additional coverage. Each test uses its own tenant/namespace name so the scenarios
 * do not interfere with each other on the shared cluster. The same shared-container-across-tests
 * shape is already used by {@code S3SourceConnectorTest} in the {@code ingest} module.
 *
 * <p><b>What this class proves:</b>
 *
 * <ul>
 *   <li>The generated {@code bluemaphostings} CRD applies to a real API server and registers as
 *       {@code Namespaced} ({@link #bluemaphostingsCrdAppliesAndRegistersAsNamespaced()}).
 *   <li>A full reconcile against a real cluster -- a real {@code Tenant} reconciled by {@code
 *       TenantReconciler} (so the namespace carries the real {@link
 *       net.onelitefeather.apus.operator.api.Labels#TENANT} label, not a hand-rolled one) with
 *       matching {@code allowedDomains}, and {@code BlueMapMap}s with a bound bucket status --
 *       produces a ConfigMap (one file per map, plus {@code webserver.conf}), a Deployment, a
 *       Service, and an Ingress with the expected properties ({@link
 *       #reconcilesAFullHostingIntoConfigMapDeploymentServiceAndIngress()}).
 *   <li>Both security checks from the design spec still refuse to create any resource when
 *       resolved against a real Tenant/namespace-label lookup, not just the mock server's
 *       in-memory maps ({@link #hostnameOutsideAllowedDomainsCreatesNoResourcesOnARealCluster()},
 *       {@link #mapMissingFromNamespaceCreatesNoResourcesOnARealCluster()}).
 *   <li>{@code client.supports(Certificate.class)} genuinely returns {@code false} on this
 *       cert-manager-less k3s cluster, and {@link BlueMapHostingReconciler} blocks the entire
 *       hosting rather than creating a broken Ingress when that happens ({@link
 *       #certManagerSupportsReturnsFalseOnARealClusterWithoutCertManagerInstalled()}, {@link
 *       #tlsRequestedWithoutCertManagerBlocksTheEntireHostingOnARealCluster()}).
 * </ul>
 *
 * <p><b>What this class deliberately does not prove</b> -- see the phase 3 plan's Task 5 section
 * and the task-5 report: the full network path through a real Ingress controller (nginx or
 * cloudflare-tunnel) is out of scope here; standing one up on k3s just for this test would be
 * disproportionate, and Task 2's own verification already proved with a real HTTP call that the
 * hosting pod serves a map's tiles once its config is in place. This class stops at "the
 * Kubernetes objects the reconciler creates are wired together correctly."
 */
@Testcontainers
class BlueMapHostingIntegrationTest {

    private static final Duration CRD_REGISTRATION_TIMEOUT = Duration.ofMinutes(2);

    @Container
    private static final K3sContainer K3S = new K3sContainer(DockerImageName.parse("rancher/k3s:v1.31.2-k3s1"));

    private static KubernetesClient client;

    @BeforeAll
    static void createClientAndApplyCrds() throws Exception {
        Config config = Config.fromKubeconfig(K3S.getKubeConfigYaml());
        client = new KubernetesClientBuilder().withConfig(config).build();

        K3sCrdSupport.applyGeneratedCrds(client);
        K3sCrdSupport.awaitCrdRegistration(client, "tenants.bluemap.onelitefeather.net", CRD_REGISTRATION_TIMEOUT);
        K3sCrdSupport.awaitCrdRegistration(
                client, "bluemapmaps.bluemap.onelitefeather.net", CRD_REGISTRATION_TIMEOUT);
        K3sCrdSupport.awaitCrdRegistration(
                client, "bluemaphostings.bluemap.onelitefeather.net", CRD_REGISTRATION_TIMEOUT);
    }

    @AfterAll
    static void closeClient() {
        if (client != null) {
            client.close();
        }
    }

    // --- Requirement 1: the CRD itself applies and registers ------------------------------

    @Test
    void bluemaphostingsCrdAppliesAndRegistersAsNamespaced() {
        boolean registeredAsNamespaced = client.apiextensions()
                .v1()
                .customResourceDefinitions()
                .list()
                .getItems()
                .stream()
                .anyMatch(crd -> "bluemaphostings.bluemap.onelitefeather.net".equals(
                                crd.getMetadata().getName())
                        && "Namespaced".equals(crd.getSpec().getScope()));
        assertTrue(
                registeredAsNamespaced,
                "the generated bluemaphostings CRD must register as a Namespaced resource on a real API server");
    }

    // --- Requirement 2: a full reconcile produces every resource with the right properties -

    @Test
    void reconcilesAFullHostingIntoConfigMapDeploymentServiceAndIngress() throws Exception {
        Tenant tenant = tenantWithAllowedDomains("friends-full", "*.friends.example.net");
        String namespace = TenantReconciler.namespaceFor(tenant);
        boundMap(namespace, "survival-overworld", "bucket-a", "secret-a");
        boundMap(namespace, "creative-overworld", "bucket-b", "secret-b");
        BlueMapHosting hosting = createHosting(
                namespace, "friends-maps", "map.friends.example.net", "survival-overworld", "creative-overworld");
        // spec.tls.enabled defaults to true, which would hit the CertManagerUnavailable branch
        // covered separately by tlsRequestedWithoutCertManagerBlocksTheEntireHostingOnARealCluster
        // -- this k3s cluster genuinely has no cert-manager. Disabled here so this test isolates
        // the ConfigMap/Deployment/Service/Ingress properties it actually asserts on.
        hosting.getSpec().getTls().setEnabled(false);

        BlueMapHostingReconciler reconciler = new BlueMapHostingReconciler(client, OperatorConfig.defaults());
        reconciler.reconcile(hosting, null);

        // Requirement 4: one config file per map, plus webserver.conf. Real Kubernetes rejects a
        // ConfigMap data key containing '/' outright, so HostingResourceBuilder#configMapKey
        // sanitises "maps/<id>.conf" to "maps.<id>.conf" before it ever reaches the API server --
        // this reconcile only got past the ConfigMap creation at all once that fix landed (see
        // the task-5 report: this is exactly the class of bug the mock server cannot catch).
        ConfigMap configMap = client.configMaps().inNamespace(namespace).withName("friends-maps-config").get();
        assertNotNull(configMap, "reconciling a valid hosting must create its ConfigMap");
        assertTrue(
                configMap.getData().containsKey("maps.survival-overworld.conf"),
                configMap.getData().keySet().toString());
        assertTrue(
                configMap.getData().containsKey("maps.creative-overworld.conf"),
                configMap.getData().keySet().toString());
        assertTrue(configMap.getData().containsKey("webserver.conf"), configMap.getData().keySet().toString());

        Deployment deployment =
                client.apps().deployments().inNamespace(namespace).withName("friends-maps").get();
        assertNotNull(deployment, "reconciling a valid hosting must create its Deployment");
        assertEquals(
                OperatorConfig.defaults().hostingImage(),
                deployment
                        .getSpec()
                        .getTemplate()
                        .getSpec()
                        .getContainers()
                        .get(0)
                        .getImage());
        assertEquals(1, deployment.getSpec().getReplicas());

        // The API server round-trips the ConfigMap volume's `items` (key -> nested path)
        // untouched -- proving the sanitised keys and their restored paths are not just accepted
        // by HostingResourceBuilder's pure-function tests, but by the real object schema too.
        var configVolume = deployment.getSpec().getTemplate().getSpec().getVolumes().stream()
                .filter(volume -> volume.getConfigMap() != null)
                .findFirst()
                .orElseThrow(() -> new AssertionError("deployment must mount the hosting ConfigMap"));
        var keyToPath = configVolume.getConfigMap().getItems().stream()
                .collect(java.util.stream.Collectors.toMap(
                        io.fabric8.kubernetes.api.model.KeyToPath::getKey,
                        io.fabric8.kubernetes.api.model.KeyToPath::getPath));
        assertEquals(
                "maps/survival-overworld.conf",
                keyToPath.get("maps.survival-overworld.conf"),
                "the volume item must restore the original nested path: " + keyToPath);

        Service service = client.services().inNamespace(namespace).withName("friends-maps").get();
        assertNotNull(service, "reconciling a valid hosting must create its Service");
        assertEquals(
                HostingResourceBuilder.WEBSERVER_PORT,
                service.getSpec().getPorts().get(0).getPort());

        Ingress ingress =
                client.network().v1().ingresses().inNamespace(namespace).withName("friends-maps").get();
        assertNotNull(ingress, "reconciling a valid hosting must create its Ingress");
        assertEquals(
                "map.friends.example.net",
                ingress.getSpec().getRules().get(0).getHost());
        assertEquals(
                "friends-maps",
                ingress.getSpec()
                        .getRules()
                        .get(0)
                        .getHttp()
                        .getPaths()
                        .get(0)
                        .getBackend()
                        .getService()
                        .getName());
    }

    // --- Requirement 3: both security checks hold against a real Tenant/namespace lookup ---

    @Test
    void hostnameOutsideAllowedDomainsCreatesNoResourcesOnARealCluster() throws Exception {
        Tenant tenant = tenantWithAllowedDomains("friends-s1", "*.friends.example.net");
        String namespace = TenantReconciler.namespaceFor(tenant);
        boundMap(namespace, "survival-overworld", "bucket-a", "secret-a");
        BlueMapHosting hosting =
                createHosting(namespace, "friends-maps", "map.evil.example.com", "survival-overworld");

        BlueMapHostingReconciler reconciler = new BlueMapHostingReconciler(client, OperatorConfig.defaults());
        reconciler.reconcile(hosting, null);

        assertEquals(BlueMapHostingReconciler.HOSTNAME_NOT_ALLOWED_REASON, readyReason(hosting));
        assertNull(
                client.apps().deployments().inNamespace(namespace).withName("friends-maps").get(),
                "no Deployment may be created for a hostname outside the tenant's allowedDomains");
        assertNull(
                client.network().v1().ingresses().inNamespace(namespace).withName("friends-maps").get(),
                "no Ingress may be created for a hostname outside the tenant's allowedDomains");
        assertNull(
                client.configMaps().inNamespace(namespace).withName("friends-maps-config").get(),
                "no ConfigMap may be created for a hostname outside the tenant's allowedDomains");
    }

    @Test
    void mapMissingFromNamespaceCreatesNoResourcesOnARealCluster() throws Exception {
        Tenant tenant = tenantWithAllowedDomains("friends-s2", "*.friends.example.net");
        String namespace = TenantReconciler.namespaceFor(tenant);
        BlueMapHosting hosting =
                createHosting(namespace, "friends-maps", "map.friends.example.net", "does-not-exist");

        BlueMapHostingReconciler reconciler = new BlueMapHostingReconciler(client, OperatorConfig.defaults());
        reconciler.reconcile(hosting, null);

        assertEquals(BlueMapHostingReconciler.MAP_NOT_FOUND_REASON, readyReason(hosting));
        assertNull(
                client.apps().deployments().inNamespace(namespace).withName("friends-maps").get(),
                "no Deployment may be created for a map that does not exist in the hosting's namespace");
        assertNull(
                client.configMaps().inNamespace(namespace).withName("friends-maps-config").get(),
                "no ConfigMap may be created for a map that does not exist in the hosting's namespace");
    }

    // --- The client.supports(Certificate.class) branch: untestable against the mock server -

    @Test
    void certManagerSupportsReturnsFalseOnARealClusterWithoutCertManagerInstalled() {
        // This is the exact assertion the fabric8 mock server used by BlueMapHostingReconcilerTest
        // can never make: EnableKubernetesMockClient answers supports() with an unconditional
        // true for any CustomResource, cert-manager installed or not. This k3s cluster genuinely
        // has no cert-manager, so this is the first time this call is proven to return false.
        assertFalse(
                client.supports(Certificate.class),
                "cert-manager is not installed on this cluster; supports() must report that honestly");
    }

    @Test
    void tlsRequestedWithoutCertManagerBlocksTheEntireHostingOnARealCluster() throws Exception {
        Tenant tenant = tenantWithAllowedDomains("friends-tls", "*.friends.example.net");
        String namespace = TenantReconciler.namespaceFor(tenant);
        boundMap(namespace, "survival-overworld", "bucket-a", "secret-a");
        BlueMapHosting hosting =
                createHosting(namespace, "friends-maps", "map.friends.example.net", "survival-overworld");
        hosting.getSpec().getTls().setEnabled(true);
        hosting.getSpec().getTls().getIssuerRef().setName("letsencrypt-prod");

        BlueMapHostingReconciler reconciler = new BlueMapHostingReconciler(client, OperatorConfig.defaults());
        reconciler.reconcile(hosting, null);

        assertEquals(BlueMapHostingReconciler.CERT_MANAGER_UNAVAILABLE_REASON, readyReason(hosting));
        assertNull(
                client.apps().deployments().inNamespace(namespace).withName("friends-maps").get(),
                "TLS requested without cert-manager must block the whole hosting, not just the Certificate");
        assertNull(
                client.configMaps().inNamespace(namespace).withName("friends-maps-config").get(),
                "TLS requested without cert-manager must block the whole hosting, not just the Certificate");
        assertNull(
                client.network().v1().ingresses().inNamespace(namespace).withName("friends-maps").get(),
                "TLS requested without cert-manager must block the whole hosting, not just the Certificate");
    }

    // --- Fixtures ---------------------------------------------------------------------------

    /**
     * Creates a {@code Tenant} with {@code allowedDomains} set, then reconciles it for real via
     * {@link TenantReconciler} so its namespace exists and carries the exact {@code
     * apus.onelitefeather.net/tenant} label {@link BlueMapHostingReconciler} looks up -- not a
     * hand-labelled stand-in, unlike {@code BlueMapHostingReconcilerTest}'s mock-server fixture,
     * which only needs to fool an in-memory map.
     */
    private static Tenant tenantWithAllowedDomains(String tenantName, String... allowedDomains) {
        Tenant tenant = new Tenant();
        tenant.setMetadata(new ObjectMetaBuilder().withName(tenantName).build());
        tenant.getSpec().setDisplayName(tenantName);
        tenant.getSpec().getStorage().setQuota("10Gi");
        tenant.getSpec().getHosting().setAllowedDomains(List.of(allowedDomains));
        Tenant created = client.resources(Tenant.class).resource(tenant).create();

        new TenantReconciler(client, OperatorConfig.defaults()).reconcile(created, null);
        return created;
    }

    /** Creates a {@code BlueMapMap} in {@code namespace} with a bucket already bound in status. */
    private static BlueMapMap boundMap(String namespace, String name, String bucketName, String secretName) {
        BlueMapMap map = new BlueMapMap();
        map.setMetadata(new ObjectMetaBuilder()
                .withName(name)
                .withNamespace(namespace)
                .build());
        map.getSpec().getSource().setDimension("minecraft:overworld");
        map.getSpec().getBluemap().setMinecraftVersion("1.21.10");
        BlueMapMap created =
                client.resources(BlueMapMap.class).inNamespace(namespace).resource(map).create();

        created.getStatus().getBucket().setName(bucketName);
        created.getStatus().getBucket().setSecretName(secretName);
        created.getStatus().getBucket().setEndpoint("http://rgw.example.svc:80");
        client.resources(BlueMapMap.class).inNamespace(namespace).resource(created).updateStatus();
        return created;
    }

    private static BlueMapHosting createHosting(String namespace, String name, String hostname, String... mapNames) {
        BlueMapHosting hosting = new BlueMapHosting();
        hosting.setMetadata(new ObjectMetaBuilder()
                .withName(name)
                .withNamespace(namespace)
                .build());
        hosting.getSpec().setHostname(hostname);
        for (String mapName : mapNames) {
            Ref ref = new Ref();
            ref.setName(mapName);
            hosting.getSpec().getMaps().add(ref);
        }
        // The API server assigns the UID; BlueMapHostingReconciler's ownership check depends on
        // it (see the class Javadoc), so the reconciler must see the server-assigned object.
        return client.resources(BlueMapHosting.class).inNamespace(namespace).resource(hosting).create();
    }

    private static String readyReason(BlueMapHosting hosting) {
        return hosting.getStatus().getConditions().stream()
                .filter(condition -> Conditions.READY.equals(condition.getType()))
                .findFirst()
                .orElseThrow()
                .getReason();
    }
}
