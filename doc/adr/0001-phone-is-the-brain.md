# ADR 0001: Phone is the brain, glasses handle display and input

- **Status:** Accepted
- **Date:** 2026-10-08

## Context

Smart glasses such as Rokid AR glasses (`RG_glasses`) have strict battery, thermal, memory, and input constraints. Entering API keys, editing custom endpoints, or managing multi-provider model catalogs on a head-mounted touchpad is error-prone, and running network-heavy speech-to-text (STT), large language model (LLM) clients, text-to-speech (TTS) synthesis, and encrypted database storage directly on the glasses drains the wearable battery quickly.

Earlier revisions of the repository also carried a standalone `:app` module alongside `:phone-app` and `:glasses-app`, blurring the boundary between what runs on the phone and what runs on the glasses.

## Decision

We split the system into two dedicated Android applications connected over Bluetooth, sharing only protocol and constant definitions in `:common`:

1. **Phone (`:phone-app`) is the brain:**
   - Runs the foreground `PhoneAIService`, holds provider credentials in Android Keystore (`EncryptedSharedPreferences`), and resolves model catalogs (`ModelCatalogRepository`).
   - Executes all STT transcription (`SttService` / `AiServiceProvider`), pre-generation decision routing (`DecisionRouter`), AI chat and vision inference, and TTS synthesis (`TextToSpeechService`).
   - Persists conversation history, recordings, photos, and local routing metrics in Room (`AppDatabase`).
   - Hosts all configuration UI, including provider keys, routing slots, and the glasses viewport preview.
2. **Glasses (`:glasses-app`) handle display and input:**
   - Capture microphone PCM audio (`AudioRecord`) and camera images (`Camera2` via `UnifiedCameraManager`) and forward them to the phone over Bluetooth.
   - Render connection state, recording status, hints, and paginated AI answers inside the user-configured safe viewport (`MainActivity`, `GlassesViewModel`, `MeasuredTextPaginator`).
   - Handle physical touchpad and button gestures (tap to record, long-press/camera key to capture a photo, swipe/volume keys to turn pages).
3. **Single reference architecture:** The legacy `:app` module is removed so the repository contains only `:common`, `:phone-app`, and `:glasses-app`.

## Consequences

- The glasses app remains small, responsive, and decoupled from AI/STT vendor SDKs; adding or updating model logic, prompts, or database migrations requires no changes on the glasses.
- Users configure credentials, models, and display geometry on the phone screen rather than on the glasses.
- Standalone glasses-only AI operation is out of scope: the glasses require a Bluetooth link to the phone for transcription and generation (while the phone itself may optionally use an experimental local Gemma engine without network access).
- Every voice and photo interaction traverses the Bluetooth link, making framing, payload compression, and backward-compatible protocol evolution critical (see [ADR 0002](0002-shared-binary-protocol-and-capability-handshake.md)).
