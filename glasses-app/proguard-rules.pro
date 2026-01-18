# Glasses App ProGuard Rules
# Keep Rokid SDK classes (when available)
-keep class com.rokid.cxr.** { *; }

# Keep Gson
-keep class com.google.gson.** { *; }

# Keep common protocol
-keep class com.example.rokidcommon.** { *; }
