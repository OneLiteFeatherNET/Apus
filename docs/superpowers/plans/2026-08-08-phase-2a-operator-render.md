# Apus Phase 2a — Operator und Render-Pfad: Implementierungsplan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ein Kubernetes-Operator, der `Tenant`, `BlueMapMap` und `BlueMapRender` verwaltet: Mandanten-Namespaces mit Quotas anlegen, S3-Buckets über Rook provisionieren, BlueMap-Konfiguration erzeugen und Render-Jobs mit dem Runner-Image aus Phase 1 starten — inklusive Fortschritt im Status der Custom Resource.

**Architecture:** Java 25 mit Java Operator SDK 5.5.1 auf Fabric8-Client 7.8.0. Micronaut liefert nur DI, Konfiguration und Health; der Operator selbst wird über einen `StartupEvent`-Listener hochgefahren, da es für Micronaut keine JOSDK-Integration gibt. CRDs werden zur Bauzeit aus den Java-Klassen erzeugt (`crd-generator-api-v2` in einer eigenen Gradle-Task). S3 wird nicht selbst verwaltet, sondern an Rook delegiert: Der Operator legt `CephObjectStoreUser` und `ObjectBucketClaim` an und wartet auf die von Rook erzeugten Secrets.

**Tech Stack:** Java 25, Gradle 9.4.1, JOSDK 5.5.1, Fabric8 7.8.0, Micronaut, JUnit Jupiter, Fabric8 `KubernetesMockServer`, Testcontainers (k3s).

## Global Constraints

- **Java-Toolchain 25**, wie das bestehende `telemetry-addon`-Modul. JOSDK kompiliert gegen Java 17, läuft aber auf 25.
- **Exakte Koordinaten** (real gegen Maven Central geprüft):
  - `io.javaoperatorsdk:operator-framework:5.5.1`
  - `io.javaoperatorsdk:operator-framework-junit:5.5.1` — **nicht** `operator-framework-junit-5`, das ist bei 5.2.5 eingefroren
  - `io.fabric8:crd-generator-api-v2:7.8.0` und `io.fabric8:crd-generator-collector:7.8.0`
  - `io.fabric8:kubernetes-junit-jupiter:7.8.0` (Mock-Server)
  - Der Fabric8-Client kommt transitiv über JOSDK in 7.8.0 — nicht separat pinnen, sonst driftet er.
- **Nicht verwenden:** `io.fabric8:crd-generator-apt` (seit 7.0.0 deprecated) und `io.fabric8.crd.generator.CRDGenerator` (v1, deprecated). Der v2-Weg ist `io.fabric8.crdv2.generator.CRDGenerator`.
- **API-Gruppe:** `bluemap.onelitefeather.net`, Version `v1alpha1`.
- **Java-Basispaket:** `net.onelitefeather.apus.operator`.
- **Der Operator arbeitet strikt namespace-lokal.** Eine namespaced CR darf ausschließlich Ressourcen ihres eigenen Namespace referenzieren. Referenzen über Namespace-Grenzen werden bei der Validierung abgelehnt — das ist die Mandantentrennung aus §10.1 der Spec.
- **Zugangsdaten erscheinen niemals** in CR-Status, Events oder Logs (§12 der Spec).
- **Löschverhalten:** Das Löschen einer `BlueMapMap` löscht keine Daten. Nur bei `spec.purgeOnDelete: true` räumt ein Finalizer auf (§9.4 der Spec).
- AGPL-Header über Spotless, Conventional Commits, **keine** Claude-Attribution, Bezeichner und Javadoc auf Englisch.

### Verifizierte JOSDK-Fakten

```java
// Operator bauen — Operator(KubernetesClient) ist package-private!
Operator operator = new Operator(o -> o.withKubernetesClient(client));
RegisteredController<?> c = operator.register(reconciler);   // wirft OperatorException
operator.start();                                            // public synchronized void
operator.stop();

// Reconciler
@ControllerConfiguration
public class FooReconciler implements Reconciler<Foo> {
    @Override
    public UpdateControl<Foo> reconcile(Foo resource, Context<Foo> context) {
        return UpdateControl.patchStatus(resource);
    }
}

// Custom Resource — ohne `implements Namespaced` ist sie cluster-scoped.
// Die Status-Subresource ist aktiv, sobald ein Status-Typ als zweiter Parameter steht.
@Group("bluemap.onelitefeather.net")
@Version("v1alpha1")
@Kind("BlueMapMap")
@Plural("bluemapmaps")
@ShortNames("bmmap")
public class BlueMapMap extends CustomResource<BlueMapMapSpec, BlueMapMapStatus>
        implements Namespaced {}
```

### Verifizierte Rook-Ressourcen

Aus dem bestehenden Cluster (`Kubernetes-FLUX`):

```yaml
apiVersion: objectbucket.io/v1alpha1
kind: ObjectBucketClaim
spec:
  bucketName: <name>
  storageClassName: ceph-bucket-fr01
  additionalConfig:
    bucketOwner: <ceph-object-store-user>
---
apiVersion: ceph.rook.io/v1
kind: CephObjectStoreUser
spec:
  store: feather-s3
  displayName: <name>
  quotas: { maxSize: 500Gi, maxObjects: 5000000 }   # §10.2 der Spec
```

Rook erzeugt zur `ObjectBucketClaim` im **selben Namespace** ein Secret (`AWS_ACCESS_KEY_ID`, `AWS_SECRET_ACCESS_KEY`) und eine ConfigMap (`BUCKET_HOST`, `BUCKET_NAME`, `BUCKET_PORT`), jeweils benannt wie die Claim.

Für beide CRDs gibt es keine fertigen Java-Modelle. Wir definieren schlanke eigene `CustomResource`-Klassen mit genau den Feldern, die wir brauchen — typsicher, weil der Reconciler den Provisioning-Status auswerten muss.

---

## File Structure

```
operator/
├── build.gradle.kts                      JOSDK, CRD-Generierung, Micronaut
└── src/
    ├── main/java/net/onelitefeather/apus/operator/
    │   ├── ApusOperator.java             StartupEvent-Listener, registriert Reconciler
    │   ├── api/                          Custom Resources (reine Datenklassen)
    │   │   ├── Tenant.java  TenantSpec.java  TenantStatus.java
    │   │   ├── BlueMapMap.java  BlueMapMapSpec.java  BlueMapMapStatus.java
    │   │   ├── BlueMapRender.java  BlueMapRenderSpec.java  BlueMapRenderStatus.java
    │   │   └── Conditions.java           Gemeinsame Condition-Helfer
    │   ├── rook/                          Fremde CRDs, schlank modelliert
    │   │   ├── ObjectBucketClaim.java  ObjectBucketClaimSpec.java  ObjectBucketClaimStatus.java
    │   │   └── CephObjectStoreUser.java  CephObjectStoreUserSpec.java  CephObjectStoreUserStatus.java
    │   ├── tenant/TenantReconciler.java
    │   ├── map/
    │   │   ├── BlueMapMapReconciler.java
    │   │   ├── BucketProvisioner.java    Legt OBC an, wartet auf Secret/ConfigMap
    │   │   └── BlueMapConfigBuilder.java Erzeugt core.conf / maps/*.conf / storages/s3.conf
    │   ├── render/
    │   │   ├── BlueMapRenderReconciler.java
    │   │   ├── RenderJobBuilder.java     Baut den k8s-Job aus dem Runner-Image
    │   │   └── ProgressPoller.java       Liest /progress vom Pod, füllt den Status
    │   └── schedule/RenderScheduler.java Cron und onNewBundle → erzeugt BlueMapRender
    └── test/java/net/onelitefeather/apus/operator/…
```

**Warum diese Aufteilung:** Die Klassen unter `api/` sind reine Datenhalter ohne Logik und ohne Kubernetes-Zugriff — sie sind die Schnittstelle, die auch Phase 5 (API/UI) nutzt. `BlueMapConfigBuilder` und `RenderJobBuilder` sind reine Funktionen von CR nach Kubernetes-Objekt und damit ohne Cluster testbar; nur die Reconciler brauchen einen Client.

---

## Parallelisierung

| Gruppe | Aufgaben | Voraussetzung |
|---|---|---|
| A | Task 1 | — |
| B | Task 2, Task 3 | Task 1 |
| C | Task 4, Task 5 | Task 2, Task 3 |
| D | Task 6, Task 7 | Task 4, Task 5 |
| E | Task 8 | alle |

---

### Task 1: Operator-Modul und CRD-Generierung

**Files:**
- Modify: `settings.gradle.kts` (Modul `operator` und neue Katalog-Einträge)
- Create: `operator/build.gradle.kts`
- Create: `operator/src/main/java/net/onelitefeather/apus/operator/api/Tenant.java` (Minimalfassung, damit es etwas zu generieren gibt)
- Create: `operator/src/main/java/net/onelitefeather/apus/operator/api/TenantSpec.java`
- Create: `operator/src/main/java/net/onelitefeather/apus/operator/api/TenantStatus.java`
- Test: `operator/src/test/java/net/onelitefeather/apus/operator/CrdGenerationTest.java`

