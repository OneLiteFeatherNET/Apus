import java.time.Duration

plugins {
    application
    alias(libs.plugins.shadow)
}

dependencies {
    // AWS SDK v2 S3 client -- see settings.gradle.kts for why this over the MinIO Java client.
    implementation(platform(libs.aws.sdk.bom))
    implementation(libs.aws.sdk.s3)

    // Jackson -- see settings.gradle.kts for why. Backs BundleManifest (de)serialisation and
    // Pterodactyl API response parsing; both used to be hand-rolled JSON codecs.
    implementation(platform(libs.jackson.bom))
    implementation(libs.jackson.databind)

    // Observability -- see docs/logging-and-tracing.md for the contract all three services share.
    // SLF4J is the API every class logs through; Logback is the implementation that writes the
    // human-readable console line and, via the OpenTelemetry appender referenced from
    // src/main/resources/logback.xml, ships the same record through OTLP.
    implementation(libs.slf4j.api)
    implementation(libs.logback.classic)

    implementation(platform(libs.opentelemetry.bom))
    implementation(libs.opentelemetry.api)
    implementation(libs.opentelemetry.sdk)
    implementation(libs.opentelemetry.exporter.otlp)
    // IngestMain builds the SDK from the OTEL_* environment variables, so which collector receives
    // the run's spans -- or whether one exists at all -- stays a deployment decision.
    implementation(libs.opentelemetry.sdk.autoconfigure)

    implementation(platform(libs.opentelemetry.instrumentation.bom))
    implementation(libs.opentelemetry.logback.appender)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)

    // InMemorySpanExporter, so the ingest run's span tree can be asserted on without a collector.
    // Spelled out rather than referenced from the version catalog because settings.gradle.kts is
    // outside this change's scope; the version comes from the OpenTelemetry BOM above, which
    // testImplementation inherits from implementation.
    testImplementation("io.opentelemetry:opentelemetry-sdk-testing")

    // Real MinIO via Testcontainers for S3SourceConnectorTest -- see that test's Javadoc.
    testImplementation(platform(libs.testcontainers.bom))
    testImplementation(libs.testcontainers.junit)
    testImplementation(libs.testcontainers.minio)
}

application {
    mainClass.set("net.onelitefeather.apus.ingest.IngestMain")
}

tasks {
    shadowJar {
        archiveClassifier.set("")
        archiveBaseName.set("apus-ingest")
        // Fixed name instead of the default "apus-ingest-<version>.jar": ingest/Dockerfile
        // COPYs this file by name (no glob) -- see telemetry-addon/build.gradle.kts for the
        // same rationale applied to the render container's addon jar.
        archiveFileName.set("apus-ingest.jar")
        // The OpenTelemetry SDK discovers its exporters through java.util.ServiceLoader, and the
        // autoconfigure module and the OTLP exporter each contribute their own entries to the same
        // META-INF/services files. Without merging, one jar's copy would silently win and
        // AutoConfiguredOpenTelemetrySdk would find no OTLP exporter to configure -- the shaded jar
        // would build, start and export nothing.
        mergeServiceFiles()
    }
    build {
        dependsOn(shadowJar)
    }
}

// S3SourceConnectorTest, PushSourceConnectorTest, UploadSourceConnectorTest and (phase 6 task 3)
// PushIngestEndToEndTest -- the last one drives the whole IngestMain flow rather than one
// connector method, proving push/upload ingest end to end -- all start a real MinIO container via
// Testcontainers and therefore need Docker. Exactly like runner/build.gradle.kts and
// operator/build.gradle.kts do for their own container-based tests, that must not run as part of
// the routine `./gradlew build`/`check` -- it would make every build slow and fail outright on a
// machine without Docker. Excluded from the default `test` task and exposed only via the explicit
// `integrationTest` task below. See ingest/README.md for how to run it.
tasks.test {
    exclude("**/S3SourceConnectorTest.class")
    exclude("**/PushSourceConnectorTest.class")
    exclude("**/UploadSourceConnectorTest.class")
    exclude("**/PushIngestEndToEndTest.class")
}

val integrationTest by tasks.registering(Test::class) {
    group = "verification"
    description = "Runs the MinIO-backed connector tests (S3SourceConnectorTest, PushSourceConnectorTest, " +
        "UploadSourceConnectorTest, PushIngestEndToEndTest) against a real MinIO container via Testcontainers. " +
        "Requires Docker. Not part of build/check."
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    include("**/S3SourceConnectorTest.class")
    include("**/PushSourceConnectorTest.class")
    include("**/UploadSourceConnectorTest.class")
    include("**/PushIngestEndToEndTest.class")
    timeout.set(Duration.ofMinutes(5))
    outputs.upToDateWhen { false }
}
