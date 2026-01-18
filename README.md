# Rokid AI Assistant

An AI voice assistant app running on Android phones that works with Rokid smart glasses.

## Features

- 🔗 **Bluetooth Connection**: Automatically scan and connect to Rokid glasses
- 🎤 **Voice Interaction**: Receive voice input through glasses microphone
- 🤖 **AI Conversation**: Integrated with Google Gemini API for intelligent dialogue
- 📺 **AR Display**: Display conversation content on glasses screen
- 📷 **Photo Capture**: Capture photos through glasses camera for AI analysis

## Technical Architecture

```
┌─────────────────────────────────────────┐
│           Phone App (phone-app)          │
├─────────────────────────────────────────┤
│  Rokid CXR-M SDK                        │
│  ├── Bluetooth Connection Management    │
│  ├── AI Event Listening                 │
│  └── Audio Stream Reception             │
├─────────────────────────────────────────┤
│  AI Service Layer                        │
│  ├── Speech-to-Text (Whisper API) ✅    │
│  ├── Gemini API (Integrated) ✅         │
│  └── Text-to-Speech (Edge TTS) ✅       │
└─────────────────────────────────────────┘
                   │
            Bluetooth SPP
                   │
┌─────────────────────────────────────────┐
│          Glasses App (glasses-app)       │
├─────────────────────────────────────────┤
│  Rokid CXR-S SDK                        │
│  ├── Touchpad / Voice Wake-up           │
│  ├── Microphone Recording               │
│  ├── Camera Capture (Camera2 API)       │
│  └── AR Subtitle Display                │
└─────────────────────────────────────────┘
```

## Project Structure

```
RokidAIAssistant/
├── glasses-app/                    # Glasses-side application
│   └── src/main/java/.../
│       ├── MainActivity.kt         # Main entry, key handling
│       ├── viewmodel/
│       │   └── GlassesViewModel.kt # UI state management
│       └── service/
│           ├── photo/
│           │   ├── GlassesCameraManager.kt  # Camera2 API wrapper
│           │   └── UnifiedCameraManager.kt  # Unified camera interface
│           ├── BluetoothSppClient.kt        # Bluetooth SPP client
│           ├── CxrServiceManager.kt         # CXR-S SDK manager
│           └── WakeWordService.kt           # Voice wake-up detection
│
├── phone-app/                      # Phone-side application
│   └── src/main/java/.../
│       ├── MainActivity.kt         # Main entry
│       ├── viewmodel/
│       │   ├── PhoneViewModel.kt   # Main UI state
│       │   └── ImageAnalysisViewModel.kt  # Image AI analysis
│       └── service/
│           ├── BluetoothSppManager.kt      # Bluetooth SPP server
│           ├── GeminiSpeechService.kt      # Gemini Live API
│           ├── PhoneAIService.kt           # AI orchestration
│           ├── ServiceBridge.kt            # Service communication
│           └── cxr/
│               └── CxrMobileManager.kt     # CXR-M SDK manager
│
├── common/                         # Shared module
│   └── src/main/java/.../
│       ├── Constants.kt            # Shared constants
│       └── protocol/
│           └── MessageType.kt      # Bluetooth message protocol
│
└── app/                            # Legacy app module
```

## Quick Start

### Prerequisites

- Android Studio Hedgehog (2023.1.1) or newer
- Android SDK 34
- Kotlin 1.9.22
- Rokid glasses device + SN authentication file
- Gemini API Key

### Setup Steps

1. **Clone the project**

   ```bash
   cd RokidAIAssistant
   ```

2. **Configure sensitive information**

   Edit `local.properties`:

   ```properties
   sdk.dir=<your Android SDK path>
   ROKID_CLIENT_SECRET=<your Client Secret, remove hyphens>
   GEMINI_API_KEY=<your Gemini API Key>
   OPENAI_API_KEY=<your OpenAI API Key for Whisper STT>
   ```

3. **Place SN authentication file**

   Copy the `.lc` authentication file to:

   ```
   app/src/main/res/raw/sn_auth_file.lc
   ```

4. **Build and run**
   ```bash
   ./gradlew assembleDebug
   # Or click Run in Android Studio
   ```

### Usage

1. Install `glasses-app` on Rokid glasses
2. Install `phone-app` on Android phone
3. Open both apps and connect via Bluetooth
4. **On glasses**: Press Enter key or say wake word
5. **On phone**: Tap "Capture Photo" button to take photos
6. Start conversing with AI!

## Feature Status

### ✅ Completed

- [x] Speech-to-Text integration (OpenAI Whisper API)
- [x] Text-to-Speech integration (Edge TTS + System TTS fallback)
- [x] Gemini AI conversation
- [x] Bluetooth connection with CXR SDK integration
- [x] Photo capture via Camera2 API (YUV format)
- [x] Photo transfer to phone via Bluetooth
- [x] Image analysis with Gemini Vision

### ⏳ To Do

- [ ] Settings page (API Key management, voice settings, etc.)
- [ ] Conversation history persistence
- [ ] Offline mode support
- [ ] Error handling optimization

## Dependencies

| Dependency        | Version    |
| ----------------- | ---------- |
| Rokid CXR SDK     | 1.0.4      |
| Kotlin            | 1.9.22     |
| Compose BOM       | 2024.02.00 |
| Generative AI SDK | 0.2.2      |
| Retrofit          | 2.9.0      |
| OkHttp            | 4.12.0     |

## Notes

⚠️ **Security Reminder**:

- `local.properties` contains sensitive information, **do NOT commit to Git**
- Already added to `.gitignore` exclusion

## License

Private project, for internal use only.
