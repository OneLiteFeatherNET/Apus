import java.time.Duration

plugins {
    application
}

dependencies {
    implementation(libs.josdk)

    // cron-utils -- see settings.gradle.kts for why. Backs CronSchedule, which
    // WorldSourceReconciler (phase 2b, task 6) uses to evaluate spec.poll.
    implementation(libs.cron.utils)

    // The ingest connectors (WorldSourceConnector/S3SourceConnector/PterodactylConnector) are
    // reused directly by WorldSourceReconciler to run discover() on a schedule -- see
    // ingest/README.md's "Design notes" section, which already documents this split:
    // discover() (listing available versions) belongs to the reconciler, fetch() (pulling one
    // specific version) belongs to the ingest Job this reconciler schedules. Depending on the
    // module rather than duplicating the connector interface keeps exactly one implementation
    // of each source type.
    implementation(project(":ingest"))

    // AWS SDK v2 S3 client -- see settings.gradle.kts for why this over the MinIO Java client.
    // Used by AwsBundleStore (phase 2b, task 6) to enforce WorldSource.spec.retention: listing
    // and deleting older bundle versions in the destination bucket.
    implementation(platform(libs.aws.sdk.bom))
    implementation(libs.aws.sdk.s3)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
    testImplementation(libs.fabric8.junit)
    testImplementation(libs.fabric8.server.mock)

    testImplementation(platform(libs.testcontainers.bom))
    testImplementation(libs.testcontainers.junit)
    testImplementation(libs.testcontainers.k3s)
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
        sourceSets.main.get().output.classesDirs.asPath,
    )
    doFirst {
        // The generator only ever writes files, it never removes ones that no longer
        // correspond to a CustomResource class -- e.g. after a resource is renamed or
        // deleted. Without this, a stale manifest from an earlier run would keep sitting in
        // crdOutputDir and any test scanning that directory would stay green even though the
        // actual generator output is now wrong. Clearing the directory before every run makes
        // its contents an accurate reflection of the current source, not an accumulation of
        // every run that ever touched it.
        val dir = crdOutputDir.get().asFile
        dir.deleteRecursively()
        dir.mkdirs()
    }
}

tasks.named("build") {
    dependsOn(generateCrds)
}

tasks.test {
    dependsOn(generateCrds)
    systemProperty("apus.crd.dir", crdOutputDir.get().asFile.absolutePath)
    // OperatorIntegrationTest and BlueMapHostingIntegrationTest each start a k3s container and
    // are not part of the routine build/check run -- see the integrationTest task below for why.
    // Matched by naming convention (every real-cluster test class ends in "IntegrationTest")
    // rather than by an ever-growing explicit list.
    exclude("**/*IntegrationTest.class")
}

// OperatorIntegrationTest and BlueMapHostingIntegrationTest each start a k3s container (via
// Testcontainers) to apply the generated CRDs against a real API server and reconcile real
// resources end to end. That is minutes of work and requires Docker, so -- exactly like
// runner/build.gradle.kts does for its own container-based tests -- neither runs as part of the
// routine `./gradlew build`/`check`. Both are disabled in the default `test` task above and
// exposed only via this explicit task.
val integrationTest by tasks.registering(Test::class) {
    group = "verification"
    description = "Runs the *IntegrationTest classes against a real k3s cluster started via Testcontainers. " +
        "Requires Docker. Not part of build/check."
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    dependsOn(generateCrds)
    systemProperty("apus.crd.dir", crdOutputDir.get().asFile.absolutePath)
    include("**/*IntegrationTest.class")
    // Pulling the k3s image and letting the API server come up takes real time on a cold
    // Docker cache; generous but finite so a hung container fails the build instead of the
    // run hanging forever.
    timeout.set(Duration.ofMinutes(10))
    outputs.upToDateWhen { false }
}

application {
    mainClass.set("net.onelitefeather.apus.operator.ApusOperator")
}
