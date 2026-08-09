#!/usr/bin/env bash
set -euo pipefail

: "${APUS_MAP_ID:?APUS_MAP_ID is required}"
: "${APUS_DIMENSION:?APUS_DIMENSION is required}"
: "${APUS_MC_VERSION:?APUS_MC_VERSION is required}"
: "${APUS_WORLD_S3_URL:?APUS_WORLD_S3_URL is required}"
: "${APUS_MAP_BUCKET:?APUS_MAP_BUCKET is required}"
: "${APUS_S3_ENDPOINT:?APUS_S3_ENDPOINT is required}"
: "${APUS_S3_ACCESS_KEY:?APUS_S3_ACCESS_KEY is required}"
: "${APUS_S3_SECRET_KEY:?APUS_S3_SECRET_KEY is required}"

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
