# Apus Phase 2a — Operator and Render Path: Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A Kubernetes operator that manages `Tenant`, `BlueMapMap`, and `BlueMapRender`: creating tenant namespaces with quotas, provisioning S3 buckets through Rook, generating BlueMap configuration, and starting render jobs with the runner image from Phase 1 — including progress tracking in the custom resource's status.

**Architecture:** Java 25 with Java Operator SDK 5.5.1 on Fabric8 client 7.8.0. Micronaut only supplies DI, configuration, and health; the operator itself is bootstrapped through a `StartupEvent` listener, since there is no JOSDK integration for Micronaut. CRDs are generated from the Java classes at build time (`crd-generator-api-v2` in a dedicated Gradle task). S3 is not managed by us directly but delegated to Rook: the operator creates a `CephObjectStoreUser` and an `ObjectBucketClaim` and waits for the Secrets that Rook produces.

**Tech Stack:** Java 25, Gradle 9.4.1, JOSDK 5.5.1, Fabric8 7.8.0, Micronaut, JUnit Jupiter, Fabric8 `KubernetesMockServer`, Testcontainers (k3s).

## Global Constraints

- **Java toolchain 25**, same as the existing `telemetry-addon` module. JOSDK compiles against Java 17 but runs on 25.
- **Exact coordinates** (verified against Maven Central):
  - `io.javaoperatorsdk:operator-framework:5.5.1`
  - `io.javaoperatorsdk:operator-framework-junit:5.5.1` — **not** `operator-framework-junit-5`, which is frozen at 5.2.5
  - `io.fabric8:crd-generator-api-v2:7.8.0` and `io.fabric8:crd-generator-collector:7.8.0`
  - `io.fabric8:kubernetes-junit-jupiter:7.8.0` (mock server)
  - The Fabric8 client comes in transitively through JOSDK at 7.8.0 — do not pin it separately, or it will drift.
- **Do not use:** `io.fabric8:crd-generator-apt` (deprecated since 7.0.0) and `io.fabric8.crd.generator.CRDGenerator` (v1, deprecated). The v2 route is `io.fabric8.crdv2.generator.CRDGenerator`.
- **API group:** `bluemap.onelitefeather.net`, version `v1alpha1`.
- **Java base package:** `net.onelitefeather.apus.operator`.
- **The operator works strictly namespace-local.** A namespaced CR may only reference resources in its own namespace. Cross-namespace references are rejected during validation — this is the tenant separation from §10.1 of the spec.
- **Credentials must never appear** in CR status, events, or logs (§12 of the spec).
- **Deletion behaviour:** Deleting a `BlueMapMap` does not delete any data. Only when `spec.purgeOnDelete: true` does a finalizer clean up (§9.4 of the spec).
- AGPL header via Spotless, Conventional Commits, **no** Claude attribution, identifiers and Javadoc in English.

### Verified JOSDK facts

```java
// Building the operator — Operator(KubernetesClient) is package-private!
Operator operator = new Operator(o -> o.withKubernetesClient(client));
RegisteredController<?> c = operator.register(reconciler);   // throws OperatorException
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

// Custom Resource — without `implements Namespaced` it is cluster-scoped.
// The status subresource is active as soon as a status type is given as the second parameter.
@Group("bluemap.onelitefeather.net")
@Version("v1alpha1")
@Kind("BlueMapMap")
@Plural("bluemapmaps")
@ShortNames("bmmap")
public class BlueMapMap extends CustomResource<BlueMapMapSpec, BlueMapMapStatus>
        implements Namespaced {}
```

### Verified Rook resources

From the existing cluster (`Kubernetes-FLUX`):

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
  quotas: { maxSize: 500Gi, maxObjects: 5000000 }   # §10.2 of the spec
