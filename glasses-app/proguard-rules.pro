# ==============================================================================
# Glasses App ProGuard / R8 Rules
# ==============================================================================

# ── Common module ─────────────────────────────────────────────────────────────
-keep class com.example.rokidcommon.** { *; }

# ── Rokid CXR SDK (JNI) ──────────────────────────────────────────────────────
-keep class com.rokid.cxr.** { *; }
-dontwarn com.rokid.cxr.**

# ── Google Generative AI (Gemini) ────────────────────────────────────────────
-keep class com.google.ai.client.generativeai.** { *; }

# ── OkHttp ────────────────────────────────────────────────────────────────────
-dontwarn okhttp3.**
-keep class okhttp3.** { *; }
-dontwarn okio.**

# ── Gson ──────────────────────────────────────────────────────────────────────
-keep class com.google.gson.** { *; }
-keepattributes Signature
-keepattributes *Annotation*
-keepclassmembers,allowobfuscation class * {
    @com.google.gson.annotations.SerializedName <fields>;
}

# ── Kotlin Coroutines ─────────────────────────────────────────────────────────
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepclassmembers class kotlinx.coroutines.** {
    volatile <fields>;
}
-dontwarn kotlinx.coroutines.**

# ── General ───────────────────────────────────────────────────────────────────
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
