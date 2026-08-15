# Apus — Design

**As of:** 2026-08-08
**Status:** Draft for approval

Apus renders Minecraft worlds with BlueMap on Kubernetes and hosts the results.
Worlds come from several very different sources; an ETL layer normalises them,
an operator runs render and hosting jobs, and a front end shows progress and
allows operation without YAML.

---

## 0. Implementation status

<!-- markdownlint-disable-next-line MD036 -->
*(Added after phase 6 was completed — the entry point for anyone joining now.)*

**All six phases from §14 are built**, including phase 6 (push sources:
`paper-worldpush` plus the UI upload path via `POST /api/uploads`). Render core,
operator/ingest with all four connectors (`s3`, `pterodactyl`, `push`, `upload`),
hosting, API/UI/tenants and the push sources are all on the main branch. The
module table in §4 reflects the state as it is today (including `hosting`, `api`, `ui`,
`paper-worldpush`).

**Region sharding (phase 4) was deliberately not built after the spike.** The spike
(`docs/superpowers/spikes/2026-08-09-lowres-sharding-spike.md`) demonstrated tile corruption
with shards running concurrently; the decision went in favour of vertical scaling
via `render-threads` — see §14, phase 4, for the full rationale.
`BlueMapMap.spec.shards` exists and stays capped at `1` until further notice.

**Delivery has been in place since phase 7.** All six components exist as container images
(`runner`, `ingest`, `hosting`, `operator`, `api`, `ui`); `telemetry-addon` and
`paper-worldpush` are published to Maven. Versions and changelogs are produced
by Release Please from Conventional Commits; `telemetry-addon` and `paper-worldpush`
carry their own release tracks in the process, as §4 foresaw. What is still missing is the
observability wiring (metrics, dashboards) — see the plan for phase 8.

**Rollout happens through Helm.** Apus is rolled out through two Helm charts under
`deploy/charts/` (`apus-operator`, `apus-platform`); they supersede the Kustomize base
originally planned for phase 8. Details:
`docs/superpowers/specs/2026-08-13-helm-charts-design.md`.

**Points deliberately left open** (details in §15):

- **Identity broker not chosen.** The API validates JWTs against a
  configurable issuer; which product (Keycloak, Zitadel, ...) actually sits in
  front of it has not been decided (§15, point 3).
- **OIDC login never tested against a real broker.** The auth tests in the
  `api` module run against a fake Kubernetes client and self-issued
  test JWTs (§13.2); an end-to-end run against a real identity broker (Keycloak/
  Zitadel) never happened.
- **`paper-worldpush`'s save window untested against a real
  Paper server.** The copy logic is covered by unit tests, but `BukkitSaveCoordinator`
  (the actual pause-autosave-and-force-save step) has never been checked against a running
  Paper instance or with MockBukkit, contrary to what §13.2 originally foresaw.

---

## 1. Goal and scope

### 1.1 Problem

Today, rendering a BlueMap map for a world that does not live on the same server
is manual work: get a backup, unpack it, map dimensions correctly, write HOCON configuration,
start BlueMap, wait without a reliable progress indicator, deliver the result
somehow. That scales neither across several worlds nor across several groups.

### 1.2 Goal

A service in which a world source is configured once and is then rendered and
hosted automatically — on a schedule or when new world data arrives —, with visible
progress and without anyone writing BlueMap configuration by hand.

### 1.3 Users

Internal use plus friendly servers. Several tenants, all known and
trusted. Tenant isolation is required; hard sandbox isolation against
malicious users is not.

Three role levels:

| Level | Who | May |
| --- | --- | --- |
| Platform | OLF as the operator | Create tenants, set quotas, see everything |
| Tenant administration | Owner/admin of a tenant | Sources, maps, hosting, members of their own tenant |
| Tenant usage | Operator/viewer | Trigger renders, or only watch |

### 1.4 Non-goals

- No public self-service for outsiders (no billing, no abuse detection, no hard sandbox).
- No replacement for BlueMap. Apus orchestrates BlueMap, it does not render itself.
- No renderer of our own in the MVP (see §14, phase 4).
- No management of the Minecraft servers themselves. Apus reads their world data, nothing more.

---

## 2. Starting point

Existing building blocks this design builds on:

- **`BlueMapS3Storage`** — a BlueMap addon that writes and reads map data in S3-compatible storage. The basis for render output and hosting input.
- **`Kubernetes-FLUX`** — cluster `feather-core`, GitOps via FluxCD. Already contains: Rook-Ceph with `CephObjectStore`, an `ObjectBucketClaim` provisioner (StorageClass for buckets), kube-prometheus-stack, Loki + Alloy, cert-manager, CNPG, nginx and Cloudflare Tunnel ingress.
- Existing bucket claims follow the `ObjectBucketClaim` + `bucketOwner` pattern, users the `CephObjectStoreUser` pattern.
- **`launchpad`** — Nuxt 4 + Vue 3.5 + Tailwind 4, the in-house frontend standard.
- An OIDC provider is in use (Outline, Grafana and Dependency-Track authenticate against it).

### 2.1 Findings from the BlueMap research

These points shape the design and are to be verified against the BlueMap version
in use before implementation:

1. **Progress exists but is not exposed.** A timer periodically queries `RenderTask.estimateProgress()` (0..1) and `RenderManager.estimateCurrentRenderTaskTimeRemaining()` and writes both as a log line. `BlueMapAPI` gives access to the `RenderManager`, but not to the running task.
2. **Render state lives in the map storage**, at a fine granularity: `tileState()` and `chunkState()` are `GridStorage` instances, stored per tile and per chunk respectively. That makes resume after a crash possible, and incremental rendering works over S3 as well.
3. **Addons are loaded in the CLI too**, early in `main` via `AddonLoader.tryLoadAddons(packsFolder)`. Addons run in their own classloader, may start their own threads and servers, and receive `BlueMapAPI`.
4. **There is no hook for the world data source.** Only the map output is swappable via storages. World data must be present locally as files.
5. **Region-wise rendering is possible through the public API:** `scheduleMapUpdateTask(map, Collection<Vector2i> regions)`.
6. **Lowres tiles aggregate across region boundaries** (averaging colour, height and light from several higher-resolution tiles). That is the open risk for parallel rendering (§14, phase 4).
7. **CLI flags:** `-r/--render`, `-f/--force-render`, `-m <map-ids>`, `-u/--watch`, `-w/--webserver`, `-e/--fix-edges`. Render and webserver operation are cleanly separable.
8. **BlueMap's web app is Vue 3 + Vite** and exposes `window.bluemap` as a JS API (switch map, camera, screenshot). Relevant for embedding it in the Apus UI later.

---

## 3. Architecture overview

```text
  Pterodactyl API ─┐   Pull:  query the backup list, stream tar.gz
  S3 bucket ───────┤   Pull:  check the prefix for new objects
  Paper plugin ────┤   Push:  async + incremental into a staging prefix
  UI upload ───────┘   Push:  presigned multipart
                    │
                    ▼
         ┌──────────────────────┐
         │     ingest (ETL)     │  Extract → Transform → Load
         └──────────┬───────────┘
                    ▼
          World Bundle in S3   ◄────── contract between ingest and render
                    │                  (normalised layout + manifest.json)
                    ▼
         ┌──────────────────────┐
         │   bluemap-runner     │  Job: fetch bundle → BlueMap CLI → map storage
         │   + S3StorageAddon   │
         │   + telemetry-addon  │  /progress (JSON) · /metrics (Prometheus)
         └──────────┬───────────┘
                    ▼
            Map storage in S3
                    │
                    ▼
         ┌──────────────────────┐
         │  bluemap-webserver   │  Deployment + Service + Ingress + certificate
         │  + S3StorageAddon    │
         └──────────────────────┘

   All of it driven by bluemap-operator through six CRDs.
   bluemap-api reads the CRs and logs, bluemap-ui displays them.
```

The central dividing line runs along the **World Bundle**. To the left of it nobody knows
anything about BlueMap, to the right nobody knows anything about Pterodactyl, ZIP uploads or Bukkit folder structures.

---

## 4. Building blocks

All in one multi-module Gradle monorepo, `Apus`.

| Module | Language/stack | Purpose |
| --- | --- | --- |
| `telemetry-addon` | Java 25, BlueMap addon | Exposes render progress as JSON and Prometheus metrics |
| `ingest` | Java 25 | ETL: connector SPI (s3, pterodactyl, push, upload), layout detection, bundle writer. Runs as a job |
| `runner` | Dockerfile + entrypoint | BlueMap CLI + both addons + bundle sync |
| `hosting` | Dockerfile + entrypoint | Long-lived webserver (BlueMap CLI in `-w` mode); reads rendered maps directly from S3 via `BlueMapS3Storage`, configured by a mounted ConfigMap instead of environment variables |
| `operator` | Java 25, Java Operator SDK (fabric8) | Six CRDs (`Tenant`, `WorldSource`, `WorldIngest`, `BlueMapMap`, `BlueMapRender`, `BlueMapHosting`), creates jobs/deployments/ingresses/buckets/secrets |
| `api` | Java 25, Micronaut | REST + SSE over the CRs, log aggregation, auth enforcement |
| `ui` | Nuxt 4, Vue 3, Tailwind 4, Nuxt UI, VueUse | Two dashboard levels |
| `paper-worldpush` | Java 25, Paper plugin | Async, incremental world upload from a running server |

`telemetry-addon` and `paper-worldpush` are tied to third-party versions (the BlueMap and
Paper APIs respectively) and get their own release track with their own version matrix — the
Java *language version* (toolchain, uniformly 25 for the whole monorepo, see the root
`build.gradle.kts`) is independent of that and applies equally to every Java module.
`runner` and `hosting` are pure Dockerfile/entrypoint images with no Gradle application
code of their own (hence no Java language version entry above); `runner` only carries
integration tests that check the contract with `ingest`.

---

## 5. World Bundle — the contract

A bundle is an immutable, versioned snapshot of a world in
normalised form.

