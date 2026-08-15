# Apus Phase 1 — Render Core: Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A container image that renders a Minecraft world from S3 with BlueMap, writes the result back to S3, and reports its progress along the way over HTTP as JSON and Prometheus metrics — fully operable without Kubernetes.

**Architecture:** A Gradle monorepo with two delivery artifacts. The `telemetry-addon` is a BlueMap addon (`implements Runnable`) that reaches the internal RenderManager via `BlueMapAPI.onEnable` and starts a JDK-native HTTP server. The `runner` image bundles the BlueMap CLI, the telemetry addon, and the existing `BlueMapS3Storage` addon with an entrypoint that fetches the world from S3, generates the BlueMap configuration from environment variables, and starts the render. All access to BlueMap internals sits behind a single interface (`RenderManagerAccess`) so it stays testable and can be swapped out later.

**Tech Stack:** Java 25, Gradle 9.4.1 (Kotlin DSL, inline version catalog), JUnit Jupiter, Testcontainers (MinIO), Spotless, Shadow, Docker (eclipse-temurin:25-jre), MinIO Client (`mc`), `jq`.

---

## Global Constraints

These rules apply to **every** task in this plan:

- **Java toolchain: 25.** BlueMap 5.23 is built with `JavaLanguageVersion.of(25)` and ships an `eclipse-temurin:25-jre-jammy` image. Code compiled against `bluemap-common` therefore needs JDK 25.
- **BlueMap version: 5.23.** Artifacts: `de.bluecolored:bluemap-core:5.23`, `de.bluecolored:bluemap-common:5.23`, `de.bluecolored:bluemap-api:2.8.0` (the API has its own versioning). Repository: `https://repo.bluecolored.de/releases`, path structure `de/bluecolored/<artifactId>/`.
- **All BlueMap dependencies are `compileOnly`.** They are present at runtime in the CLI fat jar. Shipping them alongside would cause class conflicts.
- **License: AGPL-3.0**, like `BlueMapS3Storage`. Every Java file carries the header from `.spotless/Copyright.java`, enforced by Spotless.
- **Java base package: `net.onelitefeather.apus`.**
- **Gradle group: `net.onelitefeather.apus`, version `999.0.0`** in `gradle.properties` (Release Please replaces it on release — same convention as `BlueMapS3Storage`).
- **No third-party runtime dependencies in the addon.** HTTP via `com.sun.net.httpserver.HttpServer`, JSON via a small hand-written writer. Reason: the addon runs in its own classloader alongside BlueMap; every shipped library is a potential conflict.
- **Commit convention: Conventional Commits.** Git identity is already set in the repo (`TheMeinerLP <github@themeinerlp.dev>`). **No** Claude co-author or "Generated with" lines.
- **Language in code:** identifiers and Javadoc in English, as in `BlueMapS3Storage`.

### Verified BlueMap facts

These signatures were checked against the BlueMap 5.23 source and are used throughout the plan:

```java
// de.bluecolored.bluemap.api.BlueMapAPI
public static void onEnable(Consumer<BlueMapAPI> consumer)
public static synchronized void onDisable(Consumer<BlueMapAPI> consumer)
public abstract Collection<BlueMapMap> getMaps();

// de.bluecolored.bluemap.common.api.BlueMapAPIImpl
public @Nullable Plugin plugin();          // per its Javadoc, explicitly intended for addons

// de.bluecolored.bluemap.common.plugin.Plugin
public RenderManager getRenderManager();   // Lombok-@Getter

// de.bluecolored.bluemap.common.rendermanager.RenderManager
public RenderTask getCurrentRenderTask()             // null when nothing is running
public long estimateCurrentRenderTaskTimeRemaining() // Millisekunden, 0 = unbekannt
public int getScheduledRenderTaskCount()
public int getWorkerThreadCount()
public boolean isRunning()

// de.bluecolored.bluemap.common.rendermanager.RenderTask
double estimateProgress();   // 0..1
String getDescription();

// de.bluecolored.bluemap.common.rendermanager.MapRenderTask extends RenderTask
BmMap getMap();              // de.bluecolored.bluemap.core.map.BmMap
```

**No reflection required.** Access goes through `((BlueMapAPIImpl) api).plugin().getRenderManager()`. `plugin()` can return `null` when the platform provides no plugin API — that must be treated as an error case, not a crash.

### Verified BlueMap CLI facts

- Artifact: `bluemap-5.23-cli.jar`, download at `https://github.com/BlueMap-Minecraft/BlueMap/releases/download/v5.23/bluemap-5.23-cli.jar`. Single fat jar.
- Relevant options: `-c/--config <folder>`, `-r/--render`, `-f/--force-render`, `-m/--maps <list>`, `-u/--watch`, `-w/--webserver`, `-v/--mc-version <version>`, `-V/--version`.
- The `packs/` folder is **fixed** at `<config-folder>/packs` and is not configurable. Addons **and** resource packs both go there.
- Without an action flag, the CLI only writes default configurations and exits with **exit code 1**.
- Exit codes: `0` success, `1` configuration/IO/argument error, `2` missing Minecraft resources (`accept-download` not set).
- The Minecraft client JAR is downloaded to `<data-folder>/minecraft-client-<versionId>.jar` — **only if the file is missing**. Pre-seeding it reliably prevents the download.
- The CLI defaults its data folder to `data` (relative to the working directory), not `bluemap`.
- Logging goes to stdout. There is neither a log-level nor a JSON-logging switch.

### Configuration formats

`core.conf` (only the keys needed here):

```hocon
accept-download: true
data: "/work/data"
render-thread-count: 4
metrics: false
scan-for-mod-resources: false
```

`maps/<id>.conf`:

```hocon
world: "/work/world/overworld"
dimension: "minecraft:overworld"
name: "Overworld"
sorting: 0
storage: "s3"
render-edges: true
```

`storages/s3.conf` — fields from `S3StorageConfiguration` in `BlueMapS3Storage`. Configurate maps camelCase to kebab-case (visible in `render-thread-count` ↔ `renderThreadCount` in `core.conf`):

```hocon
storage-type: "themeinerlp:s3"
bucket-name: "apus-maps"
region: "us-east-1"
access-key-id: "..."
secret-access-key: "..."
endpoint-url: "http://minio:9000"
compression: "gzip"
root-path: "survival"
force-path-style: true
```

> **Verified in Task 7:** The integration test `runner/src/test/java/net/onelitefeather/apus/runner/RenderEndToEndTest.java` renders the fixture world from MinIO against a real BlueMap CLI run with `themeinerlp:s3` as the storage type and the kebab-case field names above — **unchanged, exactly as originally assumed**. The BlueMap log output confirms `Initializing Storage: 's3' (Type: 'themeinerlp:s3')`, and the render finishes with exit code 0. Also confirmed by source review: `StorageConfig.storageType` in `bluemap-common` is mapped to `storage-type` via `@Setting`-free Configurate object mapping (see `de.bluecolored.bluemap.common.config.storage.StorageConfig`), and `Key.parse(key, Key.BLUEMAP_NAMESPACE)` expects exactly the `namespace:value` format `themeinerlp:s3` produced by `new Key("themeinerlp", "s3")` in `S3StorageAddon`. `render-config.sh` did not need to change for this.

---

## File Structure

```text
Apus/
├── settings.gradle.kts                 Modules + inline version catalog
├── build.gradle.kts                    Root: Spotless, toolchain for all modules
├── gradle.properties                   group, version
├── .spotless/Copyright.java            AGPL header template
├── .gitignore
├── LICENSE                             AGPL-3.0
├── gradle/wrapper/                     Gradle 9.4.1
│
├── telemetry-addon/
│   ├── build.gradle.kts
│   └── src/
│       ├── main/java/net/onelitefeather/apus/telemetry/
│       │   ├── ApusTelemetryAddon.java      Entrypoint (Runnable), wiring
│       │   ├── TelemetryConfig.java         Configuration from environment variables
│       │   ├── ProgressSnapshot.java        Immutable data model (record)
│       │   ├── JsonWriter.java              Minimal JSON serializer
│       │   ├── PrometheusWriter.java        Prometheus text format
│       │   ├── TelemetryServer.java         HTTP server, routes
│       │   └── probe/
│       │       ├── RenderManagerAccess.java Narrow interface onto BlueMap
│       │       ├── RenderProgressProbe.java Produces snapshots, encapsulates error cases
│       │       ├── BlueMapRenderManagerAccess.java  Route via the BlueMap API
│       │       └── LogTailRenderManagerAccess.java  Route via BlueMap's logger (added in Task 8,
│       │                                            see below)
│       ├── main/resources/bluemap.addon.json
│       └── test/java/net/onelitefeather/apus/telemetry/
│           ├── ProgressSnapshotTest.java
│           ├── JsonWriterTest.java
│           ├── PrometheusWriterTest.java
│           ├── TelemetryServerTest.java
│           ├── AddonManifestTest.java
│           └── probe/
│               ├── RenderProgressProbeTest.java
│               └── FakeRenderManagerAccess.java
│
├── runner/
│   ├── Dockerfile
│   ├── entrypoint.sh                   Flow control
│   ├── bin/
│   │   ├── render-config.sh            Generates core.conf, maps/, storages/
│   │   └── bundle-sync.sh              Fetches world data from S3
│   └── src/test/java/net/onelitefeather/apus/runner/
│       ├── RunnerImageTest.java        Image smoke test
│       └── RenderEndToEndTest.java     MinIO + real render
│
└── testdata/
    ├── README.md                       Origin and generation of the fixture
    └── mini-world/                     Minimal Vanilla world for tests
```

**Why this split:** BlueMap types are imported **exclusively** within the `probe` package, behind the `RenderManagerAccess` interface. Everything else — snapshot, serialization, HTTP, error handling — is plain Java and testable without a running BlueMap instance. That makes Phase 1 almost fully unit-testable and limits the blast radius of a BlueMap upgrade to that one package.

> **Updated to reflect the delivered state:** Originally, `BlueMapRenderManagerAccess` was planned as the *only* class touching BlueMap. Task 8 found that the API route is structurally unable to work in CLI mode, and added `LogTailRenderManagerAccess` as a second implementation of the same interface. This exact cut is what made the extension cheap: one class was added, no existing one had to change.

---

## Parallelization

Tasks in the same group can be worked on simultaneously by different agents:

| Group | Tasks | Prerequisite |
| --- | --- | --- |
| A | Task 1 | — |
| B | Task 2, Task 3, Task 4 | Task 1 |
| C | Task 5, Task 6 | Task 2–4 |
| D | Task 7 | Task 5, Task 6 |
| E | Task 8 | Task 7 |

Task 2 (snapshot + JSON), Task 3 (probe), and Task 4 (HTTP server) touch separate files and can genuinely be worked on in parallel. The interfaces between them are pinned down exactly below, in each `Interfaces` block — all three must stick to them without coordinating.

---

### Task 1: Monorepo scaffolding

**Files:**

- Create: `settings.gradle.kts`
- Create: `build.gradle.kts`
- Create: `gradle.properties`
- Create: `.spotless/Copyright.java`
- Create: `.gitignore`
- Create: `LICENSE`
- Create: `telemetry-addon/build.gradle.kts`
- Create: `telemetry-addon/src/test/java/net/onelitefeather/apus/telemetry/BuildSetupTest.java`

**Interfaces:**

- Consumes: nothing
- Produces: version catalog aliases `libs.bluemap.api`, `libs.bluemap.core`, `libs.bluemap.common`, `libs.junit.bom`, `libs.junit.jupiter`, `libs.junit.platform.launcher`, `libs.testcontainers.bom`, `libs.testcontainers.junit`, `libs.testcontainers.minio`, `libs.plugins.spotless`, `libs.plugins.shadow`. Module name `:telemetry-addon`.

- [ ] **Step 1: Create the Gradle wrapper**

```bash
cd /mnt/projects/oss/onelitefeather/Apus
gradle wrapper --gradle-version 9.4.1
```

