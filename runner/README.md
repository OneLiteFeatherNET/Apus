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
  -e APUS_S3_ENDPOINT=http://minio:9000 \
  -e APUS_S3_ACCESS_KEY=... \
  -e APUS_S3_SECRET_KEY=... \
  apus/runner:dev
```

Progress is available at `http://localhost:8099/progress` while the render runs.

## Exit codes

Inherited from the BlueMap CLI: `0` success, `1` configuration or IO error,
`2` missing Minecraft resources. `bundle-sync.sh` adds `3` when the synced world
contains no `region/` directory, and `4` when `APUS_WORLD_S3_URL` is missing the
`s3://` prefix or has no path after it.

## Telemetry

`/progress` is served by the `apus-telemetry` BlueMap addon (`telemetry-addon/`). All
coupling to BlueMap's internals lives in one class,
[`BlueMapRenderManagerAccess`](../telemetry-addon/src/main/java/net/onelitefeather/apus/telemetry/probe/BlueMapRenderManagerAccess.java),
behind the `RenderManagerAccess` seam. It reaches BlueMap's internal `RenderManager` via
`((BlueMapAPIImpl) api).plugin().getRenderManager()` — the route BlueMap's own javadoc
recommends for addons. No reflection.

**Known limitation: this route is permanently unavailable when BlueMap runs as the CLI
jar, which is how `apus/runner` invokes it.** Decompiling `BlueMapCLI.renderMaps()`
(`javap -p -c` against `de/bluecolored/bluemap/cli/BlueMapCLI.class` inside `cli.jar`)
shows the only call site of `new BlueMapAPIImpl(BlueMapService, Plugin)` pushes a
constant `null` for the `Plugin` argument (`aconst_null` right before the
`invokespecial`). BlueMap's own constructor logic then skips constructing a
`RenderManagerImpl` entirely whenever `Plugin == null` — so **neither** `plugin()`
**nor** the newer public `BlueMapAPIImpl.getRenderManager()` **nor** `getPlugin()`
return anything to reflect on; there is no `RenderManagerImpl` instance in CLI mode to
even fall back to reflection against. The `RenderManager` that actually drives a CLI
render is a local variable inside `BlueMapCLI.renderMaps()`, captured only by two
CLI-private inner classes (a progress-logging `TimerTask` and a shutdown-hook lambda) —
it is never published anywhere `BlueMapAPI` or the addon can reach it.

Practical effect, confirmed against a real render of the `testdata/mini-world` fixture:
`/progress` stays at `{"state":"starting","progress":-1,...,"degraded":false,
"description":"waiting for BlueMap API"}` for the entire render and only ever changes
once, from the addon's `onEnable` callback logging
`no plugin instance available; progress will report as unknown` — after which
`RenderProgressProbe` still reports `starting`, not `unknown`/`degraded`, because it
cannot distinguish "not yet enabled" from "enabled but no plugin instance exists".
`state` never reaches `rendering`, even though the BlueMap CLI's own log output shows
real render progress (`updating map 'overworld': 43.512% (ETA: 51 seconds)`) at the
same time.

`runner/src/test/java/net/onelitefeather/apus/runner/TelemetryContractTest.java` proves
this end-to-end against a real render and is currently `@Disabled` with this finding as
the reason — it is not a flaky test, it fails deterministically today. Do not weaken its
assertions to force it green; **re-enable it** once one of the following is done, and run
it against every BlueMap version Apus claims to support before each release:

1. **Upstream fix (preferred):** BlueMap's CLI already builds the exact `RenderManager`
   it needs locally in `renderMaps()`. A small upstream change to either pass a
   minimal `Plugin` wrapping it, or to give `BlueMapAPIImpl`/`RenderManagerImpl` a
   constructor overload that accepts a `RenderManager` directly (no `Plugin` required),
   would close this gap for every CLI-based addon, not just Apus.
2. **Switch the runner off CLI-only mode** onto a code path where BlueMap does construct
   a real `Plugin` (i.e. embedding BlueMap the way a server plugin does). This is a much
   larger architectural change than swapping one class.
3. **Track progress independently of `RenderManager`,** e.g. by comparing
   `BmMap.getMapTileState()`/`getMapRegionState()` against the world's known region
   files. Reachable without a `Plugin` (via `BlueMapMapImpl.map()`, itself reachable from
   `BlueMapAPI.getMap(id)`), but it duplicates render-progress accounting BlueMap already
   does internally and does not cover queue depth or ETA. Not implemented.

What was **not** done, and why: reflecting into the live JVM's `Timer` named
`BlueMap-CLI-Timer` to pull the captured `RenderManager` field out of BlueMap's private
`TimerTask` subclasses was considered and rejected. That is reflection into
`java.util.Timer` internals plus an anonymous class's synthetic captured-variable field —
two layers removed from anything BlueMap documents or versions, with no stable field
name guaranteed, and it would defeat the entire point of concentrating BlueMap coupling
in one seam class.
