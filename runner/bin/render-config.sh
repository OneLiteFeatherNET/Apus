#!/usr/bin/env bash
# Generates the complete BlueMap configuration from environment variables.
# Users of Apus never write HOCON by hand; this is where it comes from.
set -euo pipefail

CONFIG_DIR="${1:?config dir required}"

mkdir -p "${CONFIG_DIR}/maps" "${CONFIG_DIR}/storages" "${CONFIG_DIR}/packs"

cat > "${CONFIG_DIR}/core.conf" <<EOF
accept-download: true
data: "/work/data"
render-thread-count: ${APUS_RENDER_THREADS}
metrics: false
scan-for-mod-resources: false
EOF

cat > "${CONFIG_DIR}/maps/${APUS_MAP_ID}.conf" <<EOF
world: "/work/world"
dimension: "${APUS_DIMENSION}"
name: "${APUS_MAP_ID}"
sorting: 0
storage: "s3"
render-edges: true
EOF

# The storage id is the file name; map.conf references it via storage: "s3".
cat > "${CONFIG_DIR}/storages/s3.conf" <<EOF
storage-type: "themeinerlp:s3"
bucket-name: "${APUS_MAP_BUCKET}"
region: "${APUS_S3_REGION}"
access-key-id: "${APUS_S3_ACCESS_KEY}"
secret-access-key: "${APUS_S3_SECRET_KEY}"
endpoint-url: "${APUS_S3_ENDPOINT}"
compression: "gzip"
root-path: "${APUS_MAP_PREFIX}"
force-path-style: true
EOF

echo "[apus] wrote BlueMap config to ${CONFIG_DIR}"