```

For the `ObjectBucketClaim`, Rook creates a Secret (`AWS_ACCESS_KEY_ID`, `AWS_SECRET_ACCESS_KEY`) and a ConfigMap (`BUCKET_HOST`, `BUCKET_NAME`, `BUCKET_PORT`) in the **same namespace**, each named after the claim.

Neither CRD has a ready-made Java model. We define our own lean `CustomResource` classes with exactly the fields we need — type-safe, because the reconciler has to evaluate the provisioning status.

---

## File Structure

```text
operator/
├── build.gradle.kts                      JOSDK, CRD-Generierung, Micronaut
└── src/
    ├── main/java/net/onelitefeather/apus/operator/
    │   ├── ApusOperator.java             StartupEvent listener, registers reconcilers
    │   ├── api/                          Custom Resources (plain data classes)
    │   │   ├── Tenant.java  TenantSpec.java  TenantStatus.java
    │   │   ├── BlueMapMap.java  BlueMapMapSpec.java  BlueMapMapStatus.java
    │   │   ├── BlueMapRender.java  BlueMapRenderSpec.java  BlueMapRenderStatus.java
    │   │   └── Conditions.java           Shared condition helpers
    │   ├── rook/                          Foreign CRDs, modelled leanly
    │   │   ├── ObjectBucketClaim.java  ObjectBucketClaimSpec.java  ObjectBucketClaimStatus.java
    │   │   └── CephObjectStoreUser.java  CephObjectStoreUserSpec.java  CephObjectStoreUserStatus.java
    │   ├── tenant/TenantReconciler.java
    │   ├── map/
    │   │   ├── BlueMapMapReconciler.java
    │   │   ├── BucketProvisioner.java    Creates the OBC, waits for Secret/ConfigMap
    │   │   └── BlueMapConfigBuilder.java Generates core.conf / maps/*.conf / storages/s3.conf
    │   ├── render/
    │   │   ├── BlueMapRenderReconciler.java
    │   │   ├── RenderJobBuilder.java     Builds the k8s Job from the runner image
    │   │   └── ProgressPoller.java       Reads /progress from the pod, fills the status
    │   └── schedule/RenderScheduler.java Cron and onNewBundle → creates a BlueMapRender
    └── test/java/net/onelitefeather/apus/operator/…
```

**Why this layout:** The classes under `api/` are pure data holders with no logic and no Kubernetes access — they are the interface that Phase 5 (API/UI) also uses. `BlueMapConfigBuilder` and `RenderJobBuilder` are pure functions from CR to Kubernetes object and therefore testable without a cluster; only the reconcilers need a client.

---

## Parallelization

The cut is deliberately designed to make the middle parallelizable.
The trick: **all data classes are created up front, in Task 2.** As long as data classes and
the logic that uses them sit in the same task, everything depends on everything — pulled
forward, the three follow-on tasks touch completely disjoint files.

| Group | Tasks | Execution |
| --- | --- | --- |
| A | Task 1 — module and CRD generation | sequential (foundation) |
| B | Task 2 — complete data model | sequential (everything builds on it) |
| C | Task 3, Task 4, Task 5 | **parallel**, each in its own worktree |
| D | Task 6 — render reconciler | sequential (needs Task 5) |
| E | Task 7 — entrypoint | sequential (wires up all reconcilers) |
| F | Task 8 — integration test | sequential |

**Why Task 6 and 7 don't run alongside the rest:** Task 6 builds on the signature of
`RenderJobBuilder` from Task 5, and Task 7 wires up every reconciler. Built in parallel,
both would have to program against interfaces that are still changing — the rework would
eat the time saved right back up.

**Files of the parallel Group C** (verifiably disjoint):

- Task 3: `tenant/TenantReconciler.java` + its test
- Task 4: `map/BucketProvisioner.java`, `map/BlueMapConfigBuilder.java` + tests
- Task 5: `render/RenderJobBuilder.java` + test

None of the three tasks changes a file belonging to another, or the build files.

---

### Task 1: Operator module and CRD generation

**Files:**

- Modify: `settings.gradle.kts` (module `operator` and new catalog entries)
- Create: `operator/build.gradle.kts`
- Create: `operator/src/main/java/net/onelitefeather/apus/operator/api/Tenant.java` (minimal version, so there's something to generate from)
- Create: `operator/src/main/java/net/onelitefeather/apus/operator/api/TenantSpec.java`
- Create: `operator/src/main/java/net/onelitefeather/apus/operator/api/TenantStatus.java`
- Test: `operator/src/test/java/net/onelitefeather/apus/operator/CrdGenerationTest.java`

**Interfaces:**

- Consumes: nothing
- Produces: catalog aliases `libs.josdk`, `libs.josdk.junit`, `libs.crd.generator.api.v2`, `libs.crd.generator.collector`, `libs.fabric8.junit`; the Gradle task `generateCrds`, which writes YAML to `operator/build/crds/`; the class `net.onelitefeather.apus.operator.api.Tenant`

- [ ] **Step 1: Add the catalog entries**

Add to the `versionCatalogs` block in `settings.gradle.kts`:

```kotlin
            version("josdk", "5.5.1")
            version("fabric8", "7.8.0")

            library("josdk", "io.javaoperatorsdk", "operator-framework").versionRef("josdk")
            library("josdk.junit", "io.javaoperatorsdk", "operator-framework-junit").versionRef("josdk")
            library("crd.generator.api.v2", "io.fabric8", "crd-generator-api-v2").versionRef("fabric8")
            library("crd.generator.collector", "io.fabric8", "crd-generator-collector").versionRef("fabric8")
            library("fabric8.junit", "io.fabric8", "kubernetes-junit-jupiter").versionRef("fabric8")
```

And extend the include line:

```kotlin
include("telemetry-addon", "runner", "operator")
```

- [ ] **Step 2: Create the custom resource so the generator has something to find**

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

`api/Tenant.java` — note: **no** `implements Namespaced`, because `Tenant` is cluster-scoped (§8.1 of the spec):

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

- [ ] **Step 3: Write `operator/build.gradle.kts`**

The route through `crd-generator-api-v2` is the one Fabric8 recommends; the earlier annotation processor has been deprecated since 7.0.0.

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

// Separate configuration for the generator, so its dependencies don't
// end up on the operator's runtime classpath.
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

> **To verify in Step 5:** The generator CLI's main class name (`io.fabric8.crdv2.generator.cli.CRDGeneratorCLI`) and its argument names come from research, not from an actual run. If the invocation doesn't work, determine the real entry point class from the jar and correct both plan and build:
>
> ```bash
> ./gradlew :operator:dependencies --configuration crdGenerator | grep crd-generator
> unzip -l ~/.gradle/caches/modules-2/files-2.1/io.fabric8/crd-generator-api-v2/7.8.0/*/crd-generator-api-v2-7.8.0.jar | grep -iE "cli|Main"
> ```
>
> Alternatively, the programmatic route always works: a small Java class in `buildSrc`, or a `JavaExec` task pointed at your own generator main class that calls `new CRDGenerator().customResourceClasses(...).inOutputDir(dir).detailedGenerate()`. Pick whichever route actually works and document it.

- [ ] **Step 4: Write the failing test**

This test verifies that the generation actually ran and produced a CRD with the expected properties — in particular `scope: Cluster`, the most common mistake with `Tenant`.

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

So the test can find the directory, add to `operator/build.gradle.kts`:

```kotlin
tasks.test {
    dependsOn(generateCrds)
    systemProperty("apus.crd.dir", crdOutputDir.get().asFile.absolutePath)
}
```

- [ ] **Step 5: Run the test and confirm it fails**

Run: `./gradlew :operator:test`
Expected: FAIL — either because the generator task doesn't start (wrong main class name, see the note in Step 3) or because no CRD has been generated yet.

Work through the note from Step 3 until generation runs.

- [ ] **Step 6: Run the test and confirm it passes**

Run: `./gradlew :operator:test`
Expected: PASS (3 tests)

Take a look at the generated YAML, so you know what the operator ships:

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

### Task 2: Complete data model

This task creates **all** the data classes that the parallel Group C needs — Rook models,
the two remaining custom resources, the shared helper classes, and the operational
configuration. After this, Task 3, 4, and 5 no longer touch any shared file.

All classes here are pure data holders with no Kubernetes access and no logic. They are
also the interface that Phase 5 (API and UI) will later reuse.

**Files:**

- Create: `operator/src/main/java/net/onelitefeather/apus/operator/rook/ObjectBucketClaim.java`
- Create: `operator/src/main/java/net/onelitefeather/apus/operator/rook/ObjectBucketClaimSpec.java`
- Create: `operator/src/main/java/net/onelitefeather/apus/operator/rook/ObjectBucketClaimStatus.java`
- Create: `operator/src/main/java/net/onelitefeather/apus/operator/rook/CephObjectStoreUser.java`
- Create: `operator/src/main/java/net/onelitefeather/apus/operator/rook/CephObjectStoreUserSpec.java`
- Create: `operator/src/main/java/net/onelitefeather/apus/operator/rook/CephObjectStoreUserStatus.java`
- Create: `operator/src/main/java/net/onelitefeather/apus/operator/api/Ref.java`
- Create: `operator/src/main/java/net/onelitefeather/apus/operator/api/Conditions.java`
- Create: `operator/src/main/java/net/onelitefeather/apus/operator/api/BlueMapMap.java`, `BlueMapMapSpec.java`, `BlueMapMapStatus.java`
- Create: `operator/src/main/java/net/onelitefeather/apus/operator/api/BlueMapRender.java`, `BlueMapRenderSpec.java`, `BlueMapRenderStatus.java`
- Create: `operator/src/main/java/net/onelitefeather/apus/operator/OperatorConfig.java`
- Test: `operator/src/test/java/net/onelitefeather/apus/operator/rook/RookResourceSerialisationTest.java`
- Test: `operator/src/test/java/net/onelitefeather/apus/operator/api/ApusResourceTest.java`
- Test: `operator/src/test/java/net/onelitefeather/apus/operator/OperatorConfigTest.java`
- Modify: `operator/src/test/java/net/onelitefeather/apus/operator/CrdGenerationTest.java` (assertions for the two new CRDs)

**Interfaces:**

- Consumes: `Tenant` and the CRD generation from Task 1
- Produces:

```java
// Both are namespaced.
ObjectBucketClaim:  spec.bucketName, spec.storageClassName,
                    spec.additionalConfig (Map<String,String>, incl. "bucketOwner")
                    status.phase   // "Bound", "Pending", "Failed"
