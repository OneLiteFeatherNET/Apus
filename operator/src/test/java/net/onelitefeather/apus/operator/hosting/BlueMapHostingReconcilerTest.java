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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.kubernetes.api.model.NamespaceBuilder;
import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;
import io.fabric8.kubernetes.api.model.Service;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.api.model.apps.DeploymentStatusBuilder;
import io.fabric8.kubernetes.api.model.networking.v1.Ingress;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.server.mock.EnableKubernetesMockClient;
import io.javaoperatorsdk.operator.api.reconciler.UpdateControl;
import java.util.List;
import java.util.UUID;
import net.onelitefeather.apus.operator.OperatorConfig;
import net.onelitefeather.apus.operator.TenantUiConfig;
import net.onelitefeather.apus.operator.api.BlueMapHosting;
import net.onelitefeather.apus.operator.api.BlueMapMap;
import net.onelitefeather.apus.operator.api.Conditions;
import net.onelitefeather.apus.operator.api.Labels;
import net.onelitefeather.apus.operator.api.Ref;
import net.onelitefeather.apus.operator.api.Tenant;
import org.junit.jupiter.api.Test;

@EnableKubernetesMockClient(crud = true)
class BlueMapHostingReconcilerTest {

    private static final String NAMESPACE = "bluemap-friends";
    private static final String TENANT_NAME = "friends";

    KubernetesClient client;

    private void namespaceForTenant(String tenantName, String namespace) {
        client.namespaces()
                .resource(new NamespaceBuilder()
                        .withNewMetadata()
                        .withName(namespace)
                        .withLabels(Labels.standard("tenant", tenantName))
                        .addToLabels(Labels.TENANT, tenantName)
                        .endMetadata()
                        .build())
                .create();
    }

    private Tenant tenant(String name, String... allowedDomains) {
        Tenant tenant = new Tenant();
        tenant.setMetadata(new ObjectMetaBuilder()
                .withName(name)
                .withUid(UUID.randomUUID().toString())
                .build());
        tenant.getSpec().getHosting().setAllowedDomains(List.of(allowedDomains));
        client.resources(Tenant.class).resource(tenant).create();
        return tenant;
    }

    /** Creates a namespace-labelled tenant with no {@code allowedDomains} restriction issue. */
    private void tenantWithDomains(String... allowedDomains) {
        namespaceForTenant(TENANT_NAME, NAMESPACE);
        tenant(TENANT_NAME, allowedDomains);
    }

    private BlueMapMap boundMap(String name, String bucketName, String secretName) {
        BlueMapMap map = new BlueMapMap();
        map.setMetadata(new ObjectMetaBuilder()
                .withName(name)
                .withNamespace(NAMESPACE)
                .withUid(UUID.randomUUID().toString())
                .build());
        map.getSpec().getSource().setDimension("minecraft:overworld");
        client.resources(BlueMapMap.class).inNamespace(NAMESPACE).resource(map).create();

        map.getStatus().getBucket().setName(bucketName);
        map.getStatus().getBucket().setSecretName(secretName);
        map.getStatus().getBucket().setEndpoint("http://rgw.example.svc:80");
        client.resources(BlueMapMap.class).inNamespace(NAMESPACE).resource(map).updateStatus();
        return map;
    }

    private void unboundMap(String name) {
        BlueMapMap map = new BlueMapMap();
        map.setMetadata(new ObjectMetaBuilder()
                .withName(name)
                .withNamespace(NAMESPACE)
                .withUid(UUID.randomUUID().toString())
                .build());
        map.getSpec().getSource().setDimension("minecraft:overworld");
        client.resources(BlueMapMap.class).inNamespace(NAMESPACE).resource(map).create();
    }

    private BlueMapHosting hosting(String name, String hostname, String... mapNames) {
        BlueMapHosting hosting = new BlueMapHosting();
        hosting.setMetadata(new ObjectMetaBuilder()
                .withName(name)
                .withNamespace(NAMESPACE)
                .withUid(UUID.randomUUID().toString())
                .build());
        hosting.getSpec().setHostname(hostname);
        for (String mapName : mapNames) {
            Ref ref = new Ref();
            ref.setName(mapName);
            hosting.getSpec().getMaps().add(ref);
        }
        return hosting;
    }

