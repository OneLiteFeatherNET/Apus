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
package net.onelitefeather.apus.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;
import io.fabric8.kubernetes.client.Config;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import io.micronaut.core.type.Argument;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.http.client.exceptions.HttpClientResponseException;
import io.micronaut.security.token.generator.TokenGenerator;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import io.micronaut.test.support.TestPropertyProvider;
import jakarta.inject.Inject;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.onelitefeather.apus.api.rest.map.BlueMapMapResponse;
import net.onelitefeather.apus.api.support.PrincipalResolver;
import net.onelitefeather.apus.operator.OperatorConfig;
import net.onelitefeather.apus.operator.api.BlueMapMap;
import net.onelitefeather.apus.operator.api.BlueMapRender;
import net.onelitefeather.apus.operator.api.Tenant;
import net.onelitefeather.apus.operator.tenant.TenantReconciler;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.testcontainers.k3s.K3sContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Phase 5a's actual proof: two tenants' resources on a real Kubernetes API server (k3s, started
 * via Testcontainers, following the exact pattern {@code operator}'s {@code
 * OperatorIntegrationTest}/{@code BlueMapHostingIntegrationTest} already established), and a
 * real, JWT-signed <b>token</b> for tenant {@code acme} proven unable to either see or modify
 * tenant {@code globex}'s {@code BlueMapMap} -- over the real embedded HTTP server, the real
 * security filter chain, and the real {@code Fabric8*Repository} implementations, none of them
 * replaced with a fake (compare {@code BlueMapMapControllerHttpTest}, which replaces the
 * repository precisely because it does *not* need a real cluster). This is the one test in the
 * module where "the tenant isolation holds" is checked against the actual thing it depends on --
 * the Kubernetes API server enforcing namespace boundaries -- rather than against an in-memory
 * stand-in of it.
 *
 * <p>Runs under the {@code k3s} Micronaut environment (see {@code
 * net.onelitefeather.apus.api.support.K3sTestKubernetesClientFactory}), which is what points
 * every repository's {@link KubernetesClient} bean at this test's container instead of
 * ambient/in-cluster config. {@link #getProperties()} starts the container and applies the
 * generated CRDs <em>before</em> the Micronaut context (and with it, that factory) is built --
 * the same ordering guarantee {@link TestPropertyProvider} exists to give.
 *
 * <p>Not part of {@code build}/{@code check}: see the {@code integrationTest} Gradle task in
 * {@code api/build.gradle.kts}, matched by this class's {@code *IntegrationTest} name the same
 * way {@code operator}'s and {@code ingest}'s own {@code integrationTest} tasks match theirs.
 */
@MicronautTest(environments = "k3s")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TenantIsolationIntegrationTest implements TestPropertyProvider {

    private static final Duration CRD_REGISTRATION_TIMEOUT = Duration.ofMinutes(2);
    private static final K3sContainer K3S = new K3sContainer(DockerImageName.parse("rancher/k3s:v1.31.2-k3s1"));

    private static KubernetesClient verificationClient;
    private static String acmeNamespace;
    private static String globexNamespace;

    @Override
    public Map<String, String> getProperties() {
        K3S.start();
        String kubeconfigYaml = K3S.getKubeConfigYaml();
        Config config = Config.fromKubeconfig(kubeconfigYaml);
        verificationClient = new KubernetesClientBuilder().withConfig(config).build();

        applyGeneratedCrds(verificationClient);
        awaitCrdRegistration(verificationClient, "tenants.bluemap.onelitefeather.net");
        awaitCrdRegistration(verificationClient, "bluemapmaps.bluemap.onelitefeather.net");
        awaitCrdRegistration(verificationClient, "bluemaprenders.bluemap.onelitefeather.net");

        Tenant acme = createReconciledTenant("acme");
        Tenant globex = createReconciledTenant("globex");
        acmeNamespace = TenantReconciler.namespaceFor(acme);
        globexNamespace = TenantReconciler.namespaceFor(globex);

        createMap(globexNamespace, "globex-only-map");
        createMap(acmeNamespace, "acme-own-map");

        return Map.of("apus.test.k3s.kubeconfig", kubeconfigYaml);
    }

    @AfterAll
    static void closeVerificationClient() {
        if (verificationClient != null) {
            verificationClient.close();
        }
    }

    @Inject
    @Client("/")
    HttpClient client;

    @Inject
    TokenGenerator tokenGenerator;

    // -- "neither see ..." -------------------------------------------------------------------

    @Test
    void tokenForTenantACannotGetTenantBsMapById() {
        String tokenA = token("carol", List.of("tenant-viewer"), "acme");

        HttpClientResponseException e = assertThrows(
                HttpClientResponseException.class,
                () -> client.toBlocking()
                        .exchange(HttpRequest.GET("/api/maps/globex-only-map").bearerAuth(tokenA)));

        assertEquals(HttpStatus.NOT_FOUND, e.getStatus());
    }

    @Test
    void tokenForTenantACannotSeeTenantBsMapInTheListEndpointEither() {
        String tokenA = token("carol", List.of("tenant-viewer"), "acme");

        List<BlueMapMapResponse> maps = client.toBlocking()
                .exchange(HttpRequest.GET("/api/maps").bearerAuth(tokenA), Argument.listOf(BlueMapMapResponse.class))
                .body();

        assertEquals(List.of("acme-own-map"), maps.stream().map(BlueMapMapResponse::name).toList());
    }

    // -- "... nor modify" --------------------------------------------------------------------

    @Test
    void tokenForTenantACannotTriggerARenderForTenantBsMap() {
        String tokenA = token("dave", List.of("tenant-operator"), "acme");

        HttpClientResponseException e = assertThrows(
                HttpClientResponseException.class,
                () -> client.toBlocking()
                        .exchange(HttpRequest.POST("/api/maps/globex-only-map/render", null)
                                .bearerAuth(tokenA)));

        assertEquals(HttpStatus.NOT_FOUND, e.getStatus());
        assertTrue(
                verificationClient
                        .resources(BlueMapRender.class)
                        .inNamespace(globexNamespace)
                        .list()
                        .getItems()
                        .isEmpty(),
                "no BlueMapRender may be created in a foreign tenant's namespace, even after a rejected attempt");
    }

    // -- Sanity check: the same mechanism does not also block the caller's own tenant -------

    @Test
    void tokenForTenantACanSeeAndModifyItsOwnMap() {
        String tokenA = token("dave", List.of("tenant-operator"), "acme");

        var getResponse = client.toBlocking()
                .exchange(HttpRequest.GET("/api/maps/acme-own-map").bearerAuth(tokenA), BlueMapMapResponse.class);
        assertEquals(HttpStatus.OK, getResponse.getStatus());

        var renderResponse = client.toBlocking()
                .exchange(
                        HttpRequest.POST("/api/maps/acme-own-map/render", null).bearerAuth(tokenA),
                        net.onelitefeather.apus.api.rest.render.BlueMapRenderResponse.class);
        assertEquals(HttpStatus.CREATED, renderResponse.getStatus());
        assertTrue(verificationClient
                .resources(BlueMapRender.class)
                .inNamespace(acmeNamespace)
                .list()
                .getItems()
                .stream()
                .anyMatch(r -> "acme-own-map".equals(
                        r.getSpec().getMapRef().getName())));
    }

    // -- Fixtures -----------------------------------------------------------------------------

    private static Tenant createReconciledTenant(String name) {
        Tenant tenant = new Tenant();
        tenant.setMetadata(new ObjectMetaBuilder().withName(name).build());
        tenant.getSpec().setDisplayName(name);
        tenant.getSpec().getStorage().setQuota("10Gi");
        Tenant created =
                verificationClient.resources(Tenant.class).resource(tenant).create();

        new TenantReconciler(verificationClient, OperatorConfig.defaults()).reconcile(created, null);
        return created;
    }

    private static void createMap(String namespace, String name) {
        BlueMapMap map = new BlueMapMap();
        map.setMetadata(
                new ObjectMetaBuilder().withName(name).withNamespace(namespace).build());
        map.getSpec().getSource().setDimension("minecraft:overworld");
        map.getSpec().getBluemap().setMinecraftVersion("1.21.10");
        BlueMapMap created = verificationClient
                .resources(BlueMapMap.class)
                .inNamespace(namespace)
                .resource(map)
                .create();

        created.getStatus().getBucket().setName(name + "-bucket");
        created.getStatus().getBucket().setSecretName(name + "-secret");
        created.getStatus().getBucket().setEndpoint("http://rgw.example.svc:80");
        verificationClient
                .resources(BlueMapMap.class)
                .inNamespace(namespace)
                .resource(created)
                .updateStatus();
    }

    private String token(String subject, List<String> roles, String tenant) {
        Map<String, Object> claims = new HashMap<>();
        claims.put("sub", subject);
        claims.put("roles", roles);
        claims.put(PrincipalResolver.TENANT_CLAIM, tenant);
        claims.put("iss", "https://apus-test-issuer.internal");
        return tokenGenerator
                .generateToken(claims)
                .orElseThrow(() -> new IllegalStateException("test token generation failed"));
    }

    // -- CRD apply/await, mirroring operator's K3sCrdSupport (no cross-module test-fixture
    // wiring exists yet to share it directly -- see api/build.gradle.kts's integrationTest task
    // for how this module still reuses :operator's *generated CRD manifests* via apus.crd.dir). -

    private static void applyGeneratedCrds(KubernetesClient client) {
        Path crdDir = Path.of(System.getProperty("apus.crd.dir", "build/crds"));
        try (var files = Files.list(crdDir)) {
            files.filter(path -> path.toString().endsWith(".yml") || path.toString().endsWith(".yaml"))
                    .forEach(path -> {
                        try (InputStream in = Files.newInputStream(path)) {
                            client.load(in).serverSideApply();
                        } catch (IOException e) {
                            throw new UncheckedIOException("failed to apply CRD manifest " + path, e);
                        }
                    });
        } catch (IOException e) {
            throw new UncheckedIOException("failed to list CRD manifests in " + crdDir, e);
        }
    }

    private static void awaitCrdRegistration(KubernetesClient client, String crdName) {
        long deadline = System.currentTimeMillis() + CRD_REGISTRATION_TIMEOUT.toMillis();
        boolean known = false;
        while (System.currentTimeMillis() < deadline && !known) {
            known = client.apiextensions().v1().customResourceDefinitions().list().getItems().stream()
                    .anyMatch(crd -> crdName.equals(crd.getMetadata().getName()));
            if (!known) {
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                }
            }
        }
        assertTrue(known, crdName + " CRD must be registered on the API server");
    }
}
