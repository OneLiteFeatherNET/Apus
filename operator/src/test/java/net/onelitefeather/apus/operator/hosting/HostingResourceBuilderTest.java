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

import io.fabric8.kubernetes.api.model.Container;
import io.fabric8.kubernetes.api.model.EnvVar;
import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;
import io.fabric8.kubernetes.api.model.OwnerReference;
import io.fabric8.kubernetes.api.model.Volume;
import io.fabric8.kubernetes.api.model.VolumeMount;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.api.model.networking.v1.HTTPIngressPath;
import io.fabric8.kubernetes.api.model.networking.v1.Ingress;
import io.fabric8.kubernetes.api.model.networking.v1.IngressTLS;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import net.onelitefeather.apus.operator.OperatorConfig;
import net.onelitefeather.apus.operator.api.BlueMapHosting;
import net.onelitefeather.apus.operator.api.Labels;
import org.junit.jupiter.api.Test;

class HostingResourceBuilderTest {

    private BlueMapHosting hosting() {
        BlueMapHosting hosting = new BlueMapHosting();
        hosting.setMetadata(new ObjectMetaBuilder()
                .withName("friends-maps")
                .withNamespace("bluemap-friends")
                .withUid("11111111-1111-1111-1111-111111111111")
                .build());
        hosting.getSpec().getMaps().add(ref("survival-overworld"));
        hosting.getSpec().setHostname("maps.friends.example.com");
        return hosting;
    }

    private net.onelitefeather.apus.operator.api.Ref ref(String name) {
        net.onelitefeather.apus.operator.api.Ref ref = new net.onelitefeather.apus.operator.api.Ref();
        ref.setName(name);
        return ref;
    }

    private Map<String, EnvVar> envOf(Deployment deployment) {
        List<EnvVar> env = deployment
                .getSpec()
                .getTemplate()
                .getSpec()
                .getContainers()
                .get(0)
                .getEnv();
        return env.stream().collect(Collectors.toMap(EnvVar::getName, Function.identity()));
    }

    private void assertOwnedByHosting(List<OwnerReference> ownerReferences) {
        assertNotNull(ownerReferences);
        assertTrue(
                ownerReferences.stream()
                        .anyMatch(ref -> "BlueMapHosting".equals(ref.getKind())
                                && "friends-maps".equals(ref.getName())
                                && "bluemap.onelitefeather.net/v1alpha1".equals(ref.getApiVersion())),
                "expected an owner reference to the BlueMapHosting, got " + ownerReferences);
    }

    @Test
    void deploymentIsOwnedByTheHostingResourceSoItIsGarbageCollected() {
        Deployment deployment =
                HostingResourceBuilder.deployment(
                        hosting(), "friends-maps-config", Set.of("maps/survival-overworld.conf", "webserver.conf"), "bucket-secret", OperatorConfig.defaults());

        assertOwnedByHosting(deployment.getMetadata().getOwnerReferences());
    }

    @Test
    void serviceIsOwnedByTheHostingResource() {
        io.fabric8.kubernetes.api.model.Service service = HostingResourceBuilder.service(hosting());

        assertOwnedByHosting(service.getMetadata().getOwnerReferences());
    }

    @Test
    void ingressIsOwnedByTheHostingResource() {
        Ingress ingress = HostingResourceBuilder.ingress(hosting());

        assertOwnedByHosting(ingress.getMetadata().getOwnerReferences());
    }

    @Test
    void certificateIsOwnedByTheHostingResourceWhenTlsIsEnabled() {
        BlueMapHosting hosting = hosting();
        hosting.getSpec().getTls().setEnabled(true);
        hosting.getSpec().getTls().getIssuerRef().setName("letsencrypt-prod");

        Optional<Certificate> certificate = HostingResourceBuilder.certificate(hosting);

        assertTrue(certificate.isPresent());
        assertOwnedByHosting(certificate.get().getMetadata().getOwnerReferences());
    }

    @Test
    void allResourcesCarryTheStandardManagedByLabel() {
        BlueMapHosting hosting = hosting();
        hosting.getSpec().getTls().getIssuerRef().setName("letsencrypt-prod");

        Deployment deployment =
                HostingResourceBuilder.deployment(
                        hosting, "friends-maps-config", Set.of("maps/survival-overworld.conf", "webserver.conf"), "bucket-secret", OperatorConfig.defaults());
        io.fabric8.kubernetes.api.model.Service service = HostingResourceBuilder.service(hosting);
        Ingress ingress = HostingResourceBuilder.ingress(hosting);
        Certificate certificate = HostingResourceBuilder.certificate(hosting).orElseThrow();

        assertEquals(Labels.MANAGED_BY_VALUE, deployment.getMetadata().getLabels().get(Labels.MANAGED_BY));
        assertEquals(Labels.MANAGED_BY_VALUE, service.getMetadata().getLabels().get(Labels.MANAGED_BY));
        assertEquals(Labels.MANAGED_BY_VALUE, ingress.getMetadata().getLabels().get(Labels.MANAGED_BY));
        assertEquals(Labels.MANAGED_BY_VALUE, certificate.getMetadata().getLabels().get(Labels.MANAGED_BY));
    }

