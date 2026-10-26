# Decision Routing and Glasses Display Settings

> 📖 [繁體中文版](zh-TW/DECISION_ROUTING_AND_DISPLAY.md)

## Model Routing

In phone **Settings → Model routing**, you can enable the decision layer, choose an existing Gemini / OpenAI key, TypeSafe Jev (Experimental), or self-hosted Laya (Experimental), and assign three model slots: Fast, Balanced, and Quality. Each slot can pick a model across AI providers; if the selected provider lacks credentials, the app falls back to the primary model. Model routing applies only to general text chat and questions transcribed from voice recordings; photo analysis and Gemini Live keep their dedicated pipelines. Currently the user uses Gemini for decision routing, and a Laya server URL has not been prepared yet.

> **Experimental:** `Jev`, `Laya`, and the multi-step `Agents` design are marked as **Experimental**. They remain in the code and documentation but receive no further feature work unless local routing metrics (`routing_metrics`) justify it (see [`CONTRIBUTING.md`](../CONTRIBUTING.md) and [ADR 0004](adr/0004-decision-routing-before-generation.md)).

The decision layer sends **the text of the current question** to the selected service. Jev / Laya use `choice` on `POST /v1/systemone`; Gemini / OpenAI use structured JSON classification. The selected generation model receives the last ten messages of the current conversation. The decision step returns only a difficulty classification—it **does not generate an answer** and cannot trigger photo capture or audio recording. Next to the answer, the UI displays the actual generation model and the app's routing rule reason; LLM classification does not display a self-reported confidence percentage.

If the decision service fails to connect, returns insufficient confidence, maps to an unset slot, or lacks credentials, the app uses the original primary model. If generation with the chosen slot fails, the app retries with the primary model. A connection failure never silently switches to another decision backend. Users can disable model routing to restore the original flow.

### Jev (Experimental)

> **Experimental:** The Jev decision backend is experimental and not under active development until routing metrics justify further work.

