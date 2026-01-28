# Rokid AI Assistant

> 📖 [繁體中文版](doc/zh-TW/README.md)

**AI-powered voice and vision assistant for Rokid AR glasses.**

---

## 🚀 Quick Start (5 minutes)

```bash
# 1. Clone
git clone https://github.com/your-repo/RokidAIAssistant.git && cd RokidAIAssistant

# 2. Configure API keys
cp local.properties.template local.properties
# Edit local.properties → Add your GEMINI_API_KEY (required)

# 3. Build & Install
./gradlew :phone-app:installDebug    # Install phone app
./gradlew :glasses-app:installDebug  # Install glasses app (on Rokid device)
```

> **Minimum requirement**: Only `GEMINI_API_KEY` is needed to run. Get one at [Google AI Studio](https://ai.google.dev/).

---

## Scope

### In Scope

- Voice-to-text transcription and AI chat on Rokid AR glasses
- Photo capture from glasses camera with AI image analysis
- Phone ↔ Glasses communication via Rokid CXR SDK
- Multiple AI/STT provider support (Gemini, OpenAI, Anthropic, etc.)
- Conversation history persistence

### Out of Scope

- Standalone glasses-only operation (phone required for AI processing)
- Offline AI inference
- Video streaming or real-time AR overlays

---

## Features

| Feature                 | Description                                                                                                                                                                                                                                |
| ----------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| 🎤 Voice Interaction    | Speak to AI through glasses or phone                                                                                                                                                                                                       |
| 📷 Photo Analysis       | Capture images with glasses camera, get AI analysis                                                                                                                                                                                        |
| 🤖 Multi-AI Providers   | 11 providers: Gemini, OpenAI, Anthropic, Claude, DeepSeek, Groq, Baidu, Alibaba (Qwen), Zhipu (GLM), Perplexity, xAI                                                                                                                       |
| 🎧 Multi-STT Providers  | 18 providers: Gemini, OpenAI Whisper, Groq Whisper, Deepgram, AssemblyAI, Azure Speech, iFLYTEK, Google Cloud STT, AWS Transcribe, Alibaba ASR, Tencent ASR, Baidu ASR, IBM Watson, Huawei SIS, Volcengine, Rev.ai, Speechmatics, Otter.ai |
| 📱 Phone-Glasses Comm   | Via Rokid CXR SDK and Bluetooth SPP                                                                                                                                                                                                        |
| 💬 Conversation History | Room database persistence                                                                                                                                                                                                                  |
| 🌍 Multi-Language       | 13 languages: English, 简体中文, 繁體中文, 日本語, 한국어, Español, Français, Italiano, Русский, Українська, العربية, Tiếng Việt, ไทย                                                                                                      |

---

## Module / Directory Guide

```
RokidAIAssistant/
├── phone-app/                    # 📱 Phone app (main AI hub)
│   └── src/main/java/.../rokidphone/
│       ├── MainActivity.kt       # Entry point
│       ├── service/ai/           # AI provider implementations
│       ├── service/stt/          # STT provider implementations
│       ├── service/cxr/          # CXR SDK manager
│       ├── data/db/              # Room database
│       ├── ui/                   # Compose UI screens
│       └── viewmodel/            # ViewModels
│
├── glasses-app/                  # 👓 Glasses app (display/input)
│   └── src/main/java/.../rokidglasses/
│       ├── MainActivity.kt       # Entry point
│       ├── service/photo/        # Camera service
│       ├── ui/                   # Compose UI
│       └── viewmodel/            # GlassesViewModel
│
├── common/                       # 📦 Shared protocol library
│   └── src/main/java/.../rokidcommon/
│       ├── Constants.kt          # Shared constants
│       └── protocol/             # Message, MessageType, ConnectionState
│
├── app/                          # 🧪 Original integrated app (dev only)
├── doc/                          # 📚 Documentation
└── gradle/libs.versions.toml     # Version catalog
```

| Module        | App ID                     | Purpose                               |
| ------------- | -------------------------- | ------------------------------------- |
| `phone-app`   | `com.example.rokidphone`   | AI processing, STT, CXR SDK, database |
| `glasses-app` | `com.example.rokidglasses` | Display, camera, wake word            |
| `common`      | (library)                  | Shared protocol & constants           |

---

## Technology Stack

| Category    | Technology                   | Version        |
| ----------- | ---------------------------- | -------------- |
| Language    | Kotlin                       | 2.2.10         |
| Min SDK     | Android                      | 28 (9.0 Pie)   |
| Target SDK  | Android                      | 34 (14)        |
| Compile SDK | Android                      | 36             |
| Build       | Gradle + Kotlin DSL          | 9.0            |
| UI          | Jetpack Compose + Material 3 | BOM 2026.01.00 |
| Async       | Kotlin Coroutines            | 1.10.2         |
| Database    | Room                         | 2.8.4          |
| Networking  | Retrofit + OkHttp            | 3.0 / 5.3      |
| Rokid SDK   | CXR client-m                 | 1.0.4          |

---

## Build & Run

### Prerequisites

- **Android Studio**: Ladybug (2024.2) or later
- **JDK**: 17
- **Android SDK**: API 36 installed

### Environment Setup

```bash
# Copy template and edit with your keys
cp local.properties.template local.properties
```

**Required keys in `local.properties`:**

```properties
# Required
GEMINI_API_KEY=your_gemini_api_key

# Required for glasses connection
ROKID_CLIENT_SECRET=your_rokid_secret_without_hyphens

# Optional
OPENAI_API_KEY=your_openai_key
ANTHROPIC_API_KEY=your_anthropic_key
```

### Gradle Commands

```bash
# Build all modules (debug)
./gradlew assembleDebug

# Build specific module
./gradlew :phone-app:assembleDebug
./gradlew :glasses-app:assembleDebug

# Install to connected device
./gradlew :phone-app:installDebug
./gradlew :glasses-app:installDebug

# Build release APK
./gradlew assembleRelease

# Clean build
./gradlew clean
```

### APK Output Locations

```
phone-app/build/outputs/apk/debug/phone-app-debug.apk
phone-app/build/outputs/apk/release/phone-app-release.apk
glasses-app/build/outputs/apk/debug/glasses-app-debug.apk
glasses-app/build/outputs/apk/release/glasses-app-release.apk
```

---

## Debug vs Release

| Aspect       | Debug            | Release                       |
| ------------ | ---------------- | ----------------------------- |
| Minification | ❌ Disabled      | ✅ Enabled (ProGuard)         |
| Debuggable   | ✅ Yes           | ❌ No                         |
| Signing      | Debug keystore   | Release keystore (required)   |
| BuildConfig  | API keys visible | API keys visible (obfuscated) |
| Performance  | Slower           | Optimized                     |

### ProGuard Rules

- `phone-app/proguard-rules.pro` - Keeps Gemini, OkHttp, Gson, common protocol
- `glasses-app/proguard-rules.pro` - Keeps CXR SDK, common protocol

---

## Testing

> ⚠️ **Note**: Unit tests are not yet implemented in this project.

### Manual Testing Checklist

1. **Phone App**
   - [ ] Launch app, verify Settings screen loads
   - [ ] Configure AI provider (Gemini), test text chat
   - [ ] Test voice input from phone microphone
   - [ ] Verify conversation history persists after restart

2. **Glasses App**
   - [ ] Install on Rokid glasses, verify UI displays
   - [ ] Test camera photo capture
   - [ ] Verify photo transfer to phone

3. **Integration**
   - [ ] Pair phone with glasses via CXR SDK
   - [ ] Test voice command from glasses → AI response displayed
   - [ ] Test photo capture → AI analysis → result displayed

### Running Instrumentation Tests (when available)

```bash
./gradlew :phone-app:connectedAndroidTest
./gradlew :glasses-app:connectedAndroidTest
```

---

## Common Developer Tasks

### Add a New AI Provider

1. Create implementation in `phone-app/src/.../service/ai/YourProvider.kt`
2. Implement `AiServiceProvider` interface (see [ARCHITECTURE.md](doc/ARCHITECTURE.md#ai-service-provider-interface))
3. Register in `AiServiceFactory.kt`
4. Add to `AiProvider` enum in settings

### Add a New Screen (Compose)

1. Create screen composable in `phone-app/src/.../ui/yourscreen/YourScreen.kt`
2. Create ViewModel in `phone-app/src/.../viewmodel/YourViewModel.kt`
3. Add route to `phone-app/src/.../ui/navigation/AppNavigation.kt`

### Add a New Permission

1. Add to `AndroidManifest.xml`:
   ```xml
   <uses-permission android:name="android.permission.YOUR_PERMISSION" />
   ```
2. Request at runtime (for dangerous permissions) in Activity/ViewModel

---

## FAQ & Troubleshooting

### Build Issues

**Q: Build fails with "API key not found"**

```
A: Ensure local.properties exists and contains GEMINI_API_KEY.
   Check the file is in project root, not in a module folder.
```

**Q: Gradle sync fails with version errors**

```
A: Ensure Android Studio has SDK 36 installed.
   File → Settings → SDK Manager → Install API 36.
```

**Q: JDK version mismatch**

```
A: Project requires JDK 17.
   File → Settings → Build → Gradle → Gradle JDK → Select JDK 17.
```

### Runtime Issues

**Q: App crashes on launch**

```
A: Check Logcat for missing API key errors.
   Ensure all required permissions are granted.
```

**Q: Cannot connect to glasses**

```
A: 1. Verify ROKID_CLIENT_SECRET is set (without hyphens)
   2. Enable Bluetooth on both devices
   3. Ensure glasses are in pairing mode
```

**Q: AI responses are empty**

```
A: 1. Verify API key is valid and has quota
   2. Check network connectivity
   3. Review Logcat for API error responses
```

### Release Issues

**Q: Release build fails with signing error**

```
A: Create a release keystore and configure in build.gradle.kts:
   signingConfigs {
       create("release") {
           storeFile = file("path/to/keystore.jks")
           storePassword = "password"
           keyAlias = "alias"
           keyPassword = "password"
       }
   }
```

**Q: ProGuard removes required classes**

```
A: Add keep rules to proguard-rules.pro:
   -keep class com.your.package.** { *; }
```

---

## Documentation

| Document                                                      | Description                                  |
| ------------------------------------------------------------- | -------------------------------------------- |
| [API Settings Guide](doc/API_SETTINGS.md)                     | Complete API configuration for all providers |
| [Architecture Overview](doc/ARCHITECTURE.md)                  | System design, data flow, component details  |
| [STT Implementation Status](doc/STT_IMPLEMENTATION_STATUS.md) | Complete status of all 18 STT providers      |

---

## License

This project is proprietary software.