CephObjectStoreUser: spec.store, spec.displayName,
                     spec.quotas.maxSize, spec.quotas.maxObjects, spec.quotas.maxBuckets
                     status.phase
```

The Rook classes must **not** end up in our CRD generation — they model foreign CRDs that Rook brings along. The generator scans for `@Group`, so they need to be kept out via the exclusion in `CrdGeneratorMain`, or a package restriction. Task 1 already has an assertion for this (`generatesNoForeignCrds`) — it must stay green.

The following are also created here:

```java
// api/Ref.java — deliberately WITHOUT a namespace field.
// §10.1 of the spec forbids references across namespace boundaries; what
// doesn't exist can't be set wrong either.
public class Ref { String name; }

// api/Conditions.java
public static Condition ready(boolean ready, String reason, String message);
public static void set(List<Condition> conditions, Condition condition);  // replaces one of the same name

// api/BlueMapMap — namespaced, @Kind("BlueMapMap"), @Plural("bluemapmaps"), @ShortNames("bmmap")
// BlueMapMapSpec — every group initialised in the field, so nothing ever needs a null check:
Source source = new Source();                     // Ref sourceRef; String world; String dimension
Trigger trigger = new Trigger();                  // boolean onNewBundle; String schedule;
                                                  // String concurrencyPolicy = "Forbid"
