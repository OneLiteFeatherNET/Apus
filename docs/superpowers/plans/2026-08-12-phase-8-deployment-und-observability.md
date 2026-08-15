# Apus Phase 8 — Deployment and Observability: Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Apus can be rolled out into a cluster via GitOps, and whoever operates it can see on the dashboard what the system is currently doing — instead of having to piece it together from `kubectl` output.

**Architecture:** The repository ships a Kustomize base under `deploy/`, which the cluster repository (`Kubernetes-FLUX`) references and overrides with its own values via an overlay. The six CRD YAMLs are checked in rather than only generated, so that a rollout does not require a Gradle run; a test keeps the checked-in version in sync with the generator. Metrics follow the pattern already established in the repository: the operator exposes them, like `telemetry-addon`, over the JDK's own `HttpServer`, the API over Micronaut's Micrometer integration.

**Tech Stack:** Kustomize, Prometheus Operator (`PodMonitor`/`ServiceMonitor` from the kube-prometheus-stack already present in the cluster), Micrometer 1.15, JOSDK 5.5.1, Grafana, k3s via Testcontainers.

## Global Constraints

- **Prerequisite: Phase 7 is complete.** The manifests reference the images built there (`apus/operator`, `apus/api`, `apus/ui`); without them this plan cannot be rolled out.
- **Java toolchain 25**, base packages as before (`net.onelitefeather.apus.operator`, `...apus.api`).
- **AGPL license header** on every new Java file; Spotless enforces it.
- **New dependencies go into the inline version catalog** in `settings.gradle.kts` — this repository deliberately uses no `libs.versions.toml`. Every new version gets a comment there stating what it was checked against, the way the existing entries do.
- **The operator works strictly namespace-local** (design spec §10.1). This plan's RBAC rules must not loosen that in any way.
- **Credentials never appear in metrics, labels or dashboards** (design spec §12).
- **Integration tests stay excluded from the PR build** — the k3s test from Task 8 follows the existing `*IntegrationTest` convention.

---

### Task 1: Check in the CRD YAMLs and keep them in sync

Today `./gradlew :operator:generateCrds` generates the six CRDs into `operator/build/crds`. Anyone rolling out Apus needs them, though, before the operator starts for the first time — and a cluster repository should not have to run Gradle for that.

**Files:**

