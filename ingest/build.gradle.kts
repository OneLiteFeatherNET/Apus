dependencies {
    // AWS SDK v2 S3 client -- see settings.gradle.kts for why this over the MinIO Java client.
    implementation(platform(libs.aws.sdk.bom))
    implementation(libs.aws.sdk.s3)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}
