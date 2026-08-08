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
    }
    build {
        dependsOn(shadowJar)
    }
}
