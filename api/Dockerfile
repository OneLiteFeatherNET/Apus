# syntax=docker/dockerfile:1

FROM eclipse-temurin:25-jre-jammy

RUN useradd --uid 10001 --create-home --home-dir /home/apus apus

# Built by: ./gradlew :api:shadowJar
COPY --chown=apus:apus api/build/libs/apus-api.jar /opt/apus/api.jar

USER apus
WORKDIR /home/apus

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "/opt/apus/api.jar"]
