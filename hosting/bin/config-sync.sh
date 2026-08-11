#!/usr/bin/env bash
# Copies the read-only ConfigMap-mounted BlueMap configuration into a writable directory,
# then fills in exactly what a ConfigMap cannot safely carry -- nothing more.
#
# A ConfigMap is readable by anything in the namespace, so the operator's
# BlueMapConfigBuilder.buildForHosting() deliberately never writes S3 credentials into the
# storages/*.conf files it generates (see that class's javadoc in operator/). This script
# appends them here, from this pod's environment, into a copy that lives on a writable
# volume -- the mounted ConfigMap itself stays untouched and read-only throughout.
#
# Everything else this script fills in (endpoint-url/region on a storage file, a whole
# missing webserver.conf or core.conf) is a *gap-filling* default, not an override: it is
# only written when the corresponding key or file is absent. In the normal deployment path
# the operator already writes all of those, so this never fires; it exists so the image
# stays usable stand-alone (e.g. for local testing without a running operator) without ever
# clobbering a value the operator deliberately chose.
#
# Credentials are appended via heredoc/redirection, never as a command-line argument to any
# program -- see runner/bin/bundle-sync.sh's comment for why that matters: every argument on
# a process's command line is readable by any other process in the same PID namespace via
# /proc/<pid>/cmdline.
set -euo pipefail

SRC="${1:?source config dir required}"
DEST="${2:?destination config dir required}"
PACKS_SRC="${3:?packs source dir required}"

if [ ! -d "${SRC}" ]; then
  echo "[apus] ERROR: config source ${SRC} does not exist -- mount the BlueMapHosting ConfigMap there" >&2
  exit 6
fi

if [ -z "$(find "${SRC}" -type f -print -quit)" ]; then
  echo "[apus] ERROR: config source ${SRC} is empty -- nothing to host" >&2
  exit 6
fi

# Escape a value for embedding in a HOCON quoted string (backslash first, then quote) --
# mirrors runner/bin/render-config.sh's hocon_escape.
hocon_escape() {
  local s="$1"
  s="${s//\\/\\\\}"
  s="${s//\"/\\\"}"
  printf '%s' "${s}"
}

rm -rf "${DEST}"
mkdir -p "${DEST}"
cp -r "${SRC}/." "${DEST}/"
# The copy inherits the mounted ConfigMap's read-only permissions; BlueMap needs to write
# generated files (webapp assets, sorting caches) into this directory at runtime.
chmod -R u+rwX "${DEST}"

mkdir -p "${DEST}/storages"
shopt -s nullglob
for storage_conf in "${DEST}"/storages/*.conf; do
  {
    echo ""
    echo "# Injected by the hosting entrypoint at pod start; deliberately absent from the"
    echo "# mounted ConfigMap because a ConfigMap is readable by anything in the namespace."
    echo "access-key-id: \"$(hocon_escape "${APUS_S3_ACCESS_KEY}")\""
    echo "secret-access-key: \"$(hocon_escape "${APUS_S3_SECRET_KEY}")\""
    if ! grep -q '^endpoint-url:' "${storage_conf}"; then
      echo "endpoint-url: \"$(hocon_escape "${APUS_S3_ENDPOINT}")\""
    fi
    if ! grep -q '^region:' "${storage_conf}"; then
      echo "region: \"$(hocon_escape "${APUS_S3_REGION}")\""
    fi
  } >> "${storage_conf}"
  # Now contains the S3 secret key; must not be world-readable.
  chmod 600 "${storage_conf}"
done
shopt -u nullglob

if [ ! -f "${DEST}/webserver.conf" ]; then
  echo "[apus] WARN: no webserver.conf in the mounted config, writing a default for port ${APUS_WEBSERVER_PORT}" >&2
  cat > "${DEST}/webserver.conf" <<EOF
enabled: true
webroot: "web"
port: ${APUS_WEBSERVER_PORT}
sse-enabled: true
EOF
fi

if [ ! -f "${DEST}/core.conf" ]; then
  cat > "${DEST}/core.conf" <<EOF
accept-download: false
data: "/work/data"
metrics: false
scan-for-mod-resources: false
EOF
fi

# The BlueMapS3Storage addon jar is baked into the image, not part of the ConfigMap --
# it's a binary plugin, not configuration. Re-populate it into the fresh writable dir on
# every start, since the wipe above would otherwise drop it.
mkdir -p "${DEST}/packs"
cp "${PACKS_SRC}"/*.jar "${DEST}/packs/"

echo "[apus] prepared writable BlueMap config at ${DEST}"
