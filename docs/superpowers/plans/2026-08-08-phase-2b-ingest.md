# Apus Phase 2b — Ingest and ETL: Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Bring world data from heterogeneous sources into a single, versioned World Bundle in S3, so that the rendering path from Phase 2a can process it without knowing where it came from.

**Architecture:** Only the Extract step is source-specific; Transform (layout detection) and Load (bundle writer) are shared. A new connector therefore costs an implementation of two methods. Two new Custom Resources (`WorldSource`, `WorldIngest`) fall in line with the pattern from Phase 2a: a template produces runs. The actual ingest runs as a Kubernetes Job with its own container image, analogous to the runner from Phase 1.

**Tech Stack:** Java 25, Gradle, JOSDK 5.5.1, Fabric8 7.8.0, JUnit Jupiter, Testcontainers (MinIO), Fabric8 Mock Server.

## Global Constraints

- **Java toolchain 25**, base package `net.onelitefeather.apus.ingest` (new module `ingest`) or `net.onelitefeather.apus.operator.api` for the CRDs.
- API group `bluemap.onelitefeather.net`, version `v1alpha1`. Both new resources are **namespaced**.
- Coordinates as in Phase 2a; the Fabric8 client comes in transitively via JOSDK.
- **The bundle manifest is the contract** (§5 of the spec). It is written **last** — it is the commit point. Without a manifest, a bundle does not exist and is never rendered. This means there are no half-unpacked worlds in the rendering path.
- **Bundles are immutable.** New world data produces a new version, never a change to an existing one.
- **The region list belongs in the manifest.** It costs nothing extra during ingest, since every `.mca` file is touched anyway, and it's the basis for the sharding in Phase 4 as well as for accurate progress calculation.
- **Dimensions are named logically** (`overworld`, `the_nether`, `the_end`), regardless of whether the source had a vanilla or Bukkit layout.
- **Ownership check**: before modifying an existing resource, check whether it belongs to your own Custom Resource (name **and** UID). Foreign or unlabeled resources produce a conflict condition. This was a security finding in Phase 2a and must not happen again.
- **Shared `Labels` class** from Phase 2a for all resources created.
- Credentials never in status, events, or logs.
- AGPL header via Spotless, Conventional Commits, **no** Claude attribution, identifiers and Javadoc in English.

### What already exists from Phase 1 and 2a, and is to be used

- `net.onelitefeather.apus.operator.api.Labels`, `Conditions`, `Ref`, `OperatorConfig`
- The ownership-check pattern in `TenantReconciler` and `BlueMapMapReconciler`
- `client.supports(...)` as a check for missing foreign CRDs
- CRD generation automatically picks up new resources under `net.onelitefeather.apus.operator.api`
- `BlueMapRender.spec.bundleUrl` expects an `s3://` URL pointing to a bundle directory

---

## File Structure

```text
ingest/                                   new module, container image analogous to runner/
├── build.gradle.kts
├── Dockerfile
└── src/
    ├── main/java/net/onelitefeather/apus/ingest/
    │   ├── IngestMain.java               Job entry point
    │   ├── WorldLayout.java              Detected layout + dimension mapping
    │   ├── LayoutDetector.java           Detects vanilla / bukkit / nested
    │   ├── BundleManifest.java           Manifest data model
    │   ├── BundleWriter.java             Writes bundle to S3, manifest last
    │   ├── S3Client.java                 Narrow S3 facade (Upload, List, Head)
    │   └── connector/
    │       ├── WorldSourceConnector.java Interface: discover / fetch
    │       ├── SourceVersion.java
    │       ├── S3SourceConnector.java    Pull from a bucket prefix
    │       └── PterodactylConnector.java Pull via the panel API
    └── test/java/...

operator/src/main/java/net/onelitefeather/apus/operator/
├── api/WorldSource.java  WorldSourceSpec.java  WorldSourceStatus.java
├── api/WorldIngest.java  WorldIngestSpec.java  WorldIngestStatus.java
└── ingest/
    ├── WorldSourceReconciler.java        Poll schedule → creates WorldIngest
    ├── WorldIngestReconciler.java        creates the ingest job, tracks progress
    └── IngestJobBuilder.java             builds the job from the ingest image
```

**Why a separate module:** ingest runs as a Job in the cluster, not in the operator process. Streaming a large `tar.gz` and writing gigabytes of region files doesn't belong in an operator that manages many resources at once. `LayoutDetector`, `BundleManifest`, and the connectors are pure logic and testable without a cluster.

