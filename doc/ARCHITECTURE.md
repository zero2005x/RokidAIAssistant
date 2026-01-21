# Rokid AI Assistant Architecture

## Overview

Rokid AI Assistant is a smart glasses assistant application using a "phone relay" architecture. The glasses connect to the phone via Bluetooth, and the phone handles all AI processing.

## System Architecture

```
┌─────────────────┐      Bluetooth SPP      ┌─────────────────┐      WiFi      ┌─────────────────┐
│  Rokid Glasses  │ ◄──────────────────────► │    Phone App    │ ◄────────────► │    AI APIs      │
│  (glasses-app)  │    Voice/Commands/Resp   │   (phone-app)   │   HTTP/REST   │  (Cloud/Local)  │
└─────────────────┘                          └─────────────────┘                └─────────────────┘
        │                                            │
        │                                            │
   ┌────┴────┐                              ┌───────┴────────┐
   │ Record  │                              │ProviderManager │
   │ Display │                              │  Room Database │
   └─────────┘                              │ EnhancedAIServ │
                                            └────────────────┘
```

---

## Multi-LLM Provider Architecture

The application uses a type-safe multi-provider architecture inspired by RikkaHub.

### Core Components

1. **ProviderSetting.kt** - Provider Configuration Sealed Class
   - Path: `phone-app/src/main/java/com/example/rokidphone/ai/provider/ProviderSetting.kt`
   - Uses sealed class for type-safe multi-provider support
   - Each provider has its own configuration type (Gemini, OpenAI, Anthropic, DeepSeek, Groq, xAI, Alibaba, Zhipu, Baidu, Perplexity, Custom)
   - Supports Kotlin Serialization

2. **Provider.kt** - Unified Provider Interface
   - Path: `phone-app/src/main/java/com/example/rokidphone/ai/provider/Provider.kt`
   - Defines generic `Provider<T : ProviderSetting>` interface
   - Includes `listModels`, `generateText`, `streamText`, `transcribe`, `analyzeImage` methods
   - Defines `ChatMessage`, `GenerationResult`, `MessageChunk` data classes

3. **ProviderManager.kt** - Provider Manager
   - Path: `phone-app/src/main/java/com/example/rokidphone/ai/provider/ProviderManager.kt`
   - Centralized management of all AI Provider instances and settings
   - Service caching mechanism to avoid redundant service creation
   - Dynamic provider and model switching support

### Architecture Pattern

```kotlin
// Sealed class for different provider configurations
sealed class ProviderSetting {
    data class Gemini(val apiKey: String, ...) : ProviderSetting()
    data class OpenAI(val apiKey: String, ...) : ProviderSetting()
    data class Custom(val baseUrl: String, ...) : ProviderSetting()
    // ...
}

// Unified Provider interface
interface Provider<T : ProviderSetting> {
    suspend fun generateText(setting: T, messages: List<ChatMessage>): GenerationResult
    fun streamText(setting: T, messages: List<ChatMessage>): Flow<MessageChunk>
    suspend fun transcribe(setting: T, audioData: ByteArray): SpeechResult
    // ...
}
```

---

## Conversation Persistence (Room Database)

### Database Components

1. **AppDatabase.kt** - Room Database
   - Path: `phone-app/src/main/java/com/example/rokidphone/data/db/AppDatabase.kt`
   - Uses Room Database for data persistence
   - Contains `ConversationEntity` and `MessageEntity` entities
   - Defines `ConversationDao` and `MessageDao`

2. **ConversationRepository.kt** - Conversation Repository
   - Path: `phone-app/src/main/java/com/example/rokidphone/data/db/ConversationRepository.kt`
   - Provides CRUD operations for conversations and messages
   - Supports conversation archiving and pinning
   - Auto-generates conversation titles

### Data Schema

```kotlin
// Conversation Entity
@Entity(tableName = "conversations")
data class ConversationEntity(
    val id: String,
    val title: String,
    val providerId: String,
    val modelId: String,
    val systemPrompt: String,
    val createdAt: Long,
    val updatedAt: Long,
    val messageCount: Int,
    val isArchived: Boolean,
    val isPinned: Boolean
)

// Message Entity
@Entity(tableName = "messages")
data class MessageEntity(
    val id: String,
    val conversationId: String,
    val role: String,  // "user", "assistant", "system"
    val content: String,
    val createdAt: Long,
    val tokenCount: Int?,
    val modelId: String?,
    val hasImage: Boolean,
    val imagePath: String?
)
```

---

## Architecture Diagram

