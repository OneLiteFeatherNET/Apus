rootProject.name = "Apus"

include("telemetry-addon", "runner", "operator", "ingest")

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        maven("https://repo.bluecolored.de/releases")
    }
    versionCatalogs {
        create("libs") {
            version("bluemap", "5.23")
            version("bluemap-api", "2.8.0")
            version("junit", "6.0.3")
            version("testcontainers", "1.20.4")
            version("spotless", "8.3.0")
            version("shadow", "9.3.2")
            version("josdk", "5.5.1")
            version("fabric8", "7.8.0")
            // Jackson: not a new dependency family for the project -- fabric8's kubernetes-client
            // already pulls jackson-databind transitively for the operator module -- just the
            // first place it's declared explicitly, for the ingest module's BundleManifest
            // serialisation and Pterodactyl JSON parsing (replacing two hand-rolled JSON
            // parsers). Version verified against Maven Central on 2026-08-08: 2.22.1 is the
            // newest jackson-bom release (2.22.2 does not exist yet). Note that jackson-bom
            // 2.20+ pins jackson-annotations to a patch-less "2.22" version, by design (see the
            // bom's own POM comment) -- only jackson-databind itself needs a version("jackson")
            // reference here.
            version("jackson", "2.22.1")
            // AWS SDK v2, not the MinIO Java client: runner/vendor/BlueMapS3Storage.jar (the
            // BlueMap storage addon the render container already uses) is itself built on
            // software.amazon.nio.spi.s3, which wraps this same SDK. Using it here too keeps
            // exactly one S3 client family/credential-provider chain across the project
            // instead of two competing ones, and it works against any S3-compatible endpoint
            // (Rook/Ceph, MinIO, R2, ...) via endpoint override + path-style access -- nothing
            // MinIO-specific is needed. Version verified against Maven Central on 2026-08-08.
            version("aws-sdk", "2.46.7")

            library("bluemap.api", "de.bluecolored", "bluemap-api").versionRef("bluemap-api")
            library("bluemap.core", "de.bluecolored", "bluemap-core").versionRef("bluemap")
            library("bluemap.common", "de.bluecolored", "bluemap-common").versionRef("bluemap")

            library("junit.bom", "org.junit", "junit-bom").versionRef("junit")
            library("junit.jupiter", "org.junit.jupiter", "junit-jupiter").withoutVersion()
            library("junit.platform.launcher", "org.junit.platform", "junit-platform-launcher").withoutVersion()

            library("testcontainers.bom", "org.testcontainers", "testcontainers-bom").versionRef("testcontainers")
            library("testcontainers.junit", "org.testcontainers", "junit-jupiter").withoutVersion()
            library("testcontainers.minio", "org.testcontainers", "minio").withoutVersion()
            library("testcontainers.k3s", "org.testcontainers", "k3s").withoutVersion()

            library("josdk", "io.javaoperatorsdk", "operator-framework").versionRef("josdk")
            library("josdk.junit", "io.javaoperatorsdk", "operator-framework-junit").versionRef("josdk")
            library("crd.generator.api.v2", "io.fabric8", "crd-generator-api-v2").versionRef("fabric8")
            library("crd.generator.collector", "io.fabric8", "crd-generator-collector").versionRef("fabric8")
            library("fabric8.junit", "io.fabric8", "kubernetes-junit-jupiter").versionRef("fabric8")
            // @EnableKubernetesMockClient lives here, NOT in kubernetes-junit-jupiter
            // (that one targets tests against a real cluster and ships no mock classes).
            library("fabric8.server.mock", "io.fabric8", "kubernetes-server-mock").versionRef("fabric8")

            library("aws.sdk.bom", "software.amazon.awssdk", "bom").versionRef("aws-sdk")
            library("aws.sdk.s3", "software.amazon.awssdk", "s3").withoutVersion()

            library("jackson.bom", "com.fasterxml.jackson", "jackson-bom").versionRef("jackson")
            library("jackson.databind", "com.fasterxml.jackson.core", "jackson-databind").withoutVersion()

            plugin("spotless", "com.diffplug.spotless").versionRef("spotless")
            plugin("shadow", "com.gradleup.shadow").versionRef("shadow")
        }
    }
}