---

## Parallelization

The same pattern as in Phase 2a: data model first, then the follow-on tasks touch separate files.

| Group | Tasks | Execution |
| --- | --- | --- |
| A | Task 1 — Module and data model | sequential |
| B | Task 2, Task 3, Task 4 | **parallel**, each in its own worktree |
| C | Task 5 — Ingest entry point and image | sequential |
| D | Task 6 — Reconciler | sequential |
| E | Task 7 — Integration test | sequential |

**Files in the parallel group** (disjoint):

- Task 2: `LayoutDetector.java`, `WorldLayout.java` + Tests
- Task 3: `BundleManifest.java`, `BundleWriter.java`, `S3Client.java` + Tests
- Task 4: `connector/*` + Tests

---

### Task 1: Module, CRDs, and shared data model

**Files:**

- Modify: `settings.gradle.kts` (module `ingest`, catalog entries for the S3 client)
- Create: `ingest/build.gradle.kts`
- Create: `operator/src/main/java/.../api/WorldSource.java`, `WorldSourceSpec.java`, `WorldSourceStatus.java`
- Create: `operator/src/main/java/.../api/WorldIngest.java`, `WorldIngestSpec.java`, `WorldIngestStatus.java`
- Test: `operator/src/test/java/.../api/IngestResourceTest.java`
- Modify: `operator/src/test/java/.../CrdGenerationTest.java`

**Interfaces — binding, three follow-on tasks build on these:**

```java
// WorldSourceSpec — every group initialized in the field, as in Phase 2a
String type;                              // "s3" | "pterodactyl" | "upload" | "push"
S3Source s3 = new S3Source();             // String endpoint; String bucket; String prefix;
                                          // Ref credentialsSecretRef
Pterodactyl pterodactyl = new Pterodactyl();  // String panelUrl; String serverId;
                                              // Ref credentialsSecretRef; String select = "latest"
String poll;                              // cron expression, only for pull types; null = manual only
List<WorldSelector> worlds = new ArrayList<>();  // String name; String layout = "auto"
Retention retention = new Retention();    // int keepVersions = 5

// WorldSourceStatus
String lastSeenVersion;
BundleRef latestBundle = new BundleRef(); // String path; String version; List<String> dimensions
String lastPollTime;
List<Condition> conditions = new ArrayList<>();

// WorldIngestSpec
Ref sourceRef = new Ref();
String sourceVersion;
String worldName;

// WorldIngestStatus
String phase;                             // Pending|Extracting|Transforming|Loading|Succeeded|Failed
Progress progress = new Progress();       // double percent; long bytesDone; long bytesTotal
BundleRef bundle = new BundleRef();
String jobName; String startTime; String completionTime;
List<Condition> conditions = new ArrayList<>();
```

- [ ] **Step 1: Set up catalog and module**

`settings.gradle.kts`: `include(..., "ingest")` and add an S3 client. Choose deliberately: the project already uses `mc` in the runner image, but a Java job needs a library. Take the AWS SDK v2 S3 client (`software.amazon.awssdk:s3`) or MinIO's Java client — actually research the current version against Maven Central and document the choice in the report.

- [ ] **Step 2: Write the failing test**

`IngestResourceTest.java` following the pattern of `ApusResourceTest` from Phase 2a:

```java
    @Test
    void bothResourcesAreNamespaced() {
        assertTrue(Namespaced.class.isAssignableFrom(WorldSource.class));
        assertTrue(Namespaced.class.isAssignableFrom(WorldIngest.class));
    }

    @Test
    void specGroupsAreInitialisedSoReconcilersNeverSeeNull() {
        WorldSource source = new WorldSource();
        assertNotNull(source.getSpec().getS3());
        assertNotNull(source.getSpec().getPterodactyl());
        assertNotNull(source.getSpec().getWorlds());
        assertNotNull(source.getSpec().getRetention());
        assertNotNull(source.getStatus().getLatestBundle());

        WorldIngest ingest = new WorldIngest();
        assertNotNull(ingest.getSpec().getSourceRef());
        assertNotNull(ingest.getStatus().getProgress());
        assertNotNull(ingest.getStatus().getBundle());
    }

    @Test
    void retentionDefaultsToFiveVersions() {
        assertEquals(5, new WorldSource().getSpec().getRetention().getKeepVersions());
    }

    @Test
    void layoutDefaultsToAutomaticDetection() {
        WorldSource.WorldSelector selector = new WorldSource.WorldSelector();
        assertEquals("auto", selector.getLayout());
    }
```

