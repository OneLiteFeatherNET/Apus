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

The BlueMapS3Storage release asset is versioned (e.g. `BlueMapS3Storage-1.5.1.jar`,
not `BlueMapS3Storage.jar`), so `releases/latest/download/BlueMapS3Storage.jar`
returns a 404 — the version-pinned URL above is the primary, reproducible way to
fetch it. Bump the `v1.5.1`/`1.5.1` in the URL when a newer release is needed.

To find the current version without assuming `gh` is installed and authenticated,
check the release page directly:
`https://github.com/TheMeinerLP/BlueMapS3Storage/releases/latest`. If `gh` is
available, filter by name instead of indexing into `assets[0]` — the array order
is not guaranteed to stay stable if the release gains more assets (checksums, etc.):

```bash
gh release view --repo TheMeinerLP/BlueMapS3Storage --json assets \
  --jq '.assets[] | select(.name | startswith("BlueMapS3Storage-")) | .url'
```

If the download is unavailable, build it locally instead:

```bash
(cd ../BlueMapS3Storage && ./gradlew shadowJar)
cp ../BlueMapS3Storage/build/libs/BlueMapS3Storage-*.jar runner/vendor/BlueMapS3Storage.jar
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
  -e APUS_MAP_PREFIX=survival \
  -e APUS_S3_ENDPOINT=http://minio:9000 \
  -e APUS_S3_ACCESS_KEY=... \
  -e APUS_S3_SECRET_KEY=... \
  -e APUS_S3_REGION=us-east-1 \
  -e APUS_RENDER_THREADS=4 \
  -e APUS_FORCE_RENDER=false \
  -e APUS_TELEMETRY_PORT=8099 \
  apus/runner:dev
```

Progress is available at `http://localhost:8099/progress` while the render runs.

### Environment variables

This is the full contract the image accepts, and the interface that a future Phase 2
Kubernetes operator will drive (see `docs/superpowers/specs/2026-08-08-apus-design.md`
§7.4).

| Variable | Required | Default | Meaning |
|---|---|---|---|
| `APUS_MAP_ID` | yes | — | Map id, e.g. `overworld`. Used as a path segment (`maps/<id>.conf`); must match `^[a-z0-9_-]+$`, `render-config.sh` rejects anything else with exit code `5` |
| `APUS_DIMENSION` | yes | — | `minecraft:overworld`, `minecraft:the_nether`, `minecraft:the_end` |
| `APUS_MC_VERSION` | yes | — | Minecraft version, e.g. `1.21.10` |
| `APUS_WORLD_S3_URL` | yes | — | Source of the world, e.g. `s3://bundles/worlds/demo/survival/v1/overworld` |
| `APUS_MAP_BUCKET` | yes | — | Destination bucket for the rendered map |
| `APUS_MAP_PREFIX` | no | `.` | Prefix inside the destination bucket |
| `APUS_S3_ENDPOINT` | yes | — | e.g. `http://minio:9000` |
| `APUS_S3_ACCESS_KEY` | yes | — | Access key |
| `APUS_S3_SECRET_KEY` | yes | — | Secret key |
| `APUS_S3_REGION` | no | `us-east-1` | |
| `APUS_RENDER_THREADS` | no | `2` | Passed to BlueMap's `render-thread-count` |
| `APUS_FORCE_RENDER` | no | `false` | `true` adds `-f`/`--force-render` to the BlueMap CLI invocation |
| `APUS_TELEMETRY_PORT` | no | `8099` | Port the telemetry HTTP server binds to |

## Exit codes

Inherited from the BlueMap CLI: `0` success, `1` configuration or IO error,
`2` missing Minecraft resources. `bundle-sync.sh` adds `3` when the synced world
contains no `region/` directory, and `4` when `APUS_WORLD_S3_URL` is missing the
`s3://` prefix or has no path after it. `render-config.sh` adds `5` when
`APUS_MAP_ID` fails validation.

## Integration tests

`runner/src/test/java/net/onelitefeather/apus/runner/` contains two container-based
integration tests (`RenderEndToEndTest`, `TelemetryContractTest`) that start MinIO plus
the `apus/runner` image via Testcontainers and run a full BlueMap render against the
fixture world in `testdata/mini-world`. They are **not** part of `./gradlew build` or
`check` — each run takes minutes and requires the image to already exist. Run them
explicitly:

```bash
./gradlew :telemetry-addon:shadowJar
docker build -f runner/Dockerfile -t apus/runner:dev .
./gradlew :runner:integrationTest
```

Pass `-Dapus.runner.image=<tag>` to `integrationTest` to test a different image tag.

## Telemetry

`/progress` is served by the `apus-telemetry` BlueMap addon (`telemetry-addon/`). All
coupling to BlueMap's internals lives in the `probe` package, behind the
`RenderManagerAccess` seam — `ApusTelemetryAddon` (the entrypoint) only *chooses* between
two implementations, it never talks to BlueMap directly itself.

### The route that works in `apus/runner`: log-tailing

