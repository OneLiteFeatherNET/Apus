# Apus Phase 8 — Deployment und Observability: Implementierungsplan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Apus lässt sich per GitOps in einen Cluster ausrollen, und wer es betreibt, sieht am Dashboard, was das System gerade tut — statt es aus `kubectl`-Ausgaben zusammenzureimen.

**Architecture:** Apus wird über zwei Helm Charts unter `deploy/charts/` ausgerollt
(`apus-operator`, `apus-platform`) statt über eine Kustomize-Basis — siehe
`docs/superpowers/plans/2026-08-13-helm-charts.md` und Design-Spec §9. Die sechs
CRD-YAMLs werden eingecheckt statt nur generiert, damit ein Ausrollen keinen Gradle-Lauf
voraussetzt; ein Test hält die eingecheckte Fassung mit dem Generator synchron. Metriken
folgen dem im Repository bereits etablierten Muster: der Operator exponiert sie wie das
`telemetry-addon` über den JDK-eigenen `HttpServer`, die API über Micronauts
Micrometer-Integration.

**Tech Stack:** Helm, Prometheus Operator (`PodMonitor`/`ServiceMonitor` aus dem im Cluster vorhandenen kube-prometheus-stack), Micrometer 1.15, JOSDK 5.5.1, Grafana, k3s via Testcontainers.

## Global Constraints

- **Voraussetzung: Phase 7 ist abgeschlossen.** Die Manifeste referenzieren die dort gebauten Images (`apus/operator`, `apus/api`, `apus/ui`); ohne sie ist dieser Plan nicht ausrollbar.
- **Java-Toolchain 25**, Basispakete wie gehabt (`net.onelitefeather.apus.operator`, `...apus.api`).
- **AGPL-Lizenzheader** über jede neue Java-Datei; Spotless erzwingt ihn.
- **Neue Abhängigkeiten kommen in den Inline-Version-Catalog** in `settings.gradle.kts` — dieses Repository benutzt bewusst kein `libs.versions.toml`. Jede neue Version bekommt dort einen Kommentar, gegen was sie geprüft wurde, wie es die bestehenden Einträge tun.
- **Der Operator arbeitet strikt namespace-lokal** (Design-Spec §10.1). Die RBAC-Regeln dieses Plans dürfen daran nichts aufweichen.
- **Credentials erscheinen nie in Metriken, Labels oder Dashboards** (Design-Spec §12).
- **Integrationstests bleiben aus dem PR-Build ausgeschlossen** — der k3s-Test aus Task 8 folgt der bestehenden `*IntegrationTest`-Konvention.

---

### Task 1: CRD-YAMLs einchecken und synchron halten

Heute erzeugt `./gradlew :operator:generateCrds` die sechs CRDs nach `operator/build/crds`. Wer Apus ausrollt, braucht sie aber vor dem ersten Operator-Start — und ein Cluster-Repository soll dafür kein Gradle ausführen müssen.

**Extension-Hinweis:** Der Generator schreibt seine Ausgabe mit der Endung `.yml`, nicht
`.yaml`. `deploy/crds/` und die davon abgeleiteten `deploy/charts/apus-operator/files/crds/`
benutzen durchgängig `.yaml` — dieser Plan macht `deploy/crds/` konsistent dazu, indem der
Copy-Task in Schritt 4 beim Kopieren umbenennt, statt die Endung des Generators zu
übernehmen. Grund für diese Wahl statt umgekehrt (alles auf `.yml` umzustellen): `deploy/crds/`
existiert im Repository bereits mit sechs `.yaml`-Dateien (eingecheckt beim Bau der Helm
Charts), und jede Glob-Regel, die darauf aufsetzt — im Chart-Template, in `sync-crds.sh`,
in der Doku — erwartet `.yaml`. Auf `.yml` umzustellen hieße, all das nachträglich zu ändern,
ohne einen Vorteil dafür zu bekommen.

**Files:**

- Create: `deploy/crds/*.yaml` (sechs Dateien, Generator-Ausgabe)
- Create: `operator/src/test/java/net/onelitefeather/apus/operator/CrdsInSyncTest.java`
- Modify: `operator/build.gradle.kts` (Ausgabeverzeichnis des Generators zusätzlich nach `deploy/crds`)

