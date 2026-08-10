# Apus Phase 3 — Hosting: Implementierungsplan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Gerenderte Karten unter einer eigenen Adresse erreichbar machen. Eine `BlueMapHosting`-Ressource erzeugt ein BlueMap-Webserver-Deployment, das die Karten direkt aus S3 liest, samt Service, Ingress und Zertifikat — und meldet die URL im Status zurück.

**Architecture:** Der BlueMap-CLI kann Render- und Webserver-Betrieb sauber trennen (`-w/--webserver`). Ein Hosting-Pod ist derselbe CLI im Webserver-Modus mit dem `BlueMapS3Storage`-Addon, das die fertigen Karten aus dem Map-Bucket liest. Anders als beim Render braucht dieser Pod eine **echte Konfigurationsdatei**: Der Umgebungsvariablen-Vertrag aus Phase 1 deckt `webserver.conf` und die Liste der anzuzeigenden Karten nicht ab. Genau dafür wurde `BlueMapConfigBuilder` in Phase 2a gebaut und bewusst unverdrahtet aufgehoben.

**Tech Stack:** Java 25, JOSDK 5.5.1, Fabric8 7.8.0, BlueMap-CLI 5.23, JUnit Jupiter, Fabric8 Mock-Server, Testcontainers.

## Global Constraints

- Java-Toolchain 25, Basispaket `net.onelitefeather.apus.operator`.
- API-Gruppe `bluemap.onelitefeather.net`, Version `v1alpha1`. `BlueMapHosting` ist **namespaced**.
- `initSpec()`/`initStatus()` überschreiben, alle Gruppen im Feld initialisieren — ein `null`-Spec hat in Phase 2a bereits drei parallele Aufgaben blockiert. Der rekursive Null-Check-Test in `IngestResourceTest` zeigt das Muster.
- **Eigentümerprüfung** über Name **und** UID vor jedem Schreibvorgang, Konflikt-Condition statt Übernahme. Das war ein Sicherheitsbefund in Phase 2a.
- **Gemeinsame `Labels`-Klasse** für alle erzeugten Ressourcen.
- `client.supports(...)` für fremde CRDs (`Certificate` von cert-manager), damit ein fehlender cert-manager nicht zum Absturz führt.
- Zugangsdaten niemals in Status, Events, Logs oder ConfigMaps — S3-Zugangsdaten kommen über `secretKeyRef` aus dem von Rook erzeugten Secret.
- AGPL-Header über Spotless, Conventional Commits, **keine** Claude-Attribution, Bezeichner und Javadoc auf Englisch.

### Was bereits existiert und zu benutzen ist

- `BlueMapConfigBuilder` (Phase 2a) erzeugt `core.conf`, `maps/<id>.conf` und `storages/s3.conf`. Für Phase 3 kommt `webserver.conf` dazu, und es müssen **mehrere** Karten in einer Konfiguration stehen.
- `Labels`, `Conditions`, `Ref`, `OperatorConfig`
- Das Eigentümer- und Sperrmuster aus `BlueMapMapReconciler` und `BlueMapRenderReconciler`
- `runner/` als Vorbild für ein Container-Image (nicht-root, Pflichtvariablen zuerst, `exec`)
- Verifizierte CLI-Flags: `-w/--webserver` startet nur den Webserver, `-c <ordner>` setzt den Konfigurationsordner, der `packs/`-Ordner liegt fest unter `<config>/packs`

### Verifizierte Cluster-Gegebenheiten

Aus `Kubernetes-FLUX`: Es gibt zwei IngressClasses (`nginx` und `cloudflare-tunnel`), cert-manager mit step-issuer, und Rook-Ceph als S3. Der Hosting-Pod liest aus demselben Bucket, in den der Render schreibt.

---

## File Structure

```
hosting/                                  neues Modul: Container-Image
├── Dockerfile
├── entrypoint.sh
├── bin/hosting-config.sh
└── README.md

operator/src/main/java/net/onelitefeather/apus/operator/
├── api/BlueMapHosting.java  BlueMapHostingSpec.java  BlueMapHostingStatus.java
└── hosting/
    ├── BlueMapHostingReconciler.java
    └── HostingResourceBuilder.java       Deployment, Service, Ingress, Certificate
```