- Create: `deploy/crds/*.yaml` (six files, generator output)
- Create: `operator/src/test/java/net/onelitefeather/apus/operator/CrdsInSyncTest.java`
- Modify: `operator/build.gradle.kts` (also write the generator's output to `deploy/crds`)

**Interfaces:**

- Consumes: `generateCrds` (JavaExec task, `operator/build.gradle.kts:62`), which writes to `build/crds`.
- Produces: `deploy/crds/` as the checked-in source for Task 2.

- [ ] **Step 1: Generate the CRDs and note the names**

Run: `./gradlew :operator:generateCrds && ls operator/build/crds/`
Expected: six YAML files. Note down the exact file names — they are needed in Step 3.

- [ ] **Step 2: Write a failing test**

```java
package net.onelitefeather.apus.operator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Guards the CRDs checked in under {@code deploy/crds} against drift from the generator.
 *
 * <p>They are checked in so that rolling Apus out needs no Gradle run, which means nothing
 * stops a CustomResource class from changing without its YAML following. This test is that
 * something: it fails the build rather than letting a cluster receive a schema that no
 * longer matches the code.
 */
class CrdsInSyncTest {

    @Test
    void checkedInCrdsMatchTheGeneratedOnes() throws IOException {
        Path generated = Path.of(System.getProperty("apus.crd.dir"));
        Path checkedIn = Path.of("..", "deploy", "crds");

        Map<String, String> generatedFiles = read(generated);
        Map<String, String> checkedInFiles = read(checkedIn);

        assertEquals(
                generatedFiles.keySet(),
                checkedInFiles.keySet(),
                "deploy/crds is missing or has extra files; run ./gradlew :operator:generateCrds");

        generatedFiles.forEach((name, content) ->
                assertEquals(
                        content,
                        checkedInFiles.get(name),
                        name + " differs; run ./gradlew :operator:generateCrds and commit the result"));
    }

    @Test
    void allSixCustomResourcesArePresent() throws IOException {
        assertEquals(6, read(Path.of("..", "deploy", "crds")).size());
    }

    private static Map<String, String> read(Path dir) throws IOException {
        assertTrue(Files.isDirectory(dir), dir + " does not exist");
        try (Stream<Path> files = Files.list(dir)) {
            return files.filter(p -> p.toString().endsWith(".yaml"))
                    .collect(Collectors.toMap(
                            p -> p.getFileName().toString(),
                            p -> {
                                try {
                                    return Files.readString(p);
                                } catch (IOException e) {
                                    throw new IllegalStateException(e);
                                }
                            }));
        }
    }
}
```

- [ ] **Step 3: Run the test and confirm the failure**

Run: `./gradlew :operator:test --tests '*CrdsInSyncTest*'`
Expected: FAIL with `../deploy/crds does not exist`.

- [ ] **Step 4: Have the generator also write to `deploy/crds`**

In `operator/build.gradle.kts`, after the `generateCrds` registration:

```kotlin
val syncCrds by tasks.registering(Copy::class) {
    description = "Copies the generated CRDs to deploy/crds, which is what gets rolled out."
    group = "build"
    from(generateCrds)
    into(rootProject.layout.projectDirectory.dir("deploy/crds"))
}
```

- [ ] **Step 5: Generate the CRDs and check them in**

Run: `./gradlew :operator:syncCrds && ls deploy/crds/`
Expected: the same six files as in Step 1.

- [ ] **Step 6: Test passes**

Run: `./gradlew :operator:test --tests '*CrdsInSyncTest*'`
Expected: PASS

- [ ] **Step 7: Confirm the test actually catches drift**

```bash
printf '\n# drift\n' >> deploy/crds/$(ls deploy/crds | head -1)
./gradlew :operator:test --tests '*CrdsInSyncTest*' || echo "caught"
git checkout deploy/crds
```

Expected: `caught` — a test that does not notice drift is worthless.

- [ ] **Step 8: Commit**

```bash
git add deploy/crds operator/build.gradle.kts operator/src/test/java/net/onelitefeather/apus/operator/CrdsInSyncTest.java
git commit -m "feat: check in the generated CRDs and guard them against drift"
```

---

### Task 2: Kustomize base for the operator

**Files:**

- Create: `deploy/base/kustomization.yaml`
- Create: `deploy/base/namespace.yaml`
- Create: `deploy/base/operator-serviceaccount.yaml`
- Create: `deploy/base/operator-rbac.yaml`
- Create: `deploy/base/operator-deployment.yaml`
- Create: `deploy/README.md`

**Interfaces:**

- Consumes: `deploy/crds/` from Task 1; the environment variables from `OperatorConfig` (`APUS_ROOK_NAMESPACE`, `APUS_CEPH_OBJECT_STORE`, `APUS_BUCKET_STORAGE_CLASS`, `APUS_RUNNER_IMAGE`, `APUS_INGEST_IMAGE`, `APUS_HOSTING_IMAGE`, `APUS_BUNDLE_BUCKET`, `APUS_BUNDLE_S3_ENDPOINT`, `APUS_BUNDLE_S3_REGION`, `APUS_BUNDLE_CREDENTIALS_SECRET`).
- Produces: the base that Task 3 (API and UI) and Task 6 (PodMonitor) build on.

- [ ] **Step 1: Namespace and ServiceAccount**

`deploy/base/namespace.yaml`:

```yaml
apiVersion: v1
kind: Namespace
metadata:
  name: apus-system
```

`deploy/base/operator-serviceaccount.yaml`:

```yaml
apiVersion: v1
kind: ServiceAccount
metadata:
  name: apus-operator
  namespace: apus-system
```

- [ ] **Step 2: RBAC**

`deploy/base/operator-rbac.yaml`:

```yaml
apiVersion: rbac.authorization.k8s.io/v1
kind: ClusterRole
metadata:
  name: apus-operator
rules:
  # Own custom resources, including status and finalizers.
  - apiGroups: ["bluemap.onelitefeather.net"]
    resources:
      - tenants
      - worldsources
      - worldingests
      - bluemapmaps
      - bluemaprenders
      - bluemaphostings
    verbs: ["get", "list", "watch", "create", "update", "patch", "delete"]
  - apiGroups: ["bluemap.onelitefeather.net"]
    resources:
      - tenants/status
      - worldsources/status
      - worldingests/status
      - bluemapmaps/status
      - bluemaprenders/status
      - bluemaphostings/status
    verbs: ["get", "update", "patch"]
  - apiGroups: ["bluemap.onelitefeather.net"]
    resources:
      - tenants/finalizers
      - bluemapmaps/finalizers
    verbs: ["update"]
  # A Tenant creates a namespace with its quota and network policy (design spec §8.1).
  - apiGroups: [""]
    resources: ["namespaces", "resourcequotas", "limitranges"]
    verbs: ["get", "list", "watch", "create", "update", "patch"]
  - apiGroups: ["networking.k8s.io"]
    resources: ["networkpolicies"]
    verbs: ["get", "list", "watch", "create", "update", "patch"]
  # Renders and ingests are Jobs; hosting is a Deployment behind a Service and Ingress.
  - apiGroups: ["batch"]
    resources: ["jobs"]
    verbs: ["get", "list", "watch", "create", "update", "patch", "delete"]
  - apiGroups: ["apps"]
    resources: ["deployments"]
    verbs: ["get", "list", "watch", "create", "update", "patch", "delete"]
  - apiGroups: [""]
    resources: ["services", "configmaps"]
    verbs: ["get", "list", "watch", "create", "update", "patch", "delete"]
  - apiGroups: ["networking.k8s.io"]
    resources: ["ingresses"]
    verbs: ["get", "list", "watch", "create", "update", "patch", "delete"]
  # Reading the render pod's /progress endpoint and its termination message (design spec §7.2).
  - apiGroups: [""]
    resources: ["pods", "pods/log"]
    verbs: ["get", "list", "watch"]
  # Rook provisions bucket, credentials secret and endpoint ConfigMap (design spec §9.1).
  - apiGroups: ["objectbucket.io"]
    resources: ["objectbucketclaims"]
    verbs: ["get", "list", "watch", "create", "update", "patch", "delete"]
  - apiGroups: ["ceph.rook.io"]
    resources: ["cephobjectstoreusers"]
    verbs: ["get", "list", "watch", "create", "update", "patch", "delete"]
  # The secrets Rook creates, wired into render jobs and hosting pods. Deliberately not
  # cluster-wide write: the operator only ever reads them.
  - apiGroups: [""]
    resources: ["secrets"]
    verbs: ["get", "list", "watch"]
  - apiGroups: [""]
    resources: ["events"]
    verbs: ["create", "patch"]
---
apiVersion: rbac.authorization.k8s.io/v1
kind: ClusterRoleBinding
metadata:
  name: apus-operator
roleRef:
  apiGroup: rbac.authorization.k8s.io
  kind: ClusterRole
  name: apus-operator
subjects:
  - kind: ServiceAccount
    name: apus-operator
    namespace: apus-system
```

- [ ] **Step 3: Check the RBAC against the actual code**

Run: `grep -rhoE '\b(Job|Deployment|Service|Ingress|ConfigMap|Secret|Namespace|ResourceQuota|LimitRange|NetworkPolicy|ObjectBucketClaim|CephObjectStoreUser|Pod)\b' operator/src/main/java --include='*.java' | sort -u`
Expected: every type it prints has a rule above. If one is missing, add it — an overly narrow ClusterRole shows up at runtime as `Forbidden` in the middle of a reconciliation, not at startup.

- [ ] **Step 4: Operator Deployment**

`deploy/base/operator-deployment.yaml`:

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: apus-operator
  namespace: apus-system
  labels:
    app.kubernetes.io/name: apus-operator
    app.kubernetes.io/part-of: apus
spec:
  replicas: 1
  selector:
    matchLabels:
      app.kubernetes.io/name: apus-operator
  template:
    metadata:
      labels:
        app.kubernetes.io/name: apus-operator
        app.kubernetes.io/part-of: apus
    spec:
      serviceAccountName: apus-operator
      securityContext:
        runAsNonRoot: true
        runAsUser: 10001
        seccompProfile:
          type: RuntimeDefault
      containers:
        - name: operator
          image: harbor.onelitefeather.dev/apus/operator:0.1.0
          imagePullPolicy: IfNotPresent
          ports:
            - name: metrics
              containerPort: 8080
          env:
            # Defaults live in OperatorConfig; every value here is set explicitly so that
            # what a cluster runs with is readable from the manifest rather than the code.
            - name: APUS_ROOK_NAMESPACE
              value: rook-ceph
            - name: APUS_CEPH_OBJECT_STORE
              value: ceph-objectstore
            - name: APUS_BUCKET_STORAGE_CLASS
              value: ceph-bucket
            - name: APUS_RUNNER_IMAGE
              value: harbor.onelitefeather.dev/apus/runner:0.1.0
            - name: APUS_INGEST_IMAGE
              value: harbor.onelitefeather.dev/apus/ingest:0.1.0
            - name: APUS_HOSTING_IMAGE
              value: harbor.onelitefeather.dev/apus/hosting:0.1.0
            - name: APUS_BUNDLE_BUCKET
              value: apus-bundles
            - name: APUS_BUNDLE_S3_ENDPOINT
              value: http://rook-ceph-rgw-ceph-objectstore.rook-ceph.svc:80
            - name: APUS_BUNDLE_S3_REGION
              value: us-east-1
            - name: APUS_BUNDLE_CREDENTIALS_SECRET
              value: apus-bundle-credentials
          resources:
            requests:
              cpu: 100m
              memory: 256Mi
            limits:
              memory: 512Mi
          securityContext:
            allowPrivilegeEscalation: false
            readOnlyRootFilesystem: true
            capabilities:
              drop: ["ALL"]
```

- [ ] **Step 5: Kustomization and README**

`deploy/base/kustomization.yaml`:

```yaml
apiVersion: kustomize.config.k8s.io/v1beta1
kind: Kustomization

resources:
  - ../crds
  - namespace.yaml
  - operator-serviceaccount.yaml
  - operator-rbac.yaml
  - operator-deployment.yaml
```

For that, `deploy/crds` needs its own `kustomization.yaml` listing the six files:

```yaml
apiVersion: kustomize.config.k8s.io/v1beta1
kind: Kustomization

resources:
  - <the six file names from Task 1, Step 1>
```

`deploy/README.md`:

```markdown
# Rolling out

`base/` is the complete but unconfigured Kustomize base. Cluster-specific
values — registry, image tags, Rook names, hostnames — belong in an overlay in the
cluster repository, not here.

    kubectl apply -k deploy/base            # directly, for a test cluster
    kustomize build deploy/base | kubectl apply -f -

The CRDs under `crds/` are generated. They are never edited by hand; instead they are
refreshed via

    ./gradlew :operator:syncCrds

`CrdsInSyncTest` breaks the build if anyone forgets.
```

- [ ] **Step 6: Validate the manifests**

Run: `kustomize build deploy/base > /tmp/apus-base.yaml && grep -c '^kind:' /tmp/apus-base.yaml`
Expected: at least 11 objects (6 CRDs, Namespace, ServiceAccount, ClusterRole, ClusterRoleBinding, Deployment).

Run: `kubectl apply --dry-run=client -f /tmp/apus-base.yaml`
Expected: every line ends in `(dry run)`, no errors.

- [ ] **Step 7: Commit**

```bash
git add deploy/
git commit -m "feat: add a Kustomize base for rolling out the operator"
```

---

### Task 3: Manifests for the API and the UI

**Files:**

- Create: `deploy/base/api-deployment.yaml`
- Create: `deploy/base/api-service.yaml`
- Create: `deploy/base/api-rbac.yaml`
- Create: `deploy/base/ui-deployment.yaml`
- Create: `deploy/base/ui-service.yaml`
- Create: `deploy/base/ingress.yaml`
- Modify: `deploy/base/kustomization.yaml`

- [ ] **Step 1: Determine the API's RBAC instead of guessing it**

Run: `grep -rn 'resources(\|\.secrets()\|\.namespaces()\|customResources' api/src/main/java --include='*.java' | head -20`
Expected: a list of the resources actually touched. The API reads the custom resources and — for the push-token lookup — secrets. Exactly those, and no more, go into the role.

- [ ] **Step 2: Write the API RBAC**

`deploy/base/api-rbac.yaml`:

```yaml
apiVersion: v1
kind: ServiceAccount
metadata:
  name: apus-api
  namespace: apus-system
---
apiVersion: rbac.authorization.k8s.io/v1
kind: ClusterRole
metadata:
  name: apus-api
rules:
  - apiGroups: ["bluemap.onelitefeather.net"]
    resources:
      - tenants
      - worldsources
      - worldingests
      - bluemapmaps
      - bluemaprenders
      - bluemaphostings
    verbs: ["get", "list", "watch", "create", "update", "patch", "delete"]
  # Service-token lookup. This is deliberately cluster-wide read on secrets today, which
  # is wider than ideal -- see design spec §15, point 9. Narrowing it is scoped in the
  # phase 9 plan; until then this rule must not be copied as a pattern for anything else.
  - apiGroups: [""]
    resources: ["secrets"]
    verbs: ["get", "list"]
---
apiVersion: rbac.authorization.k8s.io/v1
kind: ClusterRoleBinding
metadata:
  name: apus-api
roleRef:
  apiGroup: rbac.authorization.k8s.io
  kind: ClusterRole
  name: apus-api
subjects:
  - kind: ServiceAccount
    name: apus-api
    namespace: apus-system
```

- [ ] **Step 3: Deployments and Services**

`deploy/base/api-deployment.yaml` — same structure as the operator Deployment (`securityContext`, `runAsUser: 10001`, `readOnlyRootFilesystem`), image `harbor.onelitefeather.dev/apus/api:0.1.0`, `serviceAccountName: apus-api`, port 8080, plus:

```yaml
          env:
            - name: MICRONAUT_ENVIRONMENTS
              value: k8s
            # The issuer is the one open product decision (design spec §15, point 3). The
            # overlay in the cluster repository supplies the real value; the base leaves
            # it empty on purpose so that a half-configured rollout fails loudly at startup
            # instead of accepting unvalidated tokens.
            - name: MICRONAUT_SECURITY_TOKEN_JWT_SIGNATURES_JWKS_DEFAULT_URL
              value: ""
          readinessProbe:
            httpGet:
              path: /health/readiness
              port: 8080
            initialDelaySeconds: 10
          livenessProbe:
            httpGet:
              path: /health/liveness
              port: 8080
            initialDelaySeconds: 30
```

`deploy/base/api-service.yaml` and `deploy/base/ui-service.yaml`: one `ClusterIP` Service each on port 8080 with a matching selector.

`deploy/base/ui-deployment.yaml`: image `harbor.onelitefeather.dev/apus/ui:0.1.0`, `runAsUser: 101` (the unprivileged nginx base from Phase 7, Task 8 runs under this uid — not 10001), port 8080, `readOnlyRootFilesystem: false`, because nginx writes to its cache directory.

- [ ] **Step 4: Ingress**

`deploy/base/ingress.yaml` — one host, two paths: `/api` to the API Service, `/` to the UI Service. `ingressClassName: nginx`, TLS via cert-manager, hostname as the placeholder `apus.example.net`, which the overlay replaces.

- [ ] **Step 5: Verify the health endpoints before checking in the probes**

Run: `grep -rn 'micronaut-management\|endpoints:' api/build.gradle.kts api/src/main/resources/application.yml`
Expected: `micronaut-management` is present as a dependency and `/health` is enabled. If it is not, the probes hit nothing and the pod restarts endlessly — in that case, run Task 5 of this plan first (it brings in `micronaut-management`) and come back here afterwards.

- [ ] **Step 6: Extend and validate the kustomization**

Add the six new files under `resources` in `deploy/base/kustomization.yaml`.

Run: `kustomize build deploy/base | kubectl apply --dry-run=client -f -`
Expected: no errors.

- [ ] **Step 7: Commit**

```bash
git add deploy/base
git commit -m "feat: add deployment manifests for the API and the dashboard"
```

---

### Task 4: Operator metrics

Design spec §13.1 requires "renders by phase, ingest duration, quota usage per tenant". None of that exists.

**Files:**

- Modify: `settings.gradle.kts` (Micrometer in the catalog)
- Modify: `operator/build.gradle.kts`
- Create: `operator/src/main/java/net/onelitefeather/apus/operator/metrics/ApusMetrics.java`
- Create: `operator/src/main/java/net/onelitefeather/apus/operator/metrics/MetricsServer.java`
- Create: `operator/src/test/java/net/onelitefeather/apus/operator/metrics/ApusMetricsTest.java`
- Create: `operator/src/test/java/net/onelitefeather/apus/operator/metrics/MetricsServerTest.java`
- Modify: `operator/src/main/java/net/onelitefeather/apus/operator/ApusOperator.java`

**Interfaces:**

- Produces:

  ```java
  public final class ApusMetrics {
      public ApusMetrics(MeterRegistry registry);
      public void recordRenderPhase(String tenant, String phase);
      public void recordIngestDuration(String tenant, Duration duration);
      public void recordStorageUsed(String tenant, long bytes);
      public String scrape();
  }
  public final class MetricsServer implements AutoCloseable {
      public MetricsServer(int port, Supplier<String> scrape);
      public void start() throws IOException;
      /** The port actually bound -- differs from the constructor argument when that was 0. */
      public int port();
      @Override public void close();
  }
  ```

- Consumes: JOSDK 5.5.1's `Metrics` interface for the reconciliation metrics.

- [ ] **Step 1: Add the catalog entries**

In the `versionCatalogs` block of `settings.gradle.kts`:

```kotlin
// Micrometer: the operator has no web framework to inherit a registry from, so it takes
// the Prometheus registry directly and serves it over the JDK HttpServer, exactly like
// telemetry-addon does. Version verified against Maven Central on 2026-08-12.
version("micrometer", "1.15.2")
library("micrometer.core", "io.micrometer", "micrometer-core").versionRef("micrometer")
library("micrometer.registry.prometheus", "io.micrometer", "micrometer-registry-prometheus")
    .versionRef("micrometer")
// JOSDK's own reconciliation metrics (queue depth, reconciliation time, failures), bound
// to the same registry so operator-internal and Apus-domain metrics scrape together.
library("josdk.micrometer", "io.javaoperatorsdk", "micrometer-support").versionRef("josdk")
```

In `operator/build.gradle.kts` under `dependencies`:

```kotlin
implementation(libs.micrometer.core)
implementation(libs.micrometer.registry.prometheus)
implementation(libs.josdk.micrometer)
```

- [ ] **Step 2: Write a failing test for the metrics**

```java
package net.onelitefeather.apus.operator.metrics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class ApusMetricsTest {

    @Test
    void countsRendersPerTenantAndPhase() {
        PrometheusMeterRegistry registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        ApusMetrics metrics = new ApusMetrics(registry);

        metrics.recordRenderPhase("friends-server", "Succeeded");
        metrics.recordRenderPhase("friends-server", "Succeeded");
        metrics.recordRenderPhase("friends-server", "Failed");

        // Micrometer name is "apus_renders"; the Prometheus registry appends "_total" for
        // counters, which is why the scraped name is apus_renders_total. Naming the meter
        // apus_renders_total here would scrape as apus_renders_total_total.
        assertEquals(
                2.0,
                registry.counter("apus_renders", "tenant", "friends-server", "phase", "Succeeded")
                        .count());
        assertEquals(
                1.0,
                registry.counter("apus_renders", "tenant", "friends-server", "phase", "Failed")
                        .count());
    }

    @Test
    void recordsIngestDurationAsAHistogram() {
        PrometheusMeterRegistry registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        ApusMetrics metrics = new ApusMetrics(registry);

        metrics.recordIngestDuration("friends-server", Duration.ofSeconds(42));

        assertEquals(1L, registry.timer("apus_ingest_duration", "tenant", "friends-server").count());
        // The platform dashboard shows a 95th percentile, which needs buckets -- a plain
        // timer scrapes count and sum only and would leave that panel empty.
        assertTrue(metrics.scrape().contains("apus_ingest_duration_seconds_bucket"), metrics.scrape());
    }

    @Test
    void exposesStorageUsedAsAGauge() {
        PrometheusMeterRegistry registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        ApusMetrics metrics = new ApusMetrics(registry);

        metrics.recordStorageUsed("friends-server", 228730548224L);
        metrics.recordStorageUsed("friends-server", 300000000000L);

        // A gauge, not a counter: the value goes down when a tenant deletes a map.
        assertEquals(
                300000000000.0,
                registry.get("apus_storage_used_bytes").tag("tenant", "friends-server").gauge().value());
    }

    @Test
    void scrapeRendersPrometheusText() {
        PrometheusMeterRegistry registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        ApusMetrics metrics = new ApusMetrics(registry);
        metrics.recordRenderPhase("friends-server", "Succeeded");

        String body = metrics.scrape();

        assertTrue(body.contains("apus_renders_total"), body);
        assertTrue(body.contains("tenant=\"friends-server\""), body);
    }
}
```

- [ ] **Step 3: Run the test and confirm the failure**

Run: `./gradlew :operator:test --tests '*ApusMetricsTest*'`
Expected: FAIL, `ApusMetrics` does not exist.

- [ ] **Step 4: Implement `ApusMetrics`**

```java
package net.onelitefeather.apus.operator.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The Apus-domain metrics from design spec §13.1.
 *
 * <p>Tenant is a label rather than part of the metric name: the platform dashboard needs to
 * sum across tenants, which a name-per-tenant scheme makes impossible. The number of tenants
 * is small and known (design spec §1.3), so the cardinality this adds is bounded.
 */
public final class ApusMetrics {

    private final MeterRegistry registry;
    private final Map<String, AtomicLong> storageUsed = new ConcurrentHashMap<>();

    public ApusMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public void recordRenderPhase(String tenant, String phase) {
        // No "_total" suffix here: the Prometheus registry adds it for counters, so the
        // scraped name becomes apus_renders_total.
        Counter.builder("apus_renders")
                .description("Renders that reached a given phase")
                .tag("tenant", tenant)
                .tag("phase", phase)
                .register(registry)
                .increment();
    }

    public void recordIngestDuration(String tenant, Duration duration) {
        Timer.builder("apus_ingest_duration")
                .description("Wall-clock time an ingest job took")
                // Buckets, not just count and sum: the platform dashboard renders a 95th
                // percentile, which histogram_quantile cannot compute without them.
                .publishPercentileHistogram()
                .tag("tenant", tenant)
                .register(registry)
                .record(duration);
    }

    public void recordStorageUsed(String tenant, long bytes) {
        storageUsed
                .computeIfAbsent(tenant, t -> {
                    AtomicLong holder = new AtomicLong();
                    io.micrometer.core.instrument.Gauge.builder("apus_storage_used_bytes", holder, AtomicLong::get)
                            .description("Bytes a tenant currently occupies, as reported by RGW")
                            .tag("tenant", t)
                            .register(registry);
                    return holder;
                })
                .set(bytes);
    }

    public String scrape() {
        if (registry instanceof PrometheusMeterRegistry prometheus) {
            return prometheus.scrape();
        }
        throw new IllegalStateException(
                "scrape() needs a PrometheusMeterRegistry; got " + registry.getClass().getName());
    }
}
```

- [ ] **Step 5: Test passes**

Run: `./gradlew :operator:test --tests '*ApusMetricsTest*'`
Expected: PASS

- [ ] **Step 6: Write a failing test for the metrics server**

```java
package net.onelitefeather.apus.operator.metrics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;

class MetricsServerTest {

    @Test
    void servesTheScrapeBodyOnSlashMetrics() throws Exception {
        try (MetricsServer server = new MetricsServer(0, () -> "apus_renders_total 1.0\n")) {
            server.start();

            HttpResponse<String> response = HttpClient.newHttpClient()
                    .send(
                            HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + "/metrics"))
                                    .build(),
                            HttpResponse.BodyHandlers.ofString());

            assertEquals(200, response.statusCode());
            assertTrue(response.body().contains("apus_renders_total"), response.body());
        }
    }

    @Test
    void answers404ForEverythingElse() throws Exception {
        try (MetricsServer server = new MetricsServer(0, () -> "")) {
            server.start();

            HttpResponse<String> response = HttpClient.newHttpClient()
                    .send(
                            HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + "/"))
                                    .build(),
                            HttpResponse.BodyHandlers.ofString());

            assertEquals(404, response.statusCode());
        }
    }
}
```

- [ ] **Step 7: Implement `MetricsServer`**

Following the model of `telemetry-addon/src/main/java/net/onelitefeather/apus/telemetry/TelemetryServer.java`: JDK `HttpServer`, an `HttpHandler` on `/metrics`, `Executors.newVirtualThreadPerTaskExecutor()`, a `port()` method that returns the port actually bound (needed because the test binds to port 0).

- [ ] **Step 8: Test passes**

Run: `./gradlew :operator:test --tests '*MetricsServerTest*'`
Expected: PASS

- [ ] **Step 9: Wire it into `ApusOperator`**

In the start path, create a `PrometheusMeterRegistry`, hand it to `ApusMetrics` and to JOSDK's `MicrometerMetrics` (`Operator` configuration: `.withMetrics(MicrometerMetrics.newPerResourceCollectingMicrometerMetricsBuilder(registry).build())`), start `MetricsServer` on port 8080 and close it on shutdown. Pass `ApusMetrics` through to the reconcilers, which report the three events.

- [ ] **Step 10: Keep the whole operator test run green**

Run: `./gradlew :operator:test`
Expected: BUILD SUCCESSFUL

- [ ] **Step 11: Commit**

```bash
git add settings.gradle.kts operator/
git commit -m "feat: export operator metrics for renders, ingests and tenant storage"
```

---

### Task 5: API metrics

**Files:**

- Modify: `settings.gradle.kts`
- Modify: `api/build.gradle.kts`
- Modify: `api/src/main/resources/application.yml`
- Create: `api/src/test/java/net/onelitefeather/apus/api/MetricsEndpointTest.java`

- [ ] **Step 1: Catalog and dependencies**

```kotlin
// Micronaut Micrometer, per the OneLiteFeather observability baseline. Version taken from
// io.micronaut.platform:micronaut-platform:5.1.0, the same BOM the existing micronaut
// entries were cross-checked against.
version("micronaut-micrometer", "5.11.0")
library("micronaut.micrometer.bom", "io.micronaut.micrometer", "micronaut-micrometer-bom")
    .versionRef("micronaut-micrometer")
