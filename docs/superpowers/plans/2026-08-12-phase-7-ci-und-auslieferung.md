# Apus Phase 7 — CI und Auslieferung: Implementierungsplan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Jeder Commit wird automatisch gebaut und getestet, jede Komponente lässt sich als Container-Image oder Maven-Artefakt ausliefern, und Versionen entstehen aus Conventional Commits statt von Hand.

**Architecture:** Apus konsumiert die zentralen wiederverwendbaren Workflows aus `OneLiteFeatherNET/workflows` statt eigene CI zu schreiben. Release Please verwaltet Versionen und Changelogs; `telemetry-addon` und `paper-worldpush` bekommen eigene Release-Spuren, weil sie an fremden Versionen (BlueMap- bzw. Paper-API) hängen — so vorgesehen in der Design-Spec §4. Die drei bisher nicht paketierten Komponenten (`operator`, `api`, `ui`) bekommen Dockerfiles im Stil der bestehenden (`runner`, `ingest`, `hosting`): Multi-Stage, non-root uid 10001, Build-Kontext ist das Repository-Root.

**Tech Stack:** GitHub Actions (`OneLiteFeatherNET/workflows@v2.4.0`), Release Please (`googleapis/release-please-action@v5`), Renovate (zentrales OLF-Preset), Docker (Harbor-Registry), Gradle 9 mit Inline-Version-Catalog, Java 25 (Temurin).

## Global Constraints

- **Java-Toolchain 25 (Temurin)** in jedem Workflow — das ist der Default der zentralen Workflows; nicht überschreiben.
- **Wiederverwendbare Workflows werden auf den vollen SemVer-Tag gepinnt** — `@v2.4.0`, niemals `@main` und niemals `@v2`.
- **Kein `clean` in Gradle-Tasks der CI** — das entwertet den `setup-gradle`-Cache.
- **Der Versionsmarker lebt in `build.gradle.kts`, nicht in `gradle.properties`.** Aktuell steht `version = 999.0.0` in `gradle.properties`; dieser Eintrag wird ersatzlos entfernt.
- **Dockerfiles bauen aus dem Repository-Root als Kontext** und kopieren mit modulqualifiziertem Pfad (`COPY operator/... `), genau wie `runner/Dockerfile` und `ingest/Dockerfile` es tun.
- **Non-root in jedem Image:** Benutzer `apus`, uid 10001, Arbeitsverzeichnis unterhalb `/work` bzw. `/app`.
- **Jar-Dateinamen sind fest** (kein Glob im `COPY`), Konvention wie `ingest`: `archiveFileName.set("apus-<modul>.jar")`.
- **AGPL-Lizenzheader** über jede neue Java-Datei — Spotless erzwingt das via `.spotless/Copyright.java`.
- **Integrationstests laufen nicht im PR-Build.** `operator`, `runner` und `ingest` schließen `**/*IntegrationTest.class` aus `test` aus und tragen einen separaten `integrationTest`-Task; das bleibt so, weil diese Tests Docker und teilweise k3s brauchen.

---

## Vorbedingung (einmalig, außerhalb des Repos)

Das Renovate-Preset verlangt ein GitHub-Team als Reviewer. Ein Team `apus-maintainers` existiert in der Organisation **nicht** (geprüft am 2026-08-12 über `gh api orgs/OneLiteFeatherNET/teams`). Vor Task 2 ist entweder das Team anzulegen:

```bash
gh api -X POST orgs/OneLiteFeatherNET/teams -f name='apus-maintainers' -f privacy='closed'
gh api -X PUT orgs/OneLiteFeatherNET/teams/apus-maintainers/repos/OneLiteFeatherNET/Apus -f permission='push'
```

oder in Task 2 stattdessen ein bestehendes Team einzusetzen — `infrastructure-core-team` ist der naheliegende Kandidat, da Apus Infrastruktur ist. Diese Entscheidung ist die einzige im gesamten Plan, die nicht aus dem Repository ableitbar ist.

---

### Task 1: Root-README

Das Repository hat keinen Einstiegspunkt. Modul-READMEs existieren für `runner`, `hosting`, `ingest`, `ui` und `testdata`, aber wer das Repository öffnet, findet keine Orientierung.

**Files:**
- Create: `README.md`

- [ ] **Schritt 1: README schreiben**

Inhalt (vollständig, nicht kürzen):

