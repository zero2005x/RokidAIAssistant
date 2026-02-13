pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        // Rokid Maven repository (must be first for CXR SDK)
        maven { url = uri("https://maven.rokid.com/repository/maven-public/") }
        google()
        mavenCentral()
        // Alibaba Cloud Mirror (Accelerated)
        maven { url = uri("https://maven.aliyun.com/repository/google") }
    }
}

rootProject.name = "RokidAIAssistant"

// Original integrated version of the app (for development and testing purposes)
include(":app")

// Modular Architecture

include(":common") // Shared Modules (Communication Protocol, Constants)

include(":phone-app") // Mobile App (AI Computation, Heavy Processing)

include(":glasses-app") // Glasses App (Display, Input)
