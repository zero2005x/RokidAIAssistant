import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.example.rokidphone"
    compileSdk = 36

    val localPropsFile = rootProject.file("local.properties")
    val localProps = Properties().apply {
        if (localPropsFile.exists()) {
            localPropsFile.inputStream().use { load(it) }
        }
    }
    fun localProperty(name: String): String? = localProps.getProperty(name)?.takeIf { it.isNotBlank() }

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

    defaultConfig {
        applicationId = "com.example.rokidphone"
        minSdk = 28
        targetSdk = 34
        versionCode = 1
        versionName = "0.9.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // API Keys - Read from local.properties, do not hardcode.
        val geminiKey = localProps.getProperty("GEMINI_API_KEY", "")
        val openaiKey = localProps.getProperty("OPENAI_API_KEY", "")
        buildConfigField("String", "GEMINI_API_KEY", "\"$geminiKey\"")
        buildConfigField("String", "OPENAI_API_KEY", "\"$openaiKey\"")
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = rootProject.file(releaseStoreFile!!)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            } else {
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
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
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
    // Used for connecting to glasses, device control, and photo capture
    implementation("com.rokid.cxr:client-m:1.0.4")
    
    // CXR SDK required dependencies
    implementation("com.squareup.retrofit2:retrofit:3.0.0")
    implementation("com.squareup.retrofit2:converter-gson:3.0.0")
    implementation("com.squareup.okhttp3:logging-interceptor:5.3.2")
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
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    testImplementation("io.mockk:mockk:1.13.16")
    testImplementation("com.squareup.okhttp3:mockwebserver:5.0.0-alpha.14")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("com.google.truth:truth:1.4.4")

    // Android Instrumented Test
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:rules:1.6.1")
    androidTestImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    androidTestImplementation("io.mockk:mockk-android:1.13.16")
    androidTestImplementation("com.squareup.okhttp3:mockwebserver:5.0.0-alpha.14")
    androidTestImplementation("androidx.room:room-testing:2.8.4")
}