**Interfaces:**

- Consumes: `generateCrds` (JavaExec-Task, `operator/build.gradle.kts:62`), der nach `build/crds` schreibt.
- Produces: `deploy/crds/` als eingecheckte Quelle für Task 2.

- [ ] **Schritt 1: CRDs erzeugen und Namen feststellen**

Run: `./gradlew :operator:generateCrds && ls operator/build/crds/`
Expected: sechs Dateien mit der Endung `.yml` (nicht `.yaml` — das ist die tatsächliche
Ausgabe des Generators, unabhängig von diesem Plan). Die exakten Dateinamen notieren — sie
werden in Schritt 3 gebraucht.

- [ ] **Schritt 2: Failing test schreiben**

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

- [ ] **Schritt 3: Test laufen lassen und Fehlschlag bestätigen**

Run: `./gradlew :operator:test --tests '*CrdsInSyncTest*'`
Expected: FAIL mit `../deploy/crds does not exist`.

- [ ] **Schritt 4: Generator zusätzlich nach `deploy/crds` schreiben lassen**

In `operator/build.gradle.kts` nach der `generateCrds`-Registrierung:

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

- [ ] **Schritt 5: CRDs erzeugen und einchecken**

Run: `./gradlew :operator:syncCrds && ls deploy/crds/`
Expected: dieselben sechs Dateinamen wie in Schritt 1, jetzt mit der Endung `.yaml`.

- [ ] **Schritt 6: Test läuft grün**

Run: `./gradlew :operator:test --tests '*CrdsInSyncTest*'`
Expected: PASS

- [ ] **Schritt 7: Gegenprobe, dass der Test Drift wirklich erkennt**

```bash
printf '\n# drift\n' >> deploy/crds/$(ls deploy/crds | head -1)
./gradlew :operator:test --tests '*CrdsInSyncTest*' || echo "erkannt"
git checkout deploy/crds
```

Expected: `erkannt` — ein Test, der Drift nicht bemerkt, ist wertlos.

- [ ] **Schritt 8: Commit**

```bash
git add deploy/crds operator/build.gradle.kts operator/src/test/java/net/onelitefeather/apus/operator/CrdsInSyncTest.java
git commit -m "feat: check in the generated CRDs and guard them against drift"
```

---

### Task 2 und 3: ersetzt durch die Helm Charts

Ursprünglich: Kustomize-Basis für Operator (Task 2), API und UI (Task 3). Diese beiden
Tasks sind überholt. Statt einer Kustomize-Basis unter `deploy/base` rollt
Apus über zwei Helm Charts unter `deploy/charts/` aus — `apus-operator` (die sechs CRDs
als Templates, der Operator, cluster-weite RBAC) und `apus-platform` (API, UI, Ingress).
Umsetzung und Design stehen in `docs/superpowers/plans/2026-08-13-helm-charts.md` und
`docs/superpowers/specs/2026-08-13-helm-charts-design.md`; beide Charts sind abgeschlossen
und in diesem Repository unter `deploy/charts/apus-operator` und `deploy/charts/apus-platform`
eingecheckt. Die Werte, die diese beiden Tasks ursprünglich als Manifest-Inhalt vorsahen —
`OperatorConfig`-Umgebungsvariablen, RBAC-Regeln, Health-Probes, Ingress-Pfade — finden sich
1:1 in den `values.yaml`/`values.schema.json` der beiden Charts wieder; dieser Plan
wiederholt sie nicht.

---

### Task 4: Operator-Metriken

Design-Spec §13.1 verlangt „Renders nach Phase, Ingest-Dauer, Quota-Auslastung je Mandant". Nichts davon existiert.

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

- Consumes: `JOSDK 5.5.1`s `Metrics`-Schnittstelle für die Reconciliation-Metriken.

- [ ] **Schritt 1: Katalogeinträge ergänzen**

In `settings.gradle.kts` im `versionCatalogs`-Block:

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

In `operator/build.gradle.kts` unter `dependencies`:

```kotlin
implementation(libs.micrometer.core)
implementation(libs.micrometer.registry.prometheus)
implementation(libs.josdk.micrometer)
```

