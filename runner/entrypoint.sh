#!/usr/bin/env bash
set -euo pipefail

# Aborts immediately with a clear message if the named environment variable is unset or empty.
require_env() {
  local name="$1"
  if [ -z "${!name:-}" ]; then
    echo "${name} is required" >&2
    exit 1
  fi
}

require_env APUS_MAP_ID
require_env APUS_DIMENSION
require_env APUS_MC_VERSION
require_env APUS_WORLD_S3_URL
require_env APUS_MAP_BUCKET
require_env APUS_S3_ENDPOINT
require_env APUS_S3_ACCESS_KEY
require_env APUS_S3_SECRET_KEY

CONFIG_DIR=/work/config
WORLD_DIR=/work/world

/opt/apus/bin/bundle-sync.sh "${WORLD_DIR}"
/opt/apus/bin/render-config.sh "${CONFIG_DIR}"

ARGS=(-c "${CONFIG_DIR}" -r -m "${APUS_MAP_ID}" -v "${APUS_MC_VERSION}")

if [ "${APUS_FORCE_RENDER:-false}" = "true" ]; then
  ARGS+=(-f)
fi

echo "[apus] starting BlueMap: ${ARGS[*]}"
exec java -jar /opt/bluemap/cli.jar "${ARGS[@]}"