    @Test
    void takesS3CredentialsFromTheSecretRatherThanInliningThem() {
        Deployment deployment =
                HostingResourceBuilder.deployment(
                        hosting(), "friends-maps-config", Set.of("maps/survival-overworld.conf", "webserver.conf"), "bucket-secret", OperatorConfig.defaults());

        Map<String, EnvVar> env = envOf(deployment);

        for (String key : List.of("APUS_S3_ACCESS_KEY", "APUS_S3_SECRET_KEY")) {
            EnvVar var = env.get(key);
            assertNotNull(var, "missing " + key);
            assertNotNull(var.getValueFrom(), key + " must come from a secretKeyRef");
            assertEquals("bucket-secret", var.getValueFrom().getSecretKeyRef().getName());
            assertNull(var.getValue(), key + " must never appear as a literal value in the manifest");
        }
    }

    @Test
    void deploymentMountsTheHostingConfigMap() {
        Deployment deployment =
                HostingResourceBuilder.deployment(
                        hosting(), "friends-maps-config", Set.of("maps/survival-overworld.conf", "webserver.conf"), "bucket-secret", OperatorConfig.defaults());

        List<Volume> volumes =
                deployment.getSpec().getTemplate().getSpec().getVolumes();
        assertTrue(
                volumes.stream()
                        .anyMatch(volume -> volume.getConfigMap() != null
                                && "friends-maps-config".equals(volume.getConfigMap().getName())),
                "expected a volume backed by the ConfigMap friends-maps-config, got " + volumes);

        Container container =
                deployment.getSpec().getTemplate().getSpec().getContainers().get(0);
        List<VolumeMount> mounts = container.getVolumeMounts();
        assertNotNull(mounts);
        assertFalse(mounts.isEmpty(), "container must mount the config volume");
    }

    /**
     * A real Kubernetes API server rejects a {@code ConfigMap} data key containing {@code /}
     * outright -- the fabric8 mock server every other test in this class runs against does not
     * enforce that, which is exactly how this went unnoticed until a real-cluster reconcile tried
     * it (see {@code BlueMapHostingIntegrationTest} and the phase 3 task-5 report). The volume's
     * {@code items} must therefore map a sanitised, slash-free key back to the original nested
     * {@code path} so the file still lands where {@code hosting/bin/config-sync.sh} expects it.
     */
    @Test
    void configVolumeItemsMapSanitisedKeysBackToTheirNestedPaths() {
        Deployment deployment = HostingResourceBuilder.deployment(
                hosting(),
                "friends-maps-config",
                Set.of("maps/survival-overworld.conf", "webserver.conf"),
                "bucket-secret",
                OperatorConfig.defaults());

        Volume configVolume = deployment.getSpec().getTemplate().getSpec().getVolumes().stream()
                .filter(volume -> volume.getConfigMap() != null)
                .findFirst()
                .orElseThrow();
        List<io.fabric8.kubernetes.api.model.KeyToPath> items =
                configVolume.getConfigMap().getItems();
        assertNotNull(items, "the config volume must map its keys back to their original paths");

        Map<String, String> keyToPath = items.stream()
                .collect(Collectors.toMap(
                        io.fabric8.kubernetes.api.model.KeyToPath::getKey,
                        io.fabric8.kubernetes.api.model.KeyToPath::getPath));
        assertEquals(
                "maps/survival-overworld.conf",
                keyToPath.get("maps.survival-overworld.conf"),
                "no '/' may appear in the data key itself, but the item's path must restore it: " + keyToPath);
        assertEquals("webserver.conf", keyToPath.get("webserver.conf"), keyToPath.toString());
        for (String key : keyToPath.keySet()) {
            assertFalse(key.contains("/"), "ConfigMap data keys must never contain '/': " + key);
        }
    }

    @Test
    void deploymentHasReadinessAndLivenessProbes() {
        Deployment deployment =
                HostingResourceBuilder.deployment(
                        hosting(), "friends-maps-config", Set.of("maps/survival-overworld.conf", "webserver.conf"), "bucket-secret", OperatorConfig.defaults());

        Container container =
                deployment.getSpec().getTemplate().getSpec().getContainers().get(0);

        assertNotNull(container.getReadinessProbe(), "a hosting pod must have a readiness probe");
        assertNotNull(container.getReadinessProbe().getHttpGet(), "readiness probe must be HTTP");
        assertNotNull(container.getLivenessProbe(), "a hosting pod must have a liveness probe");
        assertNotNull(container.getLivenessProbe().getHttpGet(), "liveness probe must be HTTP");
    }

