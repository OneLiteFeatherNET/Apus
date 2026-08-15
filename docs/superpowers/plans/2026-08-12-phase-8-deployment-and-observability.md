# Apus Phase 8 — Deployment and Observability: Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Apus can be rolled out into a cluster via GitOps, and whoever operates it can see on the dashboard what the system is currently doing — instead of piecing it together from `kubectl` output.

**Architecture:** Apus is rolled out via two Helm charts under `deploy/charts/`
(`apus-operator`, `apus-platform`) instead of a Kustomize base — see
`docs/superpowers/plans/2026-08-13-helm-charts.md` and design spec §9. The six
CRD YAMLs are checked in instead of only generated, so that a rollout does not
require a Gradle run; a test keeps the checked-in version in sync with the generator.
Metrics follow the pattern already established in the repository: the operator
exposes them like `telemetry-addon` does, via the JDK's own `HttpServer`, and the
API via Micronaut's Micrometer integration.

**Tech Stack:** Helm, Prometheus Operator (`PodMonitor`/`ServiceMonitor` from the kube-prometheus-stack already present in the cluster), Micrometer 1.15, JOSDK 5.5.1, Grafana, k3s via Testcontainers.

## Global Constraints

- **Prerequisite: Phase 7 is complete.** The manifests reference the images built there (`apus/operator`, `apus/api`, `apus/ui`); without them this plan cannot be rolled out.
- **Java toolchain 25**, base packages as before (`net.onelitefeather.apus.operator`, `...apus.api`).
- **AGPL license header** on every new Java file; Spotless enforces it.
- **New dependencies go into the inline version catalog** in `settings.gradle.kts` — this repository deliberately does not use a `libs.versions.toml`. Every new version gets a comment there stating what it was checked against, the way the existing entries do.
- **The operator operates strictly namespace-local** (design spec §10.1). This plan's RBAC rules must not loosen that in any way.
- **Credentials never appear in metrics, labels, or dashboards** (design spec §12).
- **Integration tests stay excluded from the PR build** — the k3s test in Task 8 follows the existing `*IntegrationTest` convention.

---

### Task 1: Check in the CRD YAMLs and keep them in sync

Today `./gradlew :operator:generateCrds` generates the six CRDs into `operator/build/crds`. Whoever rolls out Apus needs them before the first operator start, though — and a cluster repository should not have to run Gradle for that.

**Extension note:** The generator writes its output with the `.yml` extension, not
`.yaml`. `deploy/crds/` and the `deploy/charts/apus-operator/files/crds/` derived from it
both consistently use `.yaml` — this plan makes `deploy/crds/` consistent with that by
having the copy task in Step 4 rename files during the copy, rather than adopting the
generator's extension. Reason for this choice instead of the reverse (switching everything
to `.yml`): `deploy/crds/` already exists in the repository with six `.yaml` files (checked
in when the Helm charts were built), and every glob rule that builds on it — in the chart
template, in `sync-crds.sh`, in the docs — expects `.yaml`. Switching to `.yml` would mean
changing all of that after the fact, without gaining anything for it.

**Files:**

