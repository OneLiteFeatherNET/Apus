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

tasks.test {
    // The image must exist; building it is not this task's job.
    // Build it with: docker build -f runner/Dockerfile -t apus/runner:dev .
    systemProperty("apus.runner.image", System.getProperty("apus.runner.image", "apus/runner:dev"))
    // A full render of the fixture takes minutes, not seconds.
    timeout.set(Duration.ofMinutes(20))
}