**Interfaces:**
- Consumes: nichts
- Produces: Katalog-Aliase `libs.josdk`, `libs.josdk.junit`, `libs.crd.generator.api.v2`, `libs.crd.generator.collector`, `libs.fabric8.junit`; Gradle-Task `generateCrds`, die YAML nach `operator/build/crds/` schreibt; die Klasse `net.onelitefeather.apus.operator.api.Tenant`

- [ ] **Step 1: Katalog-Einträge ergänzen**

In `settings.gradle.kts` im `versionCatalogs`-Block ergänzen:

```kotlin
            version("josdk", "5.5.1")
            version("fabric8", "7.8.0")

            library("josdk", "io.javaoperatorsdk", "operator-framework").versionRef("josdk")
            library("josdk.junit", "io.javaoperatorsdk", "operator-framework-junit").versionRef("josdk")
            library("crd.generator.api.v2", "io.fabric8", "crd-generator-api-v2").versionRef("fabric8")
            library("crd.generator.collector", "io.fabric8", "crd-generator-collector").versionRef("fabric8")
            library("fabric8.junit", "io.fabric8", "kubernetes-junit-jupiter").versionRef("fabric8")
```

Und die Include-Zeile erweitern:

```kotlin
include("telemetry-addon", "runner", "operator")
```

- [ ] **Step 2: Die Custom Resource anlegen, damit die Generierung etwas vorfindet**

`api/TenantSpec.java`:

```java
package net.onelitefeather.apus.operator.api;

/** Desired state of a tenant. Plain data, no Kubernetes access. */
public class TenantSpec {

    private String displayName;
    private StorageQuota storage = new StorageQuota();

    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    public StorageQuota getStorage() {
        return storage;
    }

    public void setStorage(StorageQuota storage) {
        this.storage = storage;
    }

    /** Hard storage limit, enforced by Ceph rather than by this operator. */
    public static class StorageQuota {
        private String quota = "100Gi";
        private Long maxObjects;

        public String getQuota() {
            return quota;
        }

        public void setQuota(String quota) {
            this.quota = quota;
        }

        public Long getMaxObjects() {
            return maxObjects;
        }

        public void setMaxObjects(Long maxObjects) {
            this.maxObjects = maxObjects;
        }
    }
}
```

`api/TenantStatus.java`:

```java
package net.onelitefeather.apus.operator.api;

import io.fabric8.kubernetes.api.model.Condition;
import java.util.ArrayList;
import java.util.List;

/** Observed state of a tenant. */
public class TenantStatus {

    private String namespace;
    private String objectStoreUser;
    private Long storageUsedBytes;
    private List<Condition> conditions = new ArrayList<>();

    public String getNamespace() {
        return namespace;
    }

    public void setNamespace(String namespace) {
        this.namespace = namespace;
    }

    public String getObjectStoreUser() {
        return objectStoreUser;
    }

    public void setObjectStoreUser(String objectStoreUser) {
        this.objectStoreUser = objectStoreUser;
    }

    public Long getStorageUsedBytes() {
        return storageUsedBytes;
    }

    public void setStorageUsedBytes(Long storageUsedBytes) {
        this.storageUsedBytes = storageUsedBytes;
    }

    public List<Condition> getConditions() {
        return conditions;
    }

    public void setConditions(List<Condition> conditions) {
        this.conditions = conditions;
    }
}
```

`api/Tenant.java` — beachte: **kein** `implements Namespaced`, denn `Tenant` ist cluster-scoped (§8.1 der Spec):

```java
package net.onelitefeather.apus.operator.api;

import io.fabric8.kubernetes.client.CustomResource;
import io.fabric8.kubernetes.model.annotation.Group;
import io.fabric8.kubernetes.model.annotation.Kind;
import io.fabric8.kubernetes.model.annotation.Plural;
import io.fabric8.kubernetes.model.annotation.ShortNames;
import io.fabric8.kubernetes.model.annotation.Version;

/**
 * A tenant of the Apus platform. Cluster-scoped on purpose: only platform
 * administrators may create one, because it grants a namespace and a storage quota.
 */
@Group("bluemap.onelitefeather.net")
@Version("v1alpha1")
@Kind("Tenant")
@Plural("tenants")
@ShortNames("bmtenant")
public class Tenant extends CustomResource<TenantSpec, TenantStatus> {}
```

- [ ] **Step 3: `operator/build.gradle.kts` schreiben**

Der Weg über `crd-generator-api-v2` ist der von Fabric8 empfohlene; der frühere Annotation-Processor ist seit 7.0.0 deprecated.

```kotlin
plugins {
    application
}

dependencies {
    implementation(libs.josdk)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
    testImplementation(libs.fabric8.junit)
}

// Separate Konfiguration für den Generator, damit seine Abhängigkeiten
// nicht im Laufzeit-Classpath des Operators landen.
val crdGenerator: Configuration by configurations.creating

dependencies {
    crdGenerator(libs.crd.generator.api.v2)
    crdGenerator(libs.crd.generator.collector)
    crdGenerator(libs.josdk)
}

val crdOutputDir = layout.buildDirectory.dir("crds")

val generateCrds by tasks.registering(JavaExec::class) {
    description = "Generates CRD YAML from the CustomResource classes."
    group = "build"
    dependsOn(tasks.named("classes"))
    classpath = crdGenerator + sourceSets.main.get().runtimeClasspath
    mainClass.set("io.fabric8.crdv2.generator.cli.CRDGeneratorCLI")
    outputs.dir(crdOutputDir)
    doFirst {
        crdOutputDir.get().asFile.mkdirs()
        args = listOf(
            "--output-dir=${crdOutputDir.get().asFile.absolutePath}",
            "--classpath=${sourceSets.main.get().runtimeClasspath.asPath}",
        )
    }
}

tasks.named("build") {
    dependsOn(generateCrds)
}

application {
    mainClass.set("net.onelitefeather.apus.operator.ApusOperator")
}
```

> **Zu verifizieren in Step 5:** Der Hauptklassenname des Generator-CLI (`io.fabric8.crdv2.generator.cli.CRDGeneratorCLI`) und seine Argumentnamen stammen aus der Recherche, nicht aus einer Ausführung. Stimmt der Aufruf nicht, ermittle die echte Einstiegsklasse aus dem Jar und korrigiere Plan und Build:
> ```bash
> ./gradlew :operator:dependencies --configuration crdGenerator | grep crd-generator
> unzip -l ~/.gradle/caches/modules-2/files-2.1/io.fabric8/crd-generator-api-v2/7.8.0/*/crd-generator-api-v2-7.8.0.jar | grep -iE "cli|Main"
> ```
> Alternativ funktioniert immer der programmatische Weg: eine kleine Java-Klasse im `buildSrc` oder eine `JavaExec`-Task auf eine eigene Generator-Hauptklasse, die `new CRDGenerator().customResourceClasses(...).inOutputDir(dir).detailedGenerate()` aufruft. Wähle den Weg, der real funktioniert, und dokumentiere ihn.

- [ ] **Step 4: Den fehlschlagenden Test schreiben**

Dieser Test prüft, dass die Generierung wirklich lief und ein CRD mit den erwarteten Eigenschaften erzeugt hat — insbesondere `scope: Cluster`, den häufigsten Fehler bei `Tenant`.

`CrdGenerationTest.java`:

```java
package net.onelitefeather.apus.operator;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class CrdGenerationTest {

    private static Path crdDir() {
        return Path.of(System.getProperty("apus.crd.dir", "build/crds"));
    }

    private static String readAllCrds() throws IOException {
        try (Stream<Path> files = Files.list(crdDir())) {
            List<Path> yamls = files.filter(p -> p.toString().endsWith(".yml")
                            || p.toString().endsWith(".yaml"))
                    .toList();
            StringBuilder all = new StringBuilder();
            for (Path p : yamls) {
                all.append(Files.readString(p, StandardCharsets.UTF_8)).append('\n');
            }
            return all.toString();
        }
    }

    @Test
    void generatesACrdForTheTenantResource() throws IOException {
        assertTrue(Files.isDirectory(crdDir()), "CRD output directory must exist: " + crdDir());

        String all = readAllCrds();

        assertTrue(all.contains("bluemap.onelitefeather.net"), "API group missing:\n" + all);
        assertTrue(all.contains("kind: Tenant"), "Tenant kind missing:\n" + all);
        assertTrue(all.contains("plural: tenants"), "plural missing:\n" + all);
    }

    @Test
    void tenantIsClusterScoped() throws IOException {
        String all = readAllCrds();

        // Tenant grants a namespace and a storage quota — it must never be
        // creatable from inside a tenant namespace.
        assertTrue(all.contains("scope: Cluster"), "Tenant must be cluster-scoped:\n" + all);
    }

    @Test
    void statusSubresourceIsEnabled() throws IOException {
        String all = readAllCrds();

        // Without the status subresource the operator could not update status
        // independently of spec, and every status write would bump the resource version.
        assertTrue(all.contains("status: {}") || all.contains("subresources"),
                "status subresource missing:\n" + all);
    }
}
```

Damit der Test das Verzeichnis findet, in `operator/build.gradle.kts` ergänzen:

```kotlin
tasks.test {
    dependsOn(generateCrds)
    systemProperty("apus.crd.dir", crdOutputDir.get().asFile.absolutePath)
}
```

- [ ] **Step 5: Test ausführen und Fehlschlag prüfen**

