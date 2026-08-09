import java.time.Duration

// Needed before the integrationTest task below can reference :operator's generateCrds task by
// name -- without this, Gradle may configure :api before :operator has registered it.
evaluationDependsOn(":operator")

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

    // Phase 5a consolidation: both parallel worktrees reported this as missing, which meant
    // every existing test called controller/repository methods directly instead of going
    // through the real embedded server -- so role enforcement and 404-vs-403 error mapping over
    // the actual HTTP/security-filter path were never proven. `micronaut-test-junit5` provides
    // `@MicronautTest`/`TestPropertyProvider`; `micronaut-http-client` backs the `@Client("/")
    // HttpClient` it injects. Both test-only: production code never makes outbound HTTP calls.
    testImplementation(platform(libs.micronaut.test.bom))
    testImplementation(libs.micronaut.test.junit5)
    testImplementation(libs.micronaut.http.client)
    testAnnotationProcessor(platform(libs.micronaut.core.bom))
    testAnnotationProcessor(libs.micronaut.inject.java)

    // Test-only, for TenantIsolationIntegrationTest: a real k3s API server via Testcontainers,
    // the same pattern operator/build.gradle.kts and ingest/build.gradle.kts already use.
    testImplementation(platform(libs.testcontainers.bom))
    testImplementation(libs.testcontainers.junit)
    testImplementation(libs.testcontainers.k3s)
}

// io.micronaut.test:micronaut-test-bom imports its own, newer org.testcontainers:testcontainers-
// bom (2.0.5) than this project pins everywhere else (1.20.4, see settings.gradle.kts) -- as two
// competing platform constraints on the same modules, Gradle would otherwise pick the higher one,
// silently upgrading Testcontainers for this module's tests only, off of a major version this
// project has not verified against (2.x renamed/restructured artifacts, breaking this
// configuration's resolution outright). This module does not use micronaut-test's own
// Testcontainers integration -- forcing every org.testcontainers module back to the pinned
// version keeps exactly one Testcontainers version across the whole project.
configurations.matching { it.name == "testCompileClasspath" || it.name == "testRuntimeClasspath" }.configureEach {
    resolutionStrategy.eachDependency {
        if (requested.group == "org.testcontainers") {
            useVersion(libs.versions.testcontainers.get())
            because("pin to the project-wide Testcontainers version, see settings.gradle.kts")
        }
    }
}

application {
    mainClass.set("net.onelitefeather.apus.api.Application")
}

// TenantIsolationIntegrationTest starts a k3s container (via Testcontainers), applies the
// `:operator` module's generated CRDs to it, and proves cross-tenant isolation over a real,
// JWT-authenticated HTTP call against a real API server -- minutes of work and Docker, exactly
// like operator/build.gradle.kts's and ingest/build.gradle.kts's own `integrationTest` tasks.
// Excluded from the default `test` task/`build`/`check` for the same reason theirs are.
val operatorGenerateCrds = project(":operator").tasks.named("generateCrds")
val operatorCrdDir = project(":operator").layout.buildDirectory.dir("crds")

tasks.test {
    exclude("**/*IntegrationTest.class")
}

val integrationTest by tasks.registering(Test::class) {
    group = "verification"
    description = "Runs the *IntegrationTest classes against a real k3s cluster started via Testcontainers. " +
        "Requires Docker. Not part of build/check."
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    dependsOn(operatorGenerateCrds)
    systemProperty("apus.crd.dir", operatorCrdDir.get().asFile.absolutePath)
    include("**/*IntegrationTest.class")
    timeout.set(Duration.ofMinutes(10))
    outputs.upToDateWhen { false }
}