    private String readyReason(BlueMapHosting hosting) {
        return hosting.getStatus().getConditions().stream()
                .filter(condition -> Conditions.READY.equals(condition.getType()))
                .findFirst()
                .orElseThrow()
                .getReason();
    }

    private Deployment existingDeployment(String name) {
        return client.apps().deployments().inNamespace(NAMESPACE).withName(name).get();
    }

    // --- S1: hostname vs Tenant.spec.hosting.allowedDomains -------------------------------

    @Test
    void hostnameOutsideAllowedDomainsProducesConditionAndNoResources() {
        tenantWithDomains("*.friends.example.net");
        boundMap("survival-overworld", "bucket-a", "secret-a");
        BlueMapHostingReconciler reconciler = new BlueMapHostingReconciler(client, OperatorConfig.defaults());
        BlueMapHosting hosting = hosting("friends-maps", "map.evil.example.com", "survival-overworld");

        UpdateControl<BlueMapHosting> control = reconciler.reconcile(hosting, null);

        assertTrue(control.isPatchStatus());
        assertEquals(BlueMapHostingReconciler.HOSTNAME_NOT_ALLOWED_REASON, readyReason(hosting));
        assertNull(existingDeployment("friends-maps"), "no Deployment may be created for a disallowed hostname");
        assertNull(
                client.network().v1().ingresses().inNamespace(NAMESPACE).withName("friends-maps").get(),
                "no Ingress may be created for a disallowed hostname");
        assertFalse(hosting.getStatus().isReady());
        assertNull(hosting.getStatus().getUrl());
    }

    @Test
    void hostnameMatchingAWildcardDomainCreatesResources() {
        tenantWithDomains("*.friends.example.net");
        boundMap("survival-overworld", "bucket-a", "secret-a");
        BlueMapHostingReconciler reconciler = new BlueMapHostingReconciler(client, OperatorConfig.defaults());
        BlueMapHosting hosting = hosting("friends-maps", "map.friends.example.net", "survival-overworld");

        reconciler.reconcile(hosting, null);

        assertNotNull(existingDeployment("friends-maps"), "an allowed hostname must produce a Deployment");
        assertNotNull(client.services().inNamespace(NAMESPACE).withName("friends-maps").get());
        assertNotNull(
                client.network().v1().ingresses().inNamespace(NAMESPACE).withName("friends-maps").get());
    }

    @Test
    void hostnameMatchingALiteralDomainCreatesResources() {
        tenantWithDomains("map.friends.example.net");
        boundMap("survival-overworld", "bucket-a", "secret-a");
        BlueMapHostingReconciler reconciler = new BlueMapHostingReconciler(client, OperatorConfig.defaults());
        BlueMapHosting hosting = hosting("friends-maps", "map.friends.example.net", "survival-overworld");

        reconciler.reconcile(hosting, null);

        assertNotNull(existingDeployment("friends-maps"));
    }

    @Test
    void wildcardDoesNotMatchMoreThanOneSubdomainLevel() {
        tenantWithDomains("*.friends.example.net");
        boundMap("survival-overworld", "bucket-a", "secret-a");
        BlueMapHostingReconciler reconciler = new BlueMapHostingReconciler(client, OperatorConfig.defaults());
        BlueMapHosting hosting = hosting("friends-maps", "eu.map.friends.example.net", "survival-overworld");

        reconciler.reconcile(hosting, null);

        assertEquals(BlueMapHostingReconciler.HOSTNAME_NOT_ALLOWED_REASON, readyReason(hosting));
        assertNull(existingDeployment("friends-maps"));
    }

    @Test
    void tenantWithNoAllowedDomainsGetsNoHosting() {
        // tenantWithDomains() with zero varargs -- an explicitly empty allowedDomains list.
        tenantWithDomains();
        boundMap("survival-overworld", "bucket-a", "secret-a");
        BlueMapHostingReconciler reconciler = new BlueMapHostingReconciler(client, OperatorConfig.defaults());
        BlueMapHosting hosting = hosting("friends-maps", "map.friends.example.net", "survival-overworld");

        reconciler.reconcile(hosting, null);

        assertEquals(BlueMapHostingReconciler.HOSTING_NOT_CONFIGURED_REASON, readyReason(hosting));
        assertNull(existingDeployment("friends-maps"), "a tenant with no allowedDomains must get no hosting at all");
    }