```markdown
# Apus

Apus rendert Minecraft-Welten mit [BlueMap](https://bluemap.bluecolored.de/) auf Kubernetes
und hostet die Ergebnisse. Welt-Daten kommen aus mehreren, sehr unterschiedlichen Quellen;
ein ETL-Layer normalisiert sie, ein Operator führt Render- und Hosting-Jobs aus, eine
Oberfläche zeigt Fortschritt und erlaubt Bedienung ohne YAML.

Das vollständige Design steht in
[`docs/superpowers/specs/2026-08-08-apus-design.md`](docs/superpowers/specs/2026-08-08-apus-design.md).

## Module

| Modul | Zweck | Auslieferung |
|---|---|---|
| `telemetry-addon` | BlueMap-Addon, exponiert Render-Fortschritt als JSON und Prometheus-Metriken | Maven |
| `ingest` | ETL: Connectoren (s3, pterodactyl, push, upload), Layout-Erkennung, Bundle-Writer | Container-Image |
| `runner` | BlueMap-CLI plus beide Addons, rendert eine Welt aus S3 nach S3 | Container-Image |
| `hosting` | Langlebiger BlueMap-Webserver, liest gerenderte Karten aus S3 | Container-Image |
| `operator` | Kubernetes-Operator, sechs CRDs, erzeugt Jobs/Deployments/Ingresses/Buckets | Container-Image |
| `api` | Micronaut-REST/SSE über den Custom Resources, Durchsetzungspunkt für Auth | Container-Image |
| `ui` | Nuxt-4-Dashboard für Mandanten und Plattform-Betreiber | Container-Image |
| `paper-worldpush` | Paper-Plugin, schiebt Welten vom laufenden Server nach Apus | Maven |

## Bauen

Voraussetzungen: JDK 25, Docker (für Integrationstests), pnpm (für `ui`).

    ./gradlew build          # alle Java-Module, ohne Integrationstests
    ./gradlew integrationTest # braucht Docker
    ./gradlew :operator:generateCrds  # erzeugt die sechs CRD-YAMLs nach operator/build/crds

    cd ui && pnpm install && pnpm test && pnpm lint

## Entwicklung

Der Kern des Systems ist das **World Bundle** — eine unveränderliche, normalisierte
Momentaufnahme einer Welt in S3. Links davon (Ingest) weiß niemand etwas von BlueMap,
rechts davon (Render, Hosting) niemand etwas von Pterodactyl oder ZIP-Uploads. Wer eine
neue Welt-Quelle anbindet, implementiert nur `WorldSourceConnector` in `ingest`.

Commits folgen [Conventional Commits](https://www.conventionalcommits.org/) — Release
Please leitet daraus Version und Changelog ab.

## Lizenz

AGPL-3.0, siehe [LICENSE](LICENSE).
```

- [ ] **Schritt 2: Verifizieren, dass alle Links auflösen**

Run: `ls docs/superpowers/specs/2026-08-08-apus-design.md LICENSE`
Expected: Beide Pfade existieren.

- [ ] **Schritt 3: Commit**

```bash
git add README.md
git commit -m "docs: add a root README with module overview and build instructions"
```

---

### Task 2: Renovate

**Files:**
- Create: `renovate.json`

**Interfaces:**
- Produces: Die Datei, über die Renovate ab jetzt auch die Workflow-Pins aus Task 4/5/9 aktualisiert.

- [ ] **Schritt 1: `renovate.json` anlegen**

```json
{
  "$schema": "https://docs.renovatebot.com/renovate-schema.json",
  "extends": [
    "github>OneLiteFeatherNET/renovate:default(OneLiteFeatherNET/apus-maintainers)",
    "github>OneLiteFeatherNET/renovate:paper"
  ]
}
```

Das `:paper`-Flavour ist nötig, weil `paper-worldpush` gegen `io.papermc.paper:paper-api` baut, dessen Versionsschema `X.Y.Z-<mc-version>` von der SemVer-Standardauswertung falsch interpretiert wird. Ein `:minestom`-Flavour braucht Apus nicht.

Keine eigenen `packageRules` — Patch-Automerge, Reviewer, Zeitzone, Office-Hours-Schedule, Semantic Commits, das `renovate`-Label und Vulnerability Alerts bringt das Preset bereits mit.

- [ ] **Schritt 2: Team-Slug verifizieren**

Run: `gh api orgs/OneLiteFeatherNET/teams --paginate -q '.[].slug' | grep -x apus-maintainers`
Expected: Ausgabe `apus-maintainers`. Schlägt das fehl, ist die Vorbedingung oben nicht erfüllt — entweder Team anlegen oder den Slug in `renovate.json` auf `infrastructure-core-team` ändern.

- [ ] **Schritt 3: JSON validieren**

Run: `python3 -c "import json;json.load(open('renovate.json'));print('ok')"`
Expected: `ok`

- [ ] **Schritt 4: Commit**

```bash
git add renovate.json
git commit -m "ci: adopt the central OneLiteFeather Renovate preset"
```

---

### Task 3: Versionsmarker und Release Please

`gradle.properties` trägt heute `version = 999.0.0` — ein Platzhalter ohne Automatik dahinter. Release Please verlangt den Marker im jeweiligen `build.gradle.kts`. Apus bekommt drei Release-Spuren: das Gesamtprojekt (dessen Version die Container-Images tragen), `telemetry-addon` und `paper-worldpush`.

**Files:**
- Modify: `gradle.properties` (Zeile `version = 999.0.0` entfernen)
- Modify: `build.gradle.kts` (Versionsmarker und Weitergabe an Subprojekte)
- Modify: `telemetry-addon/build.gradle.kts` (eigener Marker)
- Modify: `paper-worldpush/build.gradle.kts` (eigener Marker)
- Create: `release-please-config.json`
- Create: `.release-please-manifest.json`
- Create: `CHANGELOG.md`
- Create: `.github/workflows/release-please.yml`

**Interfaces:**
- Produces: Die Workflow-Outputs `.--release_created`, `.--version`, `telemetry-addon--release_created`, `paper-worldpush--release_created`. Task 9 und Task 10 hängen sich daran.

- [ ] **Schritt 1: Aktuellen Zustand festhalten**

```bash
grep -n version gradle.properties
git rev-parse HEAD
```