- [ ] **Schritt 2: Failing test für die Metriken schreiben**

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

- [ ] **Schritt 3: Test laufen lassen und Fehlschlag bestätigen**

Run: `./gradlew :operator:test --tests '*ApusMetricsTest*'`
Expected: FAIL, `ApusMetrics` existiert nicht.

- [ ] **Schritt 4: `ApusMetrics` implementieren**

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

- [ ] **Schritt 5: Test läuft grün**

Run: `./gradlew :operator:test --tests '*ApusMetricsTest*'`
Expected: PASS

- [ ] **Schritt 6: Failing test für den Metrics-Server**

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

- [ ] **Schritt 7: `MetricsServer` implementieren**

Nach dem Vorbild von `telemetry-addon/src/main/java/net/onelitefeather/apus/telemetry/TelemetryServer.java`: JDK-`HttpServer`, ein `HttpHandler` auf `/metrics`, `Executors.newVirtualThreadPerTaskExecutor()`, `port()`-Methode, die den effektiv gebundenen Port zurückgibt (nötig, weil der Test mit Port 0 bindet).

- [ ] **Schritt 8: Test läuft grün**

Run: `./gradlew :operator:test --tests '*MetricsServerTest*'`
Expected: PASS

- [ ] **Schritt 9: In `ApusOperator` verdrahten**

Im Start-Pfad eine `PrometheusMeterRegistry` anlegen, an `ApusMetrics` und an JOSDKs `MicrometerMetrics` übergeben (`Operator`-Konfiguration: `.withMetrics(MicrometerMetrics.newPerResourceCollectingMicrometerMetricsBuilder(registry).build())`), `MetricsServer` auf Port 8080 starten und beim Herunterfahren schließen. `ApusMetrics` an die Reconciler durchreichen, die die drei Ereignisse melden.

- [ ] **Schritt 10: Gesamten Operator-Test-Lauf grün halten**

Run: `./gradlew :operator:test`
Expected: BUILD SUCCESSFUL

- [ ] **Schritt 11: Commit**

```bash
git add settings.gradle.kts operator/
git commit -m "feat: export operator metrics for renders, ingests and tenant storage"
```

---

### Task 5: API-Metriken

**Files:**

- Modify: `settings.gradle.kts`
- Modify: `api/build.gradle.kts`
- Modify: `api/src/main/resources/application.yml`
- Create: `api/src/test/java/net/onelitefeather/apus/api/MetricsEndpointTest.java`

- [ ] **Schritt 1: Katalog und Abhängigkeiten**

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

- [ ] **Schritt 2: Failing test schreiben**

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

- [ ] **Schritt 3: Test laufen lassen und Fehlschlag bestätigen**

Run: `./gradlew :api:test --tests '*MetricsEndpointTest*'`
Expected: FAIL — der Endpunkt existiert nicht (404 statt 401).

- [ ] **Schritt 4: `application.yml` ergänzen**

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

`health` bleibt bewusst unauthentifiziert — Kubelet-Probes tragen kein Token. Details sind dabei unbedenklich, weil der Endpunkt nur innerhalb des Clusters erreichbar ist (kein Ingress-Pfad darauf).

- [ ] **Schritt 5: Tests grün**

Run: `./gradlew :api:test`
Expected: BUILD SUCCESSFUL

- [ ] **Schritt 6: Commit**

```bash
git add settings.gradle.kts api/
git commit -m "feat: expose Prometheus metrics and health endpoints from the API"
```

---

### Task 6: Scrape-Konfiguration für Render-Pods

Die beiden `ServiceMonitor`s für Operator und API aus der ursprünglichen Fassung dieses
Tasks sind bereits Chart-Templates
(`deploy/charts/apus-operator/templates/servicemonitor.yaml`,
`deploy/charts/apus-platform/templates/api-servicemonitor.yaml`), zusammen mit den
zugehörigen `Service`s (`deploy/charts/apus-operator/templates/service.yaml`,
`deploy/charts/apus-platform/templates/api-service.yaml`). Beide `ServiceMonitor`s sind
standardmäßig aus (`metrics.serviceMonitor.enabled: false` bzw.
`api.metrics.serviceMonitor.enabled: false`), bis Task 4 bzw. Task 5 dieses Plans die
Metriken tatsächlich exportieren — vorher würden sie nur einen leeren Endpunkt scrapen.

