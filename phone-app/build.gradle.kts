import java.util.Properties
import org.gradle.api.tasks.testing.Test
import org.gradle.testing.jacoco.plugins.JacocoTaskExtension
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.kotlin.serialization)
    jacoco
}

val localPropsFile = rootProject.file("local.properties")
val localProps = Properties().apply {
    if (localPropsFile.exists()) {
        localPropsFile.inputStream().use { load(it) }
    }
}
fun localProperty(name: String): String? = localProps.getProperty(name)?.takeIf { it.isNotBlank() }

// Developer API keys (local.properties) are only ever baked into the `github` flavor.
// The `play` flavor always compiles empty constants, see productFlavors below.
val developerGeminiKey = localProps.getProperty("GEMINI_API_KEY", "")
val developerOpenAiKey = localProps.getProperty("OPENAI_API_KEY", "")


android {
    namespace = "io.github.zero2005x.glassesaicompanion"
    compileSdk = 36

    // GitHub APK signing (existing release key)
    val releaseStoreFile = localProperty("RELEASE_STORE_FILE")
    val releaseStorePassword = localProperty("RELEASE_STORE_PASSWORD")
    val releaseKeyAlias = localProperty("RELEASE_KEY_ALIAS")
    val releaseKeyPassword = localProperty("RELEASE_KEY_PASSWORD")
    val hasReleaseSigning = listOf(
        releaseStoreFile,
        releaseStorePassword,
        releaseKeyAlias,
        releaseKeyPassword
    ).all { !it.isNullOrBlank() }

    // Google Play upload key: a separate key from the GitHub release key on purpose,
    // so that leaking one never affects the other distribution channel.
    val playUploadStoreFile = localProperty("PLAY_UPLOAD_STORE_FILE")
    val playUploadStorePassword = localProperty("PLAY_UPLOAD_STORE_PASSWORD")
    val playUploadKeyAlias = localProperty("PLAY_UPLOAD_KEY_ALIAS")
    val playUploadKeyPassword = localProperty("PLAY_UPLOAD_KEY_PASSWORD")
    val hasPlayUploadSigning = listOf(
        playUploadStoreFile,
        playUploadStorePassword,
        playUploadKeyAlias,
        playUploadKeyPassword
    ).all { !it.isNullOrBlank() }

    defaultConfig {
        minSdk = 28
        targetSdk = 36

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("githubRelease") {
                storeFile = rootProject.file(releaseStoreFile!!)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
        if (hasPlayUploadSigning) {
            create("playUpload") {
                storeFile = rootProject.file(playUploadStoreFile!!)
                storePassword = playUploadStorePassword
                keyAlias = playUploadKeyAlias
                keyPassword = playUploadKeyPassword
            }
        }
    }

    flavorDimensions += "distribution"
    productFlavors {
        // Sideloaded APK distributed through GitHub releases. Keeps the legacy
        // application id so existing installs keep upgrading in place.
        create("github") {
            dimension = "distribution"
            applicationId = "com.example.rokidphone"
            versionCode = 5
            versionName = "1.1.0"
            buildConfigField("boolean", "PLAY_DISTRIBUTION", "false")
            buildConfigField("String", "GEMINI_API_KEY", "\"$developerGeminiKey\"")
            buildConfigField("String", "OPENAI_API_KEY", "\"$developerOpenAiKey\"")
            signingConfig = signingConfigs.findByName("githubRelease")
        }

        // Google Play distribution: curated provider list, no CXR SDK, no developer keys.
        create("play") {
            dimension = "distribution"
            applicationId = "io.github.zero2005x.glassesaicompanion"
            versionCode = 1
            versionName = "1.2.0"
            buildConfigField("boolean", "PLAY_DISTRIBUTION", "true")
            buildConfigField("String", "GEMINI_API_KEY", "\"\"")
            buildConfigField("String", "OPENAI_API_KEY", "\"\"")
            signingConfig = signingConfigs.findByName("playUpload")
        }
    }

    buildTypes {
        debug {
            enableUnitTestCoverage = true
            // A flavor-level signingConfig would otherwise leak into debug builds.
            signingConfig = signingConfigs.getByName("debug")
        }

        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (!hasReleaseSigning && !hasPlayUploadSigning) {
                logger.lifecycle("Release signing is not configured. Building unsigned release artifacts.")
            }
        }
    }
    
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    
    buildFeatures {
        compose = true
        buildConfig = true
    }
    
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.10"
    }
    
    // 16KB page alignment for Android 15+ compatibility
    packaging {
        jniLibs {
            useLegacyPackaging = false
        }
        resources {
            excludes += setOf("META-INF/LICENSE.md", "META-INF/LICENSE-notice.md")
        }
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }

    lint {
        baseline = file("lint-baseline.xml")
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

tasks.withType<Test>().configureEach {
    extensions.configure(JacocoTaskExtension::class.java) {
        isIncludeNoLocationClasses = true
        excludes = listOf("jdk.internal.*")
    }
}

dependencies {
    // Common module
    implementation(project(":common"))
    
    // AndroidX Core
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.10.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.10.0")
    implementation("androidx.activity:activity-compose:1.12.2")
    
    // Compose
    implementation(platform("androidx.compose:compose-bom:2026.01.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    
    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    
    // Google AI (Gemini)
    implementation("com.google.ai.client.generativeai:generativeai:0.9.0")
    
    // OkHttp for API calls
    implementation("com.squareup.okhttp3:okhttp:5.3.2")
    
    // Gson for JSON
    implementation("com.google.code.gson:gson:2.13.2")
    
    // Bluetooth
    implementation("androidx.bluetooth:bluetooth:1.0.0-alpha02")
    
    // Rokid CXR-M SDK (Mobile SDK - via Maven)
    // Used for connecting to glasses, device control, and photo capture.
    // Intentionally GitHub-flavor only: the Google Play flavor ships without the SDK
    // (no CXR classes, no native libraries, no SN auth file).
    "githubImplementation"("com.rokid.cxr:client-m:1.0.4")

    // CXR SDK required dependencies (not used by app code itself)
    "githubImplementation"("com.squareup.retrofit2:retrofit:3.0.0")
    "githubImplementation"("com.squareup.retrofit2:converter-gson:3.0.0")
    "githubImplementation"("com.squareup.okhttp3:logging-interceptor:5.3.2")
    implementation("com.squareup.okio:okio:3.16.4")
    
    // DataStore for preferences
    implementation("androidx.datastore:datastore-preferences:1.2.0")
    
    // Security Crypto for encrypted SharedPreferences
    implementation("androidx.security:security-crypto:1.1.0")
    
    // Coil for image loading in Compose
    implementation("io.coil-kt:coil-compose:2.7.0")
    
    // Room Database for conversation persistence
    implementation("androidx.room:room-runtime:2.8.4")
    implementation("androidx.room:room-ktx:2.8.4")
    ksp(libs.room.compiler)
    
    // Kotlin Serialization
    implementation(libs.kotlinx.serialization.json)
    
    // Navigation Compose
    implementation("androidx.navigation:navigation-compose:2.9.6")
    
    // Debug
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    // Unit Test
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.mockk)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.robolectric)
    testImplementation(libs.truth)
    testImplementation(libs.androidx.test.core)

    // Android Instrumented Test
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.mockk.android)
    androidTestImplementation(libs.okhttp.mockwebserver)
    androidTestImplementation("androidx.room:room-testing:2.8.4")
}

// Release guard: never ship a developer's personal API keys inside a release APK/AAB.
// The play flavor compiles empty constants, so only the github flavor can trip this.
// Opt out explicitly with -PallowDeveloperKeysInRelease=true for a private build.
val verifyNoDeveloperKeysInRelease = tasks.register("verifyNoDeveloperKeysInRelease") {
    group = "verification"
    description = "Fails if local.properties developer API keys would be embedded in a release build."
    val keysPresent = developerGeminiKey.isNotBlank() || developerOpenAiKey.isNotBlank()
    val allowed = providers.gradleProperty("allowDeveloperKeysInRelease").orNull == "true"
    doLast {
        if (keysPresent && !allowed) {
            throw GradleException(
                "GEMINI_API_KEY / OPENAI_API_KEY are set in local.properties and would be embedded " +
                    "in the release build. Remove them (users enter keys in the app), or pass " +
                    "-PallowDeveloperKeysInRelease=true for a private build."
            )
        }
    }
}
tasks.configureEach {
    if (name.matches(Regex("(assemble|bundle|package)Github[A-Za-z]*Release"))) {
        dependsOn(verifyNoDeveloperKeysInRelease)
    }
}

// Room schema export location (required for exportSchema = true; commit the JSON schemas).
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}
