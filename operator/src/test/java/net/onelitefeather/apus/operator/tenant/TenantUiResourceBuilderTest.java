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

import io.fabric8.kubernetes.api.model.Container;
import io.fabric8.kubernetes.api.model.EnvVar;
import io.fabric8.kubernetes.api.model.ObjectMeta;
import io.fabric8.kubernetes.api.model.OwnerReference;
import io.fabric8.kubernetes.api.model.OwnerReferenceBuilder;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.api.model.networking.v1.Ingress;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import net.onelitefeather.apus.operator.TenantUiConfig;
import net.onelitefeather.apus.operator.api.Labels;
import net.onelitefeather.apus.operator.api.Tenant;
import org.junit.jupiter.api.Test;

class TenantUiResourceBuilderTest {

    private static final OwnerReference OWNER = new OwnerReferenceBuilder()
            .withApiVersion("bluemap.onelitefeather.net/v1alpha1")
            .withKind("Tenant")
            .withName("acme")
            .withUid("uid-1")
            .withController(true)
            .build();

    private static final Map<String, String> LABELS = Map.of(
            Labels.MANAGED_BY, Labels.MANAGED_BY_VALUE,
            Labels.TENANT, "acme",
            Labels.TENANT_UID, "uid-1");

    private static Tenant tenant() {
        Tenant tenant = new Tenant();
        tenant.getMetadata().setName("acme");
        tenant.getMetadata().setUid("uid-1");
        return tenant;
    }

    private static TenantUiConfig config() {
        return new TenantUiConfig(
                "apus.example.dev",
                "apus/ui:1.2.3",
                "cloudflare-tunnel",
                "https://apus.example.dev",
                "https://issuer.example/v2.0",
                "client-id",
                "api://client-id/access_as_user openid");
    }

    private static Container containerOf(Deployment deployment) {
        return deployment.getSpec().getTemplate().getSpec().getContainers().get(0);
    }

    private static Map<String, String> envOf(Deployment deployment) {
        return containerOf(deployment).getEnv().stream().collect(Collectors.toMap(EnvVar::getName, EnvVar::getValue));
    }

    @Test
    void theBasePathHasATrailingSlashAndTheIngressPathDoesNot() {
        assertEquals("/t/acme/", TenantUiResourceBuilder.basePath(tenant()));
        assertEquals("/t/acme", TenantUiResourceBuilder.ingressPath(tenant()));
    }

    @Test
    void theDeploymentServesTheTenantsOwnBasePath() {
        Deployment deployment = TenantUiResourceBuilder.deployment(tenant(), config(), LABELS, OWNER);

        assertEquals("/t/acme/", envOf(deployment).get("NUXT_APP_BASE_URL"));
    }

    @Test
    void theDeploymentCarriesThePublicRuntimeConfiguration() {
        Map<String, String> env = envOf(TenantUiResourceBuilder.deployment(tenant(), config(), LABELS, OWNER));

        assertEquals("https://apus.example.dev", env.get("NUXT_PUBLIC_API_BASE_URL"));
        assertEquals("https://issuer.example/v2.0", env.get("NUXT_PUBLIC_OIDC_ISSUER"));
        assertEquals("client-id", env.get("NUXT_PUBLIC_OIDC_CLIENT_ID"));
        assertEquals("api://client-id/access_as_user openid", env.get("NUXT_PUBLIC_OIDC_SCOPE"));
    }

    @Test
    void theDeploymentRunsTheConfiguredImageInTheTenantNamespace() {
        Deployment deployment = TenantUiResourceBuilder.deployment(tenant(), config(), LABELS, OWNER);

        assertEquals("bluemap-acme", deployment.getMetadata().getNamespace());
        assertEquals("apus/ui:1.2.3", containerOf(deployment).getImage());
    }

    /**
     * A tenant namespace carries a {@code ResourceQuota} on {@code requests.cpu}/{@code
     * requests.memory}, and the {@code LimitRange} beside it has no spec at all ({@code
     * spec.limits: null} on the live cluster). A quota on a compute resource makes that request
     * mandatory for every pod, and an empty limit range supplies no default to fall back on -- so
     * a Deployment without requests is accepted by the API server and then never produces a pod.
     * That failure looks like a healthy Deployment with zero replicas and no event worth reading.
     */
    @Test
    void theDeploymentDeclaresResourceRequestsOrTheQuotaWouldRejectEveryPod() {
        Container container = containerOf(TenantUiResourceBuilder.deployment(tenant(), config(), LABELS, OWNER));

        assertNotNull(container.getResources());
        assertEquals("50m", container.getResources().getRequests().get("cpu").toString());
        assertEquals("128Mi", container.getResources().getRequests().get("memory").toString());
    }

