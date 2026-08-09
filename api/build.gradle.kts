plugins {
    application
}

dependencies {
    // Tenant/BlueMapMap/BlueMapRender/WorldSource/WorldIngest/BlueMapHosting: pure CR data
    // holders from phases 2a/2b/3, reused instead of duplicating their shape here.
    implementation(project(":operator"))

    // The fabric8 client itself -- see settings.gradle.kts for why this is needed explicitly
    // even though :operator already depends on it (transitively, via `implementation`, which
    // does not leak onto this module's compile classpath).
    implementation(libs.fabric8.kubernetes.client)
    runtimeOnly(libs.fabric8.httpclient.jdk)

    implementation(platform(libs.micronaut.core.bom))
    implementation(libs.micronaut.http.server.netty)
    implementation(libs.micronaut.runtime)
    annotationProcessor(platform(libs.micronaut.core.bom))
    annotationProcessor(libs.micronaut.inject.java)

    // JWT validation against a configurable issuer -- see settings.gradle.kts and
    // src/main/resources/application.yml. Which identity broker sits in front of Apus is an
    // open question (design spec §15); micronaut-security-jwt only needs an issuer and a JWKS
    // endpoint, both of which are plain OIDC-discovery concepts every candidate broker exposes.
    implementation(platform(libs.micronaut.security.bom))
    implementation(libs.micronaut.security.jwt)
    annotationProcessor(platform(libs.micronaut.security.bom))
    annotationProcessor(libs.micronaut.security.annotations)

    // JSON (de)serialisation for the REST responses task 2 adds.
    implementation(platform(libs.micronaut.serde.bom))
    implementation(libs.micronaut.serde.jackson)
    annotationProcessor(platform(libs.micronaut.serde.bom))
    annotationProcessor(libs.micronaut.serde.processor)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)

    // Test-only: TenantResolverTest proves its namespace convention ("bluemap-<tenant>") never
    // drifts from TenantReconciler's, by calling the reconciler's own namespaceFor(Tenant)
    // instead of duplicating the literal prefix as a second source of truth. JOSDK is not a
    // main-code dependency of this module -- the api module never reconciles anything -- so it
    // is scoped to testImplementation only, not the dependency added above for production code.
    testImplementation(libs.josdk)
}

application {
    mainClass.set("net.onelitefeather.apus.api.Application")
}
