dependencies {
    // AWS SDK v2 S3 client -- see settings.gradle.kts for why this over the MinIO Java client.
    implementation(platform(libs.aws.sdk.bom))
    implementation(libs.aws.sdk.s3)

    // Jackson -- see settings.gradle.kts for why. Backs BundleManifest (de)serialisation and
    // Pterodactyl API response parsing; both used to be hand-rolled JSON codecs.
    implementation(platform(libs.jackson.bom))
    implementation(libs.jackson.databind)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)

    // Real MinIO via Testcontainers for S3SourceConnectorTest -- see that test's Javadoc.
    testImplementation(platform(libs.testcontainers.bom))
    testImplementation(libs.testcontainers.junit)
    testImplementation(libs.testcontainers.minio)
}
