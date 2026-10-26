# ADR 0006: Errors are never shown or spoken as answers

- **Status:** Accepted
- **Date:** 2026-10-08

## Context

Several provider services (`BaseAiService` subclasses, `BaiduService`, `LocalGemmaService`) historically caught network, authentication, or missing-model failures and returned a human-readable fallback string from `chat()` or `analyzeImage()` (for example, `"An error occurred while processing, please try again"` or `"Error: ..."`).

When callers treated any non-empty string return value as a valid assistant response, three bugs occurred:

1. **Broken routing fallback:** `DecisionRouter` treated a failed routed slot as a successful reply instead of falling back to the user's primary model.
2. **History pollution:** `ConversationRepository` and `BaseAiService.addToHistory` saved the error message as an `ASSISTANT` turn, feeding error text into subsequent multi-turn prompts.
3. **Wearable UX failure:** `PhoneAIService` sent the error string as `AI_RESPONSE_TEXT` (paginating it as an AI answer on the glasses) and read the error aloud through `TextToSpeechService.speak`.

## Decision

We strictly separate error state from assistant answer text across all services, routers, and UI/glasses channels:

1. **Out-of-band error tracking (`ChatErrorSource` & stream events):**
   - `BaseAiService` and standalone providers (`BaiduService`, `LocalGemmaService`) implement `ChatErrorSource`, recording sanitized failure details in `lastChatError` (`ProviderApiException.sanitize` strips any credential or key material).
   - Streaming adapters (`AiServiceProvider.streamChat`) emit `AiStreamEvent.Error` on HTTP, SSE, or incomplete-response failures (including OpenAI `response.incomplete` and `error` events) and never emit `AiStreamEvent.Completed` or append to `conversationHistory` when a stream fails.
2. **Router validation and primary fallback (`DecisionRouter`):**
   - `DecisionRouter.tryChat` inspects `ChatErrorSource.lastChatError`, blank responses, and `"Error:"` prefixes. If a routed slot fails, `DecisionRouter` retries with the primary model; if the primary model also fails, `RoutedReply.error` is set with `code = "failed"`.
   - `PhoneAIService.analyzePhotoWithAI` applies the same `ChatErrorSource` and non-blank check before accepting an image analysis result.
3. **Dedicated error channel, no persistence, and no TTS:**
   - Whenever STT transcription, decision routing/chat, or photo analysis fails, the phone emits `MessageType.AI_ERROR` (`0x2F`) to the glasses and surfaces a localized error banner on the phone (`RoutingReason.failure`).
   - Failed turns are never saved as assistant messages in `ConversationRepository` or `BaseAiService.conversationHistory`.
   - `TextToSpeechService.speak` is never called on an error path.

## Consequences

- Glasses wearers see a clear error indicator (`AI_ERROR`) and retry prompt instead of paging through or hearing TTS read out an API error message.
- `DecisionRouter` reliably falls back from a failing routed provider to the primary model, and multi-turn conversation history stays free of error strings.
- Existing unit tests that check the return string of `chat()` on legacy services continue to pass, while production callers rely on `ChatErrorSource.lastChatError`, `RoutedReply.error`, and `AiStreamEvent.Error`.
