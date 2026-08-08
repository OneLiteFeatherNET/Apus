# Apus Phase 1 — Render-Kern: Implementierungsplan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ein Container-Image, das eine Minecraft-Welt aus S3 mit BlueMap rendert, das Ergebnis nach S3 schreibt und währenddessen seinen Fortschritt über HTTP als JSON und Prometheus-Metriken meldet — vollständig ohne Kubernetes betreibbar.

**Architecture:** Ein Gradle-Monorepo mit zwei Auslieferungsartefakten. Das `telemetry-addon` ist ein BlueMap-Addon (`implements Runnable`), das über `BlueMapAPI.onEnable` an den internen RenderManager kommt und einen JDK-eigenen HTTP-Server startet. Das `runner`-Image bündelt den BlueMap-CLI, das Telemetry-Addon und das bestehende `BlueMapS3Storage`-Addon mit einem Entrypoint, der die Welt aus S3 holt, die BlueMap-Konfiguration aus Umgebungsvariablen erzeugt und den Render startet. Der gesamte Zugriff auf BlueMap-Interna liegt hinter einer einzigen Schnittstelle (`RenderManagerAccess`), damit er testbar bleibt und später austauschbar ist.

**Tech Stack:** Java 25, Gradle 9.4.1 (Kotlin DSL, Inline-Version-Catalog), JUnit Jupiter, Testcontainers (MinIO), Spotless, Shadow, Docker (eclipse-temurin:25-jre), MinIO Client (`mc`), `jq`.

---

## Global Constraints

Diese Vorgaben gelten für **jede** Aufgabe in diesem Plan:

- **Java-Toolchain: 25.** BlueMap 5.23 wird mit `JavaLanguageVersion.of(25)` gebaut und liefert ein `eclipse-temurin:25-jre-jammy`-Image aus. Gegen `bluemap-common` kompilierter Code braucht daher JDK 25.
- **BlueMap-Version: 5.23.** Artefakte: `de.bluecolored:bluemap-core:5.23`, `de.bluecolored:bluemap-common:5.23`, `de.bluecolored:bluemap-api:2.8.0` (die API hat eine eigene Versionierung). Repository: `https://repo.bluecolored.de/releases`, Pfadstruktur `de/bluecolored/<artifactId>/`.
- **Alle BlueMap-Abhängigkeiten sind `compileOnly`.** Sie liegen zur Laufzeit im CLI-Fat-Jar vor. Werden sie mitgeliefert, entstehen Klassenkonflikte.
- **Lizenz: AGPL-3.0**, wie `BlueMapS3Storage`. Jede Java-Datei trägt den Header aus `.spotless/Copyright.java`, durchgesetzt von Spotless.
- **Java-Basispaket: `net.onelitefeather.apus`.**
- **Gradle-Group: `net.onelitefeather.apus`, Version `999.0.0`** in `gradle.properties` (Release-Please ersetzt sie beim Release — gleiche Konvention wie `BlueMapS3Storage`).
- **Keine Fremdabhängigkeiten zur Laufzeit im Addon.** HTTP über `com.sun.net.httpserver.HttpServer`, JSON über einen eigenen kleinen Writer. Grund: Das Addon läuft in einem eigenen Classloader neben BlueMap; jede mitgelieferte Bibliothek ist ein potenzieller Konflikt.
- **Commit-Konvention: Conventional Commits.** Git-Identität ist bereits im Repo gesetzt (`TheMeinerLP <github@themeinerlp.dev>`). **Keine** Claude-Co-Author- oder „Generated with"-Zeilen.
- **Sprache im Code:** Bezeichner und Javadoc auf Englisch, wie in `BlueMapS3Storage`.

### Verifizierte BlueMap-Fakten

Diese Signaturen wurden gegen den Quellcode von BlueMap 5.23 geprüft und werden im Plan verwendet:

```java
// de.bluecolored.bluemap.api.BlueMapAPI
public static void onEnable(Consumer<BlueMapAPI> consumer)
public static synchronized void onDisable(Consumer<BlueMapAPI> consumer)
public abstract Collection<BlueMapMap> getMaps();

// de.bluecolored.bluemap.common.api.BlueMapAPIImpl
public @Nullable Plugin plugin();          // laut Javadoc ausdrücklich für Addons gedacht

// de.bluecolored.bluemap.common.plugin.Plugin
public RenderManager getRenderManager();   // Lombok-@Getter

// de.bluecolored.bluemap.common.rendermanager.RenderManager
public RenderTask getCurrentRenderTask()             // null, wenn nichts läuft
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

**Kein Reflection erforderlich.** Der Zugang läuft über `((BlueMapAPIImpl) api).plugin().getRenderManager()`. `plugin()` kann `null` liefern, wenn die Plattform kein Plugin-API bereitstellt — das ist als Fehlerfall zu behandeln, nicht als Absturz.

### Verifizierte BlueMap-CLI-Fakten

- Artefakt: `bluemap-5.23-cli.jar`, Download unter `https://github.com/BlueMap-Minecraft/BlueMap/releases/download/v5.23/bluemap-5.23-cli.jar`. Einzelnes Fat-Jar.
- Relevante Optionen: `-c/--config <ordner>`, `-r/--render`, `-f/--force-render`, `-m/--maps <liste>`, `-u/--watch`, `-w/--webserver`, `-v/--mc-version <version>`, `-V/--version`.
- Der `packs/`-Ordner liegt **fest** unter `<config-ordner>/packs` und ist nicht konfigurierbar. Addons **und** Ressourcenpakete kommen dorthin.
- Ohne Aktionsflag schreibt der CLI nur Default-Konfigurationen und beendet sich mit **Exit-Code 1**.
- Exit-Codes: `0` Erfolg, `1` Konfigurations-/IO-/Argumentfehler, `2` fehlende Minecraft-Ressourcen (`accept-download` nicht gesetzt).
- Die Minecraft-Client-JAR wird nach `<data-ordner>/minecraft-client-<versionId>.jar` geladen — **nur wenn die Datei fehlt**. Vorbefüllen verhindert den Download zuverlässig.
- Der CLI setzt seinen Default-Datenordner auf `data` (relativ zum Arbeitsverzeichnis), nicht auf `bluemap`.
- Logging geht auf stdout. Es gibt weder Log-Level- noch JSON-Logging-Schalter.