Offen bleibt nur der `PodMonitor` für die vom Operator erzeugten Render-Pods: Er selektiert
Pods in Mandanten-Namespaces, die kein Chart kennt (Design-Spec §9), und ist deshalb kein
Chart-Template, sondern ein eigenständiges Manifest außerhalb der Charts.

**Files:**

- Create: `deploy/podmonitor-render.yaml`

- [ ] **Schritt 1: Label prüfen, unter dem der Operator seine Render-Pods markiert**

Run: `grep -rn 'class Labels' -A 30 operator/src/main/java/net/onelitefeather/apus/operator/api/Labels.java`
Expected: die Konstanten für die Pod-Labels. Der `PodMonitor` muss exakt darauf selektieren — geraten führt zu einem Monitor, der nie etwas findet und dabei keinen Fehler wirft.

- [ ] **Schritt 2: `PodMonitor` für Render-Pods**

```yaml
apiVersion: monitoring.coreos.com/v1
kind: PodMonitor
metadata:
  name: apus-render
  # Muss in demselben Namespace liegen wie die apus-operator-Chart-Installation, damit ein
  # Prometheus, dessen podMonitorNamespaceSelector diesen Namespace einschließt, ihn findet.
  namespace: apus-system
  labels:
    app.kubernetes.io/part-of: apus
spec:
  # Render pods live in the tenant namespaces, not in apus-system.
  namespaceSelector:
    any: true
  selector:
    matchLabels:
      <die Labels aus Schritt 1>
  podMetricsEndpoints:
    - port: telemetry
      path: /metrics
      interval: 15s
```

Damit das greift, muss der Render-Job seinen Port benennen. Prüfen:

Run: `grep -n 'containerPort\|withName' operator/src/main/java/net/onelitefeather/apus/operator/render/RenderJobBuilder.java`
Expected: ein benannter Port `telemetry` auf 8099. Fehlt der Name, im selben Task ergänzen und den zugehörigen `RenderJobBuilderTest` erweitern.

- [ ] **Schritt 3: Validieren**

Run: `kubectl apply --dry-run=client -f deploy/podmonitor-render.yaml`
Expected: keine Fehler. Der `PodMonitor` erfordert die CRDs des Prometheus-Operators; ist der
lokal nicht vorhanden, schlägt `--dry-run=client` **nicht** fehl (es prüft nur Struktur) — für
die echte Prüfung `--dry-run=server` gegen einen Cluster mit kube-prometheus-stack verwenden.

- [ ] **Schritt 4: Commit**

```bash
git add deploy/podmonitor-render.yaml
git commit -m "feat: add scrape configuration for render pods"
```

---

### Task 7: Grafana-Dashboards

Design-Spec §13.1: „ein Grafana-Dashboard je Ebene (Plattform, Mandant)". Die ConfigMap,
die diese Dashboards für die Grafana-Sidecar-Erkennung bereitstellt, wandert gegenüber der
ursprünglichen Fassung dieses Tasks als optionale `dashboards.enabled`-Ressource ins
`apus-platform`-Chart, statt über `kustomization.yaml` in die Kustomize-Basis eingebunden zu
werden (Design-Spec §9).

**Files:**

- Create: `deploy/charts/apus-platform/files/dashboards/apus-platform.json`
- Create: `deploy/charts/apus-platform/files/dashboards/apus-tenant.json`
- Create: `deploy/charts/apus-platform/templates/dashboards-configmap.yaml`
- Modify: `deploy/charts/apus-platform/values.yaml` (`dashboards.enabled`, `dashboards.labels`)
- Modify: `deploy/charts/apus-platform/values.schema.json`
- Modify: `deploy/charts/apus-platform/README.md` (Werte-Tabelle)

- [ ] **Schritt 1: Verfügbare Metriknamen zusammenstellen**

Aus Task 4 und 5 sowie dem bestehenden `telemetry-addon`:

Run: `grep -rn 'apus_' telemetry-addon/src/main/java/net/onelitefeather/apus/telemetry/PrometheusWriter.java operator/src/main/java/net/onelitefeather/apus/operator/metrics/ApusMetrics.java`
Expected: die vollständige Liste. Jedes Panel darf ausschließlich diese Namen verwenden — ein Dashboard mit erfundenen Metriken sieht korrekt aus und bleibt dauerhaft leer.

- [ ] **Schritt 2: Plattform-Dashboard bauen**

`deploy/charts/apus-platform/files/dashboards/apus-platform.json`, Panels:

1. **Renders nach Phase** (Zeitreihe): `sum by (phase) (rate(apus_renders_total[5m]))`
2. **Fehlerquote** (Stat): `sum(rate(apus_renders_total{phase="Failed"}[1h])) / sum(rate(apus_renders_total[1h]))`
3. **Speicherverbrauch je Mandant** (Balken): `apus_storage_used_bytes`
4. **Ingest-Dauer, 95. Perzentil** (Zeitreihe): `histogram_quantile(0.95, sum by (le, tenant) (rate(apus_ingest_duration_seconds_bucket[30m])))`
5. **Reconciliation-Fehler des Operators** (Zeitreihe, aus JOSDKs Micrometer-Support): `sum by (name) (rate(operator_sdk_reconciliations_failed_total[5m]))`
6. **API-Latenz** (Zeitreihe): `histogram_quantile(0.95, sum by (le, uri) (rate(http_server_requests_seconds_bucket[5m])))`

Als Template-Variable `datasource` vom Typ `prometheus`; keine fest verdrahtete Datenquellen-UID, sonst lässt sich das Dashboard in keinem zweiten Cluster importieren.

- [ ] **Schritt 3: Mandanten-Dashboard bauen**

`deploy/charts/apus-platform/files/dashboards/apus-tenant.json` mit derselben Datenquellen-Variable plus einer Variable `tenant` (`label_values(apus_storage_used_bytes, tenant)`). Panels: laufende Renders mit Fortschritt (`apus_render_progress_ratio` und `apus_render_eta_seconds` — die Namen, die `PrometheusWriter` im `telemetry-addon` tatsächlich schreibt), letzte Ingest-Dauer, Speicherverbrauch gegen Quota, Render-Historie nach Phase — alle mit `{tenant="$tenant"}` gefiltert.

Die Render-Metriken tragen allerdings **kein** `tenant`-Label: Das `telemetry-addon` läuft im Render-Pod und kennt nur `map`. Der Mandant kommt über die Pod-Labels herein, die der `PodMonitor` aus Task 6 anhängt — beim Bau der Panels ist zu prüfen, welches Label das ist (`grep` in `Labels.java`), und danach zu filtern. Wer stattdessen `{tenant="$tenant"}` auf `apus_render_progress_ratio` schreibt, bekommt ein dauerhaft leeres Panel.

- [ ] **Schritt 4: JSON validieren**

Run: `for f in deploy/charts/apus-platform/files/dashboards/*.json; do python3 -c "import json,sys;json.load(open('$f'));print('$f ok')"; done`
Expected: beide `ok`.

- [ ] **Schritt 5: Alle verwendeten Metriknamen gegen Schritt 1 gegenprüfen**

Nicht gegen den Quellcode greppen, sondern gegen einen echten Scrape — die Meter-Namen im Code und die gescrapten Namen unterscheiden sich (`apus_renders` im Code, `apus_renders_total` im Scrape; `apus_ingest_duration` im Code, `apus_ingest_duration_seconds*` im Scrape). Ein Abgleich gegen den Quellcode würde genau deshalb Fehlalarme produzieren.

```bash
# Scrape einer laufenden Instanz als Referenz nehmen. Der Service-Name kommt aus
# deploy/charts/apus-operator/templates/service.yaml -- <release-name>-apus-operator-metrics.
kubectl -n apus-system port-forward svc/apus-operator-metrics 8080:8080 &
curl -s localhost:8080/metrics | grep -oE '^apus_[a-z_]+' | sort -u > /tmp/scraped.txt
grep -ohE 'apus_[a-z_]+' deploy/charts/apus-platform/files/dashboards/*.json | sort -u > /tmp/used.txt
comm -23 /tmp/used.txt /tmp/scraped.txt
```