Run: `./gradlew :operator:test`
Expected: FAIL — entweder weil die Generator-Task nicht startet (falscher Hauptklassenname, siehe Hinweis in Step 3) oder weil noch kein CRD erzeugt wurde.

Arbeite den Hinweis aus Step 3 ab, bis die Generierung läuft.

- [ ] **Step 6: Test ausführen und Erfolg prüfen**

Run: `./gradlew :operator:test`
Expected: PASS (3 Tests)

Sieh dir das erzeugte YAML einmal an, damit du weißt, was der Operator ausliefert:

```bash
cat operator/build/crds/*.yml | head -40
```

- [ ] **Step 7: Commit**

```bash
./gradlew spotlessApply
git add -A
git commit -m "build(operator): add operator module with crd generation"
```

---

### Task 2: Rook-Ressourcen modellieren

**Files:**
- Create: `operator/src/main/java/net/onelitefeather/apus/operator/rook/ObjectBucketClaim.java`
- Create: `operator/src/main/java/net/onelitefeather/apus/operator/rook/ObjectBucketClaimSpec.java`
- Create: `operator/src/main/java/net/onelitefeather/apus/operator/rook/ObjectBucketClaimStatus.java`
- Create: `operator/src/main/java/net/onelitefeather/apus/operator/rook/CephObjectStoreUser.java`
- Create: `operator/src/main/java/net/onelitefeather/apus/operator/rook/CephObjectStoreUserSpec.java`
- Create: `operator/src/main/java/net/onelitefeather/apus/operator/rook/CephObjectStoreUserStatus.java`
- Test: `operator/src/test/java/net/onelitefeather/apus/operator/rook/RookResourceSerialisationTest.java`

**Interfaces:**
- Consumes: nichts aus anderen Aufgaben
- Produces:
```java
// Beide sind namespaced.
ObjectBucketClaim:  spec.bucketName, spec.storageClassName,
                    spec.additionalConfig (Map<String,String>, u.a. "bucketOwner")
                    status.phase   // "Bound", "Pending", "Failed"
CephObjectStoreUser: spec.store, spec.displayName,
                     spec.quotas.maxSize, spec.quotas.maxObjects, spec.quotas.maxBuckets
                     status.phase
```

Diese Klassen dürfen **nicht** in die CRD-Generierung geraten — sie modellieren fremde CRDs, die Rook mitbringt.

- [ ] **Step 1: Den fehlschlagenden Test schreiben**

Der Test prüft, dass unsere Modelle exakt das YAML erzeugen, das der Cluster erwartet. Er ist gegen die real im Cluster vorhandenen Manifeste formuliert.

`RookResourceSerialisationTest.java`:

```java
package net.onelitefeather.apus.operator.rook;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.fabric8.kubernetes.client.utils.Serialization;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RookResourceSerialisationTest {

    @Test
    void objectBucketClaimMatchesTheClusterSchema() {
        ObjectBucketClaim claim = new ObjectBucketClaim();
        claim.getMetadata().setName("apus-friends-survival");
        claim.getMetadata().setNamespace("bluemap-friends");
        claim.getSpec().setBucketName("apus-friends-survival");
        claim.getSpec().setStorageClassName("ceph-bucket-fr01");
        claim.getSpec().setAdditionalConfig(Map.of("bucketOwner", "apus-friends"));

        String yaml = Serialization.asYaml(claim);

        assertTrue(yaml.contains("apiVersion: \"objectbucket.io/v1alpha1\"")
                        || yaml.contains("apiVersion: objectbucket.io/v1alpha1"),
                yaml);
        assertTrue(yaml.contains("kind: \"ObjectBucketClaim\"") || yaml.contains("kind: ObjectBucketClaim"), yaml);
        assertTrue(yaml.contains("storageClassName"), yaml);
        assertTrue(yaml.contains("bucketOwner"), yaml);
    }

    @Test
    void cephObjectStoreUserCarriesTheQuota() {
        CephObjectStoreUser user = new CephObjectStoreUser();
        user.getMetadata().setName("apus-friends");
        user.getMetadata().setNamespace("rook-ceph-fr01");
        user.getSpec().setStore("feather-s3");
        user.getSpec().setDisplayName("apus-friends");
        user.getSpec().getQuotas().setMaxSize("500Gi");
        user.getSpec().getQuotas().setMaxObjects(5_000_000L);

        String yaml = Serialization.asYaml(user);

        assertTrue(yaml.contains("ceph.rook.io/v1"), yaml);
        assertTrue(yaml.contains("CephObjectStoreUser"), yaml);
        assertTrue(yaml.contains("500Gi"), yaml);
        assertTrue(yaml.contains("5000000"), yaml);
    }

    @Test
    void deserialisesAClaimStatusFromTheCluster() {
        String yaml = """
                apiVersion: objectbucket.io/v1alpha1
                kind: ObjectBucketClaim
                metadata:
                  name: apus-friends-survival
                  namespace: bluemap-friends
                spec:
                  bucketName: apus-friends-survival
                  storageClassName: ceph-bucket-fr01
                status:
                  phase: Bound
                """;

        ObjectBucketClaim claim = Serialization.unmarshal(yaml, ObjectBucketClaim.class);

        assertEquals("Bound", claim.getStatus().getPhase());
        assertEquals("apus-friends-survival", claim.getSpec().getBucketName());
    }
}
```

- [ ] **Step 2: Test ausführen und Fehlschlag prüfen**

Run: `./gradlew :operator:test --tests '*RookResourceSerialisationTest*'`
Expected: FAIL, „cannot find symbol: class ObjectBucketClaim"

- [ ] **Step 3: `ObjectBucketClaim` implementieren**

```java
package net.onelitefeather.apus.operator.rook;

import io.fabric8.kubernetes.api.model.Namespaced;
import io.fabric8.kubernetes.client.CustomResource;
import io.fabric8.kubernetes.model.annotation.Group;
import io.fabric8.kubernetes.model.annotation.Kind;
import io.fabric8.kubernetes.model.annotation.Plural;
import io.fabric8.kubernetes.model.annotation.Version;

/**
 * Rook's ObjectBucketClaim, modelled with only the fields Apus uses.
 *
 * <p>Apus does not manage S3 itself: creating one of these makes Rook provision the
 * bucket and drop a credentials Secret and a ConfigMap into the same namespace.
 * This class is a client-side model of a CRD Rook owns — it must never be fed to
 * our own CRD generator.
 */
@Group("objectbucket.io")
@Version("v1alpha1")
@Kind("ObjectBucketClaim")
@Plural("objectbucketclaims")
public class ObjectBucketClaim extends CustomResource<ObjectBucketClaimSpec, ObjectBucketClaimStatus>
        implements Namespaced {

    @Override
    protected ObjectBucketClaimSpec initSpec() {
        return new ObjectBucketClaimSpec();
    }

    @Override
    protected ObjectBucketClaimStatus initStatus() {
        return new ObjectBucketClaimStatus();
    }
}
```

`ObjectBucketClaimSpec.java`:

```java
package net.onelitefeather.apus.operator.rook;

import java.util.LinkedHashMap;
import java.util.Map;

public class ObjectBucketClaimSpec {

    private String bucketName;
    private String storageClassName;
    private Map<String, String> additionalConfig = new LinkedHashMap<>();

    public String getBucketName() {
        return bucketName;
    }

    public void setBucketName(String bucketName) {
        this.bucketName = bucketName;
    }

    public String getStorageClassName() {
        return storageClassName;
    }

    public void setStorageClassName(String storageClassName) {
        this.storageClassName = storageClassName;
    }

    public Map<String, String> getAdditionalConfig() {
        return additionalConfig;
    }

    public void setAdditionalConfig(Map<String, String> additionalConfig) {
        this.additionalConfig = additionalConfig;
    }
}
```

`ObjectBucketClaimStatus.java`:

```java
package net.onelitefeather.apus.operator.rook;

public class ObjectBucketClaimStatus {

    /** Rook sets this to "Bound" once the bucket exists and credentials are written. */
    private String phase;

    public String getPhase() {
        return phase;
    }

    public void setPhase(String phase) {
        this.phase = phase;
    }
}
```

- [ ] **Step 4: `CephObjectStoreUser` implementieren**

```java
package net.onelitefeather.apus.operator.rook;

import io.fabric8.kubernetes.api.model.Namespaced;
import io.fabric8.kubernetes.client.CustomResource;
import io.fabric8.kubernetes.model.annotation.Group;
import io.fabric8.kubernetes.model.annotation.Kind;
import io.fabric8.kubernetes.model.annotation.Plural;
import io.fabric8.kubernetes.model.annotation.Version;

/**
 * Rook's CephObjectStoreUser, modelled with only the fields Apus uses.
 *
 * <p>This is where a tenant's storage limit lives. Because every bucket of a tenant
 * is owned by this user, RGW enforces the quota across all of them — the limit holds
 * even if the application miscounts.
 */
@Group("ceph.rook.io")
@Version("v1")
@Kind("CephObjectStoreUser")
@Plural("cephobjectstoreusers")
public class CephObjectStoreUser
        extends CustomResource<CephObjectStoreUserSpec, CephObjectStoreUserStatus> implements Namespaced {

    @Override
    protected CephObjectStoreUserSpec initSpec() {
        return new CephObjectStoreUserSpec();
    }

    @Override
    protected CephObjectStoreUserStatus initStatus() {
        return new CephObjectStoreUserStatus();
    }
}
```

