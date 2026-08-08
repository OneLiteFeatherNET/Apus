plugins {
    alias(libs.plugins.spotless) apply false
}

subprojects {
    apply(plugin = "java")
    apply(plugin = "com.diffplug.spotless")

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