library("micronaut.micrometer.core", "io.micronaut.micrometer", "micronaut-micrometer-core")
    .withoutVersion()
library("micronaut.micrometer.registry.prometheus", "io.micronaut.micrometer", "micronaut-micrometer-registry-prometheus")
    .withoutVersion()
library("micronaut.management", "io.micronaut", "micronaut-management").withoutVersion()
```

In `api/build.gradle.kts`:

```kotlin
implementation(platform(libs.micronaut.micrometer.bom))
implementation(libs.micronaut.micrometer.core)
implementation(libs.micronaut.micrometer.registry.prometheus)
implementation(libs.micronaut.management)
```

- [ ] **Step 2: Write a failing test**

```java
package net.onelitefeather.apus.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.http.client.exceptions.HttpClientResponseException;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

@MicronautTest
class MetricsEndpointTest {

    @Inject
    @Client("/")
    HttpClient client;

    @Test
    void metricsRequireAuthentication() {
        // The endpoint stays sensitive, per the OneLiteFeather security baseline: it is
        // scraped by a PodMonitor inside the cluster, not exposed to the internet.
        HttpClientResponseException thrown = org.junit.jupiter.api.Assertions.assertThrows(
                HttpClientResponseException.class,
                () -> client.toBlocking().exchange(HttpRequest.GET("/prometheus")));

        assertEquals(HttpStatus.UNAUTHORIZED, thrown.getStatus());
    }