If no `gradle` is available globally, copy the wrapper from `BlueMapS3Storage`:

```bash
cp -r ../BlueMapS3Storage/gradle/wrapper gradle/
cp ../BlueMapS3Storage/gradlew ../BlueMapS3Storage/gradlew.bat .
chmod +x gradlew
```

- [ ] **Step 2: Write `settings.gradle.kts`**

```kotlin
rootProject.name = "Apus"

include("telemetry-addon")

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        maven("https://repo.bluecolored.de/releases")
    }
    versionCatalogs {
        create("libs") {
            version("bluemap", "5.23")
            version("bluemap-api", "2.8.0")
            version("junit", "6.0.3")
            version("testcontainers", "1.20.4")
            version("spotless", "8.3.0")
            version("shadow", "9.3.2")

            library("bluemap.api", "de.bluecolored", "bluemap-api").versionRef("bluemap-api")
            library("bluemap.core", "de.bluecolored", "bluemap-core").versionRef("bluemap")
            library("bluemap.common", "de.bluecolored", "bluemap-common").versionRef("bluemap")

            library("junit.bom", "org.junit", "junit-bom").versionRef("junit")
            library("junit.jupiter", "org.junit.jupiter", "junit-jupiter").withoutVersion()
            library("junit.platform.launcher", "org.junit.platform", "junit-platform-launcher").withoutVersion()

            library("testcontainers.bom", "org.testcontainers", "testcontainers-bom").versionRef("testcontainers")
            library("testcontainers.junit", "org.testcontainers", "junit-jupiter").withoutVersion()
            library("testcontainers.minio", "org.testcontainers", "minio").withoutVersion()

            plugin("spotless", "com.diffplug.spotless").versionRef("spotless")
            plugin("shadow", "com.gradleup.shadow").versionRef("shadow")
        }
    }
}
```

- [ ] **Step 3: Write `gradle.properties`**

```properties
group = net.onelitefeather.apus
version = 999.0.0
org.gradle.caching = true
org.gradle.parallel = true
```

- [ ] **Step 4: Write the root `build.gradle.kts`**

```kotlin
plugins {
    alias(libs.plugins.spotless) apply false
}

subprojects {
    apply(plugin = "java")
    apply(plugin = "com.diffplug.spotless")

    extensions.configure<JavaPluginExtension> {
        toolchain {
            languageVersion.set(JavaLanguageVersion.of(25))
        }
    }

    extensions.configure<com.diffplug.gradle.spotless.SpotlessExtension> {
        java {
            importOrder()
            removeUnusedImports()
            removeWildcardImports()
            formatAnnotations()
            licenseHeaderFile(rootProject.file(".spotless/Copyright.java"))
        }
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
    }
}
```

- [ ] **Step 5: Write `.spotless/Copyright.java`**

```java
/**
 * Apus - render and host BlueMap maps on Kubernetes.
 * Copyright (C) $YEAR OneLiteFeather and contributors
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
```

- [ ] **Step 6: Write `.gitignore`**

```gitignore
.gradle/
build/
/bin/
.idea/
*.iml
runner/vendor/
```

**Careful:** `/bin/` with a leading slash, so it means only one directory at the repository root. A bare `bin/` would also exclude `runner/bin/` — and an exception via `!runner/bin/` would **not** help, because Git cannot re-include files inside an already-excluded directory.

- [ ] **Step 7: Create `LICENSE`**

Copy over the full AGPL-3.0 text:

```bash
cp ../BlueMapS3Storage/LICENSE LICENSE
```

- [ ] **Step 8: Write `telemetry-addon/build.gradle.kts`**

```kotlin
plugins {
    alias(libs.plugins.shadow)
}

dependencies {
    compileOnly(libs.bluemap.api)
    compileOnly(libs.bluemap.core)
    compileOnly(libs.bluemap.common)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks {
    shadowJar {
        archiveClassifier.set("")
        archiveBaseName.set("apus-telemetry-addon")
    }
    build {
        dependsOn(shadowJar)
    }
}
```

- [ ] **Step 9: Write the failing test**

This test proves that the BlueMap dependencies actually resolve and contain the expected classes. This is exactly where the setup would otherwise fail silently.

`telemetry-addon/src/test/java/net/onelitefeather/apus/telemetry/BuildSetupTest.java`:

```java
package net.onelitefeather.apus.telemetry;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class BuildSetupTest {

    @Test
    void toolchainIsJava25() {
        int major = Runtime.version().feature();
        assertEquals(25, major, "Build must run on Java 25, BlueMap 5.23 requires it");
    }

    @Test
    void blueMapApiClassesAreOnTheCompileClasspath() {
        assertDoesNotThrow(() -> Class.forName("de.bluecolored.bluemap.api.BlueMapAPI"));
        assertDoesNotThrow(() -> Class.forName("de.bluecolored.bluemap.common.api.BlueMapAPIImpl"));
        assertDoesNotThrow(() -> Class.forName("de.bluecolored.bluemap.common.rendermanager.RenderManager"));
        assertDoesNotThrow(() -> Class.forName("de.bluecolored.bluemap.common.rendermanager.MapRenderTask"));
    }
}
```

For the classes to be visible in the test, `telemetry-addon/build.gradle.kts` must also expose them on the test classpath. Add to the `dependencies` block:

```kotlin
    testCompileOnly(libs.bluemap.common)
    testRuntimeOnly(libs.bluemap.api)
    testRuntimeOnly(libs.bluemap.core)
    testRuntimeOnly(libs.bluemap.common)
```

- [ ] **Step 10: Run the test and confirm the failure**

Run: `./gradlew :telemetry-addon:test --tests '*BuildSetupTest*'`
Expected: FAIL, as long as the wrapper, catalog, or repository are not right — typically "Could not find de.bluecolored:bluemap-common:5.23".

**If resolution fails:** the coordinates have changed between BlueMap versions. `BlueMapS3Storage` still uses `de.bluecolored.bluemap:BlueMapCore` for 5.3. Check which form applies to 5.23:

```bash
curl -s https://repo.bluecolored.de/releases/de/bluecolored/bluemap-common/maven-metadata.xml | head -20
curl -s https://repo.bluecolored.de/releases/de/bluecolored/bluemap/BlueMapCommon/maven-metadata.xml | head -20
```

Use whichever variant returns a valid `maven-metadata.xml`, and adjust both the catalog **and this plan**.

- [ ] **Step 11: Fix the setup until the test passes**

Run: `./gradlew :telemetry-addon:test --tests '*BuildSetupTest*'`
Expected: PASS

- [ ] **Step 12: Apply Spotless and verify the full build**

Run: `./gradlew spotlessApply build`
Expected: BUILD SUCCESSFUL

- [ ] **Step 13: Commit**

```bash
git add -A
git commit -m "build: set up Apus gradle monorepo with telemetry-addon module"
```

---

### Task 2: ProgressSnapshot and serialization

**Files:**

- Create: `telemetry-addon/src/main/java/net/onelitefeather/apus/telemetry/ProgressSnapshot.java`
- Create: `telemetry-addon/src/main/java/net/onelitefeather/apus/telemetry/Numbers.java`
- Create: `telemetry-addon/src/main/java/net/onelitefeather/apus/telemetry/JsonWriter.java`
- Create: `telemetry-addon/src/main/java/net/onelitefeather/apus/telemetry/PrometheusWriter.java`
- Test: `telemetry-addon/src/test/java/net/onelitefeather/apus/telemetry/ProgressSnapshotTest.java`
- Test: `telemetry-addon/src/test/java/net/onelitefeather/apus/telemetry/NumbersTest.java`
- Test: `telemetry-addon/src/test/java/net/onelitefeather/apus/telemetry/JsonWriterTest.java`
- Test: `telemetry-addon/src/test/java/net/onelitefeather/apus/telemetry/PrometheusWriterTest.java`

**Interfaces:**

- Consumes: nothing from other tasks
- Produces:

```java
public record ProgressSnapshot(
        State state,              // RENDERING, IDLE, STARTING, UNKNOWN
        String currentMap,        // null when unknown
        double progress,          // 0..1, -1 when unknown
        long etaSeconds,          // -1 when unknown
        int queuedTasks,          // -1 when unknown
        int renderThreads,        // -1 when unknown
        boolean degraded,
        String description)       // null when unknown
{
    public enum State { STARTING, RENDERING, IDLE, UNKNOWN }
    public static ProgressSnapshot unknown(String reason);
    public static ProgressSnapshot idle(int queuedTasks, int renderThreads);
}

public final class Numbers {
    /** Formats a double without trailing zeros, locale-independent. */
    public static String compact(double value);
}

public final class JsonWriter {
    public static String toJson(ProgressSnapshot snapshot);
}

public final class PrometheusWriter {
    public static String toPrometheus(ProgressSnapshot snapshot);
}
```

`Numbers.compact` is used by **both** writers. It's the single place with number formatting — writing the same logic twice would be a duplicate.

- [ ] **Step 1: Write the failing test for `ProgressSnapshot`**

`ProgressSnapshotTest.java`:

```java
package net.onelitefeather.apus.telemetry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ProgressSnapshotTest {

    @Test
    void unknownMarksItselfDegradedAndCarriesTheReason() {
        ProgressSnapshot snapshot = ProgressSnapshot.unknown("plugin() returned null");

        assertEquals(ProgressSnapshot.State.UNKNOWN, snapshot.state());
        assertTrue(snapshot.degraded());
        assertEquals(-1.0, snapshot.progress());
        assertEquals(-1L, snapshot.etaSeconds());
        assertEquals("plugin() returned null", snapshot.description());
        assertNull(snapshot.currentMap());
    }

    @Test
    void idleReportsQueueAndThreadsButNoProgress() {
        ProgressSnapshot snapshot = ProgressSnapshot.idle(3, 8);

        assertEquals(ProgressSnapshot.State.IDLE, snapshot.state());
        assertFalse(snapshot.degraded());
        assertEquals(3, snapshot.queuedTasks());
        assertEquals(8, snapshot.renderThreads());
        assertEquals(-1.0, snapshot.progress());
    }
}
```

- [ ] **Step 2: Run the test and confirm the failure**

Run: `./gradlew :telemetry-addon:test --tests '*ProgressSnapshotTest*'`
Expected: FAIL, "cannot find symbol: class ProgressSnapshot"

- [ ] **Step 3: Implement `ProgressSnapshot`**

```java
package net.onelitefeather.apus.telemetry;

/**
 * An immutable point-in-time view of BlueMap's render progress.
 *
 * <p>Unknown numeric values are represented as {@code -1} rather than {@code null}
 * so that consumers never have to null-check primitives.
 */
public record ProgressSnapshot(
        State state,
        String currentMap,
        double progress,
        long etaSeconds,
        int queuedTasks,
        int renderThreads,
        boolean degraded,
        String description) {

    public enum State {
        STARTING,
        RENDERING,
        IDLE,
        UNKNOWN
    }

    /**
     * Creates a snapshot for the case where progress could not be determined at all.
     * The render itself is unaffected; only the reporting degrades.
     */
    public static ProgressSnapshot unknown(String reason) {
        return new ProgressSnapshot(State.UNKNOWN, null, -1.0, -1L, -1, -1, true, reason);
    }

    /** Creates a snapshot for a running BlueMap that currently has no active task. */
    public static ProgressSnapshot idle(int queuedTasks, int renderThreads) {
        return new ProgressSnapshot(State.IDLE, null, -1.0, -1L, queuedTasks, renderThreads, false, null);
    }
}
```

- [ ] **Step 4: Run the test and confirm it passes**

Run: `./gradlew :telemetry-addon:test --tests '*ProgressSnapshotTest*'`
Expected: PASS

- [ ] **Step 5: Write the failing test for `Numbers`**

`NumbersTest.java`:

```java
package net.onelitefeather.apus.telemetry;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class NumbersTest {

    @Test
    void dropsTrailingZeros() {
        assertEquals("0.5", Numbers.compact(0.5));
        assertEquals("0.674", Numbers.compact(0.674));
    }

    @Test
    void dropsTheDecimalPointForWholeNumbers() {
        assertEquals("1", Numbers.compact(1.0));
        assertEquals("-1", Numbers.compact(-1.0));
        assertEquals("0", Numbers.compact(0.0));
    }

    @Test
    void usesADotRegardlessOfTheHostLocale() {
        java.util.Locale previous = java.util.Locale.getDefault();
        try {
            // German locale would otherwise render 0.5 as "0,5", producing invalid JSON.
            java.util.Locale.setDefault(java.util.Locale.GERMANY);
            assertEquals("0.5", Numbers.compact(0.5));
        } finally {
            java.util.Locale.setDefault(previous);
        }
    }
}
```

- [ ] **Step 6: Run the test, confirm the failure, implement `Numbers`**

Run: `./gradlew :telemetry-addon:test --tests '*NumbersTest*'`
Expected: FAIL, "cannot find symbol: class Numbers"

```java
package net.onelitefeather.apus.telemetry;

import java.util.Locale;

/**
 * Locale-independent number formatting shared by the JSON and Prometheus writers.
 *
 * <p>Both output formats require a dot as the decimal separator; the host locale must
 * never leak into a payload.
 */
public final class Numbers {

    private Numbers() {}

    /** Formats {@code value} with up to six decimals, without trailing zeros. */
    public static String compact(double value) {
        String formatted = String.format(Locale.ROOT, "%.6f", value);
        if (formatted.indexOf('.') >= 0) {
            formatted = formatted.replaceAll("0+$", "");
            if (formatted.endsWith(".")) {
                formatted = formatted.substring(0, formatted.length() - 1);
            }
        }
        return formatted;
    }
}
```

Run: `./gradlew :telemetry-addon:test --tests '*NumbersTest*'`
Expected: PASS

- [ ] **Step 7: Write the failing test for `JsonWriter`**

`JsonWriterTest.java`:

```java
package net.onelitefeather.apus.telemetry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class JsonWriterTest {

    @Test
    void serialisesARunningRender() {
        ProgressSnapshot snapshot = new ProgressSnapshot(
                ProgressSnapshot.State.RENDERING, "overworld", 0.674, 1830L, 2, 8, false, "Updating map 'overworld'");

        String json = JsonWriter.toJson(snapshot);

        assertEquals(
                "{\"state\":\"rendering\",\"currentMap\":\"overworld\",\"progress\":0.674,"
                        + "\"etaSeconds\":1830,\"queuedTasks\":2,\"renderThreads\":8,"
                        + "\"degraded\":false,\"description\":\"Updating map 'overworld'\"}",
                json);
    }

    @Test
    void serialisesNullFieldsAsJsonNull() {
        String json = JsonWriter.toJson(ProgressSnapshot.unknown("no plugin"));

        assertTrue(json.contains("\"currentMap\":null"), json);
        assertTrue(json.contains("\"state\":\"unknown\""), json);
        assertTrue(json.contains("\"degraded\":true"), json);
    }

    @Test
    void escapesQuotesAndBackslashesInDescriptions() {
        ProgressSnapshot snapshot = ProgressSnapshot.unknown("say \"hi\" \\ bye");

        String json = JsonWriter.toJson(snapshot);

        assertTrue(json.contains("\"description\":\"say \\\"hi\\\" \\\\ bye\""), json);
    }
}
```

- [ ] **Step 8: Run the test and confirm the failure**

Run: `./gradlew :telemetry-addon:test --tests '*JsonWriterTest*'`
Expected: FAIL, "cannot find symbol: class JsonWriter"

- [ ] **Step 9: Implement `JsonWriter`**

```java
package net.onelitefeather.apus.telemetry;

import java.util.Locale;

/**
 * Serialises a {@link ProgressSnapshot} to JSON without pulling in a JSON library.
 *
 * <p>The addon runs in its own classloader next to BlueMap; every shipped dependency
 * is a potential conflict. The payload is a flat object of eight known fields, so a
 * hand-written writer is both sufficient and safer than a dependency.
 */
public final class JsonWriter {

    private JsonWriter() {}

    public static String toJson(ProgressSnapshot snapshot) {
        StringBuilder out = new StringBuilder(256);
        out.append('{');
        appendString(out, "state", snapshot.state().name().toLowerCase(Locale.ROOT));
        out.append(',');
        appendString(out, "currentMap", snapshot.currentMap());
        out.append(',');
        appendNumber(out, "progress", snapshot.progress());
        out.append(',');
        out.append("\"etaSeconds\":").append(snapshot.etaSeconds());
        out.append(',');
        out.append("\"queuedTasks\":").append(snapshot.queuedTasks());
        out.append(',');
        out.append("\"renderThreads\":").append(snapshot.renderThreads());
        out.append(',');
        out.append("\"degraded\":").append(snapshot.degraded());
        out.append(',');
        appendString(out, "description", snapshot.description());
        out.append('}');
        return out.toString();
    }

    private static void appendString(StringBuilder out, String key, String value) {
        out.append('"').append(key).append("\":");
        if (value == null) {
            out.append("null");
            return;
        }
        out.append('"');
        escape(out, value);
        out.append('"');
    }

    private static void appendNumber(StringBuilder out, String key, double value) {
        out.append('"').append(key).append("\":").append(Numbers.compact(value));
    }

    private static void escape(StringBuilder out, String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
    }
}
```

- [ ] **Step 10: Run the test and confirm it passes**

Run: `./gradlew :telemetry-addon:test --tests '*JsonWriterTest*'`
Expected: PASS

If `progress` is expected as `-1` instead of `-1.0`: the test in Step 5 requires exactly `0.674`; `trimTrailingZeros` produces `0.674` for that. For `-1.0` it produces `-1`. Both are valid JSON.

- [ ] **Step 11: Write the failing test for `PrometheusWriter`**

`PrometheusWriterTest.java`:

```java
package net.onelitefeather.apus.telemetry;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class PrometheusWriterTest {

    @Test
    void emitsHelpTypeAndValueForARunningRender() {
        ProgressSnapshot snapshot = new ProgressSnapshot(
                ProgressSnapshot.State.RENDERING, "overworld", 0.5, 60L, 1, 4, false, "Updating");

        String text = PrometheusWriter.toPrometheus(snapshot);

        assertTrue(text.contains("# TYPE apus_render_progress_ratio gauge"), text);
        assertTrue(text.contains("apus_render_progress_ratio{map=\"overworld\"} 0.5"), text);
        assertTrue(text.contains("apus_render_eta_seconds{map=\"overworld\"} 60"), text);
        assertTrue(text.contains("apus_render_queued_tasks 1"), text);
        assertTrue(text.contains("apus_render_threads 4"), text);
        assertTrue(text.contains("apus_render_degraded 0"), text);
        assertTrue(text.endsWith("\n"), "Prometheus exposition format must end with a newline");
    }

    @Test
    void omitsUnknownValuesInsteadOfEmittingMinusOne() {
        String text = PrometheusWriter.toPrometheus(ProgressSnapshot.unknown("no plugin"));

        assertFalse(text.contains("apus_render_progress_ratio{"), text);
        assertFalse(text.contains("-1"), text);
        assertTrue(text.contains("apus_render_degraded 1"), text);
    }
}
```

- [ ] **Step 12: Run the test and confirm the failure**

Run: `./gradlew :telemetry-addon:test --tests '*PrometheusWriterTest*'`
Expected: FAIL, "cannot find symbol: class PrometheusWriter"

- [ ] **Step 13: Implement `PrometheusWriter`**

```java
package net.onelitefeather.apus.telemetry;

import java.util.Locale;

/**
 * Renders a {@link ProgressSnapshot} in the Prometheus text exposition format.
 *
 * <p>Unknown values are omitted entirely rather than exported as {@code -1}: a sentinel
 * would corrupt averages and alerting rules downstream.
 */
public final class PrometheusWriter {

    private PrometheusWriter() {}

    public static String toPrometheus(ProgressSnapshot snapshot) {
        StringBuilder out = new StringBuilder(512);
        String map = snapshot.currentMap();

        if (snapshot.progress() >= 0 && map != null) {
            out.append("# HELP apus_render_progress_ratio Progress of the current render task, 0 to 1.\n");
            out.append("# TYPE apus_render_progress_ratio gauge\n");
            out.append("apus_render_progress_ratio{map=\"").append(escapeLabel(map)).append("\"} ")
                    .append(Numbers.compact(snapshot.progress())).append('\n');
        }

        if (snapshot.etaSeconds() >= 0 && map != null) {
            out.append("# HELP apus_render_eta_seconds Estimated seconds until the current task finishes.\n");
            out.append("# TYPE apus_render_eta_seconds gauge\n");
            out.append("apus_render_eta_seconds{map=\"").append(escapeLabel(map)).append("\"} ")
                    .append(snapshot.etaSeconds()).append('\n');
        }

        if (snapshot.queuedTasks() >= 0) {
            out.append("# HELP apus_render_queued_tasks Number of scheduled render tasks.\n");
            out.append("# TYPE apus_render_queued_tasks gauge\n");
            out.append("apus_render_queued_tasks ").append(snapshot.queuedTasks()).append('\n');
        }

        if (snapshot.renderThreads() >= 0) {
            out.append("# HELP apus_render_threads Number of BlueMap render worker threads.\n");
            out.append("# TYPE apus_render_threads gauge\n");
            out.append("apus_render_threads ").append(snapshot.renderThreads()).append('\n');
        }

        out.append("# HELP apus_render_degraded 1 when progress could not be determined.\n");
        out.append("# TYPE apus_render_degraded gauge\n");
        out.append("apus_render_degraded ").append(snapshot.degraded() ? 1 : 0).append('\n');

        return out.toString();
    }

    private static String escapeLabel(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
    }
}
```

- [ ] **Step 14: Run the test and confirm it passes**

Run: `./gradlew :telemetry-addon:test --tests '*PrometheusWriterTest*'`
Expected: PASS

- [ ] **Step 15: Commit**

```bash
./gradlew spotlessApply
git add -A
git commit -m "feat(telemetry): add progress snapshot model with json and prometheus writers"
```

---

### Task 3: Progress probe with BlueMap access

**Files:**

- Create: `telemetry-addon/src/main/java/net/onelitefeather/apus/telemetry/probe/RenderManagerAccess.java`
- Create: `telemetry-addon/src/main/java/net/onelitefeather/apus/telemetry/probe/RenderProgressProbe.java`
- Create: `telemetry-addon/src/main/java/net/onelitefeather/apus/telemetry/probe/BlueMapRenderManagerAccess.java`
- Test: `telemetry-addon/src/test/java/net/onelitefeather/apus/telemetry/probe/FakeRenderManagerAccess.java`
- Test: `telemetry-addon/src/test/java/net/onelitefeather/apus/telemetry/probe/RenderProgressProbeTest.java`

**Interfaces:**

- Consumes: `ProgressSnapshot` from Task 2 (including `unknown(String)` and `idle(int, int)`)
- Produces:

```java
public interface RenderManagerAccess {
    boolean isRunning();
    int queuedTasks();
    int renderThreads();
    /** @return null when no task is currently running */
    TaskInfo currentTask();

    record TaskInfo(String mapId, String description, double progress, long etaMillis) {}
}

public final class RenderProgressProbe {
    public RenderProgressProbe(Supplier<RenderManagerAccess> accessSupplier);
    public ProgressSnapshot sample();
}
```

The constructor takes a `Supplier` because the BlueMap API is not yet ready when the addon starts. The supplier returns `null` for as long as it's missing.

- [ ] **Step 1: Write `RenderManagerAccess` (still without an implementation)**

