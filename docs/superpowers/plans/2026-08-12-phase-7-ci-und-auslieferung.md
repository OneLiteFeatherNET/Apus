# Apus Phase 7 — CI and Delivery: Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Every commit is built and tested automatically, every component can be delivered as a container image or Maven artifact, and versions come from Conventional Commits instead of by hand.

**Architecture:** Apus consumes the central, reusable workflows from `OneLiteFeatherNET/workflows` instead of writing its own CI. Release Please manages versions and changelogs; `telemetry-addon` and `paper-worldpush` get their own release tracks because they depend on external versions (the BlueMap and Paper APIs respectively) — as provided for in the design spec §4. The three components not yet packaged (`operator`, `api`, `ui`) get Dockerfiles in the style of the existing ones (`runner`, `ingest`, `hosting`): multi-stage, non-root uid 10001, build context is the repository root.

**Tech Stack:** GitHub Actions (`OneLiteFeatherNET/workflows@v2.4.0`), Release Please (`googleapis/release-please-action@v5`), Renovate (the central OLF preset), Docker (Harbor registry), Gradle 9 with an inline version catalog, Java 25 (Temurin).

## Global Constraints

- **Java toolchain 25 (Temurin)** in every workflow — that is the default of the central workflows; do not override it.
- **Reusable workflows are pinned to the full SemVer tag** — `@v2.4.0`, never `@main` and never `@v2`.
- **No `clean` in CI's Gradle tasks** — it invalidates the `setup-gradle` cache.
- **The version marker lives in `build.gradle.kts`, not in `gradle.properties`.** Today `gradle.properties` has `version = 999.0.0`; that line is removed outright, with nothing to replace it.
- **Dockerfiles build with the repository root as context** and copy using a module-qualified path (`COPY operator/...`), exactly the way `runner/Dockerfile` and `ingest/Dockerfile` do.
- **Non-root in every image:** user `apus`, uid 10001, working directory under `/work` or `/app` respectively.
- **Jar file names are fixed** (no glob in `COPY`), the same convention as `ingest`: `archiveFileName.set("apus-<module>.jar")`.
- **AGPL license header on every new Java file** — Spotless enforces it via `.spotless/Copyright.java`.
- **Integration tests do not run in the PR build.** `operator`, `runner` and `ingest` exclude `**/*IntegrationTest.class` from `test` and carry a separate `integrationTest` task; that stays as it is, because these tests need Docker and, in some cases, k3s.

---

## Prerequisite (one-time, outside the repository)

The Renovate preset requires a GitHub team as reviewer. A team `apus-maintainers` does **not** exist in the organization (checked on 2026-08-12 via `gh api orgs/OneLiteFeatherNET/teams`). Before Task 2, either create the team:

```bash
gh api -X POST orgs/OneLiteFeatherNET/teams -f name='apus-maintainers' -f privacy='closed'
gh api -X PUT orgs/OneLiteFeatherNET/teams/apus-maintainers/repos/OneLiteFeatherNET/Apus -f permission='push'
```

or use an existing team instead in Task 2 — `infrastructure-core-team` is the obvious candidate, since Apus is infrastructure. This decision is the only one in the whole plan that cannot be derived from the repository.

---

### Task 1: Root README

The repository has no entry point. Module READMEs exist for `runner`, `hosting`, `ingest`, `ui` and `testdata`, but anyone who opens the repository finds no orientation.

**Files:**

- Create: `README.md`

- [ ] **Step 1: Write the README**

Content (in full, do not shorten):