Expected: leere Ausgabe. Jeder Name, der hier erscheint, wird von keiner Instanz exportiert — entweder ein Tippfehler oder eine Metrik, die noch niemand schreibt. Beides muss vor dem Commit aufgelöst sein, denn ein Panel mit falschem Namen bleibt leer, ohne je einen Fehler zu zeigen.

Metriken aus dem `telemetry-addon` (`apus_render_*`) erscheinen nicht im Operator-Scrape; für sie ist derselbe Abgleich gegen einen Render-Pod auf Port 8099 zu fahren.

- [ ] **Schritt 6: ConfigMap als optionales Chart-Template**

`deploy/charts/apus-platform/templates/dashboards-configmap.yaml` — nach demselben Muster
wie `deploy/charts/apus-operator/templates/crds.yaml` (das die CRDs aus `files/crds/*.yaml`
liest): ein `.Files.Glob` über die im Chart mitgelieferten JSON-Dateien, kein Kopieren von
Hand.

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

- [ ] **Schritt 7: Validieren und Commit**

Run: `helm template t deploy/charts/apus-platform --set auth.issuer=https://id.example.net --set dashboards.enabled=true | grep -c 'kind: ConfigMap'`
Expected: mindestens `1`.

```bash
git add deploy/charts/apus-platform
git commit -m "feat(helm): add Grafana dashboards to the apus-platform chart"
```

---

### Task 8: Ende-zu-Ende-Lauf auf k3s

Design-Spec §13.2 sieht vor: „k3s + S3: kompletter Durchlauf Ingest → Render → Hosting mit Mini-Welt". Vorhanden sind `PushIngestEndToEndTest` (Ingest allein) und `RenderEndToEndTest` (Render allein) — der Durchlauf über alle drei Stufen fehlt, und Hosting ist in keinem E2E-Test enthalten.

**Files:**

- Create: `operator/src/test/java/net/onelitefeather/apus/operator/FullPipelineIntegrationTest.java`
- Modify: `operator/build.gradle.kts` (nur falls der `integrationTest`-Task angepasst werden muss)

**Interfaces:**

- Consumes: die bestehende k3s-Testcontainers-Infrastruktur der vorhandenen `*IntegrationTest`-Klassen sowie `testdata/mini-world`; die beiden Helm Charts unter `deploy/charts/`.

- [ ] **Schritt 1: Bestehende Integrationstest-Infrastruktur ansehen**

Run: `ls operator/src/test/java/net/onelitefeather/apus/operator/*IntegrationTest.java && grep -n 'K3sContainer\|MinIOContainer\|LocallyRunOperatorExtension' operator/src/test/java/net/onelitefeather/apus/operator/OperatorIntegrationTest.java | head`
Expected: das vorhandene Muster für k3s- und MinIO-Container. Der neue Test übernimmt es unverändert, statt eine zweite Variante zu erfinden.

- [ ] **Schritt 2: Failing test schreiben**

Der Test fährt in einer Methode:

1. k3s starten, die sechs CRDs über `helm install apus-operator deploy/charts/apus-operator --set bundles.s3Endpoint=<minio-endpoint>` einspielen (statt einzelne CRD-Manifeste anzuwenden — das Chart installiert sie als Templates, siehe `deploy/charts/apus-operator/templates/crds.yaml`), den Operator-Reconciler zusätzlich über `LocallyRunOperatorExtension` gegen denselben Cluster laufen lassen.
2. MinIO starten, `testdata/mini-world` als Push-Quelle in den Staging-Prefix legen.
3. `Tenant` anlegen, auf `status.namespace` warten.
4. `WorldSource` (Typ `push`) und `WorldIngest` anlegen, warten bis `status.phase == "Succeeded"` und `status.bundle.path` gesetzt ist.
5. `BlueMapMap` anlegen, warten bis der erzeugte `BlueMapRender` auf `Succeeded` steht.
6. `BlueMapHosting` anlegen, warten bis `status.ready == true` und `status.url` gesetzt ist.
7. Prüfen, dass im Map-Bucket tatsächlich Kacheln liegen (`settings.json` und mindestens eine `.png`/`.prbm` unterhalb des Map-Prefix).