    @Test
    void namespaceNotYetLabelledWithATenantBlocksHosting() {
        client.namespaces()
                .resource(new NamespaceBuilder().withNewMetadata().withName(NAMESPACE).endMetadata().build())
                .create();
        BlueMapHostingReconciler reconciler = new BlueMapHostingReconciler(client, OperatorConfig.defaults());
        BlueMapHosting hosting = hosting("friends-maps", "map.friends.example.net");

        reconciler.reconcile(hosting, null);

        assertEquals(BlueMapHostingReconciler.TENANT_NOT_FOUND_REASON, readyReason(hosting));
        assertNull(existingDeployment("friends-maps"));
    }

    // --- S2: referenced maps must resolve inside this hosting's own namespace -------------

    @Test
    void mapNotFoundInTheHostingsNamespaceProducesConditionInsteadOfDeployment() {
        tenantWithDomains("*.friends.example.net");
        BlueMapHostingReconciler reconciler = new BlueMapHostingReconciler(client, OperatorConfig.defaults());
        BlueMapHosting hosting = hosting("friends-maps", "map.friends.example.net", "does-not-exist");

        reconciler.reconcile(hosting, null);

        assertEquals(BlueMapHostingReconciler.MAP_NOT_FOUND_REASON, readyReason(hosting));
        assertNull(existingDeployment("friends-maps"));
    }

    @Test
    void mapWithoutABoundBucketBlocksTheDeployment() {
        tenantWithDomains("*.friends.example.net");
        unboundMap("survival-overworld");
        BlueMapHostingReconciler reconciler = new BlueMapHostingReconciler(client, OperatorConfig.defaults());
        BlueMapHosting hosting = hosting("friends-maps", "map.friends.example.net", "survival-overworld");

        reconciler.reconcile(hosting, null);

        assertEquals(BlueMapHostingReconciler.MAP_NOT_READY_REASON, readyReason(hosting));
        assertNull(existingDeployment("friends-maps"));
    }

    // --- Functional behaviour once allowed -------------------------------------------------

    @Test
    void createsAConfigMapWithOneFilePerMap() {
        tenantWithDomains("*.friends.example.net");
        boundMap("survival-overworld", "bucket-a", "secret-a");
        boundMap("creative-overworld", "bucket-b", "secret-b");
        BlueMapHostingReconciler reconciler = new BlueMapHostingReconciler(client, OperatorConfig.defaults());
        BlueMapHosting hosting =
                hosting("friends-maps", "map.friends.example.net", "survival-overworld", "creative-overworld");

        reconciler.reconcile(hosting, null);

        ConfigMap configMap =
                client.configMaps().inNamespace(NAMESPACE).withName("friends-maps-config").get();
        assertNotNull(configMap);
        // ConfigMap data keys are sanitised (no '/' -- a real API server rejects that, see
        // HostingResourceBuilder#configMapKey); the original nested path survives as the
        // corresponding config volume item's `path`, checked separately in
        // HostingResourceBuilderTest#configVolumeItemsMapSanitisedKeysBackToTheirNestedPaths.
        assertTrue(configMap.getData().containsKey("maps.survival-overworld.conf"));
        assertTrue(configMap.getData().containsKey("maps.creative-overworld.conf"));
        assertTrue(configMap.getData().containsKey("webserver.conf"));
    }

    @Test
    void deploymentUsesTheHostingImageFromOperatorConfig() {
        tenantWithDomains("*.friends.example.net");
        boundMap("survival-overworld", "bucket-a", "secret-a");
        OperatorConfig config = new OperatorConfig(
                "rook-ceph-fr01",
                "feather-s3",
                "ceph-bucket-fr01",
                "apus/runner:dev",
                "apus/ingest:dev",
                "apus/hosting:1.2.3",
                "apus-bundles",
                "http://rgw.example.svc:80",
                "us-east-1",
                "apus-bundle-credentials",
                TenantUiConfig.disabled());
        BlueMapHostingReconciler reconciler = new BlueMapHostingReconciler(client, config);
        BlueMapHosting hosting = hosting("friends-maps", "map.friends.example.net", "survival-overworld");

        reconciler.reconcile(hosting, null);

        assertEquals(
                "apus/hosting:1.2.3",
                existingDeployment("friends-maps")
                        .getSpec()
                        .getTemplate()
                        .getSpec()
                        .getContainers()
                        .get(0)
                        .getImage());
    }