---

## Parallelisierung

| Gruppe | Aufgaben | Ausführung |
|---|---|---|
| A | Task 1 — CRD und Konfigurationserzeugung | sequenziell |
| B | Task 2, Task 3 | **parallel**, je eigener Worktree |
| C | Task 4 — Reconciler | sequenziell |
| D | Task 5 — Integrationstest | sequenziell |

**Dateien der parallelen Gruppe** (disjunkt):
- Task 2: alles unter `hosting/`
- Task 3: `operator/.../hosting/HostingResourceBuilder.java` + Test

---

### Task 1: `BlueMapHosting` und die Konfiguration für mehrere Karten

**Files:**
- Create: `operator/src/main/java/.../api/BlueMapHosting.java`, `BlueMapHostingSpec.java`, `BlueMapHostingStatus.java`
- Modify: `operator/src/main/java/.../map/BlueMapConfigBuilder.java`
- Test: `operator/src/test/java/.../api/HostingResourceTest.java`
- Modify: `operator/src/test/java/.../map/BlueMapConfigBuilderTest.java`
- Modify: `operator/src/test/java/.../CrdGenerationTest.java`

**Interfaces:**

```java
// BlueMapHostingSpec — alle Gruppen im Feld initialisiert
List<Ref> maps = new ArrayList<>();       // Karten, die dieser Webserver anzeigt
String hostname;                          // Pflicht
String ingressClassName = "nginx";
Tls tls = new Tls();                      // Ref issuerRef; String issuerKind = "ClusterIssuer";
                                          // boolean enabled = true
int replicas = 1;
Resources resources = new Resources();    // String cpu; String memory

// BlueMapHostingStatus
String url;                               // "https://<hostname>" sobald bereit
boolean ready;
List<Condition> conditions = new ArrayList<>();

// BlueMapConfigBuilder — erweitert um den Hosting-Fall
public static Map<String, String> buildForHosting(
        List<BlueMapMap> maps, List<BucketBinding> bindings, int webserverPort);
```

**Der inhaltliche Unterschied zum Render-Fall:** Ein Render-Pod kennt genau eine Karte und bekommt seine Konfiguration aus Umgebungsvariablen. Ein Hosting-Pod zeigt **mehrere** Karten und braucht zusätzlich `webserver.conf`. Für jede Karte entsteht eine eigene `maps/<id>.conf` und ein eigener Storage-Eintrag, weil die Karten in unterschiedlichen Buckets liegen können.

- [ ] **Step 1: Den fehlschlagenden Test für die Ressource schreiben**

Nach dem Muster von `IngestResourceTest`: namespaced, alle Gruppen initialisiert (rekursiv geprüft), Vorgabewerte (`ingressClassName` = `nginx`, `replicas` = 1, `tls.enabled` = true).

- [ ] **Step 2: Ressource implementieren, Test grün bekommen**

- [ ] **Step 3: Den fehlschlagenden Test für die Hosting-Konfiguration schreiben**

