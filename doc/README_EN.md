# Rokid AI Assistant

[繁體中文](README_TW.md) | [简体中文](README_CN.md) | **English**

An AI voice assistant application running on Android smartphones, designed to work with Rokid smart glasses.

## Features

- 🔗 **Bluetooth Connection**: Phone acts as SPP server, glasses connect as client
- 🎤 **Voice Interaction**: Record voice through glasses microphone
- 🤖 **Multi-Provider AI**: Support for 11 AI providers (Gemini, OpenAI, Claude, Perplexity, etc.)
- 📺 **AR Display**: Show conversation content on glasses screen
- 🌍 **Multi-Language**: Support for 13 UI languages
- 🔐 **Secure Storage**: API keys stored with EncryptedSharedPreferences
- 💬 **Conversation History**: Persistent conversation storage with Room database
- 📌 **Conversation Management**: Pin, archive, and search conversations
- 🎛️ **Provider Manager**: Type-safe multi-provider architecture with service caching

## Architecture

```
┌─────────────────┐      Bluetooth SPP      ┌─────────────────┐      WiFi      ┌─────────────────┐
│  Rokid Glasses  │ ◄──────────────────────► │    Phone App    │ ◄────────────► │    AI APIs      │
│  (glasses-app)  │    Voice/Commands/Response│  (phone-app)   │   HTTP/REST   │  (Cloud)        │
└─────────────────┘                          └─────────────────┘                └─────────────────┘
        │                                            │
        │                                            │
   ┌────┴────┐                              ┌───────┴───────┐
   │ Record  │                              │ AI Processing │
   │ Display │                              │ Settings Mgmt │
   └─────────┘                              └───────────────┘
```

## Project Structure

```
RokidAIAssistant/
├── phone-app/                    # Phone application
│   ├── ai/
│   │   └── provider/
│   │       ├── Provider.kt       # Unified provider interface
│   │       ├── ProviderManager.kt# Provider manager
│   │       └── ProviderSetting.kt# Provider settings
│   ├── data/
│   │   ├── db/
│   │   │   ├── AppDatabase.kt    # Room database
│   │   │   └── ConversationRepository.kt
│   │   ├── ApiSettings.kt        # AI provider settings
│   │   ├── AppLanguage.kt        # Language definitions
│   │   └── SettingsRepository.kt # Settings storage
│   ├── service/
│   │   ├── PhoneAIService.kt     # Main foreground service
│   │   ├── EnhancedAIService.kt  # Enhanced AI integration
│   │   ├── BluetoothSppManager.kt# Bluetooth SPP server
│   │   └── ai/                   # AI service implementations
│   │       ├── GeminiService.kt
│   │       ├── OpenAiService.kt
│   │       ├── AnthropicService.kt
│   │       └── ...
│   ├── ui/
│   │   ├── conversation/
│   │   │   ├── ChatScreen.kt     # Chat interface
│   │   │   └── ConversationHistoryScreen.kt
│   │   └── SettingsScreen.kt     # Settings UI
│   └── viewmodel/
│       ├── ConversationViewModel.kt
│       └── PhoneViewModel.kt
│
├── glasses-app/                  # Glasses application
│   ├── service/
│   │   ├── BluetoothSppClient.kt # Bluetooth SPP client
│   │   └── WakeWordService.kt    # Wake word detection
│   └── viewmodel/
│       └── GlassesViewModel.kt   # UI state management
│
└── common/                       # Shared module
    ├── Message.kt                # Bluetooth message format
    ├── MessageType.kt            # Message type definitions
    └── Constants.kt              # Shared constants
```

## Supported AI Providers

| Provider              | Chat | Speech-to-Text | Vision |
| --------------------- | ---- | -------------- | ------ |
| Google Gemini         | ✅   | ✅             | ✅     |
| OpenAI                | ✅   | ✅ (Whisper)   | ✅     |
| Anthropic Claude      | ✅   | ❌             | ✅     |
| DeepSeek              | ✅   | ❌             | ✅     |
| Groq                  | ✅   | ✅ (Whisper)   | ✅     |
| xAI (Grok)            | ✅   | ❌             | ✅     |
| Alibaba Qwen          | ✅   | ❌             | ✅     |
| Zhipu AI (ChatGLM)    | ✅   | ❌             | ✅     |
| Baidu Ernie           | ✅   | ❌             | ✅     |
| Perplexity            | ✅   | ❌             | ❌     |
| Custom (Ollama, etc.) | ✅   | ❌             | ❌     |

