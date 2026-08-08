# syntax=docker/dockerfile:1

########################################
# Stage 1: fetch the BlueMap CLI
########################################
FROM eclipse-temurin:25-jre-jammy AS fetch

ARG BLUEMAP_VERSION=5.23

RUN apt-get update \
 && apt-get install -y --no-install-recommends curl ca-certificates \
 && rm -rf /var/lib/apt/lists/*

WORKDIR /download
RUN curl -fsSL -o bluemap-cli.jar \
      "https://github.com/BlueMap-Minecraft/BlueMap/releases/download/v${BLUEMAP_VERSION}/bluemap-${BLUEMAP_VERSION}-cli.jar"

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
# The S3 storage addon; see BlueMapS3Storage releases.
COPY --chown=apus:apus runner/vendor/BlueMapS3Storage.jar /work/config/packs/bluemap-s3-storage.jar

RUN chmod +x /opt/apus/entrypoint.sh /opt/apus/bin/*.sh

USER apus
WORKDIR /work

ENV APUS_TELEMETRY_PORT=8099 \
    APUS_RENDER_THREADS=2 \
    APUS_S3_REGION=us-east-1 \
    APUS_MAP_PREFIX=.

EXPOSE 8099

ENTRYPOINT ["/opt/apus/entrypoint.sh"]