```markdown
# Apus

Apus renders Minecraft worlds with [BlueMap](https://bluemap.bluecolored.de/) on Kubernetes
and hosts the results. World data comes from several, very different sources; an ETL layer
normalizes it, an operator runs render and hosting jobs, and a UI shows progress and allows
operation without YAML.

The full design is in
[`docs/superpowers/specs/2026-08-08-apus-design.md`](docs/superpowers/specs/2026-08-08-apus-design.md).

## Modules

| Module | Purpose | Delivery |
|---|---|---|
| `telemetry-addon` | BlueMap addon, exposes render progress as JSON and Prometheus metrics | Maven |
| `ingest` | ETL: connectors (s3, pterodactyl, push, upload), layout detection, bundle writer | Container image |
| `runner` | BlueMap CLI plus both addons, renders a world from S3 to S3 | Container image |
| `hosting` | Long-lived BlueMap web server, reads rendered maps from S3 | Container image |
| `operator` | Kubernetes operator, six CRDs, creates Jobs/Deployments/Ingresses/Buckets | Container image |
| `api` | Micronaut REST/SSE over the custom resources, enforcement point for auth | Container image |
| `ui` | Nuxt 4 dashboard for tenants and platform operators | Container image |
| `paper-worldpush` | Paper plugin, pushes worlds from the running server to Apus | Maven |

## Building

Prerequisites: JDK 25, Docker (for integration tests), pnpm (for `ui`).

    ./gradlew build          # all Java modules, without integration tests
    ./gradlew integrationTest # needs Docker
    ./gradlew :operator:generateCrds  # generates the six CRD YAMLs into operator/build/crds

    cd ui && pnpm install && pnpm test && pnpm lint

## Development

The core of the system is the **World Bundle** — an immutable, normalized
snapshot of a world in S3. On its left (ingest) nobody knows anything about BlueMap;
on its right (render, hosting) nobody knows anything about Pterodactyl or ZIP uploads. Anyone
connecting a new world source only has to implement `WorldSourceConnector` in `ingest`.

Commits follow [Conventional Commits](https://www.conventionalcommits.org/) — Release
Please derives the version and changelog from them.

## License

AGPL-3.0, see [LICENSE](LICENSE).
```

- [ ] **Step 2: Verify that all links resolve**

Run: `ls docs/superpowers/specs/2026-08-08-apus-design.md LICENSE`
Expected: Both paths exist.

- [ ] **Step 3: Commit**

```bash
git add README.md
git commit -m "docs: add a root README with module overview and build instructions"
```

---

### Task 2: Renovate

**Files:**

- Create: `renovate.json`

**Interfaces:**

- Produces: The file through which Renovate, from now on, also updates the workflow pins from Task 4/5/9.

- [ ] **Step 1: Create `renovate.json`**

```json
{
  "$schema": "https://docs.renovatebot.com/renovate-schema.json",
  "extends": [
    "github>OneLiteFeatherNET/renovate:default(OneLiteFeatherNET/apus-maintainers)",
    "github>OneLiteFeatherNET/renovate:paper"
  ]
}
```

The `:paper` flavour is needed because `paper-worldpush` builds against `io.papermc.paper:paper-api`, whose version scheme `X.Y.Z-<mc-version>` the standard SemVer evaluation misreads. Apus needs no `:minestom` flavour.

No `packageRules` of our own — patch automerge, reviewer, timezone, office-hours schedule, semantic commits, the `renovate` label and vulnerability alerts all already come with the preset.

- [ ] **Step 2: Verify the team slug**

Run: `gh api orgs/OneLiteFeatherNET/teams --paginate -q '.[].slug' | grep -x apus-maintainers`
Expected: output `apus-maintainers`. If this fails, the prerequisite above has not been met — either create the team or change the slug in `renovate.json` to `infrastructure-core-team`.

- [ ] **Step 3: Validate the JSON**

Run: `python3 -c "import json;json.load(open('renovate.json'));print('ok')"`
Expected: `ok`

- [ ] **Step 4: Commit**

```bash
git add renovate.json
git commit -m "ci: adopt the central OneLiteFeather Renovate preset"
```

---

### Task 3: Version marker and Release Please

`gradle.properties` today carries `version = 999.0.0` — a placeholder with no automation behind it. Release Please requires the marker in each module's `build.gradle.kts`. Apus gets three release tracks: the project as a whole (whose version the container images carry), `telemetry-addon` and `paper-worldpush`.

**Files:**

- Modify: `gradle.properties` (remove the line `version = 999.0.0`)
- Modify: `build.gradle.kts` (version marker and propagation to subprojects)
- Modify: `telemetry-addon/build.gradle.kts` (own marker)
- Modify: `paper-worldpush/build.gradle.kts` (own marker)
- Create: `release-please-config.json`
- Create: `.release-please-manifest.json`
- Create: `CHANGELOG.md`
- Create: `.github/workflows/release-please.yml`

**Interfaces:**

- Produces: The workflow outputs `release_created`, `version`, `telemetry-addon--release_created`, `paper-worldpush--release_created`. Task 9 and Task 10 hang off these. **Careful:** the root package is the exception to the `<path>--<key>` rule — `setPathOutput()` in the action sets the bare key when the package path is `.`. `.--release_created` is always empty.