- Create: `deploy/crds/*.yaml` (six files, generator output)
- Create: `operator/src/test/java/net/onelitefeather/apus/operator/CrdsInSyncTest.java`
- Modify: `operator/build.gradle.kts` (generator's output directory additionally points to `deploy/crds`)

**Interfaces:**

- Consumes: `generateCrds` (JavaExec task, `operator/build.gradle.kts:62`), which writes to `build/crds`.
- Produces: `deploy/crds/` as the checked-in source for Task 2.

- [ ] **Step 1: Generate the CRDs and note the names**

Run: `./gradlew :operator:generateCrds && ls operator/build/crds/`
Expected: six files with the `.yml` extension (not `.yaml` — that is the generator's actual
output, independent of this plan). Note down the exact file names — they are
needed in Step 3.

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
            // The generator writes .yml; syncCrds (below) renames to .yaml on the way into
            // deploy/crds, matching what the chart's files/crds already uses. Matching by
            // extension-less basename lets the two directories carry different extensions
            // without the drift check missing a renamed-but-changed file.
            return files.filter(p -> p.toString().endsWith(".yaml") || p.toString().endsWith(".yml"))
                    .collect(Collectors.toMap(
                            p -> p.getFileName().toString().replaceFirst("\\.ya?ml$", ""),
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

- [ ] **Step 4: Have the generator additionally write to `deploy/crds`**

In `operator/build.gradle.kts`, after the `generateCrds` registration:

```kotlin
val syncCrds by tasks.registering(Copy::class) {
    description = "Copies the generated CRDs to deploy/crds, renaming .yml to .yaml -- " +
            "the generator's own extension, but not what deploy/crds and the chart use."
    group = "build"
    from(generateCrds)
    into(rootProject.layout.projectDirectory.dir("deploy/crds"))
    rename { fileName -> fileName.replace(Regex("\\.yml$"), ".yaml") }
}
```

- [ ] **Step 5: Generate the CRDs and check them in**

Run: `./gradlew :operator:syncCrds && ls deploy/crds/`
Expected: the same six file names as in Step 1, now with the `.yaml` extension.

- [ ] **Step 6: Test passes**

Run: `./gradlew :operator:test --tests '*CrdsInSyncTest*'`
Expected: PASS

- [ ] **Step 7: Counter-check that the test actually detects drift**

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

### Task 2 and 3: replaced by the Helm charts

Originally: Kustomize base for the operator (Task 2), API and UI (Task 3). Both of these
tasks are superseded. Instead of a Kustomize base under `deploy/base`, Apus
rolls out via two Helm charts under `deploy/charts/` — `apus-operator` (the six CRDs
as templates, the operator, cluster-wide RBAC) and `apus-platform` (API, UI, ingress).
Implementation and design are documented in `docs/superpowers/plans/2026-08-13-helm-charts.md` and
`docs/superpowers/specs/2026-08-13-helm-charts-design.md`; both charts are complete
and checked into this repository under `deploy/charts/apus-operator` and `deploy/charts/apus-platform`.
The values these two tasks originally envisioned as manifest content —
`OperatorConfig` environment variables, RBAC rules, health probes, ingress paths — show
up 1:1 in the two charts' `values.yaml`/`values.schema.json`; this plan does not
repeat them.

---

### Task 4: Operator metrics

Design spec §13.1 requires "renders by phase, ingest duration, quota utilization per tenant". None of that exists yet.

**Files:**

- Modify: `settings.gradle.kts` (Micrometer im Katalog)
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

- Consumes: `JOSDK 5.5.1`'s `Metrics` interface for the reconciliation metrics.

- [ ] **Step 1: Add catalog entries**

In `settings.gradle.kts`, in the `versionCatalogs` block:

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

In `operator/build.gradle.kts`, under `dependencies`:

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

- [ ] **Step 6: Failing test for the metrics server**

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

Following the model of `telemetry-addon/src/main/java/net/onelitefeather/apus/telemetry/TelemetryServer.java`: JDK `HttpServer`, an `HttpHandler` on `/metrics`, `Executors.newVirtualThreadPerTaskExecutor()`, a `port()` method that returns the effectively bound port (needed because the test binds to port 0).

- [ ] **Step 8: Test passes**

Run: `./gradlew :operator:test --tests '*MetricsServerTest*'`
Expected: PASS

- [ ] **Step 9: Wire it into `ApusOperator`**

In the startup path, create a `PrometheusMeterRegistry`, pass it to `ApusMetrics` and to JOSDK's `MicrometerMetrics` (`Operator` configuration: `.withMetrics(MicrometerMetrics.newPerResourceCollectingMicrometerMetricsBuilder(registry).build())`), start `MetricsServer` on port 8080 and close it on shutdown. Pass `ApusMetrics` through to the reconcilers that report the three events.

- [ ] **Step 10: Keep the full operator test run green**

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

`health` deliberately stays unauthenticated — kubelet probes carry no token. Details are harmless there because the endpoint is only reachable within the cluster (no ingress path onto it).

- [ ] **Step 5: Tests pass**

Run: `./gradlew :api:test`
Expected: BUILD SUCCESSFUL

- [ ] **Step 6: Commit**

```bash
git add settings.gradle.kts api/
git commit -m "feat: expose Prometheus metrics and health endpoints from the API"
```

---

### Task 6: Scrape configuration for render pods

The two `ServiceMonitor`s for the operator and API from the original version of this
task are already chart templates
(`deploy/charts/apus-operator/templates/servicemonitor.yaml`,
`deploy/charts/apus-platform/templates/api-servicemonitor.yaml`), together with the
corresponding `Service`s (`deploy/charts/apus-operator/templates/service.yaml`,
`deploy/charts/apus-platform/templates/api-service.yaml`). Both `ServiceMonitor`s are
off by default (`metrics.serviceMonitor.enabled: false` and
`api.metrics.serviceMonitor.enabled: false` respectively) until Task 4 and Task 5 of this plan
actually export the metrics — before that they would only scrape an empty endpoint.

What remains open is only the `PodMonitor` for the render pods created by the operator: it
selects pods in tenant namespaces that no chart knows about (design spec §9), and is
therefore not a chart template but a standalone manifest outside the charts.

**Files:**

- Create: `deploy/podmonitor-render.yaml`

- [ ] **Step 1: Check the label under which the operator marks its render pods**

Run: `grep -rn 'class Labels' -A 30 operator/src/main/java/net/onelitefeather/apus/operator/api/Labels.java`
Expected: the constants for the pod labels. The `PodMonitor` must select exactly on these — guessing produces a monitor that never finds anything, without ever raising an error.

- [ ] **Step 2: `PodMonitor` for render pods**

```yaml
apiVersion: monitoring.coreos.com/v1
kind: PodMonitor
metadata:
  name: apus-render
  # Must live in the same namespace as the apus-operator chart installation, so that a
  # Prometheus whose podMonitorNamespaceSelector includes this namespace finds it.
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

For this to work, the render job must name its port. Check:

Run: `grep -n 'containerPort\|withName' operator/src/main/java/net/onelitefeather/apus/operator/render/RenderJobBuilder.java`
Expected: a named port `telemetry` on 8099. If the name is missing, add it within the same task and extend the corresponding `RenderJobBuilderTest`.

- [ ] **Step 3: Validate**

Run: `kubectl apply --dry-run=client -f deploy/podmonitor-render.yaml`
Expected: no errors. The `PodMonitor` requires the Prometheus operator's CRDs; if those are
not present locally, `--dry-run=client` does **not** fail (it only checks structure) — for
the real check, use `--dry-run=server` against a cluster with kube-prometheus-stack.

- [ ] **Step 4: Commit**

```bash
git add deploy/podmonitor-render.yaml
git commit -m "feat: add scrape configuration for render pods"
```

---

### Task 7: Grafana dashboards

Design spec §13.1: "a Grafana dashboard per layer (platform, tenant)". The ConfigMap
that serves these dashboards for the Grafana sidecar's discovery moves, relative to the
original version of this task, into the `apus-platform` chart as an optional
`dashboards.enabled` resource, instead of being wired into the Kustomize base via
`kustomization.yaml` (design spec §9).

**Files:**

- Create: `deploy/charts/apus-platform/files/dashboards/apus-platform.json`
- Create: `deploy/charts/apus-platform/files/dashboards/apus-tenant.json`
- Create: `deploy/charts/apus-platform/templates/dashboards-configmap.yaml`
- Modify: `deploy/charts/apus-platform/values.yaml` (`dashboards.enabled`, `dashboards.labels`)
- Modify: `deploy/charts/apus-platform/values.schema.json`
- Modify: `deploy/charts/apus-platform/README.md` (values table)

- [ ] **Step 1: Assemble the available metric names**

From Task 4 and 5, plus the existing `telemetry-addon`:

Run: `grep -rn 'apus_' telemetry-addon/src/main/java/net/onelitefeather/apus/telemetry/PrometheusWriter.java operator/src/main/java/net/onelitefeather/apus/operator/metrics/ApusMetrics.java`
Expected: the complete list. Every panel may use only these names — a dashboard with made-up metrics looks correct and stays permanently empty.

- [ ] **Step 2: Build the platform dashboard**

`deploy/charts/apus-platform/files/dashboards/apus-platform.json`, panels:

1. **Renders by phase** (time series): `sum by (phase) (rate(apus_renders_total[5m]))`
2. **Error rate** (stat): `sum(rate(apus_renders_total{phase="Failed"}[1h])) / sum(rate(apus_renders_total[1h]))`
3. **Storage used per tenant** (bar chart): `apus_storage_used_bytes`
4. **Ingest duration, 95th percentile** (time series): `histogram_quantile(0.95, sum by (le, tenant) (rate(apus_ingest_duration_seconds_bucket[30m])))`
5. **Operator reconciliation errors** (time series, from JOSDK's Micrometer support): `sum by (name) (rate(operator_sdk_reconciliations_failed_total[5m]))`
6. **API latency** (time series): `histogram_quantile(0.95, sum by (le, uri) (rate(http_server_requests_seconds_bucket[5m])))`

As a template variable, `datasource` of type `prometheus`; no hardcoded datasource UID, otherwise the dashboard cannot be imported into any second cluster.

- [ ] **Step 3: Build the tenant dashboard**

`deploy/charts/apus-platform/files/dashboards/apus-tenant.json` with the same datasource variable plus a `tenant` variable (`label_values(apus_storage_used_bytes, tenant)`). Panels: running renders with progress (`apus_render_progress_ratio` and `apus_render_eta_seconds` — the names `PrometheusWriter` in `telemetry-addon` actually writes), last ingest duration, storage used against quota, render history by phase — all filtered with `{tenant="$tenant"}`.

The render metrics, however, carry **no** `tenant` label: `telemetry-addon` runs in the render pod and only knows `map`. The tenant comes in via the pod labels that the `PodMonitor` from Task 6 attaches — when building the panels, check which label that is (`grep` in `Labels.java`), and filter on that instead. Anyone who writes `{tenant="$tenant"}` on `apus_render_progress_ratio` instead gets a permanently empty panel.

- [ ] **Step 4: Validate the JSON**

Run: `for f in deploy/charts/apus-platform/files/dashboards/*.json; do python3 -c "import json,sys;json.load(open('$f'));print('$f ok')"; done`
Expected: both `ok`.

- [ ] **Step 5: Cross-check all metric names used against Step 1**

Don't grep against the source code — check against a real scrape instead. The meter names in the code and the scraped names differ (`apus_renders` in the code, `apus_renders_total` in the scrape; `apus_ingest_duration` in the code, `apus_ingest_duration_seconds*` in the scrape). Comparing against the source code would produce false alarms for exactly that reason.

```bash
# Take a scrape from a running instance as the reference. The service name comes from
# deploy/charts/apus-operator/templates/service.yaml -- <release-name>-apus-operator-metrics.
kubectl -n apus-system port-forward svc/apus-operator-metrics 8080:8080 &
curl -s localhost:8080/metrics | grep -oE '^apus_[a-z_]+' | sort -u > /tmp/scraped.txt
grep -ohE 'apus_[a-z_]+' deploy/charts/apus-platform/files/dashboards/*.json | sort -u > /tmp/used.txt
comm -23 /tmp/used.txt /tmp/scraped.txt
```

Expected: empty output. Every name that appears here is exported by no instance — either a typo or a metric that nobody writes yet. Both must be resolved before the commit, because a panel with the wrong name stays empty without ever showing an error.

Metrics from `telemetry-addon` (`apus_render_*`) do not appear in the operator scrape; for those, run the same comparison against a render pod on port 8099.

- [ ] **Step 6: ConfigMap as an optional chart template**

`deploy/charts/apus-platform/templates/dashboards-configmap.yaml` — following the same
pattern as `deploy/charts/apus-operator/templates/crds.yaml` (which reads the CRDs from
`files/crds/*.yaml`): a `.Files.Glob` over the JSON files shipped in the chart, no manual
copying.

```gotemplate
{{- if .Values.dashboards.enabled }}
apiVersion: v1
kind: ConfigMap
metadata:
  name: {{ include "apus-platform.fullname" . }}-dashboards
  namespace: {{ .Release.Namespace }}
  labels:
    {{- include "apus-platform.labels" . | nindent 4 }}
    # The kube-prometheus-stack Grafana sidecar picks up ConfigMaps carrying this label.
    grafana_dashboard: "1"
data:
  {{- range $path, $_ := .Files.Glob "files/dashboards/*.json" }}
  {{ base $path }}: |-
    {{- $.Files.Get $path | nindent 4 }}
  {{- end }}
{{- end }}
```

In `values.yaml`:

```yaml
dashboards:
  # The dashboards reference metric names that don't exist until Task 4 and Task 5 of the
  # phase 8 plan land in the operator and the API. Off by default for the same reason the
  # ServiceMonitors default to off -- enabling it earlier just leaves every panel empty.
  enabled: false
```

- [ ] **Step 7: Validate and commit**

Run: `helm template t deploy/charts/apus-platform --set auth.issuer=https://id.example.net --set dashboards.enabled=true | grep -c 'kind: ConfigMap'`
Expected: at least `1`.

```bash
git add deploy/charts/apus-platform
git commit -m "feat(helm): add Grafana dashboards to the apus-platform chart"
```

---

### Task 8: End-to-end run on k3s

Design spec §13.2 calls for: "k3s + S3: complete pass ingest → render → hosting with a mini world". `PushIngestEndToEndTest` (ingest alone) and `RenderEndToEndTest` (render alone) already exist — the pass through all three stages is missing, and hosting is not covered in any E2E test.

**Files:**

- Create: `operator/src/test/java/net/onelitefeather/apus/operator/FullPipelineIntegrationTest.java`
- Modify: `operator/build.gradle.kts` (only if the `integrationTest` task needs adjusting)

**Interfaces:**

- Consumes: the existing k3s Testcontainers infrastructure of the existing `*IntegrationTest` classes, plus `testdata/mini-world`; the two Helm charts under `deploy/charts/`.

- [ ] **Step 1: Look at the existing integration test infrastructure**

Run: `ls operator/src/test/java/net/onelitefeather/apus/operator/*IntegrationTest.java && grep -n 'K3sContainer\|MinIOContainer\|LocallyRunOperatorExtension' operator/src/test/java/net/onelitefeather/apus/operator/OperatorIntegrationTest.java | head`
Expected: the existing pattern for the k3s and MinIO containers. The new test adopts it unchanged, instead of inventing a second variant.

- [ ] **Step 2: Write a failing test**

The test runs, in a single method:

1. Start k3s, install the six CRDs via `helm install apus-operator deploy/charts/apus-operator --set bundles.s3Endpoint=<minio-endpoint>` (instead of applying individual CRD manifests — the chart installs them as templates, see `deploy/charts/apus-operator/templates/crds.yaml`), and additionally run the operator reconciler against the same cluster via `LocallyRunOperatorExtension`.
2. Start MinIO, place `testdata/mini-world` as a push source in the staging prefix.
3. Create a `Tenant`, wait for `status.namespace`.
4. Create a `WorldSource` (type `push`) and a `WorldIngest`, wait until `status.phase == "Succeeded"` and `status.bundle.path` is set.
5. Create a `BlueMapMap`, wait until the resulting `BlueMapRender` reaches `Succeeded`.
6. Create a `BlueMapHosting`, wait until `status.ready == true` and `status.url` is set.
7. Verify that tiles actually exist in the map bucket (`settings.json` and at least one `.png`/`.prbm` beneath the map prefix).

Generous timeouts (rendering the mini world: up to 10 minutes), each waiting stage with its own descriptive error message, so that a failure shows *which* stage got stuck.

- [ ] **Step 3: Run the test and confirm the failure**

Run: `./gradlew :operator:integrationTest --tests '*FullPipelineIntegrationTest*'`
Expected: FAIL. The failure must come from one of the waiting stages, not from a compile error.

- [ ] **Step 4: Get the test passing**

What needs doing here depends on the failure. Expected stumbling blocks, each with where to fix it:

- The operator in the test doesn't know the image names → set `OperatorConfig` environment variables in the test, the same way the `apus-operator` chart injects them into the deployment via its `images.*` values (`deploy/charts/apus-operator/templates/deployment.yaml`).
- Rook does not exist in the k3s test cluster → the test does not set `storage.bucketClaim` to `auto`, but instead creates the bucket and secret directly in MinIO and references them; the Rook integration is a separate scope and is already covered in `OperatorIntegrationTest`.
- The hosting pod needs an ingress controller → in the test, check against the `Service` instead of the ingress URL; `status.ready` is the signal, not external reachability.

- [ ] **Step 5: Test passes, reproducibly**

Run: `./gradlew :operator:integrationTest --tests '*FullPipelineIntegrationTest*'` (twice in a row)
Expected: PASS both times. An E2E test that is only green on the first run has leftover state and is not done.

- [ ] **Step 6: Verify `helm upgrade` with a changed CRD schema**

Reason for this step: it demonstrates the property for which the CRDs live in
`deploy/charts/apus-operator/templates/crds.yaml` instead of Helm's special `crds/`
directory (design spec §9, Task 2 report of the Helm charts) — the latter is installed by
Helm once and never touched again on `helm upgrade`, so a changed schema would stay stuck
in the cluster.

Against the same k3s cluster from Step 1:

1. `helm install apus-operator deploy/charts/apus-operator --set bundles.s3Endpoint=<minio-endpoint>` with the chart state *before* this change (the last Git tag or the last published chart archive from Harbor).
2. Locally change a field in the generated CRD schema (e.g. a new optional property on one of the `@Group`-annotated spec classes), then run `./gradlew :operator:syncCrds` and `deploy/charts/apus-operator/sync-crds.sh`.
3. `helm upgrade apus-operator deploy/charts/apus-operator --set bundles.s3Endpoint=<minio-endpoint>` with the changed chart state.
4. Check `kubectl get crd bluemapmaps.bluemap.onelitefeather.net -o jsonpath='{.spec.versions[0].schema.openAPIV3Schema.properties.spec.properties}'` against the new field.

Run: the above as a shell sequence or as a separate test case alongside `FullPipelineIntegrationTest`.
Expected: the new field appears in the cluster schema after the `helm upgrade`, without anyone having reapplied the CRD by hand. Afterward, discard the schema change again (`git checkout` on the affected spec class), so this step leaves no real CRD change behind.

- [ ] **Step 7: Make sure it does not end up in the PR build**

Run: `./gradlew :operator:test --tests '*FullPipeline*' 2>&1 | grep -c 'No tests found'`
Expected: `1` — the test matches the `*IntegrationTest` naming convention and is therefore excluded from `test`.

- [ ] **Step 8: Commit**

```bash
git add operator/src/test/java/net/onelitefeather/apus/operator/FullPipelineIntegrationTest.java
git commit -m "test: cover the full ingest, render and hosting pipeline on k3s"
```

---

### Task 9: Update the design spec

**Files:**

- Modify: `docs/superpowers/specs/2026-08-08-apus-design.md`

- [ ] **Step 1: Mark §13.1 as implemented**

The section describes metrics, logs, and dashboards in the future tense. Rewrite it to
reflect the actual state, with the real file names (`deploy/charts/apus-operator/templates/servicemonitor.yaml`,
`deploy/charts/apus-platform/templates/api-servicemonitor.yaml`,
`deploy/podmonitor-render.yaml`, `deploy/charts/apus-platform/templates/dashboards-configmap.yaml`)
and the metric names actually exported.

- [ ] **Step 2: Point §13.2, the "E2E" line, at the new test**

<!-- markdownlint-disable-next-line MD038 -->
Replace with: `k3s + S3: complete pass ingest → render → hosting with a mini world (`FullPipelineIntegrationTest`, part of `./gradlew :operator:integrationTest`)`.

- [ ] **Step 3: Extend §0 with the deployment state**

A paragraph for phase 8 §0 already exists from the Helm charts work (see its
Task 9); this step extends it with what this phase additionally delivers, rather than
replacing it:

```markdown
**Deployable since phase 8.** The two Helm charts under `deploy/charts/`
(`apus-operator`, `apus-platform`) install CRDs, operator, API, UI, RBAC, and
scrape configuration; cluster-specific values come in via `values:` from the
`HelmRelease` in the cluster repository. Operator and API export metrics, two
Grafana dashboards ship as an optional resource in the `apus-platform` chart
(`dashboards.enabled`). What remains open are the substantive hardenings from §15 — see
the phase 9 plan.
```

- [ ] **Step 4: Lint and commit**

Run: `npx markdownlint-cli2 docs/superpowers/specs/2026-08-08-apus-design.md`
Expected: no errors.

```bash
git add docs/superpowers/specs/2026-08-08-apus-design.md
git commit -m "docs: record the phase 8 deployment and observability state"
```

---

## What this plan does not cover

- **The Flux overlay itself** (`OCIRepository` plus `HelmRelease`). It belongs in the cluster repository (`Kubernetes-FLUX`), not here: registry hostnames, Rook names, domains, and secret references are cluster properties, not project properties. The two Helm charts under `deploy/charts/` are cut so that a `HelmRelease` can override exactly these values via `values:`; see design spec §9 and `docs/superpowers/plans/2026-08-13-helm-charts.md`.
- **Alerting rules.** Sensible, but they first need operational experience with the new metrics — thresholds without a data basis just produce noise.
- **The hardenings from §15** (identity broker, RBAC narrowing, quota signal, paper-save window, `emptyDir` limit) — separate plan (phase 9).