### Konfigurationsformate

`core.conf` (nur die hier benötigten Schlüssel):

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

`storages/s3.conf` — Felder aus `S3StorageConfiguration` in `BlueMapS3Storage`. Configurate bildet camelCase auf kebab-case ab (erkennbar an `render-thread-count` ↔ `renderThreadCount` in `core.conf`):

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

> **Verifiziert in Task 7:** Der Integrationstest `runner/src/test/java/net/onelitefeather/apus/runner/RenderEndToEndTest.java` rendert die Fixture-Welt aus MinIO gegen einen echten BlueMap-CLI-Lauf mit `themeinerlp:s3` als Storage-Typ und den kebab-case-Feldnamen oben — **unverändert, wie ursprünglich angenommen**. Die BlueMap-Logausgabe bestätigt `Initializing Storage: 's3' (Type: 'themeinerlp:s3')`, und der Render endet mit Exit-Code 0. Zusätzlich bestätigt durch Quellcode-Review: `StorageConfig.storageType` in `bluemap-common` wird über `@Setting`-freies Configurate-Object-Mapping auf `storage-type` abgebildet (siehe `de.bluecolored.bluemap.common.config.storage.StorageConfig`), und `Key.parse(key, Key.BLUEMAP_NAMESPACE)` erwartet exakt das `namespace:value`-Format `themeinerlp:s3` aus `new Key("themeinerlp", "s3")` in `S3StorageAddon`. `render-config.sh` musste dafür nicht geändert werden.

---

## File Structure

```
Apus/
├── settings.gradle.kts                 Module + Inline-Version-Catalog
├── build.gradle.kts                    Root: Spotless, Toolchain für alle Module
├── gradle.properties                   group, version
├── .spotless/Copyright.java            AGPL-Header-Vorlage
├── .gitignore
├── LICENSE                             AGPL-3.0
├── gradle/wrapper/                     Gradle 9.4.1
│
├── telemetry-addon/
│   ├── build.gradle.kts
│   └── src/
│       ├── main/java/net/onelitefeather/apus/telemetry/
│       │   ├── ApusTelemetryAddon.java      Entrypoint (Runnable), Verdrahtung
│       │   ├── TelemetryConfig.java         Konfiguration aus Umgebungsvariablen
│       │   ├── ProgressSnapshot.java        Unveränderliches Datenmodell (record)
│       │   ├── JsonWriter.java              Minimaler JSON-Serialisierer
│       │   ├── PrometheusWriter.java        Prometheus-Textformat
│       │   ├── TelemetryServer.java         HTTP-Server, Routen
│       │   └── probe/
│       │       ├── RenderManagerAccess.java Schmale Schnittstelle auf BlueMap
│       │       ├── RenderProgressProbe.java Erzeugt Snapshots, kapselt Fehlerfälle
│       │       └── BlueMapRenderManagerAccess.java  Einzige Klasse mit BlueMap-Typen
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
│   ├── entrypoint.sh                   Ablaufsteuerung
│   ├── bin/
│   │   ├── render-config.sh            Erzeugt core.conf, maps/, storages/
│   │   └── bundle-sync.sh              Holt Welt-Daten aus S3
│   └── src/test/java/net/onelitefeather/apus/runner/
│       ├── RunnerImageTest.java        Image-Smoke-Test
│       └── RenderEndToEndTest.java     MinIO + echter Render
│
└── testdata/
    ├── README.md                       Herkunft und Erzeugung der Fixture
    └── mini-world/                     Minimale Vanilla-Welt für Tests
```

**Warum diese Aufteilung:** `BlueMapRenderManagerAccess` ist die **einzige** Klasse, die BlueMap-Typen importiert. Alles andere — Snapshot, Serialisierung, HTTP, Fehlerbehandlung — ist reines Java und ohne laufende BlueMap-Instanz testbar. Das macht Phase 1 fast vollständig unit-testbar und begrenzt die Auswirkung eines BlueMap-Upgrades auf eine Datei.

---

## Parallelisierung

Aufgaben mit gleicher Gruppe können gleichzeitig von verschiedenen Agenten bearbeitet werden:

| Gruppe | Aufgaben | Voraussetzung |
|---|---|---|
| A | Task 1 | — |
| B | Task 2, Task 3, Task 4 | Task 1 |
| C | Task 5, Task 6 | Task 2–4 |
| D | Task 7 | Task 5, Task 6 |
| E | Task 8 | Task 7 |

Task 2 (Snapshot + JSON), Task 3 (Probe) und Task 4 (HTTP-Server) berühren getrennte Dateien und sind echt parallel bearbeitbar. Die Schnittstellen zwischen ihnen sind unten in jedem `Interfaces`-Block exakt festgelegt — daran müssen sich alle drei halten, ohne sich abzustimmen.