BlueMapSettings bluemap = new BlueMapSettings();  // String version; String minecraftVersion;
                                                  // Map<String,String> configOverrides
Storage storage = new Storage();                  // String bucketClaim = "auto"; String prefix
Resources resources = new Resources();            // String cpu; String memory
int shards = 1;                                   // > 1 only from Phase 4 onward
int historyLimit = 10;
boolean purgeOnDelete = false;                    // §9.4: deleting must not destroy render work
// BlueMapMapStatus:
Bucket bucket = new Bucket();                     // String name; String endpoint; String secretName
LatestRender latestRender = new LatestRender();   // String name; String phase
List<Condition> conditions = new ArrayList<>();

// api/BlueMapRender — namespaced, @Kind("BlueMapRender"), @Plural("bluemaprenders"), @ShortNames("bmrender")
// BlueMapRenderSpec:
Ref mapRef = new Ref(); String bundleUrl; String bundleVersion; boolean force = false;
// BlueMapRenderStatus:
String phase;                                     // Pending|Syncing|Rendering|Finalizing|Succeeded|Failed
Progress progress = new Progress();               // double percent; String currentMap;
                                                  // long etaSeconds; boolean degraded
String jobName; String startTime; String completionTime;
List<Condition> conditions = new ArrayList<>();