Der ausgegebene SHA ist der `bootstrap-sha` für Schritt 4. Notieren.

- [ ] **Schritt 2: Version aus `gradle.properties` entfernen und in `build.gradle.kts` verlegen**

`gradle.properties`: die Zeile `version = 999.0.0` ersatzlos löschen.

`build.gradle.kts` — der `subprojects`-Block erbt die Version bisher implizit über `gradle.properties`; das muss jetzt explizit geschehen:

```kotlin
plugins {
    alias(libs.plugins.spotless) apply false
}

version = "0.1.0" // x-release-please-version

subprojects {
    apply(plugin = "java")
    apply(plugin = "com.diffplug.spotless")

    // telemetry-addon and paper-worldpush carry their own release track (design spec §4)
    // and set their own version; every other module ships as part of the project as a whole.
    if (name != "telemetry-addon" && name != "paper-worldpush") {
        version = rootProject.version
    }
    // ... bestehender Inhalt unverändert ...
}
```

- [ ] **Schritt 3: Eigene Marker in den beiden Modulen mit eigener Release-Spur**

In `telemetry-addon/build.gradle.kts` als erste Zeile nach dem `plugins`-Block:

```kotlin
version = "0.1.0" // x-release-please-version
```

Dasselbe in `paper-worldpush/build.gradle.kts`.

- [ ] **Schritt 4: `release-please-config.json` anlegen**

`<BOOTSTRAP_SHA>` durch den SHA aus Schritt 1 ersetzen.

```json
{
  "$schema": "https://raw.githubusercontent.com/googleapis/release-please/main/schemas/config.json",
  "release-type": "simple",
  "include-component-in-tag": true,
  "include-v-in-tag": true,
  "separate-pull-requests": true,
  "bootstrap-sha": "<BOOTSTRAP_SHA>",
  "pull-request-header": "",
  "packages": {
    ".": {
      "package-name": "apus",
      "changelog-path": "CHANGELOG.md",
      "extra-files": [
        { "type": "generic", "path": "build.gradle.kts" }
      ]
    },
    "telemetry-addon": {
      "package-name": "telemetry-addon",
      "changelog-path": "CHANGELOG.md",
      "extra-files": [
        { "type": "generic", "path": "telemetry-addon/build.gradle.kts" }
      ]
    },
    "paper-worldpush": {
      "package-name": "paper-worldpush",
      "changelog-path": "CHANGELOG.md",
      "extra-files": [
        { "type": "generic", "path": "paper-worldpush/build.gradle.kts" }
      ]
    }
  }
}
```

`include-component-in-tag: true` ist bei mehreren Paketen zwingend, sonst kollidieren zwei Module auf demselben Git-Tag. `separate-pull-requests: true`, weil die drei Spuren unabhängig releasen sollen.

- [ ] **Schritt 5: Manifest und Changelog anlegen**

`.release-please-manifest.json`:

```json
{
  ".": "0.1.0",
  "telemetry-addon": "0.1.0",
  "paper-worldpush": "0.1.0"
}
```

`CHANGELOG.md`: leere Datei anlegen (`: > CHANGELOG.md`). Release Please füllt sie; niemals von Hand editieren.

- [ ] **Schritt 6: Workflow anlegen**

`.github/workflows/release-please.yml`:

```yaml
name: release-please

on:
  push:
    branches: [main]

permissions:
  contents: write
  pull-requests: write

jobs:
  release-please:
    runs-on: ubuntu-latest
    outputs:
      root-released: ${{ steps.release.outputs['.--release_created'] }}
      root-version: ${{ steps.release.outputs['.--version'] }}
      telemetry-released: ${{ steps.release.outputs['telemetry-addon--release_created'] }}
      paper-released: ${{ steps.release.outputs['paper-worldpush--release_created'] }}
    steps:
      - id: release
        uses: googleapis/release-please-action@v5
        with:
          config-file: release-please-config.json
          manifest-file: .release-please-manifest.json
```

Die Publish-Jobs kommen in Task 9 (Images) und Task 10 (Maven) hinzu. Kein zusätzlicher `on: push: tags:`-Workflow — Release Please taggt mit dem Standard-`GITHUB_TOKEN` und löst damit keine Tag-Push-Workflows aus; zwei Publish-Pfade wären entweder tot oder würden sich auf demselben Tag ins Gehege kommen.

- [ ] **Schritt 7: Verifizieren, dass Gradle die Version weiterhin auflöst**

Run: `./gradlew :ingest:properties --property version && ./gradlew :telemetry-addon:properties --property version`
Expected: beide geben `version: 0.1.0` aus — die erste über `rootProject.version`, die zweite über den eigenen Marker.

- [ ] **Schritt 8: Verifizieren, dass der Build unverändert durchläuft**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL. Der Jar-Dateiname von `ingest` ist versionsunabhängig festgelegt (`apus-ingest.jar`), die Umstellung darf daran nichts ändern — gegenprüfen mit `ls ingest/build/libs/`.

- [ ] **Schritt 9: Commit**

Der Commit-Typ muss `chore:` sein, damit die Einführung nicht selbst einen Versions-Bump auslöst.

```bash
git add gradle.properties build.gradle.kts telemetry-addon/build.gradle.kts \
        paper-worldpush/build.gradle.kts release-please-config.json \
        .release-please-manifest.json CHANGELOG.md .github/workflows/release-please.yml
git commit -m "chore: manage versions and changelogs with release-please"
```

