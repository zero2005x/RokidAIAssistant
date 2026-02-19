# ==============================================================================
# Phone App ProGuard / R8 Rules
# ==============================================================================

# ── Common module ─────────────────────────────────────────────────────────────
-keep class com.example.rokidcommon.** { *; }

# ── Rokid CXR SDK (JNI) ──────────────────────────────────────────────────────
# The native library resolves methods by exact JNI signature.
# R8 must not rename, shrink, or strip these classes.
-keep class com.rokid.cxr.** { *; }
-keep class com.rokid.cxr.client.** { *; }
-dontwarn com.rokid.cxr.**

# ── Google Generative AI (Gemini) ────────────────────────────────────────────
-keep class com.google.ai.client.generativeai.** { *; }

# ── OkHttp ────────────────────────────────────────────────────────────────────
-dontwarn okhttp3.**
-keep class okhttp3.** { *; }
-dontwarn okio.**

# ── Retrofit ──────────────────────────────────────────────────────────────────
-keep class retrofit2.** { *; }
-keepattributes Signature
-keepattributes Exceptions
-keepattributes *Annotation*
-dontwarn retrofit2.**
# Keep generic signatures used by Retrofit service methods
-keepattributes RuntimeVisibleAnnotations, RuntimeVisibleParameterAnnotations
-keepclassmembers,allowshrinking,allowobfuscation interface * {
    @retrofit2.http.* <methods>;
}
-dontwarn javax.annotation.**

# ── Gson ──────────────────────────────────────────────────────────────────────
-keep class com.google.gson.** { *; }
-keepattributes Signature
-keepattributes *Annotation*
-keep class * extends com.google.gson.TypeAdapter
-keep class * implements com.google.gson.TypeAdapterFactory
-keep class * implements com.google.gson.JsonSerializer
-keep class * implements com.google.gson.JsonDeserializer
# Keep fields annotated with @SerializedName
-keepclassmembers,allowobfuscation class * {
    @com.google.gson.annotations.SerializedName <fields>;
}

# ── Room ──────────────────────────────────────────────────────────────────────
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *
-keep @androidx.room.Dao class * { *; }
-keepclassmembers class * {
    @androidx.room.* <methods>;
}
-dontwarn androidx.room.paging.**

# ── Kotlin Serialization ─────────────────────────────────────────────────────
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers @kotlinx.serialization.Serializable class ** {
    *** Companion;
}
-keepclasseswithmembers class ** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.example.rokidphone.**$$serializer { *; }
-keepclassmembers class com.example.rokidphone.** {
    *** Companion;
}
-keepclasseswithmembers class com.example.rokidphone.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# ── Kotlin Coroutines ─────────────────────────────────────────────────────────
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepclassmembers class kotlinx.coroutines.** {
    volatile <fields>;
}
-dontwarn kotlinx.coroutines.**

# ── DataStore ─────────────────────────────────────────────────────────────────
-keep class androidx.datastore.** { *; }
-keepclassmembers class * extends androidx.datastore.preferences.protobuf.GeneratedMessageLite {
    <fields>;
}

# ── Coil ──────────────────────────────────────────────────────────────────────
-dontwarn coil.**

# ── General ───────────────────────────────────────────────────────────────────
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
