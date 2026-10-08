# Rokid AI Assistant

> 📖 [繁體中文版](doc/zh-TW/README.md)

**AI-powered voice and vision assistant for Rokid AR glasses.**


[![ko-fi](https://ko-fi.com/img/githubbutton_sm.svg)](https://ko-fi.com/liangtinglin)

---

## 🚀 Quick Start (5 minutes)

```bash
# 1. Clone
git clone https://github.com/zero2005x/RokidAIAssistant.git && cd RokidAIAssistant

# 2. (Optional) Configure API keys
cp local.properties.template local.properties
# Add any provider key — or skip this and enter keys later in the app's Settings screen.

# 3. Build & Install
ANDROID_SERIAL=PHONE_SERIAL ./gradlew :phone-app:installDebug    # Install phone app
ANDROID_SERIAL=GLASSES_SERIAL ./gradlew :glasses-app:installDebug  # Install glasses app (on Rokid device)
```

> **No AI key is required** to install the app or open Settings. Only the one
> provider you actually use needs a key (entered in-app, stored encrypted with
> Android Keystore). `ROKID_CLIENT_SECRET` is only needed for glasses pairing.

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
- Video streaming or real-time AR overlays

### On-Device Inference (experimental)

- An optional **On-Device Gemma** provider runs a Gemma model locally with **no API key and no network**.
- It is **text-only**: speech-to-text and image understanding gracefully report that they are unsupported.
- Models are **not bundled** with the app. Place a Gemma model file (`.task` or `.gguf`) into the app-private
  model directory (`filesDir/models/gemma`) to make it selectable; the installed model then appears in the model
  catalog alongside the verified defaults.
- The runtime engine binding (MediaPipe LLM Inference / llama.cpp) is pluggable: until an engine is wired, the
  provider reports that no on-device model is loaded instead of silently falling back to the cloud.

---

## Features

Phone Settings now includes optional [Jev / Laya difficulty-based model routing and a glasses display preview](doc/DECISION_ROUTING_AND_DISPLAY.md). Routing is limited to text and transcribed speech. Glasses font, text size, and safe display area are saved when you tap Apply and sync over Bluetooth, including after reconnecting.

| Feature                 | Description                                                                                                                                                                                                                                |
| ----------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| 🎤 Voice Interaction    | Speak to AI through glasses or phone                                                                                                                                                                                                       |
| 📷 Photo Analysis       | Capture images with glasses camera, get AI analysis                                                                                                                                                                                        |
| 🎙️ Recording & Analysis | Record audio from phone or glasses with auto AI transcription and analysis                                                                                                                                                                 |
| 🤖 Multi-AI Providers   | 15 providers: Gemini, OpenAI, Anthropic, DeepSeek, Groq, xAI, Alibaba (Qwen), Z.AI (GLM), Baidu Qianfan, Perplexity, Moonshot (Kimi), Mistral, Gemini Live, AnythingLLM, Custom (OpenAI-compatible) — models loaded dynamically from each provider's Models API |
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

| Category    | Technology                   | Version              |
| ----------- | ---------------------------- | -------------------- |
| Language    | Kotlin                       | 2.2.10               |
| Min SDK     | Android                      | 28 (9.0 Pie)         |
| Target SDK  | Android                      | 34 (14)              |
| Compile SDK | Android                      | 36                   |
| Build       | Gradle + Kotlin DSL          | AGP 9.0 / Gradle 9.3 |
| UI          | Jetpack Compose + Material 3 | BOM 2026.01.00       |
| Async       | Kotlin Coroutines            | 1.10.2               |
| Database    | Room                         | 2.8.4                |
| Networking  | Retrofit + OkHttp            | 3.0 / 5.3            |
| Rokid SDK   | CXR client-m                 | 1.0.4                |

---

## Build & Run

### Prerequisites

- **Android Studio**: Ladybug (2024.2) or later
- **JDK**: 21 (recommended for AGP 9)
- **Android SDK**: API 36 installed

### Environment Setup

```bash
# Copy template and edit with your keys
cp local.properties.template local.properties
```

**Keys in `local.properties` (all optional at build time):**

```properties
# Optional — any provider key can also be entered in-app
GEMINI_API_KEY=your_gemini_api_key
OPENAI_API_KEY=your_openai_key
ANTHROPIC_API_KEY=your_anthropic_key

# Required only for glasses connection
ROKID_CLIENT_SECRET=your_rokid_secret_without_hyphens
```

> The app picks models dynamically from each provider's official Models API
> (live → 24h cache → verified fallback → manual ID). Fallback model lists are
> verified against official docs — see `FallbackModelCatalog.kt`
> (Last verified: 2026-08-02).

### Gradle Commands

```bash
# Build all modules (debug)
./gradlew assembleDebug

# Build specific module
./gradlew :phone-app:assembleDebug
./gradlew :glasses-app:assembleDebug

# Install to connected device
ANDROID_SERIAL=PHONE_SERIAL ./gradlew :phone-app:installDebug
ANDROID_SERIAL=GLASSES_SERIAL ./gradlew :glasses-app:installDebug

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

See [CI quality checks and shared devices](doc/CI_AND_DEVICES.md) for Sonar verification
and explicit device selection when other projects share ADB devices.

Unit and integration test suites are implemented for protocol, service, factory, and data-layer paths.

### Run Tests

```bash
# Cross-module unit tests
./gradlew :common:testDebugUnitTest :phone-app:testDebugUnitTest :glasses-app:testDebugUnitTest

# Targeted suites
./gradlew :common:testDebugUnitTest --tests "com.example.rokidcommon.protocol.*"
./gradlew :phone-app:testDebugUnitTest --tests "com.example.rokidphone.service.ai.*"
./gradlew :phone-app:testDebugUnitTest --tests "com.example.rokidphone.service.stt.*"

# Phone instrumented tests (Room/data-layer)
./gradlew :phone-app:connectedDebugAndroidTest
```

### Manual Testing Checklist

1. **Phone App**
   - [ ] Launch app, verify Settings screen loads (no API key required)
   - [ ] Configure any AI provider, test text chat and streaming
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

### Running Instrumentation Tests

```bash
./gradlew :phone-app:connectedAndroidTest
./gradlew :glasses-app:connectedAndroidTest
```

---

## Common Developer Tasks

### Add a New AI Provider

1. Add a `ProviderDescriptor` entry in `ai/catalog/ProviderRegistry.kt`
   (protocol, catalog format, models endpoint, auth style)
2. If the wire protocol is new, add a request adapter implementing
   `AiServiceProvider` (see [ARCHITECTURE.md](doc/ARCHITECTURE.md#ai-service-provider-interface))
3. Add the provider to the `AiProvider` enum and the verified fallback list in
   `ai/catalog/FallbackModelCatalog.kt` (with doc source + verification date)
4. Wire credentials in `ApiSettings` / `SettingsRepository` and the Settings UI

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
A: Project requires JDK 21 (matches AGP 9 and CI).
   File → Settings → Build → Gradle → Gradle JDK → Select JDK 21.
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

Licensed under the [Apache License, Version 2.0](LICENSE). See [NOTICE](NOTICE) for attribution.

The Rokid CXR SDK is a separate, proprietary dependency downloaded from Rokid's Maven repository at build time. It is not part of this project and is not covered by this license; its use is subject to Rokid's own terms.

Contributions are welcome under the same license with a DCO sign-off. See [CONTRIBUTING.md](CONTRIBUTING.md).