// OperatorConfig — site-specific settings the operator cannot derive
public record OperatorConfig(String rookNamespace, String cephObjectStore,
                             String bucketStorageClass, String runnerImage) {
    public static OperatorConfig defaults();                             // feather-core values
    public static OperatorConfig fromEnvironment(Function<String,String> env);
}
```

Environment variables for `fromEnvironment`: `APUS_ROOK_NAMESPACE`, `APUS_CEPH_OBJECT_STORE`,
`APUS_BUCKET_STORAGE_CLASS`, `APUS_RUNNER_IMAGE`. Defaults: `rook-ceph-fr01`, `feather-s3`,
`ceph-bucket-fr01`, `apus/runner:dev`.

Why `OperatorConfig` belongs here rather than in a reconciler: all three tasks of the
parallel group need it. If it lived in one of them, three agents would create it
simultaneously and differently.

- [ ] **Step 1: Write the failing test**

The test verifies that our models produce exactly the YAML the cluster expects. It is written against the manifests that actually exist in the cluster.

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

- [ ] **Step 2: Run the test and confirm it fails**

Run: `./gradlew :operator:test --tests '*RookResourceSerialisationTest*'`
Expected: FAIL, "cannot find symbol: class ObjectBucketClaim"

- [ ] **Step 3: Implement `ObjectBucketClaim`**

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

- [ ] **Step 4: Implement `CephObjectStoreUser`**

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

- [ ] **Step 5: Run the test and confirm it passes**

Run: `./gradlew :operator:test --tests '*RookResourceSerialisationTest*'`
Expected: PASS (3 tests)

- [ ] **Step 6: Make sure the Rook models don't end up in our CRDs**

Run: `./gradlew :operator:generateCrds && ls operator/build/crds/`
Expected: only CRDs from the `bluemap.onelitefeather.net` group. If `objectbucketclaims` or `cephobjectstoreusers` show up there, restrict the generator's class selection explicitly to the `net.onelitefeather.apus.operator.api` package and add an assertion for it in `CrdGenerationTest`:

```java
    @Test
    void doesNotGenerateCrdsForForeignResources() throws IOException {
        String all = readAllCrds();

        // Rook owns these CRDs; shipping our own copy would fight with Rook's.
        assertTrue(!all.contains("objectbucket.io"), "must not generate Rook CRDs:\n" + all);
        assertTrue(!all.contains("ceph.rook.io"), "must not generate Rook CRDs:\n" + all);
    }
```

- [ ] **Step 7: Create the two remaining custom resources**

`Ref`, `Conditions`, `BlueMapMap` (+Spec, +Status), and `BlueMapRender` (+Spec, +Status) following
the field structure laid out above. Both resources are **namespaced**, so they carry
`implements Namespaced` — unlike `Tenant`.

Write `api/ApusResourceTest.java` with these assertions:

```java
    @Test
    void bothResourcesAreNamespaced() {
        // Only Tenant is cluster-scoped: it hands out a namespace and a quota.
        // Maps and renders belong to exactly one tenant and must never escape it.
        assertTrue(io.fabric8.kubernetes.api.model.Namespaced.class.isAssignableFrom(BlueMapMap.class));
        assertTrue(io.fabric8.kubernetes.api.model.Namespaced.class.isAssignableFrom(BlueMapRender.class));
    }

    @Test
    void referencesCarryNoNamespace() throws Exception {
        // §10.1: a resource may only reference things in its own namespace.
        // A namespace field on Ref would invite exactly the cross-tenant reference
        // the design forbids.
        for (java.lang.reflect.Field field : Ref.class.getDeclaredFields()) {
            assertNotEquals("namespace", field.getName(),
                    "Ref must not carry a namespace — see spec §10.1");
        }
    }

    @Test
    void specGroupsAreInitialisedSoReconcilersNeverSeeNull() {
        BlueMapMap map = new BlueMapMap();
        assertNotNull(map.getSpec().getSource());
        assertNotNull(map.getSpec().getTrigger());
        assertNotNull(map.getSpec().getStorage());
        assertNotNull(map.getStatus().getBucket());
    }

    @Test
    void concurrencyPolicyDefaultsToForbid() {
        // Two renders writing the same map storage can leave the map inconsistent (§7.3).
        assertEquals("Forbid", new BlueMapMap().getSpec().getTrigger().getConcurrencyPolicy());
    }
