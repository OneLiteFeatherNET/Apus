# Per-Tenant App UI Instance Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `TenantReconciler` provisions a Deployment, Service and Ingress per tenant so the tenant application is served at `https://<host>/t/<tenant>/`, and reports the two Entra redirect URIs that must be registered by hand.

**Architecture:** A pure `TenantUiResourceBuilder` turns a `Tenant` plus a `TenantUiConfig` into the three manifests; `TenantReconciler` submits them with the same `createOr(update)` idempotence it already uses, guarded by whether a host is configured at all. Configuration reaches the operator as `APUS_TENANT_UI_*` environment variables, rendered by the operator chart from a `tenantUi` value block.

**Tech Stack:** Java 21, fabric8 Kubernetes client, JOSDK, JUnit 5, fabric8 `KubernetesMockServerExtension`, Helm, Spotless (palantir-java-format).

**Spec:** `docs/superpowers/specs/2026-08-16-per-tenant-app-instance-design.md`

## Global Constraints

- **Every pod in a tenant namespace must declare `resources.requests.cpu` and `.memory`.** The namespace's `ResourceQuota` constrains both and its `LimitRange` is empty (`spec.limits: null`), so a Deployment without requests is accepted and never produces a pod. Use `cpu: 50m` / `memory: 128Mi` requests and `memory: 256Mi` limit, matching `ui.resources` in the platform chart.
- **The tenant namespace is `bluemap-<name>`**, from `TenantReconciler.namespaceFor(Tenant)`. Never re-derive it.
- **Container port is 8080**, pinned by `ui/Dockerfile` (`PORT=8080`); Nitro's own default of 3000 is not what the image uses.
- **Base URL has a trailing slash, the ingress path does not**: `NUXT_APP_BASE_URL=/t/acme/`, ingress path `/t/acme`.
- **`pathType` must be `Prefix`.** The tunnel controller rejects any other value except `ImplementationSpecific`.
- Every created resource carries `Labels.standard(...)` plus `Labels.TENANT` and `Labels.TENANT_UID`, and the owner reference built by `TenantReconciler`.
- Licence header: copy the 17-line AGPL block verbatim from any existing file in the same module.
- Formatting is enforced by `./gradlew :operator:spotlessJavaCheck`; run `spotlessApply` before committing.

---

### Task 1: `TenantUiConfig` and its wiring into `OperatorConfig`

**Files:**

- Create: `operator/src/main/java/net/onelitefeather/apus/operator/TenantUiConfig.java`
- Modify: `operator/src/main/java/net/onelitefeather/apus/operator/OperatorConfig.java`
- Test: `operator/src/test/java/net/onelitefeather/apus/operator/TenantUiConfigTest.java`
- Test (modify): `operator/src/test/java/net/onelitefeather/apus/operator/OperatorConfigTest.java`

**Interfaces:**

- Consumes: nothing.
- Produces: `TenantUiConfig(String host, String image, String ingressClassName, String apiBaseUrl, String oidcIssuer, String oidcClientId, String oidcScope)` with `boolean enabled()` and `static TenantUiConfig disabled()` / `static TenantUiConfig fromEnvironment(Function<String,String>)`; `OperatorConfig.tenantUi()` returning it.

- [ ] **Step 1: Write the failing test**

`operator/src/test/java/net/onelitefeather/apus/operator/TenantUiConfigTest.java`:

```java
package net.onelitefeather.apus.operator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.Test;

class TenantUiConfigTest {

    @Test
    void isDisabledWhenNoHostIsConfigured() {
        assertFalse(TenantUiConfig.fromEnvironment(name -> null).enabled());
        assertEquals(TenantUiConfig.disabled(), TenantUiConfig.fromEnvironment(name -> null));
    }

    @Test
    void isDisabledWhenTheHostIsBlank() {
        Map<String, String> env = Map.of("APUS_TENANT_UI_HOST", "   ");

        assertFalse(TenantUiConfig.fromEnvironment(env::get).enabled());
    }

    @Test
    void isEnabledOnceAHostIsSet() {
        Map<String, String> env = Map.of("APUS_TENANT_UI_HOST", "apus.example.dev");

        assertTrue(TenantUiConfig.fromEnvironment(env::get).enabled());
    }

    @Test
    void readsEveryVariable() {
        Map<String, String> env = Map.ofEntries(
                Map.entry("APUS_TENANT_UI_HOST", "apus.example.dev"),
                Map.entry("APUS_TENANT_UI_IMAGE", "apus/ui:1.2.3"),
                Map.entry("APUS_TENANT_UI_INGRESS_CLASS", "cloudflare-tunnel"),
                Map.entry("APUS_TENANT_UI_API_BASE_URL", "https://apus.example.dev"),
                Map.entry("APUS_TENANT_UI_OIDC_ISSUER", "https://issuer.example/v2.0"),
                Map.entry("APUS_TENANT_UI_OIDC_CLIENT_ID", "client-id"),
                Map.entry("APUS_TENANT_UI_OIDC_SCOPE", "api://client-id/access_as_user openid"));

        TenantUiConfig config = TenantUiConfig.fromEnvironment(env::get);

        assertEquals("apus.example.dev", config.host());
        assertEquals("apus/ui:1.2.3", config.image());
        assertEquals("cloudflare-tunnel", config.ingressClassName());
        assertEquals("https://apus.example.dev", config.apiBaseUrl());
        assertEquals("https://issuer.example/v2.0", config.oidcIssuer());
        assertEquals("client-id", config.oidcClientId());
        assertEquals("api://client-id/access_as_user openid", config.oidcScope());
    }

    @Test
    void fallsBackToTheDefaultImageAndIngressClass() {
        Map<String, String> env = Map.of("APUS_TENANT_UI_HOST", "apus.example.dev");

        TenantUiConfig config = TenantUiConfig.fromEnvironment(env::get);

        assertEquals("apus/ui:dev", config.image());
        assertEquals("nginx", config.ingressClassName());
    }
}
```