- [ ] **Step 1: Record the current state**

```bash
grep -n version gradle.properties
git rev-parse HEAD
```

The SHA this prints is the `bootstrap-sha` for Step 4. Note it down.

- [ ] **Step 2: Remove the version from `gradle.properties` and move it into `build.gradle.kts`**

`gradle.properties`: delete the line `version = 999.0.0` outright.

`build.gradle.kts` — the `subprojects` block has so far inherited the version implicitly via `gradle.properties`; that now has to happen explicitly:

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
    // ... existing content unchanged ...
}
```

- [ ] **Step 3: Own markers in the two modules with their own release track**

In `telemetry-addon/build.gradle.kts` as the first line after the `plugins` block:

```kotlin
version = "0.1.0" // x-release-please-version
```

The same in `paper-worldpush/build.gradle.kts`.

- [ ] **Step 4: Create `release-please-config.json`**

Replace `<BOOTSTRAP_SHA>` with the SHA from Step 1.

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

`include-component-in-tag: true` is mandatory with multiple packages, otherwise two modules collide on the same Git tag. `separate-pull-requests: true`, because the three tracks are meant to release independently.

- [ ] **Step 5: Create the manifest and changelog**

`.release-please-manifest.json`:

```json
{
  ".": "0.1.0",
  "telemetry-addon": "0.1.0",
  "paper-worldpush": "0.1.0"
}
```

`CHANGELOG.md`: create an empty file (`: > CHANGELOG.md`). Release Please fills it in; never edit it by hand.

- [ ] **Step 6: Create the workflow**

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
      # Root package: bare keys, no prefix -- setPathOutput() special-cases path ".".
      root-released: ${{ steps.release.outputs.release_created }}
      root-version: ${{ steps.release.outputs.version }}
      telemetry-released: ${{ steps.release.outputs['telemetry-addon--release_created'] }}
      paper-released: ${{ steps.release.outputs['paper-worldpush--release_created'] }}
    steps:
      - id: release
        uses: googleapis/release-please-action@v5
        with:
          config-file: release-please-config.json
          manifest-file: .release-please-manifest.json
```

The publish jobs are added in Task 9 (images) and Task 10 (Maven). No additional `on: push: tags:` workflow — Release Please tags with the standard `GITHUB_TOKEN`, which does not trigger tag-push workflows; two publish paths would either be dead or collide on the same tag.

- [ ] **Step 7: Verify that Gradle still resolves the version**

Run: `./gradlew :ingest:properties --property version && ./gradlew :telemetry-addon:properties --property version`
Expected: both print `version: 0.1.0` — the first via `rootProject.version`, the second via its own marker.

- [ ] **Step 8: Verify the build still passes unchanged**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL. The jar file name of `ingest` is fixed independently of the version (`apus-ingest.jar`); the switch must not change that — cross-check with `ls ingest/build/libs/`.

- [ ] **Step 9: Commit**

The commit type must be `chore:`, so that introducing this does not itself trigger a version bump.

```bash
git add gradle.properties build.gradle.kts telemetry-addon/build.gradle.kts \
        paper-worldpush/build.gradle.kts release-please-config.json \
        .release-please-manifest.json CHANGELOG.md .github/workflows/release-please.yml
git commit -m "chore: manage versions and changelogs with release-please"
```

---

### Task 4: PR build

**Files:**

- Create: `.github/workflows/build-pr.yml`

- [ ] **Step 1: Create the workflow**

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

The path-filter key must be called `code` — that is what the central workflow expects. The `ui` part does not hang off Gradle and gets its own job in Step 2.

- [ ] **Step 2: Add a UI job to the same workflow**

The central catalog does not cover Nuxt/pnpm; this is a legitimate job that belongs to the repository itself:

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

- [ ] **Step 3: Check the workflow syntax**

Run: `python3 -c "import yaml;yaml.safe_load(open('.github/workflows/build-pr.yml'));print('ok')"`
Expected: `ok`

- [ ] **Step 4: Verify locally that the commands are actually green**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL

Run: `cd ui && pnpm install --frozen-lockfile && pnpm lint && pnpm typecheck && pnpm test`
Expected: all four succeed with no errors. If anything fails here, that is a real finding — stop this task and fix the failure first, rather than checking in a red CI.