---

### Task 1: Monorepo-Grundgerüst

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
- Consumes: nichts
- Produces: Version-Catalog-Aliase `libs.bluemap.api`, `libs.bluemap.core`, `libs.bluemap.common`, `libs.junit.bom`, `libs.junit.jupiter`, `libs.junit.platform.launcher`, `libs.testcontainers.bom`, `libs.testcontainers.junit`, `libs.testcontainers.minio`, `libs.plugins.spotless`, `libs.plugins.shadow`. Modulname `:telemetry-addon`.

- [ ] **Step 1: Gradle-Wrapper anlegen**

```bash
cd /mnt/projects/oss/onelitefeather/Apus
gradle wrapper --gradle-version 9.4.1
```

Falls kein `gradle` global verfügbar ist, den Wrapper aus `BlueMapS3Storage` kopieren:

```bash
cp -r ../BlueMapS3Storage/gradle/wrapper gradle/
cp ../BlueMapS3Storage/gradlew ../BlueMapS3Storage/gradlew.bat .
chmod +x gradlew
```

- [ ] **Step 2: `settings.gradle.kts` schreiben**

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

- [ ] **Step 3: `gradle.properties` schreiben**

```properties
group = net.onelitefeather.apus
version = 999.0.0
org.gradle.caching = true
org.gradle.parallel = true
```

- [ ] **Step 4: Root-`build.gradle.kts` schreiben**

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

- [ ] **Step 5: `.spotless/Copyright.java` schreiben**

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

- [ ] **Step 6: `.gitignore` schreiben**

```gitignore
.gradle/
build/
/bin/
.idea/
*.iml
runner/vendor/
```

**Achtung:** `/bin/` mit führendem Schrägstrich, damit nur ein Verzeichnis im Wurzelverzeichnis gemeint ist. Ein reines `bin/` würde auch `runner/bin/` ausschließen — und eine Ausnahme per `!runner/bin/` würde **nicht** greifen, weil Git Dateien in einem ausgeschlossenen Verzeichnis nicht wieder einschließen kann.

- [ ] **Step 7: `LICENSE` anlegen**

Den vollständigen AGPL-3.0-Text übernehmen:

```bash
cp ../BlueMapS3Storage/LICENSE LICENSE
```

- [ ] **Step 8: `telemetry-addon/build.gradle.kts` schreiben**

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

- [ ] **Step 9: Den fehlschlagenden Test schreiben**

Dieser Test beweist, dass die BlueMap-Abhängigkeiten wirklich auflösen und die erwarteten Klassen enthalten. Genau hier scheitert das Setup sonst still.

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

Damit die Klassen im Test sichtbar sind, muss `telemetry-addon/build.gradle.kts` sie auch dem Test-Classpath geben. Ergänze im `dependencies`-Block:

```kotlin
    testCompileOnly(libs.bluemap.common)
    testRuntimeOnly(libs.bluemap.api)
    testRuntimeOnly(libs.bluemap.core)
    testRuntimeOnly(libs.bluemap.common)
```

- [ ] **Step 10: Test ausführen und Fehlschlag prüfen**

Run: `./gradlew :telemetry-addon:test --tests '*BuildSetupTest*'`
Expected: FAIL, solange Wrapper, Katalog oder Repository nicht stimmen — typischerweise „Could not find de.bluecolored:bluemap-common:5.23".

**Wenn die Auflösung scheitert:** Die Koordinaten haben zwischen BlueMap-Versionen gewechselt. `BlueMapS3Storage` nutzt für 5.3 noch `de.bluecolored.bluemap:BlueMapCore`. Prüfe, welche Form für 5.23 gilt:

```bash
curl -s https://repo.bluecolored.de/releases/de/bluecolored/bluemap-common/maven-metadata.xml | head -20
curl -s https://repo.bluecolored.de/releases/de/bluecolored/bluemap/BlueMapCommon/maven-metadata.xml | head -20
```

Nimm die Variante, die eine gültige `maven-metadata.xml` liefert, und passe den Katalog **sowie diesen Plan** an.

- [ ] **Step 11: Setup korrigieren bis der Test besteht**

Run: `./gradlew :telemetry-addon:test --tests '*BuildSetupTest*'`
Expected: PASS

- [ ] **Step 12: Spotless anwenden und Gesamtbau prüfen**

Run: `./gradlew spotlessApply build`
Expected: BUILD SUCCESSFUL

- [ ] **Step 13: Commit**

```bash
git add -A
git commit -m "build: set up Apus gradle monorepo with telemetry-addon module"
```

---