[`LogTailRenderManagerAccess`](../telemetry-addon/src/main/java/net/onelitefeather/apus/telemetry/probe/LogTailRenderManagerAccess.java)
registers itself on BlueMap's own `Logger.global`
(`de.bluecolored.bluemap.core.logger.Logger`/`MultiLogger`) and parses the exact
progress line BlueMap's CLI already logs on its own during a render:

```
updating map 'overworld': 35.208% (ETA: 38 seconds)
```

This is a documented BlueMap extension point, not a hack: the CLI's own `-l/--log-file`
and `-b/--verbose` flags register additional loggers on `Logger.global` the same way
(`Logger.global.put(Logger.file(...))` / `Logger.global.put(Logger.stdOut(true))`,
confirmed by decompiling `BlueMapCLI.class`). An addon is loaded early in `main()`,
before any render starts, so registering here catches every line, including the one that
matters. Verified against a real render of `testdata/mini-world` (`/progress` polled
every second, see `TelemetryContractTest`):

```json
{"state":"idle","currentMap":null,"progress":-1,"etaSeconds":-1,"queuedTasks":-1,"renderThreads":-1,"degraded":false,"description":null}
{"state":"rendering","currentMap":"overworld","progress":0.35554,"etaSeconds":35,"queuedTasks":-1,"renderThreads":-1,"degraded":false,"description":"updating map 'overworld'"}
{"state":"rendering","currentMap":"overworld","progress":0.72232,"etaSeconds":28,"queuedTasks":-1,"renderThreads":-1,"degraded":false,"description":"updating map 'overworld'"}
```

Trade-off: no log line carries queue depth or thread count, so `queuedTasks` and
`renderThreads` are always `-1` (unknown) under this route — BlueMap simply never logs
them, and guessing would be worse than saying so.

### The route BlueMap documents for addons: `plugin()` — verified dead in CLI mode

[`BlueMapRenderManagerAccess`](../telemetry-addon/src/main/java/net/onelitefeather/apus/telemetry/probe/BlueMapRenderManagerAccess.java)
reaches BlueMap's internal `RenderManager` via
`((BlueMapAPIImpl) api).plugin().getRenderManager()` — the route BlueMap's own javadoc
recommends for addons, and richer when it works (it also exposes queue depth and thread
count). **This route is permanently unavailable when BlueMap runs as the CLI jar, which
is how `apus/runner` invokes it.** Decompiling `BlueMapCLI.renderMaps()` (`javap -p -c`
against `de/bluecolored/bluemap/cli/BlueMapCLI.class` inside `cli.jar`) shows the only
call site of `new BlueMapAPIImpl(BlueMapService, Plugin)` pushes a constant `null` for
the `Plugin` argument (`aconst_null` right before the `invokespecial`). BlueMap's own
constructor logic then skips constructing a `RenderManagerImpl` entirely whenever
`Plugin == null` — so **neither** `plugin()` **nor** the newer public
`BlueMapAPIImpl.getRenderManager()` **nor** `getPlugin()` return anything to reflect on;
there is no `RenderManagerImpl` instance in CLI mode to even fall back to reflection
against. The `RenderManager` that actually drives a CLI render is a local variable
inside `BlueMapCLI.renderMaps()`, captured only by two CLI-private inner classes (a
progress-logging `TimerTask` — the one that produces the log line
`LogTailRenderManagerAccess` reads — and a shutdown-hook lambda); it is never published
anywhere `BlueMapAPI` or an addon can reach it directly. This finding is worth keeping
even though the log-tail route made it moot for Apus: it explains *why* a second
implementation exists at all, and it will apply again to any future route that assumes
`plugin()` works.

What was considered and rejected instead of log-tailing: reflecting into the live JVM's
`Timer` named `BlueMap-CLI-Timer` to pull the captured `RenderManager` field out of
BlueMap's private `TimerTask` subclasses. That would have meant reflection into
`java.util.Timer` internals plus an anonymous class's synthetic captured-variable
field — two layers removed from anything BlueMap documents or versions, with no stable
field name guaranteed. The log-tail route is both more stable (a log message format is
far more likely to stay compatible across BlueMap versions than an internal field name)
and, unlike the `Timer` idea, itself a documented extension point.

### Wiring

`ApusTelemetryAddon` registers the log-tail route unconditionally and immediately after
starting the HTTP server — independent of whether `BlueMapAPI.onEnable` ever fires, since
that's the only route guaranteed to work in CLI mode. If `BlueMapAPI.onEnable` does fire
and the `plugin()` route resolves (e.g. on a server-plugin platform, not CLI), it is
preferred for its richer data; `onDisable` falls back to the log-tail route rather than to
nothing. If even registering the log-tail route fails (a hypothetical future BlueMap
version removing `Logger.global`), `/progress` reports `degraded: true` instead of
`starting` forever — a small sentinel `RenderManagerAccess` in `ApusTelemetryAddon` whose
methods throw, letting `RenderProgressProbe`'s existing failure handling do the rest
without that class needing to change.

`runner/src/test/java/net/onelitefeather/apus/runner/TelemetryContractTest.java` proves
this end-to-end against a real render and must be run against every BlueMap version Apus
claims to support before each release — it is the regression test that will catch either
route breaking on a BlueMap upgrade (a changed log line format, or `Logger.global`
disappearing).