## Supported Languages

The app UI supports the following 13 languages:

| Language            | Code  | Native Name |
| ------------------- | ----- | ----------- |
| English             | en    | English     |
| Simplified Chinese  | zh-CN | 简体中文    |
| Traditional Chinese | zh-TW | 繁體中文    |
| Japanese            | ja    | 日本語      |
| Korean              | ko    | 한국어      |
| Vietnamese          | vi    | Tiếng Việt  |
| Thai                | th    | ไทย         |
| French              | fr    | Français    |
| Spanish             | es    | Español     |
| Russian             | ru    | Русский     |
| Ukrainian           | uk    | Українська  |
| Arabic              | ar    | العربية     |
| Italian             | it    | Italiano    |

## Quick Start

### Prerequisites

- Android Studio Hedgehog (2023.1.1) or later
- Android SDK 34
- Kotlin 2.x
- Rokid glasses device (for glasses-app)
- At least one AI provider API key

### Setup

1. **Clone the repository**

   ```bash
   git clone <repository-url>
   cd RokidAIAssistant
   ```

2. **Configure API keys**

   Copy `local.properties.template` to `local.properties` and fill in your keys:

   ```properties
   sdk.dir=<your Android SDK path>
   GEMINI_API_KEY=<your Gemini API key>
   OPENAI_API_KEY=<your OpenAI API key>
   ```

3. **Build and run**

   ```bash
   # Build phone app
   ./gradlew :phone-app:assembleDebug

   # Build glasses app
   ./gradlew :glasses-app:assembleDebug
   ```

### Usage

1. Install `phone-app` on your Android phone
2. Install `glasses-app` on Rokid glasses
3. Open phone app and click "Start Service"
4. Pair glasses with phone via Bluetooth
5. On glasses, tap the touchpad or say the wake word to start recording
6. Speak your question and release to get AI response

## Configuration

All settings can be configured in the phone app's Settings screen:

- **AI Provider**: Select from 10+ providers
- **AI Model**: Choose the model for selected provider
- **API Keys**: Securely stored for each provider
- **Speech Recognition**: Choose STT service
- **System Prompt**: Customize AI behavior
- **App Language**: UI language selection

## Bluetooth Protocol

### SPP UUID

```
a1b2c3d4-e5f6-7890-abcd-ef1234567890
```

### Message Format

JSON with newline delimiter, binary data encoded as Base64.

### Message Types

| Type             | Direction     | Description                     |
| ---------------- | ------------- | ------------------------------- |
| VOICE_START      | Glasses→Phone | Recording started               |
| VOICE_END        | Glasses→Phone | Recording ended, includes audio |
| AI_PROCESSING    | Phone→Glasses | Processing status               |
| USER_TRANSCRIPT  | Phone→Glasses | Speech-to-text result           |
| AI_RESPONSE_TEXT | Phone→Glasses | AI text response                |
| AI_ERROR         | Phone→Glasses | Error message                   |

## Audio Format

- **Sample Rate**: 16000 Hz
- **Channels**: Mono
- **Bit Depth**: 16-bit
- **Format**: PCM → WAV (converted before API call)

## Development

### Build Requirements

| Component             | Version                   |
| --------------------- | ------------------------- |
| Android Gradle Plugin | 9.0.0                     |
| Kotlin                | 2.2.10                    |
| Gradle                | 9.1.0                     |
| Min SDK               | 26 (glasses) / 28 (phone) |
| Target SDK            | 34                        |

### Key Dependencies

| Dependency           | Version    |
| -------------------- | ---------- |
| Compose BOM          | 2024.02.00 |
| Room Database        | 2.7.1      |
| KSP                  | 2.3.4      |
| Kotlin Serialization | 1.6.3      |
| Navigation Compose   | 2.7.7      |
| Generative AI SDK    | 0.2.2      |
| Retrofit             | 2.9.0      |
| OkHttp               | 4.12.0     |

## Security Notes

⚠️ **Important**:

- `local.properties` contains sensitive API keys - **never commit to Git**
- API keys are stored encrypted using EncryptedSharedPreferences
- All keys in `.gitignore` are excluded from version control

## License

Private project for internal use only.

## Documentation

- [Architecture Guide](ARCHITECTURE.md)
- [API Settings Guide](API_SETTINGS_EN.md)
- [Troubleshooting Guide](TROUBLESHOOTING_EN.md)
