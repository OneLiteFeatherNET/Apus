import java.time.Duration

dependencies {
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)

    testImplementation(platform(libs.testcontainers.bom))
    testImplementation(libs.testcontainers.junit)
    testImplementation(libs.testcontainers.minio)

    testImplementation("org.slf4j:slf4j-simple:2.0.16")
}

// Every test in this module is a container-based integration test: each starts MinIO plus
// the apus/runner image and runs a full BlueMap render, taking minutes. That must not run
// as part of the routine `./gradlew build`/`check` -- it would make every build slow and
// fail outright whenever the image hasn't been built yet. Disable the default `test` task
// and expose the same tests only via the explicit `integrationTest` task below.
// See runner/README.md for how to run it.
tasks.test { enabled = false }

val integrationTest by tasks.registering(Test::class) {
    group = "verification"
    description = "Runs the container-based integration tests against the apus/runner image. " +
        "Requires ./gradlew :telemetry-addon:shadowJar and a docker build beforehand " +
        "(see runner/README.md). Not part of build/check."
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    // The image must exist; building it is not this task's job. If it's missing, the
    // container tests below fail with a clear "image not found" error from testcontainers.
    // Build it with: docker build -f runner/Dockerfile -t apus/runner:dev .
    systemProperty("apus.runner.image", System.getProperty("apus.runner.image", "apus/runner:dev"))
    // A full render of the fixture takes minutes, not seconds.
    timeout.set(Duration.ofMinutes(20))
    outputs.upToDateWhen { false }
}
