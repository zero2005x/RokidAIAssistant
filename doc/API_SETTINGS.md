# API Settings Guide

> 📖 [繁體中文版](zh-TW/API_SETTINGS.md)

**Complete configuration guide for AI and STT providers in Rokid AI Assistant.**

---

## 🚀 Quick Start

```bash
# 1. Copy template
cp local.properties.template local.properties

# 2. (Optional) Add any provider key — the app installs and opens without one.
#    Keys can also be entered later in the in-app Settings screen.
echo "GEMINI_API_KEY=your_key_here" >> local.properties

# 3. Rebuild
./gradlew assembleDebug
```

> No AI key is required to install the app or open Settings. You only need a
> key for the single provider you actually choose to use (entered in-app or
> via `local.properties`).

---

## Scope

### In Scope

- Configuration of all supported AI chat providers
- Configuration of all supported STT (Speech-to-Text) providers
- Rokid CXR SDK authentication setup
- `local.properties` file management

### Out of Scope

- AI model fine-tuning or training
- Custom model deployment
- Billing or quota management for providers

---

## Table of Contents

- [Quick Reference](#quick-reference)
- [AI Chat Providers](#ai-chat-providers)
- [Speech-to-Text Providers](#speech-to-text-providers)
- [Rokid SDK Configuration](#rokid-sdk-configuration)
- [Configuration Files](#configuration-files)
- [In-App Settings](#in-app-settings)
- [FAQ & Troubleshooting](#faq--troubleshooting)

---

## Quick Reference

### Required Keys

| Key                   | Required             | Where to Get                                        |
| --------------------- | -------------------- | --------------------------------------------------- |
| `ROKID_CLIENT_SECRET` | ⚠️ For glasses only  | Rokid Developer Portal                              |
| `GEMINI_API_KEY`      | Optional             | [Google AI Studio](https://ai.google.dev/)          |
| `OPENAI_API_KEY`      | Optional             | [OpenAI Platform](https://platform.openai.com/)     |
| `ANTHROPIC_API_KEY`   | Optional             | [Anthropic Console](https://console.anthropic.com/) |

> Only the provider you select requires credentials. Build-time keys in
> `local.properties` are optional; in-app keys are stored in Android
> Keystore-backed encrypted preferences (never in plaintext, never in logs).

### Provider Capability Matrix

Last verified: **2026-08-02**. The app loads each provider's model list
dynamically from its official Models API; the "Representative fallback models"
column is only used when the API is unreachable and no cache exists.

| Provider    | Protocol                 | Dynamic models | Vision (per model) | Streaming | STT endpoint | Realtime | Auth                |
| ----------- | ------------------------ | -------------- | ------------------ | --------- | ------------ | -------- | ------------------- |
| Gemini      | generateContent (native) | ✅ Models API  | ✅                 | ✅ SSE    | ✅ native audio | ❌    | API key             |
| OpenAI      | Responses API + Chat Completions fallback | ✅ Models API | ✅ | ✅ SSE | ✅ Whisper | ❌ | Bearer key |
| Anthropic   | Messages (native)        | ✅ Models API  | ✅                 | ✅ SSE    | ❌           | ❌       | x-api-key           |
| DeepSeek    | Chat Completions         | ✅ Models API  | ❌                 | ✅ SSE    | ❌           | ❌       | Bearer key          |
| Groq        | Chat Completions         | ✅ Models API  | ✅ (select models) | ✅ SSE    | ✅ Whisper   | ❌       | Bearer key          |
| xAI         | Chat Completions         | ✅ Models API  | ✅ (e.g. Grok 4.5) | ✅ SSE    | ❌           | ❌       | Bearer key          |
| Alibaba     | Chat Completions         | ✅ Models API  | ✅ (VL models)     | ✅ SSE    | ❌           | ❌       | Bearer key + region |
| Z.AI (GLM)  | Chat Completions         | ✅ Models API  | ✅ (GLM-V models)  | ✅ SSE    | ❌           | ❌       | Bearer key          |
| Baidu       | Qianfan v2 (+ legacy OAuth) | ✅ Models API | ✅ (VL models)  | ✅ SSE    | ❌           | ❌       | Bearer key / legacy pair |
| Perplexity  | Sonar Chat Completions   | ❌ (fallback list) | ✅ (Sonar)   | ✅ SSE    | ❌           | ❌       | Bearer key          |
| Moonshot    | Chat Completions         | ✅ Models API  | ✅ (Kimi multimodal) | ✅ SSE  | ❌           | ❌       | Bearer key          |
| Mistral     | Chat Completions         | ✅ Models API (capabilities parsed) | ✅ (select models) | ✅ SSE | ❌ | ❌ | Bearer key |
| Gemini Live | Live WebSocket           | ✅ Models API  | ✅                 | ✅ WS     | ✅ native    | ✅       | API key             |
| AnythingLLM | Workspace API            | ❌ (workspace-defined) | ⚠️ unknown | ❌    | ❌           | ❌       | API key + URL       |
| Custom      | Chat Completions or Responses | ✅ (endpoint-dependent) | ⚠️ manual override | ✅ | ❌    | ❌       | Optional key        |
| On-Device Gemma | Local inference (no network) | ✅ (installed-file scan) | ❌ text-only | ✅ (engine-dependent) | ❌ | ❌ | None (on-device) |

> Vision is a **model-level** capability: a provider marked ✅ still rejects
> image requests for its text-only models. Free/trial quotas may exist for
> some providers — check each official console; they are not guaranteed to
> exist permanently.

### Representative fallback models (verified 2026-09-30)

| Provider   | Fallback examples (stable unless noted)                                  |
| ---------- | ------------------------------------------------------------------------ |
| Gemini     | gemini-3.8-flash, gemini-3.6-flash, gemini-3.5-flash(-lite), gemini-2.5-pro/flash/lite |
| OpenAI     | gpt-6-astra/sol/luna, gpt-5.6/terra/luna (gpt-5.4, gpt-4o: legacy)       |
| Anthropic  | claude-sonnet-5-5, claude-opus-5-5, claude-sonnet-5, claude-opus-5       |
| DeepSeek   | deepseek-flash, deepseek-v4-pro (deepseek-v4-flash: temporary alias)       |
| Groq       | gpt-oss-120b/20b, qwen3.8-27b (Llama 3.3 retired; Llama 4 Scout/Maverick: preview) |
| xAI        | grok-4.7, grok-4.5 (vision), grok-4.1-fast, grok-4                       |
| Alibaba    | qwen3.8-max/flash, qwen3.7-max/plus/flash, qwen2.5-vl-72b/32b            |
| Z.AI       | glm-5.1, glm-5v-turbo (vision), glm-4.7-flash                            |
| Baidu      | ernie-5.1, ernie-5.0, ernie-4.5-turbo-128k, ernie-4.5-turbo-vl (vision)  |
| Perplexity | sonar, sonar-pro, sonar-reasoning-pro, sonar-deep-research               |
| Moonshot   | kimi-k2.6, kimi-k2.5, kimi-k2.5-thinking                                  |
| Mistral    | mistral-medium-3-5, mistral-large-2512, mistral-small-2603, ministral-3-8b |
| On-Device Gemma | gemma-3n-E2B-it, gemma-3n-E4B-it (installed local files also listed)  |

> The live Models API of your account always wins over this list. To update
> the fallback: check each provider's official model docs, edit
> `ai/catalog/FallbackModelCatalog.kt`, and bump `LAST_VERIFIED_DATE`.

---

## AI Chat Providers

### Supported Providers

The model list for each provider is loaded dynamically from its official API
(live → cache → verified fallback → manual entry). Base URLs:

| Provider        | Base URL                                                           |
| --------------- | ------------------------------------------------------------------ |
| **Gemini**      | `https://generativelanguage.googleapis.com/v1beta/`                |
| **OpenAI**      | `https://api.openai.com/v1/`                                       |
| **Anthropic**   | `https://api.anthropic.com/v1/`                                    |
| **DeepSeek**    | `https://api.deepseek.com/`                                        |
| **Groq**        | `https://api.groq.com/openai/v1/`                                  |
| **xAI**         | `https://api.x.ai/v1/`                                             |
| **Alibaba**     | Regional endpoints (China / Singapore / US / Germany / Japan / custom workspace URL) |
| **Zhipu**       | `https://api.z.ai/api/paas/v4/`                                    |
| **Baidu**       | `https://qianfan.baidubce.com/v2/` (legacy RPC kept for migration) |
| **Perplexity**  | `https://api.perplexity.ai/` (Sonar)                               |
| **Moonshot**    | `https://api.moonshot.ai/v1/`                                      |
| **Mistral**     | `https://api.mistral.ai/v1/`                                       |
| **Gemini Live** | `wss://generativelanguage.googleapis.com/ws/...`                   |
| **AnythingLLM** | User-defined server URL                                            |
| **Custom**      | User-defined (Ollama, LM Studio, vLLM...)                          |
| **On-Device Gemma** | None — local files in `filesDir/models/gemma` (`local://gemma/` sentinel) |

### Model catalog (four tiers)

1. **Live** — fetched from the provider's Models API with your key.
2. **Cached** — last successful fetch, reused for 24h (and longer on network failure).
3. **Fallback** — verified static list (2026-08-02), never contains TTS/image-generation/transcription models.
4. **Manual** — you can always type a model ID by hand; manual selections are never deleted, even if absent from the remote list (a warning is shown instead).

Each provider remembers its own last-selected model: switching
OpenAI → Gemini → OpenAI restores your previous OpenAI model.

### Getting API Keys

#### Google Gemini (Recommended)

```
URL: https://ai.google.dev/
Steps:
1. Sign in with Google account
2. Click "Get API key"
3. Create key in new project
4. Copy key to local.properties
```

#### OpenAI

```
URL: https://platform.openai.com/
Steps:
1. Create account / Sign in
2. Settings → API Keys
3. Create new secret key
4. Copy key (shown only once!)
```

#### Anthropic

```
URL: https://console.anthropic.com/
Steps:
1. Create account
2. API Keys section
3. Generate new key
```

#### DeepSeek

```
URL: https://platform.deepseek.com/
Steps:
1. Register and verify
2. API section
3. Create API key
```

#### Groq

```
URL: https://console.groq.com/
Steps:
1. Sign up
2. Dashboard → API Keys
3. Generate key
```

---

## Speech-to-Text Providers

### Built-in AI Provider STT

STT is decoupled from chat providers. Only providers with a real transcription
endpoint can transcribe:

| Provider | Method                     | Notes                              |
| -------- | -------------------------- | ---------------------------------- |
| Gemini   | Native multimodal audio    | Uses the selected Gemini chat model |
| OpenAI   | Whisper `/audio/transcriptions` | Transcription model, not a chat model |
| Groq     | Whisper (accelerated)      | `whisper-large-v3-turbo`           |

> Being "OpenAI-compatible" does NOT imply a working transcription endpoint —
> xAI, DeepSeek, Alibaba, Zhipu, Moonshot, Mistral, Perplexity and Baidu chat
> providers are never sent STT requests.

### Dedicated STT Providers

| Provider             | Auth Type                 | Streaming | Real-time |
| -------------------- | ------------------------- | --------- | --------- |
| **Google Cloud STT** | Service Account / API Key | ✅        | ✅        |
| **Azure Speech**     | Subscription Key + Region | ✅        | ✅        |
| **AWS Transcribe**   | IAM Credentials           | ✅        | ✅        |
| **IBM Watson**       | IBM IAM                   | ✅        | ✅        |
| **Deepgram**         | API Key                   | ✅        | ✅        |
| **AssemblyAI**       | API Key                   | ✅        | ✅        |
| **iFlytek**          | App ID + API Key + Secret | ❌        | ✅        |

### STT Provider Configuration

#### Azure Speech

```
Subscription Key: <your-key>
Region: eastus, westus2, etc.
```

#### AWS Transcribe

```
Access Key ID: <your-access-key>
Secret Access Key: <your-secret-key>
Region: us-east-1, etc.
```

#### Deepgram

```
API Key: <your-api-key>
```

---

## Rokid SDK Configuration

### CXR-M SDK (Phone Side)

**Location**: `phone-app/build.gradle.kts`

```kotlin
implementation("com.rokid.cxr:client-m:1.0.4")
```

**Purpose**:

- Bluetooth connection to glasses
- AI event listening (long press detection)
- Photo capture control

**Configuration**:

```properties
# In local.properties (remove hyphens from the secret)
# Original: xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx
# Enter as: xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx
ROKID_CLIENT_SECRET=your_rokid_client_secret_here
```

### CXR-S SDK (Glasses Side)

**Location**: `glasses-app/build.gradle.kts`

```kotlin
implementation("com.rokid.cxr:cxr-service-bridge")
```

**Purpose**:

- Receiving messages from phone
- Sending data back to phone

---

## Configuration Files

### local.properties

**Location**: Project root (`RokidAIAssistant/local.properties`)

> ⚠️ This file is in `.gitignore` — never commit to version control!

```properties
# =============================================
# Rokid AI Assistant - API Configuration
# =============================================

# [Required] Gemini API Key
# Get from: https://ai.google.dev/
GEMINI_API_KEY=your_gemini_api_key_here

# [Required for Glasses] Rokid Client Secret
# Remove hyphens before entering
ROKID_CLIENT_SECRET=your_rokid_client_secret_here

# [Optional] OpenAI API Key (for GPT/Whisper)
OPENAI_API_KEY=your_openai_api_key_here

# [Optional] Anthropic API Key (for Claude)
ANTHROPIC_API_KEY=your_anthropic_api_key_here
```

### How Keys Are Read

Keys are loaded in `build.gradle.kts`:

```kotlin
// phone-app/build.gradle.kts (lines 25-35)
val localProps = rootProject.file("local.properties")
val props = Properties().apply {
    if (localProps.exists()) {
        localProps.inputStream().use { load(it) }
    }
}
buildConfigField("String", "GEMINI_API_KEY", "\"${props.getProperty("GEMINI_API_KEY", "")}\"")
```

Access in code via `BuildConfig.GEMINI_API_KEY`.

---

## In-App Settings

Additional API keys can be configured in the app's **Settings** screen:

| Setting                 | Location in App               | Purpose                                      |
| ----------------------- | ----------------------------- | -------------------------------------------- |
| AI Provider             | Settings → AI Provider        | Select provider (shows protocol + configured state) |
| Model                   | Settings → Model              | Search/refresh catalog, source labels (Live/Cached/Fallback), capability badges, manual model ID |
| API Keys                | Settings → API Keys           | Only the selected provider's credentials are required |
| Baidu Auth              | Settings → API Keys (Baidu)   | Qianfan v2 key, or legacy API Key + Secret Key toggle |
| Alibaba Region          | Settings → API Keys (Alibaba) | China / Singapore / US / Germany / Japan / custom workspace URL |
| Custom Endpoint         | Settings → Custom             | Base URL, model ID, optional key, models path, Chat Completions/Responses protocol (cleartext HTTP warning) |
| STT Provider            | Settings → Speech             | Configure STT                                |
| System Prompt           | Settings → System Prompt      | Customize AI behavior                        |
| Auto Analyze Recordings | Settings → Recording Settings | Auto-send recordings to AI for transcription |

### Baidu migration

New Baidu setups use a single **Qianfan v2 API key** (Bearer). Existing
installations that only have the legacy API Key + Secret Key pair keep working
in legacy mode automatically — the old credentials are never deleted, and you
can switch explicitly with the "Legacy authentication" toggle.

### DeepSeek migration

Stored selections of the legacy `deepseek-chat` / `deepseek-reasoner` IDs are
migrated automatically to `deepseek-flash` / `deepseek-v4-pro`. Stored
`deepseek-v4-flash` selections also migrate to `deepseek-flash`. You may
still enter the old IDs manually (shown with a deprecated warning).

**Path**: `phone-app/src/.../ui/settings/SettingsScreen.kt`

---

## FAQ & Troubleshooting

### API Key Issues

**Q: "API key not found" error during build**

```
A: Check that local.properties exists in project root (not in a module folder).
   Run: cat local.properties  # Verify file contents
```

**Q: "Invalid API key" error at runtime**

```
A: 1. Verify the key is correct (no extra spaces)
   2. Check key hasn't expired or been revoked
   3. Rebuild after changing keys: ./gradlew clean assembleDebug
```

**Q: API key works in test but not in release build**

```
A: Keys are embedded at build time. Rebuild release after key changes:
   ./gradlew clean assembleRelease
```

### Provider-Specific Issues

**Q: Gemini returns "quota exceeded"**

```
A: Quota depends on your Google AI plan (trial/free quotas may exist — see
   the official console; they are not guaranteed permanently). Options:
   1. Wait for quota reset
   2. Upgrade the plan
   3. Switch to another provider temporarily
```

**Q: OpenAI returns 401 Unauthorized**

```
A: 1. Check API key is valid
   2. Ensure billing is set up for the account
   3. Verify key has correct permissions
```

**Q: Groq is very fast but responses are cut off**

```
A: Groq has lower max token limits. Adjust in settings or use for shorter responses.
```

### Rokid SDK Issues

**Q: Cannot connect to glasses**

```
A: 1. Verify ROKID_CLIENT_SECRET is correct
   2. Remove hyphens from the secret
   3. Enable Bluetooth on both devices
   4. Restart both apps
```

**Q: Photo capture fails**

```
A: 1. Grant camera permission on glasses
   2. Ensure CXR connection is established
   3. Check Logcat for CXR errors
```