```

- [ ] **Step 8: Create `OperatorConfig`**

Following the signature laid out above, with `OperatorConfigTest` checking the defaults and
environment evaluation.

- [ ] **Step 9: Extend the CRD assertions**

`CrdGenerationTest` so far only checks `Tenant`. Add — using the same structured loading
method Task 1 introduced — a test each verifying that `bluemapmaps` and `bluemaprenders`
are generated and carry **`scope: Namespaced`**. This exact assertion is what made the
earlier text-comparison test worthless, and is the reason it was replaced.

Run: `./gradlew :operator:clean :operator:test`
Expected: PASS, and `operator/build/crds/` now contains three CRDs.

- [ ] **Step 10: Commit**

```bash
./gradlew spotlessApply
git add -A
git commit -m "feat(operator): add the full apus and rook data model"
```

---

### Task 3: Tenant reconciler *(parallel with Task 4 and 5)*

> This task runs concurrently with Task 4 and Task 5, in its own worktree.
> It creates **only** the files listed below. All data classes,
> `Conditions`, and `OperatorConfig` come from Task 2 and are used unchanged —
> do not recreate or modify them.

**Files:**

- Create: `operator/src/main/java/net/onelitefeather/apus/operator/tenant/TenantReconciler.java`
- Test: `operator/src/test/java/net/onelitefeather/apus/operator/tenant/TenantReconcilerTest.java`

**Interfaces:**

- Consumes (all from Task 2 or 1, to be used unchanged): `Tenant`, `TenantSpec`, `TenantStatus`, `CephObjectStoreUser`, `Conditions.ready(...)`, `Conditions.set(...)`, `OperatorConfig.defaults()`
- Produces:

```java
@ControllerConfiguration
public class TenantReconciler implements Reconciler<Tenant> {
    public TenantReconciler(KubernetesClient client, OperatorConfig config);
    public static String namespaceFor(Tenant tenant);   // "bluemap-<name>"
    public static String cephUserFor(Tenant tenant);    // "apus-<name>"
}
```

From a `Tenant`, the reconciler creates: namespace `bluemap-<name>`, a `ResourceQuota`, a `LimitRange`, and a `CephObjectStoreUser` carrying the quota.

- [ ] **Step 1: Write the failing test**

The Fabric8 mock server allows real client calls without a cluster.

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

- [ ] **Step 2: Run the test and confirm it fails**

Run: `./gradlew :operator:test --tests '*TenantReconcilerTest*'`
Expected: FAIL, "cannot find symbol: class TenantReconciler"

- [ ] **Step 3: (skipped — `OperatorConfig` and `Conditions` come from Task 2)**

The code blocks below are shown only as a reference, so you know what you're
working with. Do **not** create them again.

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

`OperatorConfig.java` in the package `net.onelitefeather.apus.operator`:

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

- [ ] **Step 4: Implement `TenantReconciler`**

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

- [ ] **Step 5: Run the test and confirm it passes**

Run: `./gradlew :operator:test --tests '*TenantReconcilerTest*'`
Expected: PASS (5 tests)

If `serverSideApply()` fails on the mock server, fall back to `createOr(NonDeletingOperation::update)` and adjust both plan and code accordingly — the mock server doesn't support every apply semantic.

- [ ] **Step 6: Commit**

```bash
./gradlew spotlessApply
git add -A
git commit -m "feat(operator): reconcile tenants into namespaces with quotas"
```

---

### Task 4: BlueMapMap — bucket and configuration *(parallel with Task 3 and 5)*

> This task runs concurrently with Task 3 and Task 5, in its own worktree.
> `BlueMapMap`, `BlueMapMapSpec`, `BlueMapMapStatus`, `ObjectBucketClaim`, and
> `OperatorConfig` come from Task 2 — use them unchanged, do not recreate them.
> Do not touch any file from Task 3 (`tenant/`) or Task 5 (`render/`).

**Files:**

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

**Important — the `s3.conf` format was verified in Phase 1** (§9.2 of the spec). Use exactly these keys:
`storage-type: "themeinerlp:s3"`, `bucket-name`, `region`, `access-key-id`, `secret-access-key`, `endpoint-url`, `compression`, `root-path`, `force-path-style`.
`core.conf` must have `accept-download: true`, or **every** render fails.

Credentials do **not** go into the ConfigMap. They are mounted into the pod as environment variables from the Secret Rook produces; the runner's entrypoint writes them into the configuration at startup. This is exactly what the environment-variable contract from Phase 1 exists for.

- [ ] **Step 1: Write the failing test for the configuration build**

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

- [ ] **Step 2: Run the test and confirm it fails**

Run: `./gradlew :operator:test --tests '*BlueMapConfigBuilderTest*'`
Expected: FAIL, "cannot find symbol"

- [ ] **Step 3: Implement the spec classes and the builder**

Private fields with getters and setters, nested static classes for groups — like `TenantSpec` in Task 1. **Every group is initialised directly in the field** (`= new Source()`), so reconcilers and tests never have to check against `null`. This structure is binding, because Task 5 accesses it directly:

```java
// BlueMapMapSpec
Source source = new Source();                 // sourceRef(Ref), world(String), dimension(String)
Trigger trigger = new Trigger();              // onNewBundle(boolean), schedule(String),
                                              // concurrencyPolicy(String, Default "Forbid")
