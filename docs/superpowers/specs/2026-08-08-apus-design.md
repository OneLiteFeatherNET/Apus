# Apus — Design

**Stand:** 2026-08-08
**Status:** Entwurf zur Freigabe

Apus rendert Minecraft-Welten mit BlueMap auf Kubernetes und hostet die Ergebnisse.
Welten kommen aus mehreren, sehr unterschiedlichen Quellen; ein ETL-Layer normalisiert
sie, ein Operator führt Render- und Hosting-Jobs aus, eine Oberfläche zeigt Fortschritt
und erlaubt Bedienung ohne YAML.

---

## 1. Ziel und Abgrenzung

### 1.1 Problem

Heute ist das Rendern einer BlueMap-Karte für eine Welt, die nicht auf demselben Server
liegt, Handarbeit: Backup besorgen, entpacken, Dimensionen richtig zuordnen, HOCON-Konfiguration
schreiben, BlueMap starten, warten ohne verlässliche Fortschrittsanzeige, Ergebnis irgendwie
ausliefern. Das skaliert weder über mehrere Welten noch über mehrere Gruppen.

### 1.2 Ziel

Ein Dienst, in dem eine Welt-Quelle einmal konfiguriert wird und danach automatisch
gerendert und gehostet wird — zeitgesteuert oder bei neuen Welt-Daten —, mit sichtbarem
Fortschritt und ohne dass jemand BlueMap-Konfiguration von Hand schreibt.

### 1.3 Nutzer

Interne Nutzung plus befreundete Server. Mehrere Mandanten, alle bekannt und
vertrauenswürdig. Mandantentrennung ist erforderlich, harte Sandbox-Isolation gegen
böswillige Nutzer ist es nicht.

Drei Rollenebenen:

| Ebene | Wer | Darf |
|---|---|---|
| Plattform | OLF als Betreiber | Mandanten anlegen, Quotas setzen, alles sehen |
| Mandant-Verwaltung | Owner/Admin eines Mandanten | Quellen, Maps, Hosting, Mitglieder des eigenen Mandanten |
| Mandant-Nutzung | Operator/Viewer | Renders auslösen bzw. nur zusehen |

### 1.4 Nicht-Ziele

- Kein öffentlicher Self-Service für Fremde (keine Abrechnung, keine Missbrauchserkennung, keine harte Sandbox).
- Kein Ersatz für BlueMap. Apus orchestriert BlueMap, es rendert nicht selbst.
- Kein eigener Renderer im MVP (siehe §14, Phase 4).
- Keine Verwaltung der Minecraft-Server selbst. Apus liest deren Welt-Daten, mehr nicht.

---

## 2. Ausgangslage

Vorhandene Bausteine, auf denen dieses Design aufsetzt:

- **`BlueMapS3Storage`** — BlueMap-Addon, das Map-Daten in S3-kompatiblen Speicher schreibt und liest. Basis für Render-Output und Hosting-Input.
- **`Kubernetes-FLUX`** — Cluster `feather-core`, GitOps über FluxCD. Enthält bereits: Rook-Ceph mit `CephObjectStore`, `ObjectBucketClaim`-Provisioner (StorageClass für Buckets), kube-prometheus-stack, Loki + Alloy, cert-manager, CNPG, nginx- und Cloudflare-Tunnel-Ingress.
- Bestehende Bucket-Claims folgen dem Muster `ObjectBucketClaim` + `bucketOwner`, Nutzer dem Muster `CephObjectStoreUser`.
- **`launchpad`** — Nuxt 4 + Vue 3.5 + Tailwind 4, der Frontend-Hausstandard.
- Ein OIDC-Provider ist im Einsatz (Outline, Grafana, Dependency-Track authentifizieren dagegen).

### 2.1 Erkenntnisse aus der BlueMap-Recherche

Diese Punkte prägen das Design und sind vor Implementierung gegen die verwendete
BlueMap-Version zu verifizieren:

1. **Fortschritt existiert, ist aber nicht exponiert.** Ein Timer fragt periodisch `RenderTask.estimateProgress()` (0..1) und `RenderManager.estimateCurrentRenderTaskTimeRemaining()` ab und schreibt beides als Log-Zeile. `BlueMapAPI` gibt Zugriff auf den `RenderManager`, aber nicht auf den laufenden Task.
2. **Render-State liegt im Map-Storage**, granular: `tileState()` und `chunkState()` sind `GridStorage`-Instanzen, gespeichert pro Tile bzw. Chunk. Damit ist Resume nach Absturz möglich und inkrementelles Rendern funktioniert auch über S3.
3. **Addons werden auch im CLI geladen**, früh in `main` über `AddonLoader.tryLoadAddons(packsFolder)`. Addons laufen in eigenem Classloader, dürfen eigene Threads und Server starten und erhalten `BlueMapAPI`.
4. **Es gibt keinen Hook für die Welt-Datenquelle.** Nur der Map-Output ist über Storages austauschbar. Welt-Daten müssen als Dateien lokal vorliegen.
5. **Regionsweises Rendern ist über die öffentliche API möglich:** `scheduleMapUpdateTask(map, Collection<Vector2i> regions)`.
6. **Lowres-Tiles aggregieren über Regionsgrenzen hinweg** (Mittelung von Farbe, Höhe, Licht aus mehreren höher aufgelösten Tiles). Das ist das offene Risiko für paralleles Rendern (§14, Phase 4).
7. **CLI-Flags:** `-r/--render`, `-f/--force-render`, `-m <map-ids>`, `-u/--watch`, `-w/--webserver`, `-e/--fix-edges`. Render- und Webserver-Betrieb sind sauber trennbar.
8. **BlueMaps Web-App ist Vue 3 + Vite** und exponiert `window.bluemap` als JS-API (Map wechseln, Kamera, Screenshot). Relevant für spätere Einbettung in die Apus-UI.

---

## 3. Architekturüberblick

