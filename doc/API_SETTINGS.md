# API Settings Guide

> 📖 [繁體中文版](zh-TW/API_SETTINGS.md)

**Complete configuration guide for AI and STT providers in Rokid AI Assistant.**

---

## 🚀 Quick Start

```bash
# 1. Copy template
cp local.properties.template local.properties

# 2. Add your Gemini API key (minimum required)
echo "GEMINI_API_KEY=your_key_here" >> local.properties

# 3. Rebuild
./gradlew assembleDebug
```

> Get your free Gemini API key at [Google AI Studio](https://ai.google.dev/) in 2 minutes.

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

| Key                   | Required       | Where to Get                                        |
| --------------------- | -------------- | --------------------------------------------------- |
| `GEMINI_API_KEY`      | ✅ Yes         | [Google AI Studio](https://ai.google.dev/)          |
| `ROKID_CLIENT_SECRET` | ⚠️ For glasses | Rokid Developer Portal                              |
| `OPENAI_API_KEY`      | Optional       | [OpenAI Platform](https://platform.openai.com/)     |
| `ANTHROPIC_API_KEY`   | Optional       | [Anthropic Console](https://console.anthropic.com/) |

### Provider Comparison

| Provider      | Vision | Streaming | Free Tier   | Latency  |
| ------------- | ------ | --------- | ----------- | -------- |
| **Gemini**    | ✅     | ✅        | ✅ Generous | Medium   |
| **OpenAI**    | ✅     | ✅        | ❌          | Low      |
| **Anthropic** | ✅     | ✅        | ❌          | Medium   |
| **Groq**      | ✅     | ✅        | ✅ Limited  | Very Low |
| **DeepSeek**  | ❌     | ✅        | ✅          | Medium   |

> Note: In current code, non-CUSTOM providers use built-in default base URLs; `CUSTOM` allows user-defined OpenAI-compatible endpoints.

---

## AI Chat Providers

### Supported Providers

| Provider        | Models                                       | Vision | Base URL                                             |
| --------------- | -------------------------------------------- | ------ | ---------------------------------------------------- |
| **Gemini**      | Gemini 3 Pro/Flash, 2.5 Pro/Flash/Flash-Lite | ✅     | `https://generativelanguage.googleapis.com/v1beta/`  |
| **OpenAI**      | GPT-5.2, GPT-5, o3, o4-mini                  | ✅     | `https://api.openai.com/v1/`                         |
| **Anthropic**   | Claude Opus 4.6, Sonnet 4.5, Haiku 4.5       | ✅     | `https://api.anthropic.com/v1/`                      |
| **DeepSeek**    | DeepSeek Chat, DeepSeek Reasoner             | ❌     | `https://api.deepseek.com/`                          |
| **Groq**        | Llama 4 Scout/Maverick                       | ✅     | `https://api.groq.com/openai/v1/`                    |
| **xAI**         | Grok 4, Grok 4.1, Grok 3                     | ❌     | `https://api.x.ai/v1/`                               |
| **Alibaba**     | Qwen 3 Max, Qwen 2.5 VL 72B/32B/7B           | ✅     | `https://dashscope.aliyuncs.com/compatible-mode/v1/` |
| **Zhipu**       | GLM-5, GLM-4.7, GLM-4 Plus                   | ✅     | `https://api.z.ai/api/paas/v4/`                      |
| **Baidu**       | ERNIE 4.0 8K, ERNIE 3.5 8K                   | ❌     | `https://aip.baidubce.com/rpc/2.0/...`               |
| **Perplexity**  | Sonar, Sonar Pro, Sonar Reasoning Pro        | ❌     | `https://api.perplexity.ai/`                         |
| **Moonshot**    | Kimi K2.5, Moonshot V1 128K/32K/8K           | ✅     | `https://api.moonshot.ai/v1/`                        |
| **Gemini Live** | Gemini Live (session mode)                   | ✅     | `wss://generativelanguage.googleapis.com/ws/...`     |
| **Custom**      | User-defined                                 | Varies | User-defined (e.g., Ollama, LM Studio)               |

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

These providers use their native transcription capabilities:

| Provider | Method                 | Streaming |
| -------- | ---------------------- | --------- |
| Gemini   | Native multimodal      | ❌        |
| OpenAI   | Whisper API            | ❌        |
| Groq     | Whisper (accelerated)  | ❌        |
| xAI      | OpenAI-compatible path | ❌        |

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
| AI Provider             | Settings → AI Provider        | Select Gemini/OpenAI/etc.                    |
| Model                   | Settings → Model              | Choose specific model                        |
| Custom Endpoint         | Settings → Custom             | For Ollama/LM Studio                         |
| STT Provider            | Settings → Speech             | Configure STT                                |
| System Prompt           | Settings → System Prompt      | Customize AI behavior                        |
| Auto Analyze Recordings | Settings → Recording Settings | Auto-send recordings to AI for transcription |

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
A: Free tier has limits. Options:
   1. Wait for quota reset (daily)
   2. Upgrade to paid tier
   3. Switch to another provider temporarily
```

**Q: OpenAI returns 401 Unauthorized**

```
A: 1. Check API key is valid
   2. Ensure billing is set up (no free tier)
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