Timeouts großzügig (Render der Mini-Welt: bis zu 10 Minuten), jede Wartestufe mit eigener aussagekräftiger Fehlermeldung, damit ein Fehlschlag zeigt, *welche* Stufe hängen blieb.

- [ ] **Schritt 3: Test laufen lassen und Fehlschlag bestätigen**

Run: `./gradlew :operator:integrationTest --tests '*FullPipelineIntegrationTest*'`
Expected: FAIL. Der Fehlschlag muss aus einer der Wartestufen kommen, nicht aus einem Kompilierfehler.

- [ ] **Schritt 4: Test zum Laufen bringen**

Was hier zu tun ist, hängt vom Fehlschlag ab. Erwartbare Stolpersteine, jeweils mit dem Ort, an dem sie zu beheben sind:

- Der Operator im Test kennt die Image-Namen nicht → `OperatorConfig`-Umgebungsvariablen im Test setzen, so wie das `apus-operator`-Chart sie über seine `images.*`-Werte in das Deployment einsetzt (`deploy/charts/apus-operator/templates/deployment.yaml`).
- Rook existiert im k3s-Testcluster nicht → der Test setzt `storage.bucketClaim` nicht auf `auto`, sondern legt Bucket und Secret direkt in MinIO an und referenziert sie; die Rook-Integration ist eigener Scope und in `OperatorIntegrationTest` bereits abgedeckt.
- Der Hosting-Pod braucht einen Ingress-Controller → im Test gegen den `Service` prüfen statt gegen die Ingress-URL; `status.ready` ist das Signal, nicht die externe Erreichbarkeit.

- [ ] **Schritt 5: Test läuft grün, reproduzierbar**

Run: `./gradlew :operator:integrationTest --tests '*FullPipelineIntegrationTest*'` (zweimal hintereinander)
Expected: beide Male PASS. Ein E2E-Test, der nur beim ersten Lauf grün ist, hat Zustandsreste und ist nicht fertig.

- [ ] **Schritt 6: `helm upgrade` mit geändertem CRD-Schema prüfen**

Grund für diesen Schritt: Er belegt die Eigenschaft, wegen der die CRDs in
`deploy/charts/apus-operator/templates/crds.yaml` liegen und nicht in Helms `crds/`-Sonder-
verzeichnis (Design-Spec §9, Task-2-Bericht der Helm Charts) — Letzteres installiert Helm
einmalig und rührt es bei `helm upgrade` nie wieder an, ein geändertes Schema bliebe im
Cluster hängen.

Gegen denselben k3s-Cluster aus Schritt 1:

1. `helm install apus-operator deploy/charts/apus-operator --set bundles.s3Endpoint=<minio-endpoint>` mit dem Chart-Stand *vor* dieser Änderung (letzter Git-Tag bzw. letztes veröffentlichtes Chart-Archiv aus Harbor).
2. Lokal ein Feld im generierten CRD-Schema ändern (z. B. eine neue optionale Property auf einer der `@Group`-annotierten Spec-Klassen), dann `./gradlew :operator:syncCrds` und `deploy/charts/apus-operator/sync-crds.sh` laufen lassen.
3. `helm upgrade apus-operator deploy/charts/apus-operator --set bundles.s3Endpoint=<minio-endpoint>` mit dem geänderten Chart-Stand.
4. `kubectl get crd bluemapmaps.bluemap.onelitefeather.net -o jsonpath='{.spec.versions[0].schema.openAPIV3Schema.properties.spec.properties}'` gegen das neue Feld prüfen.

Run: das Vorstehende als Shell-Sequenz oder als eigener Testfall neben `FullPipelineIntegrationTest`.
Expected: Das neue Feld erscheint im Cluster-Schema nach dem `helm upgrade`, ohne dass irgendjemand die CRD von Hand neu angewendet hat. Die Änderung am Schema danach wieder verwerfen (`git checkout` auf die betroffene Spec-Klasse), damit dieser Schritt keine echte CRD-Änderung hinterlässt.

