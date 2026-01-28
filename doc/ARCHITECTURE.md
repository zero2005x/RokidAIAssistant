# Architecture Overview

> 📖 [繁體中文版](zh-TW/ARCHITECTURE.md)

**System design documentation for Rokid AI Assistant.**

---

## 🎯 Quick Reference

| Question                        | Answer                                  | See Section                                             |
| ------------------------------- | --------------------------------------- | ------------------------------------------------------- |
| How does phone talk to glasses? | CXR SDK + Bluetooth                     | [Communication Flow](#communication-flow)               |
| How to add a new AI provider?   | Implement `AiServiceProvider` interface | [AI Provider Interface](#ai-service-provider-interface) |
| Where is conversation stored?   | Room database in phone-app              | [Data Flow](#data-flow)                                 |
| How are messages formatted?     | Binary protocol in `common/protocol/`   | [Message Protocol](#message-protocol)                   |

---

## Scope

### In Scope

- High-level system architecture and module relationships
- Communication protocols between phone and glasses
- Key component interfaces and responsibilities
- Data flow patterns
- Technology decision rationale

### Out of Scope

- Line-by-line code explanations
- UI/UX design specifications
- Deployment and CI/CD pipelines
- Performance benchmarks

---

## Table of Contents

- [System Architecture](#system-architecture)
- [Module Structure](#module-structure)
- [Communication Flow](#communication-flow)
- [Component Details](#component-details)
- [Data Flow](#data-flow)
- [Technology Decisions](#technology-decisions)
- [Common Development Patterns](#common-development-patterns)

---

## System Architecture

### High-Level Overview

```
┌─────────────────────────────────────────────────────────────────────┐
│                         Rokid AI Assistant                          │
├─────────────────────────────────────────────────────────────────────┤
│                                                                     │
│   ┌─────────────┐         Bluetooth/CXR         ┌─────────────┐    │
│   │             │◄──────────────────────────────►│             │    │
│   │  Phone App  │                                │ Glasses App │    │
│   │             │                                │             │    │
│   └──────┬──────┘                                └──────┬──────┘    │
│          │                                              │           │
│          ▼                                              ▼           │
│   ┌─────────────┐                                ┌─────────────┐    │
│   │   Common    │◄───────── Shared ─────────────►│   Common    │    │
│   │   Module    │          Protocol              │   Module    │    │
│   └─────────────┘                                └─────────────┘    │
│                                                                     │
└─────────────────────────────────────────────────────────────────────┘
                                    │
                                    ▼
                    ┌───────────────────────────────┐
                    │       External AI APIs        │
                    │  (Gemini, OpenAI, Claude...)  │
                    └───────────────────────────────┘
```

### Design Principles

| Principle                  | Description                             | Implementation                         |
| -------------------------- | --------------------------------------- | -------------------------------------- |
| **Separation of Concerns** | Each module has a single responsibility | phone-app = AI, glasses-app = UI/Input |
| **Offloading**             | Heavy processing on phone               | AI inference, STT run on phone         |
| **Shared Protocol**        | Common message format                   | `common/protocol/Message.kt`           |
| **Provider Abstraction**   | Pluggable providers                     | `AiServiceProvider` interface          |

---

## Module Structure

### Project Layout

```
RokidAIAssistant/
├── app/                          # Original integrated app (dev only)
│   └── src/main/java/.../rokidaiassistant/
│
├── phone-app/                    # 📱 Phone application
│   └── src/main/java/.../rokidphone/
│       ├── MainActivity.kt       # Entry point, permission handling
│       ├── PhoneApplication.kt   # Application class
│       ├── ai/                   # AI provider interfaces
│       │   ├── AiServiceProvider.kt      # ⭐ Core interface
│       │   └── AiServiceFactory.kt       # Provider factory
│       ├── data/
│       │   ├── ApiSettings.kt    # API configuration
│       │   ├── db/               # Room database
│       │   │   ├── AppDatabase.kt
│       │   │   ├── ConversationDao.kt
│       │   │   └── MessageEntity.kt
│       │   └── SettingsRepository.kt
│       ├── service/
│       │   ├── ai/               # AI implementations
│       │   │   ├── GeminiService.kt
│       │   │   ├── OpenAiCompatibleService.kt
│       │   │   └── AnthropicService.kt
│       │   ├── cxr/              # Rokid SDK
│       │   │   └── CxrMobileManager.kt
│       │   ├── photo/            # Photo handling
│       │   └── stt/              # Speech-to-text
│       │       └── SttProvider.kt
│       ├── ui/                   # Jetpack Compose UI
│       │   ├── conversation/     # Chat screen
│       │   ├── settings/         # Settings screen
│       │   └── navigation/       # NavHost
│       └── viewmodel/            # ViewModels
│
├── glasses-app/                  # 👓 Glasses application
│   └── src/main/java/.../rokidglasses/
│       ├── MainActivity.kt       # Entry point
│       ├── GlassesApplication.kt # Application class
│       ├── sdk/                  # CXR SDK wrapper
│       ├── service/
│       │   └── photo/            # Camera service
│       ├── ui/                   # Compose UI
│       └── viewmodel/
│           └── GlassesViewModel.kt
│
├── common/                       # 📦 Shared library
│   └── src/main/java/.../rokidcommon/
│       ├── Constants.kt          # Shared constants
│       └── protocol/
│           ├── Message.kt        # ⭐ Core message class
│           ├── MessageType.kt    # Message type enum
│           └── ConnectionState.kt
│
└── gradle/libs.versions.toml     # Version catalog
```

### Module Dependencies

```
phone-app ──────► common
glasses-app ────► common
app ────────────► common (dev only)
```

---

## Communication Flow

### Voice Interaction Flow

```
┌─────────────┐                    ┌─────────────┐                    ┌─────────────┐
│   Glasses   │                    │    Phone    │                    │   AI API    │
└──────┬──────┘                    └──────┬──────┘                    └──────┬──────┘
       │                                  │                                  │
       │  1. User triggers voice input    │                                  │
       │  (button press / wake word)      │                                  │
       ├─────────────────────────────────►│                                  │
       │                                  │                                  │
       │  2. VOICE_START message          │                                  │
       ├─────────────────────────────────►│                                  │
       │                                  │                                  │
       │  3. VOICE_DATA (audio chunks)    │                                  │
       ├─────────────────────────────────►│                                  │
       │                                  │                                  │
       │  4. VOICE_END message            │                                  │
       ├─────────────────────────────────►│                                  │
       │                                  │                                  │
       │                                  │  5. Speech-to-Text               │
       │                                  ├─────────────────────────────────►│
       │                                  │                                  │
       │                                  │  6. Transcription result         │
       │                                  │◄─────────────────────────────────┤
       │                                  │                                  │
       │  7. AI_PROCESSING message        │                                  │
       │◄─────────────────────────────────┤                                  │
       │                                  │                                  │
       │                                  │  8. AI Chat request              │
       │                                  ├─────────────────────────────────►│
       │                                  │                                  │
       │                                  │  9. AI response                  │
       │                                  │◄─────────────────────────────────┤
       │                                  │                                  │
       │  10. AI_RESPONSE_TEXT message    │                                  │
       │◄─────────────────────────────────┤                                  │
       │                                  │                                  │
       │  11. Display response on glasses │                                  │
       ▼                                  ▼                                  ▼
```

### Photo Capture Flow

```
┌─────────────┐                    ┌─────────────┐                    ┌─────────────┐
│   Glasses   │                    │    Phone    │                    │   AI API    │
└──────┬──────┘                    └──────┬──────┘                    └──────┬──────┘
       │                                  │                                  │
       │  1. CAPTURE_PHOTO command        │                                  │
       │◄─────────────────────────────────┤                                  │
       │                                  │                                  │
       │  2. Camera captures image        │                                  │
       │                                  │                                  │
       │  3. PHOTO_START message          │                                  │
       ├─────────────────────────────────►│                                  │
       │                                  │                                  │
       │  4. PHOTO_DATA (chunks)          │                                  │
       ├─────────────────────────────────►│                                  │
       │                                  │                                  │
       │  5. PHOTO_END message            │                                  │
       ├─────────────────────────────────►│                                  │
       │                                  │                                  │
       │                                  │  6. Image analysis request       │
       │                                  ├─────────────────────────────────►│
       │                                  │                                  │
       │                                  │  7. Analysis result              │
       │                                  │◄─────────────────────────────────┤
       │                                  │                                  │
       │  8. PHOTO_ANALYSIS_RESULT        │                                  │
       │◄─────────────────────────────────┤                                  │
       │                                  │                                  │
       ▼                                  ▼                                  ▼
```

---

## Component Details

### Phone App Components

#### PhoneAIService

The central background service that orchestrates all AI operations:

```kotlin
class PhoneAIService : Service() {
    // AI service (pluggable providers)
    private var aiService: AiServiceProvider?

    // Speech recognition service
    private var speechService: AiServiceProvider?

    // CXR SDK manager
    private var cxrManager: CxrMobileManager?

    // Photo repository
    private var photoRepository: PhotoRepository?

    // Conversation persistence
    private var conversationRepository: ConversationRepository?
}
```

#### AI Service Provider Interface

All AI providers implement this unified interface:

```kotlin
interface AiServiceProvider {
    val provider: AiProvider

    suspend fun transcribe(pcmAudioData: ByteArray): SpeechResult
    suspend fun chat(userMessage: String): String
    suspend fun analyzeImage(imageData: ByteArray, prompt: String): String
    fun clearHistory()
}
```

#### Supported AI Providers

| Provider  | Implementation            | Features               |
| --------- | ------------------------- | ---------------------- |
| Gemini    | `GeminiService`           | Native SDK, multimodal |
| OpenAI    | `OpenAiCompatibleService` | OpenAI-compatible API  |
| Anthropic | `AnthropicService`        | Custom API format      |
| DeepSeek  | `OpenAiCompatibleService` | OpenAI-compatible      |
| Groq      | `OpenAiCompatibleService` | OpenAI-compatible      |
| xAI       | `OpenAiCompatibleService` | OpenAI-compatible      |
| Alibaba   | `OpenAiCompatibleService` | OpenAI-compatible      |
| Zhipu     | `OpenAiCompatibleService` | OpenAI-compatible      |
| Baidu     | `BaiduService`            | OAuth authentication   |
| Custom    | `OpenAiCompatibleService` | User-defined endpoint  |

#### STT Service Architecture

```kotlin
interface SttService {
    suspend fun transcribe(audioData: ByteArray): SpeechResult
    suspend fun validateCredentials(): Boolean
    fun supportsStreaming(): Boolean
}
```

**Supported Providers (18 total):**

**Tier 1 - Premium (5):**

- Google Cloud STT
- AWS Transcribe
- Alibaba ASR (Aliyun)
- Tencent ASR
- Baidu ASR

**Tier 2 - Enterprise (9):**

- Gemini (native)
- OpenAI Whisper
- Groq Whisper
- Deepgram
- AssemblyAI
- Azure Speech
- iFLYTEK
- IBM Watson STT
- Huawei SIS

**Tier 3 - Specialized (4):**

- Volcengine ASR
- Rev.ai
- Speechmatics
- Otter.ai

### Glasses App Components

#### GlassesViewModel

Main ViewModel handling UI state and user interactions:

```kotlin
class GlassesViewModel : ViewModel() {
    // UI state
    val uiState: StateFlow<GlassesUiState>

    // Voice recording state
    val isRecording: StateFlow<Boolean>

    // Connection state
    val connectionState: StateFlow<ConnectionState>
}
```

#### WakeWordService

Background service for voice wake word detection:

```kotlin
class WakeWordService : Service() {
    // Listens for wake word ("Hey Rokid")
    // Triggers voice input when detected
}
```

#### CameraService

Handles photo capture on glasses:

```kotlin
class CameraService : Service() {
    // Captures photos from glasses camera
    // Transfers photos to phone via protocol
}
```

### Common Module Components

#### Message Protocol

Binary message format for efficient transmission:

```kotlin
data class Message(
    val id: String,
    val type: MessageType,
    val timestamp: Long,
    val payload: String?,
    val binaryData: ByteArray?
)
```

#### Message Types

```kotlin
enum class MessageType(val code: Int) {
    // Connection (0x00-0x0F)
    HANDSHAKE(0x00),
    HEARTBEAT(0x02),
    DISCONNECT(0x0F),

    // Voice (0x10-0x1F)
    VOICE_START(0x10),
    VOICE_DATA(0x11),
    VOICE_END(0x12),

    // AI (0x20-0x2F)
    AI_PROCESSING(0x20),
    AI_RESPONSE_TEXT(0x21),
    AI_ERROR(0x2F),

    // Display (0x30-0x3F)
    DISPLAY_TEXT(0x30),
    DISPLAY_CLEAR(0x31),

    // Photo (0x40-0x4F)
    PHOTO_START(0x40),
    PHOTO_DATA(0x41),
    PHOTO_END(0x42),
    CAPTURE_PHOTO(0x47),

    // System (0xF0-0xFF)
    SYSTEM_STATUS(0xF0),
    SYSTEM_ERROR(0xFF)
}
```

---

## Data Flow

### Settings Storage

```
┌─────────────────────────────────────────────────────────────────┐
│                        Phone App                                 │
├─────────────────────────────────────────────────────────────────┤
│                                                                  │
│   ┌─────────────────┐     ┌─────────────────┐                   │
│   │ SettingsScreen  │────►│SettingsRepository│                  │
│   └─────────────────┘     └────────┬────────┘                   │
│                                    │                             │
│                         ┌──────────┴──────────┐                 │
│                         ▼                     ▼                 │
│               ┌─────────────────┐   ┌─────────────────┐         │
│               │  DataStore     │   │ EncryptedShared │         │
│               │  Preferences   │   │   Preferences   │         │
│               └─────────────────┘   └─────────────────┘         │
│                                                                  │
└─────────────────────────────────────────────────────────────────┘
```

### Conversation Persistence

```
┌─────────────────────────────────────────────────────────────────┐
│                     Room Database                                │
├─────────────────────────────────────────────────────────────────┤
│                                                                  │
│   ┌───────────────┐    ┌───────────────┐    ┌───────────────┐   │
│   │ Conversation  │    │   Message     │    │   Photo       │   │
│   │    Entity     │    │    Entity     │    │   Entity      │   │
│   └───────┬───────┘    └───────┬───────┘    └───────┬───────┘   │
│           │                    │                    │           │
│           └────────────────────┼────────────────────┘           │
│                                ▼                                 │
│                    ┌───────────────────┐                        │
│                    │ ConversationRepo  │                        │
│                    └───────────────────┘                        │
│                                                                  │
└─────────────────────────────────────────────────────────────────┘
```

---

## Technology Decisions

| Decision                  | Rationale                                    | Trade-offs                   |
| ------------------------- | -------------------------------------------- | ---------------------------- |
| **Kotlin**                | Coroutines, null safety, Compose integration | Learning curve for Java devs |
| **Jetpack Compose**       | Declarative, better state management         | Newer ecosystem              |
| **Room Database**         | Type-safe, Flow support                      | SQLite overhead              |
| **Multiple AI Providers** | Flexibility, fallback, cost optimization     | More code to maintain        |
| **Modular Architecture**  | Parallel dev, different targets              | Build complexity             |

---

## Common Development Patterns

### Adding a New AI Provider

**1. Create the service class:**

```kotlin
// phone-app/src/.../service/ai/YourProviderService.kt
class YourProviderService(private val apiKey: String) : AiServiceProvider {
    override val provider = AiProvider.YOUR_PROVIDER

    override suspend fun sendMessage(message: String, imageData: ByteArray?): Flow<String> = flow {
        // Implement API call
        emit("Response from your provider")
    }

    override fun clearHistory() { /* Clear conversation */ }
}
```

**2. Register in factory:**

```kotlin
// phone-app/src/.../ai/AiServiceFactory.kt
fun create(provider: AiProvider, settings: ApiSettings): AiServiceProvider {
    return when (provider) {
        AiProvider.YOUR_PROVIDER -> YourProviderService(settings.apiKey)
        // ... other providers
    }
}
```

**3. Add to enum:**

```kotlin
// phone-app/src/.../data/ApiSettings.kt
enum class AiProvider {
    GEMINI, OPENAI, ANTHROPIC, YOUR_PROVIDER
}
```

### Adding a New Message Type

**1. Add to MessageType enum:**

```kotlin
// common/src/.../protocol/MessageType.kt
enum class MessageType(val code: Int) {
    // ... existing types
    YOUR_NEW_TYPE(0x50)
}
```

**2. Handle in phone-app:**

```kotlin
// phone-app/src/.../service/cxr/CxrMobileManager.kt
when (message.type) {
    MessageType.YOUR_NEW_TYPE -> handleYourNewType(message)
    // ...
}
```

**3. Handle in glasses-app (if needed):**

```kotlin
// glasses-app/src/.../sdk/CxrGlassesManager.kt
when (message.type) {
    MessageType.YOUR_NEW_TYPE -> handleYourNewType(message)
    // ...
}
```

### Adding a New Compose Screen

**1. Create the screen:**

```kotlin
// phone-app/src/.../ui/yourscreen/YourScreen.kt
@Composable
fun YourScreen(
    viewModel: YourViewModel = viewModel(),
    onNavigateBack: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    // UI implementation
}
```

**2. Create ViewModel:**

```kotlin
// phone-app/src/.../viewmodel/YourViewModel.kt
class YourViewModel : ViewModel() {
    private val _uiState = MutableStateFlow(YourUiState())
    val uiState: StateFlow<YourUiState> = _uiState.asStateFlow()
}
```

**3. Add navigation:**

```kotlin
// phone-app/src/.../ui/navigation/AppNavigation.kt
composable("your_screen") {
    YourScreen(onNavigateBack = { navController.popBackStack() })
}
```
