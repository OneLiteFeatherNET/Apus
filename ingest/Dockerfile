# syntax=docker/dockerfile:1

FROM eclipse-temurin:25-jre-jammy

# Non-root: the ingest job only ever writes below /work (its fetched source data lands in
# /work/source; nothing else on the filesystem is touched). Same convention as runner/Dockerfile.
RUN useradd --uid 10001 --create-home --home-dir /home/apus apus \
 && mkdir -p /work/source \
 && chown -R apus:apus /work

COPY --chown=apus:apus ingest/entrypoint.sh /opt/apus/entrypoint.sh
# Built by: ./gradlew :ingest:shadowJar
COPY --chown=apus:apus ingest/build/libs/apus-ingest.jar /opt/apus/ingest.jar

RUN chmod +x /opt/apus/entrypoint.sh

USER apus
WORKDIR /work

# Only the genuinely optional variables get a default here. Every required variable
# (APUS_SOURCE_TYPE, APUS_WORLD_NAME, APUS_SOURCE_VERSION, APUS_BUNDLE_*, APUS_S3_ENDPOINT/
# ACCESS_KEY/SECRET_KEY, and the source-specific ones) intentionally has none, so a Job that
# forgets one fails fast with IngestConfig's error message instead of silently doing the wrong
# thing. See ingest/README.md for the full contract.
ENV APUS_LAYOUT=auto \
    APUS_S3_REGION=us-east-1 \
    APUS_PROGRESS_INTERVAL_SECONDS=10

ENTRYPOINT ["/opt/apus/entrypoint.sh"]
