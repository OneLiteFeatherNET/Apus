#!/usr/bin/env bash
# Variant of runner/entrypoint.sh that adds a render-mask box to the generated map.conf
# before invoking BlueMap. Used only by the lowres-sharding spike (see the report one
# directory up) to split a world into two disjoint, adjacent region sets rendered by
# separate containers into the same storage. Not part of the production image -- the
# real Phase 4 implementation (if built) would use BlueMap's scheduleMapUpdateTask(map,
# regions) API directly rather than render-mask; see the report's "Bewertung" section for
# why render-mask was still the right tool for this spike.
set -euo pipefail

: "${APUS_MAP_ID:?APUS_MAP_ID is required}"
: "${APUS_DIMENSION:?APUS_DIMENSION is required}"
: "${APUS_MC_VERSION:?APUS_MC_VERSION is required}"
: "${APUS_WORLD_S3_URL:?APUS_WORLD_S3_URL is required}"
: "${APUS_MAP_BUCKET:?APUS_MAP_BUCKET is required}"
: "${APUS_S3_ENDPOINT:?APUS_S3_ENDPOINT is required}"
: "${APUS_S3_ACCESS_KEY:?APUS_S3_ACCESS_KEY is required}"
: "${APUS_S3_SECRET_KEY:?APUS_S3_SECRET_KEY is required}"
: "${APUS_RENDER_MASK_MIN_X:?APUS_RENDER_MASK_MIN_X is required}"
: "${APUS_RENDER_MASK_MAX_X:?APUS_RENDER_MASK_MAX_X is required}"
: "${APUS_RENDER_MASK_MIN_Z:?APUS_RENDER_MASK_MIN_Z is required}"
: "${APUS_RENDER_MASK_MAX_Z:?APUS_RENDER_MASK_MAX_Z is required}"

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
