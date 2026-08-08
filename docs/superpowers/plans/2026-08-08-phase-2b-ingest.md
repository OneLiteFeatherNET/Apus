# Apus Phase 2b — Ingest und ETL: Implementierungsplan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Welt-Daten aus heterogenen Quellen in ein einheitliches, versioniertes World Bundle in S3 überführen, sodass der Render-Pfad aus Phase 2a sie ohne Kenntnis der Herkunft verarbeiten kann.

**Architecture:** Nur der Extract-Schritt ist quellenspezifisch; Transform (Layout-Erkennung) und Load (Bundle-Writer) sind gemeinsam. Ein neuer Connector kostet damit eine Implementierung von zwei Methoden. Zwei neue Custom Resources (`WorldSource`, `WorldIngest`) reihen sich in das Muster aus Phase 2a ein: Vorlage erzeugt Ausführungen. Der eigentliche Ingest läuft als Kubernetes-Job mit einem eigenen Container-Image, analog zum Runner aus Phase 1.

**Tech Stack:** Java 25, Gradle, JOSDK 5.5.1, Fabric8 7.8.0, JUnit Jupiter, Testcontainers (MinIO), Fabric8 Mock-Server.

## Global Constraints

- **Java-Toolchain 25**, Basispaket `net.onelitefeather.apus.ingest` (neues Modul `ingest`) bzw. `net.onelitefeather.apus.operator.api` für die CRDs.
- API-Gruppe `bluemap.onelitefeather.net`, Version `v1alpha1`. Beide neuen Ressourcen sind **namespaced**.
- Koordinaten wie in Phase 2a; der Fabric8-Client kommt transitiv über JOSDK.
- **Das Bundle-Manifest ist der Vertrag** (§5 der Spec). Es wird **zuletzt** geschrieben — es ist der Commit-Punkt. Ohne Manifest existiert ein Bundle nicht und wird nie gerendert. Damit gibt es keine halb entpackten Welten im Render-Pfad.
- **Bundles sind unveränderlich.** Neue Welt-Daten erzeugen eine neue Version, nie eine Änderung an einer bestehenden.
- **Die Regionsliste gehört ins Manifest.** Sie kostet beim Ingest nichts, weil ohnehin jede `.mca`-Datei angefasst wird, und ist die Grundlage für das Sharding aus Phase 4 sowie für genaue Fortschrittsberechnung.
- **Dimensionen werden logisch benannt** (`overworld`, `the_nether`, `the_end`), unabhängig davon, ob die Quelle Vanilla- oder Bukkit-Layout hatte.
- **Eigentümerprüfung**: Vor dem Verändern einer bestehenden Ressource ist zu prüfen, ob sie zur eigenen Custom Resource gehört (Name **und** UID). Fremde oder ungekennzeichnete Ressourcen führen zu einer Konflikt-Condition. Das war ein Sicherheitsbefund in Phase 2a und darf sich nicht wiederholen.
- **Gemeinsame `Labels`-Klasse** aus Phase 2a für alle erzeugten Ressourcen.
- Zugangsdaten niemals in Status, Events oder Logs.
- AGPL-Header über Spotless, Conventional Commits, **keine** Claude-Attribution, Bezeichner und Javadoc auf Englisch.

### Was aus Phase 1 und 2a bereits existiert und zu benutzen ist

- `net.onelitefeather.apus.operator.api.Labels`, `Conditions`, `Ref`, `OperatorConfig`
- Das Eigentümer-Prüfmuster in `TenantReconciler` und `BlueMapMapReconciler`
- `client.supports(...)` als Prüfung auf fehlende fremde CRDs
- Die CRD-Generierung erfasst neue Ressourcen unter `net.onelitefeather.apus.operator.api` automatisch
- `BlueMapRender.spec.bundleUrl` erwartet eine `s3://`-URL auf ein Bundle-Verzeichnis

---

## File Structure

