rootProject.name = "Apus"

include("telemetry-addon", "runner", "operator", "ingest", "api", "paper-worldpush")

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        maven("https://repo.bluecolored.de/releases")
        // paper-api, for :paper-worldpush -- see that module's own note in this file for why
        // it depends on a foreign version track instead of the rest of this catalog.
        maven("https://repo.papermc.io/repository/maven-public/")
    }
    versionCatalogs {
        create("libs") {
            version("bluemap", "5.23")
            version("bluemap-api", "2.8.0")
            version("junit", "6.0.3")
            version("testcontainers", "1.20.4")
            version("spotless", "8.3.0")
            version("shadow", "9.3.2")
            version("josdk", "5.5.1")
            version("fabric8", "7.8.0")
            // Jackson: not a new dependency family for the project -- fabric8's kubernetes-client
            // already pulls jackson-databind transitively for the operator module -- just the
            // first place it's declared explicitly, for the ingest module's BundleManifest
            // serialisation and Pterodactyl JSON parsing (replacing two hand-rolled JSON
            // parsers). Version verified against Maven Central on 2026-08-08: 2.22.1 is the
            // newest jackson-bom release (2.22.2 does not exist yet). Note that jackson-bom
            // 2.20+ pins jackson-annotations to a patch-less "2.22" version, by design (see the
            // bom's own POM comment) -- only jackson-databind itself needs a version("jackson")
            // reference here.
            version("jackson", "2.22.1")
            // AWS SDK v2, not the MinIO Java client: runner/vendor/BlueMapS3Storage.jar (the
            // BlueMap storage addon the render container already uses) is itself built on
            // software.amazon.nio.spi.s3, which wraps this same SDK. Using it here too keeps
            // exactly one S3 client family/credential-provider chain across the project
            // instead of two competing ones, and it works against any S3-compatible endpoint
            // (Rook/Ceph, MinIO, R2, ...) via endpoint override + path-style access -- nothing
            // MinIO-specific is needed. Version verified against Maven Central on 2026-08-08.
            version("aws-sdk", "2.46.7")

            // cron-utils: parses/evaluates the Cron expression in WorldSourceSpec.poll
            // (phase 2b, task 6). Chosen over hand-rolling a parser (an explicitly named
            // known error source in the task brief) and over pulling in a full scheduler
            // framework (Quartz, Spring) just for "is this cron string due yet" -- this
            // operator never runs cron jobs itself, JOSDK's own reschedule mechanism does
            // that; only expression parsing + next-execution-time math is needed, which is
            // exactly cron-utils' scope. Verified against Maven Central on 2026-08-09: 9.2.1
            // is the newest release (last published 2023-03, no newer version exists). Its
            // POM (also checked directly) has exactly one non-test runtime dependency,
            // slf4j-api (compile scope) -- javax.validation:validation-api is "provided"
            // (only needed if bean-validation annotations are actually exercised, which
            // CronParser/ExecutionTime do not do), so this stays the "slim library" the
            // brief asks for rather than a heavyweight addition.
            version("cron-utils", "9.2.1")

            // Micronaut, for the `api` module (phase 5a, task 1) -- REST + SSE over the CRs,
            // with Micronaut Security validating JWTs against a configurable issuer (the
            // identity broker in front of Apus is intentionally undecided, see design spec
            // §15). No Micronaut Gradle plugin is used, in keeping with this project's own
            // convention of a hand-written inline catalog rather than a generated one (see
            // minestom-knowledge:gradle) -- these three artifact families are added directly,
            // the same way josdk/fabric8/aws-sdk are above. Versions verified against Maven
            // Central on 2026-08-09 via each artifact's maven-metadata.xml (<release>) and
            // cross-checked against io.micronaut.platform:micronaut-platform:5.1.0's own POM,
            // which pins exactly this combination (micronaut.core.version=5.1.10,
            // micronaut.security.version=5.3.1, micronaut.serialization.version=3.1.0) --
            // Micronaut 5 is the current major; there is no newer 4.x release to prefer over it.
            version("micronaut", "5.1.10")
            version("micronaut-security", "5.3.1")
            version("micronaut-serde", "3.1.0")
            // Test-only (phase 5a consolidation, part 2): micronaut-test-junit5 versions
            // independently of micronaut-core -- verified against Maven Central on 2026-08-09,
            // 5.1.0 is the newest io.micronaut.test:micronaut-test-bom release and is the one
            // the io.micronaut.platform:micronaut-platform:5.1.0 BOM (already cross-checked
            // above for the other Micronaut coordinates) pins for this major.
            version("micronaut-test", "5.1.0")

            library("micronaut.core.bom", "io.micronaut", "micronaut-core-bom").versionRef("micronaut")
            library("micronaut.inject.java", "io.micronaut", "micronaut-inject-java").withoutVersion()
            library("micronaut.http.server.netty", "io.micronaut", "micronaut-http-server-netty").withoutVersion()
            library("micronaut.runtime", "io.micronaut", "micronaut-runtime").withoutVersion()
            // Test-only: backs the `@Client("/") HttpClient` micronaut-test-junit5 injects into
            // `@MicronautTest` classes, so the phase 5a consolidation's HTTP-level security tests
            // (401/403/404) exercise the real embedded server and filter chain instead of calling
            // controller methods directly.
            library("micronaut.http.client", "io.micronaut", "micronaut-http-client").withoutVersion()

            library("micronaut.security.bom", "io.micronaut.security", "micronaut-security-bom")
                .versionRef("micronaut-security")
            library("micronaut.security.jwt", "io.micronaut.security", "micronaut-security-jwt").withoutVersion()
            library("micronaut.security.annotations", "io.micronaut.security", "micronaut-security-annotations")
                .withoutVersion()

            library("micronaut.serde.bom", "io.micronaut.serde", "micronaut-serde-bom").versionRef("micronaut-serde")
            library("micronaut.serde.jackson", "io.micronaut.serde", "micronaut-serde-jackson").withoutVersion()
            library("micronaut.serde.processor", "io.micronaut.serde", "micronaut-serde-processor").withoutVersion()

            library("micronaut.test.bom", "io.micronaut.test", "micronaut-test-bom").versionRef("micronaut-test")
            library("micronaut.test.junit5", "io.micronaut.test", "micronaut-test-junit5").withoutVersion()

            // The full fabric8 client (not just kubernetes-client-api): the `api` module reads
            // Tenant/BlueMapMap/BlueMapRender/... CRs directly (see operator dependency below),
            // and unlike :operator it does not get these transitively, because :operator itself
            // depends on JOSDK/fabric8 via `implementation`, which -- correctly -- does not leak
            // onto a downstream project's compile classpath (verified directly: referencing
            // Tenant from a first draft of this module failed to compile with "class file for
            // io.fabric8.kubernetes.client.CustomResource not found" until this was added).
            // kubernetes-httpclient-jdk is picked as the HTTP engine over the vertx/okhttp
            // options fabric8 7.x supports: it needs no extra dependency of its own, and -- more
            // importantly -- avoids pulling a second, differently-versioned Netty into a module
            // whose own HTTP server (micronaut-http-server-netty, above) already brings one.
            library("fabric8.kubernetes.client", "io.fabric8", "kubernetes-client").versionRef("fabric8")
            library("fabric8.httpclient.jdk", "io.fabric8", "kubernetes-httpclient-jdk").versionRef("fabric8")

            library("bluemap.api", "de.bluecolored", "bluemap-api").versionRef("bluemap-api")
            library("bluemap.core", "de.bluecolored", "bluemap-core").versionRef("bluemap")
            library("bluemap.common", "de.bluecolored", "bluemap-common").versionRef("bluemap")

            library("junit.bom", "org.junit", "junit-bom").versionRef("junit")
            library("junit.jupiter", "org.junit.jupiter", "junit-jupiter").withoutVersion()
            library("junit.platform.launcher", "org.junit.platform", "junit-platform-launcher").withoutVersion()

            library("testcontainers.bom", "org.testcontainers", "testcontainers-bom").versionRef("testcontainers")
            library("testcontainers.junit", "org.testcontainers", "junit-jupiter").withoutVersion()
            library("testcontainers.minio", "org.testcontainers", "minio").withoutVersion()
            library("testcontainers.k3s", "org.testcontainers", "k3s").withoutVersion()

            library("josdk", "io.javaoperatorsdk", "operator-framework").versionRef("josdk")
            library("josdk.junit", "io.javaoperatorsdk", "operator-framework-junit").versionRef("josdk")
            library("crd.generator.api.v2", "io.fabric8", "crd-generator-api-v2").versionRef("fabric8")
            library("crd.generator.collector", "io.fabric8", "crd-generator-collector").versionRef("fabric8")
            library("fabric8.junit", "io.fabric8", "kubernetes-junit-jupiter").versionRef("fabric8")
            // @EnableKubernetesMockClient lives here, NOT in kubernetes-junit-jupiter
            // (that one targets tests against a real cluster and ships no mock classes).
            library("fabric8.server.mock", "io.fabric8", "kubernetes-server-mock").versionRef("fabric8")

            library("aws.sdk.bom", "software.amazon.awssdk", "bom").versionRef("aws-sdk")
            // software.amazon.awssdk.services.s3.presigner.S3Presigner (used by the `api` module's
            // POST /api/uploads, design spec §11.1, to hand out presigned multipart-upload part
            // URLs) ships inside this same artifact in this SDK major version -- verified directly
            // against the resolved s3-2.46.7.jar on 2026-08-09; there is no separate
            // `s3-presigner` artifact to depend on (an earlier SDK version did have one).
            library("aws.sdk.s3", "software.amazon.awssdk", "s3").withoutVersion()

            library("jackson.bom", "com.fasterxml.jackson", "jackson-bom").versionRef("jackson")
            library("jackson.databind", "com.fasterxml.jackson.core", "jackson-databind").withoutVersion()

            library("cron.utils", "com.cronutils", "cron-utils").versionRef("cron-utils")

            // Paper API, for :paper-worldpush (phase 6, task 1) -- the plugin that lets a live
            // Paper server push its own world instead of Apus pulling it. Deliberately its own
            // version() entry rather than reusing anything above: like bluemap-core/bluemap-api,
            // this tracks a fast-moving third-party project (see §4 of the design spec, "eigene
            // Release-Spur"), not this repo's own version. Pinned to a specific stable build
            // rather than the floating "26.2.build.+" range PaperMC's own setup docs show, to
            // keep this build reproducible -- the same reasoning already applied to every other
            // pinned version in this catalog. Verified against
            // https://repo.papermc.io/repository/maven-public/io/papermc/paper/paper-api/maven-metadata.xml
            // on 2026-08-09: 26.2.build.111-stable is the newest build on the "stable" channel
            // (id 111, 2026-08-07). Minecraft/Paper 26.2 is the current version line (PaperMC
            // moved off the old 1.21.x scheme); api-version in paper-plugin.yml uses the short
            // "26.2" form the same metadata/docs use.
            version("paper-api", "26.2.build.111-stable")
            library("paper.api", "io.papermc.paper", "paper-api").versionRef("paper-api")

            // Observability. Versions verified against Maven Central on 2026-08-15.
            //
            // Logging is SLF4J over Logback in every service. Logback writes a readable line to
            // the console -- that is what `kubectl logs` shows -- while the OpenTelemetry appender
            // ships the same event through OTLP. Nothing scrapes stdout for ingestion; the console
            // is for humans, OTLP is for the pipeline.
            version("slf4j", "2.0.17")
            version("logback", "1.5.18")
            library("slf4j.api", "org.slf4j", "slf4j-api").versionRef("slf4j")
            library("logback.classic", "ch.qos.logback", "logback-classic").versionRef("logback")

            // The stable OTel BOM covers the API, SDK, autoconfigure and the OTLP exporter.
            version("opentelemetry", "1.51.0")
            library("opentelemetry.bom", "io.opentelemetry", "opentelemetry-bom").versionRef("opentelemetry")
            library("opentelemetry.api", "io.opentelemetry", "opentelemetry-api").withoutVersion()
            library("opentelemetry.sdk", "io.opentelemetry", "opentelemetry-sdk").withoutVersion()
            library("opentelemetry.exporter.otlp", "io.opentelemetry", "opentelemetry-exporter-otlp")
                .withoutVersion()
            // Reads OTEL_* environment variables and builds the SDK from them, so which collector
            // receives the data is a deployment decision, never a code change. Without an endpoint
            // configured the SDK is a no-op, which is what makes this safe to ship enabled.
            library("opentelemetry.sdk.autoconfigure", "io.opentelemetry",
                "opentelemetry-sdk-extension-autoconfigure").withoutVersion()

            // The Logback appender lives in the instrumentation project, which versions separately
            // from the core BOM and carries an -alpha suffix by that project's convention -- the
            // appender API itself has been stable for several releases.
            version("opentelemetry-instrumentation", "2.16.0-alpha")
            library("opentelemetry.instrumentation.bom", "io.opentelemetry.instrumentation",
                "opentelemetry-instrumentation-bom-alpha").versionRef("opentelemetry-instrumentation")
            library("opentelemetry.logback.appender", "io.opentelemetry.instrumentation",
                "opentelemetry-logback-appender-1.0").withoutVersion()

            plugin("spotless", "com.diffplug.spotless").versionRef("spotless")
            plugin("shadow", "com.gradleup.shadow").versionRef("shadow")
        }
    }
}
