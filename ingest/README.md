# Apus Ingest Image

Pulls raw Minecraft world data from a configured source (S3 bucket or a Pterodactyl panel
backup), detects its on-disk layout, and writes it to S3 as a versioned, self-describing world
bundle that the `runner` render container can consume without knowing where the data came from.

`IngestMain` orchestrates the flow: read and validate configuration from environment variables
(failing before anything is downloaded if one is missing) → fetch via the connector matching
`APUS_SOURCE_TYPE` → detect the layout → write the bundle, manifest last.

## Build

```bash
./gradlew :ingest:shadowJar
docker build -f ingest/Dockerfile -t apus/ingest:dev .
```

The build context is the repository root, matching `runner/Dockerfile`'s convention -- the image
needs the shadow jar Gradle builds under `ingest/build/libs/`.

## Run

```bash
docker run --rm \
  -e APUS_SOURCE_TYPE=s3 \
  -e APUS_WORLD_NAME=world \
  -e APUS_SOURCE_VERSION=2026-08-01T00-00-00Z.zip \
  -e APUS_BUNDLE_BUCKET=bundles \
  -e APUS_BUNDLE_TENANT=acme \
  -e APUS_BUNDLE_SOURCE_NAME=survival-source \
  -e APUS_BUNDLE_WORLD_ID=survival \
  -e APUS_BUNDLE_VERSION=v1 \
  -e APUS_S3_ENDPOINT=http://minio:9000 \
  -e APUS_S3_ACCESS_KEY=... \
  -e APUS_S3_SECRET_KEY=... \
  -e APUS_SOURCE_S3_BUCKET=backups \
  -e APUS_SOURCE_S3_PREFIX=survival/ \
  -e APUS_SOURCE_S3_ACCESS_KEY=... \
  -e APUS_SOURCE_S3_SECRET_KEY=... \
  apus/ingest:dev
```

### Environment variables

The full contract this image accepts, and the interface `IngestJobBuilder` (phase 2b, task 6)
builds Kubernetes Jobs against -- the ingest equivalent of `runner/README.md`'s table.

