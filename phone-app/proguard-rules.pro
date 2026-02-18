# Phone App ProGuard Rules
# Keep Gemini API classes
-keep class com.google.ai.client.generativeai.** { *; }

# Keep OkHttp
-dontwarn okhttp3.**
-keep class okhttp3.** { *; }

# Keep Gson
-keep class com.google.gson.** { *; }

# Keep common protocol
-keep class com.example.rokidcommon.** { *; }

# Keep CXR SDK classes — required for JNI.
# The native library libcxr-sock-proto-jni.so resolves methods by exact JNI signature
# (e.g. com/rokid/cxr/Caps.size()I). R8 must not rename, shrink, or strip these classes
# or their members, otherwise the JNI lookup fails at runtime with:
#   "caps-jni: cannot find method com/rokid/cxr/Caps.size()I"
-keep class com.rokid.cxr.** { *; }
-keep class com.rokid.cxr.client.** { *; }
-dontwarn com.rokid.cxr.**