    @Test
    void deploymentCarriesAConfigChecksumAnnotationThatChangesWithTheMapList() {
        tenantWithDomains("*.friends.example.net");
        boundMap("survival-overworld", "bucket-a", "secret-a");
        boundMap("creative-overworld", "bucket-b", "secret-b");
        BlueMapHostingReconciler reconciler = new BlueMapHostingReconciler(client, OperatorConfig.defaults());
        BlueMapHosting hosting = hosting("friends-maps", "map.friends.example.net", "survival-overworld");

        reconciler.reconcile(hosting, null);
        String firstChecksum = existingDeployment("friends-maps")
                .getSpec()
                .getTemplate()
                .getMetadata()
                .getAnnotations()
                .get(BlueMapHostingReconciler.CONFIG_CHECKSUM_ANNOTATION);
        assertNotNull(firstChecksum);

        hosting.getSpec().getMaps().add(ref("creative-overworld"));
        reconciler.reconcile(hosting, null);
        String secondChecksum = existingDeployment("friends-maps")
                .getSpec()
                .getTemplate()
                .getMetadata()
                .getAnnotations()
                .get(BlueMapHostingReconciler.CONFIG_CHECKSUM_ANNOTATION);

        assertNotEquals(firstChecksum, secondChecksum, "adding a map must change the checksum so pods restart");
    }

    private static Ref ref(String name) {
        Ref ref = new Ref();
        ref.setName(name);
        return ref;
    }

    @Test
    void certificateIsCreatedWhenTlsIsEnabled() {
        tenantWithDomains("*.friends.example.net");
        boundMap("survival-overworld", "bucket-a", "secret-a");
        BlueMapHostingReconciler reconciler = new BlueMapHostingReconciler(client, OperatorConfig.defaults());
        BlueMapHosting hosting = hosting("friends-maps", "map.friends.example.net", "survival-overworld");
        hosting.getSpec().getTls().setEnabled(true);
        hosting.getSpec().getTls().getIssuerRef().setName("letsencrypt-prod");

        reconciler.reconcile(hosting, null);

        Certificate certificate =
                client.resources(Certificate.class).inNamespace(NAMESPACE).withName("friends-maps").get();
        assertNotNull(certificate, "TLS enabled must create a Certificate");
        assertEquals(List.of("map.friends.example.net"), certificate.getSpec().getDnsNames());
    }

    @Test
    void noCertificateIsCreatedWhenTlsIsDisabled() {
        tenantWithDomains("*.friends.example.net");
        boundMap("survival-overworld", "bucket-a", "secret-a");
        BlueMapHostingReconciler reconciler = new BlueMapHostingReconciler(client, OperatorConfig.defaults());
        BlueMapHosting hosting = hosting("friends-maps", "map.friends.example.net", "survival-overworld");
        hosting.getSpec().getTls().setEnabled(false);

        reconciler.reconcile(hosting, null);

        assertNull(client.resources(Certificate.class)
                .inNamespace(NAMESPACE)
                .withName("friends-maps")
                .get());
    }

    @Test
    void reportsTheUrlOnlyOnceTheDeploymentIsReady() {
        tenantWithDomains("*.friends.example.net");
        boundMap("survival-overworld", "bucket-a", "secret-a");
        BlueMapHostingReconciler reconciler = new BlueMapHostingReconciler(client, OperatorConfig.defaults());
        BlueMapHosting hosting = hosting("friends-maps", "map.friends.example.net", "survival-overworld");

        UpdateControl<BlueMapHosting> firstPass = reconciler.reconcile(hosting, null);
        assertFalse(hosting.getStatus().isReady(), "must not be ready before the Deployment reports ready replicas");
        assertNull(hosting.getStatus().getUrl());
        assertTrue(firstPass.getScheduleDelay().isPresent(), "must be rechecked while waiting for readiness");

        Deployment deployment = existingDeployment("friends-maps");
        deployment.setStatus(
                new DeploymentStatusBuilder().withReadyReplicas(1).build());
        client.apps().deployments().inNamespace(NAMESPACE).resource(deployment).updateStatus();

        reconciler.reconcile(hosting, null);

        assertTrue(hosting.getStatus().isReady());
        assertEquals("https://map.friends.example.net", hosting.getStatus().getUrl());
        assertEquals(BlueMapHostingReconciler.HOSTING_READY_REASON, readyReason(hosting));
    }