```java
package net.onelitefeather.apus.telemetry.probe;

/**
 * The single seam between Apus and BlueMap's internals.
 *
 * <p>Everything above this interface is plain Java and unit-testable without a running
 * BlueMap. Only {@link BlueMapRenderManagerAccess} imports BlueMap types, which keeps the
 * blast radius of a BlueMap upgrade to exactly one class.
 */
public interface RenderManagerAccess {

    boolean isRunning();

    int queuedTasks();

    int renderThreads();

    /**
     * @return information about the currently running task, or {@code null} when idle
     */
    TaskInfo currentTask();

    /**
     * @param mapId       id of the map being rendered, or {@code null} if the task is not map-bound
     * @param description human-readable task description as provided by BlueMap
     * @param progress    completion between 0 and 1
     * @param etaMillis   estimated milliseconds remaining, 0 when BlueMap cannot estimate
     */
    record TaskInfo(String mapId, String description, double progress, long etaMillis) {}
}
```

- [ ] **Step 2: Write the fake for tests**

`FakeRenderManagerAccess.java` (in the test directory):

```java
package net.onelitefeather.apus.telemetry.probe;

/** A hand-written test double; BlueMap's RenderManager is a concrete class and cannot be mocked cleanly. */
final class FakeRenderManagerAccess implements RenderManagerAccess {

    boolean running = true;
    int queued = 0;
    int threads = 4;
    TaskInfo task = null;
    RuntimeException failWith = null;

    @Override
    public boolean isRunning() {
        if (failWith != null) throw failWith;
        return running;
    }

    @Override
    public int queuedTasks() {
        if (failWith != null) throw failWith;
        return queued;
    }

    @Override
    public int renderThreads() {
        if (failWith != null) throw failWith;
        return threads;
    }

    @Override
    public TaskInfo currentTask() {
        if (failWith != null) throw failWith;
        return task;
    }
}
```

- [ ] **Step 3: Write the failing test for the probe**

`RenderProgressProbeTest.java`:

```java
package net.onelitefeather.apus.telemetry.probe;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.onelitefeather.apus.telemetry.ProgressSnapshot;
import org.junit.jupiter.api.Test;

class RenderProgressProbeTest {

    @Test
    void reportsRenderingWhenATaskIsActive() {
        FakeRenderManagerAccess access = new FakeRenderManagerAccess();
        access.queued = 2;
        access.threads = 8;
        access.task = new RenderManagerAccess.TaskInfo("overworld", "Updating map 'overworld'", 0.674, 1_830_000L);

        ProgressSnapshot snapshot = new RenderProgressProbe(() -> access).sample();

        assertEquals(ProgressSnapshot.State.RENDERING, snapshot.state());
        assertEquals("overworld", snapshot.currentMap());
        assertEquals(0.674, snapshot.progress(), 1e-9);
        assertEquals(1830L, snapshot.etaSeconds());
        assertEquals(2, snapshot.queuedTasks());
        assertEquals(8, snapshot.renderThreads());
        assertFalse(snapshot.degraded());
    }

    @Test
    void reportsIdleWhenNoTaskIsRunning() {
        FakeRenderManagerAccess access = new FakeRenderManagerAccess();
        access.queued = 0;
        access.threads = 4;
        access.task = null;

        ProgressSnapshot snapshot = new RenderProgressProbe(() -> access).sample();

        assertEquals(ProgressSnapshot.State.IDLE, snapshot.state());
        assertFalse(snapshot.degraded());
        assertEquals(4, snapshot.renderThreads());
    }

    @Test
    void reportsStartingWhileTheApiIsNotYetAvailable() {
        ProgressSnapshot snapshot = new RenderProgressProbe(() -> null).sample();

        assertEquals(ProgressSnapshot.State.STARTING, snapshot.state());
        assertFalse(snapshot.degraded(), "waiting for the API is normal, not a degradation");
    }

    @Test
    void degradesInsteadOfThrowingWhenBlueMapAccessFails() {
        FakeRenderManagerAccess access = new FakeRenderManagerAccess();
        access.failWith = new NoSuchMethodError("getCurrentRenderTask");

        ProgressSnapshot snapshot = new RenderProgressProbe(() -> access).sample();

        assertEquals(ProgressSnapshot.State.UNKNOWN, snapshot.state());
        assertTrue(snapshot.degraded());
        assertTrue(snapshot.description().contains("getCurrentRenderTask"), snapshot.description());
    }

    @Test
    void treatsAnEtaOfZeroAsUnknown() {
        FakeRenderManagerAccess access = new FakeRenderManagerAccess();
        access.task = new RenderManagerAccess.TaskInfo("nether", "Updating", 0.1, 0L);

        ProgressSnapshot snapshot = new RenderProgressProbe(() -> access).sample();

        assertEquals(-1L, snapshot.etaSeconds(), "BlueMap returns 0 when it cannot estimate");
    }

    @Test
    void survivesATaskWithoutAMapBinding() {
        FakeRenderManagerAccess access = new FakeRenderManagerAccess();
        access.task = new RenderManagerAccess.TaskInfo(null, "Saving map data", 0.9, 5000L);

        ProgressSnapshot snapshot = new RenderProgressProbe(() -> access).sample();

        assertEquals(ProgressSnapshot.State.RENDERING, snapshot.state());
        assertEquals(null, snapshot.currentMap());
        assertFalse(snapshot.degraded());
    }
}
```

- [ ] **Step 4: Run the test and confirm the failure**

Run: `./gradlew :telemetry-addon:test --tests '*RenderProgressProbeTest*'`
Expected: FAIL, "cannot find symbol: class RenderProgressProbe"

- [ ] **Step 5: Implement `RenderProgressProbe`**

```java
package net.onelitefeather.apus.telemetry.probe;

import java.util.function.Supplier;
import net.onelitefeather.apus.telemetry.ProgressSnapshot;

/**
 * Turns BlueMap's render state into a {@link ProgressSnapshot}.
 *
 * <p>This class never throws. Progress reporting is a convenience: if BlueMap's internals
 * move under us, the render must still run and the snapshot simply degrades.
 */
public final class RenderProgressProbe {

    private final Supplier<RenderManagerAccess> accessSupplier;

    public RenderProgressProbe(Supplier<RenderManagerAccess> accessSupplier) {
        this.accessSupplier = accessSupplier;
    }

    public ProgressSnapshot sample() {
        RenderManagerAccess access;
        try {
            access = accessSupplier.get();
        } catch (Throwable t) {
            return ProgressSnapshot.unknown(describe(t));
        }

        if (access == null) {
            // The BlueMap API has not fired onEnable yet. Normal during startup.
            return new ProgressSnapshot(
                    ProgressSnapshot.State.STARTING, null, -1.0, -1L, -1, -1, false, "waiting for BlueMap API");
        }

        try {
            int queued = access.queuedTasks();
            int threads = access.renderThreads();
            RenderManagerAccess.TaskInfo task = access.currentTask();

            if (task == null) {
                return ProgressSnapshot.idle(queued, threads);
            }

            // BlueMap returns 0 from estimateCurrentRenderTaskTimeRemaining() when it has
            // no basis for an estimate; that is "unknown", not "finishing right now".
            long etaSeconds = task.etaMillis() > 0 ? task.etaMillis() / 1000L : -1L;

            return new ProgressSnapshot(
                    ProgressSnapshot.State.RENDERING,
                    task.mapId(),
                    task.progress(),
                    etaSeconds,
                    queued,
                    threads,
                    false,
                    task.description());
        } catch (Throwable t) {
            return ProgressSnapshot.unknown(describe(t));
        }
    }

    private static String describe(Throwable t) {
        String message = t.getMessage();
        return message == null ? t.getClass().getSimpleName() : t.getClass().getSimpleName() + ": " + message;
    }
}
```

`Throwable` instead of `Exception` is deliberate here: a BlueMap upgrade typically manifests as a `NoSuchMethodError` or `NoClassDefFoundError`, and those specifically must not be allowed to abort the render.

- [ ] **Step 6: Run the test and confirm it passes**

Run: `./gradlew :telemetry-addon:test --tests '*RenderProgressProbeTest*'`
Expected: PASS (6 tests)

- [ ] **Step 7: Implement `BlueMapRenderManagerAccess`**

The first of the two classes with BlueMap imports — the second (`LogTailRenderManagerAccess`) was added in Task 8, after this route turned out not to work in CLI mode.

```java
package net.onelitefeather.apus.telemetry.probe;

import de.bluecolored.bluemap.api.BlueMapAPI;
import de.bluecolored.bluemap.common.api.BlueMapAPIImpl;
import de.bluecolored.bluemap.common.plugin.Plugin;
import de.bluecolored.bluemap.common.rendermanager.MapRenderTask;
import de.bluecolored.bluemap.common.rendermanager.RenderManager;
import de.bluecolored.bluemap.common.rendermanager.RenderTask;

/**
 * Adapts BlueMap's internal {@code RenderManager} to {@link RenderManagerAccess}.
 *
 * <p>The route used here is the one BlueMap documents for addons: {@code BlueMapAPIImpl}
 * exposes {@code plugin()} with a javadoc comment explicitly recommending it for addons
 * that depend on BlueMapCommon, and {@code Plugin} exposes {@code getRenderManager()}.
 * No reflection is involved.
 */
public final class BlueMapRenderManagerAccess implements RenderManagerAccess {

    private final RenderManager renderManager;

    private BlueMapRenderManagerAccess(RenderManager renderManager) {
        this.renderManager = renderManager;
    }

    /**
     * @return an access instance, or {@code null} when this platform exposes no plugin
     *         (in which case there is no internal render manager to read)
     */
    public static BlueMapRenderManagerAccess createOrNull(BlueMapAPI api) {
        if (!(api instanceof BlueMapAPIImpl impl)) {
            return null;
        }
        Plugin plugin = impl.plugin();
        if (plugin == null) {
            return null;
        }
        RenderManager renderManager = plugin.getRenderManager();
        return renderManager == null ? null : new BlueMapRenderManagerAccess(renderManager);
    }

    @Override
    public boolean isRunning() {
        return renderManager.isRunning();
    }

    @Override
    public int queuedTasks() {
        return renderManager.getScheduledRenderTaskCount();
    }

    @Override
    public int renderThreads() {
        return renderManager.getWorkerThreadCount();
    }

    @Override
    public TaskInfo currentTask() {
        RenderTask task = renderManager.getCurrentRenderTask();
        if (task == null) {
            return null;
        }
        String mapId = null;
        if (task instanceof MapRenderTask mapTask && mapTask.getMap() != null) {
            mapId = mapTask.getMap().getId();
        }
        return new TaskInfo(
                mapId,
                task.getDescription(),
                task.estimateProgress(),
                renderManager.estimateCurrentRenderTaskTimeRemaining());
    }
}
```

- [ ] **Step 8: Verify compilation**

Run: `./gradlew :telemetry-addon:compileJava`
Expected: BUILD SUCCESSFUL

**If `BmMap.getId()` doesn't exist:** check the actual method:

```bash
cd /tmp && curl -sL https://repo.bluecolored.de/releases/de/bluecolored/bluemap-core/5.23/bluemap-core-5.23.jar -o core.jar \
  && unzip -p core.jar de/bluecolored/bluemap/core/map/BmMap.class | javap -c - 2>/dev/null | head -40
```

Alternatively, `javap -classpath core.jar de.bluecolored.bluemap.core.map.BmMap` gives the full signature list. Adjust the call and correct this plan.

- [ ] **Step 9: Commit**

```bash
./gradlew spotlessApply
git add -A
git commit -m "feat(telemetry): read render progress through a single blueMap seam"
```

---

### Task 4: HTTP server

**Files:**

- Create: `telemetry-addon/src/main/java/net/onelitefeather/apus/telemetry/TelemetryServer.java`
- Create: `telemetry-addon/src/main/java/net/onelitefeather/apus/telemetry/TelemetryConfig.java`
- Test: `telemetry-addon/src/test/java/net/onelitefeather/apus/telemetry/TelemetryServerTest.java`

**Interfaces:**

- Consumes: `ProgressSnapshot`, `JsonWriter`, `PrometheusWriter` from Task 2
- Produces:

```java
public record TelemetryConfig(String bindAddress, int port, boolean enabled) {
    public static TelemetryConfig fromEnvironment(Function<String, String> env);
}

public final class TelemetryServer implements AutoCloseable {
    public TelemetryServer(TelemetryConfig config, Supplier<ProgressSnapshot> snapshotSupplier);
    public void start() throws IOException;
    public int boundPort();
    @Override public void close();
}
```

Routes: `GET /progress` (JSON), `GET /metrics` (Prometheus text), `GET /healthz` (`"ok"`), everything else 404.

- [ ] **Step 1: Write the failing test for `TelemetryConfig`**

In `TelemetryServerTest.java`:

```java
package net.onelitefeather.apus.telemetry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TelemetryServerTest {

    @Test
    void configDefaultsToPort8099OnAllInterfaces() {
        TelemetryConfig config = TelemetryConfig.fromEnvironment(name -> null);

        assertEquals(8099, config.port());
        assertEquals("0.0.0.0", config.bindAddress());
        assertTrue(config.enabled());
    }

    @Test
    void configReadsOverridesFromEnvironment() {
        Map<String, String> env = Map.of(
                "APUS_TELEMETRY_PORT", "9110",
                "APUS_TELEMETRY_BIND", "127.0.0.1",
                "APUS_TELEMETRY_ENABLED", "false");

        TelemetryConfig config = TelemetryConfig.fromEnvironment(env::get);

        assertEquals(9110, config.port());
        assertEquals("127.0.0.1", config.bindAddress());
        assertFalse(config.enabled());
    }

    @Test
    void configFallsBackToTheDefaultPortOnGarbageInput() {
        TelemetryConfig config =
                TelemetryConfig.fromEnvironment(name -> "APUS_TELEMETRY_PORT".equals(name) ? "not-a-number" : null);

        assertEquals(8099, config.port(), "a bad port must not prevent the addon from starting");
    }
}
```

- [ ] **Step 2: Run the test and confirm the failure**

Run: `./gradlew :telemetry-addon:test --tests '*TelemetryServerTest*'`
Expected: FAIL, "cannot find symbol: class TelemetryConfig"

- [ ] **Step 3: Implement `TelemetryConfig`**

```java
package net.onelitefeather.apus.telemetry;

import java.util.function.Function;

/**
 * Telemetry settings, read from the environment.
 *
 * <p>Environment variables rather than a config file: the addon lives inside BlueMap's
 * config folder, and adding a parallel config format there would be one more thing for
 * the operator to template.
 */
public record TelemetryConfig(String bindAddress, int port, boolean enabled) {

    public static final int DEFAULT_PORT = 8099;
    public static final String DEFAULT_BIND = "0.0.0.0";

    public static TelemetryConfig fromEnvironment(Function<String, String> env) {
        return new TelemetryConfig(
                valueOrDefault(env.apply("APUS_TELEMETRY_BIND"), DEFAULT_BIND),
                parsePort(env.apply("APUS_TELEMETRY_PORT")),
                !"false".equalsIgnoreCase(env.apply("APUS_TELEMETRY_ENABLED")));
    }

    private static String valueOrDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static int parsePort(String value) {
        if (value == null || value.isBlank()) {
            return DEFAULT_PORT;
        }
        try {
            int parsed = Integer.parseInt(value.trim());
            return parsed > 0 && parsed <= 65535 ? parsed : DEFAULT_PORT;
        } catch (NumberFormatException e) {
            return DEFAULT_PORT;
        }
    }
}
```

- [ ] **Step 4: Run the test and confirm it passes**

Run: `./gradlew :telemetry-addon:test --tests '*TelemetryServerTest*'`
Expected: PASS (3 tests)

- [ ] **Step 5: Write the failing test for the server**

Append to `TelemetryServerTest.java`:

```java
    private static HttpResponse<String> get(int port, String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .GET()
                .build();
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void servesProgressAsJson() throws Exception {
        ProgressSnapshot snapshot = new ProgressSnapshot(
                ProgressSnapshot.State.RENDERING, "overworld", 0.5, 60L, 1, 4, false, "Updating");

        // Port 0 lets the OS pick a free port, so parallel test runs never collide.
        TelemetryConfig config = new TelemetryConfig("127.0.0.1", 0, true);
        try (TelemetryServer server = new TelemetryServer(config, () -> snapshot)) {
            server.start();

            HttpResponse<String> response = get(server.boundPort(), "/progress");

            assertEquals(200, response.statusCode());
            assertEquals("application/json", response.headers().firstValue("content-type").orElse(""));
            assertTrue(response.body().contains("\"state\":\"rendering\""), response.body());
        }
    }

    @Test
    void servesMetricsAsPrometheusText() throws Exception {
        ProgressSnapshot snapshot = new ProgressSnapshot(
                ProgressSnapshot.State.RENDERING, "overworld", 0.5, 60L, 1, 4, false, "Updating");

        try (TelemetryServer server = new TelemetryServer(new TelemetryConfig("127.0.0.1", 0, true), () -> snapshot)) {
            server.start();

            HttpResponse<String> response = get(server.boundPort(), "/metrics");

            assertEquals(200, response.statusCode());
            assertTrue(response.body().contains("apus_render_progress_ratio"), response.body());
        }
    }

    @Test
    void servesHealthzEvenWhenTheProbeFails() throws Exception {
        Supplier<ProgressSnapshot> exploding = () -> {
            throw new IllegalStateException("boom");
        };

        try (TelemetryServer server = new TelemetryServer(new TelemetryConfig("127.0.0.1", 0, true), exploding)) {
            server.start();

            assertEquals(200, get(server.boundPort(), "/healthz").statusCode());
            assertEquals(500, get(server.boundPort(), "/progress").statusCode());
        }
    }

    @Test
    void returns404ForUnknownPaths() throws Exception {
        try (TelemetryServer server = new TelemetryServer(
                new TelemetryConfig("127.0.0.1", 0, true), () -> ProgressSnapshot.idle(0, 1))) {
            server.start();

            assertEquals(404, get(server.boundPort(), "/nope").statusCode());
        }
    }
```

Add the import `java.util.function.Supplier` at the top of the file.

- [ ] **Step 6: Run the test and confirm the failure**

Run: `./gradlew :telemetry-addon:test --tests '*TelemetryServerTest*'`
Expected: FAIL, "cannot find symbol: class TelemetryServer"

- [ ] **Step 7: Implement `TelemetryServer`**

**Updated to reflect the delivered state:** `HttpServer.createContext` matches paths as a
prefix, not exactly — a context for `/progress` would happily also serve `/progressX` or
`/progress/nested`. This was discovered and fixed during implementation: every handler
below ships wrapped in an `exactPath(...)` wrapper that returns 404 on anything but an
exact match, instead of relying on `createContext`'s prefix matching. The sketch below
deliberately still shows the original, naive version without `exactPath()` — for the code
actually delivered, see
`telemetry-addon/src/main/java/net/onelitefeather/apus/telemetry/TelemetryServer.java`.

```java
package net.onelitefeather.apus.telemetry;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import java.util.function.Supplier;

/**
 * Exposes render progress over HTTP.
 *
 * <p>Uses the JDK's built-in {@link HttpServer} on purpose: the addon must not ship any
 * dependency that could clash with BlueMap's own classpath.
 */
public final class TelemetryServer implements AutoCloseable {

    private final TelemetryConfig config;
    private final Supplier<ProgressSnapshot> snapshotSupplier;
    private HttpServer server;

    public TelemetryServer(TelemetryConfig config, Supplier<ProgressSnapshot> snapshotSupplier) {
        this.config = config;
        this.snapshotSupplier = snapshotSupplier;
    }

    public void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(config.bindAddress(), config.port()), 0);
        server.createContext("/progress", exchange -> respondWith(exchange, "application/json", JsonWriter::toJson));
        server.createContext(
                "/metrics", exchange -> respondWith(exchange, "text/plain; version=0.0.4", PrometheusWriter::toPrometheus));
        server.createContext("/healthz", exchange -> send(exchange, 200, "text/plain", "ok"));
        server.createContext("/", exchange -> send(exchange, 404, "text/plain", "not found"));
        // A single daemon thread is plenty: the operator polls once per second.
        server.setExecutor(Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "apus-telemetry");
            thread.setDaemon(true);
            return thread;
        }));
        server.start();
    }

    public int boundPort() {
        if (server == null) {
            throw new IllegalStateException("server not started");
        }
        return server.getAddress().getPort();
    }

    @Override
    public void close() {
        if (server != null) {
            server.stop(0);
            server = null;
        }
    }

    private void respondWith(HttpExchange exchange, String contentType, Formatter formatter) throws IOException {
        String body;
        try {
            body = formatter.format(snapshotSupplier.get());
        } catch (Throwable t) {
            send(exchange, 500, "text/plain", "progress unavailable: " + t.getClass().getSimpleName());
            return;
        }
        send(exchange, 200, contentType, body);
    }

    private static void send(HttpExchange exchange, int status, String contentType, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    @FunctionalInterface
    private interface Formatter {
        String format(ProgressSnapshot snapshot);
    }
}
```

- [ ] **Step 8: Run the test and confirm it passes**

Run: `./gradlew :telemetry-addon:test --tests '*TelemetryServerTest*'`
Expected: PASS (7 tests)

Note: `/healthz` deliberately returns 200 even when the probe fails — the process is healthy, just the measurement isn't. A k8s liveness probe must not kill the render over a telemetry failure.

- [ ] **Step 9: Commit**

```bash
./gradlew spotlessApply
git add -A
git commit -m "feat(telemetry): serve progress over http as json and prometheus metrics"
```

---

### Task 5: Addon entrypoint

**Files:**

- Create: `telemetry-addon/src/main/java/net/onelitefeather/apus/telemetry/ApusTelemetryAddon.java`
- Create: `telemetry-addon/src/main/resources/bluemap.addon.json`
- Test: `telemetry-addon/src/test/java/net/onelitefeather/apus/telemetry/AddonManifestTest.java`

**Interfaces:**

- Consumes: `TelemetryConfig`, `TelemetryServer` (Task 4), `RenderProgressProbe`, `BlueMapRenderManagerAccess` (Task 3)
- Produces: the class `net.onelitefeather.apus.telemetry.ApusTelemetryAddon` as the addon entrypoint, referenced in `bluemap.addon.json`

- [ ] **Step 1: Write the failing test for the manifest**

This test catches the most common mistake with this kind of addon: a typo in the entrypoint path that only surfaces at runtime in the cluster.

`AddonManifestTest.java`:

```java
package net.onelitefeather.apus.telemetry;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class AddonManifestTest {

    private String manifest() throws Exception {
        try (InputStream in = getClass().getResourceAsStream("/bluemap.addon.json")) {
            assertNotNull(in, "bluemap.addon.json must be on the classpath");
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void declaresTheApusTelemetryId() throws Exception {
        assertTrue(manifest().contains("\"id\""), manifest());
        assertTrue(manifest().contains("apus-telemetry"), manifest());
    }

    @Test
    void entrypointClassExistsAndIsRunnable() throws Exception {
        Matcher matcher = Pattern.compile("\"entrypoint\"\\s*:\\s*\"([^\"]+)\"").matcher(manifest());
        assertTrue(matcher.find(), "manifest must declare an entrypoint");

        String className = matcher.group(1);
        Class<?> entrypoint = assertDoesNotThrow(
                () -> Class.forName(className), "entrypoint class named in bluemap.addon.json must exist");

        assertTrue(Runnable.class.isAssignableFrom(entrypoint), "BlueMap only runs entrypoints implementing Runnable");
        assertDoesNotThrow(
                () -> entrypoint.getDeclaredConstructor().newInstance(),
                "BlueMap instantiates the entrypoint with a no-arg constructor");
    }
}
```

- [ ] **Step 2: Run the test and confirm the failure**

Run: `./gradlew :telemetry-addon:test --tests '*AddonManifestTest*'`
Expected: FAIL, "bluemap.addon.json must be on the classpath"