```text
worlds/<tenant>/<world-id>/<version>/
  manifest.json
  overworld/region/r.0.0.mca …
  overworld/entities/…            (if present)
  overworld/poi/…                 (if present)
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

Rules:

1. **`manifest.json` is written last.** It is the commit point. Without a manifest the bundle does not exist and is never rendered. That way there are no half-unpacked worlds in the render path.
2. **Bundles are immutable.** New world data produces a new version, never a change to an existing one.
3. **The region list lives in the manifest.** It costs nothing during ingest (every `.mca` file is touched anyway) and is the basis for sharding and for accurate progress calculation.
4. **Dimensions are named logically** (`overworld`, `the_nether`, `the_end`), regardless of whether the source used a vanilla or a Bukkit layout.

---

## 6. Ingest layer (ETL)

### 6.1 Split

Only **extract** is source-specific. **Transform** and **load** are shared.
A new connector therefore costs an implementation of two methods.

```java
interface WorldSourceConnector {
    String type();
    List<SourceVersion> discover(WorldSourceConfig cfg);   // Pull; push returns an empty list
    FetchResult fetch(WorldSourceConfig cfg, SourceVersion version, Path workDir);
}
```

| Connector | Kind | Extract |
| --- | --- | --- |
| `pterodactyl` | Pull | Query the backup list through the client API, load the selected backup via a signed URL, stream the `tar.gz` and write out only the world paths |
| `s3` | Pull | Check the bucket prefix for new objects, load an archive or folder structure |
| `upload` | Push | Presigned multipart into a staging prefix, the UI reports completion |
| `push` | Push | The Paper plugin writes directly into a staging prefix and reports completion |

Since `tar.gz` is not seekable, the stream is walked through once in full and written
selectively while doing so. The whole archive never lands on disk.

### 6.2 Transform: layout detection

The critical part. Detection is based on the directory structure:

| Layout | Distinguishing feature | Mapping |
| --- | --- | --- |
| `vanilla` | `<w>/region`, `<w>/DIM-1/region`, `<w>/DIM1/region` | direct |
| `bukkit` | `<w>/region`, `<w>_nether/DIM-1/region`, `<w>_the_end/DIM1/region` | merge folders |
| `nested` | exactly one subfolder containing one of the above | skip the level, check again |

`WorldSource.spec.worlds[].layout: auto` detects automatically; `vanilla` or `bukkit`
force a choice. If detection fails, the ingest aborts with condition
`LayoutDetectionFailed` and the list of paths it found — no guessing.

### 6.3 Load

Files are written to `worlds/<tenant>/<world-id>/<version>/`, then the
manifest. After that the operator sets `WorldSource.status.latestBundle` and triggers
dependent renders (§8.3).

Retention: `WorldSource.spec.retention.keepVersions` (default 5). Older bundles are
deleted, provided no `BlueMapRender` still references them.

### 6.4 Triggers

- **Pull sources:** `WorldSource.spec.poll` as a cron expression. The operator compares against `status.lastSeenVersion` and creates a `WorldIngest` when something new appears.
- **Push sources:** preferably a bucket notification (Rook provides `CephBucketTopic`/`CephBucketNotification`) to an API endpoint, which turns it into a `WorldIngest`. **Fallback**, should notifications not be enabled in the cluster: polling the staging prefix at the same interval as pull sources. The decision is made while building phase 2 after checking the cluster configuration; both paths end in the same code path.

---

## 7. Render layer

### 7.1 How a render runs

A `BlueMapRender` creates a Kubernetes `Job` with:

1. **Init: `bundle-sync`** — loads the bundle's dimensions listed in the manifest onto an `emptyDir` (or a PVC for large worlds).
2. **Init: `assets-sync`** — fetches the Minecraft client JAR for the required version from the asset cache bucket. Prevents every render pod from downloading from Mojang again.
3. **Main: `bluemap`** — the BlueMap CLI with `-r`, plus `BlueMapS3Storage` (map output) and `telemetry-addon` (progress) in the `packs/` folder. The container generates the configuration itself at startup from environment variables (§7.4); the credentials for that come from the secret Rook creates. **No** ConfigMap is mounted — see the note in §9.2.

The map output goes straight into the target bucket via the S3 storage. There is no
separate upload step — and therefore no state that can be lost between "rendered" and
"uploaded".

### 7.2 Progress

The `telemetry-addon` starts an HTTP server (default `:8099`):

```jsonc
// GET /progress
{
  "state": "rendering",             // starting | rendering | idle | unknown
  "currentMap": "overworld",
  "progress": 0.674,
  "etaSeconds": 1830,
  "queuedTasks": -1,                // structurally undeterminable in CLI operation, see below
  "renderThreads": -1,              // ditto
  "degraded": false,                // true when progress cannot be determined
  "description": "updating map 'overworld'"
}
```

`queuedTasks` and `renderThreads` show `-1` here instead of example values: in CLI operation
Apus reads progress by tailing the log (see below), and BlueMap's own progress line
contains neither queue depth nor thread count — these fields are structurally
unreachable via that route, not a measurement failure.

`/metrics` serves the same values as Prometheus metrics; a `PodMonitor` collects them
for Grafana and history.

**Known coupling:** `estimateProgress()` is not reachable through the public `BlueMapAPI`.
Two implementations of `RenderManagerAccess` exist, and `ApusTelemetryAddon`
picks between them — no reflection in either case:

1. **`BlueMapRenderManagerAccess`** — the route BlueMap documents for addons,
   `((BlueMapAPIImpl) api).plugin().getRenderManager()`. On success it additionally provides
   queue depth and thread count.
2. **`LogTailRenderManagerAccess`** — registers itself on BlueMap's own
   `Logger.global` (`de.bluecolored.bluemap.core.logger.Logger`/`MultiLogger`, the same
   mechanism the CLI flags `-l`/`-b` use themselves) and parses the progress line
   BlueMap logs of its own accord anyway (`updating map 'overworld': 35.208% (ETA: 38 seconds)`).
   Provides no queue depth/thread count (they are not in the log line; `-1`
   there).

**Verified against BlueMap 5.23, task 8:** in CLI operation — the mode in which
`apus/runner` uses BlueMap exclusively — route 1 (`plugin()`) is **structurally always**
`null`. `BlueMapCLI.renderMaps()` unconditionally constructs `BlueMapAPIImpl` with
`Plugin = null` (verified by decompilation); BlueMap itself then also skips
building the internal `RenderManagerImpl`, so a reflection fallback onto it would run into
nothing as well — in this mode there is no object reachable via `BlueMapAPI` that holds the real
`RenderManager`. The reflection fallback onto `RenderManagerImpl` originally sketched here
is therefore **not** a viable route for CLI operation — it presupposes an
instance that never comes into existence in CLI mode. Route 2 (log tailing), in contrast, works
reliably in CLI operation, because BlueMap's own CLI emits the progress line independently
of the `Plugin` object through the global logger. `apus/runner` therefore runs
exclusively on route 2; route 1 is kept for a future server-plugin deployment
(where a real `Plugin` instance does exist) as the preferred, richer path.
Details, observed `/progress` responses and the rejected alternative
(reflection into `java.util.Timer` internals) are in `runner/README.md#telemetry`.

