#!/usr/bin/env bash
# Pulls the world data from S3 onto the local volume.
# BlueMap has no hook for custom world sources, so the files must exist locally.
set -euo pipefail

TARGET="${1:?target dir required}"

mc alias set apus "${APUS_S3_ENDPOINT}" "${APUS_S3_ACCESS_KEY}" "${APUS_S3_SECRET_KEY}" >/dev/null

SOURCE="${APUS_WORLD_S3_URL#s3://}"

echo "[apus] syncing world from ${APUS_WORLD_S3_URL} to ${TARGET}"
mkdir -p "${TARGET}"
mc mirror --overwrite --remove "apus/${SOURCE}" "${TARGET}"

if [ ! -d "${TARGET}/region" ]; then
  echo "[apus] ERROR: no region/ directory found in the synced world at ${TARGET}" >&2
  ls -la "${TARGET}" >&2
  exit 3
fi

REGION_COUNT=$(find "${TARGET}/region" -name '*.mca' | wc -l)
echo "[apus] synced ${REGION_COUNT} region files"