```
ingest/                                   neues Modul, Container-Image analog zu runner/
├── build.gradle.kts
├── Dockerfile
└── src/
    ├── main/java/net/onelitefeather/apus/ingest/
    │   ├── IngestMain.java               Einstiegspunkt des Jobs
    │   ├── WorldLayout.java              Erkanntes Layout + Dimensions-Zuordnung
    │   ├── LayoutDetector.java           Erkennt vanilla / bukkit / nested
    │   ├── BundleManifest.java           Datenmodell des Manifests
    │   ├── BundleWriter.java             Schreibt Bundle nach S3, Manifest zuletzt
    │   ├── S3Client.java                 Schmale S3-Fassade (Upload, List, Head)
    │   └── connector/
    │       ├── WorldSourceConnector.java Schnittstelle: discover / fetch
    │       ├── SourceVersion.java
    │       ├── S3SourceConnector.java    Pull aus einem Bucket-Prefix
    │       └── PterodactylConnector.java Pull über die Panel-API
    └── test/java/...

operator/src/main/java/net/onelitefeather/apus/operator/
├── api/WorldSource.java  WorldSourceSpec.java  WorldSourceStatus.java
├── api/WorldIngest.java  WorldIngestSpec.java  WorldIngestStatus.java
└── ingest/
    ├── WorldSourceReconciler.java        Poll-Zeitplan → erzeugt WorldIngest
    ├── WorldIngestReconciler.java        erzeugt den Ingest-Job, führt Fortschritt
    └── IngestJobBuilder.java             baut den Job aus dem ingest-Image
```

**Warum ein eigenes Modul:** Der Ingest läuft als Job im Cluster, nicht im Operator-Prozess. Ein großes `tar.gz` zu streamen und Gigabyte an Region-Dateien zu schreiben gehört nicht in einen Operator, der viele Ressourcen gleichzeitig betreut. `LayoutDetector`, `BundleManifest` und die Connectoren sind reine Logik und ohne Cluster testbar.

---

## Parallelisierung

Dasselbe Muster wie in Phase 2a: Datenmodell zuerst, dann berühren die Folgeaufgaben getrennte Dateien.

| Gruppe | Aufgaben | Ausführung |
|---|---|---|
| A | Task 1 — Modul und Datenmodell | sequenziell |
| B | Task 2, Task 3, Task 4 | **parallel**, je eigener Worktree |
| C | Task 5 — Ingest-Einstiegspunkt und Image | sequenziell |
| D | Task 6 — Reconciler | sequenziell |
| E | Task 7 — Integrationstest | sequenziell |

**Dateien der parallelen Gruppe** (disjunkt):
- Task 2: `LayoutDetector.java`, `WorldLayout.java` + Tests
- Task 3: `BundleManifest.java`, `BundleWriter.java`, `S3Client.java` + Tests
- Task 4: `connector/*` + Tests

---

### Task 1: Modul, CRDs und gemeinsames Datenmodell

**Files:**
- Modify: `settings.gradle.kts` (Modul `ingest`, Katalog-Einträge für den S3-Client)
- Create: `ingest/build.gradle.kts`
- Create: `operator/src/main/java/.../api/WorldSource.java`, `WorldSourceSpec.java`, `WorldSourceStatus.java`
- Create: `operator/src/main/java/.../api/WorldIngest.java`, `WorldIngestSpec.java`, `WorldIngestStatus.java`
- Test: `operator/src/test/java/.../api/IngestResourceTest.java`
- Modify: `operator/src/test/java/.../CrdGenerationTest.java`

**Interfaces — bindend, drei Folgeaufgaben bauen darauf:**