    @Test
    void deploymentUsesTheReplicaCountFromTheSpec() {
        BlueMapHosting hosting = hosting();
        hosting.getSpec().setReplicas(3);

        Deployment deployment =
                HostingResourceBuilder.deployment(
                        hosting, "friends-maps-config", Set.of("maps/survival-overworld.conf", "webserver.conf"), "bucket-secret", OperatorConfig.defaults());

        assertEquals(3, deployment.getSpec().getReplicas());
    }

    @Test
    void deploymentIsNamespacedLikeTheHostingItBelongsTo() {
        Deployment deployment =
                HostingResourceBuilder.deployment(
                        hosting(), "friends-maps-config", Set.of("maps/survival-overworld.conf", "webserver.conf"), "bucket-secret", OperatorConfig.defaults());

        assertEquals("bluemap-friends", deployment.getMetadata().getNamespace());
    }

    @Test
    void ingressCarriesTheHostnameFromTheSpec() {
        Ingress ingress = HostingResourceBuilder.ingress(hosting());

        assertEquals(
                "maps.friends.example.com",
                ingress.getSpec().getRules().get(0).getHost());
    }

    @Test
    void ingressUsesTheIngressClassFromTheSpec() {
        BlueMapHosting hosting = hosting();
        hosting.getSpec().setIngressClassName("cloudflare-tunnel");

        Ingress ingress = HostingResourceBuilder.ingress(hosting);

        assertEquals("cloudflare-tunnel", ingress.getSpec().getIngressClassName());
    }

    @Test
    void ingressRoutesToTheHostingService() {
        Ingress ingress = HostingResourceBuilder.ingress(hosting());
        io.fabric8.kubernetes.api.model.Service service = HostingResourceBuilder.service(hosting());

        HTTPIngressPath path = ingress.getSpec()
                .getRules()
                .get(0)
                .getHttp()
                .getPaths()
                .get(0);

        assertEquals(
                service.getMetadata().getName(),
                path.getBackend().getService().getName(),
                "ingress must route to the Service this builder created for the same hosting");
    }

    @Test
    void serviceSelectsThePodsTheDeploymentCreates() {
        Deployment deployment =
                HostingResourceBuilder.deployment(
                        hosting(), "friends-maps-config", Set.of("maps/survival-overworld.conf", "webserver.conf"), "bucket-secret", OperatorConfig.defaults());
        io.fabric8.kubernetes.api.model.Service service = HostingResourceBuilder.service(hosting());

        Map<String, String> podLabels =
                deployment.getSpec().getTemplate().getMetadata().getLabels();
        Map<String, String> selector = service.getSpec().getSelector();

        assertFalse(selector.isEmpty());
        selector.forEach((key, value) -> assertEquals(value, podLabels.get(key), "selector key " + key + " does not match pod label"));
    }

    @Test
    void producesACertificateWhenTlsIsEnabledAndTheIngressReferencesItsSecret() {
        BlueMapHosting hosting = hosting();
        hosting.getSpec().getTls().setEnabled(true);
        hosting.getSpec().getTls().getIssuerRef().setName("letsencrypt-prod");
        hosting.getSpec().getTls().setIssuerKind("ClusterIssuer");

        Optional<Certificate> certificate = HostingResourceBuilder.certificate(hosting);
        Ingress ingress = HostingResourceBuilder.ingress(hosting);

        assertTrue(certificate.isPresent(), "TLS enabled must produce a Certificate");
        assertEquals(
                List.of("maps.friends.example.com"),
                certificate.get().getSpec().getDnsNames());
        assertEquals("letsencrypt-prod", certificate.get().getSpec().getIssuerRef().getName());
        assertEquals(
                "ClusterIssuer", certificate.get().getSpec().getIssuerRef().getKind());

        List<IngressTLS> tls = ingress.getSpec().getTls();
        assertNotNull(tls);
        assertFalse(tls.isEmpty(), "ingress must carry a tls section when TLS is enabled");
        assertEquals(
                certificate.get().getSpec().getSecretName(),
                tls.get(0).getSecretName(),
                "ingress tls secretName must match the Certificate's secretName");
        assertEquals(List.of("maps.friends.example.com"), tls.get(0).getHosts());
    }

    @Test
    void producesNoCertificateAndNoTlsSectionWhenTlsIsDisabled() {
        BlueMapHosting hosting = hosting();
        hosting.getSpec().getTls().setEnabled(false);

        Optional<Certificate> certificate = HostingResourceBuilder.certificate(hosting);
        Ingress ingress = HostingResourceBuilder.ingress(hosting);

        assertTrue(certificate.isEmpty(), "TLS disabled must not produce a Certificate");
        assertTrue(
                ingress.getSpec().getTls() == null
                        || ingress.getSpec().getTls().isEmpty(),
                "ingress must not carry a tls section when TLS is disabled");
    }
}
