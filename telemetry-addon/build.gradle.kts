plugins {
    `maven-publish`
    alias(libs.plugins.shadow)
}

version = "0.1.0" // x-release-please-version

dependencies {
    compileOnly(libs.bluemap.api)
    compileOnly(libs.bluemap.core)
    compileOnly(libs.bluemap.common)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)

    testCompileOnly(libs.bluemap.common)
    testRuntimeOnly(libs.bluemap.api)
    testRuntimeOnly(libs.bluemap.core)
    testRuntimeOnly(libs.bluemap.common)
}

tasks {
    shadowJar {
        archiveClassifier.set("")
        archiveBaseName.set("apus-telemetry-addon")
        // Fixed name instead of the default "apus-telemetry-addon-<version>.jar": the
        // Dockerfile COPYs this file by name (no glob), and a glob over build/libs breaks
        // the moment more than one version is present there -- which happens after the
        // first release, once release-please bumps the project version.
        archiveFileName.set("apus-telemetry-addon.jar")
    }
    build {
        dependsOn(shadowJar)
    }
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            groupId = "net.onelitefeather.apus"
            artifactId = "telemetry-addon"
            // The shadow jar is the artifact consumers need -- the thin jar would leave
            // them to resolve the relocated dependencies themselves.
            artifact(tasks.named("shadowJar"))
        }
    }
    repositories {
        maven {
            // Name and credential env vars match the OneLiteFeather-wide convention (verified
            // against OneLiteFeatherNET/Aves and the central gradle-publish.yml reusable
            // workflow, which injects exactly these two secrets).
            name = "OneLiteFeatherRepository"
            credentials(PasswordCredentials::class) {
                username = System.getenv("ONELITEFEATHER_MAVEN_USERNAME")
                password = System.getenv("ONELITEFEATHER_MAVEN_PASSWORD")
            }
            url = if (project.version.toString().contains("SNAPSHOT")) {
                uri("https://repo.onelitefeather.dev/onelitefeather-snapshots")
            } else {
                uri("https://repo.onelitefeather.dev/onelitefeather-releases")
            }
        }
    }
}
