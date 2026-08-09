#!/usr/bin/env bash
# Variant of runner/entrypoint.sh that adds a render-mask box to the generated map.conf
# before invoking BlueMap. Used only by the lowres-sharding spike (see the report one
# directory up) to split a world into two disjoint, adjacent region sets rendered by
# separate containers into the same storage. Not part of the production image -- the
# real Phase 4 implementation (if built) would use BlueMap's scheduleMapUpdateTask(map,
# regions) API directly rather than render-mask; see the report's "Bewertung" section for
# why render-mask was still the right tool for this spike.
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
require_env APUS_RENDER_MASK_MIN_X
require_env APUS_RENDER_MASK_MAX_X
require_env APUS_RENDER_MASK_MIN_Z
require_env APUS_RENDER_MASK_MAX_Z

CONFIG_DIR=/work/config
WORLD_DIR=/work/world

/opt/apus/bin/bundle-sync.sh "${WORLD_DIR}"
/opt/apus/bin/render-config.sh "${CONFIG_DIR}"

# min-y/max-y cover the full Minecraft 1.21 world height, so only X/Z are actually
# restricted -- the split under test is a region boundary (a vertical wall spanning the
# entire build height), never a horizontal one.
cat >> "${CONFIG_DIR}/maps/${APUS_MAP_ID}.conf" <<MASKCONF
render-mask: [
  { type: "box", min-x: ${APUS_RENDER_MASK_MIN_X}, max-x: ${APUS_RENDER_MASK_MAX_X}, min-y: -128, max-y: 384, min-z: ${APUS_RENDER_MASK_MIN_Z}, max-z: ${APUS_RENDER_MASK_MAX_Z} }
]
MASKCONF

ARGS=(-c "${CONFIG_DIR}" -r -m "${APUS_MAP_ID}" -v "${APUS_MC_VERSION}")

if [ "${APUS_FORCE_RENDER:-false}" = "true" ]; then
  ARGS+=(-f)
fi

echo "[apus-spike] render-mask x[${APUS_RENDER_MASK_MIN_X},${APUS_RENDER_MASK_MAX_X}] z[${APUS_RENDER_MASK_MIN_Z},${APUS_RENDER_MASK_MAX_Z}]"
echo "[apus-spike] starting BlueMap: ${ARGS[*]}"
exec java -jar /opt/bluemap/cli.jar "${ARGS[@]}"