`CephObjectStoreUserSpec.java`:

```java
package net.onelitefeather.apus.operator.rook;

public class CephObjectStoreUserSpec {

    private String store;
    private String displayName;
    private Quotas quotas = new Quotas();

    public String getStore() {
        return store;
    }

    public void setStore(String store) {
        this.store = store;
    }

    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    public Quotas getQuotas() {
        return quotas;
    }

    public void setQuotas(Quotas quotas) {
        this.quotas = quotas;
    }

    /** Enforced by RGW, not by Apus. Exceeding it makes uploads fail. */
    public static class Quotas {
        private String maxSize;
        private Long maxObjects;
        private Integer maxBuckets;

        public String getMaxSize() {
            return maxSize;
        }

        public void setMaxSize(String maxSize) {
            this.maxSize = maxSize;
        }

        public Long getMaxObjects() {
            return maxObjects;
        }

        public void setMaxObjects(Long maxObjects) {
            this.maxObjects = maxObjects;
        }

        public Integer getMaxBuckets() {
            return maxBuckets;
        }

        public void setMaxBuckets(Integer maxBuckets) {
            this.maxBuckets = maxBuckets;
        }
    }
}
```

`CephObjectStoreUserStatus.java`:

```java
package net.onelitefeather.apus.operator.rook;

public class CephObjectStoreUserStatus {

    private String phase;

    public String getPhase() {
        return phase;
    }

    public void setPhase(String phase) {
        this.phase = phase;
    }
}
```

- [ ] **Step 5: Test ausführen und Erfolg prüfen**

Run: `./gradlew :operator:test --tests '*RookResourceSerialisationTest*'`
Expected: PASS (3 Tests)

- [ ] **Step 6: Sicherstellen, dass die Rook-Modelle nicht in unsere CRDs geraten**

Run: `./gradlew :operator:generateCrds && ls operator/build/crds/`
Expected: Nur CRDs der Gruppe `bluemap.onelitefeather.net`. Erscheinen dort `objectbucketclaims` oder `cephobjectstoreusers`, schränke die Klassenauswahl des Generators explizit auf das Paket `net.onelitefeather.apus.operator.api` ein und ergänze eine Zusicherung dafür in `CrdGenerationTest`:

```java
    @Test
    void doesNotGenerateCrdsForForeignResources() throws IOException {
        String all = readAllCrds();

        // Rook owns these CRDs; shipping our own copy would fight with Rook's.
        assertTrue(!all.contains("objectbucket.io"), "must not generate Rook CRDs:\n" + all);
        assertTrue(!all.contains("ceph.rook.io"), "must not generate Rook CRDs:\n" + all);
    }
```

- [ ] **Step 7: Commit**

```bash
./gradlew spotlessApply
git add -A
git commit -m "feat(operator): model the rook resources apus provisions"
```

---

### Task 3: Tenant-Reconciler

**Files:**
- Create: `operator/src/main/java/net/onelitefeather/apus/operator/tenant/TenantReconciler.java`
- Create: `operator/src/main/java/net/onelitefeather/apus/operator/api/Conditions.java`
- Test: `operator/src/test/java/net/onelitefeather/apus/operator/tenant/TenantReconcilerTest.java`

**Interfaces:**
- Consumes: `Tenant`, `TenantSpec`, `TenantStatus` (Task 1); `CephObjectStoreUser` (Task 2)
- Produces:
```java
public final class Conditions {
    public static Condition ready(boolean ready, String reason, String message);
    public static void set(List<Condition> conditions, Condition condition);  // ersetzt gleichnamige
}

@ControllerConfiguration
public class TenantReconciler implements Reconciler<Tenant> {
    public TenantReconciler(KubernetesClient client, OperatorConfig config);
}
```
Der Reconciler erzeugt aus einem `Tenant`: Namespace `bluemap-<name>`, `ResourceQuota`, `LimitRange` und einen `CephObjectStoreUser` mit der Quota.

- [ ] **Step 1: Den fehlschlagenden Test schreiben**

Der Fabric8-Mock-Server erlaubt echte Client-Aufrufe ohne Cluster.

`TenantReconcilerTest.java`:

```java
package net.onelitefeather.apus.operator.tenant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.fabric8.kubernetes.api.model.Namespace;
import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;
import io.fabric8.kubernetes.api.model.ResourceQuota;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.server.mock.EnableKubernetesMockClient;
import io.fabric8.kubernetes.client.server.mock.KubernetesMockServer;
import net.onelitefeather.apus.operator.api.Tenant;
import org.junit.jupiter.api.Test;

@EnableKubernetesMockClient(crud = true)
class TenantReconcilerTest {

    KubernetesClient client;
    KubernetesMockServer server;

    private Tenant tenant(String name, String quota) {
        Tenant tenant = new Tenant();
        tenant.setMetadata(new ObjectMetaBuilder().withName(name).build());
        tenant.getSpec().setDisplayName(name);
        tenant.getSpec().getStorage().setQuota(quota);
        return tenant;
    }

    @Test
    void createsTheNamespaceForANewTenant() {
        TenantReconciler reconciler = new TenantReconciler(client, OperatorConfig.defaults());

        reconciler.reconcile(tenant("friends", "500Gi"), null);

        Namespace ns = client.namespaces().withName("bluemap-friends").get();
        assertNotNull(ns, "tenant namespace must be created");
        assertEquals("friends", ns.getMetadata().getLabels().get("apus.onelitefeather.net/tenant"));
    }

    @Test
    void appliesTheComputeQuotaToTheNamespace() {
        TenantReconciler reconciler = new TenantReconciler(client, OperatorConfig.defaults());

        reconciler.reconcile(tenant("friends", "500Gi"), null);

        ResourceQuota quota =
                client.resourceQuotas().inNamespace("bluemap-friends").withName("apus-tenant").get();
        assertNotNull(quota, "resource quota must be created");
    }

    @Test
    void createsACephUserCarryingTheStorageQuota() {
        TenantReconciler reconciler = new TenantReconciler(client, OperatorConfig.defaults());

        reconciler.reconcile(tenant("friends", "500Gi"), null);

        var user = client.resources(net.onelitefeather.apus.operator.rook.CephObjectStoreUser.class)
                .inNamespace(OperatorConfig.defaults().rookNamespace())
                .withName("apus-friends")
                .get();

        assertNotNull(user, "ceph object store user must be created");
        assertEquals("500Gi", user.getSpec().getQuotas().getMaxSize());
    }

    @Test
    void isIdempotent() {
        TenantReconciler reconciler = new TenantReconciler(client, OperatorConfig.defaults());
        Tenant tenant = tenant("friends", "500Gi");

        reconciler.reconcile(tenant, null);
        reconciler.reconcile(tenant, null);

        assertNotNull(client.namespaces().withName("bluemap-friends").get());
    }

    @Test
    void reportsTheNamespaceInStatus() {
        TenantReconciler reconciler = new TenantReconciler(client, OperatorConfig.defaults());
        Tenant tenant = tenant("friends", "500Gi");

        var control = reconciler.reconcile(tenant, null);

        assertEquals("bluemap-friends", tenant.getStatus().getNamespace());
        assertEquals("apus-friends", tenant.getStatus().getObjectStoreUser());
        assertTrue(control.isPatchStatus(), "status must be patched so the user can see the namespace");
    }
}
```

- [ ] **Step 2: Test ausführen und Fehlschlag prüfen**

Run: `./gradlew :operator:test --tests '*TenantReconcilerTest*'`
Expected: FAIL, „cannot find symbol: class TenantReconciler"

- [ ] **Step 3: `OperatorConfig` und `Conditions` implementieren**

`api/Conditions.java`:

```java
package net.onelitefeather.apus.operator.api;

import io.fabric8.kubernetes.api.model.Condition;
import io.fabric8.kubernetes.api.model.ConditionBuilder;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/** Helpers for the condition lists every Apus resource carries in its status. */
public final class Conditions {

    public static final String READY = "Ready";

    private Conditions() {}

    public static Condition ready(boolean ready, String reason, String message) {
        return new ConditionBuilder()
                .withType(READY)
                .withStatus(ready ? "True" : "False")
                .withReason(reason)
                .withMessage(message)
                .withLastTransitionTime(DateTimeFormatter.ISO_INSTANT.format(ZonedDateTime.now()))
                .build();
    }

    /** Replaces an existing condition of the same type instead of appending a duplicate. */
    public static void set(List<Condition> conditions, Condition condition) {
        conditions.removeIf(existing -> existing.getType().equals(condition.getType()));
        conditions.add(condition);
    }
}
```

`OperatorConfig.java` im Paket `net.onelitefeather.apus.operator`:

```java
package net.onelitefeather.apus.operator;

/**
 * Cluster-specific settings the operator needs but cannot derive.
 *
 * <p>These differ per installation, which is why they are configuration rather than
 * constants: the Rook namespace, the object store name and the bucket StorageClass
 * are all site-specific.
 */
public record OperatorConfig(
        String rookNamespace, String cephObjectStore, String bucketStorageClass, String runnerImage) {

    public static OperatorConfig defaults() {
        return new OperatorConfig("rook-ceph-fr01", "feather-s3", "ceph-bucket-fr01", "apus/runner:dev");
    }
}
```