| Variable | Required | Default | Meaning |
|---|---|---|---|
| `APUS_SOURCE_TYPE` | yes | — | `s3` or `pterodactyl`. `upload`/`push` are recognised by `WorldSource.spec.type` but have no connector yet (phase 6) -- an unsupported value fails fast rather than being guessed at |
| `APUS_WORLD_NAME` | yes | — | The world's folder name at the source, e.g. `world` |
| `APUS_LAYOUT` | no | `auto` | `auto`, `vanilla`, or `bukkit`. `auto` lets `LayoutDetector` decide; any other value forces that layout and fails detection rather than falling back if the fetched data doesn't actually match it |
| `APUS_SOURCE_VERSION` | yes | — | The exact source version id to fetch, as previously resolved by `WorldSourceReconciler`'s `discover()` poll (task 6) and recorded on the owning `WorldIngest.spec.sourceVersion`. This job never calls `discover()` itself -- see "Design notes" below |
| `APUS_BUNDLE_BUCKET` | yes | — | Destination bucket for the bundle |
| `APUS_BUNDLE_TENANT` | yes | — | Tenant id, becomes the first path segment of the bundle |
| `APUS_BUNDLE_SOURCE_NAME` | yes | — | The owning `WorldSource`'s name, becomes the second path segment. Required so two different sources ingesting a world with the same id (e.g. the vanilla default `world`) never collide on the same bundle path -- see `BundlePath` |
| `APUS_BUNDLE_WORLD_ID` | yes | — | World id, becomes the third path segment |
| `APUS_BUNDLE_VERSION` | yes | — | This bundle version's identifier, becomes the fourth path segment |
| `APUS_S3_ENDPOINT` | yes | — | Bundle destination S3-compatible endpoint, e.g. `http://minio:9000` |
| `APUS_S3_ACCESS_KEY` | yes | — | Bundle destination access key |
| `APUS_S3_SECRET_KEY` | yes | — | Bundle destination secret key |
| `APUS_S3_REGION` | no | `us-east-1` | Bundle destination region |
| `APUS_MC_VERSION` | no | — | Minecraft version recorded as `manifest.minecraftVersion`. Not part of the original task-5 contract -- added because nothing else can supply this value reliably; see "Design notes" |
| `APUS_PROGRESS_INTERVAL_SECONDS` | no | `10` | Minimum seconds between progress lines on stdout; the final update always prints regardless |
| `APUS_MAX_ARCHIVE_TOTAL_BYTES` | no | `5368709120` (5 GiB) | Upper bound on total bytes extracted from one source archive; extraction aborts once exceeded. The work directory has no mounted volume, so this bounds how much of the node's own disk an archive (hostile or just unexpectedly large) can consume -- see `Archives` |
| `APUS_MAX_ARCHIVE_ENTRIES` | no | `200000` | Upper bound on the number of entries (files + directories) extracted from one source archive; extraction aborts once exceeded |
| `APUS_SOURCE_S3_BUCKET` | yes, if `APUS_SOURCE_TYPE=s3` | — | Source bucket |
| `APUS_SOURCE_S3_ENDPOINT` | no | AWS default | Source S3-compatible endpoint |
| `APUS_SOURCE_S3_PREFIX` | no | `""` | Prefix under which each object is one fetchable version |
| `APUS_SOURCE_S3_ACCESS_KEY` | no | credential chain | Source access key; if unset, falls back to the AWS SDK default credentials chain |
| `APUS_SOURCE_S3_SECRET_KEY` | no | credential chain | Source secret key |
| `APUS_SOURCE_S3_REGION` | no | `us-east-1` | Source region |
| `APUS_PTERODACTYL_PANEL_URL` | yes, if `APUS_SOURCE_TYPE=pterodactyl` | — | Panel base URL, e.g. `https://panel.example.com` |
| `APUS_PTERODACTYL_SERVER_ID` | yes, if pterodactyl | — | Server identifier (short id) |
| `APUS_PTERODACTYL_API_KEY` | yes, if pterodactyl | — | Client API key (`ptlc_...`) |
| `APUS_PTERODACTYL_WORLD_PATHS` | yes, if pterodactyl | — | Comma-separated top-level archive paths that make up the world, e.g. `world,world_nether,world_the_end` |

Missing a required variable (including the source-specific ones for the chosen
`APUS_SOURCE_TYPE`) aborts with a clear `[apus-ingest] ERROR: <VAR> is required but was not set.`
message on stderr and a non-zero exit, **before** any connector is touched -- see
`IngestConfig.fromEnv` and `IngestMainTest`.

## Exit codes

| Code | Meaning |
|---|---|
| `0` | Bundle written successfully |
| `1` | Configuration error: a required variable is missing/blank, or `APUS_SOURCE_TYPE` names an unimplemented source. Nothing was fetched. |
| `2` | Layout detection failed -- no known world layout (vanilla/bukkit) could be recognised in the fetched data. The error message names the paths that were actually found. |
| `3` | Any other failure while fetching the source or writing the bundle (network error, S3 error, ...) |

## Design notes

**Progress reporting: stdout lines, no HTTP server.** Unlike `runner`, which stays alive for
minutes serving `/progress` to an operator that polls it during a long render, the ingest job is
short-lived and its Kubernetes `Job`/`Pod` status already gives an external reconciler
(`WorldIngestReconciler`, task 6) the coarse state it needs -- `Active`/`Succeeded`/`Failed` plus
timestamps, with no extra moving part in the container. Building an HTTP server here would add a
listening port, a shutdown-ordering concern, and a second thing that can fail, for a job that
typically finishes before a poll loop would notice it existed. Instead, `IngestMain` prints a
`phase=<Pending|Extracting|Transforming|Loading|Succeeded|Failed>` line at each stage transition
and `ThrottledProgressSink` prints a `progress: NN.N% (done/total bytes)` line at most once per
`APUS_PROGRESS_INTERVAL_SECONDS` (plus unconditionally on the final update) while the bundle is
being written -- exactly the periodic-line-plus-end-state shape the phase 2b plan asks for. If a
future reconciler wants finer-grained percentage rather than just phase, it already has one: read
the pod's logs and parse this same line format, the same relationship `TelemetryContractTest`
documents between `runner`'s log-tail route and its `/progress` endpoint, just without needing an
HTTP round trip at all here.