---

### Task 4: PR-Build

**Files:**
- Create: `.github/workflows/build-pr.yml`

- [ ] **Schritt 1: Workflow anlegen**

```yaml
name: build-pr

on:
  pull_request:
    branches: [main]

jobs:
  gradle:
    uses: OneLiteFeatherNET/workflows/.github/workflows/gradle-build-pr.yml@v2.4.0
    with:
      java-version: "25"
      java-distribution: "temurin"
      paths-filters: |
        code:
          - '**/*.java'
          - '**/*.kts'
          - '**/*.properties'
          - 'gradle/**'
          - 'gradlew'
          - '.spotless/**'
    secrets: inherit
```

Der Path-Filter-Schlüssel muss `code` heißen — so erwartet ihn der zentrale Workflow. Der `ui`-Teil hängt nicht an Gradle und bekommt einen eigenen Job in Schritt 2.

- [ ] **Schritt 2: UI-Job im selben Workflow ergänzen**

Nuxt/pnpm deckt der zentrale Katalog nicht ab; das ist ein legitimer repo-eigener Job:

```yaml
  ui:
    runs-on: ubuntu-latest
    defaults:
      run:
        working-directory: ui
    steps:
      - uses: actions/checkout@v5
      - uses: pnpm/action-setup@v4
      - uses: actions/setup-node@v5
        with:
          node-version: "22"
          cache: pnpm
          cache-dependency-path: ui/pnpm-lock.yaml
      - run: pnpm install --frozen-lockfile
      - run: pnpm lint
      - run: pnpm typecheck
      - run: pnpm test
```

- [ ] **Schritt 3: Workflow-Syntax prüfen**

Run: `python3 -c "import yaml;yaml.safe_load(open('.github/workflows/build-pr.yml'));print('ok')"`
Expected: `ok`

- [ ] **Schritt 4: Lokal gegenprüfen, dass die Kommandos tatsächlich grün sind**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL

Run: `cd ui && pnpm install --frozen-lockfile && pnpm lint && pnpm typecheck && pnpm test`
Expected: alle vier ohne Fehler. Schlägt hier etwas fehl, ist das ein echter Befund — dann diesen Task stoppen und den Fehler zuerst beheben, statt eine rote CI einzuchecken.

- [ ] **Schritt 5: Commit**

```bash
git add .github/workflows/build-pr.yml
git commit -m "ci: build and test Gradle modules and the UI on pull requests"
```

---

### Task 5: Markdown-Lint und Fork-PR-Schutz

Das Repository trägt ungewöhnlich viel Dokumentation (Design-Spec, Pläne, Spike-Berichte, sechs READMEs) mit vielen Querverweisen. Kaputte Links fallen sonst niemandem auf.

**Files:**
- Create: `.github/workflows/markdown-lint.yml`
- Create: `.github/workflows/close-invalid-prs.yml`
- Create: `.markdownlint-cli2.jsonc`

- [ ] **Schritt 1: Markdownlint-Konfiguration anlegen**

```jsonc
{
  "config": {
    // The design spec and the plans use long prose lines; wrapping them would make
    // diffs unreadable.
    "MD013": false,
    // Release Please writes the changelog; its heading structure is not ours to police.
    "MD024": { "siblings_only": true }
  },
  "ignores": [
    "**/node_modules/**",
    "**/build/**",
    "CHANGELOG.md"
  ]
}
```

- [ ] **Schritt 2: Workflows anlegen**

`.github/workflows/markdown-lint.yml`:

```yaml
name: markdown-lint

on:
  pull_request:
    branches: [main]
    paths:
      - '**/*.md'

jobs:
  lint:
    uses: OneLiteFeatherNET/workflows/.github/workflows/markdown-lint.yml@v2.4.0
    secrets: inherit
```

`.github/workflows/close-invalid-prs.yml`:

```yaml
name: close-invalid-prs

on:
  pull_request_target:
    types: [opened]

jobs:
  close:
    uses: OneLiteFeatherNET/workflows/.github/workflows/close-invalid-prs.yml@v2.4.0
    secrets: inherit
```

- [ ] **Schritt 3: Lint lokal ausführen**

Run: `npx markdownlint-cli2 "**/*.md" "#ui/node_modules" "#**/build"`
Expected: keine Fehler. Treten welche auf, im selben Task beheben — entweder die Datei korrigieren oder, wenn die Regel für dieses Repository unsinnig ist, sie in `.markdownlint-cli2.jsonc` mit Begründung abschalten.

- [ ] **Schritt 4: Commit**

```bash
git add .github/workflows/markdown-lint.yml .github/workflows/close-invalid-prs.yml .markdownlint-cli2.jsonc
git commit -m "ci: lint markdown and close pull requests from fork default branches"
```

---

### Task 6: Container-Image für den Operator

**Files:**
- Create: `operator/Dockerfile`
- Modify: `operator/build.gradle.kts` (Shadow-Plugin und fester Jar-Name)
- Modify: `settings.gradle.kts` — nur falls `shadow` im Katalog fehlt; er ist bereits als `version("shadow", "9.3.2")` vorhanden, dann entfällt die Änderung