- [ ] **Step 5: Commit**

```bash
git add .github/workflows/build-pr.yml
git commit -m "ci: build and test Gradle modules and the UI on pull requests"
```

---

### Task 5: Markdown lint and fork PR protection

The repository carries an unusually large amount of documentation (design spec, plans, spike reports, six READMEs) with many cross-references. Broken links otherwise go unnoticed.

**Files:**

- Create: `.github/workflows/markdown-lint.yml`
- Create: `.github/workflows/close-invalid-prs.yml`
- Create: `.markdownlint-cli2.jsonc`

- [ ] **Step 1: Create the markdownlint configuration**

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
    // Every changelog, not just the one at the repository root: Release Please writes
    // one per release track. A bare "CHANGELOG.md" only matches the root file.
    "**/CHANGELOG.md"
  ]
}
```

- [ ] **Step 2: Create the workflows**

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

- [ ] **Step 3: Run the lint locally**

Run: `npx markdownlint-cli2 "**/*.md" "#ui/node_modules" "#**/build"`
Expected: no errors. If any show up, fix them within the same task — either correct the file, or, if the rule makes no sense for this repository, disable it in `.markdownlint-cli2.jsonc` with a rationale.

- [ ] **Step 4: Commit**

```bash
git add .github/workflows/markdown-lint.yml .github/workflows/close-invalid-prs.yml .markdownlint-cli2.jsonc
git commit -m "ci: lint markdown and close pull requests from fork default branches"
```

---

### Task 6: Container image for the operator

**Files:**

- Create: `operator/Dockerfile`
- Modify: `operator/build.gradle.kts` (shadow plugin and fixed jar name)
- Modify: `settings.gradle.kts` — only if `shadow` is missing from the catalog; it is already present as `version("shadow", "9.3.2")`, in which case this change is dropped

**Interfaces:**

- Consumes: `application { mainClass.set("net.onelitefeather.apus.operator.ApusOperator") }`, already present in `operator/build.gradle.kts:124`.
- Produces: `operator/build/libs/apus-operator.jar`, which the Dockerfile copies by its fixed name.

- [ ] **Step 1: Configure the shadow jar**

In `operator/build.gradle.kts` add the plugin (in the `plugins` block, mirroring `ingest`):

```kotlin
alias(libs.plugins.shadow)
```

and configure the task:

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

- [ ] **Step 2: Build the jar and check that it can start**

Run: `./gradlew :operator:shadowJar && ls -la operator/build/libs/apus-operator.jar`
Expected: the file exists.

Run: `unzip -p operator/build/libs/apus-operator.jar META-INF/MANIFEST.MF | grep Main-Class`
Expected: `Main-Class: net.onelitefeather.apus.operator.ApusOperator`

- [ ] **Step 3: Write the Dockerfile**

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

- [ ] **Step 4: Build and start the image**

Run: `docker build -f operator/Dockerfile -t apus-operator:test .`
Expected: a successful build.

Run: `docker run --rm apus-operator:test 2>&1 | head -20`
Expected: the operator starts and fails, as expected, on the missing Kubernetes connection — not on a `ClassNotFoundException` or `no main manifest attribute`. That distinction is exactly what tells a working fat jar apart from a broken one.

- [ ] **Step 5: Commit**

```bash
git add operator/Dockerfile operator/build.gradle.kts
git commit -m "feat: package the operator as a container image"
```

---

### Task 7: Container image for the API

**Files:**

- Create: `api/Dockerfile`
- Modify: `api/build.gradle.kts` (shadow plugin and fixed jar name)

**Interfaces:**

- Consumes: `application { mainClass.set("net.onelitefeather.apus.api.Application") }`, present in `api/build.gradle.kts:113`.
- Produces: `api/build/libs/apus-api.jar`.

- [ ] **Step 1: Configure the shadow jar**

In the `plugins` block of `api/build.gradle.kts`:

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

- [ ] **Step 2: Build the jar and verify bean resolution**

Run: `./gradlew :api:shadowJar`
Expected: BUILD SUCCESSFUL

Run: `unzip -l api/build/libs/apus-api.jar | grep -c 'META-INF/services'`
Expected: a number greater than 0 — if this fails, `mergeServiceFiles()` did not take effect and the API would find no beans at runtime.

- [ ] **Step 3: Write the Dockerfile**

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

- [ ] **Step 4: Build the image and check the start**

Run: `docker build -f api/Dockerfile -t apus-api:test .`
Expected: a successful build.

Run: `docker run --rm -p 8080:8080 -d --name apus-api-test apus-api:test && sleep 15 && docker logs apus-api-test | tail -20`
Expected: a Micronaut startup line (`Startup completed in ...ms`). JWT validation needs an issuer and will fail on the first request — but the startup itself has to complete cleanly.

Clean up: `docker rm -f apus-api-test`

- [ ] **Step 5: Commit**

```bash
git add api/Dockerfile api/build.gradle.kts
git commit -m "feat: package the API as a container image"
```

---

### Task 8: Container image for the UI

Per design spec §11.2 the UI runs as an SPA (`ssr: false`). A static build output, served by nginx, is therefore the right shape — no Node process in the cluster.

**Files:**

- Create: `ui/Dockerfile`
- Create: `ui/nginx.conf`

- [ ] **Step 1: Verify that SPA mode is actually configured**

Run: `grep -n 'ssr' ui/nuxt.config.ts`
Expected: `ssr: false`. If it says something else, this task is scoped wrong — build a Node image with `node .output/server/index.mjs` instead of nginx, and skip Step 2.

- [ ] **Step 2: Write the nginx configuration**

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

- [ ] **Step 3: Write the Dockerfile**

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

- [ ] **Step 4: Build the image and let it serve**

Run: `docker build -f ui/Dockerfile -t apus-ui:test .`
Expected: a successful build.

Run: `docker run --rm -d -p 8081:8080 --name apus-ui-test apus-ui:test && sleep 3 && curl -sf -o /dev/null -w '%{http_code}\n' http://localhost:8081/`
Expected: `200`

