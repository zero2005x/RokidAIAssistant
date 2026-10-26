# ADR 0003: Provider abstraction with a data-driven catalog; provider breadth frozen

- **Status:** Accepted
- **Date:** 2026-10-08

## Context

`:phone-app` supports 16 AI provider options (`GEMINI`, `OPENAI`, `ANTHROPIC`, `DEEPSEEK`, `GROQ`, `XAI`, `ALIBABA`, `ZHIPU`, `BAIDU`, `PERPLEXITY`, `MOONSHOT`, `MISTRAL`, `GEMINI_LIVE`, `ANYTHINGLLM`, `LOCAL_GEMMA`, `CUSTOM`) and 18 speech-to-text (`SttProvider`) options.

When model IDs, endpoint URLs, and request parameter rules were scattered across UI screens and individual service classes, routine vendor changes—such as model deprecations, OpenAI's shift from Chat Completions to the Responses API, or reasoning models rejecting custom `temperature`/`top_p`/penalty fields—caused regressions and stale UI pickers. Meanwhile, continuing to add more vendor-specific providers increases maintenance and test surface without advancing the core wearable reference architecture.

## Decision

We standardize provider execution behind a single service contract and a data-driven model catalog, and freeze the set of built-in providers:

1. **Unified provider contract (`AiServiceProvider` & `ChatErrorSource`):**
   - Callers (`PhoneAIService`, `DecisionRouter`, `EnhancedAIService`, `ConversationViewModel`) interact with providers exclusively through `AiServiceProvider` (`transcribe`, `transcribeAudioFile`, `chat`, `streamChat`, `analyzeImage`, `clearHistory`) and `AiServiceFactory`.
   - Protocol-specific differences (headers, OpenAI Responses vs. Chat Completions, Anthropic `messages`, Gemini `generateContent`, parameter filtering for reasoning models) are encapsulated in the protocol adapters and `ProviderRequestPolicy`.
2. **Data-driven registry and four-tier catalog (`io.github.zero2005x.glassesaicompanion.ai.catalog`):**
   - `ProviderRegistry` defines a `ProviderDescriptor` for every `AiProvider` (default base URL, `ApiProtocol`, `CatalogFormat`, models path, credential requirement, and custom-URL policy), verified for completeness at startup (`ProviderRegistry.isComplete()`).
   - `ModelCatalogRepository` resolves selectable models through a four-tier hierarchy:
     1. **Live remote catalog** fetched from the provider's `/models` endpoint when an API key is present.
     2. **On-device cache** (`ModelCatalogCache`) with a 24-hour TTL (and stale-cache fallback on network error).
     3. **Verified static fallback catalog** (`FallbackModelCatalog`) for offline use and providers without a public models endpoint (`PERPLEXITY`, `GEMINI_LIVE`, `ANYTHINGLLM`, `LOCAL_GEMMA`).
     4. **Manual model ID entry** (`CatalogSource.MANUAL`), allowing users to type any model ID. Persisted user selections are never deleted when a remote catalog changes; `selectionWarning` surfaces a non-destructive notice instead.
3. **Provider breadth frozen:**
   - The list of 16 `AiProvider` and 18 `SttProvider` entries is frozen (documented in `CONTRIBUTING.md`). Additional OpenAI-compatible or self-hosted endpoints are supported through `AiProvider.CUSTOM` or `AiProvider.ANYTHINGLLM`.

## Consequences

- Model refreshes, deprecations, and parameter policies are updated centrally in `ai/catalog/` and `ProviderRequestPolicy`, covered by offline `MockWebServer` contract tests (`ProviderApiContractTest`).
- The app starts and renders model pickers offline without requiring an API key, while still discovering new server-side models automatically once a key is configured.
- Engineering effort stays focused on wearable UX (routing, low-latency streaming, glasses layout, and reliability) rather than maintaining an ever-growing list of vendor adapters.