    @Test
    void metricsExposeHttpServerRequests() {
        String body = client.toBlocking()
                .retrieve(HttpRequest.GET("/prometheus").basicAuth("metrics", "metrics"));

        assertTrue(body.contains("http_server_requests"), body);
    }
}
```

- [ ] **Step 3: Run the test and confirm the failure**

Run: `./gradlew :api:test --tests '*MetricsEndpointTest*'`
Expected: FAIL — the endpoint does not exist (404 instead of 401).

- [ ] **Step 4: Extend `application.yml`**

```yaml
endpoints:
  metrics:
    enabled: true
    sensitive: true
  prometheus:
    enabled: true
    sensitive: true
  health:
    enabled: true
    sensitive: false
    details-visible: ANONYMOUS

micronaut:
  metrics:
    enabled: true
    binders:
      jvm.enabled: true
      web.enabled: true
      uptime.enabled: true
```

`health` deliberately stays unauthenticated — kubelet probes carry no token. The details are unproblematic there, because the endpoint is only reachable from inside the cluster (no ingress path onto it).

- [ ] **Step 5: Tests pass**

Run: `./gradlew :api:test`
Expected: BUILD SUCCESSFUL

- [ ] **Step 6: Commit**

```bash
git add settings.gradle.kts api/
git commit -m "feat: expose Prometheus metrics and health endpoints from the API"
```

---

### Task 6: Scrape configuration

**Files:**

- Create: `deploy/base/podmonitor-render.yaml`
- Create: `deploy/base/servicemonitor-operator.yaml`
- Create: `deploy/base/servicemonitor-api.yaml`
- Create: `deploy/base/operator-service.yaml`
- Modify: `deploy/base/kustomization.yaml`

- [ ] **Step 1: Check the label under which the operator marks its render pods**

Run: `grep -rn 'class Labels' -A 30 operator/src/main/java/net/onelitefeather/apus/operator/api/Labels.java`
Expected: the constants for the pod labels. The `PodMonitor` has to select on exactly these — guessing produces a monitor that never finds anything and throws no error while doing so.

- [ ] **Step 2: `PodMonitor` for render pods**

```yaml
apiVersion: monitoring.coreos.com/v1
kind: PodMonitor
metadata:
  name: apus-render
  namespace: apus-system
  labels:
    app.kubernetes.io/part-of: apus