```
  Pterodactyl-API ─┐   Pull:  Backup-Liste abfragen, tar.gz streamen
  S3-Bucket ───────┤   Pull:  Prefix auf neue Objekte prüfen
  Paper-Plugin ────┤   Push:  async + inkrementell in Staging-Prefix
  UI-Upload ───────┘   Push:  presigned Multipart
                    │
                    ▼
         ┌──────────────────────┐
         │  world-ingest (ETL)  │  Extract → Transform → Load
         └──────────┬───────────┘
                    ▼
          World Bundle in S3   ◄────── Vertrag zwischen Ingest und Render
                    │                  (normalisiertes Layout + manifest.json)
                    ▼
         ┌──────────────────────┐
         │   bluemap-runner     │  Job: Bundle holen → BlueMap-CLI → Map-Storage
         │   + S3StorageAddon   │
         │   + telemetry-addon  │  /progress (JSON) · /metrics (Prometheus)
         └──────────┬───────────┘
                    ▼
            Map-Storage in S3
                    │
                    ▼
         ┌──────────────────────┐
         │  bluemap-webserver   │  Deployment + Service + Ingress + Zertifikat
         │  + S3StorageAddon    │
         └──────────────────────┘

   Alles gesteuert vom bluemap-operator über sechs CRDs.
   bluemap-api liest die CRs und Logs, bluemap-ui zeigt sie an.
```

Die zentrale Trennlinie verläuft am **World Bundle**. Links davon weiß niemand etwas von
BlueMap, rechts davon niemand etwas von Pterodactyl, ZIP-Uploads oder Bukkit-Ordnerstrukturen.

---

## 4. Bausteine

Alle in einem Gradle-Monorepo `Apus`, mehrmodulig.

| Modul | Sprache/Stack | Zweck |
|---|---|---|
| `telemetry-addon` | Java 21, BlueMap-Addon | Exponiert Render-Fortschritt als JSON und Prometheus-Metriken |
| `world-ingest` | Java 21, Micronaut | ETL: Connector-SPI, Layout-Erkennung, Bundle-Writer. Läuft als Job |
| `runner-image` | Dockerfile + Entrypoint | BlueMap-CLI + beide Addons + Bundle-Sync |
| `operator` | Java 21, Micronaut + Java Operator SDK | Sechs CRDs, erzeugt Jobs/Deployments/Ingresses/Buckets |
| `api` | Java 21, Micronaut | REST + SSE über den CRs, Log-Aggregation, Auth-Durchsetzung |
| `ui` | Nuxt 4, Vue 3, Tailwind 4, Nuxt UI | Zwei Dashboard-Ebenen |
| `paper-worldpush` | Java 21, Paper-Plugin | Async, inkrementeller Welt-Upload vom laufenden Server |

`telemetry-addon` und `paper-worldpush` hängen an fremden Versionen (BlueMap bzw. Paper)
und bekommen eine eigene Release-Spur mit eigener Versionsmatrix.

---

## 5. World Bundle — der Vertrag

Ein Bundle ist eine unveränderliche, versionierte Momentaufnahme einer Welt in
normalisierter Form.

```
worlds/<tenant>/<world-id>/<version>/
  manifest.json
  overworld/region/r.0.0.mca …
  overworld/entities/…            (falls vorhanden)
  overworld/poi/…                 (falls vorhanden)
  the_nether/region/…
  the_end/region/…
  level.dat
```

```jsonc
{
  "schemaVersion": 1,
  "tenant": "friends-server",
  "worldId": "survival",
  "version": "2026-08-08T04:12:00Z",
  "source": { "type": "pterodactyl", "ref": "backup-uuid", "detectedLayout": "bukkit" },
  "minecraftVersion": "1.21.10",
  "dimensions": [
    { "id": "overworld",  "path": "overworld/region",  "regions": [[0,0],[0,1]], "regionCount": 812 },
    { "id": "the_nether", "path": "the_nether/region", "regions": [[0,0]],       "regionCount": 96 }
  ],
  "sizeBytes": 21474836480,
  "checksums": { "algorithm": "sha256", "manifest": "…" }
}
```

Regeln:

1. **`manifest.json` wird zuletzt geschrieben.** Es ist der Commit-Punkt. Ohne Manifest existiert das Bundle nicht und wird nie gerendert. Damit gibt es keine halb entpackten Welten im Render-Pfad.
2. **Bundles sind unveränderlich.** Neue Welt-Daten erzeugen eine neue Version, nie eine Änderung an einer bestehenden.
3. **Die Regionsliste steht im Manifest.** Sie kostet beim Ingest nichts (jede `.mca`-Datei wird ohnehin angefasst) und ist die Grundlage für Sharding und für genaue Fortschrittsberechnung.
4. **Dimensionen sind logisch benannt** (`overworld`, `the_nether`, `the_end`), unabhängig davon, ob die Quelle Vanilla- oder Bukkit-Layout verwendet hat.

---

## 6. Ingest-Layer (ETL)

### 6.1 Aufteilung

Nur **Extract** ist quellenspezifisch. **Transform** und **Load** sind gemeinsam.
Ein neuer Connector kostet damit eine Implementierung von zwei Methoden.

```java
interface WorldSourceConnector {
    String type();
    List<SourceVersion> discover(WorldSourceConfig cfg);   // Pull; Push liefert leere Liste
    FetchResult fetch(WorldSourceConfig cfg, SourceVersion version, Path workDir);
}
```

| Connector | Art | Extract |
|---|---|---|
| `pterodactyl` | Pull | Backup-Liste über die Client-API abfragen, ausgewähltes Backup als signierte URL laden, `tar.gz` streamen und nur Welt-Pfade herausschreiben |
| `s3` | Pull | Bucket-Prefix auf neue Objekte prüfen, Archiv oder Ordnerstruktur laden |
| `upload` | Push | Presigned Multipart in ein Staging-Prefix, Abschluss meldet die UI |
| `push` | Push | Paper-Plugin schreibt direkt in ein Staging-Prefix und meldet Vollzug |

Da `tar.gz` nicht seekbar ist, wird der Stream einmal vollständig durchlaufen und dabei
selektiv geschrieben. Das gesamte Archiv landet nie auf der Platte.

### 6.2 Transform: Layout-Erkennung

Der kritische Teil. Erkannt wird anhand der Verzeichnisstruktur:

| Layout | Erkennungsmerkmal | Abbildung |
|---|---|---|
| `vanilla` | `<w>/region`, `<w>/DIM-1/region`, `<w>/DIM1/region` | direkt |
| `bukkit` | `<w>/region`, `<w>_nether/DIM-1/region`, `<w>_the_end/DIM1/region` | Ordner zusammenführen |
| `nested` | genau ein Unterordner, darin eines der obigen | Ebene überspringen, erneut prüfen |

