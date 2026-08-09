# Apus Hosting Image

Serves already-rendered BlueMap maps from S3, over HTTP, for as long as the pod lives.
Uses the same BlueMap CLI as `runner/`, but in webserver mode (`-w`) instead of render mode
(`-r`), reading tiles directly out of S3 through the `BlueMapS3Storage` addon instead of
writing them.

Unlike `runner/`, which is a one-shot job configured entirely through environment variables,
this image runs as a long-lived Deployment and gets its map/storage configuration from a
**mounted ConfigMap** (built by `BlueMapConfigBuilder.buildForHosting()` in `operator/`) --
several maps and a `webserver.conf` don't fit an environment-variable contract the way a
single render does.

## Build

```bash
docker build -f hosting/Dockerfile -t apus/hosting:dev hosting
```

The build context is `hosting/` itself -- this image has no dependency on anything else in
the repository; both the BlueMap CLI and the `BlueMapS3Storage` addon are fetched from their
GitHub releases in the Dockerfile's `fetch` stage.

## Run

The container expects two things to be mounted/set at start:

1. A BlueMap configuration directory at `/work/config-src`, **read-only** -- in production
   this is the `BlueMapHosting` ConfigMap the operator mounts; for manual testing, any
   directory containing `webserver.conf`, `maps/*.conf` and `storages/*.conf` in BlueMap's
   own format works (see `operator/src/main/java/net/onelitefeather/apus/operator/map/BlueMapConfigBuilder.java#buildForHosting`
   for the exact shape the operator produces).
2. S3 credentials via environment variables (never via the ConfigMap -- a ConfigMap is
   readable by anything in the namespace, so the operator's config builder never writes
   credentials into it).

```bash
docker run --rm -p 8100:8100 \
  -v "$(pwd)/my-hosting-config:/work/config-src:ro" \
  -e APUS_S3_ENDPOINT=http://minio:9000 \
  -e APUS_S3_ACCESS_KEY=... \
  -e APUS_S3_SECRET_KEY=... \
  -e APUS_S3_REGION=us-east-1 \
  -e APUS_WEBSERVER_PORT=8100 \
  apus/hosting:dev
```

### Environment variables

| Variable | Required | Default | Meaning |
|---|---|---|---|
| `APUS_S3_ENDPOINT` | yes | -- | e.g. `http://minio:9000` |
| `APUS_S3_ACCESS_KEY` | yes | -- | Access key |
| `APUS_S3_SECRET_KEY` | yes | -- | Secret key |
| `APUS_S3_REGION` | no | `us-east-1` | |
| `APUS_WEBSERVER_PORT` | no | `8100` | Only used as a fallback if the mounted config has no `webserver.conf` at all; the normal (operator-driven) path always has one, already carrying the real port |

Checked before anything else runs, in that order -- an unset required variable aborts
immediately with a non-zero exit and a message naming the missing variable, before the
config directory is even touched.

## Why the entrypoint copies the config before touching it

`/work/config-src` is a **read-only** mount (a Kubernetes ConfigMap volume). The operator's
`BlueMapConfigBuilder.buildForHosting()` deliberately never writes S3 credentials into the
`storages/*.conf` files it puts in that ConfigMap -- a ConfigMap is readable by anything in
the namespace, so credentials must never end up in one. `hosting/bin/config-sync.sh` copies
the whole tree into a writable directory (`/work/config`) first, then appends
`access-key-id`/`secret-access-key` to every `storages/*.conf` file found there, using the
env vars above. Everything else it does (filling in `endpoint-url`/`region` on a storage file
if missing, writing a default `webserver.conf`/`core.conf` if the mounted config has none) is
gap-filling only, never an override -- see the comment at the top of that script.

Credentials are appended via a heredoc/redirection, **never** as a command-line argument to
any program. See `runner/bin/bundle-sync.sh`'s comment for why: every argument on a process's
command line is readable by any other process in the same PID namespace via
`/proc/<pid>/cmdline`. `mc`'s own `alias set` subcommand has exactly this problem, which is
why `bundle-sync.sh` writes an `mc` config file instead of invoking `mc alias set` with the
secret as an argument. This image never invokes anything with the secret as an argument
either -- it only ever appends it to a file.

## Readiness path (for Task 4's Kubernetes probes)

**`GET /settings.json`** is the right path for both liveness and readiness checks.

Determined empirically against a real BlueMap 5.23 CLI, not assumed:

- `GET /settings.json` returns HTTP `200` with a JSON body once the webserver has started
  *and* run its webapp-generation step (`-g`) at least once. Before `-g` has run, it 404s --
  it is a generated static file, not something the webserver computes on the fly.
- Its body includes `"maps": [...]`, listing every map id the currently-loaded config knows
  about (e.g. `{"maps":["overworld"], ...}`) -- so a `200` here is proof both that the
  webserver process is accepting connections *and* that it parsed the mounted map configs
  successfully. This is why the entrypoint runs BlueMap with the combined `-gw` flag: `-w`
  alone never populates `/settings.json` (or `/`) at all, only files that already exist under
  `webroot` (see `entrypoint.sh`'s comment for the full trail).
- It does **not** depend on any map's underlying tile data actually existing in S3 -- it
  reflects configuration, not storage content. That is exactly the right property for a
  Kubernetes probe: it fails when the process or its config is broken, not when a particular
  map's render happens to be incomplete.
- Per-map endpoints (e.g. `/maps/<id>/settings.json`, `/maps/<id>/live/markers.json`) *do*
  depend on storage content and 404 until actual tile data exists there -- unsuitable for a
  generic pod-level probe (would make the probe depend on the health of a specific map's
  storage rather than the pod itself), but useful for smoke-testing that a *specific* map
  serves real data (see the "Verified end-to-end" section below).

Suggested Kubernetes probe config for Task 4: `httpGet` on path `/settings.json`, port
`$APUS_WEBSERVER_PORT` (default `8100`), expecting `200`.

## Verified end-to-end

Built and run against a real MinIO instance seeded with a map rendered by `runner/` from
`testdata/mini-world` (BlueMap 5.23, `BlueMapS3Storage` v1.5.1, `themeinerlp:s3` storage
type):

1. `apus/hosting:dev` started with no S3 env vars set at all -- exited non-zero immediately
   with `APUS_S3_ENDPOINT is required`, before touching `/work/config-src` or starting Java.
2. `apus/hosting:dev` started against MinIO, with a hand-built config directory (`webserver.conf`,
   `maps/overworld.conf`, `storages/overworld.conf` -- the last one *without* credentials, in
   the same shape `buildForHosting()` produces) mounted read-only at `/work/config-src`.
3. `GET http://localhost:8100/settings.json` returned `200` with `"maps":["overworld"]`.
4. `GET http://localhost:8100/maps/overworld/settings.json` returned `200` with tile-set
   metadata for the map actually rendered into MinIO by `runner/` -- proof the webserver is
   reading real tile data back out of S3 through `BlueMapS3Storage`, not just echoing static
   config.

See `.superpowers/sdd/2026-08-09-phase-3-hosting/task-2-report.md` for the exact commands and
raw output of this run.
