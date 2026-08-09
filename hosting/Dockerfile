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
# version-pinned URL below is the reproducible way to fetch it (see runner/README.md, which
# fetches the same jar the same way). Bump BLUEMAP_S3_STORAGE_VERSION when a newer release
# is needed.
RUN curl -fsSL -o bluemap-cli.jar \
      "https://github.com/BlueMap-Minecraft/BlueMap/releases/download/v${BLUEMAP_VERSION}/bluemap-${BLUEMAP_VERSION}-cli.jar" \
 && curl -fsSL -o bluemap-s3-storage.jar \
      "https://github.com/TheMeinerLP/BlueMapS3Storage/releases/download/v${BLUEMAP_S3_STORAGE_VERSION}/BlueMapS3Storage-${BLUEMAP_S3_STORAGE_VERSION}.jar"

########################################
# Stage 2: runtime
########################################
FROM eclipse-temurin:25-jre-jammy

# Non-root: this pod only ever writes below /work (its BlueMap config copy and generated
# webapp/data files); everything else is read-only image content.
RUN useradd --uid 10001 --create-home --home-dir /home/apus apus \
 && mkdir -p /work/config-src /work/config \
 && chown -R apus:apus /work

COPY --from=fetch /download/bluemap-cli.jar /opt/bluemap/cli.jar
# The S3 storage addon is a binary plugin baked into the image, not configuration -- it is
# not part of the ConfigMap the operator mounts. config-sync.sh copies it from here into the
# writable config dir's packs/ folder on every start.
COPY --from=fetch --chown=apus:apus /download/bluemap-s3-storage.jar /opt/apus/packs/bluemap-s3-storage.jar
COPY --chown=apus:apus entrypoint.sh /opt/apus/entrypoint.sh
COPY --chown=apus:apus bin/ /opt/apus/bin/

RUN chmod +x /opt/apus/entrypoint.sh /opt/apus/bin/*.sh

USER apus
WORKDIR /work

ENV APUS_S3_REGION=us-east-1 \
    APUS_WEBSERVER_PORT=8100

EXPOSE 8100

ENTRYPOINT ["/opt/apus/entrypoint.sh"]
