# syntax=docker/dockerfile:1

FROM eclipse-temurin:25-jre-jammy

# Non-root, same convention as runner/Dockerfile and ingest/Dockerfile. The operator writes
# nothing to the filesystem at all -- it only talks to the Kubernetes API -- so it gets no
# writable directory beyond its home.
RUN useradd --uid 10001 --create-home --home-dir /home/apus apus

# Built by: ./gradlew :operator:shadowJar
COPY --chown=apus:apus operator/build/libs/apus-operator.jar /opt/apus/operator.jar

USER apus
WORKDIR /home/apus

# The operator serves no traffic of its own; 8080 is only the metrics endpoint added in
# phase 8. Declared here so the port contract lives with the image.
EXPOSE 8080

ENTRYPOINT ["java", "-jar", "/opt/apus/operator.jar"]