- [ ] **Step 2: Run it to make sure it fails**

Run: `./gradlew :operator:test --tests '*TenantUiConfigTest*'`
Expected: FAIL — compilation error, `TenantUiConfig` does not exist.

- [ ] **Step 3: Write the implementation**

`operator/src/main/java/net/onelitefeather/apus/operator/TenantUiConfig.java` (licence header omitted here — copy it verbatim from `OperatorConfig.java`):

```java
package net.onelitefeather.apus.operator;

import java.util.function.Function;

/**
 * Settings for the per-tenant application instance {@code TenantReconciler} provisions: which
 * image to run it from, which host and ingress class to expose it on, and the public runtime
 * configuration every instance is handed.
 *
 * <p>The four {@code NUXT_PUBLIC_*} values are modelled one by one rather than as a free-form
 * map: {@link OperatorConfig} is built entirely from environment variables, so a map would have
 * to be serialised through a single variable and would lose its schema, its documentation and
 * the ability to test each value on its own. All four are identical for every tenant -- same
 * API, same issuer, same OIDC client -- and none is a secret; every one of them reaches the
 * served HTML by design.
 *
 * @param host the host tenant paths hang off. <b>Blank disables the feature entirely</b>: an
 *     instance with no host would have nothing to serve it, and creating a Deployment nobody can
 *     reach would burn a pod per tenant for nothing
 * @param image the tenant application image, the same one the platform chart deploys as {@code ui}
 * @param ingressClassName the ingress class of the per-tenant {@code Ingress}; must match the
 *     platform's, since both serve paths on {@link #host}
 * @param apiBaseUrl becomes {@code NUXT_PUBLIC_API_BASE_URL}. The origin only, with no {@code
 *     /api} suffix -- the typed client already asks for paths beginning with {@code /api}
 * @param oidcIssuer becomes {@code NUXT_PUBLIC_OIDC_ISSUER}
 * @param oidcClientId becomes {@code NUXT_PUBLIC_OIDC_CLIENT_ID}
 * @param oidcScope becomes {@code NUXT_PUBLIC_OIDC_SCOPE}
 */
public record TenantUiConfig(
        String host,
        String image,
        String ingressClassName,
        String apiBaseUrl,
        String oidcIssuer,
        String oidcClientId,
        String oidcScope) {

    private static final String DEFAULT_IMAGE = "apus/ui:dev";
    private static final String DEFAULT_INGRESS_CLASS = "nginx";

    /** The feature switched off: no host, so no per-tenant instance is provisioned at all. */
    public static TenantUiConfig disabled() {
        return new TenantUiConfig("", DEFAULT_IMAGE, DEFAULT_INGRESS_CLASS, "", "", "", "");
    }

    /**
     * Recognised variables: {@code APUS_TENANT_UI_HOST}, {@code APUS_TENANT_UI_IMAGE}, {@code
     * APUS_TENANT_UI_INGRESS_CLASS}, {@code APUS_TENANT_UI_API_BASE_URL}, {@code
     * APUS_TENANT_UI_OIDC_ISSUER}, {@code APUS_TENANT_UI_OIDC_CLIENT_ID}, {@code
     * APUS_TENANT_UI_OIDC_SCOPE}.
     */
    public static TenantUiConfig fromEnvironment(Function<String, String> env) {
        return new TenantUiConfig(
                valueOrDefault(env.apply("APUS_TENANT_UI_HOST"), ""),
                valueOrDefault(env.apply("APUS_TENANT_UI_IMAGE"), DEFAULT_IMAGE),
                valueOrDefault(env.apply("APUS_TENANT_UI_INGRESS_CLASS"), DEFAULT_INGRESS_CLASS),
                valueOrDefault(env.apply("APUS_TENANT_UI_API_BASE_URL"), ""),
                valueOrDefault(env.apply("APUS_TENANT_UI_OIDC_ISSUER"), ""),
                valueOrDefault(env.apply("APUS_TENANT_UI_OIDC_CLIENT_ID"), ""),
                valueOrDefault(env.apply("APUS_TENANT_UI_OIDC_SCOPE"), ""));
    }

    /** Whether a per-tenant instance should be provisioned at all -- see {@link #host}. */
    public boolean enabled() {
        return host != null && !host.isBlank();
    }

    private static String valueOrDefault(String value, String defaultValue) {
        return (value == null || value.isBlank()) ? defaultValue : value;
    }
}
```

Then add the component to `OperatorConfig`: a `TenantUiConfig tenantUi` parameter at the end of the record header, `TenantUiConfig.disabled()` in `defaults()`, and `TenantUiConfig.fromEnvironment(env)` in `fromEnvironment`. Add the Javadoc `@param tenantUi settings for the per-tenant application instance; see {@link TenantUiConfig}`.

- [ ] **Step 4: Extend `OperatorConfigTest`**

Add to `defaultsMatchTheFeatherCoreCluster`:

```java
        assertFalse(config.tenantUi().enabled());
```

and a new test:

```java
    @Test
    void fromEnvironmentCarriesTheTenantUiSettings() {
        Map<String, String> env = Map.of("APUS_TENANT_UI_HOST", "apus.example.dev");

        OperatorConfig config = OperatorConfig.fromEnvironment(env::get);

        assertTrue(config.tenantUi().enabled());
        assertEquals("apus.example.dev", config.tenantUi().host());
    }
```