Safeguards:

- Both access routes are encapsulated behind the `RenderManagerAccess` interface
  (`telemetry-addon/.../probe/`), and `ApusTelemetryAddon` only picks between them. A
  later third route (an official API extension, our own runner) replaces only the
  wiring there.
- If every access route fails (including the log-tail registration itself),
  `/progress` returns `degraded: true`, **without** affecting the render. Progress is
  a convenience, not a critical path.
- A contract test (`runner/src/test/java/.../TelemetryContractTest.java`) runs against
  a real render and must be executed before every BlueMap upgrade. **Honestly
  accounted for, it covers only route 2** (log tailing, the only route that works at all
  in CLI operation). Route 1 (`BlueMapRenderManagerAccess`) and `ApusTelemetryAddon.run()`
  itself have **no** test coverage in phase 1 — route 1 only becomes relevant once Apus
  runs on a server platform with a real `Plugin` instance, which phase 1 does not cover.
- Medium term: an upstream PR making the CLI's own `RenderManager` reachable through
  `BlueMapAPI` independently of `Plugin` (see `runner/README.md#telemetry`) would make route 1
  viable in CLI operation too and render the log-tail stopgap unnecessary.

The operator polls `/progress` once a second through the pod and writes the values into
`BlueMapRender.status.progress`. That way `kubectl get bluemaprender` shows the state as well.

### 7.3 Resume and concurrency

Because chunk hashes and tile states live in the map storage, a newly started pod picks up
the work. `backoffLimit` controls the attempts; after that the CR goes to `Failed` — with
the last progress value retained, so it stays visible where it broke off.

**Two concurrent renders onto the same map storage must be prevented.** Competing
writers to tile and state data can leave the map inconsistent.
`BlueMapMap.spec.trigger.concurrencyPolicy: Forbid` is the default and is enforced through
the CR status as a lock: the operator creates no new job as long as a
`BlueMapRender` for the same map is in an active phase.

The hosting pod reads while rendering is in progress. That is harmless — BlueMap is designed
for ongoing updates; users briefly see mixed states.

### 7.4 Environment variable contract (phase 1)

`apus/runner` (§7.1, container `bluemap`) is configured exclusively through environment
variables. That is the interface the operator will drive in phase 2 — the
`Job` produces exactly these variables from `BlueMapRender`/`BlueMapMap` and the Rook values
from §9.1. Verified and shipped in phase 1 (`runner/entrypoint.sh`,
`runner/bin/render-config.sh`):

| Variable | Required | Meaning |
| --- | --- | --- |
| `APUS_MAP_ID` | yes | Map id, e.g. `overworld`. Used as a path segment — lowercase letters, digits, `-`, `_` only |
| `APUS_DIMENSION` | yes | `minecraft:overworld`, `minecraft:the_nether`, `minecraft:the_end` |
| `APUS_MC_VERSION` | yes | e.g. `1.21.10` |
| `APUS_WORLD_S3_URL` | yes | Source of the world, e.g. `s3://bundles/worlds/t/survival/v1/overworld` |
| `APUS_MAP_BUCKET` | yes | Target bucket for the rendered map |
| `APUS_MAP_PREFIX` | no | Prefix in the target bucket, default `.` |
| `APUS_S3_ENDPOINT` | yes | e.g. `http://minio:9000` |
| `APUS_S3_ACCESS_KEY` | yes | Access key |
| `APUS_S3_SECRET_KEY` | yes | Secret key |
| `APUS_S3_REGION` | no | Default `us-east-1` |
| `APUS_RENDER_THREADS` | no | Default `2` |
| `APUS_FORCE_RENDER` | no | `true` adds `-f` |
| `APUS_TELEMETRY_PORT` | no | Default `8099` |
| `APUS_TELEMETRY_BIND` | no | Listen address of the telemetry server, default `0.0.0.0` |
| `APUS_TELEMETRY_ENABLED` | no | `false` switches the telemetry server off; any other value leaves it running |

The complete, continuously maintained reference with example values is in
`runner/README.md`.

---

## 8. Data model

API group `bluemap.onelitefeather.net/v1alpha1`. All resources namespaced except
`Tenant`. Pattern: **a template creates executions**, analogous to CronJob→Job.

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
    organization: friends-server # organisation in the identity broker
