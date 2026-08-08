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