### Task 2: ProgressSnapshot und Serialisierung

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
- Consumes: nichts aus anderen Aufgaben
- Produces:
```java
public record ProgressSnapshot(
        State state,              // RENDERING, IDLE, STARTING, UNKNOWN
        String currentMap,        // null wenn unbekannt
        double progress,          // 0..1, -1 wenn unbekannt
        long etaSeconds,          // -1 wenn unbekannt
        int queuedTasks,          // -1 wenn unbekannt
        int renderThreads,        // -1 wenn unbekannt
        boolean degraded,
        String description)       // null wenn unbekannt
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

`Numbers.compact` wird von **beiden** Writern genutzt. Es ist die einzige Stelle mit Zahlenformatierung — dieselbe Logik zweimal zu schreiben wäre ein Duplikat.

- [ ] **Step 1: Den fehlschlagenden Test für `ProgressSnapshot` schreiben**

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

- [ ] **Step 2: Test ausführen und Fehlschlag prüfen**

Run: `./gradlew :telemetry-addon:test --tests '*ProgressSnapshotTest*'`
Expected: FAIL, „cannot find symbol: class ProgressSnapshot"

- [ ] **Step 3: `ProgressSnapshot` implementieren**

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

- [ ] **Step 4: Test ausführen und Erfolg prüfen**

Run: `./gradlew :telemetry-addon:test --tests '*ProgressSnapshotTest*'`
Expected: PASS

- [ ] **Step 5: Den fehlschlagenden Test für `Numbers` schreiben**

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

- [ ] **Step 6: Test ausführen, Fehlschlag prüfen, `Numbers` implementieren**

Run: `./gradlew :telemetry-addon:test --tests '*NumbersTest*'`
Expected: FAIL, „cannot find symbol: class Numbers"

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

- [ ] **Step 7: Den fehlschlagenden Test für `JsonWriter` schreiben**

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

- [ ] **Step 8: Test ausführen und Fehlschlag prüfen**

Run: `./gradlew :telemetry-addon:test --tests '*JsonWriterTest*'`
Expected: FAIL, „cannot find symbol: class JsonWriter"

- [ ] **Step 9: `JsonWriter` implementieren**

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

- [ ] **Step 10: Test ausführen und Erfolg prüfen**

Run: `./gradlew :telemetry-addon:test --tests '*JsonWriterTest*'`
Expected: PASS

Falls `progress` als `-1` statt `-1.0` erwartet wird: Der Test in Step 5 fordert `0.674` exakt; `trimTrailingZeros` liefert dafür `0.674`. Für `-1.0` liefert es `-1`. Beides ist gültiges JSON.

- [ ] **Step 11: Den fehlschlagenden Test für `PrometheusWriter` schreiben**

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

- [ ] **Step 12: Test ausführen und Fehlschlag prüfen**

Run: `./gradlew :telemetry-addon:test --tests '*PrometheusWriterTest*'`
Expected: FAIL, „cannot find symbol: class PrometheusWriter"

- [ ] **Step 13: `PrometheusWriter` implementieren**

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

- [ ] **Step 14: Test ausführen und Erfolg prüfen**

Run: `./gradlew :telemetry-addon:test --tests '*PrometheusWriterTest*'`
Expected: PASS

- [ ] **Step 15: Commit**

```bash
./gradlew spotlessApply
git add -A
git commit -m "feat(telemetry): add progress snapshot model with json and prometheus writers"
```

---

### Task 3: Progress-Probe mit BlueMap-Zugriff

**Files:**
- Create: `telemetry-addon/src/main/java/net/onelitefeather/apus/telemetry/probe/RenderManagerAccess.java`
- Create: `telemetry-addon/src/main/java/net/onelitefeather/apus/telemetry/probe/RenderProgressProbe.java`
- Create: `telemetry-addon/src/main/java/net/onelitefeather/apus/telemetry/probe/BlueMapRenderManagerAccess.java`
- Test: `telemetry-addon/src/test/java/net/onelitefeather/apus/telemetry/probe/FakeRenderManagerAccess.java`
- Test: `telemetry-addon/src/test/java/net/onelitefeather/apus/telemetry/probe/RenderProgressProbeTest.java`

**Interfaces:**
- Consumes: `ProgressSnapshot` aus Task 2 (inklusive `unknown(String)` und `idle(int, int)`)
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

Der Konstruktor nimmt einen `Supplier`, weil die BlueMap-API beim Start des Addons noch nicht bereit ist. Der Supplier liefert `null`, solange sie fehlt.

- [ ] **Step 1: `RenderManagerAccess` schreiben (noch ohne Implementierung)**

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

- [ ] **Step 2: Den Fake für Tests schreiben**

`FakeRenderManagerAccess.java` (im Testverzeichnis):

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

- [ ] **Step 3: Den fehlschlagenden Test für die Probe schreiben**

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

- [ ] **Step 4: Test ausführen und Fehlschlag prüfen**

Run: `./gradlew :telemetry-addon:test --tests '*RenderProgressProbeTest*'`
Expected: FAIL, „cannot find symbol: class RenderProgressProbe"

- [ ] **Step 5: `RenderProgressProbe` implementieren**

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

`Throwable` statt `Exception` ist hier Absicht: Ein BlueMap-Upgrade äußert sich typischerweise als `NoSuchMethodError` oder `NoClassDefFoundError`, und genau die dürfen den Render nicht abbrechen.

- [ ] **Step 6: Test ausführen und Erfolg prüfen**

Run: `./gradlew :telemetry-addon:test --tests '*RenderProgressProbeTest*'`
Expected: PASS (6 Tests)

- [ ] **Step 7: `BlueMapRenderManagerAccess` implementieren**

Die einzige Klasse mit BlueMap-Importen.

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

- [ ] **Step 8: Kompilierung prüfen**

Run: `./gradlew :telemetry-addon:compileJava`
Expected: BUILD SUCCESSFUL

**Wenn `BmMap.getId()` nicht existiert:** Prüfe die tatsächliche Methode:

```bash
cd /tmp && curl -sL https://repo.bluecolored.de/releases/de/bluecolored/bluemap-core/5.23/bluemap-core-5.23.jar -o core.jar \
  && unzip -p core.jar de/bluecolored/bluemap/core/map/BmMap.class | javap -c - 2>/dev/null | head -40