- [ ] **Step 3: Write `bluemap.addon.json`**

`telemetry-addon/src/main/resources/bluemap.addon.json`:

```json
{
  "id": "apus-telemetry",
  "entrypoint": "net.onelitefeather.apus.telemetry.ApusTelemetryAddon"
}
```

- [ ] **Step 4: Implement `ApusTelemetryAddon`**

```java
package net.onelitefeather.apus.telemetry;

import de.bluecolored.bluemap.api.BlueMapAPI;
import java.util.concurrent.atomic.AtomicReference;
import net.onelitefeather.apus.telemetry.probe.BlueMapRenderManagerAccess;
import net.onelitefeather.apus.telemetry.probe.RenderManagerAccess;
import net.onelitefeather.apus.telemetry.probe.RenderProgressProbe;

/**
 * Entrypoint of the Apus telemetry addon.
 *
 * <p>BlueMap's {@code AddonLoader} instantiates this class and calls {@link #run()} once,
 * early during startup — before the API is ready. We therefore only register a callback
 * here and start serving immediately; until the API fires, {@code /progress} reports
 * {@code starting}.
 */
public final class ApusTelemetryAddon implements Runnable {

    private final AtomicReference<RenderManagerAccess> access = new AtomicReference<>();
    private TelemetryServer server;

    @Override
    public void run() {
        TelemetryConfig config = TelemetryConfig.fromEnvironment(System::getenv);
        if (!config.enabled()) {
            System.out.println("[apus-telemetry] disabled via APUS_TELEMETRY_ENABLED=false");
            return;
        }

        RenderProgressProbe probe = new RenderProgressProbe(access::get);
        server = new TelemetryServer(config, probe::sample);

        try {
            server.start();
            System.out.println("[apus-telemetry] listening on " + config.bindAddress() + ":" + server.boundPort());
        } catch (Exception e) {
            // A failed telemetry server must never stop a render from happening.
            System.err.println("[apus-telemetry] failed to start: " + e);
            return;
        }

        BlueMapAPI.onEnable(api -> {
            RenderManagerAccess resolved = BlueMapRenderManagerAccess.createOrNull(api);
            access.set(resolved);
            if (resolved == null) {
                System.err.println("[apus-telemetry] no plugin instance available; progress will report as unknown");
            }
        });
        BlueMapAPI.onDisable(api -> access.set(null));

        Runtime.getRuntime().addShutdownHook(new Thread(this::stop, "apus-telemetry-shutdown"));
    }

    private void stop() {
        if (server != null) {
            server.close();
        }
    }
}
```

- [ ] **Step 5: Run the test and confirm it passes**

Run: `./gradlew :telemetry-addon:test --tests '*AddonManifestTest*'`
Expected: PASS (2 tests)

- [ ] **Step 6: Build the fat jar and inspect its contents**

```bash
./gradlew :telemetry-addon:shadowJar
unzip -l telemetry-addon/build/libs/apus-telemetry-addon-999.0.0.jar | head -30
```

Expected: `bluemap.addon.json` sits at the jar's root, the `net/onelitefeather/apus/telemetry/` classes are present, and **no** `de/bluecolored/` classes (those are `compileOnly`).

- [ ] **Step 7: Run the full test suite**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL, all tests green

- [ ] **Step 8: Commit**

```bash
./gradlew spotlessApply
git add -A
git commit -m "feat(telemetry): add bluemap addon entrypoint and manifest"
```

---

### Task 6: Runner image

**Files:**

- Create: `runner/Dockerfile`
- Create: `runner/entrypoint.sh`
- Create: `runner/bin/render-config.sh`
- Create: `runner/bin/bundle-sync.sh`
- Create: `runner/README.md`

**Interfaces:**

- Consumes: `apus-telemetry-addon-<version>.jar` from Task 5
- Produces: image `apus/runner:dev` with the following contract via environment variables:

| Variable | Required | Meaning |
| --- | --- | --- |
| `APUS_MAP_ID` | yes | Map id, e.g. `overworld` |
| `APUS_DIMENSION` | yes | `minecraft:overworld`, `minecraft:the_nether`, `minecraft:the_end` |
| `APUS_MC_VERSION` | yes | e.g. `1.21.10` |
| `APUS_WORLD_S3_URL` | yes | Source of the world, e.g. `s3://bundles/worlds/t/survival/v1/overworld` |
| `APUS_MAP_BUCKET` | yes | Target bucket for the rendered map |
| `APUS_MAP_PREFIX` | no | Prefix within the target bucket, default `.` |
| `APUS_S3_ENDPOINT` | yes | e.g. `http://minio:9000` |
| `APUS_S3_ACCESS_KEY` | yes | Access key |
| `APUS_S3_SECRET_KEY` | yes | Secret key |
| `APUS_S3_REGION` | no | Default `us-east-1` |
| `APUS_RENDER_THREADS` | no | Default `2` |
| `APUS_FORCE_RENDER` | no | `true` adds `-f` |
| `APUS_TELEMETRY_PORT` | no | Default `8099` |

- [ ] **Step 1: Write `runner/Dockerfile`**

```dockerfile
# syntax=docker/dockerfile:1

########################################
# Stage 1: fetch the BlueMap CLI
########################################
FROM eclipse-temurin:25-jre-jammy AS fetch

ARG BLUEMAP_VERSION=5.23

RUN apt-get update \
 && apt-get install -y --no-install-recommends curl ca-certificates \
 && rm -rf /var/lib/apt/lists/*

WORKDIR /download
RUN curl -fsSL -o bluemap-cli.jar \
      "https://github.com/BlueMap-Minecraft/BlueMap/releases/download/v${BLUEMAP_VERSION}/bluemap-${BLUEMAP_VERSION}-cli.jar"

########################################
# Stage 2: runtime
########################################
FROM eclipse-temurin:25-jre-jammy

RUN apt-get update \
 && apt-get install -y --no-install-recommends ca-certificates jq curl \
 && curl -fsSL -o /usr/local/bin/mc https://dl.min.io/client/mc/release/linux-amd64/mc \
 && chmod +x /usr/local/bin/mc \
 && apt-get purge -y curl \
 && apt-get autoremove -y \
 && rm -rf /var/lib/apt/lists/*

# Non-root: the render only ever writes below /work.
RUN useradd --uid 10001 --create-home --home-dir /home/apus apus \
 && mkdir -p /work/config/packs /work/data /work/world \
 && chown -R apus:apus /work

COPY --from=fetch /download/bluemap-cli.jar /opt/bluemap/cli.jar
COPY --chown=apus:apus runner/entrypoint.sh /opt/apus/entrypoint.sh
COPY --chown=apus:apus runner/bin/ /opt/apus/bin/
# Built by: ./gradlew :telemetry-addon:shadowJar
COPY --chown=apus:apus telemetry-addon/build/libs/apus-telemetry-addon-*.jar /work/config/packs/apus-telemetry.jar
# The S3 storage addon; see BlueMapS3Storage releases.
COPY --chown=apus:apus runner/vendor/BlueMapS3Storage.jar /work/config/packs/bluemap-s3-storage.jar

RUN chmod +x /opt/apus/entrypoint.sh /opt/apus/bin/*.sh

USER apus
WORKDIR /work

ENV APUS_TELEMETRY_PORT=8099 \
    APUS_RENDER_THREADS=2 \
    APUS_S3_REGION=us-east-1 \
    APUS_MAP_PREFIX=.

EXPOSE 8099

ENTRYPOINT ["/opt/apus/entrypoint.sh"]
```

- [ ] **Step 2: Write `runner/bin/render-config.sh`**

```bash
#!/usr/bin/env bash
# Generates the complete BlueMap configuration from environment variables.
# Users of Apus never write HOCON by hand; this is where it comes from.
set -euo pipefail

CONFIG_DIR="${1:?config dir required}"

mkdir -p "${CONFIG_DIR}/maps" "${CONFIG_DIR}/storages" "${CONFIG_DIR}/packs"

cat > "${CONFIG_DIR}/core.conf" <<EOF
accept-download: true
data: "/work/data"
render-thread-count: ${APUS_RENDER_THREADS}
metrics: false
scan-for-mod-resources: false
EOF

cat > "${CONFIG_DIR}/maps/${APUS_MAP_ID}.conf" <<EOF
world: "/work/world"
dimension: "${APUS_DIMENSION}"
name: "${APUS_MAP_ID}"
sorting: 0
storage: "s3"
render-edges: true
EOF

# The storage id is the file name; map.conf references it via storage: "s3".
cat > "${CONFIG_DIR}/storages/s3.conf" <<EOF
storage-type: "themeinerlp:s3"
bucket-name: "${APUS_MAP_BUCKET}"
region: "${APUS_S3_REGION}"
access-key-id: "${APUS_S3_ACCESS_KEY}"
secret-access-key: "${APUS_S3_SECRET_KEY}"
endpoint-url: "${APUS_S3_ENDPOINT}"
compression: "gzip"
root-path: "${APUS_MAP_PREFIX}"
force-path-style: true
EOF

echo "[apus] wrote BlueMap config to ${CONFIG_DIR}"
```

- [ ] **Step 3: Write `runner/bin/bundle-sync.sh`**

```bash
#!/usr/bin/env bash
# Pulls the world data from S3 onto the local volume.
# BlueMap has no hook for custom world sources, so the files must exist locally.
set -euo pipefail

TARGET="${1:?target dir required}"

mc alias set apus "${APUS_S3_ENDPOINT}" "${APUS_S3_ACCESS_KEY}" "${APUS_S3_SECRET_KEY}" >/dev/null

SOURCE="${APUS_WORLD_S3_URL#s3://}"

echo "[apus] syncing world from ${APUS_WORLD_S3_URL} to ${TARGET}"
mkdir -p "${TARGET}"
mc mirror --overwrite --remove "apus/${SOURCE}" "${TARGET}"

if [ ! -d "${TARGET}/region" ]; then
  echo "[apus] ERROR: no region/ directory found in the synced world at ${TARGET}" >&2
  ls -la "${TARGET}" >&2
  exit 3
fi

REGION_COUNT=$(find "${TARGET}/region" -name '*.mca' | wc -l)
echo "[apus] synced ${REGION_COUNT} region files"
```

- [ ] **Step 4: Write `runner/entrypoint.sh`**

**Updated to reflect the delivered state (Task 6/8):** `APUS_MC_VERSION` is required, not
optional — consistent with the contract table above, which always listed it as `yes`. The
`if [ -n ... ]` fallback originally sketched here let the BlueMap CLI start without `-v`,
forcing BlueMap to guess the Minecraft version itself; that is unsupported anywhere and was
corrected before delivery.

**Updated to reflect the delivered state:** in the delivered script, the required-variable
check runs through a small `require_env` helper function that names each variable only
once, instead of repeating it line by line as originally sketched here — the old,
repetitive style was misinterpreted by a secret scanner as a possible high-entropy value.
The code block below shows the delivered version.

```bash
#!/usr/bin/env bash
set -euo pipefail

# Aborts immediately with a clear message if the named environment variable is unset or empty.
require_env() {
  local name="$1"
  if [ -z "${!name:-}" ]; then
    echo "${name} is required" >&2
    exit 1
  fi
}

require_env APUS_MAP_ID
require_env APUS_DIMENSION
require_env APUS_MC_VERSION
require_env APUS_WORLD_S3_URL
require_env APUS_MAP_BUCKET
require_env APUS_S3_ENDPOINT
require_env APUS_S3_ACCESS_KEY
require_env APUS_S3_SECRET_KEY

CONFIG_DIR=/work/config
WORLD_DIR=/work/world

/opt/apus/bin/bundle-sync.sh "${WORLD_DIR}"
/opt/apus/bin/render-config.sh "${CONFIG_DIR}"

ARGS=(-c "${CONFIG_DIR}" -r -m "${APUS_MAP_ID}" -v "${APUS_MC_VERSION}")

if [ "${APUS_FORCE_RENDER:-false}" = "true" ]; then
  ARGS+=(-f)
fi

echo "[apus] starting BlueMap: ${ARGS[*]}"
exec java -jar /opt/bluemap/cli.jar "${ARGS[@]}"
```

