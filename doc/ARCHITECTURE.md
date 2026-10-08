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
├── phone-app/                    # 📱 Phone application
│   └── src/main/java/.../glassesaicompanion/
│       ├── MainActivity.kt       # Entry point, permission handling
│       ├── PhoneApplication.kt   # Application class
│       ├── ai/provider/           # Provider abstraction layer (RikkaHub-style)
│       │   ├── Provider.kt               # Generic provider interface
│       │   ├── ProviderManager.kt        # Centralized provider management
│       │   └── ProviderSetting.kt        # Type-safe provider settings
│       ├── service/ai/           # AI provider interfaces & implementations
│       │   ├── AiServiceProvider.kt      # ⭐ Core interface
│       │   ├── AiServiceFactory.kt       # Provider factory
│       │   ├── BaseAiService.kt          # Base class with shared logic
│       │   ├── GeminiService.kt
│       │   ├── GeminiLiveService.kt      # Gemini Live mode service
│       │   ├── GeminiLiveSession.kt      # WebSocket live session manager
│       │   ├── LiveAudioManager.kt       # Live audio I/O management
│       │   ├── OpenAiCompatibleService.kt
│       │   ├── AnthropicService.kt
│       │   ├── BaiduService.kt           # Baidu (OAuth authentication)
│       │   ├── NetworkClientFactory.kt   # HTTP client factory
│       │   ├── SystemToolsHandler.kt     # System tool call handling
│       │   ├── ToolCallRouter.kt         # Tool call dispatch
│       │   └── ToolCallModels.kt         # Tool call data models
│       ├── data/
│       │   ├── ApiSettings.kt    # API configuration
│       │   ├── db/               # Room database
│       │   │   ├── AppDatabase.kt
│       │   │   ├── ConversationDao.kt
│       │   │   └── MessageEntity.kt
│       │   └── SettingsRepository.kt
│       ├── service/
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
│           └── ConnectionState.kt # Connection state, DeviceInfo, DeviceType
│
└── gradle/libs.versions.toml     # Version catalog
```

### Module Dependencies

```
phone-app ──────► common
glasses-app ────► common
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

### Recording & Auto-Analysis Flow (NEW)

```
┌─────────────┐                    ┌─────────────┐                    ┌─────────────┐
│   User UI   │                    │PhoneAIService│                    │   AI API    │
└──────┬──────┘                    └──────┬──────┘                    └──────┬──────┘
       │                                  │                                  │
       │  1. Start Recording (Phone/Glasses) │                               │
       ├─────────────────────────────────►│                                  │
       │                                  │                                  │
       │  2. Stop Recording               │                                  │
       ├─────────────────────────────────►│                                  │
       │                                  │                                  │
       │                                  │  3. Save WAV to disk             │
       │                                  │  4. Save to Room DB              │
       │                                  │                                  │
       │                                  │  5. Check autoAnalyzeRecordings  │
       │                                  │     (if enabled)                 │
       │                                  │                                  │
       │                                  │  6. STT transcription            │
       │                                  ├─────────────────────────────────►│
       │                                  │                                  │
       │                                  │  7. Transcription result         │
       │                                  │◄─────────────────────────────────┤
       │                                  │                                  │
       │                                  │  8. AI analysis request          │
       │                                  ├─────────────────────────────────►│
       │                                  │                                  │
       │                                  │  9. AI response                  │
       │                                  │◄─────────────────────────────────┤
       │                                  │                                  │
       │  10. Update conversation UI      │                                  │
       │◄─────────────────────────────────┤                                  │
       │                                  │                                  │
       ▼                                  ▼                                  ▼
```

**Key Implementation Points:**