**Interfaces:**
- Consumes: `application { mainClass.set("net.onelitefeather.apus.operator.ApusOperator") }`, bereits vorhanden in `operator/build.gradle.kts:124`.
- Produces: `operator/build/libs/apus-operator.jar`, das der Dockerfile per festem Namen kopiert.

- [ ] **Schritt 1: Shadow-Jar konfigurieren**

In `operator/build.gradle.kts` das Plugin ergänzen (im `plugins`-Block, analog zu `ingest`):

```kotlin
alias(libs.plugins.shadow)
```

und den Task konfigurieren:

```kotlin
tasks {
    shadowJar {
        archiveClassifier.set("")
        archiveBaseName.set("apus-operator")
        // Fixed name instead of the default "apus-operator-<version>.jar": operator/Dockerfile
        // COPYs the file by name (no glob), the same convention ingest and telemetry-addon use.
        archiveFileName.set("apus-operator.jar")
    }
    build {
        dependsOn(shadowJar)
    }
}
```

- [ ] **Schritt 2: Jar bauen und prüfen, dass er startfähig ist**

Run: `./gradlew :operator:shadowJar && ls -la operator/build/libs/apus-operator.jar`
Expected: Datei existiert.

Run: `unzip -p operator/build/libs/apus-operator.jar META-INF/MANIFEST.MF | grep Main-Class`
Expected: `Main-Class: net.onelitefeather.apus.operator.ApusOperator`

- [ ] **Schritt 3: Dockerfile schreiben**

```dockerfile
# syntax=docker/dockerfile:1

FROM eclipse-temurin:25-jre-jammy

# Non-root, same convention as runner/Dockerfile and ingest/Dockerfile. The operator writes
# nothing to the filesystem at all -- it only talks to the Kubernetes API -- so it gets no
# writable directory beyond its home.
RUN useradd --uid 10001 --create-home --home-dir /home/apus apus

# Built by: ./gradlew :operator:shadowJar
COPY --chown=apus:apus operator/build/libs/apus-operator.jar /opt/apus/operator.jar

USER apus
WORKDIR /home/apus

# The operator serves no traffic of its own; 8080 is only the metrics endpoint added in
# phase 8. Declared here so the port contract lives with the image.
EXPOSE 8080

ENTRYPOINT ["java", "-jar", "/opt/apus/operator.jar"]
```

- [ ] **Schritt 4: Image bauen und starten**

Run: `docker build -f operator/Dockerfile -t apus-operator:test .`
Expected: erfolgreicher Build.

Run: `docker run --rm apus-operator:test 2>&1 | head -20`
Expected: Der Operator startet und scheitert erwartbar an der fehlenden Kubernetes-Verbindung — nicht an `ClassNotFoundException` oder `no main manifest attribute`. Genau das unterscheidet ein funktionierendes Fat-Jar von einem kaputten.

- [ ] **Schritt 5: Commit**

```bash
git add operator/Dockerfile operator/build.gradle.kts
git commit -m "feat: package the operator as a container image"
```

---

### Task 7: Container-Image für die API

**Files:**
- Create: `api/Dockerfile`
- Modify: `api/build.gradle.kts` (Shadow-Plugin und fester Jar-Name)

**Interfaces:**
- Consumes: `application { mainClass.set("net.onelitefeather.apus.api.Application") }`, vorhanden in `api/build.gradle.kts:113`.
- Produces: `api/build/libs/apus-api.jar`.

- [ ] **Schritt 1: Shadow-Jar konfigurieren**

Im `plugins`-Block von `api/build.gradle.kts`:

```kotlin
alias(libs.plugins.shadow)
```

```kotlin
tasks {
    shadowJar {
        archiveClassifier.set("")
        archiveBaseName.set("apus-api")
        archiveFileName.set("apus-api.jar")
        // Micronaut ships service files (annotation-driven bean definitions, serde config)
        // in META-INF/services; without merging them the shadowed jar starts but resolves
        // no beans, which surfaces as a confusing "no route matched" at runtime rather
        // than a build failure.
        mergeServiceFiles()
    }
    build {
        dependsOn(shadowJar)
    }
}
```

- [ ] **Schritt 2: Jar bauen und Bean-Auflösung verifizieren**

Run: `./gradlew :api:shadowJar`
Expected: BUILD SUCCESSFUL

Run: `unzip -l api/build/libs/apus-api.jar | grep -c 'META-INF/services'`
Expected: eine Zahl größer 0 — schlägt das fehl, hat `mergeServiceFiles()` nicht gegriffen und die API würde zur Laufzeit keine Beans finden.

- [ ] **Schritt 3: Dockerfile schreiben**

```dockerfile
# syntax=docker/dockerfile:1

FROM eclipse-temurin:25-jre-jammy

RUN useradd --uid 10001 --create-home --home-dir /home/apus apus

# Built by: ./gradlew :api:shadowJar
COPY --chown=apus:apus api/build/libs/apus-api.jar /opt/apus/api.jar

USER apus
WORKDIR /home/apus

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "/opt/apus/api.jar"]
```

- [ ] **Schritt 4: Image bauen und Start prüfen**

Run: `docker build -f api/Dockerfile -t apus-api:test .`
Expected: erfolgreicher Build.

Run: `docker run --rm -p 8080:8080 -d --name apus-api-test apus-api:test && sleep 15 && docker logs apus-api-test | tail -20`
Expected: Micronaut-Startzeile (`Startup completed in ...ms`). Die JWT-Validierung braucht einen Issuer und wird beim ersten Request scheitern — der Start selbst muss aber sauber durchlaufen.