- [ ] **Step 4: `TenantReconciler` implementieren**

```java
package net.onelitefeather.apus.operator.tenant;

import io.fabric8.kubernetes.api.model.LimitRangeBuilder;
import io.fabric8.kubernetes.api.model.NamespaceBuilder;
import io.fabric8.kubernetes.api.model.Quantity;
import io.fabric8.kubernetes.api.model.ResourceQuotaBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.javaoperatorsdk.operator.api.reconciler.Context;
import io.javaoperatorsdk.operator.api.reconciler.ControllerConfiguration;
import io.javaoperatorsdk.operator.api.reconciler.Reconciler;
import io.javaoperatorsdk.operator.api.reconciler.UpdateControl;
import java.util.Map;
import net.onelitefeather.apus.operator.OperatorConfig;
import net.onelitefeather.apus.operator.api.Conditions;
import net.onelitefeather.apus.operator.api.Tenant;
import net.onelitefeather.apus.operator.rook.CephObjectStoreUser;

/**
 * Turns a Tenant into the ground a tenant stands on: a namespace, compute limits and
 * a Ceph user carrying the storage quota.
 *
 * <p>The storage limit is deliberately enforced by Ceph rather than by this operator —
 * a tenant cannot exceed it even if Apus miscounts.
 */
@ControllerConfiguration
public class TenantReconciler implements Reconciler<Tenant> {

    public static final String TENANT_LABEL = "apus.onelitefeather.net/tenant";

    private final KubernetesClient client;
    private final OperatorConfig config;

    public TenantReconciler(KubernetesClient client, OperatorConfig config) {
        this.client = client;
        this.config = config;
    }

    public static String namespaceFor(Tenant tenant) {
        return "bluemap-" + tenant.getMetadata().getName();
    }

    public static String cephUserFor(Tenant tenant) {
        return "apus-" + tenant.getMetadata().getName();
    }

    @Override
    public UpdateControl<Tenant> reconcile(Tenant tenant, Context<Tenant> context) {
        String namespace = namespaceFor(tenant);
        String cephUser = cephUserFor(tenant);

        client.namespaces()
                .resource(new NamespaceBuilder()
                        .withNewMetadata()
                        .withName(namespace)
                        .withLabels(Map.of(TENANT_LABEL, tenant.getMetadata().getName()))
                        .endMetadata()
                        .build())
                .serverSideApply();

        client.resourceQuotas()
                .inNamespace(namespace)
                .resource(new ResourceQuotaBuilder()
                        .withNewMetadata()
                        .withName("apus-tenant")
                        .withNamespace(namespace)
                        .endMetadata()
                        .withNewSpec()
                        .withHard(Map.of(
                                "requests.cpu", new Quantity("4"),
                                "requests.memory", new Quantity("8Gi")))
                        .endSpec()
                        .build())
                .serverSideApply();

        client.limitRanges()
                .inNamespace(namespace)
                .resource(new LimitRangeBuilder()
                        .withNewMetadata()
                        .withName("apus-tenant")
                        .withNamespace(namespace)
                        .endMetadata()
                        .build())
                .serverSideApply();

        CephObjectStoreUser user = new CephObjectStoreUser();
        user.getMetadata().setName(cephUser);
        user.getMetadata().setNamespace(config.rookNamespace());
        user.getSpec().setStore(config.cephObjectStore());
        user.getSpec().setDisplayName(cephUser);
        user.getSpec().getQuotas().setMaxSize(tenant.getSpec().getStorage().getQuota());
        user.getSpec().getQuotas().setMaxObjects(tenant.getSpec().getStorage().getMaxObjects());
        client.resources(CephObjectStoreUser.class)
                .inNamespace(config.rookNamespace())
                .resource(user)
                .serverSideApply();

        tenant.getStatus().setNamespace(namespace);
        tenant.getStatus().setObjectStoreUser(cephUser);
        Conditions.set(
                tenant.getStatus().getConditions(),
                Conditions.ready(true, "Provisioned", "namespace and storage user exist"));

        return UpdateControl.patchStatus(tenant);
    }
}
```

- [ ] **Step 5: Test ausführen und Erfolg prüfen**

Run: `./gradlew :operator:test --tests '*TenantReconcilerTest*'`
Expected: PASS (5 Tests)

Schlägt `serverSideApply()` im Mock-Server fehl, weiche auf `createOr(NonDeletingOperation::update)` aus und passe Plan wie Code an — der Mock-Server unterstützt nicht jede Apply-Semantik.

- [ ] **Step 6: Commit**

```bash
./gradlew spotlessApply
git add -A
git commit -m "feat(operator): reconcile tenants into namespaces with quotas"
```

---

### Task 4: BlueMapMap — Bucket und Konfiguration

**Files:**
- Create: `operator/src/main/java/net/onelitefeather/apus/operator/api/BlueMapMap.java`, `BlueMapMapSpec.java`, `BlueMapMapStatus.java`
- Create: `operator/src/main/java/net/onelitefeather/apus/operator/map/BucketProvisioner.java`
- Create: `operator/src/main/java/net/onelitefeather/apus/operator/map/BlueMapConfigBuilder.java`
- Test: `operator/src/test/java/net/onelitefeather/apus/operator/map/BlueMapConfigBuilderTest.java`
- Test: `operator/src/test/java/net/onelitefeather/apus/operator/map/BucketProvisionerTest.java`

**Interfaces:**
- Consumes: `ObjectBucketClaim` (Task 2), `OperatorConfig` (Task 3)
- Produces:
```java
BlueMapMapSpec:  source{sourceRef,world,dimension}, trigger{onNewBundle,schedule,concurrencyPolicy},
                 bluemap{version,configOverrides}, storage{bucketClaim,prefix},
                 resources{cpu,memory}, shards, historyLimit, purgeOnDelete
BlueMapMapStatus: bucket{name,endpoint,secretName}, latestRender{name,phase}, conditions

public final class BucketProvisioner {
    public BucketProvisioner(KubernetesClient client, OperatorConfig config);
    /** @return the bound claim, or empty while Rook is still provisioning */
    public Optional<ObjectBucketClaim> ensureBucket(BlueMapMap map, String cephUser);
}

public final class BlueMapConfigBuilder {
    /** @return file name → file content, ready to become a ConfigMap */
    public static Map<String, String> build(BlueMapMap map, BucketBinding binding);
    public record BucketBinding(String bucketName, String endpoint, String region) {}
}
```

**Wichtig — das `s3.conf`-Format ist in Phase 1 verifiziert worden** (§9.2 der Spec). Nutze exakt diese Schlüssel:
`storage-type: "themeinerlp:s3"`, `bucket-name`, `region`, `access-key-id`, `secret-access-key`, `endpoint-url`, `compression`, `root-path`, `force-path-style`.
`core.conf` braucht zwingend `accept-download: true`, sonst schlägt **jeder** Render fehl.

Zugangsdaten kommen **nicht** in die ConfigMap. Sie werden im Pod aus dem von Rook erzeugten Secret als Umgebungsvariablen gemountet; der Runner-Entrypoint schreibt sie beim Start in die Konfiguration. Genau dafür existiert der Umgebungsvariablen-Vertrag aus Phase 1.

- [ ] **Step 1: Den fehlschlagenden Test für den Konfigurationsbau schreiben**

```java
package net.onelitefeather.apus.operator.map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;
import java.util.Map;
import net.onelitefeather.apus.operator.api.BlueMapMap;
import org.junit.jupiter.api.Test;

class BlueMapConfigBuilderTest {

    private BlueMapMap map() {
        BlueMapMap map = new BlueMapMap();
        map.setMetadata(new ObjectMetaBuilder().withName("survival-overworld")
                .withNamespace("bluemap-friends").build());
        map.getSpec().getSource().setDimension("minecraft:overworld");
        map.getSpec().getStorage().setPrefix("survival");
        return map;
    }

    private BlueMapConfigBuilder.BucketBinding binding() {
        return new BlueMapConfigBuilder.BucketBinding(
                "apus-friends-survival", "http://rook-ceph-rgw.example.svc:80", "us-east-1");
    }

    @Test
    void coreConfigEnablesTheResourceDownload() {
        Map<String, String> files = BlueMapConfigBuilder.build(map(), binding());

        // Without accept-download BlueMap refuses to fetch Minecraft resources
        // and every render exits with code 2.
        assertTrue(files.get("core.conf").contains("accept-download: true"), files.get("core.conf"));
    }

    @Test
    void storageConfigUsesTheVerifiedS3Format() {
        Map<String, String> files = BlueMapConfigBuilder.build(map(), binding());
        String s3 = files.get("storages/s3.conf");

        assertTrue(s3.contains("storage-type: \"themeinerlp:s3\""), s3);
        assertTrue(s3.contains("bucket-name: \"apus-friends-survival\""), s3);
        assertTrue(s3.contains("root-path: \"survival\""), s3);
        assertTrue(s3.contains("force-path-style: true"), s3);
    }

    @Test
    void neverPutsCredentialsIntoTheConfigMap() {
        Map<String, String> files = BlueMapConfigBuilder.build(map(), binding());

        // Credentials live in the Rook-managed Secret and are injected as environment
        // variables at pod start. A ConfigMap is world-readable within the namespace.
        for (Map.Entry<String, String> file : files.entrySet()) {
            assertFalse(file.getValue().contains("secret-access-key: \""),
                    "credentials must not be in " + file.getKey());
            assertFalse(file.getValue().contains("access-key-id: \""),
                    "credentials must not be in " + file.getKey());
        }
    }

    @Test
    void mapConfigCarriesTheDimension() {
        Map<String, String> files = BlueMapConfigBuilder.build(map(), binding());

        assertTrue(files.get("maps/survival-overworld.conf").contains("minecraft:overworld"),
                files.toString());
    }
}
```