`WorldSource.spec.worlds[].layout: auto` erkennt automatisch; `vanilla` oder `bukkit`
erzwingen. Schlägt die Erkennung fehl, bricht der Ingest mit Condition
`LayoutDetectionFailed` und der Liste gefundener Pfade ab — kein Raten.

### 6.3 Load

Dateien werden nach `worlds/<tenant>/<world-id>/<version>/` geschrieben, danach das
Manifest. Anschließend setzt der Operator `WorldSource.status.latestBundle` und löst
abhängige Renders aus (§8.3).

Retention: `WorldSource.spec.retention.keepVersions` (Default 5). Ältere Bundles werden
gelöscht, sofern kein `BlueMapRender` sie noch referenziert.

### 6.4 Trigger

- **Pull-Quellen:** `WorldSource.spec.poll` als Cron-Ausdruck. Der Operator vergleicht mit `status.lastSeenVersion` und legt bei Neuem einen `WorldIngest` an.
- **Push-Quellen:** bevorzugt Bucket-Notification (Rook stellt `CephBucketTopic`/`CephBucketNotification` bereit) auf einen Endpunkt der API, die daraus einen `WorldIngest` erzeugt. **Fallback**, falls Notifications im Cluster nicht aktiviert sind: Polling des Staging-Prefix im selben Intervall wie Pull-Quellen. Die Entscheidung fällt beim Bau von Phase 2 nach Prüfung der Cluster-Konfiguration; beide Wege münden in denselben Code-Pfad.

---

## 7. Render-Layer

### 7.1 Ablauf eines Renders

Ein `BlueMapRender` erzeugt einen Kubernetes-`Job` mit:

1. **Init: `bundle-sync`** — lädt die im Manifest gelisteten Dimensionen des Bundles auf ein `emptyDir` (oder PVC bei großen Welten).
2. **Init: `assets-sync`** — holt die Minecraft-Client-JAR der benötigten Version aus dem Asset-Cache-Bucket. Verhindert, dass jeder Render-Pod erneut bei Mojang lädt.
3. **Main: `bluemap`** — BlueMap-CLI mit `-r`, dazu im `packs/`-Ordner `BlueMapS3Storage` (Map-Output) und `telemetry-addon` (Fortschritt). Die Konfiguration erzeugt der Container beim Start selbst aus Umgebungsvariablen (§7.4); Zugangsdaten kommen dabei aus dem von Rook erzeugten Secret. Es wird **keine** ConfigMap gemountet — siehe die Anmerkung in §9.2.

Der Map-Output geht direkt über den S3-Storage in den Ziel-Bucket. Es gibt keinen
separaten Upload-Schritt — und damit auch keinen Zustand, der zwischen „gerendert" und
„hochgeladen" verloren gehen kann.

### 7.2 Fortschritt

Das `telemetry-addon` startet einen HTTP-Server (Default `:8099`):

```jsonc
// GET /progress
{
  "state": "rendering",             // starting | rendering | idle | unknown
  "currentMap": "overworld",
  "progress": 0.674,
  "etaSeconds": 1830,
  "queuedTasks": -1,                // im CLI-Betrieb strukturell nicht ermittelbar, siehe unten
  "renderThreads": -1,              // dito
  "degraded": false,                // true, wenn Progress nicht ermittelbar
  "description": "updating map 'overworld'"
}
```

`queuedTasks` und `renderThreads` zeigen hier `-1` statt Beispielwerten: Im CLI-Betrieb liest
Apus den Fortschritt über Log-Tailing (siehe unten), und BlueMaps eigene Fortschrittszeile
enthält weder Warteschlangentiefe noch Thread-Anzahl — diese Felder sind unter dieser Route
strukturell unerreichbar, kein Erhebungsfehler.

`/metrics` liefert dieselben Werte als Prometheus-Metriken; ein `PodMonitor` sammelt sie
für Grafana und Historie.

**Bekannte Kopplung:** `estimateProgress()` ist über die öffentliche `BlueMapAPI` nicht
erreichbar. Zwei Implementierungen von `RenderManagerAccess` existieren, `ApusTelemetryAddon`
wählt zwischen ihnen — keine Reflection in beiden Fällen:

1. **`BlueMapRenderManagerAccess`** — der von BlueMap für Addons dokumentierte Weg,
   `((BlueMapAPIImpl) api).plugin().getRenderManager()`. Liefert bei Erfolg zusätzlich
   Warteschlangentiefe und Thread-Anzahl.
2. **`LogTailRenderManagerAccess`** — registriert sich auf BlueMaps eigenem
   `Logger.global` (`de.bluecolored.bluemap.core.logger.Logger`/`MultiLogger`, derselbe
   Mechanismus, den die CLI-Flags `-l`/`-b` selbst nutzen) und parst die Fortschrittszeile,
   die BlueMap ohnehin selbst loggt (`updating map 'overworld': 35.208% (ETA: 38 seconds)`).
   Liefert keine Warteschlangentiefe/Thread-Anzahl (nicht in der Logzeile enthalten, dort
   `-1`).

**Verifiziert gegen BlueMap 5.23, Task 8:** Im CLI-Betrieb — dem Modus, in dem
`apus/runner` BlueMap ausschließlich nutzt — ist Weg 1 (`plugin()`) **strukturell immer**
`null`. `BlueMapCLI.renderMaps()` konstruiert `BlueMapAPIImpl` unbedingt mit
`Plugin = null` (per Dekompilierung verifiziert); BlueMap selbst überspringt dann auch den
Bau der internen `RenderManagerImpl`, sodass auch ein Reflection-Fallback darauf ins Leere
liefe — es gibt in diesem Modus kein über `BlueMapAPI` erreichbares Objekt, das den echten
`RenderManager` hält. Der ursprünglich hier skizzierte Reflection-Fallback auf
`RenderManagerImpl` ist für den CLI-Betrieb daher **kein** gangbarer Weg — er setzt eine
Instanz voraus, die im CLI-Modus nie entsteht. Weg 2 (Log-Tailing) funktioniert im
CLI-Betrieb dagegen zuverlässig, da BlueMaps eigene CLI die Fortschrittszeile unabhängig
vom `Plugin`-Objekt über den globalen Logger ausgibt. `apus/runner` läuft daher
ausschließlich auf Weg 2; Weg 1 bleibt für einen künftigen Server-Plugin-Betrieb
(dort existiert eine echte `Plugin`-Instanz) als bevorzugter, reichhaltigerer Pfad
erhalten. Details, beobachtete `/progress`-Antworten und die verworfene Alternative
(Reflection in `java.util.Timer`-Internals) stehen in `runner/README.md#telemetry`.

