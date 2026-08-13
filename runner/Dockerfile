# syntax=docker/dockerfile:1

########################################
# Stage 1: fetch the BlueMap CLI and the S3 storage addon
########################################
FROM eclipse-temurin:25-jre-jammy AS fetch

ARG BLUEMAP_VERSION=5.23
ARG BLUEMAP_S3_STORAGE_VERSION=1.5.1

RUN apt-get update \
 && apt-get install -y --no-install-recommends curl ca-certificates \
 && rm -rf /var/lib/apt/lists/*

WORKDIR /download
# BlueMapS3Storage release assets are versioned (e.g. BlueMapS3Storage-1.5.1.jar, not
# BlueMapS3Storage.jar), so releases/latest/download/BlueMapS3Storage.jar 404s -- the
# version-pinned URL below is the reproducible way to fetch it (hosting/Dockerfile fetches
# the same jar the same way). Bump BLUEMAP_S3_STORAGE_VERSION when a newer release is
# needed. Fetching it here instead of copying a checked-in runner/vendor/ jar is what makes
# this image buildable in CI at all: runner/vendor/ is gitignored, so it does not exist in
# a fresh clone.
RUN curl -fsSL -o bluemap-cli.jar \
      "https://github.com/BlueMap-Minecraft/BlueMap/releases/download/v${BLUEMAP_VERSION}/bluemap-${BLUEMAP_VERSION}-cli.jar" \
 && curl -fsSL -o bluemap-s3-storage.jar \
      "https://github.com/TheMeinerLP/BlueMapS3Storage/releases/download/v${BLUEMAP_S3_STORAGE_VERSION}/BlueMapS3Storage-${BLUEMAP_S3_STORAGE_VERSION}.jar"

########################################
# Stage 2: runtime
########################################
FROM eclipse-temurin:25-jre-jammy

# Pinned to a specific mc release (not the floating "latest" alias) and verified against
# its published checksum, so the image is reproducible and cannot silently pick up a
# breaking mc change.
ARG MC_VERSION=RELEASE.2025-08-13T08-35-41Z
ARG MC_SHA256=01f866e9c5f9b87c2b09116fa5d7c06695b106242d829a8bb32990c00312e891

RUN apt-get update \
 && apt-get install -y --no-install-recommends ca-certificates curl \
 && curl -fsSL -o /usr/local/bin/mc \
      "https://dl.min.io/client/mc/release/linux-amd64/archive/mc.${MC_VERSION}" \
 && echo "${MC_SHA256}  /usr/local/bin/mc" | sha256sum -c - \
 && chmod +x /usr/local/bin/mc \
 && apt-get purge -y curl \
 && apt-get autoremove -y \
 && rm -rf /var/lib/apt/lists/*

# Non-root: the render only ever writes below /work.
RUN useradd --uid 10001 --create-home --home-dir /home/apus apus \
 && mkdir -p /work/config/packs /work/data /work/world \
 && chown -R apus:apus /work

COPY --from=fetch /download/bluemap-cli.jar /opt/bluemap/cli.jar
COPY --chown=apus:apus runner/entrypoint.sh /opt/apus/entrypoint.sh
COPY --chown=apus:apus runner/bin/ /opt/apus/bin/
# Built by: ./gradlew :telemetry-addon:shadowJar
# Fixed file name (see telemetry-addon/build.gradle.kts archiveFileName) -- no glob, so
# this keeps working once build/libs holds more than one version after a release.
COPY --chown=apus:apus telemetry-addon/build/libs/apus-telemetry-addon.jar /work/config/packs/apus-telemetry.jar
# The S3 storage addon; fetched in the stage above, see BlueMapS3Storage releases.
COPY --from=fetch --chown=apus:apus /download/bluemap-s3-storage.jar /work/config/packs/bluemap-s3-storage.jar

RUN chmod +x /opt/apus/entrypoint.sh /opt/apus/bin/*.sh

USER apus
WORKDIR /work

ENV APUS_TELEMETRY_PORT=8099 \
    APUS_RENDER_THREADS=2 \
    APUS_S3_REGION=us-east-1 \
    APUS_MAP_PREFIX=.

EXPOSE 8099

ENTRYPOINT ["/opt/apus/entrypoint.sh"]