status:
  namespace: bluemap-friends-server
  objectStoreUser: apus-friends-server
  storageUsedBytes: 228730548224
  conditions: [...]
```

From this the operator creates: a namespace, a `CephObjectStoreUser` with a quota, a `ResourceQuota`,
a `LimitRange`, RBAC and a NetworkPolicy.

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
  poll: "0 */6 * * *"            # pull types only
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
      overrides:                                  # optional, selective
        render-threads: 8
  storage:
    bucketClaim: auto            # the operator creates an ObjectBucketClaim
    prefix: survival
  resources: { cpu: "8", memory: 16Gi }
  shards: 1                      # >1 only from phase 4 onwards
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
  force: false                   # equivalent to --force-render
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

### 8.7 Deliberately not modelled

- **No `BlueMapSchedule`.** One schedule per map is enough, and it sits as a field in the template. The same goes for `WorldSource.poll`.
- **Ingest and render stay separate.** Both are long-running, fail independently and carry their own progress. Merged, there would be one resource with two competing state machines, and a failed render would force an expensive re-ingest even though the bundle is intact.

---

## 9. Automatic setup of S3 and BlueMap

### 9.1 S3 is delegated to Rook

The operator contains **no** S3 administration code. With `storage.bucketClaim: auto`
it creates an `ObjectBucketClaim` against the bucket StorageClass, with
`additionalConfig.bucketOwner` = the tenant's `CephObjectStoreUser`. Rook then creates
the bucket, a credentials secret and a ConfigMap with endpoint and bucket name; the
operator waits for those to be provisioned and wires the values onward.

**A deliberate deviation from the existing convention:** existing bucket claims live
centrally in the Rook namespace. Apus creates them in the **tenant namespace**, because Rook always
creates the secret and ConfigMap in the namespace of the claim — that way the credentials are
where the render job and hosting pod need them, without copying secrets across namespace boundaries.

Because all of a tenant's buckets belong to its `CephObjectStoreUser`, their
total consumption counts against that user's quota (§10.2).

### 9.2 BlueMap configuration is generated (phase 3, hosting)

**Clarification (2026-08-08, phase 2a review):** this section originally described the
operator delivering the render configuration as a ConfigMap. That contradicted §7.4: the phase-1
runner is configured for the render **exclusively through environment variables** and never reads
anything from a mounted path — this is verified against a real render
(`runner/entrypoint.sh`, `runner/bin/render-config.sh`). The `RenderJobBuilder` from phase 2a
therefore deliberately mounts **no** ConfigMap; §7.4 is authoritative for the render path, not this
section.

The configuration generation described here remains valid, but only becomes relevant for **phase 3**
(`BlueMapHosting`): the long-lived webserver pod that serves already rendered maps
needs a complete `webserver.conf` and the same storage binding — a
surface the render environment variable contract from §7.4 does not cover. `BlueMapConfigBuilder`
already exists (phase 2a) and generates these files, but is only actually wired up with the hosting pod in
phase 3.

From the CR and the Rook values the operator generates the complete
BlueMap configuration for the hosting pod as a ConfigMap (plus a secret for credentials):

| File | Contents |
| --- | --- |
| `core.conf` | Data directory, `render-threads`, metrics disabled, `accept-download: true` (**required** — without this key BlueMap does not download the Minecraft resources and every render fails with exit code 2), `scan-for-mod-resources: false` |
| `storages/s3.conf` | Endpoint, bucket, path-style access, credentials — for `BlueMapS3Storage` |
| `maps/<id>.conf` | World path from the bundle manifest, dimension, render settings |
| `webserver.conf` | in the hosting pod only |

Users write no HOCON. Anyone who needs a special case sets
`bluemap.config.overrides` selectively or references a ConfigMap of their own.

**Verified format of `storages/s3.conf`** (phase 1, task 7 — confirmed by an integration test
against a real BlueMap CLI run and a source review of `S3StorageConfiguration`;
the operator must produce exactly these keys when wiring it up in phase 3):

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

### 9.3 Asset cache

BlueMap downloads the Minecraft client JAR for textures and models. In ephemeral pods that
would happen on every run. Instead, the required client JARs are kept per Minecraft version in
a platform-wide asset bucket; the init container `assets-sync` fetches them from there.
If a version is missing, a one-off job downloads and stores it.

### 9.4 Lifecycle

`reclaimPolicy: Retain` on the bucket StorageClass means: deleting a
`BlueMapMap` deletes **no** data. That is intentional — an accidental
`kubectl delete` must not destroy hours of render work. Cleaning up requires
`spec.purgeOnDelete: true`; the operator sets a finalizer for that.

---

## 10. Tenants, quotas, auth

### 10.1 Isolation

One tenant corresponds to one namespace. Within it: its own S3 credentials as a secret,
a `ResourceQuota` and `LimitRange` as an upper bound, a NetworkPolicy. The operator runs
cluster-wide but works strictly namespace-locally: a CR may reference only secrets
and resources of its own namespace. References across namespace boundaries
are rejected during validation.

### 10.2 Storage limit

`Tenant.spec.storage.quota` is mapped onto `CephObjectStoreUser.spec.quotas.maxSize`
(plus `maxObjects`). Enforcement happens in Ceph: RGW rejects uploads above
the limit. This is not a display value — a tenant cannot exceed its limit
even if the application miscalculates.

Per-bucket quotas (`bucketMaxSize` via `additionalConfig`) are **not** used: they
are disabled by default in Rook and would require a cluster-wide operator configuration.
The user quota serves the purpose.

The operator queries consumption periodically through the RGW admin ops API and writes it
into `Tenant.status.storageUsedBytes`. The credentials for that are platform-wide, not
tenant-bound.

### 10.3 Authentication

In front of Apus sits an **identity broker with organisation support** (e.g. Keycloak from
version 26 onwards, or Zitadel — both offer organisations with their own identity provider per
organisation as well as invitation flows; the concrete product choice is made at the start of
phase 5 after checking it against the existing OIDC setup).

That covers both cases without Apus managing passwords:

- Tenant with its own IdP: the broker federates to that OIDC.
- Tenant without its own IdP: local accounts in the broker, managed by its admins.

Apus knows exactly **one** issuer. The organisation claim in the token determines the tenant
and thereby the namespace.

Roles: `platform-admin`, `tenant-owner`, `tenant-operator`, `tenant-viewer`.

| Role | May |
| --- | --- |
| `platform-admin` | Create/change/delete tenants, quotas, cluster-wide view |
| `tenant-owner` | Everything within their own tenant, including member management |
| `tenant-operator` | Maintain sources and maps, trigger renders |
| `tenant-viewer` | Read only |

**Service tokens** are tenant-bound and carry a narrow scope (`world:push`).
The Paper plugin and CI processes use them. They deliberately hang off no user login —
otherwise a person leaving would cripple the server upload.

The backend is the enforcement point: it first checks the application permissions and then talks
to the Kubernetes API through its own ServiceAccount. No impersonation.

---

## 11. API and UI

### 11.1 API

Micronaut, REST plus SSE. The CRs are the source of truth; the API keeps no
copy of the state but reads through an informer cache.

| Endpoint | Purpose |
| --- | --- |
| `GET /api/tenants` … | Platform level, `platform-admin` only |
| `GET /api/sources`, `POST /api/sources` | Sources of one's own tenant |
| `POST /api/maps/{id}/render` | Trigger a render (creates a `BlueMapRender`) |
| `GET /api/renders/{id}/events` | SSE: progress live |
| `GET /api/renders/{id}/logs` | SSE: log stream from Loki |
| `POST /api/uploads` | Presigned multipart for a world upload |
| `POST /api/push/{token}` | Push notification from the Paper plugin |

For the log stream, Loki is queried (Alloy already collects all pod logs), filtered
to the job of the render in question. That way the API needs no direct pod access.

### 11.2 UI

Nuxt 4 in SPA mode (`ssr: false`), Vue 3, Tailwind 4, Nuxt UI, VueUse — aligned with
`launchpad`. Two levels, separated by the role in the token:

- **Platform:** tenants, quotas with a consumption display, running jobs cluster-wide, domain approvals.
- **Tenant:** sources, maps, render history, live progress with ETA, log viewer, hosting URLs, members.

Accessibility is checked via `eslint-plugin-vuejs-accessibility`, as in `launchpad`.

Since BlueMap's web app is itself Vue and exposes `window.bluemap`, the hosted
map can later be embedded and controlled rather than merely linked to. Not in the MVP.

---

## 12. Error handling

| Case | Behaviour |
| --- | --- |
| Render pod dies (OOM, eviction) | A new pod resumes from the render state in S3. After `backoffLimit`, phase `Failed`; the last progress stays visible |
| Two renders on the same map | Prevented by `concurrencyPolicy: Forbid`, lock via CR status |
| Ingest aborts | No manifest → the bundle counts as non-existent. No half state in the render path |
| Unknown world layout | Condition `LayoutDetectionFailed` with the paths found. No guessing, no retry |
| Storage limit reached | RGW error → condition `StorageQuotaExceeded`, **no** retry. Otherwise the job runs endlessly against a wall |
| Telemetry access route breaks after a BlueMap update | `/progress` degrades (see §7.2), the render continues normally |
| Rook does not deliver the bucket | `BlueMapMap` stays in `Pending` with condition `BucketProvisioning`, no job is started |
| Bundle version was deleted | The render fails with `BundleNotFound`; retention deletes only unreferenced bundles |

Credentials never appear in CR status, events or logs.

---

## 13. Observability and tests

### 13.1 Observability

- **Metrics:** the telemetry addon exposes `/metrics`, collected via a `PodMonitor`. The operator exports metrics of its own (renders by phase, ingest duration, quota utilisation per tenant).
- **Logs:** Alloy → Loki, as is usual in the cluster. The API reads the live stream from there.
- **Dashboards:** one Grafana dashboard per level (platform, tenant).

### 13.2 Tests

| Building block | Approach |
| --- | --- |
| `ingest` | Fixture archives per layout (Pterodactyl `tar.gz`, Bukkit split, vanilla, ZIP with a subfolder, corrupt archive) against the layout detector. Pure unit tests, plus MinIO-backed integration tests per connector (`s3`, `pterodactyl`, `push`, `upload`) and an end-to-end test (`PushIngestEndToEndTest`) that drives a complete ingest run for push/upload sources against real MinIO |
| `telemetry-addon` | Contract test per BlueMap version: render a mini world, check `/progress` for plausible values (covers the log-tail route, see §7.2). **Partly open:** CI has existed since phase 7 (`.github/workflows/build-pr.yml`), but a matrix across several BlueMap versions as an early-warning system has not — the contract test runs against the single version pinned in the catalog. Until a matrix exists, it must still be run deliberately before every BlueMap upgrade |
| `runner` | Integration test against an S3 test container with a small world, including `IngestRenderContractTest` (ingest → bundle → render end to end) |
| `operator` | JOSDK `LocallyRunOperatorExtension` against k3s via Testcontainers, plus `EnableKubernetesMockClient` tests per reconciler |
| `api` | Micronaut tests against a fake Kubernetes client or `EnableKubernetesMockClient`, auth cases per role. **Open:** no run against a real identity broker (see §0/§15, point 3) |
| `ui` | Component tests plus accessibility lint |
| `paper-worldpush` | Unit tests for the copy logic, the configuration and the HTTP report path against a local JDK `HttpServer` stub. **Open:** no MockBukkit test and no run against a real Paper server for the save window (`BukkitSaveCoordinator`) — see §0 |
| E2E | k3s + S3: a complete run through ingest → render → hosting with a mini world |

**Note on CRD generation — done.** The Fabric8 CRD generator is geared towards Maven
and ships no supported CLI for the version in use (7.8.0). Solved
with a dedicated `crdgen` source set in `operator/build.gradle.kts` plus a small
`CrdGeneratorMain` entry point that calls the programmatic `crd-generator-api-v2`/
`CustomResourceCollector` API; a `generateCrds` task produces the six
CRD YAMLs from it. See §15, point 4.

---

## 14. Phase plan

The MVP uses the **BlueMap CLI unmodified**. A renderer of our own is explicitly not
part of the MVP.

### Phase 1 — Render core *(MVP)*

`telemetry-addon` and `runner`. Result: a `docker run` renders a world from S3
to S3 and reports progress. Fully testable without Kubernetes.

### Phase 2 — Operator and ingest *(MVP)*

Six CRDs, reconcilers, job creation, bucket provisioning via Rook,
configuration generation, progress in the status. Ingest with the first connectors.
Result: renders run declaratively through Flux, `kubectl get bluemaprender` shows per cent
and ETA. Already fully usable for internal use.

`Tenant` comes into being here already, because namespace, quota and bucket ownership are the basis
for everything that follows — in the MVP, however, maintained exclusively through Git and `kubectl`.
The front end and identity broker for it only arrive in phase 5.

### Phase 3 — Hosting *(MVP)*

`BlueMapHosting`: webserver deployment, service, ingress, certificate, URL in the status.
Result: maps are reachable under their own address. **End of the MVP.**

### Phase 4 — Region sharding *(spike carried out, result: no sharding)*

**The spike has run and came out negative.** Report:
`docs/superpowers/spikes/2026-08-09-lowres-sharding-spike.md`.

What was measured was a reference run (the whole world in one pass) against two containers
running concurrently with disjoint, adjacent region sets in the same
map storage. Result: **7 out of 24 lowres tiles differ**, reproduced three times; one
tile drops from 99 % rendered terrain to 91 % empty. A sequential control run
corrupts as many as 10 out of 24 tiles — the order dependency confirms the mechanism
independently of the race.

That refutes the earlier assumption that granular storage protects sufficiently. It
prevents corruption of individual tiles, but not two shards overwriting the same aggregated
lowres tile.

**Decision: no sharding.** Of the two alternatives foreseen in this spec,
the second is chosen — forgoing it in favour of vertical scaling via `render-threads`.
Rationale:

- The two-stage approach (shards render only hires, a final pass builds the
  lowres levels) presupposes a runner of our own bound to BlueMap core. That is
  exactly what §1.4 rules out for the MVP, and §2.1 names the reason: BlueMap core is not
  a stable public API.
- Vertical scaling is already available and costs nothing.
- So far there is no world whose render time would justify the problem. Without that
  need, sharding would be effort spent against a hypothetical problem.

**What remains of phase 4:** the architecture is laid out to be sharding-capable — the region list
is in the bundle manifest, `BlueMapMap.spec.shards` exists. Should a world in future
genuinely take too long, the two-stage route is the approach to examine then, and
the spike report is the basis for it. Until then `shards` stays capped at `1`;
a higher value is not implemented and should be rejected by the operator.

### Phase 5 — API, UI and tenants

Identity broker, `Tenant` administration with quotas, REST/SSE API, Vue dashboard at two
levels.

### Phase 6 — Push sources *(done)*

`paper-worldpush` and the UI upload. What was actually implemented instead of bucket notifications: a
direct completion callback from the writer itself (`POST /api/push/{token}` from the
Paper plugin, `POST /api/uploads/{id}/complete` from the UI upload flow) instead of a
mailbox-like signal from Ceph or polling of the staging prefix — see §15, point 2,
for the rationale.

---

## 15. Open points

1. ~~**Connector order in the MVP.**~~ **Done.** The assumed order has
   held up: `s3` and `pterodactyl` first (phase 2), `push` and `upload` in phase
   6 — the Paper plugin did not turn out to be the more important route, and a reordering
   was not needed. All four connectors sit behind the same `WorldSourceConnector`
   interface (`ingest/.../connector/`); `IngestConfig`/`IngestMain` wire all
   four up in the same way.
2. ~~**Bucket notifications.**~~ **Solved differently, no longer open.** Neither
   `CephBucketTopic`/`CephBucketNotification` nor prefix polling is used for push sources:
   instead the writer itself reports completion directly to the API
   (`POST /api/push/{token}` from the Paper plugin, `POST /api/uploads/{id}/complete` from the
   UI upload flow) — checking whether Rook notifications are enabled in the cluster was
   therefore not needed for the MVP. Kept in mind as a possible later hardening,
   should a writer be able to lose the callback (a network error after the last
   upload, before the notification goes out) and a second, independent detection route be
   wanted.
3. **Identity broker product choice.** Still open — see §0. The API validates JWTs
   against a configurable issuer, without any concrete broker product
   (Keycloak/Zitadel) having been chosen or tested against a real broker.
4. ~~**CRD generation under Gradle.**~~ **Done** — see the "Note on CRD generation" in
   §13.2.
5. **`render-mask` and edges.** Only relevant should the mask route be chosen in phase 4 instead of our own runner: whether the filling with air outside the mask can be switched off is then to be checked.
6. **Volume type for large worlds.** `emptyDir` suffices up to a size that depends on the node's specification; above that a PVC is needed. **Open:** contrary to the original commitment, this threshold was **not** measured in phase 1 — it is scope of its own, not merely the verification of an existing plan. Must be made up before phase 2, before the operator fixes a default for the CR.
7. **No dependable quota signal from the runner image.** `BlueMapRenderReconciler` currently detects a storage limit heuristically from the reason/message of the terminated render pod (patterns such as `QuotaExceeded`, or "quota" combined with an S3 reference like `bucket`/`rgw`/`ceph`), backed by `terminationMessagePolicy: FallbackToLogsOnError` so that a message arrives at all. That remains best effort: the kubelet vocabulary for the termination reason never contains "quota", and the message is only a log excerpt with no contract. A dependable signal (e.g. a dedicated runner exit code for "quota exhausted") must be added before production use, before more behaviour (automatic notifications, say) is built on top of it.
8. **`paper-worldpush`'s save window untested against a real Paper server.** §13.2 originally foresaw MockBukkit for the copy logic plus a run against a real Paper server for `BukkitSaveCoordinator`'s pause-autosave-and-force-save step; in fact only unit test coverage exists for the copy logic, the configuration and the HTTP report path (`HttpPushNotifierTest` against a local `HttpServer` stub). Whether the brief window between `disableAutoSave()`/`forceSave()` and the start of the incremental copy really yields a consistent snapshot on a real server under load is to be verified before production use.
9. **RBAC for the API's push token lookup broader than ideal.** `FabricPushTokenRepository#resolveNamespace` searches (for lack of a tenant hint in the request) by label across all namespaces for service token secrets; Kubernetes RBAC cannot restrict this access to the label, so the narrowest *working* permission for today's approach is nonetheless `get`/`list` on **all** secrets in the cluster (see the class documentation for the full trade-off and for a sketched but unimplemented narrower route via `Tenant` enumeration + `get` with a fixed secret name).
10. **No SLF4J provider on the runtime classpath — no application logs on stdout.** Not one
    `build.gradle.kts` in the monorepo declares a dependency on `logback-classic` or any
    other SLF4J provider; for `api` and `operator` this was additionally confirmed on the running
    container (`SLF4J(W): No SLF4J providers were found` at startup, falling back to the no-op
    logger). As a result, no built image currently writes application logs to stdout. This affects
    two places in the spec directly: §13.1 assumes that Alloy forwards pod logs to Loki,
    and §11.1's `GET /api/renders/{id}/logs` reads exactly that Loki stream for the API SSE route —
    both remain ineffective as long as no module brings a logging provider with it. Must be fixed before the
    observability wiring (phase 8); this gap is merely recorded here,
    not fixed.