Aufräumen: `docker rm -f apus-api-test`

- [ ] **Schritt 5: Commit**

```bash
git add api/Dockerfile api/build.gradle.kts
git commit -m "feat: package the API as a container image"
```

---

### Task 8: Container-Image für die UI

Die UI läuft laut Design-Spec §11.2 als SPA (`ssr: false`). Ein statisches Build-Ergebnis, ausgeliefert von nginx, ist damit die passende Form — kein Node-Prozess im Cluster.

**Files:**
- Create: `ui/Dockerfile`
- Create: `ui/nginx.conf`

- [ ] **Schritt 1: Verifizieren, dass der SPA-Modus tatsächlich konfiguriert ist**

Run: `grep -n 'ssr' ui/nuxt.config.ts`
Expected: `ssr: false`. Steht dort etwas anderes, ist dieser Task falsch zugeschnitten — dann statt nginx ein Node-Image mit `node .output/server/index.mjs` bauen und Schritt 2 überspringen.

- [ ] **Schritt 2: nginx-Konfiguration schreiben**

```nginx
server {
    listen 8080;
    server_name _;
    root /usr/share/nginx/html;

    # Single-page app: every unknown path must fall back to index.html, otherwise a
    # browser reload on /tenants/foo returns 404 instead of the app.
    location / {
        try_files $uri $uri/ /index.html;
    }

    # Hashed build assets are immutable; index.html must never be cached, or a deploy
    # leaves clients on the previous bundle.
    location /_nuxt/ {
        expires 1y;
        add_header Cache-Control "public, immutable";
    }

    location = /index.html {
        add_header Cache-Control "no-store";
    }
}
```

- [ ] **Schritt 3: Dockerfile schreiben**

```dockerfile
# syntax=docker/dockerfile:1

########################################
# Stage 1: build the SPA
########################################
FROM node:22-bookworm-slim AS build

RUN corepack enable

WORKDIR /src
COPY ui/package.json ui/pnpm-lock.yaml ui/pnpm-workspace.yaml ./
RUN pnpm install --frozen-lockfile

COPY ui/ ./
RUN pnpm generate

########################################
# Stage 2: serve it
########################################
FROM nginxinc/nginx-unprivileged:1.29-alpine

# The unprivileged nginx image already runs as uid 101; it needs no writable root and
# listens on 8080 rather than 80, which is why it is used instead of the stock image.
COPY ui/nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /src/.output/public /usr/share/nginx/html

EXPOSE 8080
```

- [ ] **Schritt 4: Image bauen und ausliefern lassen**

Run: `docker build -f ui/Dockerfile -t apus-ui:test .`
Expected: erfolgreicher Build.

Run: `docker run --rm -d -p 8081:8080 --name apus-ui-test apus-ui:test && sleep 3 && curl -sf -o /dev/null -w '%{http_code}\n' http://localhost:8081/`
Expected: `200`

Run: `curl -sf -o /dev/null -w '%{http_code}\n' http://localhost:8081/tenants/does-not-exist`
Expected: `200` — der SPA-Fallback greift. Kommt hier `404`, ist `try_files` falsch verdrahtet.

Aufräumen: `docker rm -f apus-ui-test`

- [ ] **Schritt 5: Commit**

```bash
git add ui/Dockerfile ui/nginx.conf
git commit -m "feat: package the dashboard as a static nginx container image"
```

---

### Task 9: Images veröffentlichen

Sechs Images: `runner`, `ingest`, `hosting`, `operator`, `api`, `ui`. Die ersten drei haben ihre Dockerfiles bereits, die letzten drei kommen aus Task 6–8.

**Files:**
- Modify: `.github/workflows/release-please.yml` (Publish-Jobs anhängen)

**Interfaces:**
- Consumes: `needs.release-please.outputs.root-released` und `root-version` aus Task 3.

- [ ] **Schritt 1: Gradle-Job für die Jar-Artefakte ergänzen**

Drei der sechs Images kopieren Gradle-Ausgaben (`telemetry-addon` für `runner`, `ingest`, `operator`, `api`). Der Build-Kontext muss diese Dateien also enthalten. In `.github/workflows/release-please.yml` nach dem `release-please`-Job:

```yaml
  build-context:
    needs: release-please
    if: needs.release-please.outputs.root-released == 'true'
    uses: OneLiteFeatherNET/workflows/.github/workflows/gradle-docker-context.yml@v2.4.0
    with:
      java-version: "25"
      version: ${{ needs.release-please.outputs.root-version }}
      gradle-command: "./gradlew :telemetry-addon:shadowJar :ingest:shadowJar :operator:shadowJar :api:shadowJar"
      context-path: "."
      artifact-name: "docker-context"
    secrets: inherit
```

- [ ] **Schritt 2: Die sechs Publish-Jobs ergänzen**