- [ ] **Step 2: Test ausführen und Fehlschlag prüfen**

Run: `./gradlew :operator:test --tests '*BlueMapConfigBuilderTest*'`
Expected: FAIL, „cannot find symbol"

- [ ] **Step 3: Die Spec-Klassen und den Builder implementieren**

Private Felder mit Gettern und Settern, verschachtelte statische Klassen für Gruppen — wie `TenantSpec` in Task 1. **Alle Gruppen werden im Feld direkt initialisiert** (`= new Source()`), damit Reconciler und Tests nie gegen `null` prüfen müssen. Diese Struktur ist bindend, weil Task 5 direkt darauf zugreift:

```java
// BlueMapMapSpec
Source source = new Source();                 // sourceRef(Ref), world(String), dimension(String)
Trigger trigger = new Trigger();              // onNewBundle(boolean), schedule(String),
                                              // concurrencyPolicy(String, Default "Forbid")
BlueMapSettings bluemap = new BlueMapSettings();  // version(String), minecraftVersion(String),
                                                  // configOverrides(Map<String,String>)
Storage storage = new Storage();              // bucketClaim(String, Default "auto"), prefix(String)
Resources resources = new Resources();        // cpu(String), memory(String)
int shards = 1;                               // > 1 erst ab Phase 4
int historyLimit = 10;
boolean purgeOnDelete = false;                // §9.4: Löschen vernichtet keine Renderarbeit

// BlueMapMapStatus
Bucket bucket = new Bucket();                 // name(String), endpoint(String), secretName(String)
LatestRender latestRender = new LatestRender(); // name(String), phase(String)
List<Condition> conditions = new ArrayList<>();

// Ref (im Paket api, von mehreren Specs genutzt)
String name;                                  // absichtlich ohne namespace-Feld:
                                              // §10.1 verbietet Referenzen über Namespace-Grenzen
```

`Ref` bewusst ohne Namespace-Feld: Die Mandantentrennung aus §10.1 der Spec verlangt, dass eine CR nur Ressourcen ihres eigenen Namespace referenziert. Was es nicht gibt, kann auch nicht falsch gesetzt werden.

`BlueMapRenderSpec` (Task 5) analog: `Ref mapRef`, `String bundleUrl`, `String bundleVersion`, `boolean force`.
`BlueMapRenderStatus`: `String phase`, `Progress progress` (percent, currentMap, etaSeconds, degraded), `String jobName`, `String startTime`, `String completionTime`, `List<Condition> conditions`.

`BlueMapConfigBuilder.java`:

```java
package net.onelitefeather.apus.operator.map;

import java.util.LinkedHashMap;
import java.util.Map;
import net.onelitefeather.apus.operator.api.BlueMapMap;

/**
 * Generates the complete BlueMap configuration for a map.
 *
 * <p>Nobody writes HOCON by hand — that is the point of Apus. Credentials are
 * deliberately absent: they come from the Rook-managed Secret as environment
 * variables, because a ConfigMap is readable by anything in the namespace.
 */
public final class BlueMapConfigBuilder {

    private BlueMapConfigBuilder() {}

    public record BucketBinding(String bucketName, String endpoint, String region) {}

    public static Map<String, String> build(BlueMapMap map, BucketBinding binding) {
        Map<String, String> files = new LinkedHashMap<>();
        String mapId = map.getMetadata().getName();

        files.put(
                "core.conf",
                """
                accept-download: true
                data: "/work/data"
                render-thread-count: %d
                metrics: false
                scan-for-mod-resources: false
                """
                        .formatted(renderThreads(map)));

        files.put(
                "maps/" + mapId + ".conf",
                """
                world: "/work/world"
                dimension: "%s"
                name: "%s"
                sorting: 0
                storage: "s3"
                render-edges: true
                """
                        .formatted(map.getSpec().getSource().getDimension(), mapId));

        // No credentials here: the runner's entrypoint fills them in from the
        // environment before starting BlueMap.
        files.put(
                "storages/s3.conf",
                """
                storage-type: "themeinerlp:s3"
                bucket-name: "%s"
                region: "%s"
                endpoint-url: "%s"
                compression: "gzip"
                root-path: "%s"
                force-path-style: true
                """
                        .formatted(
                                binding.bucketName(),
                                binding.region(),
                                binding.endpoint(),
                                map.getSpec().getStorage().getPrefix()));

        return files;
    }

    private static int renderThreads(BlueMapMap map) {
        return 2;
    }
}
```

- [ ] **Step 4: Test ausführen und Erfolg prüfen**

Run: `./gradlew :operator:test --tests '*BlueMapConfigBuilderTest*'`
Expected: PASS (4 Tests)

- [ ] **Step 5: Den fehlschlagenden Test für die Bucket-Provisionierung schreiben**

```java
package net.onelitefeather.apus.operator.map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.server.mock.EnableKubernetesMockClient;
import java.util.Optional;
import net.onelitefeather.apus.operator.OperatorConfig;
import net.onelitefeather.apus.operator.api.BlueMapMap;
import net.onelitefeather.apus.operator.rook.ObjectBucketClaim;
import org.junit.jupiter.api.Test;

@EnableKubernetesMockClient(crud = true)
class BucketProvisionerTest {

    KubernetesClient client;

    private BlueMapMap map() {
        BlueMapMap map = new BlueMapMap();
        map.setMetadata(new ObjectMetaBuilder().withName("survival-overworld")
                .withNamespace("bluemap-friends").build());
        return map;
    }

    @Test
    void createsAClaimInTheTenantNamespaceNotTheRookNamespace() {
        BucketProvisioner provisioner = new BucketProvisioner(client, OperatorConfig.defaults());

        provisioner.ensureBucket(map(), "apus-friends");

        ObjectBucketClaim claim = client.resources(ObjectBucketClaim.class)
                .inNamespace("bluemap-friends")
                .withName("survival-overworld")
                .get();

        // Rook writes the credentials Secret into the claim's namespace, so the claim
        // must live where the render job runs — not centrally in the Rook namespace.
        assertNotNull(claim, "claim must be created in the tenant namespace");
        assertEquals("ceph-bucket-fr01", claim.getSpec().getStorageClassName());
        assertEquals("apus-friends", claim.getSpec().getAdditionalConfig().get("bucketOwner"));
    }

    @Test
    void reportsNothingWhileRookIsStillProvisioning() {
        BucketProvisioner provisioner = new BucketProvisioner(client, OperatorConfig.defaults());

        Optional<ObjectBucketClaim> bound = provisioner.ensureBucket(map(), "apus-friends");

        assertTrue(bound.isEmpty(), "an unbound claim must not be reported as ready");
    }

    @Test
    void reportsTheClaimOnceRookHasBoundIt() {
        BucketProvisioner provisioner = new BucketProvisioner(client, OperatorConfig.defaults());
        provisioner.ensureBucket(map(), "apus-friends");

        ObjectBucketClaim claim = client.resources(ObjectBucketClaim.class)
                .inNamespace("bluemap-friends")
                .withName("survival-overworld")
                .get();
        claim.getStatus().setPhase("Bound");
        client.resources(ObjectBucketClaim.class).inNamespace("bluemap-friends").resource(claim).updateStatus();

        Optional<ObjectBucketClaim> bound = provisioner.ensureBucket(map(), "apus-friends");

        assertTrue(bound.isPresent(), "a bound claim must be reported");
    }
}
```

- [ ] **Step 6: `BucketProvisioner` implementieren, Test grün bekommen**

Run: `./gradlew :operator:test --tests '*BucketProvisionerTest*'`
Expected: PASS (3 Tests)

- [ ] **Step 7: Commit**

```bash
./gradlew spotlessApply
git add -A
git commit -m "feat(operator): provision map buckets through rook and build bluemap config"
```

---

### Task 5: BlueMapRender — Job-Erzeugung

**Files:**
- Create: `operator/src/main/java/net/onelitefeather/apus/operator/api/BlueMapRender.java`, `BlueMapRenderSpec.java`, `BlueMapRenderStatus.java`
- Create: `operator/src/main/java/net/onelitefeather/apus/operator/render/RenderJobBuilder.java`
- Test: `operator/src/test/java/net/onelitefeather/apus/operator/render/RenderJobBuilderTest.java`

**Interfaces:**
- Consumes: `BlueMapMap` (Task 4), `OperatorConfig` (Task 3)
- Produces:
```java
public final class RenderJobBuilder {
    public static Job build(BlueMapRender render, BlueMapMap map,
                            String bucketSecretName, String configMapName, OperatorConfig config);
}
```