- `ApiSettings.autoAnalyzeRecordings` controls auto-analysis (default: enabled)
- `ServiceBridge.transcribeRecordingFlow` handles recording events
- `PhoneAIService.processPhoneRecording()` orchestrates the full workflow

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

    suspend fun transcribe(pcmAudioData: ByteArray, languageCode: String = "zh-TW"): SpeechResult
    suspend fun transcribeAudioFile(audioData: ByteArray, mimeType: String, languageCode: String = "zh-TW"): SpeechResult
    /** Real streaming; default impl wraps chat() for non-streaming providers. */
    fun streamChat(userMessage: String): Flow<AiStreamEvent>
    /** Batch chat (compatibility API kept for all existing callers). */
    suspend fun chat(userMessage: String): String
    suspend fun analyzeImage(imageData: ByteArray, prompt: String = "Please describe this image"): String
    fun clearHistory()
}
```

> **Note:** `transcribeAudioFile()` accepts pre-encoded audio (M4A, MP3, OGG) with its MIME type.
> The default implementation falls back to `transcribe()` treating data as PCM.

#### Provider Registry & Model Catalog

Provider metadata is data-driven (`ai/catalog/`), not a growing `when` block:

- `ProviderRegistry` / `ProviderDescriptor` — one entry per provider: wire
  protocol (`ApiProtocol`), catalog format, models endpoint, auth style,
  regional endpoint support.
- `ModelCatalogRepository` — four-tier model catalog:
  **live Models API → on-device cache (24h TTL) → verified static fallback →
  manual model ID**. The fallback catalog records `LAST_VERIFIED_DATE` and the
  official documentation source per provider; a preview or deprecated model is
  never the sole default.
- `ModelCapabilities` — model-level flags (text/image/audio input, streaming,
  tool calling, reasoning, realtime, transcription, context sizes). Vision is
  decided per model, not per provider: a text-only model never receives an
  image request.
- `ModelCapabilityResolver` — live metadata → fallback capability map →
  conservative provider default → manual override (Custom provider).
- `ProviderRequestPolicy` — capability-driven request parameters. OpenAI-only
  knobs (`reasoning_effort`, `verbosity`, `max_completion_tokens`) are scoped
  to the OpenAI provider; DeepSeek reasoning models strip sampling params;
  Perplexity never receives penalty params; Groq/xAI/others use standard
  Chat Completions shapes.
- `ProviderApiException` — classified errors (invalid key, permission, model
  unavailable/deprecated, rate limit, quota, region, invalid parameter,
  unsupported image, context too long, network, timeout, unavailable) with
  HTTP status + provider code preserved and secrets redacted.

#### Streaming

`AiStreamEvent` (`TextDelta`, `ToolCallDelta`, `Citation`, `Usage`,
`Thinking`, `Completed`, `Error`) is emitted by:

- OpenAI-compatible SSE (Chat Completions chunks) and OpenAI Responses SSE
  (`response.output_text.delta`, `response.completed`, ...)
- Anthropic Messages SSE (`content_block_delta`, `message_delta`; thinking
  blocks surface only as a `Thinking` marker, never as text)
- Gemini `:streamGenerateContent?alt=sse`

DeepSeek `reasoning_content` and Claude thinking are never shown to the user.

#### Supported AI Providers

| Provider       | Protocol                   | Implementation            | Notes                                   |
| -------------- | -------------------------- | ------------------------- | --------------------------------------- |
| Gemini         | `GEMINI_GENERATE_CONTENT`  | `GeminiService`           | Native generateContent + SSE            |
| OpenAI         | `OPENAI_RESPONSES`         | `OpenAiCompatibleService` | Responses API, Chat Completions fallback |
| Anthropic      | `ANTHROPIC_MESSAGES`       | `AnthropicService`        | x-api-key + anthropic-version, SSE      |
| DeepSeek       | `OPENAI_CHAT_COMPLETIONS`  | `DeepSeekService`         | reasoning_content side channel          |
| Groq           | `OPENAI_CHAT_COMPLETIONS`  | `OpenAiCompatibleService` | + Whisper transcription endpoint        |
| xAI            | `OPENAI_CHAT_COMPLETIONS`  | `OpenAiCompatibleService` | grok-4.5 vision via image_url           |
| Alibaba (Qwen) | `OPENAI_CHAT_COMPLETIONS`  | `OpenAiCompatibleService` | Regional endpoints (China/SG/US/DE/JP/custom) |
| Zhipu (GLM)    | `OPENAI_CHAT_COMPLETIONS`  | `OpenAiCompatibleService` | Text vs vision models tracked separately |
| Baidu          | `BAIDU_QIANFAN_V2`         | `QianfanV2Service`        | Bearer key; legacy OAuth via `BaiduService` |
| Perplexity     | `OPENAI_CHAT_COMPLETIONS`  | `PerplexityService`       | Sonar; citations/search results preserved |
| Moonshot       | `OPENAI_CHAT_COMPLETIONS`  | `OpenAiCompatibleService` | Kimi multimodal                         |
| Mistral        | `OPENAI_CHAT_COMPLETIONS`  | `OpenAiCompatibleService` | Models API capability metadata parsed   |
| Gemini Live    | `GEMINI_LIVE`              | `GeminiLiveSession`       | WebSocket; `GeminiService` for non-live ops |
| AnythingLLM    | `ANYTHING_LLM`             | adapter                   | Capability depends on workspace LLM     |
| Custom         | `CUSTOM_OPENAI_COMPATIBLE` | `OpenAiCompatibleService` | Chat Completions or Responses, cleartext warning |
| On-Device Gemma | `LOCAL_INFERENCE`         | `LocalGemmaService`       | Offline, keyless, text-only; pluggable local engine; installed models discovered via `LocalModelCatalogSource` |

STT is decoupled from chat: only providers with a real transcription endpoint
(OpenAI Whisper, Groq Whisper, Gemini native audio) can transcribe; the chat
model catalog never implies STT support.

##### On-Device Inference (`LOCAL_INFERENCE`)

The On-Device Gemma provider follows the same data-driven registry pattern but
runs entirely on the device:

- `ProviderRegistry` marks it `requiresApiKey = false`, `catalogFormat = NONE`,
  `modelsEndpointPath = null` — there is no network catalog.
- `ModelCatalogRepository` resolves its catalog from `LocalModelCatalogSource`
  (a scan of the app-private `filesDir/models/gemma` directory for `.task` /
  `.gguf` files) merged with the verified fallback entries, never the network.
- `LocalGemmaService` implements the full `AiServiceProvider` contract; text
  chat delegates to a pluggable `LocalInferenceEngine`, while `transcribe` and
  `analyzeImage` degrade gracefully with clear messages.
- `PhoneAIService` skips its cloud-key fallback for keyless providers, so the
  device provider is never silently switched to Gemini.

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

enum class ConnectionState {
    DISCONNECTED, CONNECTING, CONNECTED, RECONNECTING, ERROR
}

data class DeviceInfo(
    val name: String,
    val address: String,
    val type: DeviceType,
    val batteryLevel: Int = -1,
    val firmwareVersion: String = ""
)

enum class DeviceType { PHONE, GLASSES }
```

