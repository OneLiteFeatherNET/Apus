#!/usr/bin/env bash
# Generates the complete BlueMap configuration from environment variables.
# Users of Apus never write HOCON by hand; this is where it comes from.
set -euo pipefail

CONFIG_DIR="${1:?config dir required}"

# Defaults mirrored from the ENV block in runner/Dockerfile. Kept here too so the script
# does not depend on running inside that exact image -- consistent with how
# entrypoint.sh already defaults APUS_FORCE_RENDER.
APUS_RENDER_THREADS="${APUS_RENDER_THREADS:-2}"
APUS_S3_REGION="${APUS_S3_REGION:-us-east-1}"
APUS_MAP_PREFIX="${APUS_MAP_PREFIX:-.}"

# APUS_MAP_ID becomes a path segment below (maps/${APUS_MAP_ID}.conf). In Phase 2 this
# value comes from a Kubernetes resource, so it must be validated before use -- a value
# containing '/' or '..' could otherwise escape the config directory.
if ! [[ "${APUS_MAP_ID}" =~ ^[a-z0-9_-]+$ ]]; then
  echo "[apus] ERROR: APUS_MAP_ID must match ^[a-z0-9_-]+\$, got: ${APUS_MAP_ID}" >&2
  exit 5
fi

# Escape a value for embedding in a HOCON quoted string (backslash first, then quote).
# Without this, a secret containing '"' or '\' produces broken HOCON and the render
# aborts -- see bundle-sync.sh's json_escape() for the same rationale applied to JSON.
hocon_escape() {
  local s="$1"
  s="${s//\\/\\\\}"
  s="${s//\"/\\\"}"
  printf '%s' "${s}"
}

mkdir -p "${CONFIG_DIR}/maps" "${CONFIG_DIR}/storages" "${CONFIG_DIR}/packs"

cat > "${CONFIG_DIR}/core.conf" <<EOF
accept-download: true
data: "/work/data"
render-thread-count: $(hocon_escape "${APUS_RENDER_THREADS}")
metrics: false
scan-for-mod-resources: false
EOF

cat > "${CONFIG_DIR}/maps/${APUS_MAP_ID}.conf" <<EOF
world: "/work/world"
dimension: "$(hocon_escape "${APUS_DIMENSION}")"
name: "$(hocon_escape "${APUS_MAP_ID}")"
sorting: 0
storage: "s3"
render-edges: true
EOF

# The storage id is the file name; map.conf references it via storage: "s3".
cat > "${CONFIG_DIR}/storages/s3.conf" <<EOF
storage-type: "themeinerlp:s3"
bucket-name: "$(hocon_escape "${APUS_MAP_BUCKET}")"
region: "$(hocon_escape "${APUS_S3_REGION}")"
access-key-id: "$(hocon_escape "${APUS_S3_ACCESS_KEY}")"
secret-access-key: "$(hocon_escape "${APUS_S3_SECRET_KEY}")"
endpoint-url: "$(hocon_escape "${APUS_S3_ENDPOINT}")"
compression: "gzip"
root-path: "$(hocon_escape "${APUS_MAP_PREFIX}")"
force-path-style: true
EOF
# Contains the S3 secret key; must not be world-readable.
chmod 600 "${CONFIG_DIR}/storages/s3.conf"

echo "[apus] wrote BlueMap config to ${CONFIG_DIR}"
