#!/usr/bin/env bash
# Pulls the world data from S3 onto the local volume.
# BlueMap has no hook for custom world sources, so the files must exist locally.
set -euo pipefail

TARGET="${1:?target dir required}"

# Percent-encode a string for safe use inside a URL userinfo component
# (mc's MC_HOST_<alias> credentials may contain characters like @, :, / or %).
urlencode() {
  local string="$1"
  local length="${#string}"
  local encoded=""
  local i c
  for (( i = 0; i < length; i++ )); do
    c="${string:i:1}"
    case "$c" in
      [a-zA-Z0-9.~_-]) encoded+="$c" ;;
      *) printf -v hex '%%%02X' "'${c}"
         encoded+="$hex" ;;
    esac
  done
  printf '%s' "${encoded}"
}

if [[ "${APUS_WORLD_S3_URL}" != s3://* ]]; then
  echo "[apus] ERROR: APUS_WORLD_S3_URL must start with s3://, got: ${APUS_WORLD_S3_URL}" >&2
  exit 4
fi

SOURCE="${APUS_WORLD_S3_URL#s3://}"

if [ -z "${SOURCE}" ]; then
  echo "[apus] ERROR: APUS_WORLD_S3_URL has no path after s3://, refusing to mirror the alias root" >&2
  exit 4
fi

# Pass credentials via MC_HOST_<alias> instead of `mc alias set ... <key> <secret>`
# so they never appear in this process's command line (visible via /proc/<pid>/cmdline).
SCHEME="${APUS_S3_ENDPOINT%%://*}"
HOSTPART="${APUS_S3_ENDPOINT#*://}"
ENCODED_ACCESS_KEY="$(urlencode "${APUS_S3_ACCESS_KEY}")"
ENCODED_SECRET_KEY="$(urlencode "${APUS_S3_SECRET_KEY}")"
export MC_HOST_apus="${SCHEME}://${ENCODED_ACCESS_KEY}:${ENCODED_SECRET_KEY}@${HOSTPART}"

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