```java
// WorldSourceSpec — alle Gruppen im Feld initialisiert, wie in Phase 2a
String type;                              // "s3" | "pterodactyl" | "upload" | "push"
S3Source s3 = new S3Source();             // String endpoint; String bucket; String prefix;
                                          // Ref credentialsSecretRef
Pterodactyl pterodactyl = new Pterodactyl();  // String panelUrl; String serverId;
                                              // Ref credentialsSecretRef; String select = "latest"
String poll;                              // Cron-Ausdruck, nur für Pull-Typen; null = nur manuell
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

- [ ] **Step 1: Katalog und Modul anlegen**

`settings.gradle.kts`: `include(..., "ingest")` und einen S3-Client ergänzen. Wähle bewusst: Das Projekt nutzt bereits `mc` im Runner-Image, aber ein Java-Job braucht eine Bibliothek. Nimm den AWS-SDK-v2-S3-Client (`software.amazon.awssdk:s3`) oder MinIOs Java-Client — recherchiere die aktuelle Version real gegen Maven Central und dokumentiere die Wahl im Report.

- [ ] **Step 2: Den fehlschlagenden Test schreiben**

`IngestResourceTest.java` nach dem Muster von `ApusResourceTest` aus Phase 2a:

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

- [ ] **Step 3: Test ausführen, Fehlschlag prüfen, Klassen implementieren**

Beide Ressourcen mit `implements Namespaced`, Annotationen `@Group("bluemap.onelitefeather.net")`, `@Version("v1alpha1")`, `@Kind`, `@Plural` (`worldsources`, `worldingests`), `@ShortNames` (`bmsource`, `bmingest`), und `initSpec()`/`initStatus()` überschrieben — sonst liefert `new WorldSource().getSpec()` `null`, was in Phase 2a bereits einmal drei parallele Aufgaben blockiert hat.

- [ ] **Step 4: CRD-Zusicherungen erweitern**

In `CrdGenerationTest` je einen Test, dass `worldsources` und `worldingests` erzeugt werden und **`scope: Namespaced`** tragen. Nutze die vorhandene, gezielt ladende Hilfsmethode.

Run: `./gradlew :operator:clean :operator:test`
Expected: PASS, `operator/build/crds/` enthält jetzt fünf CRDs.

- [ ] **Step 5: Commit**

```bash
./gradlew spotlessApply
git add -A
git commit -m "feat(ingest): add world source and ingest custom resources"
```

---

### Task 2: Layout-Erkennung *(parallel mit Task 3 und 4)*

> Eigener Worktree. Ausschließlich `ingest/src/main/java/.../LayoutDetector.java`, `WorldLayout.java` und die zugehörigen Tests. Keine andere Datei, keine Build-Datei.

**Das ist der inhaltliche Kern des ETL-Layers.** Die Quellen liefern unterschiedliche Verzeichnisstrukturen; BlueMap braucht pro Karte einen definierten Pfad zur richtigen Dimension.

| Layout | Erkennungsmerkmal | Abbildung |
|---|---|---|
| `vanilla` | `<w>/region`, `<w>/DIM-1/region`, `<w>/DIM1/region` | direkt |
| `bukkit` | `<w>/region`, `<w>_nether/DIM-1/region`, `<w>_the_end/DIM1/region` | Ordner zusammenführen |
| `nested` | genau ein Unterverzeichnis, darin eines der obigen | Ebene überspringen, erneut prüfen |

**Interfaces:**
```java
public record WorldLayout(String kind, Map<String, Path> dimensions) {}
// kind: "vanilla" | "bukkit"; dimensions: "overworld"/"the_nether"/"the_end" → Pfad zum region-Verzeichnis