```

Alternativ liefert `javap -classpath core.jar de.bluecolored.bluemap.core.map.BmMap` die vollständige Signaturliste. Passe den Aufruf an und korrigiere diesen Plan.

- [ ] **Step 9: Commit**

```bash
./gradlew spotlessApply
git add -A
git commit -m "feat(telemetry): read render progress through a single blueMap seam"
```

---

### Task 4: HTTP-Server

**Files:**
- Create: `telemetry-addon/src/main/java/net/onelitefeather/apus/telemetry/TelemetryServer.java`
- Create: `telemetry-addon/src/main/java/net/onelitefeather/apus/telemetry/TelemetryConfig.java`
- Test: `telemetry-addon/src/test/java/net/onelitefeather/apus/telemetry/TelemetryServerTest.java`

**Interfaces:**
- Consumes: `ProgressSnapshot`, `JsonWriter`, `PrometheusWriter` aus Task 2
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

Routen: `GET /progress` (JSON), `GET /metrics` (Prometheus-Text), `GET /healthz` (`"ok"`), alles andere 404.

- [ ] **Step 1: Den fehlschlagenden Test für `TelemetryConfig` schreiben**

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

- [ ] **Step 2: Test ausführen und Fehlschlag prüfen**

Run: `./gradlew :telemetry-addon:test --tests '*TelemetryServerTest*'`
Expected: FAIL, „cannot find symbol: class TelemetryConfig"

- [ ] **Step 3: `TelemetryConfig` implementieren**

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

- [ ] **Step 4: Test ausführen und Erfolg prüfen**

Run: `./gradlew :telemetry-addon:test --tests '*TelemetryServerTest*'`
Expected: PASS (3 Tests)

- [ ] **Step 5: Den fehlschlagenden Test für den Server schreiben**

An `TelemetryServerTest.java` anhängen:

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

Ergänze oben in der Datei den Import `java.util.function.Supplier`.

- [ ] **Step 6: Test ausführen und Fehlschlag prüfen**

Run: `./gradlew :telemetry-addon:test --tests '*TelemetryServerTest*'`
Expected: FAIL, „cannot find symbol: class TelemetryServer"

- [ ] **Step 7: `TelemetryServer` implementieren**

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

- [ ] **Step 8: Test ausführen und Erfolg prüfen**

Run: `./gradlew :telemetry-addon:test --tests '*TelemetryServerTest*'`
Expected: PASS (7 Tests)

Beachte: `/healthz` liefert bewusst auch dann 200, wenn die Probe scheitert — der Prozess ist gesund, nur die Messung nicht. Eine k8s-Liveness-Probe darf den Render nicht wegen eines Telemetriefehlers töten.

- [ ] **Step 9: Commit**

```bash
./gradlew spotlessApply
git add -A
git commit -m "feat(telemetry): serve progress over http as json and prometheus metrics"
```

---

### Task 5: Addon-Entrypoint

**Files:**
- Create: `telemetry-addon/src/main/java/net/onelitefeather/apus/telemetry/ApusTelemetryAddon.java`
- Create: `telemetry-addon/src/main/resources/bluemap.addon.json`
- Test: `telemetry-addon/src/test/java/net/onelitefeather/apus/telemetry/AddonManifestTest.java`

**Interfaces:**
- Consumes: `TelemetryConfig`, `TelemetryServer` (Task 4), `RenderProgressProbe`, `BlueMapRenderManagerAccess` (Task 3)
- Produces: Die Klasse `net.onelitefeather.apus.telemetry.ApusTelemetryAddon` als Addon-Entrypoint, referenziert in `bluemap.addon.json`

- [ ] **Step 1: Den fehlschlagenden Test für das Manifest schreiben**

Dieser Test fängt den häufigsten Fehler dieser Art von Addon ab: einen Tippfehler im Entrypoint-Pfad, der erst zur Laufzeit im Cluster auffällt.

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

- [ ] **Step 2: Test ausführen und Fehlschlag prüfen**

Run: `./gradlew :telemetry-addon:test --tests '*AddonManifestTest*'`
Expected: FAIL, „bluemap.addon.json must be on the classpath"

- [ ] **Step 3: `bluemap.addon.json` schreiben**

`telemetry-addon/src/main/resources/bluemap.addon.json`:

```json
{
  "id": "apus-telemetry",
  "entrypoint": "net.onelitefeather.apus.telemetry.ApusTelemetryAddon"
}
```

- [ ] **Step 4: `ApusTelemetryAddon` implementieren**

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

- [ ] **Step 5: Test ausführen und Erfolg prüfen**

Run: `./gradlew :telemetry-addon:test --tests '*AddonManifestTest*'`
Expected: PASS (2 Tests)

- [ ] **Step 6: Fat-Jar bauen und Inhalt prüfen**

```bash
./gradlew :telemetry-addon:shadowJar
unzip -l telemetry-addon/build/libs/apus-telemetry-addon-999.0.0.jar | head -30
```

Expected: `bluemap.addon.json` liegt im Wurzelverzeichnis des Jars, die `net/onelitefeather/apus/telemetry/`-Klassen sind enthalten, und **keine** `de/bluecolored/`-Klassen (die sind `compileOnly`).

- [ ] **Step 7: Vollständigen Testlauf ausführen**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL, alle Tests grün

- [ ] **Step 8: Commit**

```bash
./gradlew spotlessApply
git add -A
git commit -m "feat(telemetry): add bluemap addon entrypoint and manifest"
```

---

### Task 6: Runner-Image

**Files:**
- Create: `runner/Dockerfile`
- Create: `runner/entrypoint.sh`
- Create: `runner/bin/render-config.sh`
- Create: `runner/bin/bundle-sync.sh`
- Create: `runner/README.md`

**Interfaces:**
- Consumes: `apus-telemetry-addon-<version>.jar` aus Task 5
- Produces: Image `apus/runner:dev` mit folgendem Vertrag über Umgebungsvariablen:

| Variable | Pflicht | Bedeutung |
|---|---|---|
| `APUS_MAP_ID` | ja | Map-Id, z.B. `overworld` |
| `APUS_DIMENSION` | ja | `minecraft:overworld`, `minecraft:the_nether`, `minecraft:the_end` |
| `APUS_MC_VERSION` | ja | z.B. `1.21.10` |
| `APUS_WORLD_S3_URL` | ja | Quelle der Welt, z.B. `s3://bundles/worlds/t/survival/v1/overworld` |
| `APUS_MAP_BUCKET` | ja | Ziel-Bucket für die gerenderte Map |
| `APUS_MAP_PREFIX` | nein | Präfix im Ziel-Bucket, Default `.` |
| `APUS_S3_ENDPOINT` | ja | z.B. `http://minio:9000` |
| `APUS_S3_ACCESS_KEY` | ja | Zugangsschlüssel |
| `APUS_S3_SECRET_KEY` | ja | Geheimer Schlüssel |
| `APUS_S3_REGION` | nein | Default `us-east-1` |
| `APUS_RENDER_THREADS` | nein | Default `2` |
| `APUS_FORCE_RENDER` | nein | `true` fügt `-f` hinzu |
| `APUS_TELEMETRY_PORT` | nein | Default `8099` |