---

## 16. Decisions

| Decision | Rationale |
| --- | --- |
| BlueMap CLI unmodified instead of a renderer of our own | Least coupling to BlueMap internals; upgrades are an image tag |
| Addon API preferred, log tailing as the load-bearing route in phase 1 | Log parsing had originally been rejected (no contract, locale/format risk). Verified in task 8 (§7.2): the CLI unconditionally constructs `BlueMapAPIImpl` with `Plugin = null`, so the documented addon route (`plugin().getRenderManager()`) structurally never works in CLI operation — there is no `RenderManager` object reachable via `BlueMapAPI` there. Log tailing on `Logger.global` is the only working route in CLI operation and stays encapsulated behind the same `RenderManagerAccess` interface; the addon route remains the preferred, richer path for a future server-plugin deployment |
| One addon, no split into telemetry and control | The operator triggers renders through CRs; a control endpoint would be a second route to the same goal |
| World Bundle as the contract | Decouples sources from BlueMap; a new source costs only a connector |
| Write the manifest last | Makes bundle completion atomic, without transactions over S3 |
| Rook instead of S3 code of our own | Bucket, credentials and quota are available declaratively |
| Bucket claims in the tenant namespace | Rook creates the secret and ConfigMap there; avoids secret copies across namespaces |
| Quota at user rather than bucket level | Per-bucket quotas are switched off by default in Rook; the user quota serves the purpose |
| Identity broker instead of our own user management | Covers "own OIDC" and "internal accounts" per tenant, without Apus being responsible for password security |
| No `BlueMapSchedule` | One schedule per map is enough; as a field rather than a resource |
| `Retain` as the deletion behaviour | Render work is expensive; data loss needs an explicit intent |
| Vue/Nuxt/Tailwind | The in-house standard from `launchpad`; BlueMap's own web app is Vue as well |
| Sharding only after a spike | The lowres aggregation behaviour is unclear and does not belong on the critical path of the MVP |