Add the `assertFalse`/`assertTrue` static imports.

- [ ] **Step 5: Run the tests**

Run: `./gradlew :operator:test --tests '*TenantUiConfigTest*' --tests '*OperatorConfigTest*'`
Expected: PASS.

- [ ] **Step 6: Format and commit**

```bash
./gradlew :operator:spotlessApply
git add operator/src/main/java/net/onelitefeather/apus/operator/TenantUiConfig.java \
        operator/src/main/java/net/onelitefeather/apus/operator/OperatorConfig.java \
        operator/src/test/java/net/onelitefeather/apus/operator/TenantUiConfigTest.java \
        operator/src/test/java/net/onelitefeather/apus/operator/OperatorConfigTest.java
git commit --no-gpg-sign -m "feat(operator): configure the per-tenant application instance"
```

---

### Task 2: `TenantUiResourceBuilder`

**Files:**

- Create: `operator/src/main/java/net/onelitefeather/apus/operator/tenant/TenantUiResourceBuilder.java`
- Test: `operator/src/test/java/net/onelitefeather/apus/operator/tenant/TenantUiResourceBuilderTest.java`

**Interfaces:**

- Consumes: `TenantUiConfig` (Task 1); `Tenant`, `Labels`, `TenantReconciler.namespaceFor`.
- Produces:
  - `static String basePath(Tenant)` → `/t/<name>/`
  - `static String ingressPath(Tenant)` → `/t/<name>`
  - `static List<String> redirectUris(Tenant, TenantUiConfig)` → the two `https://<host>/t/<name>/auth/{callback,silent-renew}`
  - `static Deployment deployment(Tenant, TenantUiConfig, Map<String,String> labels, OwnerReference owner)`
  - `static Service service(Tenant, Map<String,String> labels, OwnerReference owner)`
  - `static Ingress ingress(Tenant, TenantUiConfig, Map<String,String> labels, OwnerReference owner)`
  - `static final String RESOURCE_NAME = "apus-tenant-ui"`, `static final int CONTAINER_PORT = 8080`

  Labels and the owner reference are passed in rather than rebuilt, so the builder cannot drift from what `TenantReconciler` stamps on everything else.

- [ ] **Step 1: Write the failing test**

`operator/src/test/java/net/onelitefeather/apus/operator/tenant/TenantUiResourceBuilderTest.java`:

```java
package net.onelitefeather.apus.operator.tenant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.fabric8.kubernetes.api.model.Container;
import io.fabric8.kubernetes.api.model.EnvVar;
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

    private static Map<String, String> envOf(Deployment deployment) {
        Container container =
                deployment.getSpec().getTemplate().getSpec().getContainers().get(0);
        return container.getEnv().stream().collect(Collectors.toMap(EnvVar::getName, EnvVar::getValue));
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
        assertEquals(
                "apus/ui:1.2.3",
                deployment.getSpec().getTemplate().getSpec().getContainers().get(0).getImage());
    }

    /**
     * The tenant namespace's ResourceQuota constrains requests.cpu and requests.memory, and the
     * LimitRange beside it is empty (spec.limits: null). A quota on a compute resource makes that
     * request mandatory for every pod, and an empty limit range supplies no default -- so a
     * Deployment without requests is created happily and then never produces a pod.
     */
    @Test
    void theDeploymentDeclaresResourceRequestsOrTheQuotaWouldRejectEveryPod() {
        Deployment deployment = TenantUiResourceBuilder.deployment(tenant(), config(), LABELS, OWNER);
        Container container =
                deployment.getSpec().getTemplate().getSpec().getContainers().get(0);

        assertNotNull(container.getResources());
        assertEquals("50m", container.getResources().getRequests().get("cpu").toString());
        assertEquals("128Mi", container.getResources().getRequests().get("memory").toString());
    }

    @Test
    void everyResourceIsOwnedByTheTenantAndLabelledLikeTheRest() {
        var deployment = TenantUiResourceBuilder.deployment(tenant(), config(), LABELS, OWNER);
        var service = TenantUiResourceBuilder.service(tenant(), LABELS, OWNER);
        var ingress = TenantUiResourceBuilder.ingress(tenant(), config(), LABELS, OWNER);

        for (var meta : List.of(deployment.getMetadata(), service.getMetadata(), ingress.getMetadata())) {
            assertEquals("apus-tenant-ui", meta.getName());
            assertEquals("bluemap-acme", meta.getNamespace());
            assertEquals(LABELS, meta.getLabels());
            assertEquals(List.of(OWNER), meta.getOwnerReferences());
        }
    }

    @Test
    void theIngressRoutesTheTenantPathOnTheConfiguredHost() {
        Ingress ingress = TenantUiResourceBuilder.ingress(tenant(), config(), LABELS, OWNER);
        var rule = ingress.getSpec().getRules().get(0);
        var path = rule.getHttp().getPaths().get(0);

        assertEquals("cloudflare-tunnel", ingress.getSpec().getIngressClassName());
        assertEquals("apus.example.dev", rule.getHost());
        assertEquals("/t/acme", path.getPath());
        assertEquals("Prefix", path.getPathType());
        assertEquals("apus-tenant-ui", path.getBackend().getService().getName());
    }

    /** TLS terminates at the edge; a tls section here would ask for a certificate nobody issues. */
    @Test
    void theIngressAsksForNoTls() {
        Ingress ingress = TenantUiResourceBuilder.ingress(tenant(), config(), LABELS, OWNER);

        assertTrue(ingress.getSpec().getTls() == null
                || ingress.getSpec().getTls().isEmpty());
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
```