Absicherungen:

- Beide Zugriffswege sind hinter dem `RenderManagerAccess`-Interface gekapselt
  (`telemetry-addon/.../probe/`), `ApusTelemetryAddon` wählt nur zwischen ihnen. Ein
  späterer dritter Weg (offizielle API-Erweiterung, eigener Runner) ersetzt nur die
  Verdrahtung dort.
- Schlägt jeder Zugriff fehl (auch die Log-Tail-Registrierung selbst), liefert
  `/progress` `degraded: true`, **ohne** den Render zu beeinträchtigen. Fortschritt ist
  Komfort, kein kritischer Pfad.
- Ein Contract-Test (`runner/src/test/java/.../TelemetryContractTest.java`) läuft gegen
  einen echten Render und muss vor jedem BlueMap-Upgrade ausgeführt werden. **Ehrlich
  bilanziert deckt er nur Weg 2 ab** (Log-Tailing, der einzige Weg, der im CLI-Betrieb
  überhaupt greift). Weg 1 (`BlueMapRenderManagerAccess`) und `ApusTelemetryAddon.run()`
  selbst haben in Phase 1 **keine** Testabdeckung — Weg 1 wird erst relevant, sobald Apus
  auf einer Server-Plattform mit echter `Plugin`-Instanz läuft, was Phase 1 nicht abdeckt.
- Mittelfristig: Upstream-PR, die den CLI-eigenen `RenderManager` unabhängig von `Plugin`
  über `BlueMapAPI` erreichbar macht (siehe `runner/README.md#telemetry`), würde Weg 1
  auch im CLI-Betrieb tragfähig machen und die Log-Tail-Notlösung überflüssig machen.

Der Operator pollt `/progress` im Sekundentakt über den Pod und schreibt die Werte nach
`BlueMapRender.status.progress`. Damit zeigt auch `kubectl get bluemaprender` den Stand.

### 7.3 Resume und Nebenläufigkeit

Da Chunk-Hashes und Tile-States im Map-Storage liegen, setzt ein neu gestarteter Pod die
Arbeit fort. `backoffLimit` steuert die Versuche; danach geht die CR auf `Failed` — mit
erhaltenem letztem Fortschrittswert, damit sichtbar bleibt, wo es abbrach.

**Zwei gleichzeitige Renders auf denselben Map-Storage sind zu verhindern.** Konkurrierende
Schreiber auf Tile- und State-Daten können die Karte inkonsistent hinterlassen.
`BlueMapMap.spec.trigger.concurrencyPolicy: Forbid` ist Default und wird über den
CR-Status als Lock durchgesetzt: Der Operator legt keinen neuen Job an, solange ein
`BlueMapRender` derselben Map in einer aktiven Phase steht.

Der Hosting-Pod liest, während gerendert wird. Das ist unkritisch — BlueMap ist auf
laufende Aktualisierung ausgelegt; Nutzer sehen kurzzeitig gemischte Stände.

### 7.4 Umgebungsvariablen-Vertrag (Phase 1)

`apus/runner` (§7.1, Container `bluemap`) wird ausschließlich über Umgebungsvariablen
konfiguriert. Das ist die Schnittstelle, die der Operator in Phase 2 bedienen wird — der
`Job` erzeugt genau diese Variablen aus `BlueMapRender`/`BlueMapMap` und den Rook-Werten
aus §9.1. Verifiziert und ausgeliefert in Phase 1 (`runner/entrypoint.sh`,
`runner/bin/render-config.sh`):

| Variable | Pflicht | Bedeutung |
|---|---|---|
| `APUS_MAP_ID` | ja | Map-Id, z.B. `overworld`. Wird als Pfadsegment verwendet — nur Kleinbuchstaben, Ziffern, `-`, `_` |
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
| `APUS_TELEMETRY_BIND` | nein | Lausch-Adresse des Telemetrieservers, Default `0.0.0.0` |
| `APUS_TELEMETRY_ENABLED` | nein | `false` schaltet den Telemetrieserver ab; jeder andere Wert lässt ihn laufen |

Die vollständige, laufend aktuelle Referenz mit Beispielwerten steht in
`runner/README.md`.

---

## 8. Datenmodell

API-Gruppe `bluemap.onelitefeather.net/v1alpha1`. Alle Ressourcen namespaced außer
`Tenant`. Muster: **Vorlage erzeugt Ausführungen**, analog CronJob→Job.

### 8.1 Tenant (cluster-scoped)

```yaml
apiVersion: bluemap.onelitefeather.net/v1alpha1
kind: Tenant
metadata: { name: friends-server }
spec:
  displayName: "Friends Server"
  storage:
    quota: 500Gi                 # → CephObjectStoreUser.spec.quotas.maxSize
    maxObjects: 5000000
  compute:
    cpu: "16"
    memory: 32Gi
    maxConcurrentRenders: 2
  hosting:
    allowedDomains: ["*.friends.example.net"]
  auth:
    organization: friends-server # Organisation im Identity-Broker
status:
  namespace: bluemap-friends-server
  objectStoreUser: apus-friends-server
  storageUsedBytes: 228730548224
  conditions: [...]
```

Der Operator erzeugt daraus: Namespace, `CephObjectStoreUser` mit Quota, `ResourceQuota`,
`LimitRange`, RBAC und eine NetworkPolicy.

### 8.2 WorldSource

```yaml
kind: WorldSource
metadata: { name: survival, namespace: bluemap-friends-server }
spec:
  type: pterodactyl              # | s3 | upload | push
  pterodactyl:
    panelUrl: https://panel.example.net
    serverId: a1b2c3d4
    credentialsSecretRef: { name: ptero-token }
    select: latest
  poll: "0 */6 * * *"            # nur Pull-Typen
  worlds:
    - name: world
      layout: auto               # | vanilla | bukkit
  retention: { keepVersions: 5 }
status:
  lastSeenVersion: "backup-uuid"
  latestBundle: { path: "worlds/friends-server/survival/2026-08-08T04:12:00Z", version: "..." }
  conditions: [...]
```

### 8.3 WorldIngest