#### Message Types

```kotlin
enum class MessageType(val code: Int) {
    // Connection management (0x00-0x0F)
    HANDSHAKE(0x00),
    HANDSHAKE_ACK(0x01),
    HEARTBEAT(0x02),
    HEARTBEAT_ACK(0x03),
    DISCONNECT(0x0F),

    // Voice related (0x10-0x1F)
    VOICE_START(0x10),           // Glasses -> Phone: Start recording
    VOICE_DATA(0x11),            // Glasses -> Phone: Audio data
    VOICE_END(0x12),             // Glasses -> Phone: End recording
    VOICE_CANCEL(0x13),          // Glasses -> Phone: Cancel recording
    REMOTE_RECORD_START(0x14),   // Phone -> Glasses: Start recording remotely
    REMOTE_RECORD_STOP(0x15),    // Phone -> Glasses: Stop recording remotely

    // AI processing (0x20-0x2F)
    AI_PROCESSING(0x20),         // Phone -> Glasses: Processing
    AI_RESPONSE_TEXT(0x21),      // Phone -> Glasses: Text response
    AI_RESPONSE_TTS(0x22),       // Phone -> Glasses: TTS audio
    USER_TRANSCRIPT(0x23),       // Internal: User speech recognition result
    AI_ERROR(0x2F),              // Phone -> Glasses: Error message

    // Display control (0x30-0x3F)
    DISPLAY_TEXT(0x30),          // Phone -> Glasses: Display text
    DISPLAY_CLEAR(0x31),         // Phone -> Glasses: Clear display
    DISPLAY_STATUS(0x32),        // Phone -> Glasses: Status update

    // Photo transfer (0x40-0x4F)
    PHOTO_START(0x40),           // Glasses -> Phone: Start photo transfer
    PHOTO_DATA(0x41),            // Glasses -> Phone: Photo chunk data
    PHOTO_END(0x42),             // Glasses -> Phone: End photo transfer
    PHOTO_ACK(0x43),             // Phone -> Glasses: Acknowledge chunk
    PHOTO_RETRY(0x44),           // Phone -> Glasses: Request retransmit
    PHOTO_CANCEL(0x45),          // Bidirectional: Cancel transfer
    PHOTO_ANALYSIS_RESULT(0x46), // Phone -> Glasses: AI analysis result
    CAPTURE_PHOTO(0x47),         // Phone -> Glasses: Request to capture photo

    // Live mode (0x50-0x5F)
    LIVE_SESSION_START(0x50),    // Phone -> Glasses: Live session started
    LIVE_SESSION_END(0x51),      // Phone -> Glasses: Live session ended
    LIVE_TRANSCRIPTION(0x52),    // Bidirectional: Real-time transcription
    VIDEO_FRAME(0x53),           // Glasses -> Phone: Video frame for Live mode

    // System control (0xF0-0xFF)
    SYSTEM_STATUS(0xF0),         // Bidirectional: System status
    SYSTEM_CONFIG(0xF1),         // Phone -> Glasses: Config update
    SYSTEM_ERROR(0xFF)           // Bidirectional: System error
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
class YourProviderService(
    private val apiKey: String,
    private val modelId: String = "your-model",
    private val systemPrompt: String = ""
) : AiServiceProvider {
    override val provider = AiProvider.YOUR_PROVIDER

    override suspend fun transcribe(pcmAudioData: ByteArray, languageCode: String): SpeechResult {
        // Implement STT or throw UnsupportedOperationException
    }

    override suspend fun chat(userMessage: String): String {
        // Implement API call, return response text
    }

    override suspend fun analyzeImage(imageData: ByteArray, prompt: String): String {
        // Implement image analysis
    }

    override fun clearHistory() { /* Clear conversation */ }
}
```

**2. Register in factory:**

```kotlin
// phone-app/src/.../service/ai/AiServiceFactory.kt
// In AiServiceFactory.createService():
fun createService(settings: ApiSettings): AiServiceProvider {
    return when (settings.aiProvider) {
        AiProvider.YOUR_PROVIDER -> YourProviderService(
            apiKey = settings.getCurrentApiKey(),
            modelId = settings.getCurrentModelId(),
            systemPrompt = settings.systemPrompt
        )
        // ... other providers (13 total)
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
    // Note: 0x50-0x5F range is reserved for Live mode
    YOUR_NEW_TYPE(0xA0)  // Use an unused code range
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
