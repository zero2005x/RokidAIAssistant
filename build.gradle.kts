// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    id("org.sonarqube") version "6.0.1.5171"
}

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
            "**/*Binding.java",
            "**/*Binding.kt"
        ).joinToString(","))
    }
}
