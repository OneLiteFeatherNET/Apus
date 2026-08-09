#!/usr/bin/env bash
# Pulls the world data from S3 onto the local volume.
# BlueMap has no hook for custom world sources, so the files must exist locally.
set -euo pipefail

TARGET="${1:?target dir required}"

if [[ "${APUS_WORLD_S3_URL}" != s3://* ]]; then
  echo "[apus] ERROR: APUS_WORLD_S3_URL must start with s3://, got: ${APUS_WORLD_S3_URL}" >&2
  exit 4
fi

SOURCE="${APUS_WORLD_S3_URL#s3://}"

if [ -z "${SOURCE}" ]; then
  echo "[apus] ERROR: APUS_WORLD_S3_URL has no path after s3://, refusing to mirror the alias root" >&2
  exit 4
fi

# Escape a value for embedding in a JSON string (quotes and backslashes).
json_escape() {
  local s="$1"
  s="${s//\\/\\\\}"
  s="${s//\"/\\\"}"
  printf '%s' "${s}"
}

# Write mc's alias config directly instead of running `mc alias set <key> <secret>`.
# Two reasons, not one:
#  - `mc alias set` puts the access key and secret key on this process's command
#    line for its whole lifetime, readable via /proc/<pid>/cmdline.
#  - The alternative of embedding credentials in a MC_HOST_<alias> URL requires
#    percent-encoding them, but `mc` does NOT decode percent-encoded userinfo in
#    MC_HOST_<alias> — it sends the encoded form verbatim as the AWS SigV4 access
#    key/secret. That silently breaks authentication for any credential containing
#    a character that needs encoding (e.g. secret keys with '+' or '/', which are
#    common in base64-style secrets). A previous version of this script did that
#    and was reverted after a live test showed the SigV4 Credential header
#    containing a literal percent-escape instead of the real key.
# A config file has no such encoding step: accessKey/secretKey are plain JSON
# strings, so any character mc's own client library accepts is passed through
# unchanged.
MC_CONFIG_DIR="$(mktemp -d /work/.mc-config.XXXXXX)"
trap 'rm -rf "${MC_CONFIG_DIR}"' EXIT
chmod 700 "${MC_CONFIG_DIR}"

cat > "${MC_CONFIG_DIR}/config.json" <<EOF
{
	"version": "10",
	"aliases": {
		"apus": {
			"url": "$(json_escape "${APUS_S3_ENDPOINT}")",
			"accessKey": "$(json_escape "${APUS_S3_ACCESS_KEY}")",
			"secretKey": "$(json_escape "${APUS_S3_SECRET_KEY}")",
			"api": "S3v4",
			"path": "auto"
		}
	}
}
EOF
chmod 600 "${MC_CONFIG_DIR}/config.json"

echo "[apus] syncing world from ${APUS_WORLD_S3_URL} to ${TARGET}"
mkdir -p "${TARGET}"
mc --config-dir "${MC_CONFIG_DIR}" mirror --overwrite --remove "apus/${SOURCE}" "${TARGET}"

if [ ! -d "${TARGET}/region" ]; then
  echo "[apus] ERROR: no region/ directory found in the synced world at ${TARGET}" >&2
  ls -la "${TARGET}" >&2
  exit 3
fi

REGION_COUNT=$(find "${TARGET}/region" -name '*.mca' | wc -l)
echo "[apus] synced ${REGION_COUNT} region files"
