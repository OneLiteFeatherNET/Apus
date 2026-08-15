plugins {
    alias(libs.plugins.spotless) apply false
}

version = "0.5.0" // x-release-please-version

subprojects {
    apply(plugin = "java")
    apply(plugin = "com.diffplug.spotless")

    // telemetry-addon and paper-worldpush carry their own release track (design spec §4)
    // and set their own version; every other module ships as part of the project as a whole.
    if (name != "telemetry-addon" && name != "paper-worldpush") {
        version = rootProject.version
    }

    extensions.configure<JavaPluginExtension> {
        toolchain {
            languageVersion.set(JavaLanguageVersion.of(25))
        }
    }

    extensions.configure<com.diffplug.gradle.spotless.SpotlessExtension> {
        java {
            importOrder()
            removeUnusedImports()
            removeWildcardImports()
            formatAnnotations()
            licenseHeaderFile(rootProject.file(".spotless/Copyright.java"))
        }
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
    }
}