Run: `curl -sf -o /dev/null -w '%{http_code}\n' http://localhost:8081/tenants/does-not-exist`
Expected: `200` — the SPA fallback kicks in. If this comes back `404`, `try_files` is wired wrong.

Clean up: `docker rm -f apus-ui-test`

- [ ] **Step 5: Commit**

```bash
git add ui/Dockerfile ui/nginx.conf
git commit -m "feat: package the dashboard as a static nginx container image"
```

---

### Task 9: Publish the images

Six images: `runner`, `ingest`, `hosting`, `operator`, `api`, `ui`. The first three already have their Dockerfiles; the last three come from Task 6–8.

**Files:**

- Modify: `.github/workflows/release-please.yml` (append publish jobs)

**Interfaces:**

- Consumes: `needs.release-please.outputs.root-released` and `root-version` from Task 3.

- [ ] **Step 1: Add a Gradle job for the jar artifacts**

Three of the six images copy Gradle outputs (`telemetry-addon` for `runner`, `ingest`, `operator`, `api`). The build context therefore has to contain those files. In `.github/workflows/release-please.yml`, after the `release-please` job:

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

- [ ] **Step 2: Add the six publish jobs**

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

`publish-ui` deliberately does **not** hang off `build-context`: the UI builds itself from source inside the Dockerfile and needs no Gradle output. The other five share one context artifact name — `artifact-name` must read exactly `docker-context` everywhere, or the publish job cannot find the upload.

- [ ] **Step 3: Check that `dockerfile` is a supported input**

Run: `gh api repos/OneLiteFeatherNET/workflows/contents/.github/workflows/docker-publish.yml -q '.content' | base64 -d | grep -A3 -E '^\s+(dockerfile|context|artifact-name):'`
Expected: the three inputs appear in the `workflow_call` `inputs` block. If `dockerfile` is missing, the central workflow only supports the standard name `Dockerfile` in the context directory — that then becomes an extension to the central workflow (approach per `release-engineering:workflows`, section "Introducing a new mechanic"), and this task is blocked until that has been added and tagged there.

- [ ] **Step 4: Validate the YAML**

Run: `python3 -c "import yaml;yaml.safe_load(open('.github/workflows/release-please.yml'));print('ok')"`
Expected: `ok`

- [ ] **Step 5: Commit**

```bash
git add .github/workflows/release-please.yml
git commit -m "ci: publish all six container images on release"
```

---

### Task 10: Maven publishing for the two libraries

