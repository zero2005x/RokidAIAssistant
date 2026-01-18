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
