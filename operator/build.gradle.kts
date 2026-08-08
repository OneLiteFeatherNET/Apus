plugins {
    application
}

dependencies {
    implementation(libs.josdk)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
    testImplementation(libs.fabric8.junit)
}

// Dedicated source set for the CRD generator entry point (CrdGeneratorMain). The fabric8
// crd-generator libraries have no supported CLI/Main class for 7.8.0 -- crd-generator-apt and
// the v1 io.fabric8.crd.generator.CRDGenerator class are deprecated since 7.0.0, and
// crd-generator-api-v2/crd-generator-collector ship only the programmatic CRDGenerator /
// CustomResourceCollector APIs (verified by inspecting the resolved jars, see
// task-1-report.md). A dedicated source set keeps those generator-only dependencies out of the
// operator's runtime/application classpath.
sourceSets {
    create("crdgen") {
        java.srcDir("src/crdgen/java")
        compileClasspath += sourceSets.main.get().output
        runtimeClasspath += sourceSets.main.get().output
    }
}

dependencies {
    "crdgenImplementation"(libs.crd.generator.api.v2)
    "crdgenImplementation"(libs.crd.generator.collector)
}

val crdOutputDir = layout.buildDirectory.dir("crds")

val generateCrds by tasks.registering(JavaExec::class) {
    description = "Generates CRD YAML from the CustomResource classes found in this module."
    group = "build"
    dependsOn(tasks.named("classes"))
    classpath = sourceSets["crdgen"].runtimeClasspath
    mainClass.set("net.onelitefeather.apus.operator.crdgen.CrdGeneratorMain")
    outputs.dir(crdOutputDir)
    args(
        crdOutputDir.get().asFile.absolutePath,
        sourceSets.main.get().output.classesDirs.singleFile.absolutePath,
    )
    doFirst {
        crdOutputDir.get().asFile.mkdirs()
    }
}

tasks.named("build") {
    dependsOn(generateCrds)
}

tasks.test {
    dependsOn(generateCrds)
    systemProperty("apus.crd.dir", crdOutputDir.get().asFile.absolutePath)
}

application {
    mainClass.set("net.onelitefeather.apus.operator.ApusOperator")
}
