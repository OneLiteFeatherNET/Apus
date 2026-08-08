plugins {
    alias(libs.plugins.shadow)
}

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
