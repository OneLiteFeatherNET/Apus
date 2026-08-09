#!/usr/bin/env bash
# Orchestrates the lowres-sharding spike end to end:
#   1. A private docker network + MinIO container (no published ports).
#   2. Seeds testdata/mini-world into a "bundles" bucket.
#   3. Reference render: the whole world, one process, no mask -> maps-reference.
#   4. N (default 3) concurrent-parallel renders: the world split at the z=512 region
#      boundary into a "south" shard (r.-1.0 + r.0.0) and a "north" shard (r.-1.1 +
#      r.0.1) via render-mask, both writing into the same fresh bucket at the same time
#      -> maps-parallel-1..N.
#   5. One sequential control render: the same two shards, same shared bucket, but
#      south fully completes before north starts (no race window) -> maps-sequential.
#
# Requires: docker, and apus/runner:dev already built (see runner/README.md). Run from
# anywhere; paths are resolved relative to this script and the repo root.
#
# Usage: ./run-spike.sh [repeats]
#
# Does not tear itself down -- see ./teardown.sh once you're done inspecting the
# buckets. Comparison/analysis is a separate step: see compare_tiles.py.
set -euo pipefail

REPEATS="${1:-3}"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../../../.." && pwd)"
FIXTURE_DIR="${REPO_ROOT}/testdata/mini-world"
NET=apus-spike-net
MC_IMAGE=minio/mc:RELEASE.2025-08-13T08-35-41Z
MINIO_IMAGE=minio/minio:RELEASE.2024-11-07T00-52-20Z
S3_USER=apusspike
S3_PASS=apusspikesecret

mkdir -p "${SCRIPT_DIR}/logs"

echo "[spike] network + MinIO (no published ports)"
docker network create "$NET" >/dev/null 2>&1 || true
docker rm -f apus-spike-minio >/dev/null 2>&1 || true
docker run -d --name apus-spike-minio \
  --network "$NET" --network-alias minio \
  -e MINIO_ROOT_USER="$S3_USER" -e MINIO_ROOT_PASSWORD="$S3_PASS" \
  "$MINIO_IMAGE" server /data >/dev/null
sleep 3

echo "[spike] seeding ${FIXTURE_DIR} into bundles/worlds/demo/v1"
docker run --rm --network "$NET" \
  -v "${FIXTURE_DIR}:/fixture:ro" \
  --entrypoint /bin/sh "$MC_IMAGE" -c "
mc alias set m http://minio:9000 ${S3_USER} ${S3_PASS} >/dev/null &&
mc mb --ignore-existing m/bundles &&
mc mb --ignore-existing m/maps-reference &&
mc mirror --quiet /fixture m/bundles/worlds/demo/v1 &&
echo SEEDED
"

REGION_COUNT=$(find "${FIXTURE_DIR}/region" -name '*.mca' | wc -l)
echo "[spike] fixture has ${REGION_COUNT} region files"

run_one() {
  local bucket="$1" mask_min_z="$2" mask_max_z="$3" name="$4"
  docker run --rm --network "$NET" --entrypoint /bin/sh "$MC_IMAGE" -c "
mc alias set m http://minio:9000 ${S3_USER} ${S3_PASS} >/dev/null &&
mc rb --force --dangerous m/${bucket} 2>/dev/null || true
mc mb --ignore-existing m/${bucket}
" >/dev/null
  docker run -d --name "$name" --network "$NET" \
    -e APUS_MAP_ID=overworld \
    -e APUS_DIMENSION=minecraft:overworld \
    -e APUS_MC_VERSION=1.21.10 \
    -e APUS_WORLD_S3_URL=s3://bundles/worlds/demo/v1 \
    -e APUS_MAP_BUCKET="$bucket" \
    -e APUS_MAP_PREFIX=demo \
    -e APUS_S3_ENDPOINT=http://minio:9000 \
    -e APUS_S3_ACCESS_KEY="$S3_USER" \
    -e APUS_S3_SECRET_KEY="$S3_PASS" \
    -e APUS_RENDER_THREADS=4 \
    -e APUS_TELEMETRY_ENABLED=false \
    -e APUS_RENDER_MASK_MIN_X=-512 -e APUS_RENDER_MASK_MAX_X=511 \
    -e APUS_RENDER_MASK_MIN_Z="$mask_min_z" -e APUS_RENDER_MASK_MAX_Z="$mask_max_z" \
    -v "${SCRIPT_DIR}/spike-entrypoint.sh:/opt/apus/spike-entrypoint.sh:ro" \
    --entrypoint /opt/apus/spike-entrypoint.sh \
    apus/runner:dev
}