spec:
  # Render pods live in the tenant namespaces, not in apus-system.
  namespaceSelector:
    any: true
  selector:
    matchLabels:
      <the labels from Step 1>
  podMetricsEndpoints:
    - port: telemetry
      path: /metrics
      interval: 15s
```

For this to take effect, the render job has to name its port. Check:

Run: `grep -n 'containerPort\|withName' operator/src/main/java/net/onelitefeather/apus/operator/render/RenderJobBuilder.java`
Expected: a named port `telemetry` on 8099. If the name is missing, add it within the same task and extend the associated `RenderJobBuilderTest`.

- [ ] **Step 3: Service and `ServiceMonitor` for operator and API**

`operator-service.yaml`: ClusterIP Service on port 8080, name `metrics`, selector `app.kubernetes.io/name: apus-operator`.

Both `ServiceMonitor`s select on the same labels; the one for the API scrapes path `/prometheus` and needs the basic-auth or token reference that protects the endpoint (`basicAuth` referencing a secret that the overlay in the cluster repository supplies).

- [ ] **Step 4: Validate**

Run: `kustomize build deploy/base | kubectl apply --dry-run=client -f - 2>&1 | tail -5`
Expected: no errors. `PodMonitor`/`ServiceMonitor` require the Prometheus Operator's CRDs; if those are not present locally, `--dry-run=client` does **not** fail (it only checks structure) — for a real check, use `--dry-run=server` against a cluster that has kube-prometheus-stack.

- [ ] **Step 5: Commit**

```bash
git add deploy/base
git commit -m "feat: add scrape configuration for render pods, operator and API"
```

---

### Task 7: Grafana dashboards

Design spec §13.1: "one Grafana dashboard per level (platform, tenant)".

**Files:**

- Create: `deploy/dashboards/apus-platform.json`
- Create: `deploy/dashboards/apus-tenant.json`
- Create: `deploy/base/dashboards-configmap.yaml`
- Modify: `deploy/base/kustomization.yaml`

- [ ] **Step 1: Compile the available metric names**

From Task 4 and 5, plus the existing `telemetry-addon`:

Run: `grep -rn 'apus_' telemetry-addon/src/main/java/net/onelitefeather/apus/telemetry/PrometheusWriter.java operator/src/main/java/net/onelitefeather/apus/operator/metrics/ApusMetrics.java`
Expected: the complete list. Every panel may use only these names — a dashboard with invented metrics looks correct and stays permanently empty.

- [ ] **Step 2: Build the platform dashboard**

`deploy/dashboards/apus-platform.json`, panels:

1. **Renders by phase** (time series): `sum by (phase) (rate(apus_renders_total[5m]))`
2. **Error rate** (stat): `sum(rate(apus_renders_total{phase="Failed"}[1h])) / sum(rate(apus_renders_total[1h]))`
3. **Storage used per tenant** (bar): `apus_storage_used_bytes`
4. **Ingest duration, 95th percentile** (time series): `histogram_quantile(0.95, sum by (le, tenant) (rate(apus_ingest_duration_seconds_bucket[30m])))`
5. **Operator reconciliation errors** (time series, from JOSDK's Micrometer support): `sum by (name) (rate(operator_sdk_reconciliations_failed_total[5m]))`
6. **API latency** (time series): `histogram_quantile(0.95, sum by (le, uri) (rate(http_server_requests_seconds_bucket[5m])))`

A `datasource` template variable of type `prometheus`; no hardcoded datasource UID, or the dashboard cannot be imported into a second cluster.

- [ ] **Step 3: Build the tenant dashboard**

`deploy/dashboards/apus-tenant.json` with the same datasource variable plus a `tenant` variable (`label_values(apus_storage_used_bytes, tenant)`). Panels: running renders with progress (`apus_render_progress_ratio` and `apus_render_eta_seconds` — the names `PrometheusWriter` in `telemetry-addon` actually writes), latest ingest duration, storage used against quota, render history by phase — all filtered on `{tenant="$tenant"}`.

The render metrics, however, carry **no** `tenant` label: `telemetry-addon` runs in the render pod and only knows `map`. The tenant comes in through the pod labels the `PodMonitor` from Task 6 attaches — when building the panels, check which label that is (`grep` in `Labels.java`) and filter on it. Writing `{tenant="$tenant"}` on `apus_render_progress_ratio` instead yields a permanently empty panel.

- [ ] **Step 4: Validate the JSON**

Run: `for f in deploy/dashboards/*.json; do python3 -c "import json,sys;json.load(open('$f'));print('$f ok')"; done`
Expected: both `ok`.

- [ ] **Step 5: Cross-check every metric name used against Step 1**

Do not grep against the source code, but against an actual scrape — the meter names in the code and the scraped names differ (`apus_renders` in the code, `apus_renders_total` in the scrape; `apus_ingest_duration` in the code, `apus_ingest_duration_seconds*` in the scrape). Comparing against the source code would produce false alarms for exactly that reason.

```bash
# Use a scrape from a running instance as the reference:
kubectl -n apus-system port-forward svc/apus-operator 8080:8080 &
curl -s localhost:8080/metrics | grep -oE '^apus_[a-z_]+' | sort -u > /tmp/scraped.txt
grep -ohE 'apus_[a-z_]+' deploy/dashboards/*.json | sort -u > /tmp/used.txt
comm -23 /tmp/used.txt /tmp/scraped.txt
```

Expected: empty output. Any name that shows up here is exported by no instance — either a typo or a metric nobody writes yet. Either way it has to be resolved before committing, because a panel with the wrong name stays empty without ever showing an error.

Metrics from `telemetry-addon` (`apus_render_*`) do not appear in the operator scrape; run the same comparison against a render pod on port 8099 for those.

- [ ] **Step 6: ConfigMap for the Grafana sidecar discovery**

```yaml
apiVersion: v1
kind: ConfigMap
metadata:
  name: apus-dashboards
  namespace: apus-system
  labels:
    # The kube-prometheus-stack Grafana sidecar picks up ConfigMaps carrying this label.
    grafana_dashboard: "1"
```

The two JSON files are pulled in via `configMapGenerator` in `kustomization.yaml`, not copied into the ConfigMap by hand:

```yaml
configMapGenerator:
  - name: apus-dashboards
    namespace: apus-system
    files:
      - ../dashboards/apus-platform.json
      - ../dashboards/apus-tenant.json
    options:
      labels:
        grafana_dashboard: "1"
      disableNameSuffixHash: true
```

- [ ] **Step 7: Commit**

```bash
git add deploy/dashboards deploy/base
git commit -m "feat: add Grafana dashboards for the platform and tenant views"
```

---

### Task 8: End-to-end run on k3s

Design spec §13.2 calls for: "k3s + S3: full run through Ingest → Render → Hosting with a mini world." `PushIngestEndToEndTest` (ingest alone) and `RenderEndToEndTest` (render alone) exist — the run across all three stages is missing, and hosting is in no E2E test at all.

**Files:**

- Create: `operator/src/test/java/net/onelitefeather/apus/operator/FullPipelineIntegrationTest.java`
- Modify: `operator/build.gradle.kts` (only if the `integrationTest` task needs adjusting)

**Interfaces:**

- Consumes: the existing k3s Testcontainers infrastructure of the existing `*IntegrationTest` classes, plus `testdata/mini-world`.

- [ ] **Step 1: Look at the existing integration-test infrastructure**

Run: `ls operator/src/test/java/net/onelitefeather/apus/operator/*IntegrationTest.java && grep -n 'K3sContainer\|MinIOContainer\|LocallyRunOperatorExtension' operator/src/test/java/net/onelitefeather/apus/operator/OperatorIntegrationTest.java | head`
Expected: the existing pattern for k3s and MinIO containers. The new test adopts it unchanged, rather than inventing a second variant.

- [ ] **Step 2: Write a failing test**

The test drives, in one method:

1. Start k3s, apply the six CRDs from `deploy/crds`, run the operator against this cluster via `LocallyRunOperatorExtension`.
2. Start MinIO, place `testdata/mini-world` as a push source in the staging prefix.
3. Create `Tenant`, wait for `status.namespace`.
4. Create `WorldSource` (type `push`) and `WorldIngest`, wait until `status.phase == "Succeeded"` and `status.bundle.path` is set.
5. Create `BlueMapMap`, wait until the generated `BlueMapRender` reaches `Succeeded`.
6. Create `BlueMapHosting`, wait until `status.ready == true` and `status.url` is set.
7. Check that the map bucket actually contains tiles (`settings.json` and at least one `.png`/`.prbm` beneath the map prefix).

Generous timeouts (rendering the mini world: up to 10 minutes), every wait stage with its own descriptive failure message, so a failure shows *which* stage got stuck.

- [ ] **Step 3: Run the test and confirm the failure**

Run: `./gradlew :operator:integrationTest --tests '*FullPipelineIntegrationTest*'`
Expected: FAIL. The failure has to come from one of the wait stages, not from a compile error.

- [ ] **Step 4: Get the test passing**

What needs doing here depends on the failure. Expected stumbling blocks, each with where to fix it:

- The operator in the test does not know the image names → set the `OperatorConfig` environment variables in the test, the same way the Deployment from Task 2 does.
- Rook does not exist in the k3s test cluster → the test does not set `storage.bucketClaim` to `auto`, but creates a bucket and secret directly in MinIO and references them; the Rook integration is a separate scope and is already covered in `OperatorIntegrationTest`.
- The hosting pod needs an ingress controller → check against the `Service` in the test instead of the ingress URL; `status.ready` is the signal, not external reachability.

- [ ] **Step 5: Test passes, reproducibly**

Run: `./gradlew :operator:integrationTest --tests '*FullPipelineIntegrationTest*'` (twice in a row)
Expected: PASS both times. An E2E test that is only green on the first run has leftover state and is not done.

- [ ] **Step 6: Make sure it does not end up in the PR build**

Run: `./gradlew :operator:test --tests '*FullPipeline*' 2>&1 | grep -c 'No tests found'`
Expected: `1` — the test matches the `*IntegrationTest` naming convention and is thereby excluded from `test`.

- [ ] **Step 7: Commit**

```bash
git add operator/src/test/java/net/onelitefeather/apus/operator/FullPipelineIntegrationTest.java
git commit -m "test: cover the full ingest, render and hosting pipeline on k3s"
```

---

### Task 9: Update the design spec

**Files:**

- Modify: `docs/superpowers/specs/2026-08-08-apus-design.md`

- [ ] **Step 1: Mark §13.1 as implemented**

The section describes metrics, logs and dashboards in future tense. Rewrite it to the actual state, with the real file names (`deploy/base/servicemonitor-*.yaml`, `deploy/dashboards/*.json`) and the metric names that are actually exported.

- [ ] **Step 2: Point §13.2, the "E2E" line, at the new test**

<!-- markdownlint-disable-next-line MD038 -->
Replace with: `k3s + S3: full run through Ingest → Render → Hosting with a mini world (`FullPipelineIntegrationTest`, part of `./gradlew :operator:integrationTest`)`.

- [ ] **Step 3: Add the deployment state to §0**

```markdown
**Rollable out since Phase 8.** `deploy/base` is a complete Kustomize base
(CRDs, operator, API, UI, RBAC, scrape configuration); cluster-specific values come
from an overlay in the cluster repository. Operator and API export metrics, two
Grafana dashboards live under `deploy/dashboards`. What remains open are the
substantive hardening items from §15 — see the phase 9 plan.
```

- [ ] **Step 4: Lint and commit**

Run: `npx markdownlint-cli2 docs/superpowers/specs/2026-08-08-apus-design.md`
Expected: no errors.

```bash
git add docs/superpowers/specs/2026-08-08-apus-design.md
git commit -m "docs: record the phase 8 deployment and observability state"
```

---

## What this plan deliberately does not cover

- **The Flux overlay itself.** It belongs in the cluster repository (`Kubernetes-FLUX`), not here: registry hostnames, Rook names, domains and secret references are cluster properties, not project properties. `deploy/base` is cut so that an overlay can patch exactly these values.
- **Alerting rules.** Worthwhile, but they first need operational experience with the new metrics — thresholds without a data basis just produce noise.
- **The hardening items from §15** (identity broker, RBAC narrowing, quota signal, Paper save window, `emptyDir` limit) — a separate plan (Phase 9).
