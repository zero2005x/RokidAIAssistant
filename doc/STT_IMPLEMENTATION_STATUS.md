# STT Provider Implementation Status

## Overview

This document reflects the **current code state** of STT provider support in `phone-app`.

- **Total STT Providers**: 18
- **Implemented in Factory**: 18
- **Planned/Placeholder Providers**: 0
- **Factory Source**: `phone-app/src/main/java/com/example/rokidphone/service/stt/SttServiceFactory.kt`
- **Provider Enum Source**: `phone-app/src/main/java/com/example/rokidphone/service/stt/SttProvider.kt`

---

## Provider Matrix (Current)

### Built-in (share main AI keys)

1. `GEMINI` → `GeminiSttAdapter`
2. `OPENAI_WHISPER` → `OpenAiWhisperSttAdapter`
3. `GROQ_WHISPER` → `GroqWhisperSttAdapter`

### Dedicated STT providers

4. `GOOGLE_CLOUD_STT` → `GoogleCloudSttService`
5. `AZURE_SPEECH` → `AzureSpeechSttService`
6. `AWS_TRANSCRIBE` → `AwsTranscribeSttService`
7. `IBM_WATSON` → `IbmWatsonSttService`
8. `DEEPGRAM` → `DeepgramSttService`
9. `ASSEMBLYAI` → `AssemblyAiSttService`
10. `IFLYTEK` → `IflytekSttService`
11. `HUAWEI_SIS` → `HuaweiSisSttService`
12. `VOLCENGINE` → `VolcengineSttService`
13. `REV_AI` → `RevAiSttService`
14. `SPEECHMATICS` → `SpeechmaticsSttService`
15. `ALIBABA_ASR` → `AliyunSttService`
16. `TENCENT_ASR` → `TencentSttService`
17. `BAIDU_ASR` → `BaiduSttService`
18. `OTTER_AI` → `OtterAiSttService`

---

## Architecture Compliance

All providers are created through `SttServiceFactory.createService(...)` and conform to:

- `SttService` interface
- `BaseSttService` common utilities (WAV conversion, retry, multipart builder)
- `SttValidationResult`-based credential checks

Key interfaces and base classes:

- `phone-app/src/main/java/com/example/rokidphone/service/stt/SttService.kt`
- `phone-app/src/main/java/com/example/rokidphone/service/stt/BaseSttService.kt`

---

## Test Coverage Status

### Factory / registration coverage

- `SttServiceFactoryTest` verifies:
  - All `SttProvider` enum values are handled
  - Missing credentials return `null`
  - `getImplementedProviders()` matches expected providers
  - `getPlannedProviders()` is empty

### Provider-level coverage

Provider test classes exist under:

- `phone-app/src/test/java/com/example/rokidphone/service/stt/`

Includes tests for all 18 providers/adapters, covering at least:

- credential validation behavior
- request shape/header/auth behavior (where applicable)
- error-path handling
- basic success-path parsing

---

## Notes

- This status file intentionally tracks **implementation + test reality**, not roadmap items.
- If a provider is intentionally downgraded to placeholder/stub in future, update:
  1. `SttServiceFactory.getImplementedProviders()`
  2. `SttServiceFactory.getPlannedProviders()`
  3. this document and related test expectations.
