#!/usr/bin/env bash
set -euo pipefail

# Required configuration is validated by IngestMain itself, before it does any work (see
# IngestConfig.fromEnv) -- this entrypoint stays a thin, replaceable launch line, the same
# separation of concerns runner/entrypoint.sh uses between shell-level checks and the actual
# render process.
#
# No secret ever appears here: JAVA_OPTS carries only JVM tuning flags, and every credential
# IngestMain needs is read straight out of its own environment in-process, never passed as a
# command-line argument to this or any other process (unlike `mc alias set` in
# runner/bin/bundle-sync.sh, which is why that script writes a config file instead).
echo "[apus-ingest] starting"
# shellcheck disable=SC2086
exec java ${JAVA_OPTS:-} -jar /opt/apus/ingest.jar