`exec` matters here: it makes BlueMap PID 1, so it receives `SIGTERM` directly when Kubernetes terminates the pod.

- [ ] **Step 5: Write `runner/README.md`**

**Updated to reflect the delivered state:** `releases/latest/download/BlueMapS3Storage.jar`
returns 404 — the release asset is versioned (e.g. `BlueMapS3Storage-1.5.1.jar`). The URL
below has therefore already been corrected to the versioned form; the authoritative,
always-current version lives in `runner/README.md` itself.

````markdown
# Apus Runner Image

Renders a Minecraft world from S3 with BlueMap and writes the result back to S3.

## Build

```bash
./gradlew :telemetry-addon:shadowJar
mkdir -p runner/vendor
curl -fsSL -o runner/vendor/BlueMapS3Storage.jar \
  https://github.com/TheMeinerLP/BlueMapS3Storage/releases/download/v1.5.1/BlueMapS3Storage-1.5.1.jar
docker build -f runner/Dockerfile -t apus/runner:dev .
```

The build context is the repository root, because the image needs the addon jar
built by Gradle.

## Run

```bash
docker run --rm -p 8099:8099 \
  -e APUS_MAP_ID=overworld \
  -e APUS_DIMENSION=minecraft:overworld \
  -e APUS_MC_VERSION=1.21.10 \
  -e APUS_WORLD_S3_URL=s3://bundles/worlds/demo/survival/v1/overworld \
  -e APUS_MAP_BUCKET=apus-maps \
  -e APUS_S3_ENDPOINT=http://minio:9000 \
  -e APUS_S3_ACCESS_KEY=... \
  -e APUS_S3_SECRET_KEY=... \
  apus/runner:dev
```

Progress is available at `http://localhost:8099/progress` while the render runs.

## Exit codes

Inherited from the BlueMap CLI: `0` success, `1` configuration or IO error,
`2` missing Minecraft resources. `3` is added by `bundle-sync.sh` when the synced
world contains no `region/` directory.
````

- [ ] **Step 6: Build the image**

```bash
cd /mnt/projects/oss/onelitefeather/Apus
./gradlew :telemetry-addon:shadowJar
mkdir -p runner/vendor
curl -fsSL -o runner/vendor/BlueMapS3Storage.jar \
  https://github.com/TheMeinerLP/BlueMapS3Storage/releases/download/v1.5.1/BlueMapS3Storage-1.5.1.jar
docker build -f runner/Dockerfile -t apus/runner:dev .
```

Expected: image builds successfully.

**If downloading `BlueMapS3Storage.jar` fails:** the release asset name may differ. Check with `gh release view --repo TheMeinerLP/BlueMapS3Storage --json assets` and adjust the URL and `runner/README.md` accordingly. Alternatively, build locally: `(cd ../BlueMapS3Storage && ./gradlew shadowJar)` and copy the jar from `build/libs/`.

- [ ] **Step 7: Smoke-test the image**

```bash
docker run --rm --entrypoint java apus/runner:dev -jar /opt/bluemap/cli.jar -V
```

Expected: prints the BlueMap version, exit code 0.

```bash
docker run --rm --entrypoint sh apus/runner:dev -c 'ls -la /work/config/packs && id'
```

Expected: both addon jars are in `packs/`, and the process runs as `uid=10001(apus)`.

- [ ] **Step 8: Verify that missing required variables fail cleanly**

```bash
docker run --rm apus/runner:dev; echo "exit=$?"
```

Expected: error message `APUS_MAP_ID is required`, exit code non-zero.

- [ ] **Step 9: Commit**

```bash
git add -A
git commit -m "feat(runner): add container image running bluemap cli with apus addons"
```

`runner/vendor/` is already in `.gitignore` (Task 1) — the downloaded third-party jar does not belong in the repository.

---

### Task 7: Integration test against MinIO

**Files:**

- Create: `testdata/README.md`
- Create: `testdata/mini-world/` (fixture)
- Create: `runner/build.gradle.kts`
- Create: `runner/src/test/java/net/onelitefeather/apus/runner/RenderEndToEndTest.java`
- Modify: `settings.gradle.kts` (add the `runner` module)

**Interfaces:**

- Consumes: image `apus/runner:dev` from Task 6, the environment variable contract from Task 6
- Produces: confirmation that `storage-type: "themeinerlp:s3"` and the kebab-case keys are correct; corrects Task 6 **and** the spec if they differ

- [ ] **Step 1: Create the mini-world fixture**

Cut a small, deterministic fixture from the existing demo world. **Only `region/` and `level.dat`** — `playerdata/`, `stats/`, and `advancements/` contain personal data and must not go into the repository.

```bash
cd /mnt/projects/oss/onelitefeather/Apus
SRC=../falco-demo-world-backup-1.21.10
mkdir -p testdata/mini-world/region

# Two adjacent regions produce one contiguous area
for f in r.0.0.mca r.0.1.mca; do
  if [ -f "$SRC/region/$f" ]; then cp "$SRC/region/$f" testdata/mini-world/region/; fi
done

# If these coordinates don't exist, take the two smallest files instead:
if [ -z "$(ls -A testdata/mini-world/region)" ]; then
  find "$SRC/region" -name '*.mca' -printf '%s %p\n' | sort -n | head -2 | cut -d' ' -f2- \
    | xargs -I{} cp {} testdata/mini-world/region/
fi

cp "$SRC/level.dat" testdata/mini-world/
du -sh testdata/mini-world
ls -la testdata/mini-world/region
```

Expected: directory under roughly 20 MB. If it's bigger, keep only a single region.

- [ ] **Step 2: Write `testdata/README.md`**

```markdown
# Test fixtures

## mini-world

A minimal Vanilla-layout Minecraft world used by the render integration tests.

- **Origin:** extracted from an internal demo world backup (Minecraft 1.21.10).
- **Contents:** `level.dat` plus one or two `region/*.mca` files. Nothing else.
- **Deliberately excluded:** `playerdata/`, `stats/`, `advancements/` — these contain
  personal data and must never be committed.
- **Layout:** Vanilla (`region/` directly below the world root), so BlueMap resolves
  `minecraft:overworld` without any dimension sub-folder.

Regenerate with the snippet in
`docs/superpowers/plans/2026-08-08-phase-1-render-kern.md`, Task 7, Step 1.
```

- [ ] **Step 3: Register the `runner` module**

In `settings.gradle.kts`, replace the include line:

```kotlin
include("telemetry-addon", "runner")
```

- [ ] **Step 4: Write `runner/build.gradle.kts`**

```kotlin
dependencies {
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)

    testImplementation(platform(libs.testcontainers.bom))
    testImplementation(libs.testcontainers.junit)
    testImplementation(libs.testcontainers.minio)
}

tasks.test {
    // The image must exist; building it is not this task's job.
    // Build it with: docker build -f runner/Dockerfile -t apus/runner:dev .
    systemProperty("apus.runner.image", System.getProperty("apus.runner.image", "apus/runner:dev"))
    // A full render of the fixture takes minutes, not seconds.
    timeout.set(java.time.Duration.ofMinutes(20))
}
```

- [ ] **Step 5: Write the failing end-to-end test**

`runner/src/test/java/net/onelitefeather/apus/runner/RenderEndToEndTest.java`:

```java
package net.onelitefeather.apus.runner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.BindMode;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MinIOContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.output.Slf4jLogConsumer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

/**
 * Proves the whole Phase 1 contract: world in from S3, map out to S3, progress over HTTP.
 *
 * <p>Requires the runner image to be built beforehand:
 * {@code docker build -f runner/Dockerfile -t apus/runner:dev .}
 */
class RenderEndToEndTest {

    private static final String ACCESS_KEY = "<generated-at-test-runtime>";
    private static final String SECRET_KEY = "<generated-at-test-runtime>";
    private static final String WORLD_BUCKET = "bundles";
    private static final String MAP_BUCKET = "maps";

    private static Path fixture() {
        return Path.of(System.getProperty("user.dir")).getParent().resolve("testdata/mini-world");
    }

    @Test
    void rendersAWorldFromS3BackIntoS3AndReportsProgress() throws Exception {
        try (Network network = Network.newNetwork();
                MinIOContainer minio = new MinIOContainer(DockerImageName.parse("minio/minio:RELEASE.2024-11-07T00-52-20Z"))
                        .withUserName(ACCESS_KEY)
                        .withPassword(SECRET_KEY)
                        .withNetwork(network)
                        .withNetworkAliases("minio")) {

            minio.start();

            // Upload the fixture world using the mc client from a throwaway container.
            try (GenericContainer<?> seeder = new GenericContainer<>(DockerImageName.parse("minio/mc:latest"))
                    .withNetwork(network)
                    .withFileSystemBind(fixture().toString(), "/fixture", BindMode.READ_ONLY)
                    .withCreateContainerCmdModifier(cmd -> cmd.withEntrypoint("/bin/sh"))
                    .withCommand(
                            "-c",
                            "mc alias set m http://minio:9000 " + ACCESS_KEY + " " + SECRET_KEY
                                    + " && mc mb --ignore-existing m/" + WORLD_BUCKET
                                    + " && mc mb --ignore-existing m/" + MAP_BUCKET
                                    + " && mc mirror /fixture m/" + WORLD_BUCKET + "/worlds/demo/v1"
                                    + " && echo SEEDED")
                    .waitingFor(Wait.forLogMessage(".*SEEDED.*", 1).withStartupTimeout(Duration.ofMinutes(3)))) {
                seeder.start();
            }

            String image = System.getProperty("apus.runner.image", "apus/runner:dev");

            try (GenericContainer<?> runner = new GenericContainer<>(DockerImageName.parse(image))
                    .withNetwork(network)
                    .withEnv("APUS_MAP_ID", "overworld")
                    .withEnv("APUS_DIMENSION", "minecraft:overworld")
                    .withEnv("APUS_MC_VERSION", "1.21.10")
                    .withEnv("APUS_WORLD_S3_URL", "s3://" + WORLD_BUCKET + "/worlds/demo/v1")
                    .withEnv("APUS_MAP_BUCKET", MAP_BUCKET)
                    .withEnv("APUS_MAP_PREFIX", "demo")
                    .withEnv("APUS_S3_ENDPOINT", "http://minio:9000")
                    .withEnv("APUS_S3_ACCESS_KEY", ACCESS_KEY)
                    .withEnv("APUS_S3_SECRET_KEY", SECRET_KEY)
                    .withEnv("APUS_RENDER_THREADS", "2")
                    .withLogConsumer(new Slf4jLogConsumer(org.slf4j.LoggerFactory.getLogger("runner")))
                    .waitingFor(Wait.forLogMessage(".*starting BlueMap.*", 1)
                            .withStartupTimeout(Duration.ofMinutes(5)))) {

                runner.start();

                // Wait for the container to exit on its own; a render-only run must terminate.
                long deadline = System.currentTimeMillis() + Duration.ofMinutes(15).toMillis();
                while (runner.isRunning() && System.currentTimeMillis() < deadline) {
                    Thread.sleep(2000);
                }

                assertTrue(!runner.isRunning(), "render container must exit after rendering, it must not hang");

                Long exitCode = runner.getCurrentContainerInfo().getState().getExitCodeLong();
                assertEquals(0L, exitCode, "BlueMap CLI must exit 0; logs:\n" + runner.getLogs());
            }

            // Verify that map data actually landed in the target bucket.
            try (GenericContainer<?> verifier = new GenericContainer<>(DockerImageName.parse("minio/mc:latest"))
                    .withNetwork(network)
                    .withCreateContainerCmdModifier(cmd -> cmd.withEntrypoint("/bin/sh"))
                    .withCommand(
                            "-c",
                            "mc alias set m http://minio:9000 " + ACCESS_KEY + " " + SECRET_KEY
                                    + " && COUNT=$(mc ls --recursive m/" + MAP_BUCKET + " | wc -l)"
                                    + " && echo OBJECTS=$COUNT"
                                    + " && test \"$COUNT\" -gt 0")
                    .waitingFor(Wait.forLogMessage(".*OBJECTS=.*", 1).withStartupTimeout(Duration.ofMinutes(2)))) {
                verifier.start();
                String logs = verifier.getLogs();
                assertTrue(logs.contains("OBJECTS="), logs);
                assertTrue(!logs.contains("OBJECTS=0"), "map bucket must not be empty after a render:\n" + logs);
            }
        }
    }
}
```

Add `testImplementation("org.slf4j:slf4j-simple:2.0.16")` to the test dependencies in `runner/build.gradle.kts`, so `Slf4jLogConsumer` actually shows output.

- [ ] **Step 6: Run the test and expect a failure**

```bash
./gradlew :telemetry-addon:shadowJar
docker build -f runner/Dockerfile -t apus/runner:dev .
./gradlew :runner:test
```

Expected: FAIL — most likely because `storage-type` or the field names in `storages/s3.conf` are wrong. This is exactly what this test is for.

- [ ] **Step 7: Fix the `s3.conf` format based on the error message**

The BlueMap error message names the unknown storage type or the unknown field. Check it against the source:

```bash
grep -n "Key(" ../BlueMapS3Storage/src/main/java/dev/themeinerlp/bluemap/s3/S3StorageAddon.java
grep -rn "NamingScheme\|NAMING" ../BlueMapS3Storage/src/main/java/ || true
```

The registry key is `new Key("themeinerlp", "s3")`, which yields `themeinerlp:s3`. If BlueMap expects a different format, the error message shows the allowed values.

For field names: Configurate maps camelCase to kebab-case by default (`renderThreadCount` → `render-thread-count`). If the fields remain unknown, try the camelCase variant (`bucketName` instead of `bucket-name`).

Adjust `runner/bin/render-config.sh`, rebuild the image, and repeat.

- [ ] **Step 8: Run the test and confirm it passes**

```bash
docker build -f runner/Dockerfile -t apus/runner:dev .
./gradlew :runner:test
```

Expected: PASS

- [ ] **Step 9: Correct the spec if the format differed**

If Step 7 required changes, update the "Configuration formats" section of this plan **and** §9.2 of the spec `docs/superpowers/specs/2026-08-08-apus-design.md` to the verified values. The operator generates exactly this file in Phase 2 — a wrong assumption there would propagate.

- [ ] **Step 10: Commit**

```bash
git add -A
git commit -m "test(runner): verify end-to-end render from s3 to s3 against minio"
```

---

### Task 8: Prove telemetry works in a real render

**Files:**

- Modify: `runner/src/test/java/net/onelitefeather/apus/runner/RenderEndToEndTest.java`
- Create: `runner/src/test/java/net/onelitefeather/apus/runner/TelemetryContractTest.java`

**Interfaces:**

- Consumes: everything from Task 7
- Produces: proof that `/progress` delivers reliable values during a real render — the contract test that exposes a BlueMap upgrade

- [ ] **Step 1: Write the failing contract test**

`TelemetryContractTest.java`:

```java
package net.onelitefeather.apus.runner;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.BindMode;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MinIOContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