BlueMap users consume `telemetry-addon`, server operators consume `paper-worldpush`. Neither is reachable without publishing.

**Files:**

- Modify: `telemetry-addon/build.gradle.kts`
- Modify: `paper-worldpush/build.gradle.kts`
- Modify: `.github/workflows/release-please.yml`

- [ ] **Step 1: Configure publishing in both modules**

In both `build.gradle.kts` files (adjust the module name each time):

```kotlin
plugins {
    `maven-publish`
    // ... existing plugins ...
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            groupId = "net.onelitefeather.apus"
            artifactId = "telemetry-addon"   // or "paper-worldpush"
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

- [ ] **Step 2: Cross-check the repository URL and credential names against an existing OLF project**

Run: `gh api repos/OneLiteFeatherNET/Aves/contents/build.gradle.kts -q '.content' | base64 -d | grep -A12 'repositories'`
Expected: the URL and environment variable names match Step 1. If they differ, the value from the existing project wins — the central `gradle-publish.yml` workflow injects exactly these secrets.

- [ ] **Step 3: Publish locally into a directory**

Run: `./gradlew :telemetry-addon:publishToMavenLocal :paper-worldpush:publishToMavenLocal`
Expected: BUILD SUCCESSFUL

Run: `find ~/.m2/repository/net/onelitefeather/apus -name '*.jar' | sort`
Expected: one jar per module.

- [ ] **Step 4: Append the publish jobs**

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

The project-qualified task syntax ensures that publishing one module does not drag the other along with it.

- [ ] **Step 5: Commit**

```bash
git add telemetry-addon/build.gradle.kts paper-worldpush/build.gradle.kts .github/workflows/release-please.yml
git commit -m "feat: publish telemetry-addon and paper-worldpush to the OneLiteFeather Maven repository"
```

---

### Task 11: Update the design spec

The spec still lists in §13.2 "Phase 1 set up no CI configuration at all in the repository" as an open point. After this plan that is no longer true.

**Files:**

- Modify: `docs/superpowers/specs/2026-08-08-apus-design.md`

- [ ] **Step 1: Rewrite §13.2, the line about `telemetry-addon`**

The sentence "**Open:** A CI matrix over supported BlueMap versions as an early-warning system does not exist — Phase 1 set up no CI configuration at all in the repository." is replaced with:

```markdown
**Partly open:** CI has existed since Phase 7 (`.github/workflows/build-pr.yml`), but a
matrix over multiple BlueMap versions as an early-warning system does not exist yet — the
contract test runs against the one version pinned in the catalog. Until a matrix exists, it
still has to be run deliberately before every BlueMap upgrade.
```

- [ ] **Step 2: Add a paragraph on the delivery state to §0**

Insert after the paragraph on region sharding:

```markdown
**Delivery has existed since Phase 7.** All six components are available as container images
(`runner`, `ingest`, `hosting`, `operator`, `api`, `ui`), `telemetry-addon` and
`paper-worldpush` are published to Maven. Versions and changelogs come from Release Please
out of Conventional Commits; `telemetry-addon` and `paper-worldpush` carry their own release
tracks in doing so, as provided for in §4. What is still missing are the cluster manifests
and the observability wiring — see the phase 8 plan.
```

- [ ] **Step 3: Markdown-lint the changed file**

Run: `npx markdownlint-cli2 docs/superpowers/specs/2026-08-08-apus-design.md`
Expected: no errors.

- [ ] **Step 4: Commit**

```bash
git add docs/superpowers/specs/2026-08-08-apus-design.md
git commit -m "docs: record the phase 7 delivery state in the design spec"
```

---

## What this plan deliberately does not cover

- **Cluster manifests, CRD YAMLs, metrics, dashboards and the k3s E2E run** — a separate plan (Phase 8). They assume the images built here, but not the other way around.
- **Identity broker selection, RBAC hardening, the quota exit code, the Paper save window and the `emptyDir` limit** — a separate plan (Phase 9). Those are substantive hardening work on existing code, not delivery questions.
- **A CI matrix over multiple BlueMap versions.** Worthwhile, but it assumes the contract test is parametrizable over the BlueMap version — which it is not today (the version is fixed in the catalog and in `runner/Dockerfile`). Belongs in the same step as a rework of the contract test, not in the CI rollout.