- [ ] **Step 1: `runner/Dockerfile` schreiben**

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

- [ ] **Step 2: `runner/bin/render-config.sh` schreiben**

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

- [ ] **Step 3: `runner/bin/bundle-sync.sh` schreiben**

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

- [ ] **Step 4: `runner/entrypoint.sh` schreiben**

```bash
#!/usr/bin/env bash
set -euo pipefail

: "${APUS_MAP_ID:?APUS_MAP_ID is required}"
: "${APUS_DIMENSION:?APUS_DIMENSION is required}"
: "${APUS_WORLD_S3_URL:?APUS_WORLD_S3_URL is required}"
: "${APUS_MAP_BUCKET:?APUS_MAP_BUCKET is required}"
: "${APUS_S3_ENDPOINT:?APUS_S3_ENDPOINT is required}"
: "${APUS_S3_ACCESS_KEY:?APUS_S3_ACCESS_KEY is required}"
: "${APUS_S3_SECRET_KEY:?APUS_S3_SECRET_KEY is required}"

CONFIG_DIR=/work/config
WORLD_DIR=/work/world

/opt/apus/bin/bundle-sync.sh "${WORLD_DIR}"
/opt/apus/bin/render-config.sh "${CONFIG_DIR}"

ARGS=(-c "${CONFIG_DIR}" -r -m "${APUS_MAP_ID}")

if [ "${APUS_FORCE_RENDER:-false}" = "true" ]; then
  ARGS+=(-f)
fi

if [ -n "${APUS_MC_VERSION:-}" ]; then
  ARGS+=(-v "${APUS_MC_VERSION}")
fi

echo "[apus] starting BlueMap: ${ARGS[*]}"
exec java -jar /opt/bluemap/cli.jar "${ARGS[@]}"
```

`exec` ist wichtig: BlueMap wird damit PID 1 und empfängt `SIGTERM` direkt, wenn Kubernetes den Pod beendet.

- [ ] **Step 5: `runner/README.md` schreiben**

````markdown
# Apus Runner Image

Renders a Minecraft world from S3 with BlueMap and writes the result back to S3.

## Build

```bash
./gradlew :telemetry-addon:shadowJar
mkdir -p runner/vendor
curl -fsSL -o runner/vendor/BlueMapS3Storage.jar \
  https://github.com/TheMeinerLP/BlueMapS3Storage/releases/latest/download/BlueMapS3Storage.jar
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

- [ ] **Step 6: Image bauen**

```bash
cd /mnt/projects/oss/onelitefeather/Apus
./gradlew :telemetry-addon:shadowJar
mkdir -p runner/vendor
curl -fsSL -o runner/vendor/BlueMapS3Storage.jar \
  https://github.com/TheMeinerLP/BlueMapS3Storage/releases/latest/download/BlueMapS3Storage.jar
