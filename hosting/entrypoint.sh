#!/usr/bin/env bash
set -euo pipefail

: "${APUS_S3_ENDPOINT:?APUS_S3_ENDPOINT is required}"
: "${APUS_S3_ACCESS_KEY:?APUS_S3_ACCESS_KEY is required}"
: "${APUS_S3_SECRET_KEY:?APUS_S3_SECRET_KEY is required}"

APUS_S3_REGION="${APUS_S3_REGION:-us-east-1}"
APUS_WEBSERVER_PORT="${APUS_WEBSERVER_PORT:-8100}"
export APUS_S3_REGION APUS_WEBSERVER_PORT

CONFIG_SRC=/work/config-src
CONFIG_DIR=/work/config
PACKS_SRC=/opt/apus/packs

/opt/apus/bin/config-sync.sh "${CONFIG_SRC}" "${CONFIG_DIR}" "${PACKS_SRC}"

# -g (re)generates the static web-app shell (index.html/assets/settings.json) into webroot;
# -w starts the webserver. Both run in the same process so the exec below still gets SIGTERM.
#
# Verified against BlueMap 5.23: -w alone only serves whatever already exists under webroot
# -- with no prior -g run, even /settings.json (a file -g generates, not something the
# webserver computes) 404s, and so does /. -gw combined generates the webapp shell and then
# starts serving it, which is what a stateless pod needs on every start since nothing about
# /work persists across restarts. See hosting/README.md for the full verification trail.
ARGS=(-c "${CONFIG_DIR}" -gw)

echo "[apus] starting BlueMap webserver: ${ARGS[*]}"
exec java -jar /opt/bluemap/cli.jar "${ARGS[@]}"
