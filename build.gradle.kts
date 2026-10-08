// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    id("org.sonarqube") version "6.0.1.5171"
}

// Module -> debug variant that produces the coverage report. phone-app has product flavors;
// the `github` flavor is the superset of the code base (it also contains the CXR integration).
val coverageVariants = linkedMapOf(
    "common" to "debug",
    "glasses-app" to "debug",
    "phone-app" to "githubDebug"
)
sonar {
    properties {
        property("sonar.projectKey", "zero2005x_RokidAIAssistant")
        property("sonar.organization", "zero2005x")
        property("sonar.host.url", "https://sonarcloud.io")

        // Exclude Android-generated files and build artifacts from analysis
        property("sonar.exclusions", listOf(
            "**/R.java",
            "**/R\$*.java",
            "**/BuildConfig.java",
            "**/Manifest*.java",
            "**/*Binding.java",
            "**/*Binding.kt",
            "**/*BR.java",
            "**/*_Factory.java",
            "**/*_MembersInjector.java",
            "**/build/**",
            "**/assets/**",
            "**/res/**",
            "**/*.png",
            "**/*.webp",
            "**/*.xml"
        ).joinToString(","))

        // Coverage exclusions (same generated files)
        property("sonar.coverage.exclusions", listOf(
            "**/R.java",
            "**/R\$*.java",
            "**/BuildConfig.java",
            "**/Manifest*.java",
            "**/*Binding.java",
            "**/*Binding.kt",
            "**/*BR.java",
            "**/*_Factory.java",
            "**/*_MembersInjector.java",
            "**/ui/**",
            "**/MainActivity.kt"
        ).joinToString(","))

        property(
            "sonar.coverage.jacoco.xmlReportPaths",
            coverageVariants.entries.joinToString(",") { (module, variant) ->
                file("$module/build/reports/coverage/test/$variant/report.xml").absolutePath
            }
        )
    }
}

// Let Android register variant tasks before Gradle resolves these dependencies.
tasks.register("testCoverage") {
    group = "verification"
    description = "Run debug unit tests and generate coverage for every Android module."
    dependsOn(coverageVariants.map { (module, variant) ->
        ":$module:create${variant.replaceFirstChar { it.uppercase() }}UnitTestCoverageReport"
    })
}