Der Job muss den **Umgebungsvariablen-Vertrag aus Phase 1** exakt bedienen (§7.4 der Spec). Pflichtvariablen: `APUS_MAP_ID`, `APUS_DIMENSION`, `APUS_MC_VERSION`, `APUS_WORLD_S3_URL`, `APUS_MAP_BUCKET`, `APUS_S3_ENDPOINT`, `APUS_S3_ACCESS_KEY`, `APUS_S3_SECRET_KEY`. Fehlt eine, bricht der Container ab.

Zugangsdaten kommen über `secretKeyRef` aus dem von Rook erzeugten Secret — niemals als Klartext im Job-Manifest.

- [ ] **Step 1: Den fehlschlagenden Test schreiben**

```java
package net.onelitefeather.apus.operator.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.fabric8.kubernetes.api.model.EnvVar;
import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;
import io.fabric8.kubernetes.api.model.batch.v1.Job;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import net.onelitefeather.apus.operator.OperatorConfig;
import net.onelitefeather.apus.operator.api.BlueMapMap;
import net.onelitefeather.apus.operator.api.BlueMapRender;
import org.junit.jupiter.api.Test;

class RenderJobBuilderTest {

    private BlueMapMap map() {
        BlueMapMap map = new BlueMapMap();
        map.setMetadata(new ObjectMetaBuilder().withName("survival-overworld")
                .withNamespace("bluemap-friends").build());
        map.getSpec().getSource().setDimension("minecraft:overworld");
        map.getSpec().getBluemap().setMinecraftVersion("1.21.10");
        map.getSpec().getStorage().setPrefix("survival");
        map.getStatus().getBucket().setName("apus-friends-survival");
        map.getStatus().getBucket().setEndpoint("http://rgw.example.svc:80");
        return map;
    }

    private BlueMapRender render() {
        BlueMapRender render = new BlueMapRender();
        render.setMetadata(new ObjectMetaBuilder().withName("render-abc")
                .withNamespace("bluemap-friends").build());
        render.getSpec().getMapRef().setName("survival-overworld");
        render.getSpec().setBundleUrl("s3://bundles/worlds/friends/survival/v1/overworld");
        return render;
    }

    private Map<String, EnvVar> envOf(Job job) {
        List<EnvVar> env = job.getSpec().getTemplate().getSpec().getContainers().get(0).getEnv();
        return env.stream().collect(Collectors.toMap(EnvVar::getName, Function.identity()));
    }

    @Test
    void suppliesEveryMandatoryEnvironmentVariable() {
        Job job = RenderJobBuilder.build(render(), map(), "bucket-secret", "map-config",
                OperatorConfig.defaults());

        Map<String, EnvVar> env = envOf(job);

        // The runner image exits non-zero if any of these is missing.
        for (String required : List.of("APUS_MAP_ID", "APUS_DIMENSION", "APUS_MC_VERSION",
                "APUS_WORLD_S3_URL", "APUS_MAP_BUCKET", "APUS_S3_ENDPOINT",
                "APUS_S3_ACCESS_KEY", "APUS_S3_SECRET_KEY")) {
            assertNotNull(env.get(required), "missing mandatory variable " + required);
        }
        assertEquals("survival-overworld", env.get("APUS_MAP_ID").getValue());
        assertEquals("1.21.10", env.get("APUS_MC_VERSION").getValue());
    }

    @Test
    void takesCredentialsFromTheSecretRatherThanInliningThem() {
        Job job = RenderJobBuilder.build(render(), map(), "bucket-secret", "map-config",
                OperatorConfig.defaults());

        Map<String, EnvVar> env = envOf(job);

        assertNotNull(env.get("APUS_S3_ACCESS_KEY").getValueFrom(),
                "credentials must come from a secretKeyRef");
        assertEquals("bucket-secret",
                env.get("APUS_S3_ACCESS_KEY").getValueFrom().getSecretKeyRef().getName());
        assertEquals(null, env.get("APUS_S3_SECRET_KEY").getValue(),
                "the secret must never appear as a literal value in the job manifest");
    }

    @Test
    void doesNotRestartTheJobEndlessly() {
        Job job = RenderJobBuilder.build(render(), map(), "bucket-secret", "map-config",
                OperatorConfig.defaults());

        assertNotNull(job.getSpec().getBackoffLimit(), "a render must not retry forever");
        assertTrue(job.getSpec().getBackoffLimit() <= 6, "backoff limit unexpectedly high");
        assertEquals("Never", job.getSpec().getTemplate().getSpec().getRestartPolicy());
    }

    @Test
    void isOwnedByTheRenderResourceSoItIsGarbageCollected() {
        Job job = RenderJobBuilder.build(render(), map(), "bucket-secret", "map-config",
                OperatorConfig.defaults());

        assertTrue(job.getMetadata().getOwnerReferences().stream()
                        .anyMatch(ref -> "BlueMapRender".equals(ref.getKind())),
                "job must be owned by its BlueMapRender");
    }
}
```

- [ ] **Step 2: Test ausführen, Fehlschlag prüfen, `RenderJobBuilder` implementieren**

Run: `./gradlew :operator:test --tests '*RenderJobBuilderTest*'`
Expected: zunächst FAIL, nach der Implementierung PASS (4 Tests)

- [ ] **Step 3: Commit**

```bash
./gradlew spotlessApply
git add -A
git commit -m "feat(operator): build render jobs against the phase 1 env contract"
```

---

### Task 6: Render-Reconciler mit Fortschritt und Nebenläufigkeitssperre

**Files:**
- Create: `operator/src/main/java/net/onelitefeather/apus/operator/render/BlueMapRenderReconciler.java`
- Create: `operator/src/main/java/net/onelitefeather/apus/operator/render/ProgressPoller.java`
- Test: `operator/src/test/java/net/onelitefeather/apus/operator/render/ProgressPollerTest.java`
- Test: `operator/src/test/java/net/onelitefeather/apus/operator/render/BlueMapRenderReconcilerTest.java`

**Interfaces:**
- Consumes: `RenderJobBuilder` (Task 5), `BlueMapMap` (Task 4)
- Produces:
```java
public final class ProgressPoller {
    /** Parses the /progress payload the telemetry addon serves. */
    public static Optional<RenderProgress> parse(String json);
    public record RenderProgress(String state, String currentMap, double progress,
                                 long etaSeconds, boolean degraded) {}
}
```

Zwei Verhaltensweisen sind hier entscheidend und in der Spec begründet:
- **`concurrencyPolicy: Forbid` ist Default** (§7.3): Zwei gleichzeitige Renders auf denselben Map-Storage können die Karte inkonsistent hinterlassen. Der Reconciler startet keinen Job, solange ein anderer `BlueMapRender` derselben Map in einer aktiven Phase steht.
- **Ein überschrittenes Speicherlimit wird nicht wiederholt** (§12): Die Condition `StorageQuotaExceeded` beendet den Render endgültig, statt endlos gegen die Wand zu laufen.

- [ ] **Step 1: Den fehlschlagenden Test für den Fortschritts-Parser schreiben**

Das JSON-Format stammt aus Phase 1 und ist dort durch einen Contract-Test abgesichert.

```java
package net.onelitefeather.apus.operator.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import org.junit.jupiter.api.Test;

class ProgressPollerTest {

    @Test
    void parsesARunningRender() {
        String json = """
                {"state":"rendering","currentMap":"overworld","progress":0.72232,\
                "etaSeconds":28,"queuedTasks":-1,"renderThreads":-1,"degraded":false,\
                "description":"updating map 'overworld'"}""";

        Optional<ProgressPoller.RenderProgress> parsed = ProgressPoller.parse(json);

        assertTrue(parsed.isPresent());
        assertEquals("rendering", parsed.get().state());
        assertEquals("overworld", parsed.get().currentMap());
        assertEquals(0.72232, parsed.get().progress(), 1e-6);
        assertEquals(28L, parsed.get().etaSeconds());
        assertFalse(parsed.get().degraded());
    }

    @Test
    void parsesADegradedResponseWithoutFailing() {
        String json = """
                {"state":"unknown","currentMap":null,"progress":-1,"etaSeconds":-1,\
                "queuedTasks":-1,"renderThreads":-1,"degraded":true,"description":"no plugin"}""";

        Optional<ProgressPoller.RenderProgress> parsed = ProgressPoller.parse(json);

        assertTrue(parsed.isPresent());
        assertTrue(parsed.get().degraded());
        assertEquals(-1.0, parsed.get().progress(), 1e-9);
    }

    @Test
    void returnsEmptyForGarbageInsteadOfThrowing() {
        // The pod may be starting up, or something else may answer on that port.
        assertTrue(ProgressPoller.parse("not json at all").isEmpty());
        assertTrue(ProgressPoller.parse("").isEmpty());
    }
}
```

- [ ] **Step 2: Test ausführen, Fehlschlag prüfen, `ProgressPoller.parse` implementieren**

Run: `./gradlew :operator:test --tests '*ProgressPollerTest*'`
Expected: zunächst FAIL, danach PASS (3 Tests)

- [ ] **Step 3: Den fehlschlagenden Test für den Reconciler schreiben**