```
┌─────────────────┐      Bluetooth SPP      ┌─────────────────┐      WiFi      ┌─────────────────┐
│   Rokid 眼鏡    │ ◄──────────────────────► │     手機 App    │ ◄────────────► │   Gemini API    │
│  (glasses-app)  │     語音/指令/回應       │   (phone-app)   │   HTTP/REST   │  (Google Cloud) │
└─────────────────┘                          └─────────────────┘                └─────────────────┘
```

## Module Description

### 1. glasses-app (Glasses Side)

- **Main Functions**: Voice recording, wake word detection, display AI responses
- **BluetoothSppClient**: Bluetooth SPP client, connects to phone
- **GlassesViewModel**: Manages UI state and voice recording
- **WakeWordService**: Wake word detection service

### 2. phone-app (Phone Side)

- **Main Functions**: Bluetooth server, speech recognition, AI conversation, settings management
- **BluetoothSppManager**: Bluetooth SPP server, receives glasses connections
- **ProviderManager**: Manages all AI provider instances and settings
- **EnhancedAIService**: Integrates ProviderManager, ConversationRepository, and AI services
- **PhoneAIService**: Foreground service, manages Bluetooth and AI processing
- **SettingsRepository**: Manages API settings
- **ConversationRepository**: Manages conversation history persistence

### 3. common (Shared Module)

- **Message**: Bluetooth communication message format (JSON + Base64 encoding)
- **MessageType**: Message type definitions
- **ConnectionState**: Connection state enumeration

## Communication Protocol

### Bluetooth SPP Protocol

- **UUID**: `a1b2c3d4-e5f6-7890-abcd-ef1234567890`
- **Format**: JSON + newline delimiter
- **Audio Encoding**: Base64 (for binaryData field)

### Message Types

| Type             | Direction       | Description                     |
| ---------------- | --------------- | ------------------------------- |
| VOICE_START      | Glasses → Phone | Recording started               |
| VOICE_END        | Glasses → Phone | Recording ended, includes audio |
| AI_PROCESSING    | Phone → Glasses | Processing status               |
| USER_TRANSCRIPT  | Phone → Glasses | Speech-to-text result           |
| AI_RESPONSE_TEXT | Phone → Glasses | AI text response                |
| AI_ERROR         | Phone → Glasses | Processing error                |

## Audio Format

- **Sample Rate**: 16000 Hz
- **Channels**: Mono
- **Bit Depth**: 16-bit
- **Format**: PCM → WAV (converted before API call)

## Supported AI Services

### Speech-to-Text

1. **Google Gemini** - Uses multimodal model for audio processing
2. **OpenAI Whisper** - Supported via OpenAI and Groq
3. **Google Cloud STT** - (Planned)

### AI Chat

1. **Google Gemini** - gemini-2.5-pro, gemini-2.5-flash, gemini-2.5-flash-lite
2. **OpenAI GPT** - gpt-5.2, gpt-5, gpt-4o, gpt-4o-mini, o3-pro, o4-mini
3. **Anthropic Claude** - claude-opus-4.5, claude-sonnet-4.5, claude-haiku-4.5
4. **DeepSeek** - deepseek-chat, deepseek-reasoner, deepseek-vl-3
5. **Groq** - llama-4-70b, llama-3.3-70b, qwen-3-32b, llama-4-vision-90b
6. **xAI** - grok-4, grok-4-fast, grok-3, grok-2-vision-1212
7. **Alibaba Qwen** - qwen3-max, qwen3-plus, qwen3-turbo, qwen3-vl-max
8. **Zhipu AI** - glm-4.7, glm-4-plus, glm-4-flash, glm-4v-plus
9. **Baidu Ernie** - ernie-5.0, ernie-x1, ernie-4.5-turbo, ernie-vision-pro
10. **Perplexity** - sonar-pro, sonar, sonar-reasoning-pro, sonar-reasoning
11. **Custom** - Ollama, LM Studio 等 OpenAI 相容 API

## Configuration (phone-app)

The phone app provides a complete API configuration interface:

### Supported AI Providers