/**
 * The early-warning system for BlueMap upgrades.
 *
 * <p>If BlueMap changes how the internal render manager is reached, every unit test still
 * passes — only this test fails. Run it against every BlueMap version Apus claims to
 * support before releasing.
 */
class TelemetryContractTest {

    private static final String ACCESS_KEY = "<generated-at-test-runtime>";
    private static final String SECRET_KEY = "<generated-at-test-runtime>";

    private static Path fixture() {
        return Path.of(System.getProperty("user.dir")).getParent().resolve("testdata/mini-world");
    }

    @Test
    void progressEndpointReportsARunningRenderWithARealPercentage() throws Exception {
        try (Network network = Network.newNetwork();
                MinIOContainer minio = new MinIOContainer(DockerImageName.parse("minio/minio:RELEASE.2024-11-07T00-52-20Z"))
                        .withUserName(ACCESS_KEY)
                        .withPassword(SECRET_KEY)
                        .withNetwork(network)
                        .withNetworkAliases("minio")) {

            minio.start();

            try (GenericContainer<?> seeder = new GenericContainer<>(DockerImageName.parse("minio/mc:latest"))
                    .withNetwork(network)
                    .withFileSystemBind(fixture().toString(), "/fixture", BindMode.READ_ONLY)
                    .withCreateContainerCmdModifier(cmd -> cmd.withEntrypoint("/bin/sh"))
                    .withCommand(
                            "-c",
                            "mc alias set m http://minio:9000 " + ACCESS_KEY + " " + SECRET_KEY
                                    + " && mc mb --ignore-existing m/bundles && mc mb --ignore-existing m/maps"
                                    + " && mc mirror /fixture m/bundles/worlds/demo/v1 && echo SEEDED")
                    .waitingFor(Wait.forLogMessage(".*SEEDED.*", 1).withStartupTimeout(Duration.ofMinutes(3)))) {
                seeder.start();
            }

            try (GenericContainer<?> runner = new GenericContainer<>(
                            DockerImageName.parse(System.getProperty("apus.runner.image", "apus/runner:dev")))
                    .withNetwork(network)
                    .withExposedPorts(8099)
                    .withEnv("APUS_MAP_ID", "overworld")
                    .withEnv("APUS_DIMENSION", "minecraft:overworld")
                    .withEnv("APUS_MC_VERSION", "1.21.10")
                    .withEnv("APUS_WORLD_S3_URL", "s3://bundles/worlds/demo/v1")
                    .withEnv("APUS_MAP_BUCKET", "maps")
                    .withEnv("APUS_S3_ENDPOINT", "http://minio:9000")
                    .withEnv("APUS_S3_ACCESS_KEY", ACCESS_KEY)
                    .withEnv("APUS_S3_SECRET_KEY", SECRET_KEY)
                    .withEnv("APUS_FORCE_RENDER", "true")
                    .waitingFor(Wait.forLogMessage(".*apus-telemetry] listening.*", 1)
                            .withStartupTimeout(Duration.ofMinutes(5)))) {

                runner.start();

                String base = "http://" + runner.getHost() + ":" + runner.getMappedPort(8099);
                HttpClient client = HttpClient.newHttpClient();

                boolean sawRendering = false;
                boolean sawProgress = false;
                String lastBody = "";

                long deadline = System.currentTimeMillis() + Duration.ofMinutes(10).toMillis();
                while (System.currentTimeMillis() < deadline && runner.isRunning()) {
                    HttpResponse<String> response = client.send(
                            HttpRequest.newBuilder(URI.create(base + "/progress")).GET().build(),
                            HttpResponse.BodyHandlers.ofString());
                    lastBody = response.body();

                    if (lastBody.contains("\"degraded\":true")) {
                        fail("telemetry degraded during a real render — the BlueMap access path broke: " + lastBody);
                    }
                    if (lastBody.contains("\"state\":\"rendering\"")) {
                        sawRendering = true;
                        if (!lastBody.contains("\"progress\":-1")) {
                            sawProgress = true;
                            break;
                        }
                    }
                    Thread.sleep(1000);
                }

                assertTrue(sawRendering, "never observed state=rendering; last body: " + lastBody);
                assertTrue(sawProgress, "never observed a real progress value; last body: " + lastBody);
            }
        }
    }
}
```

- [ ] **Step 2: Run the test**

```bash
./gradlew :runner:test --tests '*TelemetryContractTest*'
```

Expected on the first run: possibly FAIL with `degraded:true`. That is the single most valuable piece of feedback in this whole plan — it means the access path via `BlueMapAPIImpl.plugin()` doesn't work in the CLI context.

- [ ] **Step 3: If `degraded:true`, diagnose the access path**

Most likely reason: in CLI mode there is no `Plugin` instance (it exists primarily in server-plugin mode), so `impl.plugin()` returns `null`.

Diagnosis:

```bash
docker run --rm --entrypoint sh apus/runner:dev -c \
  'cd /tmp && unzip -o -q /opt/bluemap/cli.jar "de/bluecolored/bluemap/common/api/BlueMapAPIImpl.class" \
   && javap -p de/bluecolored/bluemap/common/api/BlueMapAPIImpl.class | head -40'
```

Shows the actually available fields and methods. Also check how the CLI provides the API:

```bash
docker run --rm --entrypoint sh apus/runner:dev -c \
  'cd /tmp && unzip -o -q /opt/bluemap/cli.jar "de/bluecolored/bluemap/cli/BlueMapCLI.class" \
   && javap -p -c de/bluecolored/bluemap/cli/BlueMapCLI.class | grep -i "renderManager\|BlueMapAPIImpl" | head -20'
```

If this reveals a different route (say, an accessible `BlueMapService` or a different `BlueMapAPIImpl` constructor), **adjust only `BlueMapRenderManagerAccess`**. All other classes and every unit test stay unchanged — that is exactly what the interface was introduced for.

If no route via the API reaches the internal RenderManager, reflection on the private field `renderManager` in `RenderManagerImpl` is the documented fallback (field name from the research in the Global Constraints). That, too, belongs exclusively in `BlueMapRenderManagerAccess`.

**Updated to reflect the delivered state:** the reflection fallback sketched above was
**not** implemented. The diagnosis in Step 3 confirmed that `impl.plugin()` structurally
always returns `null` in CLI mode, and that BlueMap doesn't even build a `RenderManagerImpl`
instance for it — in this mode there is no field for reflection to reach; the fallback
would have hit a dead end. Instead, `LogTailRenderManagerAccess` was introduced: it
registers itself on BlueMap's own `Logger.global` and parses the progress line that the CLI
logs anyway — a documented extension point, not reflection. As a result, contrary to the
original assumption, BlueMap access consists of **two** implementations of the same
`RenderManagerAccess` interface (`BlueMapRenderManagerAccess` for the API route — dead in
CLI mode — and `LogTailRenderManagerAccess` for the route that actually works), between
which `ApusTelemetryAddon` chooses. Details, the full decompilation findings, and the
discarded `java.util.Timer` reflection alternative are documented in
`runner/README.md#telemetry` and spec §7.2.

- [ ] **Step 4: Implement the fix until the test passes**

```bash
./gradlew :telemetry-addon:shadowJar
docker build -f runner/Dockerfile -t apus/runner:dev .
./gradlew :runner:test --tests '*TelemetryContractTest*'
```

Expected: PASS

- [ ] **Step 5: Document the findings**

Add a "Telemetry" section to `runner/README.md` with the access route that actually works, noting that `TelemetryContractTest` must run on every BlueMap upgrade. Update spec §7.2 if the route differs from what's described there.

- [ ] **Step 6: Full run**

```bash
./gradlew build
./gradlew :runner:test
```

Expected: everything green.

- [ ] **Step 7: Commit**

```bash
./gradlew spotlessApply
git add -A
git commit -m "test(runner): add telemetry contract test against a real bluemap render"
```

---

## Phase 1 completion

After Task 8, the following has been achieved:

- A container image renders a world from S3 to S3, without Kubernetes.
- Progress is available during the run as JSON and as Prometheus metrics.
- All BlueMap access sits behind the `RenderManagerAccess` interface, implemented by two classes (`BlueMapRenderManagerAccess` for the API route, `LogTailRenderManagerAccess` for the log-tail route that actually works in CLI mode) — backed by a contract test that covers the log-tail route.
- Serialization, error handling, and configuration are tested without a running BlueMap instance.

**Not part of Phase 1** (follows in separate plans): operator and CRDs, ingest/ETL, hosting, asset cache for the Minecraft client JAR, region sharding.

**Handoff points to Phase 2:** the environment variable contract from Task 6 is the interface the operator will drive. The `s3.conf` format verified in Task 7 is the template for the operator's configuration generation.