```java
    @Test
    void hostingConfigContainsOneMapFilePerMap() {
        Map<String, String> files = BlueMapConfigBuilder.buildForHosting(
                List.of(map("survival-overworld"), map("creative-overworld")),
                List.of(binding("bucket-a"), binding("bucket-b")), 8100);

        assertTrue(files.containsKey("maps/survival-overworld.conf"), files.keySet().toString());
        assertTrue(files.containsKey("maps/creative-overworld.conf"), files.keySet().toString());
    }

    @Test
    void hostingConfigContainsAWebserverConfigBoundToAllInterfaces() {
        Map<String, String> files = BlueMapConfigBuilder.buildForHosting(
                List.of(map("survival-overworld")), List.of(binding("bucket-a")), 8100);

        String webserver = files.get("webserver.conf");
        assertNotNull(webserver, files.keySet().toString());
        assertTrue(webserver.contains("8100"), webserver);
        // A pod must accept connections from the service, not just from localhost.
        assertTrue(webserver.contains("0.0.0.0"), webserver);
    }

    @Test
    void eachMapGetsItsOwnStorageBecauseBucketsCanDiffer() {
        Map<String, String> files = BlueMapConfigBuilder.buildForHosting(
                List.of(map("a"), map("b")),
                List.of(binding("bucket-a"), binding("bucket-b")), 8100);

        assertTrue(files.get("maps/a.conf").contains("storage: \"a\""), files.get("maps/a.conf"));
        assertTrue(files.get("maps/b.conf").contains("storage: \"b\""), files.get("maps/b.conf"));
        assertTrue(files.containsKey("storages/a.conf"), files.keySet().toString());
        assertTrue(files.containsKey("storages/b.conf"), files.keySet().toString());
    }

    @Test
    void neverPutsCredentialsIntoTheHostingConfig() {
        Map<String, String> files = BlueMapConfigBuilder.buildForHosting(
                List.of(map("a")), List.of(binding("bucket-a")), 8100);

        for (Map.Entry<String, String> file : files.entrySet()) {
            assertFalse(file.getValue().contains("secret-access-key: \""),
                    "credentials must not be in " + file.getKey());
        }
    }
```

- [ ] **Step 4: `buildForHosting` implementieren, Test grün bekommen**

Zugangsdaten bleiben auch hier draußen — der Entrypoint des Hosting-Images setzt sie beim Start aus der Umgebung ein, genau wie im Runner.

**Zu verifizieren beim Bau des Images (Task 2):** Der Schlüsselname für die Bind-Adresse in `webserver.conf` stammt aus BlueMaps Default-Konfiguration. Prüfe ihn gegen die echte, vom CLI erzeugte Datei und korrigiere Plan wie Code, falls er abweicht.

- [ ] **Step 5: CRD-Zusicherung ergänzen und committen**

`bluemaphostings` wird erzeugt und trägt `scope: Namespaced`. Danach liegen sechs CRDs vor.

---

### Task 2: Hosting-Image *(parallel mit Task 3)*

> Eigener Worktree. Ausschließlich Dateien unter `hosting/`. Prüfe zuerst die Worktree-Basis (`git log --oneline -1`) — in früheren Phasen wurden Worktrees vom falschen Stand abgezweigt.

Analog zu `runner/`, aber im Webserver-Modus. Der Container läuft **dauerhaft**, nicht als Job.

**Umgebungsvariablen-Vertrag:**

| Variable | Pflicht | Bedeutung |
|---|---|---|
| `APUS_S3_ENDPOINT` | ja | S3-Endpunkt |
| `APUS_S3_ACCESS_KEY` | ja | Zugangsschlüssel |
| `APUS_S3_SECRET_KEY` | ja | Geheimer Schlüssel |
| `APUS_S3_REGION` | nein | Default `us-east-1` |
| `APUS_WEBSERVER_PORT` | nein | Default `8100` |

Die Karten- und Storage-Konfiguration kommt hier **als gemountete ConfigMap** — anders als beim Render, wo Umgebungsvariablen genügen. Der Entrypoint ergänzt nur die Zugangsdaten in den Storage-Dateien, die der Operator ohne sie erzeugt hat. Achte darauf: Eine gemountete ConfigMap ist schreibgeschützt, der Entrypoint muss also in ein beschreibbares Verzeichnis kopieren, bevor er ergänzt.

**Betriebsrelevant:**
- Eine Bereitschaftsprüfung muss möglich sein. Prüfe, welchen Pfad BlueMaps Webserver ausliefert, und dokumentiere ihn — der Reconciler in Task 4 braucht ihn für die Probes.
- `exec` für den Hauptprozess, damit `SIGTERM` ankommt.
- Nicht-root.
- Zugangsdaten dürfen nicht in der Prozess-Kommandozeile stehen. `runner/bin/bundle-sync.sh` erklärt im Kommentar, warum das im Runner über eine Konfigurationsdatei gelöst wurde.

**Verifikation:** Image bauen, gegen ein MinIO mit einer zuvor gerenderten Karte starten, und mit einem HTTP-Aufruf belegen, dass die Karte ausgeliefert wird. Ohne diesen Nachweis gilt die Aufgabe als nicht erledigt.

---