docker build -f runner/Dockerfile -t apus/runner:dev .
```

Expected: Image baut durch.

**Falls der Download der `BlueMapS3Storage.jar` fehlschlägt:** Der Release-Asset-Name kann abweichen. Prüfe mit `gh release view --repo TheMeinerLP/BlueMapS3Storage --json assets` und passe die URL sowie `runner/README.md` an. Alternativ lokal bauen: `(cd ../BlueMapS3Storage && ./gradlew shadowJar)` und das Jar aus `build/libs/` kopieren.

- [ ] **Step 7: Smoke-Test des Images**

```bash
docker run --rm --entrypoint java apus/runner:dev -jar /opt/bluemap/cli.jar -V
```

Expected: Ausgabe der BlueMap-Version, Exit-Code 0.

```bash
docker run --rm --entrypoint sh apus/runner:dev -c 'ls -la /work/config/packs && id'
```

Expected: Beide Addon-Jars liegen in `packs/`, und der Prozess läuft als `uid=10001(apus)`.

- [ ] **Step 8: Prüfen, dass fehlende Pflichtvariablen sauber scheitern**

```bash
docker run --rm apus/runner:dev; echo "exit=$?"
```

Expected: Fehlermeldung `APUS_MAP_ID is required`, Exit-Code ungleich 0.

- [ ] **Step 9: Commit**

```bash
git add -A
git commit -m "feat(runner): add container image running bluemap cli with apus addons"
```

`runner/vendor/` ist bereits in `.gitignore` (Task 1) — das heruntergeladene Fremd-Jar gehört nicht ins Repository.

---

### Task 7: Integrationstest gegen MinIO

**Files:**
- Create: `testdata/README.md`
- Create: `testdata/mini-world/` (Fixture)
- Create: `runner/build.gradle.kts`
- Create: `runner/src/test/java/net/onelitefeather/apus/runner/RenderEndToEndTest.java`
- Modify: `settings.gradle.kts` (Modul `runner` ergänzen)

**Interfaces:**
- Consumes: Image `apus/runner:dev` aus Task 6, der Umgebungsvariablen-Vertrag aus Task 6
- Produces: Bestätigung, dass `storage-type: "themeinerlp:s3"` und die kebab-case-Schlüssel korrekt sind; korrigiert bei Abweichung Task 6 **und** die Spec

- [ ] **Step 1: Mini-Welt-Fixture erzeugen**

Aus der vorhandenen Demo-Welt eine kleine, deterministische Fixture schneiden. **Nur `region/` und `level.dat`** — `playerdata/`, `stats/` und `advancements/` enthalten personenbezogene Daten und gehören nicht ins Repository.

```bash
cd /mnt/projects/oss/onelitefeather/Apus
SRC=../falco-demo-world-backup-1.21.10
mkdir -p testdata/mini-world/region

# Zwei benachbarte Regionen ergeben eine zusammenhängende Fläche
for f in r.0.0.mca r.0.1.mca; do
  if [ -f "$SRC/region/$f" ]; then cp "$SRC/region/$f" testdata/mini-world/region/; fi
done

# Falls diese Koordinaten nicht existieren, die zwei kleinsten Dateien nehmen:
if [ -z "$(ls -A testdata/mini-world/region)" ]; then
  find "$SRC/region" -name '*.mca' -printf '%s %p\n' | sort -n | head -2 | cut -d' ' -f2- \
    | xargs -I{} cp {} testdata/mini-world/region/
fi

cp "$SRC/level.dat" testdata/mini-world/
du -sh testdata/mini-world
ls -la testdata/mini-world/region
```

Expected: Verzeichnis unter etwa 20 MB. Ist es größer, eine einzelne Region behalten.

- [ ] **Step 2: `testdata/README.md` schreiben**

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

- [ ] **Step 3: Modul `runner` registrieren**

In `settings.gradle.kts` die Include-Zeile ersetzen:

```kotlin
include("telemetry-addon", "runner")
```

- [ ] **Step 4: `runner/build.gradle.kts` schreiben**

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

- [ ] **Step 5: Den fehlschlagenden End-to-End-Test schreiben**

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

    private static final String ACCESS_KEY = "apustest";
    private static final String SECRET_KEY = "apustestsecret";
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

Ergänze in `runner/build.gradle.kts` bei den Testabhängigkeiten `testImplementation("org.slf4j:slf4j-simple:2.0.16")`, damit `Slf4jLogConsumer` Ausgaben zeigt.

- [ ] **Step 6: Test ausführen und Fehlschlag erwarten**

```bash
./gradlew :telemetry-addon:shadowJar
docker build -f runner/Dockerfile -t apus/runner:dev .
./gradlew :runner:test
```

Expected: FAIL — höchstwahrscheinlich, weil `storage-type` oder die Schlüsselnamen in `storages/s3.conf` nicht stimmen. Genau dafür ist dieser Test da.

- [ ] **Step 7: `s3.conf`-Format anhand der Fehlermeldung korrigieren**

Die BlueMap-Fehlermeldung nennt den unbekannten Storage-Typ oder das unbekannte Feld. Prüfe gegen die Quelle:

```bash
grep -n "Key(" ../BlueMapS3Storage/src/main/java/dev/themeinerlp/bluemap/s3/S3StorageAddon.java
grep -rn "NamingScheme\|NAMING" ../BlueMapS3Storage/src/main/java/ || true
```

Der Registry-Key ist `new Key("themeinerlp", "s3")`, was `themeinerlp:s3` ergibt. Sollte BlueMap das Format anders erwarten, zeigt die Fehlermeldung die zulässigen Werte an.

Bei Feldnamen: Configurate bildet standardmäßig camelCase auf kebab-case ab (`renderThreadCount` → `render-thread-count`). Falls die Felder unbekannt bleiben, teste die camelCase-Variante (`bucketName` statt `bucket-name`).

Passe `runner/bin/render-config.sh` an, baue das Image neu und wiederhole.

- [ ] **Step 8: Test ausführen und Erfolg prüfen**

```bash
docker build -f runner/Dockerfile -t apus/runner:dev .
./gradlew :runner:test
```

Expected: PASS

- [ ] **Step 9: Die Spec korrigieren, falls das Format abwich**

Wenn Step 7 Änderungen erforderte, den Abschnitt „Konfigurationsformate" in diesem Plan **und** §9.2 der Spec `docs/superpowers/specs/2026-08-08-apus-design.md` auf die verifizierten Werte aktualisieren. Der Operator generiert in Phase 2 genau diese Datei — eine falsche Annahme würde sich dort fortpflanzen.

- [ ] **Step 10: Commit**

```bash
git add -A
git commit -m "test(runner): verify end-to-end render from s3 to s3 against minio"
```

---

### Task 8: Telemetrie im echten Render nachweisen

**Files:**
- Modify: `runner/src/test/java/net/onelitefeather/apus/runner/RenderEndToEndTest.java`
- Create: `runner/src/test/java/net/onelitefeather/apus/runner/TelemetryContractTest.java`

**Interfaces:**
- Consumes: alles aus Task 7
- Produces: Der Nachweis, dass `/progress` während eines echten Renders belastbare Werte liefert — der Contract-Test, der ein BlueMap-Upgrade auffliegen lässt

- [ ] **Step 1: Den fehlschlagenden Contract-Test schreiben**

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

    private static final String ACCESS_KEY = "apustest";
    private static final String SECRET_KEY = "apustestsecret";

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

- [ ] **Step 2: Test ausführen**

```bash
./gradlew :runner:test --tests '*TelemetryContractTest*'
```

Expected beim ersten Lauf: möglicherweise FAIL mit `degraded:true`. Das ist die wertvollste Rückmeldung des gesamten Plans — sie bedeutet, dass der Zugriffspfad über `BlueMapAPIImpl.plugin()` im CLI-Kontext nicht greift.

- [ ] **Step 3: Bei `degraded:true` den Zugriffspfad diagnostizieren**

Der wahrscheinlichste Grund: Im CLI-Betrieb gibt es keine `Plugin`-Instanz (die existiert primär im Server-Plugin-Betrieb), sodass `impl.plugin()` `null` liefert.

Diagnose:

```bash
docker run --rm --entrypoint sh apus/runner:dev -c \
  'cd /tmp && unzip -o -q /opt/bluemap/cli.jar "de/bluecolored/bluemap/common/api/BlueMapAPIImpl.class" \
   && javap -p de/bluecolored/bluemap/common/api/BlueMapAPIImpl.class | head -40'