BlueMapSettings bluemap = new BlueMapSettings();  // version(String), minecraftVersion(String),
                                                  // configOverrides(Map<String,String>)
Storage storage = new Storage();              // bucketClaim(String, Default "auto"), prefix(String)
Resources resources = new Resources();        // cpu(String), memory(String)
int shards = 1;                               // > 1 only from Phase 4 onward
int historyLimit = 10;
boolean purgeOnDelete = false;                // §9.4: deleting must not destroy render work

// BlueMapMapStatus
Bucket bucket = new Bucket();                 // name(String), endpoint(String), secretName(String)
LatestRender latestRender = new LatestRender(); // name(String), phase(String)
List<Condition> conditions = new ArrayList<>();

// Ref (in the api package, used by several specs)
String name;                                  // deliberately without a namespace field:
                                              // §10.1 forbids references across namespace boundaries
```

`Ref` deliberately has no namespace field: the tenant separation from §10.1 of the spec requires that a CR only reference resources in its own namespace. What doesn't exist can't be set wrong.

`BlueMapRenderSpec` (Task 5) analogously: `Ref mapRef`, `String bundleUrl`, `String bundleVersion`, `boolean force`.
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

- [ ] **Step 4: Run the test and confirm it passes**

Run: `./gradlew :operator:test --tests '*BlueMapConfigBuilderTest*'`
Expected: PASS (4 tests)

- [ ] **Step 5: Write the failing test for bucket provisioning**

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

- [ ] **Step 6: Implement `BucketProvisioner`, get the test green**

Run: `./gradlew :operator:test --tests '*BucketProvisionerTest*'`
Expected: PASS (3 tests)

- [ ] **Step 7: Commit**

```bash
./gradlew spotlessApply
git add -A
git commit -m "feat(operator): provision map buckets through rook and build bluemap config"
```

---

### Task 5: BlueMapRender — job creation *(parallel with Task 3 and 4)*

> This task runs concurrently with Task 3 and Task 4, in its own worktree.
> `BlueMapRender`, `BlueMapMap`, and `OperatorConfig` come from Task 2 — use them
> unchanged. Do not touch any file from Task 3 (`tenant/`) or Task 4 (`map/`).
> In particular: `BlueMapMapStatus.getBucket()` already exists and is filled in by Task 4;
> for your own test, set the values yourself.

**Files:**

- Create: `operator/src/main/java/net/onelitefeather/apus/operator/render/RenderJobBuilder.java`
- Test: `operator/src/test/java/net/onelitefeather/apus/operator/render/RenderJobBuilderTest.java`

**Interfaces:**

- Consumes (all from Task 2, unchanged): `BlueMapMap`, `BlueMapRender`, `OperatorConfig`
- Produces:

```java
public final class RenderJobBuilder {
    public static Job build(BlueMapRender render, BlueMapMap map,
                            String bucketSecretName, String configMapName, OperatorConfig config);
}
```

The job must fulfil the **environment-variable contract from Phase 1** exactly (§7.4 of the spec). Mandatory variables: `APUS_MAP_ID`, `APUS_DIMENSION`, `APUS_MC_VERSION`, `APUS_WORLD_S3_URL`, `APUS_MAP_BUCKET`, `APUS_S3_ENDPOINT`, `APUS_S3_ACCESS_KEY`, `APUS_S3_SECRET_KEY`. If any is missing, the container aborts.

Credentials come from the Secret Rook creates via `secretKeyRef` — never as plaintext in the job manifest.

- [ ] **Step 1: Write the failing test**

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

- [ ] **Step 2: Run the test, confirm it fails, implement `RenderJobBuilder`**

Run: `./gradlew :operator:test --tests '*RenderJobBuilderTest*'`
Expected: FAIL first, then PASS after the implementation (4 tests)

- [ ] **Step 3: Commit**

```bash
./gradlew spotlessApply
git add -A
git commit -m "feat(operator): build render jobs against the phase 1 env contract"
```

---

### Task 6: Render reconciler with progress and a concurrency lock

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

Two behaviours here are decisive and grounded in the spec:

- **`concurrencyPolicy: Forbid` is the default** (§7.3): two concurrent renders against the same map storage can leave the map inconsistent. The reconciler does not start a job while another `BlueMapRender` of the same map is in an active phase.
- **An exceeded storage limit is not retried** (§12): the `StorageQuotaExceeded` condition ends the render for good, instead of running endlessly into a wall.

- [ ] **Step 1: Write the failing test for the progress parser**

The JSON format comes from Phase 1, where it is secured by a contract test.

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

- [ ] **Step 2: Run the test, confirm it fails, implement `ProgressPoller.parse`**

Run: `./gradlew :operator:test --tests '*ProgressPollerTest*'`
Expected: FAIL first, then PASS (3 tests)

- [ ] **Step 3: Write the failing test for the reconciler**

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

- [ ] **Step 4: Implement the reconciler, get the tests green**

Run: `./gradlew :operator:test --tests '*BlueMapRenderReconciler*'`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
./gradlew spotlessApply
git add -A
git commit -m "feat(operator): reconcile renders with progress and a concurrency lock"
```

