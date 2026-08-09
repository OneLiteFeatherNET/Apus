plugins {
    alias(libs.plugins.shadow)
}

dependencies {
    // Paper API only, never paper-server/paper-mojangapi -- a plugin compiles against the API
    // surface and runs inside whatever Paper build the operator actually deployed. See
    // settings.gradle.kts for why this version is pinned independently of the rest of the catalog.
    compileOnly(libs.paper.api)

    // AWS SDK v2 S3 client -- the same family already used by :ingest, so the project has exactly
    // one S3 client/credential-provider chain instead of two. See settings.gradle.kts's comment
    // on the `aws-sdk` version for the full rationale (also applies here unchanged).
    //
    // netty-nio-client excluded: it is the s3 artifact's *async*-client transport, pulled in as a
    // direct dependency regardless of whether it is used. S3WorldUploader only ever makes
    // blocking calls through the synchronous S3Client (apache5-client), so netty-nio-client's
    // entire Netty dependency tree is dead weight here -- and, unlike in :ingest (a standalone
    // process), shading an unrelated Netty version into a jar that loads inside a Paper server's
    // own JVM (which already bundles Netty for its own networking) is a real classpath-collision
    // risk worth avoiding outright rather than merely relocating.
    implementation(platform(libs.aws.sdk.bom))
    implementation(libs.aws.sdk.s3) {
        exclude(group = "software.amazon.awssdk", module = "netty-nio-client")
        // slf4j-api excluded too, for the same reason: Paper already puts exactly one
        // org.slf4j:slf4j-api on the server's runtime classpath (JavaPlugin#getSLF4JLogger()
        // depends on it existing there), so shading a second, independently-versioned copy in
        // alongside it is a classpath hazard rather than a safety net. compileOnly(libs.paper.api)
        // already supplies the same API surface for compilation.
        exclude(group = "org.slf4j", module = "slf4j-api")
    }

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks {
    // paper-plugin.yml's `version: '${version}'` is a Gradle resource-filtering placeholder
    // (Paper's own recommended pattern, see https://docs.papermc.io/paper/dev/project-setup/),
    // not YAML/Paper syntax -- it must be expanded here or every plugin build reports the
    // literal string "${version}" as its version.
    processResources {
        val props = mapOf("version" to project.version)
        inputs.properties(props)
        filesMatching("paper-plugin.yml") {
            expand(props)
        }
    }
    shadowJar {
        archiveClassifier.set("")
        archiveBaseName.set("apus-paper-worldpush")
        // Fixed name instead of the default "apus-paper-worldpush-<version>.jar" -- same
        // rationale as telemetry-addon/build.gradle.kts and ingest/build.gradle.kts: whatever
        // deploys this jar onto a Paper server (currently: a human, dropping it into `plugins/`)
        // needs a stable file name to reference, not one that changes on every release-please bump.
        archiveFileName.set("apus-paper-worldpush.jar")
        // The AWS SDK is the only runtime dependency this plugin ships; Paper itself is
        // compileOnly and provided by the server at runtime. Relocated to avoid clashing with
        // any other plugin on the same server that also shades an AWS SDK, however unlikely.
        relocate("software.amazon.awssdk", "net.onelitefeather.apus.paper.libs.awssdk")
        relocate("org.reactivestreams", "net.onelitefeather.apus.paper.libs.reactivestreams")
        relocate("org.apache.hc", "net.onelitefeather.apus.paper.libs.httpclient")
    }
    build {
        dependsOn(shadowJar)
    }
}