```yaml
kind: WorldIngest
spec:
  sourceRef: { name: survival }
  sourceVersion: "backup-uuid"
status:
  phase: Transforming            # Pending|Extracting|Transforming|Loading|Succeeded|Failed
  progress: { percent: 42.0, bytesDone: 9021849600, bytesTotal: 21474836480 }
  bundle: { path: "...", version: "...", dimensions: ["overworld","the_nether"] }
  jobRef: { name: ingest-survival-x7f2 }
  startTime: "..."
  completionTime: null
  conditions: [...]
```

### 8.4 BlueMapMap

```yaml
kind: BlueMapMap
metadata: { name: survival-overworld }
spec:
  source:
    sourceRef: { name: survival }
    world: world
    dimension: overworld
  trigger:
    onNewBundle: true
    schedule: "0 4 * * *"
    concurrencyPolicy: Forbid    # | Replace
  bluemap:
    version: "5.11"
    config:
      configMapRef: { name: survival-bluemap }   # optional
      overrides:                                  # optional, punktuell
        render-threads: 8
  storage:
    bucketClaim: auto            # Operator legt ObjectBucketClaim an
    prefix: survival
  resources: { cpu: "8", memory: 16Gi }
  shards: 1                      # >1 erst ab Phase 4
  historyLimit: 10
  purgeOnDelete: false
status:
  latestRender: { name: render-survival-overworld-9k3d, phase: Succeeded }
  bucket: { name: apus-friends-server-survival, endpoint: "..." }
  conditions: [...]
```

### 8.5 BlueMapRender

```yaml
kind: BlueMapRender
spec:
  mapRef: { name: survival-overworld }
  bundleVersion: "2026-08-08T04:12:00Z"
  force: false                   # entspricht --force-render
status:
  phase: Rendering               # Pending|Syncing|Rendering|Finalizing|Succeeded|Failed
  progress:
    percent: 67.4
    currentMap: overworld
    etaSeconds: 1830
    degraded: false
  jobRef: { name: render-survival-overworld-9k3d }
  startTime: "..."
  completionTime: null
  conditions: [...]
```

### 8.6 BlueMapHosting

```yaml
kind: BlueMapHosting
spec:
  maps: [ { name: survival-overworld }, { name: creative-overworld } ]
  hostname: map.friends.example.net
  ingressClassName: nginx
  tls: { issuerRef: { name: ..., kind: ClusterIssuer } }
  replicas: 1
status:
  url: https://map.friends.example.net
  ready: true
  conditions: [...]
```

### 8.7 Bewusst nicht modelliert

- **Kein `BlueMapSchedule`.** Ein Zeitplan pro Map genügt und steht als Feld in der Vorlage. Dasselbe gilt für `WorldSource.poll`.
- **Ingest und Render bleiben getrennt.** Beide sind langlaufend, scheitern unabhängig und tragen eigenen Fortschritt. Zusammengelegt gäbe es eine Ressource mit zwei konkurrierenden Zustandsmaschinen, und ein fehlgeschlagener Render würde einen teuren Re-Ingest erzwingen, obwohl das Bundle intakt ist.

---

## 9. Automatisches Setup von S3 und BlueMap

### 9.1 S3 wird an Rook delegiert

Der Operator enthält **keinen** S3-Administrationscode. Bei `storage.bucketClaim: auto`
legt er eine `ObjectBucketClaim` gegen die Bucket-StorageClass an, mit
`additionalConfig.bucketOwner` = dem `CephObjectStoreUser` des Mandanten. Rook erzeugt
daraufhin Bucket, Credentials-Secret und ConfigMap mit Endpoint und Bucket-Namen; der
Operator wartet auf deren Bereitstellung und verdrahtet die Werte weiter.

**Abweichung von der bestehenden Konvention, bewusst:** Vorhandene Bucket-Claims liegen
zentral im Rook-Namespace. Apus legt sie im **Mandanten-Namespace** an, weil Rook Secret
und ConfigMap stets im Namespace der Claim erzeugt — so stehen die Credentials dort, wo
Render-Job und Hosting-Pod sie brauchen, ohne Secrets über Namespace-Grenzen zu kopieren.

Weil alle Buckets eines Mandanten seinem `CephObjectStoreUser` gehören, zählt ihr
gesamter Verbrauch gegen dessen Quota (§10.2).

### 9.2 BlueMap-Konfiguration wird generiert (Phase 3, Hosting)

**Klarstellung (2026-08-08, Review Phase 2a):** Diese Sektion beschrieb ursprünglich, dass der
Operator die Render-Konfiguration als ConfigMap ausliefert. Das widersprach §7.4: Der Phase-1-
Runner wird für den Render **ausschließlich über Umgebungsvariablen** konfiguriert und liest nie
etwas aus einem gemounteten Pfad — das ist gegen einen echten Render verifiziert
(`runner/entrypoint.sh`, `runner/bin/render-config.sh`). Der `RenderJobBuilder` aus Phase 2a
mountet deshalb bewusst **keine** ConfigMap; §7.4 ist für den Render-Pfad maßgeblich, nicht diese
Sektion.

Die hier beschriebene Konfigurationserzeugung bleibt gültig, aber erst für **Phase 3**
(`BlueMapHosting`) relevant: Der langlebige Webserver-Pod, der bereits gerenderte Karten
ausliefert, braucht ein vollständiges `webserver.conf` und dieselbe Speicher-Anbindung — eine
Oberfläche, die der Render-Umgebungsvariablen-Vertrag aus §7.4 nicht abdeckt. `BlueMapConfigBuilder`
existiert bereits (Phase 2a) und generiert diese Dateien, wird aber erst mit dem Hosting-Pod in
Phase 3 tatsächlich verdrahtet.

Aus der CR und den Rook-Werten erzeugt der Operator für den Hosting-Pod die vollständige
BlueMap-Konfiguration als ConfigMap (plus Secret für Zugangsdaten):

| Datei | Inhalt |
|---|---|
| `core.conf` | Datenverzeichnis, `render-threads`, Metrics deaktiviert, `accept-download: true` (**erforderlich** — ohne diesen Schlüssel lädt BlueMap die Minecraft-Ressourcen nicht und jeder Render schlägt mit Exit-Code 2 fehl), `scan-for-mod-resources: false` |
| `storages/s3.conf` | Endpoint, Bucket, Path-Style-Zugriff, Credentials — für `BlueMapS3Storage` |
| `maps/<id>.conf` | Weltpfad aus dem Bundle-Manifest, Dimension, Render-Einstellungen |
| `webserver.conf` | nur im Hosting-Pod |