- [ ] **Step 2: Run it to make sure it fails**

Run: `./gradlew :operator:test --tests '*TenantUiResourceBuilderTest*'`
Expected: FAIL — `TenantUiResourceBuilder` does not exist.

- [ ] **Step 3: Write the implementation**

`operator/src/main/java/net/onelitefeather/apus/operator/tenant/TenantUiResourceBuilder.java` (licence header verbatim from `TenantReconciler.java`):

```java
package net.onelitefeather.apus.operator.tenant;

import io.fabric8.kubernetes.api.model.Container;
import io.fabric8.kubernetes.api.model.ContainerBuilder;
import io.fabric8.kubernetes.api.model.ContainerPort;
import io.fabric8.kubernetes.api.model.ContainerPortBuilder;
import io.fabric8.kubernetes.api.model.EnvVar;
import io.fabric8.kubernetes.api.model.EnvVarBuilder;
import io.fabric8.kubernetes.api.model.OwnerReference;
import io.fabric8.kubernetes.api.model.Probe;
import io.fabric8.kubernetes.api.model.ProbeBuilder;
import io.fabric8.kubernetes.api.model.Quantity;
import io.fabric8.kubernetes.api.model.ResourceRequirements;
import io.fabric8.kubernetes.api.model.ResourceRequirementsBuilder;
import io.fabric8.kubernetes.api.model.Service;
import io.fabric8.kubernetes.api.model.ServiceBuilder;
import io.fabric8.kubernetes.api.model.ServicePort;
import io.fabric8.kubernetes.api.model.ServicePortBuilder;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.api.model.apps.DeploymentBuilder;
import io.fabric8.kubernetes.api.model.networking.v1.HTTPIngressPathBuilder;
import io.fabric8.kubernetes.api.model.networking.v1.Ingress;
import io.fabric8.kubernetes.api.model.networking.v1.IngressBackendBuilder;
import io.fabric8.kubernetes.api.model.networking.v1.IngressBuilder;
import io.fabric8.kubernetes.api.model.networking.v1.IngressRuleBuilder;
import io.fabric8.kubernetes.api.model.networking.v1.IngressServiceBackendBuilder;
import java.util.List;
import java.util.Map;
import net.onelitefeather.apus.operator.TenantUiConfig;
import net.onelitefeather.apus.operator.api.Tenant;

/**
 * Turns a {@link Tenant} into the Kubernetes objects that serve it its own instance of the tenant
 * application at {@code https://<host>/t/<tenant>/}: a {@link Deployment}, a {@link Service} and
 * an {@link Ingress}.
 *
 * <p>Pure function, no Kubernetes client and no side effects, following {@code
 * HostingResourceBuilder}. Labels and the owner reference are passed in rather than rebuilt here,
 * so what lands on these three objects cannot drift from what {@link TenantReconciler} stamps on
 * the namespace, quota and limit range.
 *
 * <p>One image serves every tenant: {@code NUXT_APP_BASE_URL} moves the served prefix at runtime,
 * so a tenant instance differs from the platform's own {@code ui} Deployment in exactly one
 * environment variable.
 */
public final class TenantUiResourceBuilder {

    /** Name shared by the Deployment, the Service and the Ingress in a tenant's own namespace. */
    public static final String RESOURCE_NAME = "apus-tenant-ui";

    /** Pinned by {@code ui/Dockerfile} ({@code PORT=8080}); Nitro's own default of 3000 is unused. */
    public static final int CONTAINER_PORT = 8080;

    private static final String CONTAINER_NAME = "ui";

    /**
     * Requests are not optional here. A tenant namespace carries a {@code ResourceQuota} on
     * {@code requests.cpu}/{@code requests.memory} and a {@code LimitRange} with no spec at all,
     * so the quota makes both requests mandatory and nothing supplies a default. A Deployment
     * without them is accepted by the API server and then never produces a pod. Values match
     * {@code ui.resources} in the platform chart, where they were measured against Nitro's actual
     * shell-per-request profile.
     */
    private static final String CPU_REQUEST = "50m";

    private static final String MEMORY_REQUEST = "128Mi";

    private static final String MEMORY_LIMIT = "256Mi";

    private TenantUiResourceBuilder() {}

    /** The prefix this tenant's instance is served under, with the trailing slash Nuxt expects. */
    public static String basePath(Tenant tenant) {
        return "/t/" + tenant.getMetadata().getName() + "/";
    }

    /** The same prefix as an ingress path, which carries no trailing slash. */
    public static String ingressPath(Tenant tenant) {
        return "/t/" + tenant.getMetadata().getName();
    }

    /**
     * The two redirect URIs the identity provider must have registered before anyone can sign in
     * to this tenant's instance. Wildcards are not an option: Entra strips the query string when
     * a wildcard URI matches, and the authorization code lives in that query string. Reported on
     * {@code Tenant.status} because a missing registration fails at sign-in with {@code
     * AADSTS50011} and leaves no trace in this cluster at all.
     */
    public static List<String> redirectUris(Tenant tenant, TenantUiConfig config) {
        String prefix = "https://" + config.host() + basePath(tenant);
        return List.of(prefix + "auth/callback", prefix + "auth/silent-renew");
    }

    public static Deployment deployment(
            Tenant tenant, TenantUiConfig config, Map<String, String> labels, OwnerReference owner) {
        Container container = new ContainerBuilder()
                .withName(CONTAINER_NAME)
                .withImage(config.image())
                .withPorts(containerPort())
                .withEnv(env(tenant, config))
                .withResources(resources())
                .withReadinessProbe(probe(tenant))
                .withLivenessProbe(probe(tenant))
                .build();

        return new DeploymentBuilder()
                .withNewMetadata()
                .withName(RESOURCE_NAME)
                .withNamespace(TenantReconciler.namespaceFor(tenant))
                .withLabels(labels)
                .withOwnerReferences(owner)
                .endMetadata()
                .withNewSpec()
                .withReplicas(1)
                .withNewSelector()
                .withMatchLabels(labels)
                .endSelector()
                .withNewTemplate()
                .withNewMetadata()
                .withLabels(labels)
                .endMetadata()
                .withNewSpec()
                .withContainers(container)
                .endSpec()
                .endTemplate()
                .endSpec()
                .build();
    }

    public static Service service(Tenant tenant, Map<String, String> labels, OwnerReference owner) {
        ServicePort port = new ServicePortBuilder()
                .withName("http")
                .withPort(CONTAINER_PORT)
                .withNewTargetPort(CONTAINER_PORT)
                .build();

        return new ServiceBuilder()
                .withNewMetadata()
                .withName(RESOURCE_NAME)
                .withNamespace(TenantReconciler.namespaceFor(tenant))
                .withLabels(labels)
                .withOwnerReferences(owner)
                .endMetadata()
                .withNewSpec()
                .withSelector(labels)
                .withPorts(port)
                .endSpec()
                .build();
    }

    /**
     * The per-tenant {@link Ingress}. It has to be per-tenant and it has to live in the tenant's
     * own namespace: an Ingress may only reference a Service in its own namespace, and each
     * tenant's Service is in {@code bluemap-<name>}. A single operator-owned Ingress listing every
     * tenant's path is therefore not available at any price.
     *
     * <p>No annotations and no {@code tls} section: this cluster's tunnel controller already
     * defaults {@code backend-protocol} to {@code http}, and TLS terminates at the edge, so a
     * {@code tls} section here would ask for a certificate nobody issues.
     */
    public static Ingress ingress(
            Tenant tenant, TenantUiConfig config, Map<String, String> labels, OwnerReference owner) {
        var backend = new IngressBackendBuilder()
                .withService(new IngressServiceBackendBuilder()
                        .withName(RESOURCE_NAME)
                        .withNewPort()
                        .withName("http")
                        .endPort()
                        .build())
                .build();

        var path = new HTTPIngressPathBuilder()
                .withPath(ingressPath(tenant))
                .withPathType("Prefix")
                .withBackend(backend)
                .build();

        var rule = new IngressRuleBuilder()
                .withHost(config.host())
                .withNewHttp()
                .withPaths(path)
                .endHttp()
                .build();

        return new IngressBuilder()
                .withNewMetadata()
                .withName(RESOURCE_NAME)
                .withNamespace(TenantReconciler.namespaceFor(tenant))
                .withLabels(labels)
                .withOwnerReferences(owner)
                .endMetadata()
                .withNewSpec()
                .withIngressClassName(config.ingressClassName())
                .withRules(rule)
                .endSpec()
                .build();
    }

    private static List<EnvVar> env(Tenant tenant, TenantUiConfig config) {
        return List.of(
                literal("NUXT_APP_BASE_URL", basePath(tenant)),
                literal("NUXT_PUBLIC_API_BASE_URL", config.apiBaseUrl()),
                literal("NUXT_PUBLIC_OIDC_ISSUER", config.oidcIssuer()),
                literal("NUXT_PUBLIC_OIDC_CLIENT_ID", config.oidcClientId()),
                literal("NUXT_PUBLIC_OIDC_SCOPE", config.oidcScope()));
    }

    private static EnvVar literal(String name, String value) {
        return new EnvVarBuilder().withName(name).withValue(value).build();
    }

    private static ContainerPort containerPort() {
        return new ContainerPortBuilder()
                .withName("http")
                .withContainerPort(CONTAINER_PORT)
                .build();
    }

    /**
     * Probes hit the tenant's own base path, not {@code /} -- with {@code NUXT_APP_BASE_URL} set,
     * the bare root 404s and a probe there would restart a perfectly healthy pod forever.
     */
    private static Probe probe(Tenant tenant) {
        return new ProbeBuilder()
                .withNewHttpGet()
                .withPath(basePath(tenant))
                .withNewPort(CONTAINER_PORT)
                .endHttpGet()
                .withInitialDelaySeconds(5)
                .withPeriodSeconds(10)
                .build();
    }

    private static ResourceRequirements resources() {
        return new ResourceRequirementsBuilder()
                .withRequests(Map.of("cpu", new Quantity(CPU_REQUEST), "memory", new Quantity(MEMORY_REQUEST)))
                .withLimits(Map.of("memory", new Quantity(MEMORY_LIMIT)))
                .build();
    }
}
```