### Task 3: Kubernetes-Ressourcen für das Hosting *(parallel mit Task 2)*

> Eigener Worktree. Ausschließlich `operator/src/main/java/.../hosting/HostingResourceBuilder.java` und sein Test. Prüfe zuerst die Worktree-Basis.

```java
public final class HostingResourceBuilder {
    public static Deployment deployment(BlueMapHosting hosting, String configMapName,
                                        String bucketSecretName, OperatorConfig config);
    public static Service service(BlueMapHosting hosting);
    public static Ingress ingress(BlueMapHosting hosting);
    /** @return empty when TLS is disabled */
    public static Optional<Certificate> certificate(BlueMapHosting hosting);
}
```

**Tests, die zählen:**
- Alle erzeugten Ressourcen tragen die gemeinsamen `Labels` und eine `ownerReference` auf die `BlueMapHosting`, damit Kubernetes sie aufräumt.
- Zugangsdaten kommen über `secretKeyRef`, niemals als Klartext im Manifest.
- Der Ingress verweist auf den Service, der Service auf die Pods, und der Ingress trägt den Hostnamen aus der Spec.
- Ist TLS aktiviert, entsteht ein `Certificate` und der Ingress verweist auf dessen Secret; ist es deaktiviert, entsteht keins.
- Das Deployment mountet die Konfigurations-ConfigMap.
- Bereitschafts- und Lebendigkeitsprüfung sind gesetzt. **Begründung:** Ohne Bereitschaftsprüfung schickt der Service Anfragen an einen Pod, der die Karten noch aus S3 lädt.

`Certificate` ist eine cert-manager-Ressource; modelliere sie schlank als eigene `CustomResource`, wie es für die Rook-Typen gemacht wurde, und **nicht** über die CRD-Generierung.

---

### Task 4: `BlueMapHostingReconciler`

Erzeugt aus einer `BlueMapHosting`: ConfigMap (über `BlueMapConfigBuilder.buildForHosting`), Deployment, Service, Ingress, optional Certificate. Trägt die URL in den Status ein, sobald der Ingress bereit ist.

**Bindend:**
- Eigentümerprüfung über Name und UID vor jedem Schreibvorgang.
- Die referenzierten Karten müssen im selben Namespace liegen und einen gebundenen Bucket im Status haben. Fehlt eine, entsteht kein Deployment, sondern eine sprechende Condition — ein Webserver, der auf einen leeren Bucket zeigt, liefert eine kaputte Seite aus.
- `client.supports(Certificate.class)` prüfen, bevor cert-manager-Ressourcen angefasst werden.
- Ändern sich die Karten, muss die ConfigMap aktualisiert **und** ein Neustart der Pods ausgelöst werden — BlueMap liest seine Konfiguration nur beim Start. Der übliche Weg ist eine Annotation am Pod-Template mit einer Prüfsumme der Konfiguration.
- Registrierung in `ApusOperator`.

---

### Task 5: Integrationstest

Gegen k3s und MinIO: Eine gerenderte Karte in MinIO ablegen (nutze das Ergebnis aus dem bestehenden Render-Integrationstest oder rendere sie im Test), eine `BlueMapHosting` anlegen, reconcilen, und belegen, dass Deployment, Service und Ingress entstehen und die ConfigMap die erwarteten Kartendateien enthält.

Der vollständige Netzwerkweg über einen echten Ingress-Controller ist auf k3s aufwendig; belege stattdessen, dass der Hosting-Pod selbst die Karte ausliefert (das deckt Task 2 bereits ab) und dass die erzeugten Kubernetes-Ressourcen zusammenpassen. Halte im Report fest, was damit **nicht** abgedeckt ist.

Eigene `integrationTest`-Task, nicht Teil von `build`.

---

## Abschluss Phase 3

Danach ist eine gerenderte Karte unter ihrer eigenen Adresse erreichbar, und der Weg von der Welt-Quelle bis zur öffentlichen Karte läuft ohne Handgriff.

**Nicht Teil von Phase 3:** Authentifizierung vor der Karte, mehrere Hostnamen pro Hosting, und die Einbettung der Karte in die Apus-UI (Phase 5).