Enter your TypeSafe API key in Settings. The phone connects to `https://api.typesafe.ai/v1/systemone`, authenticates with a Bearer token, and specifies `jev-latest`. See [TypeSafe API](https://docs.typesafe.ai/api).

### Laya (Experimental)

> **Experimental:** The self-hosted Laya decision backend is experimental and not under active development until routing metrics justify further work.

Install the Python package `laya[serve]` listed on the Hugging Face model card on your own server and run `laya-serve` to expose a Jev-compatible `/v1/systemone` endpoint. The [receptron/laya](https://github.com/receptron/laya) repository provided by the user is a Node implementation and does not include this HTTP server itself. In Settings, enter a **base URL reachable from the phone** without appending `/v1/systemone`. When authentication is needed, set `LAYA_API_KEY` on the server and enter the same key on the phone. HTTPS is recommended; plain HTTP is accepted only for private IPv4 / loopback addresses, and the Android network security policy must explicitly allow the actual host. Because the user's server address is not yet available, the current APK does not open HTTP hosts; global cleartext traffic must not be enabled. Plain HTTP transmits the question text and optional API key unencrypted, as noted on the settings page. `localhost` refers to the phone itself. Traditional Chinese questions should use the multilingual checkpoint. See [Laya model and self-hosting guide](https://huggingface.co/convaiinnovations/laya).

The base Laya checkpoint has limited zero-shot performance on the author's typed-decisions benchmark; validate Fast / Balanced / Quality classification with real questions before deployment and fine-tune if necessary. Without user examples, project tests can only verify the API contract, confidence thresholds, and fallback flow, not actual classification quality.

## Glasses Text Display

In phone **Settings → Glasses text display**, you can adjust the font size, system / monospace typeface, and the viewport width, height, horizontal position, and vertical position for all text on the glasses. The black-background preview reflects the unapplied draft. Changes are saved and sent only when you tap **Apply to glasses**; **Reset** only updates the draft and also requires tapping Apply. The app constrains the viewport within the screen with at least a 3% margin.

Settings are sent over the existing `SYSTEM_CONFIG` Bluetooth message and persisted on the glasses; when the glasses reconnect, the phone re-syncs the applied settings. The main text size maps directly to the configured `sp`, and other text scales proportionally. Answers are paginated using Compose `TextMeasurer` according to the main text's actual typeface, density, available width, and height after subtracting the status bar and footer hint, preserving every character and line break; if a single line is taller than the available height, the text is still preserved and made scrollable. The device selection screen is also within the configured viewport. The phone preview uses the app screen aspect ratio reported by the glasses, explicitly indicating when an illustrative ratio is used before metrics arrive. Currently `RG_glasses` reports 480×640; the actual optical field of view still requires wearing confirmation.

## Post-Review Fixes

- The status bar and footer hint use at most 25% and 20% of the viewport height respectively, scrolling when content is longer so a small viewport or large font size cannot crowd out the answer area.
- Decision HTTP calls have a total timeout budget of at most 3.5 seconds, falling back to the primary model on timeout, and do not follow redirects.
- Fast and Balanced require confidence of at least 0.5, and Quality requires at least 0.3; the three class probabilities must be valid, sum close to 1, and the chosen class probability must be at least 0.55 and be the maximum. This is a quality-first initial rule and does not yet prove real-world classification accuracy. See [TypeSafe confidence](https://docs.typesafe.ai/confidence).
- Every turn—including when using the primary model—re-seeds recent history from the database so switching across models never loses conversational context.
- When all services fail, the primary model's error is preserved: the phone displays a localized error and sends `AI_ERROR` to the glasses without saving the error as an AI answer or speaking it via TTS.
- The settings page discloses that question text is sent to Jev / Laya, even when the answering model runs on-device; the new settings are split into dedicated sub-pages. New copy and routing reasons support all 13 existing languages, with reasons stored as stable JSON codes and translated at display time.
- Language hints for GPT-Transcribe send ISO-639-1 codes as multipart `languages[]`, matching the array serialization of the official Python SDK; Whisper keeps singular `language`. See the [migration guide](https://developers.openai.com/cookbook/examples/migrating_from_whisper_to_gpt_transcribe) and [official SDK](https://github.com/openai/openai-python/blob/main/src/openai/_base_client.py). Recognition quality has not yet been verified with a live key and recording; hinting Chinese does not guarantee Traditional Chinese output.

## 2026-10-01: Existing-Provider Decisions and Preview Fixes

- Added Gemini / OpenAI to the decision settings, using native `generateContent` JSON schema and Responses strict JSON schema respectively. They reuse the existing provider API keys, and the decision model can be chosen independently; the three answer slots keep their existing configuration.
- The LLM returns only difficulty and `clear` / `uncertain`, without treating self-reported certainty as a calibrated Jev probability. The reason is stored with the `selected_llm` code and translated at display time.
- Only the current text question is sent—never photos, audio, or conversation history. This adds one paid provider API request; the enable switch remains under user control. Classification has no action output and cannot start the camera or audio recording.
- Decision failures, refusals, incomplete responses, malformed output, `uncertain` results, missing keys, or calls exceeding 3.5 seconds all fall back to the primary model. LLM classifications over 4000 characters are not truncated for classification; they fall back directly.
- Added `DISPLAY_METRICS` (0x33), through which the glasses app reports screen pixels, density, font scale, and SPP camera capability. The phone saves the most recent dimensions and uses an explicitly labeled illustrative 480×640 canvas until metrics arrive.
- The phone preview scales text and padding according to device pixels, using non-overlapping regions for status, main text, and hint. This previews the app layout and still does not replace wearing the glasses to confirm the optical field of view.
- SPP connection queries capabilities first; when the native glasses app supports the SPP camera, CXR BLE initialization is skipped, while older devices that do not report capabilities continue to use the SDK path. A late capability report cancels this app's SDK retries.
- `ProviderConnectivitySmokeTest` requires explicitly passing `verifyProviders=true` before running paid network tests; keys are never logged or moved off the phone.

Official format references: [Gemini GenerateContent](https://ai.google.dev/api/generate-content), [Gemini Authentication](https://ai.google.dev/gemini-api/docs/api-key), [OpenAI Structured Outputs](https://developers.openai.com/api/docs/guides/structured-outputs).