    /**
     * The platform chart hardens its own {@code ui} pod, and this runs the identical image. An
     * instance that skipped it would be the same software running with fewer restrictions purely
     * because a controller created it rather than Helm -- and it trips a {@code
     * PodSecurity "restricted"} warning on every tenant pod today, which becomes a rejection the
     * moment anyone sets that namespace to enforce.
     */
    @Test
    void theInstanceIsHardenedTheSameWayThePlatformHardensTheSameImage() {
        Deployment deployment = TenantUiResourceBuilder.deployment(tenant(), config(), LABELS, OWNER);
        var pod = deployment.getSpec().getTemplate().getSpec();
        var security = containerOf(deployment).getSecurityContext();

        assertEquals(Boolean.TRUE, pod.getSecurityContext().getRunAsNonRoot());
        // The distroless :nonroot base runs as 65532, not the 10001 the Java images use.
        assertEquals(65532L, pod.getSecurityContext().getRunAsUser());
        assertEquals(
                "RuntimeDefault", pod.getSecurityContext().getSeccompProfile().getType());
        assertEquals(Boolean.FALSE, security.getAllowPrivilegeEscalation());
        assertEquals(Boolean.TRUE, security.getReadOnlyRootFilesystem());
        assertEquals(List.of("ALL"), security.getCapabilities().getDrop());
    }

    /**
     * With {@code NUXT_APP_BASE_URL} set, the bare root 404s -- a probe there would restart a
     * perfectly healthy pod forever, and it would do so only once the feature was actually
     * switched on in a cluster.
     */
    @Test
    void theProbesHitTheTenantsBasePathRatherThanTheRoot() {
        Container container = containerOf(TenantUiResourceBuilder.deployment(tenant(), config(), LABELS, OWNER));

        assertEquals("/t/acme/", container.getReadinessProbe().getHttpGet().getPath());
        assertEquals("/t/acme/", container.getLivenessProbe().getHttpGet().getPath());
    }

    @Test
    void everyResourceIsOwnedByTheTenantAndLabelledLikeTheRest() {
        var deployment = TenantUiResourceBuilder.deployment(tenant(), config(), LABELS, OWNER);
        var service = TenantUiResourceBuilder.service(tenant(), LABELS, OWNER);
        var ingress = TenantUiResourceBuilder.ingress(tenant(), config(), LABELS, OWNER);

        for (ObjectMeta meta : List.of(deployment.getMetadata(), service.getMetadata(), ingress.getMetadata())) {
            assertEquals("apus-tenant-ui", meta.getName());
            assertEquals("bluemap-acme", meta.getNamespace());
            assertEquals(LABELS, meta.getLabels());
            assertEquals(List.of(OWNER), meta.getOwnerReferences());
        }
    }

    @Test
    void theServiceTargetsTheContainerPortTheImageActuallyListensOn() {
        var port = TenantUiResourceBuilder.service(tenant(), LABELS, OWNER)
                .getSpec()
                .getPorts()
                .get(0);

        assertEquals("http", port.getName());
        assertEquals(8080, port.getPort());
        assertEquals(8080, port.getTargetPort().getIntVal());
    }

    @Test
    void theIngressRoutesTheTenantPathOnTheConfiguredHost() {
        Ingress ingress = TenantUiResourceBuilder.ingress(tenant(), config(), LABELS, OWNER);
        var rule = ingress.getSpec().getRules().get(0);
        var path = rule.getHttp().getPaths().get(0);

        assertEquals("cloudflare-tunnel", ingress.getSpec().getIngressClassName());
        assertEquals("apus.example.dev", rule.getHost());
        assertEquals("/t/acme", path.getPath());
        // The tunnel controller rejects any pathType but Prefix or ImplementationSpecific.
        assertEquals("Prefix", path.getPathType());
        assertEquals("apus-tenant-ui", path.getBackend().getService().getName());
    }

    /** TLS terminates at the edge; a tls section here would ask for a certificate nobody issues. */
    @Test
    void theIngressAsksForNoTls() {
        Ingress ingress = TenantUiResourceBuilder.ingress(tenant(), config(), LABELS, OWNER);

        assertTrue(ingress.getSpec().getTls() == null || ingress.getSpec().getTls().isEmpty());
    }

    @Test
    void theRedirectUrisAreTheTwoEntraMustHaveRegistered() {
        assertEquals(
                List.of(
                        "https://apus.example.dev/t/acme/auth/callback",
                        "https://apus.example.dev/t/acme/auth/silent-renew"),
                TenantUiResourceBuilder.redirectUris(tenant(), config()));
    }
}