```java
package net.onelitefeather.apus.operator.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.server.mock.EnableKubernetesMockClient;
import net.onelitefeather.apus.operator.OperatorConfig;
import net.onelitefeather.apus.operator.api.BlueMapRender;
import org.junit.jupiter.api.Test;

@EnableKubernetesMockClient(crud = true)
class BlueMapRenderReconcilerTest {

    KubernetesClient client;

    private BlueMapRender render(String name) {
        BlueMapRender render = new BlueMapRender();
        render.setMetadata(new ObjectMetaBuilder().withName(name).withNamespace("bluemap-friends").build());
        render.getSpec().getMapRef().setName("survival-overworld");
        render.getSpec().setBundleUrl("s3://bundles/w/v1/overworld");
        return render;
    }

    @Test
    void refusesToStartASecondRenderForTheSameMap() {
        // Two writers on the same map storage can leave the map inconsistent,
        // which is why Forbid is the default concurrency policy.
        BlueMapRenderReconciler reconciler = new BlueMapRenderReconciler(client, OperatorConfig.defaults());

        BlueMapRender first = render("render-1");
        reconciler.reconcile(first, null);

        BlueMapRender second = render("render-2");
        reconciler.reconcile(second, null);

        assertEquals("Pending", second.getStatus().getPhase());
        assertNull(client.batch().v1().jobs().inNamespace("bluemap-friends").withName("render-2").get(),
                "no second job may be created while the first is active");
    }

    @Test
    void doesNotRetryWhenTheStorageQuotaIsExceeded() {
        BlueMapRenderReconciler reconciler = new BlueMapRenderReconciler(client, OperatorConfig.defaults());
        BlueMapRender render = render("render-quota");

        reconciler.onQuotaExceeded(render, "bucket full");

        assertEquals("Failed", render.getStatus().getPhase());
        assertNotNull(render.getStatus().getConditions().stream()
                .filter(c -> "StorageQuotaExceeded".equals(c.getReason()))
                .findFirst()
                .orElse(null),
                "a quota failure must be visible as its own condition and must not be retried");
    }
}
```

- [ ] **Step 4: Reconciler implementieren, Tests grün bekommen**

Run: `./gradlew :operator:test --tests '*BlueMapRenderReconciler*'`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
./gradlew spotlessApply
git add -A
git commit -m "feat(operator): reconcile renders with progress and a concurrency lock"
```

---

### Task 7: Operator-Einstiegspunkt

**Files:**
- Create: `operator/src/main/java/net/onelitefeather/apus/operator/ApusOperator.java`
- Test: `operator/src/test/java/net/onelitefeather/apus/operator/ApusOperatorTest.java`

**Interfaces:**
- Consumes: alle Reconciler
- Produces: ausführbare Hauptklasse; `OperatorConfig` aus Umgebungsvariablen

Für Micronaut gibt es keine JOSDK-Integration. Der Operator wird deshalb selbst gebaut und gestartet; Micronaut liefert nur Konfiguration und Health, falls es später gebraucht wird. Für Phase 2a genügt eine schlanke `main`-Methode — das vermeidet eine Abhängigkeit, die nichts trägt.

```java
Operator operator = new Operator(o -> o.withKubernetesClient(client));
operator.register(new TenantReconciler(client, config));
operator.register(new BlueMapMapReconciler(client, config));
operator.register(new BlueMapRenderReconciler(client, config));
operator.start();
```

- [ ] **Step 1: Test schreiben, der die Konfiguration aus der Umgebung prüft**

```java
package net.onelitefeather.apus.operator;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Map;
import org.junit.jupiter.api.Test;

class ApusOperatorTest {

    @Test
    void readsClusterSpecificSettingsFromTheEnvironment() {
        Map<String, String> env = Map.of(
                "APUS_ROOK_NAMESPACE", "rook-ceph-other",
                "APUS_CEPH_OBJECT_STORE", "other-s3",
                "APUS_BUCKET_STORAGE_CLASS", "other-bucket",
                "APUS_RUNNER_IMAGE", "registry.example/apus/runner:1.2.3");

        OperatorConfig config = OperatorConfig.fromEnvironment(env::get);

        assertEquals("rook-ceph-other", config.rookNamespace());
        assertEquals("registry.example/apus/runner:1.2.3", config.runnerImage());
    }

    @Test
    void fallsBackToTheClusterDefaults() {
        OperatorConfig config = OperatorConfig.fromEnvironment(name -> null);

        assertEquals("rook-ceph-fr01", config.rookNamespace());
        assertEquals("feather-s3", config.cephObjectStore());
    }
}
```

- [ ] **Step 2: Implementieren, Tests grün bekommen, committen**

```bash
./gradlew spotlessApply
git add -A
git commit -m "feat(operator): add the operator entrypoint"
```

---

### Task 8: Integrationstest gegen einen echten Cluster

**Files:**
- Create: `operator/src/test/java/net/onelitefeather/apus/operator/OperatorIntegrationTest.java`
- Modify: `operator/build.gradle.kts` (eigene `integrationTest`-Task, wie im `runner`-Modul)

Die Container-Tests des `runner`-Moduls sind bewusst aus `build` herausgelöst. Halte es hier genauso.

Der Test startet einen k3s-Container über Testcontainers, wendet die generierten CRDs an, legt einen `Tenant` an und prüft, dass Namespace und Quota entstehen.

- [ ] **Step 1: Testcontainers-k3s-Abhängigkeit ergänzen**

In `settings.gradle.kts`: `library("testcontainers.k3s", "org.testcontainers", "k3s").withoutVersion()`

- [ ] **Step 2: Den Integrationstest schreiben**

```java
package net.onelitefeather.apus.operator;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.fabric8.kubernetes.client.Config;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import net.onelitefeather.apus.operator.api.Tenant;
import net.onelitefeather.apus.operator.tenant.TenantReconciler;
import org.junit.jupiter.api.Test;
import org.testcontainers.k3s.K3sContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Proves the CRDs apply cleanly to a real Kubernetes API server and that reconciling a
 * Tenant produces the namespace and quota. The mock server cannot catch schema errors —
 * only a real API server validates the generated CRD.
 */
class OperatorIntegrationTest {

    @Test
    void appliesGeneratedCrdsAndReconcilesATenant() throws Exception {
        try (K3sContainer k3s = new K3sContainer(DockerImageName.parse("rancher/k3s:v1.31.2-k3s1"))) {
            k3s.start();

            Config config = Config.fromKubeconfig(k3s.getKubeConfigYaml());
            try (KubernetesClient client = new KubernetesClientBuilder().withConfig(config).build()) {

                Path crdDir = Path.of(System.getProperty("apus.crd.dir", "build/crds"));
                try (var files = Files.list(crdDir)) {
                    files.filter(p -> p.toString().endsWith(".yml") || p.toString().endsWith(".yaml"))
                            .forEach(p -> client.load(toStream(p)).serverSideApply());
                }

                // Wait for the API server to accept the new kind.
                long deadline = System.currentTimeMillis() + Duration.ofMinutes(1).toMillis();
                boolean known = false;
                while (System.currentTimeMillis() < deadline && !known) {
                    known = client.apiextensions().v1().customResourceDefinitions()
                            .list().getItems().stream()
                            .anyMatch(crd -> "tenants.bluemap.onelitefeather.net".equals(crd.getMetadata().getName()));
                    if (!known) Thread.sleep(1000);
                }
                assertTrue(known, "Tenant CRD must be registered");

                Tenant tenant = new Tenant();
                tenant.setMetadata(new io.fabric8.kubernetes.api.model.ObjectMetaBuilder()
                        .withName("itest").build());
                tenant.getSpec().setDisplayName("itest");
                tenant.getSpec().getStorage().setQuota("10Gi");
                client.resources(Tenant.class).resource(tenant).create();

                new TenantReconciler(client, OperatorConfig.defaults()).reconcile(tenant, null);

                assertNotNull(client.namespaces().withName("bluemap-itest").get(),
                        "reconciling a tenant must create its namespace");
            }
        }
    }

    private static java.io.InputStream toStream(Path path) {
        try {
            return Files.newInputStream(path);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
```

Der `CephObjectStoreUser`-Teil schlägt auf k3s fehl, weil Rook dort nicht installiert ist. Fange das im Reconciler sauber ab (fehlende CRD ist kein Absturz, sondern eine Condition) oder überspringe diesen Teil im Integrationstest mit einer klaren Begründung im Code.

- [ ] **Step 3: `integrationTest`-Task einrichten und Test grün bekommen**

Run: `./gradlew :operator:integrationTest`
Expected: PASS

- [ ] **Step 4: Commit**

```bash
./gradlew spotlessApply
git add -A
git commit -m "test(operator): verify crds and tenant reconciliation on a real cluster"
```

---

## Abschluss Phase 2a

Danach gilt: Ein `kubectl apply` eines `Tenant` erzeugt Namespace, Quota und Ceph-User; eine `BlueMapMap` erzeugt Bucket und Konfiguration; ein `BlueMapRender` startet einen Job mit dem Runner-Image aus Phase 1 und führt dessen Fortschritt im Status mit.

**Nicht Teil von 2a** (folgt in Phase 2b): `WorldSource`, `WorldIngest` und der ETL-Layer mit seinen Connectoren. Bis dahin wird `BlueMapRender.spec.bundleUrl` direkt gesetzt, statt aus einem Bundle-Manifest aufgelöst zu werden.

**Nicht Teil von Phase 2** (folgt in Phase 3): `BlueMapHosting`.