```

Zeigt die tatsächlich verfügbaren Felder und Methoden. Prüfe zusätzlich, wie der CLI die API bereitstellt:

```bash
docker run --rm --entrypoint sh apus/runner:dev -c \
  'cd /tmp && unzip -o -q /opt/bluemap/cli.jar "de/bluecolored/bluemap/cli/BlueMapCLI.class" \
   && javap -p -c de/bluecolored/bluemap/cli/BlueMapCLI.class | grep -i "renderManager\|BlueMapAPIImpl" | head -20'
```

Ergibt sich daraus ein anderer Weg (etwa ein zugänglicher `BlueMapService` oder ein anderer Konstruktor von `BlueMapAPIImpl`), **ausschließlich `BlueMapRenderManagerAccess` anpassen**. Alle anderen Klassen und sämtliche Unit-Tests bleiben unverändert — genau dafür wurde die Schnittstelle eingezogen.

Führt kein Weg über die API zum internen RenderManager, ist Reflection auf das private Feld `renderManager` in `RenderManagerImpl` der dokumentierte Rückfallweg (Feldname aus der Recherche in den Global Constraints). Auch das gehört ausschließlich in `BlueMapRenderManagerAccess`.

- [ ] **Step 4: Korrektur umsetzen, bis der Test besteht**

```bash
./gradlew :telemetry-addon:shadowJar
docker build -f runner/Dockerfile -t apus/runner:dev .
./gradlew :runner:test --tests '*TelemetryContractTest*'
```

Expected: PASS

- [ ] **Step 5: Erkenntnisse dokumentieren**

Ergänze `runner/README.md` um einen Abschnitt „Telemetry" mit dem tatsächlich funktionierenden Zugriffsweg und dem Hinweis, dass `TelemetryContractTest` bei jedem BlueMap-Upgrade laufen muss. Aktualisiere §7.2 der Spec, falls der Weg vom dort beschriebenen abweicht.

- [ ] **Step 6: Gesamtlauf**

```bash
./gradlew build
./gradlew :runner:test
```

Expected: Alles grün.

- [ ] **Step 7: Commit**

```bash
./gradlew spotlessApply
git add -A
git commit -m "test(runner): add telemetry contract test against a real bluemap render"
```

---

## Abschluss Phase 1

Nach Task 8 ist erreicht:

- Ein Container-Image rendert eine Welt aus S3 nach S3, ohne Kubernetes.
- Der Fortschritt ist während des Laufs als JSON und als Prometheus-Metriken abrufbar.
- Der gesamte BlueMap-Zugriff liegt in einer Klasse, abgesichert durch einen Contract-Test.
- Serialisierung, Fehlerbehandlung und Konfiguration sind ohne laufende BlueMap-Instanz getestet.

**Nicht Teil von Phase 1** (folgt in eigenen Plänen): Operator und CRDs, Ingest/ETL, Hosting, Asset-Cache für die Minecraft-Client-JAR, Region-Sharding.

**Übergabepunkte an Phase 2:** Der Umgebungsvariablen-Vertrag aus Task 6 ist die Schnittstelle, die der Operator bedienen wird. Das in Task 7 verifizierte `s3.conf`-Format ist die Vorlage für die Konfigurationsgenerierung des Operators.