| Provider      | Status          | Models                                                  |
| ------------- | --------------- | ------------------------------------------------------- |
| Google Gemini | ✅ Full Support | gemini-2.5-pro, gemini-2.5-flash, gemini-2.5-flash-lite |
| OpenAI        | ✅ Full Support | gpt-5.2, gpt-5, gpt-4o, gpt-4o-mini, o3-pro, o4-mini    |
| Anthropic     | ✅ Full Support | claude-opus-4.5, claude-sonnet-4.5, claude-haiku-4.5    |
| DeepSeek      | ✅ Full Support | deepseek-chat, deepseek-reasoner, deepseek-vl-3         |
| Groq          | ✅ Full Support | llama-4-70b, llama-3.3-70b, qwen-3-32b                  |
| xAI           | ✅ Full Support | grok-4, grok-4-fast, grok-3, grok-2-vision-1212         |
| Alibaba       | ✅ Full Support | qwen3-max, qwen3-plus, qwen3-turbo, qwen3-vl-max        |
| Zhipu AI      | ✅ Full Support | glm-4.7, glm-4-plus, glm-4-flash, glm-4v-plus           |
| Baidu         | ✅ Full Support | ernie-5.0, ernie-x1, ernie-4.5-turbo                    |
| Perplexity    | ✅ Full Support | sonar-pro, sonar, sonar-reasoning-pro                   |
| Custom        | ✅ Full Support | Ollama, LM Studio, and other OpenAI-compatible APIs     |

### Configurable Options

- **AI Provider** - Select from 11 providers
- **AI Model** - Choose model for the selected provider
- **API Keys** - Encrypted storage for each provider
- **Speech Recognition** - Gemini Audio / Whisper / Google Cloud STT
- **System Prompt** - Customize AI behavior

### Settings Storage

- Uses `EncryptedSharedPreferences` for secure API key storage
- Settings changes take effect immediately without service restart

### Related Files

- `phone-app/src/main/java/com/example/rokidphone/data/ApiSettings.kt` - Settings data class
- `phone-app/src/main/java/com/example/rokidphone/data/SettingsRepository.kt` - Settings storage
- `phone-app/src/main/java/com/example/rokidphone/ui/SettingsScreen.kt` - Settings UI

## Project Structure

```
phone-app/src/main/java/com/example/rokidphone/
├── ai/
│   └── provider/
│       ├── Provider.kt              # Unified provider interface
│       ├── ProviderManager.kt       # Provider manager
│       └── ProviderSetting.kt       # Provider settings sealed class
├── data/
│   ├── db/
│   │   ├── AppDatabase.kt           # Room database
│   │   └── ConversationRepository.kt # Conversation repository
│   ├── ApiSettings.kt               # API settings
│   └── SettingsRepository.kt        # Settings storage
├── service/
│   ├── ai/                          # AI service implementations
│   │   ├── GeminiService.kt
│   │   ├── OpenAiService.kt
│   │   ├── AnthropicService.kt
│   │   └── ...
│   ├── EnhancedAIService.kt         # Enhanced AI service integration
│   ├── BluetoothSppManager.kt       # Bluetooth SPP server
│   └── PhoneAIService.kt            # Foreground service
├── ui/
│   ├── conversation/
│   │   ├── ChatScreen.kt            # Chat screen
│   │   └── ConversationHistoryScreen.kt # Conversation history
│   └── SettingsScreen.kt            # Settings UI
└── viewmodel/
    ├── ConversationViewModel.kt     # Conversation ViewModel
    └── PhoneViewModel.kt            # Phone ViewModel
```

---

## Development Environment

- **Android Gradle Plugin**: 9.0.0
- **Kotlin**: 2.2.10
- **Gradle**: 9.1.0
- **Min SDK**: 26 (glasses) / 28 (phone)
- **Target SDK**: 34

### Key Dependencies

| Dependency           | Version    |
| -------------------- | ---------- |
| Compose BOM          | 2024.02.00 |
| Room Database        | 2.7.1      |
| KSP                  | 2.3.4      |
| Kotlin Serialization | 1.6.3      |
| Navigation Compose   | 2.7.7      |
| OkHttp               | 4.12.0     |

---

## Future Roadmap

### MCP Tool Support (Advanced)

```kotlin
class McpManager {
    suspend fun callTool(toolName: String, args: JsonObject): JsonElement
    fun getAllAvailableTools(): List<McpTool>
}
```

### Offline Mode Support

- Local model integration (Ollama)
- Offline caching mechanism
- Offline speech recognition

---

## Version History

### v1.1.0 (2026-01-22)

- Multi-LLM provider architecture (ProviderManager)
- Room database for conversation persistence
- Conversation history UI with pin/archive support
- Enhanced AI service integration
- KSP 2.3.4 and Room 2.7.1 support

### v1.0.0 (2026-01-17)

- Basic architecture completed
- Bluetooth SPP communication
- Gemini API integration
- Glasses-side voice recording
- Phone-side AI processing
