import java.time.Duration

// Needed before the integrationTest task below can reference :operator's generateCrds task by
// name -- without this, Gradle may configure :api before :operator has registered it.
evaluationDependsOn(":operator")

plugins {
    application
    alias(libs.plugins.shadow)
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
    // Not only for making outbound calls of our own -- Micronaut Security fetches the remote JWK
    // Set through this client, and without it on the *runtime* classpath the fetch yields an
    // empty key set and logs nothing at all. Every token then fails signature validation with a
    // bare 401, no matter which broker issued it, and the only trace is a DEBUG line reading
    // "JWK Set Key IDs:" with nothing after it. It was a testImplementation dependency until
    // this was found against a live issuer.
    implementation(libs.micronaut.http.client)
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

    // AWS SDK v2 -- see settings.gradle.kts for why this SDK family. Backs both this module's own
    // authenticated staging-bucket calls (CreateMultipartUpload, ListParts,
    // CompleteMultipartUpload/AbortMultipartUpload -- see MultipartUploadService's Javadoc for why
    // those specifically are never presigned) and the presigned UploadPart URLs POST /api/uploads
    // hands back to the caller (design spec §11.1) via S3Presigner, which ships inside this same
    // `s3` artifact (see settings.gradle.kts).
    implementation(platform(libs.aws.sdk.bom))
    implementation(libs.aws.sdk.s3)

    // Observability -- see docs/logging-and-tracing.md for the contract all three services share.
    //
    // SLF4J is the API every class in this module logs through; Logback is the implementation
    // behind it and reads src/main/resources/logback.xml. Logback is `implementation` rather than
    // `runtimeOnly` because OpenTelemetryFactory compiles against OpenTelemetryAppender, which is
    // itself a Logback appender class.
    implementation(libs.slf4j.api)
    implementation(libs.logback.classic)

    implementation(platform(libs.opentelemetry.bom))
    implementation(libs.opentelemetry.api)
    implementation(libs.opentelemetry.sdk)
    // Reads the standard OTEL_* environment variables, so which collector receives the data (or
    // whether one exists at all) stays a deployment decision -- see OpenTelemetryFactory.
    implementation(libs.opentelemetry.sdk.autoconfigure)
    // Only ever loaded through autoconfigure's ServiceLoader lookup, never referenced from code;
    // shadowJar's mergeServiceFiles() above keeps that SPI registration intact in the fat jar.
    runtimeOnly(libs.opentelemetry.exporter.otlp)

    implementation(platform(libs.opentelemetry.instrumentation.bom))
    implementation(libs.opentelemetry.logback.appender)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)

    // Test-only: TenantResolverTest proves its namespace convention ("bluemap-<tenant>") never
    // drifts from TenantReconciler's, by calling the reconciler's own namespaceFor(Tenant)
    // instead of duplicating the literal prefix as a second source of truth. JOSDK is not a
    // main-code dependency of this module -- the api module never reconciles anything -- so it
    // is scoped to testImplementation only, not the dependency added above for production code.
    testImplementation(libs.josdk)

    // Test-only, for FabricPushTokenRepositoryTest (phase 6): the same `@EnableKubernetesMockClient`
    // fake-but-CRUD-real Kubernetes API server operator/build.gradle.kts already uses, needed here
    // to prove the cluster-wide, label-selected Secret lookup actually works -- an in-memory fake
    // repository (as InMemoryPushTokenRepository provides for the controller-level tests) cannot
    // prove that the real fabric8 `inAnyNamespace().withLabel(...)` query and Secret.data
    // base64 decoding are wired correctly.
    testImplementation(libs.fabric8.junit)
    testImplementation(libs.fabric8.server.mock)

    // Phase 5a consolidation: both parallel worktrees reported this as missing, which meant
    // every existing test called controller/repository methods directly instead of going
    // through the real embedded server -- so role enforcement and 404-vs-403 error mapping over
    // the actual HTTP/security-filter path were never proven. `micronaut-test-junit5` provides
    // `@MicronautTest`/`TestPropertyProvider`, and the `@Client("/") HttpClient` it injects is
    // backed by micronaut-http-client, which is now an `implementation` dependency above --
    // the claim that "production code never makes outbound HTTP calls" was wrong: Micronaut
    // Security fetches the JWK Set with it.
    testImplementation(platform(libs.micronaut.test.bom))
    testImplementation(libs.micronaut.test.junit5)
    testAnnotationProcessor(platform(libs.micronaut.core.bom))
    testAnnotationProcessor(libs.micronaut.inject.java)

    // Test-only, for TenantIsolationIntegrationTest: a real k3s API server via Testcontainers,
    // the same pattern operator/build.gradle.kts and ingest/build.gradle.kts already use.
    // MinIO backs MultipartUploadServiceIntegrationTest (phase 6): the only way to actually prove
    // a presigned UploadPart URL is confined to its signed key/size is to drive real HTTP PUTs
    // against a real S3-compatible server -- see that test's Javadoc.
    testImplementation(platform(libs.testcontainers.bom))
    testImplementation(libs.testcontainers.junit)
    testImplementation(libs.testcontainers.k3s)
    testImplementation(libs.testcontainers.minio)
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

tasks {
    shadowJar {
        archiveClassifier.set("")
        archiveBaseName.set("apus-api")
        archiveFileName.set("apus-api.jar")
        // Micronaut ships service files (annotation-driven bean definitions, serde config)
        // in META-INF/services; without merging them the shadowed jar starts but resolves
        // no beans, which surfaces as a confusing "no route matched" at runtime rather
        // than a build failure.
        mergeServiceFiles()
    }
    build {
        dependsOn(shadowJar)
    }
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