Nutzer schreiben kein HOCON. Wer Sonderfälle braucht, setzt gezielt
`bluemap.config.overrides` oder referenziert eine eigene ConfigMap.

**Verifiziertes Format von `storages/s3.conf`** (Phase 1, Task 7 — per Integrationstest
gegen einen echten BlueMap-CLI-Lauf und Quellcode-Review von `S3StorageConfiguration`
bestätigt; der Operator muss beim Verdrahten in Phase 3 exakt diese Schlüssel erzeugen):

```hocon
storage-type: "themeinerlp:s3"
bucket-name: "..."
region: "..."
access-key-id: "..."
secret-access-key: "..."
endpoint-url: "..."
compression: "gzip"
root-path: "..."
force-path-style: true
```

### 9.3 Asset-Cache

BlueMap lädt für Texturen und Modelle die Minecraft-Client-JAR. In ephemeren Pods geschähe
das bei jedem Lauf. Stattdessen liegen die benötigten Client-JARs pro Minecraft-Version in
einem plattformweiten Asset-Bucket; der Init-Container `assets-sync` holt sie von dort.
Fehlt eine Version, lädt ein einmaliger Job sie und legt sie ab.

### 9.4 Lebenszyklus

`reclaimPolicy: Retain` auf der Bucket-StorageClass bedeutet: Das Löschen einer
`BlueMapMap` löscht **keine** Daten. Das ist beabsichtigt — ein versehentliches
`kubectl delete` darf keine stundenlange Renderarbeit vernichten. Aufräumen erfordert
`spec.purgeOnDelete: true`; der Operator setzt dafür einen Finalizer.

---

## 10. Mandanten, Quotas, Auth

### 10.1 Trennung

Ein Mandant entspricht einem Namespace. Darin: eigene S3-Credentials als Secret,
`ResourceQuota` und `LimitRange` als Obergrenze, NetworkPolicy. Der Operator läuft
clusterweit, arbeitet aber strikt namespace-lokal: Eine CR darf ausschließlich Secrets
und Ressourcen ihres eigenen Namespaces referenzieren. Referenzen über Namespace-Grenzen
werden bei der Validierung abgelehnt.

### 10.2 Speicherlimit

`Tenant.spec.storage.quota` wird auf `CephObjectStoreUser.spec.quotas.maxSize` abgebildet
(zusätzlich `maxObjects`). Die Durchsetzung erfolgt in Ceph: RGW weist Uploads oberhalb
des Limits ab. Das ist kein Anzeigewert — ein Mandant kann sein Limit auch dann nicht
überschreiten, wenn die Anwendung sich verrechnet.

Per-Bucket-Quotas (`bucketMaxSize` via `additionalConfig`) werden **nicht** verwendet: Sie
sind in Rook standardmäßig deaktiviert und würden eine clusterweite Operator-Konfiguration
erfordern. Die User-Quota erfüllt den Zweck.

Den Verbrauch fragt der Operator periodisch über die RGW-Admin-Ops-API ab und schreibt ihn
nach `Tenant.status.storageUsedBytes`. Die Zugangsdaten dafür sind plattformweit, nicht
mandantengebunden.

### 10.3 Authentifizierung

Vor Apus steht ein **Identity-Broker mit Organisationsunterstützung** (z.B. Keycloak ab
Version 26 oder Zitadel — beide bieten Organisationen mit eigenem Identity-Provider je
Organisation sowie Einladungs-Flows; die konkrete Produktwahl erfolgt zu Beginn von
Phase 5 nach Prüfung gegen den bestehenden OIDC-Betrieb).

Damit ist beides abgedeckt, ohne dass Apus Passwörter verwaltet:

- Mandant mit eigenem IdP: Der Broker föderiert zu dessen OIDC.
- Mandant ohne eigenen IdP: lokale Accounts im Broker, verwaltet von dessen Admins.

Apus kennt genau **einen** Issuer. Der Organisations-Claim im Token bestimmt den Mandanten
und damit den Namespace.

Rollen: `platform-admin`, `tenant-owner`, `tenant-operator`, `tenant-viewer`.

| Rolle | Darf |
|---|---|
| `platform-admin` | Tenants anlegen/ändern/löschen, Quotas, clusterweite Sicht |
| `tenant-owner` | alles im eigenen Mandanten inkl. Mitgliederverwaltung |
| `tenant-operator` | Quellen und Maps pflegen, Renders auslösen |
| `tenant-viewer` | nur lesen |

**Service-Tokens** sind mandantengebunden und tragen einen engen Scope (`world:push`).
Das Paper-Plugin und CI-Prozesse nutzen sie. Sie hängen bewusst an keinem Nutzer-Login —
sonst würde das Ausscheiden einer Person den Server-Upload lahmlegen.

Das Backend ist der Durchsetzungspunkt: Es prüft erst die Anwendungsrechte und spricht
danach mit der Kubernetes-API über sein eigenes ServiceAccount. Keine Impersonation.

---

## 11. API und UI

### 11.1 API

Micronaut, REST plus SSE. Die CRs sind die Quelle der Wahrheit; die API hält keine
Kopie des Zustands, sondern liest über einen Informer-Cache.

| Endpunkt | Zweck |
|---|---|
| `GET /api/tenants` … | Plattformebene, nur `platform-admin` |
| `GET /api/sources`, `POST /api/sources` | Quellen des eigenen Mandanten |
| `POST /api/maps/{id}/render` | Render auslösen (erzeugt `BlueMapRender`) |
| `GET /api/renders/{id}/events` | SSE: Fortschritt live |
| `GET /api/renders/{id}/logs` | SSE: Log-Stream aus Loki |
| `POST /api/uploads` | presigned Multipart für Welt-Upload |
| `POST /api/push/{token}` | Push-Meldung vom Paper-Plugin |

Für den Log-Stream wird Loki abgefragt (Alloy sammelt bereits alle Pod-Logs), gefiltert
auf den Job des jeweiligen Renders. Damit braucht die API keinen direkten Pod-Zugriff.

### 11.2 UI

Nuxt 4 im SPA-Modus (`ssr: false`), Vue 3, Tailwind 4, Nuxt UI, VueUse — abgestimmt auf
`launchpad`. Zwei Ebenen, getrennt über die Rolle im Token:

- **Plattform:** Mandanten, Quotas mit Verbrauchsanzeige, laufende Jobs clusterweit, Domain-Freigaben.
- **Mandant:** Quellen, Maps, Render-Historie, Live-Fortschritt mit ETA, Log-Viewer, Hosting-URLs, Mitglieder.