- [ ] **Step 4: Run the tests**

Run: `./gradlew :operator:test --tests '*TenantUiResourceBuilderTest*'`
Expected: PASS, 9 tests.

- [ ] **Step 5: Format and commit**

```bash
./gradlew :operator:spotlessApply
git add operator/src/main/java/net/onelitefeather/apus/operator/tenant/TenantUiResourceBuilder.java \
        operator/src/test/java/net/onelitefeather/apus/operator/tenant/TenantUiResourceBuilderTest.java
git commit --no-gpg-sign -m "feat(operator): build the per-tenant application instance manifests"
```

---

### Task 3: `Tenant.status.redirectUris`

**Files:**

- Modify: `operator/src/main/java/net/onelitefeather/apus/operator/api/TenantStatus.java`
- Test (modify): `operator/src/test/java/net/onelitefeather/apus/operator/api/ApusResourceTest.java`

**Interfaces:**

- Consumes: nothing.
- Produces: `TenantStatus.getRedirectUris()` / `setRedirectUris(List<String>)`, defaulting to an empty list.

- [ ] **Step 1: Write the failing test**

Append to `ApusResourceTest`:

```java
    @Test
    void tenantStatusStartsWithNoRedirectUris() {
        assertTrue(new TenantStatus().getRedirectUris().isEmpty());
    }

    @Test
    void tenantStatusAbsorbsNullRedirectUris() {
        TenantStatus status = new TenantStatus();

        status.setRedirectUris(null);

        assertTrue(status.getRedirectUris().isEmpty());
    }
```

