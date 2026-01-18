import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// 從 local.properties 讀取敏感資訊
val localProperties = Properties()
val localPropertiesFile = rootProject.file("local.properties")
if (localPropertiesFile.exists()) {
    localProperties.load(localPropertiesFile.inputStream())
}

android {
    namespace = "com.example.rokidaiassistant"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.rokidaiassistant"
        minSdk = 28
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        
        buildConfigField("String", "ROKID_CLIENT_SECRET", 
            "\"${localProperties.getProperty("ROKID_CLIENT_SECRET", "")}\"")
        buildConfigField("String", "GEMINI_API_KEY", 
            "\"${localProperties.getProperty("GEMINI_API_KEY", "")}\"")
        buildConfigField("String", "OPENAI_API_KEY", 
            "\"${localProperties.getProperty("OPENAI_API_KEY", "")}\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    
    kotlinOptions {
        jvmTarget = "11"
    }
    
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    // ========================================
    // Rokid CXR SDK
    // ========================================
    // 注意：真正的 SDK 需要從 Rokid 官方獲取
    // 目前使用 app/src/main/java/.../sdk/CxrApi.kt 模擬實作
    // 取得真正 SDK 後，取消下行註釋並刪除模擬實作
     implementation("com.rokid.cxr:client-m:1.0.4")
    
    // ========================================
    // 網路請求
    // ========================================
    implementation("com.squareup.retrofit2:retrofit:2.9.0")
    implementation("com.squareup.retrofit2:converter-gson:2.9.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")
    implementation("com.google.code.gson:gson:2.10.1")
    implementation("com.squareup.okio:okio:3.6.0")
    
    // ========================================
    // Coroutines
    // ========================================
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")
    
    // ========================================
    // Compose
    // ========================================
    implementation(platform("androidx.compose:compose-bom:2024.02.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.8.2")
    
    // ========================================
    // Lifecycle & ViewModel
    // ========================================
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.7.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.7.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    
    // ========================================
    // AndroidX Core
    // ========================================
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    
    // ========================================
    // Google Generative AI SDK (Gemini)
    // ========================================
    implementation("com.google.ai.client.generativeai:generativeai:0.2.2")
    
    // ========================================
    // Debug
    // ========================================
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    
    // ========================================
    // Testing
    // ========================================
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
}