Barrierefreiheit wird über `eslint-plugin-vuejs-accessibility` geprüft, wie in `launchpad`.

Da BlueMaps Web-App selbst Vue ist und `window.bluemap` exponiert, kann die gehostete
Karte später eingebettet und gesteuert werden, statt nur verlinkt zu sein. Nicht im MVP.

---

## 12. Fehlerbehandlung

| Fall | Verhalten |
|---|---|
| Render-Pod stirbt (OOM, Eviction) | Neuer Pod setzt über den Render-State in S3 fort. Nach `backoffLimit` Phase `Failed`, letzter Fortschritt bleibt sichtbar |
| Zwei Renders auf dieselbe Map | Durch `concurrencyPolicy: Forbid` verhindert, Lock über CR-Status |
| Ingest bricht ab | Kein Manifest → Bundle gilt als nicht existent. Kein halber Zustand im Render-Pfad |
| Unbekanntes Welt-Layout | Condition `LayoutDetectionFailed` mit gefundenen Pfaden. Kein Raten, kein Retry |
| Speicherlimit erreicht | RGW-Fehler → Condition `StorageQuotaExceeded`, **kein** Retry. Sonst läuft der Job endlos gegen eine Wand |
| Telemetry-Zugriffsweg bricht nach BlueMap-Update | `/progress` degradiert (siehe §7.2), Render läuft normal weiter |
| Rook liefert Bucket nicht | `BlueMapMap` bleibt in `Pending` mit Condition `BucketProvisioning`, kein Job wird gestartet |
| Bundle-Version wurde gelöscht | Render schlägt mit `BundleNotFound` fehl; Retention löscht nur unreferenzierte Bundles |

Credentials erscheinen nie in CR-Status, Events oder Logs.

---

## 13. Observability und Tests

### 13.1 Observability

- **Metriken:** Das Telemetry-Addon exponiert `/metrics`, eingesammelt über `PodMonitor`. Der Operator exportiert eigene Metriken (Renders nach Phase, Ingest-Dauer, Quota-Auslastung je Mandant).
- **Logs:** Alloy → Loki, wie im Cluster üblich. Die API liest daraus den Live-Stream.
- **Dashboards:** ein Grafana-Dashboard je Ebene (Plattform, Mandant).

### 13.2 Tests

| Baustein | Vorgehen |
|---|---|
| `world-ingest` | Fixture-Archive je Layout (Pterodactyl-`tar.gz`, Bukkit-Split, Vanilla, ZIP mit Unterordner, defektes Archiv) gegen den Layout-Detektor. Reine Unit-Tests |
| `telemetry-addon` | Contract-Test pro BlueMap-Version: Mini-Welt rendern, `/progress` auf plausible Werte prüfen (deckt den Log-Tail-Weg ab, siehe §7.2). **Offen:** Eine CI-Matrix über unterstützte BlueMap-Versionen als Frühwarnsystem existiert nicht — Phase 1 hat im Repository keinerlei CI-Konfiguration angelegt. Bis dahin muss der Contract-Test vor jedem BlueMap-Upgrade manuell laufen |
| `runner-image` | Integrationstest gegen S3-Testcontainer mit kleiner Welt |
| `operator` | JOSDK `LocallyRunOperatorExtension` gegen k3s via Testcontainers |
| `api` | Micronaut-Tests gegen einen Fake-Kubernetes-Client, Auth-Fälle je Rolle |
| `ui` | Komponententests plus Accessibility-Lint |
| `paper-worldpush` | MockBukkit für die Kopierlogik, zusätzlich ein Lauf gegen einen echten Paper-Server für das Save-Fenster |
| E2E | k3s + S3: kompletter Durchlauf Ingest → Render → Hosting mit Mini-Welt |

**Hinweis zur CRD-Generierung:** Der Fabric8-CRD-Generator ist auf Maven ausgerichtet. Im
Gradle-Monorepo wird er über den Annotation-Processor bzw. eine Gradle-Task eingebunden,
die die `CRDGenerator`-API aufruft. Das ist beim Aufsetzen von Phase 2 zu verifizieren.

---

## 14. Phasenplan

Der MVP verwendet **BlueMap-CLI unverändert**. Ein eigener Renderer ist ausdrücklich kein
MVP-Bestandteil.

### Phase 1 — Render-Kern *(MVP)*

`telemetry-addon` und `runner-image`. Ergebnis: Ein `docker run` rendert eine Welt aus S3
nach S3 und meldet Fortschritt. Vollständig ohne Kubernetes testbar.

### Phase 2 — Operator und Ingest *(MVP)*

Sechs CRDs, Reconciler, Job-Erzeugung, Bucket-Provisionierung über Rook,
Konfigurationsgenerierung, Progress in den Status. Ingest mit den ersten Connectoren.
Ergebnis: Renders laufen deklarativ über Flux, `kubectl get bluemaprender` zeigt Prozent
und ETA. Für interne Nutzung bereits vollständig brauchbar.

`Tenant` entsteht bereits hier, weil Namespace, Quota und Bucket-Eigentümer die Grundlage
für alles Weitere sind — im MVP jedoch ausschließlich über Git und `kubectl` gepflegt.
Oberfläche und Identity-Broker dazu kommen erst in Phase 5.

### Phase 3 — Hosting *(MVP)*

`BlueMapHosting`: Webserver-Deployment, Service, Ingress, Zertifikat, URL im Status.
Ergebnis: Karten sind unter eigener Adresse erreichbar. **Ende des MVP.**

### Phase 4 — Region-Sharding *(nach Spike)*

Vorgeschalteter **Spike**: Zwei Prozesse rendern gleichzeitig benachbarte, disjunkte
Regionsmengen in denselben Map-Storage; anschließend werden alle Zoomstufen auf Löcher und
veraltete Bereiche geprüft. Hintergrund: Lowres-Tiles mitteln über Regionsgrenzen hinweg,
weshalb konkurrierende Shards einander überschreiben könnten. Granulare Speicherung
verhindert Korruption, aber nicht notwendigerweise gegenseitiges Überschreiben aggregierter
Werte.

