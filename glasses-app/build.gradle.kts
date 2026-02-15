import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.example.rokidglasses"
    compileSdk = 36

    val localPropsFile = rootProject.file("local.properties")
    val localProps = Properties().apply {
        if (localPropsFile.exists()) {
            localPropsFile.inputStream().use { load(it) }
        }
    }
    fun requiredLocalProperty(name: String): String {
        return localProps.getProperty(name)?.takeIf { it.isNotBlank() }
            ?: throw org.gradle.api.GradleException(
                "Missing required property '$name' in local.properties for release signing"
            )
    }

    defaultConfig {
        applicationId = "com.example.rokidglasses"
        minSdk = 28
        targetSdk = 34
        versionCode = 1
        versionName = "0.9.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // API Keys - Read from local.properties, do not hardcode
        // Rokid SDK Configuration
        buildConfigField("String", "ROKID_CLIENT_SECRET", "\"${localProps.getProperty("ROKID_CLIENT_SECRET", "")}\"")

        // API Keys
        buildConfigField("String", "GEMINI_API_KEY", "\"${localProps.getProperty("GEMINI_API_KEY", "")}\"")
        buildConfigField("String", "OPENAI_API_KEY", "\"${localProps.getProperty("OPENAI_API_KEY", "")}\"")
    }

    signingConfigs {
        create("release") {
            storeFile = rootProject.file(requiredLocalProperty("RELEASE_STORE_FILE"))
            storePassword = requiredLocalProperty("RELEASE_STORE_PASSWORD")
            keyAlias = requiredLocalProperty("RELEASE_KEY_ALIAS")
            keyPassword = requiredLocalProperty("RELEASE_KEY_PASSWORD")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("release")
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
    
    // Package local AAR files (for Rokid CXR SDK)
    // 16KB page alignment for Android 15+ compatibility
    packaging {
        jniLibs {
            useLegacyPackaging = false
        }
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
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

// Note: Local AAR repository configured in settings.gradle.kts

dependencies {
    // Common module
    implementation(project(":common"))
    
    // AndroidX Core (lightweight)
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.7.0")
    implementation("androidx.activity:activity-compose:1.8.2")
    
    // Compose (basic components only)
    implementation(platform("androidx.compose:compose-bom:2024.02.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.material3:material3")
    
    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    
    // Gson for JSON
    implementation("com.google.code.gson:gson:2.10.1")
    
    // OkHttp for API calls
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    
    // Google AI (Gemini)
    implementation("com.google.ai.client.generativeai:generativeai:0.2.2")
    
    // Bluetooth
    implementation("androidx.bluetooth:bluetooth:1.0.0-alpha02")
    
    // Rokid CXR-S SDK (Glasses Service SDK - via Maven)
    // Used for receiving phone messages and sending data
    implementation("com.rokid.cxr:cxr-service-bridge:1.0-20250519.061355-45")
    
    // Debug
    debugImplementation("androidx.compose.ui:ui-tooling")

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
}