- [ ] **Step 3: Run the test, confirm the failure, implement the classes**

Both resources with `implements Namespaced`, annotations `@Group("bluemap.onelitefeather.net")`, `@Version("v1alpha1")`, `@Kind`, `@Plural` (`worldsources`, `worldingests`), `@ShortNames` (`bmsource`, `bmingest`), and `initSpec()`/`initStatus()` overridden — otherwise `new WorldSource().getSpec()` returns `null`, which already blocked three parallel tasks once in Phase 2a.

- [ ] **Step 4: Extend the CRD assertions**

In `CrdGenerationTest`, one test each that `worldsources` and `worldingests` are generated and carry **`scope: Namespaced`**. Use the existing, selectively-loading helper method.

Run: `./gradlew :operator:clean :operator:test`
Expected: PASS, `operator/build/crds/` now contains five CRDs.

- [ ] **Step 5: Commit**

```bash
./gradlew spotlessApply
git add -A
git commit -m "feat(ingest): add world source and ingest custom resources"
```

---

### Task 2: Layout detection *(parallel with Task 3 and 4)*

> Own worktree. Only `ingest/src/main/java/.../LayoutDetector.java`, `WorldLayout.java`, and the associated tests. No other file, no build file.

**This is the substantive core of the ETL layer.** The sources deliver different directory structures; BlueMap needs a defined path to the correct dimension for each map.

| Layout | Detection signal | Mapping |
| --- | --- | --- |
| `vanilla` | `<w>/region`, `<w>/DIM-1/region`, `<w>/DIM1/region` | direct |
| `bukkit` | `<w>/region`, `<w>_nether/DIM-1/region`, `<w>_the_end/DIM1/region` | merge folders |
| `nested` | exactly one subdirectory, containing one of the above | skip a level, check again |

**Interfaces:**

```java
public record WorldLayout(String kind, Map<String, Path> dimensions) {}
// kind: "vanilla" | "bukkit"; dimensions: "overworld"/"the_nether"/"the_end" → path to the region directory

public final class LayoutDetector {
    /** @throws LayoutDetectionException if no known layout is detectable */
    public static WorldLayout detect(Path root, String worldName, String forcedLayout);
}
```

- [ ] **Step 1: Write the failing tests**

Build the directory structures in the test with `@TempDir` — no fixture files needed, it's purely about structure.

Test cases, each with its own rationale in the test name:

- A vanilla layout with all three dimensions is detected and mapped correctly.
- A vanilla layout with **only** an overworld is detected (no nether, no end — that's normal).
- A Bukkit layout with `world`, `world_nether`, `world_the_end` is detected and mapped to the same logical names.
- An additionally nested directory (the ZIP-upload case) is seen through.
- A structure with no `region` directory at all fails with `LayoutDetectionException`, and the message names the paths that were found — guessing is explicitly unwanted.
- `forcedLayout = "bukkit"` on a vanilla structure fails, instead of silently delivering something wrong.

- [ ] **Step 2: Implement, get the tests green, commit**

---

### Task 3: Bundle writer and manifest *(parallel with Task 2 and 4)*

> Own worktree. Only `BundleManifest.java`, `BundleWriter.java`, `S3Client.java`, and tests.

**Interfaces:**

```java
public record BundleManifest(
        int schemaVersion, String tenant, String worldId, String version,
        SourceInfo source, String minecraftVersion,
        List<DimensionInfo> dimensions, long sizeBytes, Checksums checksums) {
    public record SourceInfo(String type, String ref, String detectedLayout) {}
    public record DimensionInfo(String id, String path, List<int[]> regions, int regionCount) {}
    public record Checksums(String algorithm, String manifest) {}
    public String toJson();
    public static BundleManifest fromJson(String json);
}

public final class BundleWriter {
    public BundleWriter(S3Client s3, String bucket);
    /** Writes the bundle, manifest LAST. @return the bundle path */
    public String write(String tenant, String worldId, String version,
                        WorldLayoutLike layout, ProgressSink progress);
}
```

So that Task 3 doesn't have to wait on Task 2, `BundleWriter` accepts a narrow interface (`WorldLayoutLike` with `kind()` and `dimensions()`), which Task 2's record later satisfies. Define it in your own package.

**Tests that matter:**

- The manifest is written **last** — verify the order of write operations via a fake S3 client that logs them. This is the commit point and the most important property of the bundle.
- If the write aborts partway through, **no** manifest exists, so the bundle counts as not present.
- The region list in the manifest matches the `.mca` files actually written; coordinates are read from the filename `r.<x>.<z>.mca`.
- Serialization and deserialization of the manifest are lossless.
- Progress is reported via `ProgressSink`, so the job can surface it externally.

---

### Task 4: Connector interface and the two pull sources *(parallel with Task 2 and 3)*

> Own worktree. Only `connector/*` and tests.

**Interfaces:**

```java
public interface WorldSourceConnector {
    String type();
    /** Pull sources list available versions; push sources return an empty list. */
    List<SourceVersion> discover(Map<String, String> config);
    /** Fetches the raw world data into workDir. */
    void fetch(Map<String, String> config, SourceVersion version, Path workDir);
}
public record SourceVersion(String id, String label, Instant createdAt, long sizeBytes) {}
```

**`S3SourceConnector`:** lists objects under a prefix, detects new versions by object key, downloads them. Unpacks common archives if the key ends in one.

**`PterodactylConnector`:** queries the backup list via the panel's client API and downloads the chosen backup via a signed URL. **Research the actual API** (endpoints, authentication, response format) and document it in the report — do not invent endpoints. The backup is a `tar.gz` of the entire server; since gzip isn't seekable, the stream is traversed **once**, selectively writing only the world directory. The full archive must never land on disk.

**Tests:** the S3 connector against a MinIO test container. The Pterodactyl connector against a local HTTP stub that mimics the panel's responses — **no** real panel access and no listener on `0.0.0.0`. In particular, verify that from a tar.gz containing plugins, configs, and worlds, only the world paths are extracted.

---

### Task 5: Ingest entry point and container image

Analogous to `runner/` from Phase 1: `IngestMain` reads its configuration from environment variables, selects the connector, calls Extract → Detect → Write, and reports progress. Plus a `Dockerfile`.

**Environment-variable contract** (the interface that `IngestJobBuilder` serves in Task 6):
`APUS_SOURCE_TYPE`, `APUS_WORLD_NAME`, `APUS_LAYOUT` (default `auto`), `APUS_BUNDLE_BUCKET`, `APUS_BUNDLE_TENANT`, `APUS_BUNDLE_WORLD_ID`, `APUS_BUNDLE_VERSION`, `APUS_S3_ENDPOINT`, `APUS_S3_ACCESS_KEY`, `APUS_S3_SECRET_KEY`, plus the source-specific ones (`APUS_SOURCE_S3_*`, `APUS_PTERODACTYL_*`).

As with the runner: if a required variable is missing, abort with a clear message and a non-zero exit code, **before** anything is downloaded. Non-root, `exec` for the main process.

---

### Task 6: Reconciler for sources and ingests

`WorldSourceReconciler`: evaluates `poll`, compares against `status.lastSeenVersion`, creates a `WorldIngest` when there's something new. `WorldIngestReconciler`: creates the Job via `IngestJobBuilder`, tracks progress and outcome in the status, and on success sets `WorldSource.status.latestBundle`.

**Binding:** ownership check as in Phase 2a. No second ingest for the same source while one is running — the same optimistic lock as for rendering, there via `WorldSourceStatus`. Retention: delete older bundles, but **never** one that a `BlueMapRender` still references.

---

### Task 7: Integration test

End-to-end against MinIO: place a world in Bukkit layout as a source, run the ingest, verify that a bundle emerges with a correct manifest, logically named dimensions, and a complete region list. Use the existing fixture `testdata/mini-world`. Its own `integrationTest` task, not part of `build` — as in `runner` and `operator`.

Finally: start a render against the resulting bundle and demonstrate that the contract between ingest and render holds.

---

## Phase 2b completion

After this, the path from a configured source to a rendered map runs with no manual steps: create a `WorldSource`, ingest runs on schedule, a bundle is produced, render starts.

**Not part of 2b:** the push sources (`upload`, `push`) and the Paper plugin — they follow in Phase 6. `WorldSource.spec.type` already knows about them, but the connectors are still missing.