- [ ] **Schritt 7: Sicherstellen, dass er nicht im PR-Build landet**

Run: `./gradlew :operator:test --tests '*FullPipeline*' 2>&1 | grep -c 'No tests found'`
Expected: `1` — der Test greift die `*IntegrationTest`-Namenskonvention und ist damit aus `test` ausgeschlossen.

- [ ] **Schritt 8: Commit**

```bash
git add operator/src/test/java/net/onelitefeather/apus/operator/FullPipelineIntegrationTest.java
git commit -m "test: cover the full ingest, render and hosting pipeline on k3s"
```

---

### Task 9: Design-Spec nachziehen

**Files:**

- Modify: `docs/superpowers/specs/2026-08-08-apus-design.md`

- [ ] **Schritt 1: §13.1 als umgesetzt kennzeichnen**

Der Abschnitt beschreibt Metriken, Logs und Dashboards im Futur. Umschreiben auf den
Ist-Zustand, mit den echten Dateinamen (`deploy/charts/apus-operator/templates/servicemonitor.yaml`,
`deploy/charts/apus-platform/templates/api-servicemonitor.yaml`,
`deploy/podmonitor-render.yaml`, `deploy/charts/apus-platform/templates/dashboards-configmap.yaml`)
und den tatsächlich exportierten Metriknamen.

- [ ] **Schritt 2: §13.2, Zeile „E2E", auf den neuen Test verweisen**

<!-- markdownlint-disable-next-line MD038 -->
Ersetzen durch: `k3s + S3: kompletter Durchlauf Ingest → Render → Hosting mit Mini-Welt (`FullPipelineIntegrationTest`, Teil von `./gradlew :operator:integrationTest`)`.

- [ ] **Schritt 3: §0 um den Deployment-Stand ergänzen**

Ein Absatz zu Phase-8-§0 ist bereits durch die Helm-Charts-Arbeit vorhanden (siehe deren
Task 9); dieser Schritt erweitert ihn um das, was diese Phase zusätzlich liefert, statt ihn
zu ersetzen:

```markdown
**Ausrollbar seit Phase 8.** Die beiden Helm Charts unter `deploy/charts/`
(`apus-operator`, `apus-platform`) installieren CRDs, Operator, API, UI, RBAC und
Scrape-Konfiguration; cluster-spezifische Werte kommen über `values:` aus der
`HelmRelease` im Cluster-Repository. Operator und API exportieren Metriken, zwei
Grafana-Dashboards liegen als optionale Ressource im `apus-platform`-Chart
(`dashboards.enabled`). Was offen bleibt, sind die inhaltlichen Härtungen aus §15 — siehe
den Plan zu Phase 9.
```

- [ ] **Schritt 4: Lint und Commit**

Run: `npx markdownlint-cli2 docs/superpowers/specs/2026-08-08-apus-design.md`
Expected: keine Fehler.

```bash
git add docs/superpowers/specs/2026-08-08-apus-design.md
git commit -m "docs: record the phase 8 deployment and observability state"
```

---

## Was dieser Plan bewusst nicht abdeckt

- **Das Flux-Overlay selbst** (`OCIRepository` plus `HelmRelease`). Es gehört ins Cluster-Repository (`Kubernetes-FLUX`), nicht hierher: Registry-Hostnamen, Rook-Namen, Domains und Secret-Referenzen sind Cluster-Eigenschaften, keine Projekt-Eigenschaften. Die beiden Helm Charts unter `deploy/charts/` sind so geschnitten, dass eine `HelmRelease` genau diese Werte per `values:` überschreiben kann; siehe Design-Spec §9 und `docs/superpowers/plans/2026-08-13-helm-charts.md`.
- **Alerting-Regeln.** Sinnvoll, aber sie brauchen erst Betriebserfahrung mit den neuen Metriken — Schwellwerte ohne Datengrundlage erzeugen nur Rauschen.
- **Die Härtungen aus §15** (Identity-Broker, RBAC-Verengung, Quota-Signal, Paper-Save-Fenster, `emptyDir`-Grenze) — eigener Plan (Phase 9).