```yaml
  publish-runner:
    needs: [release-please, build-context]
    permissions:
      contents: read
      id-token: write
    uses: OneLiteFeatherNET/workflows/.github/workflows/docker-publish.yml@v2.4.0
    with:
      image-name: "apus/runner"
      version: ${{ needs.release-please.outputs.root-version }}
      context: "."
      dockerfile: "runner/Dockerfile"
      artifact-name: "docker-context"
    secrets: inherit

  publish-ingest:
    needs: [release-please, build-context]
    permissions:
      contents: read
      id-token: write
    uses: OneLiteFeatherNET/workflows/.github/workflows/docker-publish.yml@v2.4.0
    with:
      image-name: "apus/ingest"
      version: ${{ needs.release-please.outputs.root-version }}
      context: "."
      dockerfile: "ingest/Dockerfile"
      artifact-name: "docker-context"
    secrets: inherit

  publish-hosting:
    needs: [release-please, build-context]
    permissions:
      contents: read
      id-token: write
    uses: OneLiteFeatherNET/workflows/.github/workflows/docker-publish.yml@v2.4.0
    with:
      image-name: "apus/hosting"
      version: ${{ needs.release-please.outputs.root-version }}
      context: "."
      dockerfile: "hosting/Dockerfile"
      artifact-name: "docker-context"
    secrets: inherit

  publish-operator:
    needs: [release-please, build-context]
    permissions:
      contents: read
      id-token: write
    uses: OneLiteFeatherNET/workflows/.github/workflows/docker-publish.yml@v2.4.0
    with:
      image-name: "apus/operator"
      version: ${{ needs.release-please.outputs.root-version }}
      context: "."
      dockerfile: "operator/Dockerfile"
      artifact-name: "docker-context"
    secrets: inherit

  publish-api:
    needs: [release-please, build-context]
    permissions:
      contents: read
      id-token: write
    uses: OneLiteFeatherNET/workflows/.github/workflows/docker-publish.yml@v2.4.0
    with:
      image-name: "apus/api"
      version: ${{ needs.release-please.outputs.root-version }}
      context: "."
      dockerfile: "api/Dockerfile"
      artifact-name: "docker-context"
    secrets: inherit

  publish-ui:
    needs: release-please
    if: needs.release-please.outputs.root-released == 'true'
    permissions:
      contents: read
      id-token: write
    uses: OneLiteFeatherNET/workflows/.github/workflows/docker-publish.yml@v2.4.0
    with:
      image-name: "apus/ui"
      version: ${{ needs.release-please.outputs.root-version }}
      context: "."
      dockerfile: "ui/Dockerfile"
    secrets: inherit
```

`publish-ui` hängt bewusst **nicht** am `build-context`: Die UI baut sich im Dockerfile selbst aus den Quellen und braucht keine Gradle-Ausgabe. Die anderen fünf teilen sich einen Kontext-Artefakt-Namen — `artifact-name` muss überall exakt `docker-context` lauten, sonst findet der Publish-Job den Upload nicht.

- [ ] **Schritt 3: Prüfen, dass `dockerfile` ein unterstützter Eingabewert ist**