Add whatever imports the file is missing (`TenantStatus`, `assertTrue`) — check the file first, it may already import the package.

- [ ] **Step 2: Run it**

Run: `./gradlew :operator:test --tests '*ApusResourceTest*'`
Expected: FAIL — `getRedirectUris()` does not exist.

- [ ] **Step 3: Implement**

In `TenantStatus`, beside `pushTokenSecret`:

```java
    private List<String> redirectUris = new ArrayList<>();

    /**
     * The redirect URIs the identity provider must have registered for this tenant's own
     * application instance, or empty when no instance is provisioned. Reported here because the
     * operator cannot register them itself -- that needs Microsoft Graph application permissions
     * on the app registration -- and because a missing registration fails at sign-in with {@code
     * AADSTS50011} from the broker, leaving nothing at all in this cluster's logs to find.
     */
    public List<String> getRedirectUris() {
        return redirectUris;
    }

    public void setRedirectUris(List<String> redirectUris) {
        this.redirectUris = redirectUris == null ? new ArrayList<>() : redirectUris;
    }
```

- [ ] **Step 4: Run the tests, then regenerate the CRDs**

```bash
./gradlew :operator:test --tests '*ApusResourceTest*'
./gradlew :operator:generateCrds
```

Then sync the regenerated CRD into the chart the same way the policy change did, and confirm the diff touches only `tenants.*.yaml` and only adds `redirectUris`:

```bash
git diff --stat deploy/charts/apus-operator/templates/crds.yaml
```

- [ ] **Step 5: Format and commit**

```bash
./gradlew :operator:spotlessApply
git add -A operator deploy/charts/apus-operator
git commit --no-gpg-sign -m "feat(operator): report the redirect URIs a tenant instance needs"
```

---

### Task 4: `TenantReconciler` provisions the instance

**Files:**

- Modify: `operator/src/main/java/net/onelitefeather/apus/operator/tenant/TenantReconciler.java`
- Test (modify): `operator/src/test/java/net/onelitefeather/apus/operator/tenant/TenantReconcilerTest.java`

**Interfaces:**

- Consumes: `TenantUiResourceBuilder` (Task 2), `TenantUiConfig` (Task 1), `TenantStatus.setRedirectUris` (Task 3).
- Produces: no new public API — behaviour only.

- [ ] **Step 1: Read the existing test to match its fixtures**

`TenantReconcilerTest` already sets up a mock server, an `OperatorConfig` and a `Tenant`. Read it before writing anything; reuse its helpers rather than inventing new ones, and note how it constructs `OperatorConfig` — Task 1 added a component, so those call sites need the new argument.

- [ ] **Step 2: Write the failing tests**

```java
    @Test
    void provisionsNoApplicationInstanceWhenNoHostIsConfigured() {
        // The default: a platform that has not opted in gets no per-tenant instance at all.
        reconciler.reconcile(tenant, context);

        assertNull(client.apps()
                .deployments()
                .inNamespace("bluemap-acme")
                .withName(TenantUiResourceBuilder.RESOURCE_NAME)
                .get());
        assertNull(client.services()
                .inNamespace("bluemap-acme")
                .withName(TenantUiResourceBuilder.RESOURCE_NAME)
                .get());
        assertNull(client.network()
                .v1()
                .ingresses()
                .inNamespace("bluemap-acme")
                .withName(TenantUiResourceBuilder.RESOURCE_NAME)
                .get());
        assertTrue(tenant.getStatus().getRedirectUris().isEmpty());
    }

    @Test
    void provisionsTheApplicationInstanceOnceAHostIsConfigured() {
        TenantReconciler withUi = new TenantReconciler(client, configWithTenantUi());

        withUi.reconcile(tenant, context);

        var deployment = client.apps()
                .deployments()
                .inNamespace("bluemap-acme")
                .withName(TenantUiResourceBuilder.RESOURCE_NAME)
                .get();
        assertNotNull(deployment);
        assertNotNull(client.services()
                .inNamespace("bluemap-acme")
                .withName(TenantUiResourceBuilder.RESOURCE_NAME)
                .get());
        assertNotNull(client.network()
                .v1()
                .ingresses()
                .inNamespace("bluemap-acme")
                .withName(TenantUiResourceBuilder.RESOURCE_NAME)
                .get());
        assertEquals(
                List.of(
                        "https://apus.example.dev/t/acme/auth/callback",
                        "https://apus.example.dev/t/acme/auth/silent-renew"),
                tenant.getStatus().getRedirectUris());
    }

    @Test
    void reconcilingTwiceLeavesOneApplicationInstance() {
        TenantReconciler withUi = new TenantReconciler(client, configWithTenantUi());

        withUi.reconcile(tenant, context);
        withUi.reconcile(tenant, context);

        assertEquals(
                1,
                client.apps()
                        .deployments()
                        .inNamespace("bluemap-acme")
                        .list()
                        .getItems()
                        .size());
    }
```