    @Test
    void everyCreatedResourceCarriesTheManagedByLabel() {
        tenantWithDomains("*.friends.example.net");
        boundMap("survival-overworld", "bucket-a", "secret-a");
        BlueMapHostingReconciler reconciler = new BlueMapHostingReconciler(client, OperatorConfig.defaults());
        BlueMapHosting hosting = hosting("friends-maps", "map.friends.example.net", "survival-overworld");

        reconciler.reconcile(hosting, null);

        ConfigMap configMap =
                client.configMaps().inNamespace(NAMESPACE).withName("friends-maps-config").get();
        Deployment deployment = existingDeployment("friends-maps");
        Service service = client.services().inNamespace(NAMESPACE).withName("friends-maps").get();
        Ingress ingress =
                client.network().v1().ingresses().inNamespace(NAMESPACE).withName("friends-maps").get();

        assertEquals(Labels.MANAGED_BY_VALUE, configMap.getMetadata().getLabels().get(Labels.MANAGED_BY));
        assertEquals(Labels.MANAGED_BY_VALUE, deployment.getMetadata().getLabels().get(Labels.MANAGED_BY));
        assertEquals(Labels.MANAGED_BY_VALUE, service.getMetadata().getLabels().get(Labels.MANAGED_BY));
        assertEquals(Labels.MANAGED_BY_VALUE, ingress.getMetadata().getLabels().get(Labels.MANAGED_BY));
    }

    // --- Ownership check ---------------------------------------------------------------------

    @Test
    void refusesToAdoptAnUnownedPreExistingDeployment() {
        tenantWithDomains("*.friends.example.net");
        boundMap("survival-overworld", "bucket-a", "secret-a");
        Deployment foreign = new Deployment();
        foreign.setMetadata(new ObjectMetaBuilder()
                .withName("friends-maps")
                .withNamespace(NAMESPACE)
                .build());
        foreign.setSpec(new io.fabric8.kubernetes.api.model.apps.DeploymentSpecBuilder()
                .withNewSelector()
                .addToMatchLabels("app", "unrelated")
                .endSelector()
                .withNewTemplate()
                .withNewMetadata()
                .addToLabels("app", "unrelated")
                .endMetadata()
                .withNewSpec()
                .addNewContainer()
                .withName("unrelated")
                .withImage("busybox")
                .endContainer()
                .endSpec()
                .endTemplate()
                .build());
        client.apps().deployments().inNamespace(NAMESPACE).resource(foreign).create();

        BlueMapHostingReconciler reconciler = new BlueMapHostingReconciler(client, OperatorConfig.defaults());
        BlueMapHosting hosting = hosting("friends-maps", "map.friends.example.net", "survival-overworld");

        UpdateControl<BlueMapHosting> control = reconciler.reconcile(hosting, null);

        assertTrue(control.isPatchStatus());
        assertEquals(BlueMapHostingReconciler.RESOURCE_CONFLICT_REASON, readyReason(hosting));
        assertEquals(
                "busybox",
                existingDeployment("friends-maps")
                        .getSpec()
                        .getTemplate()
                        .getSpec()
                        .getContainers()
                        .get(0)
                        .getImage(),
                "the foreign deployment must not be overwritten");
    }

    @Test
    void isIdempotentAcrossRepeatedReconciles() {
        tenantWithDomains("*.friends.example.net");
        boundMap("survival-overworld", "bucket-a", "secret-a");
        BlueMapHostingReconciler reconciler = new BlueMapHostingReconciler(client, OperatorConfig.defaults());
        BlueMapHosting hosting = hosting("friends-maps", "map.friends.example.net", "survival-overworld");

        reconciler.reconcile(hosting, null);
        UpdateControl<BlueMapHosting> control = reconciler.reconcile(hosting, null);

        assertTrue(control.isPatchStatus());
        assertNotNull(existingDeployment("friends-maps"));
    }
}