public final class LayoutDetector {
    /** @throws LayoutDetectionException wenn kein bekanntes Layout erkennbar ist */
    public static WorldLayout detect(Path root, String worldName, String forcedLayout);
}
```

- [ ] **Step 1: Die fehlschlagenden Tests schreiben**

Baue die Verzeichnisstrukturen im Test mit `@TempDir` auf — keine Fixture-Dateien nötig, es geht nur um Struktur.

Testfälle, jeder mit eigener Begründung im Testnamen:
- Vanilla-Layout mit allen drei Dimensionen wird erkannt und korrekt zugeordnet.
- Vanilla-Layout mit **nur** Overworld wird erkannt (kein Nether, kein End — das ist normal).
- Bukkit-Layout mit `world`, `world_nether`, `world_the_end` wird erkannt und auf dieselben logischen Namen abgebildet.
- Ein zusätzlich verschachteltes Verzeichnis (ZIP-Upload-Fall) wird durchschaut.
- Eine Struktur ohne jedes `region`-Verzeichnis schlägt mit `LayoutDetectionException` fehl, und die Meldung nennt die gefundenen Pfade — Raten ist ausdrücklich unerwünscht.
- `forcedLayout = "bukkit"` auf einer Vanilla-Struktur schlägt fehl, statt still etwas Falsches zu liefern.

- [ ] **Step 2: Implementieren, Tests grün bekommen, committen**

---

### Task 3: Bundle-Writer und Manifest *(parallel mit Task 2 und 4)*

> Eigener Worktree. Ausschließlich `BundleManifest.java`, `BundleWriter.java`, `S3Client.java` und Tests.

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

Damit Task 3 nicht auf Task 2 warten muss, nimmt `BundleWriter` eine schmale Schnittstelle entgegen (`WorldLayoutLike` mit `kind()` und `dimensions()`), die Task 2s Record später erfüllt. Definiere sie in deinem eigenen Paket.

**Tests, die zählen:**
- Das Manifest wird **zuletzt** geschrieben — prüfe die Reihenfolge der Schreibvorgänge über einen Fake-S3-Client, der sie protokolliert. Das ist der Commit-Punkt und die wichtigste Eigenschaft des Bundles.
- Bricht das Schreiben mittendrin ab, existiert **kein** Manifest, das Bundle gilt also als nicht vorhanden.
- Die Regionsliste im Manifest entspricht den tatsächlich geschriebenen `.mca`-Dateien; Koordinaten werden aus dem Dateinamen `r.<x>.<z>.mca` gelesen.
- Serialisierung und Deserialisierung des Manifests sind verlustfrei.
- Der Fortschritt wird über `ProgressSink` gemeldet, damit der Job ihn nach außen geben kann.

---

### Task 4: Connector-Schnittstelle und die beiden Pull-Quellen *(parallel mit Task 2 und 3)*

> Eigener Worktree. Ausschließlich `connector/*` und Tests.

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

**`S3SourceConnector`:** listet Objekte unter einem Prefix, erkennt neue Versionen anhand des Objektschlüssels, lädt sie herunter. Entpackt gängige Archive, wenn der Schlüssel darauf endet.

**`PterodactylConnector`:** fragt die Backup-Liste über die Client-API des Panels ab und lädt das gewählte Backup über eine signierte URL. **Recherchiere die tatsächliche API** (Endpunkte, Authentifizierung, Antwortformat) und dokumentiere sie im Report — erfinde keine Endpunkte. Das Backup ist ein `tar.gz` des gesamten Servers; da gzip nicht seekbar ist, wird der Strom **einmal** durchlaufen und dabei selektiv nur das Welt-Verzeichnis geschrieben. Das gesamte Archiv darf nie auf der Platte landen.

**Tests:** Der S3-Connector gegen einen MinIO-Testcontainer. Der Pterodactyl-Connector gegen einen lokalen HTTP-Stub, der die Panel-Antworten nachbildet — **keinen** echten Panel-Zugriff und keinen Listener auf `0.0.0.0`. Prüfe insbesondere, dass aus einem tar.gz mit Plugins, Configs und Welten nur die Welt-Pfade extrahiert werden.

---

### Task 5: Ingest-Einstiegspunkt und Container-Image

Analog zu `runner/` aus Phase 1: `IngestMain` liest seine Konfiguration aus Umgebungsvariablen, wählt den Connector, ruft Extract → Detect → Write auf und meldet Fortschritt. Dazu ein `Dockerfile`.

**Umgebungsvariablen-Vertrag** (die Schnittstelle, die `IngestJobBuilder` in Task 6 bedient):
`APUS_SOURCE_TYPE`, `APUS_WORLD_NAME`, `APUS_LAYOUT` (Default `auto`), `APUS_BUNDLE_BUCKET`, `APUS_BUNDLE_TENANT`, `APUS_BUNDLE_WORLD_ID`, `APUS_BUNDLE_VERSION`, `APUS_S3_ENDPOINT`, `APUS_S3_ACCESS_KEY`, `APUS_S3_SECRET_KEY`, plus die quellenspezifischen (`APUS_SOURCE_S3_*`, `APUS_PTERODACTYL_*`).

Wie beim Runner: Fehlt eine Pflichtvariable, Abbruch mit klarer Meldung und Exit-Code ungleich null, **bevor** irgendetwas heruntergeladen wird. Nicht-root, `exec` für den Hauptprozess.

---

### Task 6: Reconciler für Quellen und Ingests

`WorldSourceReconciler`: wertet `poll` aus, vergleicht mit `status.lastSeenVersion`, legt bei Neuem einen `WorldIngest` an. `WorldIngestReconciler`: erzeugt den Job über `IngestJobBuilder`, führt Fortschritt und Ergebnis im Status, setzt bei Erfolg `WorldSource.status.latestBundle`.

**Bindend:** Eigentümerprüfung wie in Phase 2a. Kein zweiter Ingest für dieselbe Quelle, solange einer läuft — dieselbe optimistische Sperre wie beim Render, dort über `WorldSourceStatus`. Retention: ältere Bundles löschen, aber **nie** eines, das ein `BlueMapRender` noch referenziert.

---

### Task 7: Integrationstest

Ende-zu-Ende gegen MinIO: eine Welt in Bukkit-Layout als Quelle ablegen, Ingest laufen lassen, prüfen dass ein Bundle mit korrektem Manifest, logisch benannten Dimensionen und vollständiger Regionsliste entsteht. Nutze die vorhandene Fixture `testdata/mini-world`. Eigene `integrationTest`-Task, nicht Teil von `build` — wie in `runner` und `operator`.

Abschließend: ein Render gegen das erzeugte Bundle starten und belegen, dass der Vertrag zwischen Ingest und Render trägt.

---

## Abschluss Phase 2b

Danach führt der Weg von einer konfigurierten Quelle bis zur gerenderten Karte ohne Handgriff: `WorldSource` anlegen, Ingest läuft zeitgesteuert, Bundle entsteht, Render startet.

**Nicht Teil von 2b:** Die Push-Quellen (`upload`, `push`) und das Paper-Plugin — sie folgen in Phase 6. `WorldSource.spec.type` kennt sie bereits, die Connectoren fehlen noch.