Fällt der Spike positiv aus: `shards: N` über `Job` mit `completionMode: Indexed`, jeder
Pod verarbeitet seinen Anteil der Regionsliste aus dem Manifest. Umsetzung über einen
eigenen Runner, der `scheduleMapUpdateTask(map, regions)` aufruft — öffentliche API, keine
Reflection. Nebeneffekte: Der Welt-Download parallelisiert mit, und der Fortschritt wird
genauer als BlueMaps eigene Schätzung, weil über bekannte Regionsanzahlen aggregiert wird.

Fällt der Spike negativ aus: Alternative ist ein zweistufiges Verfahren (Shards rendern
Hires-Tiles, ein abschließender Lauf baut die Lowres-Ebenen auf) oder der Verzicht auf
Sharding zugunsten vertikaler Skalierung.

Die Architektur ist bereits sharding-fähig ausgelegt: Regionsliste im Manifest,
`shards`-Feld in der CR, Fortschrittsaggregation im Operator.

### Phase 5 — API, UI und Mandanten

Identity-Broker, `Tenant`-Verwaltung mit Quotas, REST/SSE-API, Vue-Dashboard in zwei
Ebenen.

### Phase 6 — Push-Quellen

`paper-worldpush` und UI-Upload inklusive Bucket-Notifications.

---

## 15. Offene Punkte

1. **Connector-Reihenfolge im MVP.** Angenommen wird: zuerst `s3` und `pterodactyl`, weil beide ohne zusätzliche Client-Software auskommen; `upload` und `push` folgen in Phase 6. Falls das Paper-Plugin der wichtigere Weg ist, verschiebt sich die Reihenfolge — ohne Auswirkung auf die Architektur, da alle Connectoren hinter derselben Schnittstelle liegen.
2. **Bucket-Notifications.** Ob `CephBucketTopic`/`CephBucketNotification` im Cluster nutzbar sind, ist vor Phase 6 zu prüfen. Fallback ist Polling.
3. **Produktwahl Identity-Broker.** Zu Beginn von Phase 5, abgestimmt auf den bestehenden OIDC-Betrieb.
4. **CRD-Generierung unter Gradle.** Vorgehen beim Aufsetzen von Phase 2 verifizieren (§13.2).
5. **`render-mask` und Kanten.** Nur relevant, falls in Phase 4 der Maskenweg statt des eigenen Runners gewählt wird: Ob sich das Auffüllen mit Luft außerhalb der Maske abschalten lässt, ist dann zu prüfen.
6. **Volume-Typ für große Welten.** `emptyDir` genügt bis zu einer Größe, die von der Node-Ausstattung abhängt; darüber ist ein PVC nötig. **Offen:** Diese Grenze wurde in Phase 1 entgegen der ursprünglichen Zusage **nicht** gemessen — es ist eigener Scope, keine bloße Verifikation eines bestehenden Plans. Muss vor Phase 2 nachgeholt werden, bevor der Operator einen Default für die CR festlegt.
7. **Kein belastbares Quota-Signal aus dem Runner-Image.** `BlueMapRenderReconciler` erkennt ein Speicherlimit derzeit heuristisch aus dem Grund/der Meldung des terminierten Render-Pods (Muster wie `QuotaExceeded` oder "quota" kombiniert mit einem S3-Bezug wie `bucket`/`rgw`/`ceph`), gestützt auf `terminationMessagePolicy: FallbackToLogsOnError`, damit überhaupt eine Meldung ankommt. Das bleibt Best-Effort: das Kubelet-Vokabular für den Terminierungsgrund enthält "quota" nie, und die Meldung ist nur ein Log-Ausschnitt ohne Vertrag. Ein belastbares Signal (z. B. ein eigener Exit-Code des Runners für "Quota erschöpft") muss vor einem produktiven Einsatz nachgezogen werden, bevor mehr Verhalten (etwa automatische Benachrichtigungen) darauf aufbaut.

---

## 16. Entscheidungen

| Entscheidung | Begründung |
|---|---|
| BlueMap-CLI unverändert statt eigenem Renderer | Geringste Kopplung an BlueMap-Interna; Upgrades sind ein Image-Tag |
| Addon-API bevorzugt, Log-Tailing als tragender Weg in Phase 1 | Ursprünglich war Log-Parsing verworfen worden (kein Vertrag, Locale-/Formatrisiko). Verifiziert in Task 8 (§7.2): Der CLI konstruiert `BlueMapAPIImpl` unbedingt mit `Plugin = null`, wodurch der dokumentierte Addon-Weg (`plugin().getRenderManager()`) im CLI-Betrieb strukturell nie greift — es gibt dort kein über `BlueMapAPI` erreichbares `RenderManager`-Objekt. Log-Tailing auf `Logger.global` ist im CLI-Betrieb der einzige funktionierende Weg und bleibt hinter derselben `RenderManagerAccess`-Schnittstelle gekapselt; der Addon-Weg bleibt für einen künftigen Server-Plugin-Betrieb der bevorzugte, reichhaltigere Pfad |
| Ein Addon, keine Aufteilung in Telemetry und Control | Der Operator löst Renders über CRs aus; ein Control-Endpunkt wäre ein zweiter Weg zum selben Ziel |
| World Bundle als Vertrag | Entkoppelt Quellen von BlueMap; neue Quelle kostet nur einen Connector |
| Manifest zuletzt schreiben | Macht den Bundle-Abschluss atomar, ohne Transaktionen über S3 |
| Rook statt eigenem S3-Code | Bucket, Credentials und Quota sind deklarativ vorhanden |
| Bucket-Claims im Mandanten-Namespace | Rook erzeugt Secret und ConfigMap dort; vermeidet Secret-Kopien über Namespaces |
| Quota auf User- statt Bucket-Ebene | Per-Bucket-Quotas sind in Rook standardmäßig abgeschaltet; die User-Quota erfüllt den Zweck |
| Identity-Broker statt eigener Nutzerverwaltung | Deckt „eigener OIDC" und „interne Accounts" je Mandant ab, ohne dass Apus Passwort-Sicherheit verantwortet |
| Kein `BlueMapSchedule` | Ein Zeitplan pro Map genügt; als Feld statt als Ressource |
| `Retain` als Löschverhalten | Renderarbeit ist teuer; Datenverlust braucht eine ausdrückliche Absicht |
| Vue/Nuxt/Tailwind | Hausstandard aus `launchpad`; BlueMaps eigene Web-App ist ebenfalls Vue |
| Sharding erst nach Spike | Das Lowres-Aggregationsverhalten ist ungeklärt und gehört nicht in den kritischen Pfad des MVP |