**Minecraft version: environment variable, not parsed from `level.dat`.** The Minecraft version a
world was generated/played under lives in `level.dat`'s NBT `Data.Version.Name` (or, on very old
worlds, isn't present at all and must be inferred from `Data.version`, an integer data-version
with its own separate mapping table). Reading it reliably means either adding an NBT-parsing
dependency (none of the ones already in this project's catalog expose it publicly at the ingest
module's layer -- `bluemap-core` has one internally, but `ingest` deliberately does not depend on
BlueMap) or hand-rolling a gzip+NBT reader for a single, easily-gotten-wrong field, for every
supported Minecraft version's `level.dat` shape. Given that `WorldSource`/`WorldIngest` are
already tenant-authored custom resources, this was decided against in favour of a user-supplied
field: `WorldSource.spec.worlds[].minecraftVersion`, which `IngestJobBuilder` reads for the
matching world selector and passes straight through as `APUS_MC_VERSION` -- the tenant already
knows which version they run, and a wrong guess parsed out of `level.dat` would silently mislabel
a manifest forever instead. `APUS_MC_VERSION` itself stays optional at this image's own contract
level (nothing here requires the operator to have set it), and if unset,
`manifest.minecraftVersion` stays `null`, matching `BundleManifest`'s existing "or `null` if not
known at bundle time" contract rather than inventing a new failure mode. If a future task adds
real NBT parsing, it becomes an *additional* fallback ahead of the environment variable, not a
replacement for it -- `level.dat` is genuinely absent for connectors that only fetch specific
region files, so the variable stays useful either way.

**Source version, not `discover()`, inside the job.** `WorldSourceConnector.discover()` is a
polling operation -- it belongs to `WorldSourceReconciler` (task 6), which resolves "is there a
new version" on a schedule and records the chosen id on `WorldIngest.spec.sourceVersion`. The job
itself only ever calls `fetch()` for the one version it was told to fetch (`APUS_SOURCE_VERSION`);
it never lists what's available. This keeps a single ingest run deterministic and keeps the
"what's new" decision in exactly one place.

## Integration tests

`S3SourceConnectorTest` (`ingest/src/test/java/net/onelitefeather/apus/ingest/connector/`) starts
a real MinIO container via Testcontainers and therefore needs Docker. Like `runner` and
`operator` do for their own container-based tests, it is **not** part of `./gradlew build` or
`check` -- it is excluded from the default `test` task and runs only via the explicit task below:

```bash
./gradlew :ingest:integrationTest
```

Every other test in this module (`IngestConfigTest`, `IngestMainTest`, `ThrottledProgressSinkTest`,
`BundleManifestTest`, `BundleWriterTest`, `LayoutDetectorTest`, `S3ClientTest`,
`PterodactylConnectorTest`, `ArchivesTest`, `TarStreamReaderTest`) runs Docker-free as part of the
routine `./gradlew :ingest:test`.

A full source-to-bundle-to-render end-to-end test (ingest a Bukkit-layout world fixture against
real MinIO, check the resulting manifest, then start a real render against the produced bundle
with the `runner` image) lives in `runner`'s `:runner:integrationTest`
(`IngestRenderContractTest`), not here -- proving the contract between this module's output and
`runner`'s input needs both modules in the same test.