with a helper beside the existing fixtures:

```java
    private static OperatorConfig configWithTenantUi() {
        // Copy every other component from the test's existing config; only tenantUi differs.
        return new OperatorConfig(
                /* … the same values the existing fixture uses … */
                new TenantUiConfig(
                        "apus.example.dev",
                        "apus/ui:1.2.3",
                        "cloudflare-tunnel",
                        "https://apus.example.dev",
                        "https://issuer.example/v2.0",
                        "client-id",
                        "api://client-id/access_as_user openid"));
    }
```

- [ ] **Step 3: Run them to make sure they fail**

Run: `./gradlew :operator:test --tests '*TenantReconcilerTest*'`
Expected: the two "provisions"/"twice" tests FAIL (no Deployment created); the "no host" test PASSES already, which is correct — it pins the default and must stay green throughout.

- [ ] **Step 4: Implement**

In `provisionNamespace`, after the limit range, add:

```java
        provisionApplicationInstance(tenant, tenantName, tenantUid, ownerReference);
```

and the method itself:

```java
    /**
     * Creates (or updates) this tenant's own instance of the tenant application, served at
     * {@code https://<host>/t/<name>/}. Skipped entirely -- and this is the default -- when no
     * host is configured: an instance with no host would have nothing to serve it.
     *
     * <p>The three objects live in the tenant's own namespace, which is where the Ingress has to
     * be anyway: an Ingress may only reference a Service in its own namespace.
     */
    private void provisionApplicationInstance(
            Tenant tenant, String tenantName, String tenantUid, OwnerReference ownerReference) {
        TenantUiConfig tenantUi = config.tenantUi();
        if (!tenantUi.enabled()) {
            tenant.getStatus().setRedirectUris(List.of());
            return;
        }

        String namespace = namespaceFor(tenant);
        Map<String, String> labels = tenantUiLabels(tenantName, tenantUid);

        client.apps()
                .deployments()
                .inNamespace(namespace)
                .resource(TenantUiResourceBuilder.deployment(tenant, tenantUi, labels, ownerReference))
                .createOr(NonDeletingOperation::update);

        client.services()
                .inNamespace(namespace)
                .resource(TenantUiResourceBuilder.service(tenant, labels, ownerReference))
                .createOr(NonDeletingOperation::update);

        client.network()
                .v1()
                .ingresses()
                .inNamespace(namespace)
                .resource(TenantUiResourceBuilder.ingress(tenant, tenantUi, labels, ownerReference))
                .createOr(NonDeletingOperation::update);

        tenant.getStatus().setRedirectUris(TenantUiResourceBuilder.redirectUris(tenant, tenantUi));
    }

    /**
     * The application instance's labels: the standard tenant-ownership set, but named for the
     * component rather than the tenant, because these labels are also the Deployment's selector
     * and the Service's -- two workloads in one namespace sharing a selector would each take the
     * other's pods.
     */
    private static Map<String, String> tenantUiLabels(String tenantName, String tenantUid) {
        Map<String, String> labels = Labels.standard("tenant-ui", tenantName);
        labels.put(Labels.TENANT, tenantName);
        if (tenantUid != null && !tenantUid.isBlank()) {
            labels.put(Labels.TENANT_UID, tenantUid);
        }
        return labels;
    }
```

Add `import java.util.List;` and `import net.onelitefeather.apus.operator.TenantUiConfig;`.

Extend the class Javadoc with a paragraph on the per-tenant instance, matching the style of the existing "Push-token Secret" and "Rook not (yet) installed" paragraphs.

- [ ] **Step 5: Run the tests**

Run: `./gradlew :operator:test --tests '*TenantReconcilerTest*'`
Expected: PASS, including the pre-existing tests.

- [ ] **Step 6: Format and commit**

```bash
./gradlew :operator:spotlessApply
git add operator/src/main/java/net/onelitefeather/apus/operator/tenant/TenantReconciler.java \
        operator/src/test/java/net/onelitefeather/apus/operator/tenant/TenantReconcilerTest.java
git commit --no-gpg-sign -m "feat(operator): provision an application instance per tenant"
```

---

### Task 5: Operator RBAC and chart

**Files:**

- Modify: `deploy/charts/apus-operator/values.yaml`
- Modify: `deploy/charts/apus-operator/templates/deployment.yaml`
- Modify: `deploy/charts/apus-operator/templates/rbac.yaml`
- Modify: `deploy/charts/apus-operator/templates/NOTES.txt`
- Modify: `deploy/charts/apus-operator/values.schema.json` (if the chart has one — check)

**Interfaces:**

- Consumes: the `APUS_TENANT_UI_*` variable names from Task 1.
- Produces: the `tenantUi` value block.

- [ ] **Step 1: Confirm the RBAC the operator already has**

```bash
grep -n -A4 'deployments\|ingresses\|services' deploy/charts/apus-operator/templates/rbac.yaml
```

The operator already creates Deployments, Services and Ingresses for `BlueMapHosting`, so the verbs are expected to be there. **If any is missing, add it** — this is the one failure mode that produces a clean-looking reconcile and no resources.

- [ ] **Step 2: Add the values**

In `values.yaml`, after the `bundles` block:

```yaml
# One instance of the tenant application per tenant, served at https://<host>/t/<tenant>/.
# The operator creates a Deployment, a Service and an Ingress in each tenant's own namespace.
#
# Off by default: an empty host disables the feature entirely. An instance with no host would
# have nothing to serve it, and a Deployment nobody can reach costs a pod per tenant.
#
# Registering the two redirect URIs each instance needs is a manual step -- the operator has no
# permission on the app registration. They are reported on Tenant.status.redirectUris.
tenantUi:
  host: ""
  image:
    repository: harbor.onelitefeather.dev/apus/ui
    tag: ""
  # Must match the platform ingress's class: both serve paths on the same host.
  ingressClassName: nginx
  # The origin only, with no /api suffix -- the typed client already asks for /api paths.
  apiBaseUrl: ""
  oidc:
    issuer: ""
    clientId: ""
    scope: ""
```

- [ ] **Step 3: Render them into the operator's environment**

In `templates/deployment.yaml`, after `APUS_BUNDLE_CREDENTIALS_SECRET`:

```yaml
            - name: APUS_TENANT_UI_HOST
              value: {{ .Values.tenantUi.host | quote }}
            - name: APUS_TENANT_UI_IMAGE
              value: {{ include "apus-operator.image" (dict "image" .Values.tenantUi.image "ctx" .) | quote }}
            - name: APUS_TENANT_UI_INGRESS_CLASS
              value: {{ .Values.tenantUi.ingressClassName | quote }}
            - name: APUS_TENANT_UI_API_BASE_URL
              value: {{ .Values.tenantUi.apiBaseUrl | quote }}
            - name: APUS_TENANT_UI_OIDC_ISSUER
              value: {{ .Values.tenantUi.oidc.issuer | quote }}
            - name: APUS_TENANT_UI_OIDC_CLIENT_ID
              value: {{ .Values.tenantUi.oidc.clientId | quote }}
            - name: APUS_TENANT_UI_OIDC_SCOPE
              value: {{ .Values.tenantUi.oidc.scope | quote }}
```

- [ ] **Step 4: Say the manual step out loud in NOTES.txt**

Append a section that prints only when `tenantUi.host` is set, naming the two URIs with `<tenant>` as a placeholder and pointing at `kubectl get tenant <name> -o jsonpath='{.status.redirectUris}'` for the real ones.

- [ ] **Step 5: Verify the chart renders**

```bash
helm template apus deploy/charts/apus-operator | grep -A1 APUS_TENANT_UI
helm template apus deploy/charts/apus-operator --set tenantUi.host=apus.example.dev | grep -A1 APUS_TENANT_UI_HOST
helm lint deploy/charts/apus-operator
```

Expected: the first shows an empty host (feature off), the second shows `apus.example.dev`, lint passes.

- [ ] **Step 6: Commit**

```bash
git add deploy/charts/apus-operator
git commit --no-gpg-sign -m "feat(chart): expose the per-tenant application instance settings"
```

---

### Task 6: The console shows the redirect URIs

**Files:**

- Modify: the console's tenant view (find it: `rg -l 'tenant' ui/apps/console/app/pages`)
- Modify: whichever API response type carries a tenant (find it: `rg -n 'redirectUris|pushTokenSecret' api/src/main/java`)
- Test: beside the component being changed, matching the existing component-test style

**Interfaces:**

- Consumes: `Tenant.status.redirectUris` from Task 3.
- Produces: no new API.

- [ ] **Step 1: Find out whether the API already exposes tenant status**

```bash
rg -n 'class TenantView|record TenantView|status' api/src/main/java/net/onelitefeather/apus/api/rest/tenant/
```

If the API's tenant representation carries no status fields at all, **add `redirectUris` to it** — a read-only list, no new endpoint. If it already carries status, extend it.

- [ ] **Step 2: Write the failing component test**

A test asserting the tenant view renders both URIs and a copy control, and renders nothing at all when the list is empty (a tenant with no instance must not show an empty "redirect URIs" box).

- [ ] **Step 3: Run it, implement, run it again**

```bash
cd ui && pnpm test
```

- [ ] **Step 4: Lint, typecheck, commit**

```bash
cd ui && pnpm lint && pnpm typecheck && pnpm test
git add ui api
git commit --no-gpg-sign -m "feat(console): show the redirect URIs a tenant instance needs"
```

---

### Task 7: Full verification

- [ ] **Step 1: The whole build**

```bash
./gradlew :operator:test :api:test spotlessCheck
cd ui && pnpm lint && pnpm typecheck && pnpm test
```

- [ ] **Step 2: Prove it against a real API server, not a mock**

The mock server does not enforce the `ResourceQuota`, which is the single most likely thing to be wrong. `OperatorIntegrationTest`/`K3sCrdSupport` already stand up k3s; add a case there that reconciles a tenant with `tenantUi` enabled and asserts a **pod** appears, not merely a Deployment. If that turns out not to fit the existing harness, say so in the PR rather than quietly dropping it.

- [ ] **Step 3: Commit and open the PR**

Describe the four findings from §0 of the spec, and state plainly that per-tenant Entra registration remains manual and why.

## Self-Review

**Spec coverage:** §1 address → Tasks 2, 4. §2 resources → Tasks 2, 4. §3 configuration → Tasks 1, 5. §4 Entra step → Tasks 2 (`redirectUris`), 3 (status), 5 (NOTES), 6 (console). §5 no UI change → nothing to do, correct. §6 tests → Tasks 2, 4, 7.

**Type consistency:** `TenantUiConfig`'s seven components are spelled identically in Tasks 1, 2 and 4. `RESOURCE_NAME` is used, never re-spelled as a literal, after Task 2 defines it. `basePath` keeps the trailing slash and `ingressPath` drops it in every use.

**Known soft spots, stated rather than hidden:** Task 6 begins with a search because the console's tenant view and the API's tenant representation have not been read yet — the task says what to do in either case rather than pretending to know. Task 7 Step 2 may not fit the existing k3s harness; it says to report that instead of silently skipping it.