echo "[spike] reference render (whole world, one process, no mask) -> maps-reference"
docker rm -f apus-spike-reference >/dev/null 2>&1 || true
docker run --name apus-spike-reference --network "$NET" \
  -e APUS_MAP_ID=overworld \
  -e APUS_DIMENSION=minecraft:overworld \
  -e APUS_MC_VERSION=1.21.10 \
  -e APUS_WORLD_S3_URL=s3://bundles/worlds/demo/v1 \
  -e APUS_MAP_BUCKET=maps-reference \
  -e APUS_MAP_PREFIX=demo \
  -e APUS_S3_ENDPOINT=http://minio:9000 \
  -e APUS_S3_ACCESS_KEY="$S3_USER" \
  -e APUS_S3_SECRET_KEY="$S3_PASS" \
  -e APUS_RENDER_THREADS=4 \
  -e APUS_TELEMETRY_ENABLED=false \
  apus/runner:dev > "${SCRIPT_DIR}/logs/reference.log" 2>&1
docker rm apus-spike-reference >/dev/null

for i in $(seq 1 "$REPEATS"); do
  bucket="maps-parallel-${i}"
  echo "[spike] parallel repeat ${i}/${REPEATS}: south + north concurrently -> ${bucket}"
  SOUTH_ID=$(run_one "$bucket" 0 511 "apus-spike-south-${i}")
  NORTH_ID=$(run_one "$bucket" 512 1023 "apus-spike-north-${i}")
  SOUTH_EXIT=$(docker wait "$SOUTH_ID")
  NORTH_EXIT=$(docker wait "$NORTH_ID")
  docker logs "apus-spike-south-${i}" > "${SCRIPT_DIR}/logs/${bucket}-south.log" 2>&1
  docker logs "apus-spike-north-${i}" > "${SCRIPT_DIR}/logs/${bucket}-north.log" 2>&1
  docker rm "apus-spike-south-${i}" "apus-spike-north-${i}" >/dev/null
  echo "[spike]   south exit=${SOUTH_EXIT} north exit=${NORTH_EXIT}"
  if [ "$SOUTH_EXIT" != "0" ] || [ "$NORTH_EXIT" != "0" ]; then
    echo "[spike] ERROR: repeat ${i} had a non-zero exit" >&2
    exit 1
  fi
done

echo "[spike] sequential control: south fully completes, then north starts -> maps-sequential"
docker run --rm --network "$NET" --entrypoint /bin/sh "$MC_IMAGE" -c "
mc alias set m http://minio:9000 ${S3_USER} ${S3_PASS} >/dev/null &&
mc rb --force --dangerous m/maps-sequential 2>/dev/null || true
mc mb --ignore-existing m/maps-sequential
" >/dev/null

docker run --name apus-spike-south-seq --network "$NET" \
  -e APUS_MAP_ID=overworld -e APUS_DIMENSION=minecraft:overworld -e APUS_MC_VERSION=1.21.10 \
  -e APUS_WORLD_S3_URL=s3://bundles/worlds/demo/v1 -e APUS_MAP_BUCKET=maps-sequential \
  -e APUS_MAP_PREFIX=demo -e APUS_S3_ENDPOINT=http://minio:9000 \
  -e APUS_S3_ACCESS_KEY="$S3_USER" -e APUS_S3_SECRET_KEY="$S3_PASS" \
  -e APUS_RENDER_THREADS=4 -e APUS_TELEMETRY_ENABLED=false \
  -e APUS_RENDER_MASK_MIN_X=-512 -e APUS_RENDER_MASK_MAX_X=511 \
  -e APUS_RENDER_MASK_MIN_Z=0 -e APUS_RENDER_MASK_MAX_Z=511 \
  -v "${SCRIPT_DIR}/spike-entrypoint.sh:/opt/apus/spike-entrypoint.sh:ro" \
  --entrypoint /opt/apus/spike-entrypoint.sh \
  apus/runner:dev > "${SCRIPT_DIR}/logs/sequential-south.log" 2>&1
docker rm apus-spike-south-seq >/dev/null

docker run --name apus-spike-north-seq --network "$NET" \
  -e APUS_MAP_ID=overworld -e APUS_DIMENSION=minecraft:overworld -e APUS_MC_VERSION=1.21.10 \
  -e APUS_WORLD_S3_URL=s3://bundles/worlds/demo/v1 -e APUS_MAP_BUCKET=maps-sequential \
  -e APUS_MAP_PREFIX=demo -e APUS_S3_ENDPOINT=http://minio:9000 \
  -e APUS_S3_ACCESS_KEY="$S3_USER" -e APUS_S3_SECRET_KEY="$S3_PASS" \
  -e APUS_RENDER_THREADS=4 -e APUS_TELEMETRY_ENABLED=false \
  -e APUS_RENDER_MASK_MIN_X=-512 -e APUS_RENDER_MASK_MAX_X=511 \
  -e APUS_RENDER_MASK_MIN_Z=512 -e APUS_RENDER_MASK_MAX_Z=1023 \
  -v "${SCRIPT_DIR}/spike-entrypoint.sh:/opt/apus/spike-entrypoint.sh:ro" \
  --entrypoint /opt/apus/spike-entrypoint.sh \
  apus/runner:dev > "${SCRIPT_DIR}/logs/sequential-north.log" 2>&1
docker rm apus-spike-north-seq >/dev/null

echo "[spike] done. Buckets: maps-reference, maps-parallel-1..${REPEATS}, maps-sequential."
echo "[spike] next: python3 compare_tiles.py <bucket> <label> for each, then inspect mirrors/."