---

### Task 7: Operator entrypoint

**Files:**

- Create: `operator/src/main/java/net/onelitefeather/apus/operator/ApusOperator.java`
- Test: `operator/src/test/java/net/onelitefeather/apus/operator/ApusOperatorTest.java`

**Interfaces:**

- Consumes: all reconcilers
- Produces: an executable main class; `OperatorConfig` from environment variables

There is no JOSDK integration for Micronaut. The operator is therefore built and started by itself; Micronaut only supplies configuration and health, should that be needed later. For Phase 2a, a lean `main` method is enough — that avoids a dependency that would carry no weight.

```java
Operator operator = new Operator(o -> o.withKubernetesClient(client));
operator.register(new TenantReconciler(client, config));
operator.register(new BlueMapMapReconciler(client, config));
operator.register(new BlueMapRenderReconciler(client, config));
operator.start();
```

- [ ] **Step 1: Write a test that checks the configuration read from the environment**

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

- [ ] **Step 2: Implement, get the tests green, commit**

```bash
./gradlew spotlessApply
git add -A
git commit -m "feat(operator): add the operator entrypoint"
```

---

### Task 8: Integration test against a real cluster

**Files:**

- Create: `operator/src/test/java/net/onelitefeather/apus/operator/OperatorIntegrationTest.java`
- Modify: `operator/build.gradle.kts` (its own `integrationTest` task, as in the `runner` module)

The `runner` module's container tests are deliberately split out of `build`. Do the same here.

The test starts a k3s container via Testcontainers, applies the generated CRDs, creates a `Tenant`, and verifies that the namespace and quota are created.

- [ ] **Step 1: Add the Testcontainers k3s dependency**

In `settings.gradle.kts`: `library("testcontainers.k3s", "org.testcontainers", "k3s").withoutVersion()`

- [ ] **Step 2: Write the integration test**

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

The `CephObjectStoreUser` part fails on k3s because Rook isn't installed there. Catch that cleanly in the reconciler (a missing CRD is not a crash, but a condition) or skip this part in the integration test with a clear justification in the code.

- [ ] **Step 3: Set up the `integrationTest` task and get the test green**

Run: `./gradlew :operator:integrationTest`
Expected: PASS

- [ ] **Step 4: Commit**

```bash
./gradlew spotlessApply
git add -A
git commit -m "test(operator): verify crds and tenant reconciliation on a real cluster"
```

---

## Conclusion of Phase 2a

Once this is done: a `kubectl apply` of a `Tenant` creates a namespace, quota, and Ceph user; a `BlueMapMap` creates a bucket and configuration; a `BlueMapRender` starts a job with the runner image from Phase 1 and tracks its progress in the status.

**Not part of 2a** (follows in Phase 2b): `WorldSource`, `WorldIngest`, and the ETL layer with its connectors. Until then, `BlueMapRender.spec.bundleUrl` is set directly, rather than being resolved from a bundle manifest.

**Not part of Phase 2** (follows in Phase 3): `BlueMapHosting`.