Run: `gh api repos/OneLiteFeatherNET/workflows/contents/.github/workflows/docker-publish.yml -q '.content' | base64 -d | grep -A3 -E '^\s+(dockerfile|context|artifact-name):'`
Expected: Die drei Eingaben erscheinen im `workflow_call`-`inputs`-Block. Fehlt `dockerfile`, unterstützt der zentrale Workflow nur den Standardnamen `Dockerfile` im Kontextverzeichnis — dann ist das eine Erweiterung am zentralen Workflow (Vorgehen laut `release-engineering:workflows`, Abschnitt „Introducing a new mechanic"), und dieser Task blockiert, bis die dort ergänzt und getaggt ist.

- [ ] **Schritt 4: YAML validieren**

Run: `python3 -c "import yaml;yaml.safe_load(open('.github/workflows/release-please.yml'));print('ok')"`
Expected: `ok`

- [ ] **Schritt 5: Commit**

```bash
git add .github/workflows/release-please.yml
git commit -m "ci: publish all six container images on release"
```

---

### Task 10: Maven-Veröffentlichung für die beiden Bibliotheken

`telemetry-addon` konsumieren BlueMap-Nutzer, `paper-worldpush` Server-Betreiber. Beide sind ohne Publishing nicht erreichbar.

**Files:**
- Modify: `telemetry-addon/build.gradle.kts`
- Modify: `paper-worldpush/build.gradle.kts`
- Modify: `.github/workflows/release-please.yml`

- [ ] **Schritt 1: Publishing in beiden Modulen konfigurieren**

In beiden `build.gradle.kts` (Modulname jeweils anpassen):

```kotlin
plugins {
    `maven-publish`
    // ... vorhandene Plugins ...
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            groupId = "net.onelitefeather.apus"
            artifactId = "telemetry-addon"   // bzw. "paper-worldpush"
            // The shadow jar is the artifact consumers need -- the thin jar would leave
            // them to resolve the relocated dependencies themselves.
            artifact(tasks.named("shadowJar"))
        }
    }
    repositories {
        maven {
            name = "OneLiteFeather"
            url = uri("https://repo.onelitefeather.dev/onelitefeather")
            credentials {
                username = System.getenv("ONELITEFEATHER_USERNAME")
                password = System.getenv("ONELITEFEATHER_PASSWORD")
            }
        }
    }
}
```

- [ ] **Schritt 2: Repository-URL und Credential-Namen gegen ein bestehendes OLF-Projekt gegenprüfen**

Run: `gh api repos/OneLiteFeatherNET/Aves/contents/build.gradle.kts -q '.content' | base64 -d | grep -A12 'repositories'`
Expected: URL und Umgebungsvariablennamen stimmen mit Schritt 1 überein. Weichen sie ab, gilt der Wert aus dem bestehenden Projekt — der zentrale `gradle-publish.yml`-Workflow reicht genau diese Secrets herein.

- [ ] **Schritt 3: Lokal in ein Verzeichnis publizieren**

Run: `./gradlew :telemetry-addon:publishToMavenLocal :paper-worldpush:publishToMavenLocal`
Expected: BUILD SUCCESSFUL

Run: `find ~/.m2/repository/net/onelitefeather/apus -name '*.jar' | sort`
Expected: je ein Jar pro Modul.

- [ ] **Schritt 4: Publish-Jobs anhängen**

In `.github/workflows/release-please.yml`:

```yaml
  publish-telemetry-addon:
    needs: release-please
    if: needs.release-please.outputs.telemetry-released == 'true'
    uses: OneLiteFeatherNET/workflows/.github/workflows/gradle-publish.yml@v2.4.0
    with:
      java-version: "25"
      java-distribution: "temurin"
      build-task: ":telemetry-addon:build"
      publish-task: ":telemetry-addon:publish"
    secrets: inherit

  publish-paper-worldpush:
    needs: release-please
    if: needs.release-please.outputs.paper-released == 'true'
    uses: OneLiteFeatherNET/workflows/.github/workflows/gradle-publish.yml@v2.4.0
    with:
      java-version: "25"
      java-distribution: "temurin"
      build-task: ":paper-worldpush:build"
      publish-task: ":paper-worldpush:publish"
    secrets: inherit
```

Die projektqualifizierte Task-Syntax sorgt dafür, dass eine Veröffentlichung des einen Moduls das andere nicht mitzieht.

- [ ] **Schritt 5: Commit**

```bash
git add telemetry-addon/build.gradle.kts paper-worldpush/build.gradle.kts .github/workflows/release-please.yml
git commit -m "feat: publish telemetry-addon and paper-worldpush to the OneLiteFeather Maven repository"
```

---

### Task 11: Design-Spec nachziehen

Die Spec führt in §13.2 „Phase 1 hat im Repository keinerlei CI-Konfiguration angelegt" als offenen Punkt. Nach diesem Plan stimmt das nicht mehr.

**Files:**
- Modify: `docs/superpowers/specs/2026-08-08-apus-design.md`

- [ ] **Schritt 1: §13.2, Zeile zum `telemetry-addon`, umschreiben**

Der Satz „**Offen:** Eine CI-Matrix über unterstützte BlueMap-Versionen als Frühwarnsystem existiert nicht — Phase 1 hat im Repository keinerlei CI-Konfiguration angelegt." wird ersetzt durch:

```markdown
**Teilweise offen:** CI existiert seit Phase 7 (`.github/workflows/build-pr.yml`), eine
Matrix über mehrere BlueMap-Versionen als Frühwarnsystem aber noch nicht — der
Contract-Test läuft gegen die eine im Katalog gepinnte Version. Bis eine Matrix existiert,
muss er vor jedem BlueMap-Upgrade weiterhin gezielt laufen.
```

- [ ] **Schritt 2: §0 um einen Absatz zum Auslieferungsstand ergänzen**

Nach dem Absatz zu Region-Sharding einfügen:

```markdown
**Auslieferung steht seit Phase 7.** Alle sechs Komponenten liegen als Container-Image vor
(`runner`, `ingest`, `hosting`, `operator`, `api`, `ui`), `telemetry-addon` und
`paper-worldpush` werden nach Maven veröffentlicht. Versionen und Changelogs entstehen
über Release Please aus Conventional Commits; `telemetry-addon` und `paper-worldpush`
tragen dabei eigene Release-Spuren, wie in §4 vorgesehen. Was weiterhin fehlt, sind die
Cluster-Manifeste und die Observability-Verdrahtung — siehe den Plan zu Phase 8.
```

- [ ] **Schritt 3: Markdown-Lint über die geänderte Datei**

Run: `npx markdownlint-cli2 docs/superpowers/specs/2026-08-08-apus-design.md`
Expected: keine Fehler.

- [ ] **Schritt 4: Commit**

```bash
git add docs/superpowers/specs/2026-08-08-apus-design.md
git commit -m "docs: record the phase 7 delivery state in the design spec"
```

---

## Was dieser Plan bewusst nicht abdeckt

- **Cluster-Manifeste, CRD-YAMLs, Metriken, Dashboards und der k3s-E2E-Lauf** — eigener Plan (Phase 8). Sie setzen die hier gebauten Images voraus, aber nicht umgekehrt.
- **Identity-Broker-Auswahl, RBAC-Härtung, Quota-Exit-Code, das Paper-Save-Fenster und die `emptyDir`-Grenze** — eigener Plan (Phase 9). Das sind inhaltliche Härtungen am bestehenden Code, keine Auslieferungsfragen.
- **Eine CI-Matrix über mehrere BlueMap-Versionen.** Sinnvoll, aber sie setzt voraus, dass der Contract-Test parametrierbar über die BlueMap-Version ist — das ist er heute nicht (die Version steht fest im Katalog und im `runner/Dockerfile`). Gehört in denselben Schritt wie eine Überarbeitung des Contract-Tests, nicht in die CI-Einführung.
