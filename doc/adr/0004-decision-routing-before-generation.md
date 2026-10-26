# ADR 0004: Decision routing happens before generation, outside the AI session (3.5 s budget, fallback to primary)

- **Status:** Accepted
- **Date:** 2026-10-08

## Context

Questions asked through the glasses or phone span simple factual queries (`fast`), multi-step explanations (`balanced`), and complex or ambiguous reasoning (`quality`). Sending every turn to a large frontier model increases latency and cost, while sending every turn to a small fast model hurts answer quality on difficult questions.

However, routing inside the conversational prompt couples routing to a single vendor and risks prompt injection or tool side effects, whereas an external classifier adds a network hop before generation starts. If the decision call is slow, returns uncalibrated or malformed output, or routes to an unconfigured provider slot, the wearer is left waiting on the glasses.

## Decision

We perform optional difficulty-based model routing in `DecisionRouter` **before** answer generation and **outside** the conversational AI session:

1. **Scope and input isolation:**
   - Routing applies only to text chat and transcribed speech (`PhoneAIService`, `ConversationViewModel`). Photo analysis and `GEMINI_LIVE` use their dedicated pipelines.
   - Only the current user question text (up to `4,000` characters) is sent to the decision backend—never photos, audio, or conversation history. The decision step has no tool or action outputs and cannot trigger camera capture or recording.
2. **Supported decision backends (`DecisionBackend`):**
   - **Existing LLM credentials (`GEMINI`, `OPENAI`):** `LlmDecisionClient` calls Gemini `generateContent` (`responseJsonSchema`) or OpenAI `POST /v1/responses` (`strict: true` JSON schema, `store: false`) to return `{ "tier": "fast" | "balanced" | "quality", "certainty": "clear" | "uncertain" }`. Because LLM self-reported confidence is not a calibrated probability, only an exact two-field response with `certainty == "clear"` is accepted (`reason_code = "selected_llm"`), and no percentage is shown in the UI. Questions exceeding `4,000` characters are rejected rather than truncated.
   - **System One endpoints (`JEV`, `LAYA`):** `DecisionRouter` posts a `choice` question to `https://api.typesafe.ai/v1/systemone` (`jev-latest`) or a self-hosted `<base>/v1/systemone` URL (restricted by `isAllowedLayaUrl` to HTTPS or loopback/RFC-1918 private IPv4). A decision is accepted only when the three tier probabilities are finite, in `[0, 1]`, sum to `1.0 ± 0.02`, the chosen tier leads with probability `>= 0.55`, and calibrated confidence is `>= 0.50` for `fast`/`balanced` or `>= 0.30` for `quality` (favoring quality over speed when uncertain).
3. **Hard 3.5 s budget and no redirects:**
   - `DecisionRouter` enforces a `3,500 ms` total call timeout (`3 s` connect/read) with HTTP and SSL redirects disabled (`followRedirects(false)`, `followSslRedirects(false)`).
4. **Deterministic fallback and cross-provider history seeding:**
   - If routing is disabled, times out, errors, returns `uncertain`, maps to an empty or unconfigured slot (`fastRoutingModel`, `balancedRoutingModel`, `qualityRoutingModel`), or if the chosen slot's generation call fails, `DecisionRouter` falls back to the user's primary model (`RoutingModel(settings.aiProvider, settings.getCurrentModelId())`). A failed decision backend never silently switches to another decision backend.
   - Because consecutive turns in one conversation may be answered by different providers, `DecisionRouter` seeds the last 10 text turns from Room (`BaseAiService.seedHistory`) before each generation call.
5. **On-device routing metrics (`RoutingMetricsRecorder`):**
   - Each answered question records one local `RoutingMetricEntity` row (`decision_latency_ms`, `tier`, `reason_code`, `provider`, `model_id`, `total_ms`, `answer_chars`, `glasses_page_count`, `asked_again_within_30s`, `time_to_first_text_ms`), pruned after 60 days and exportable only by the user as CSV.

## Consequences

- Users can assign different providers and models to `fast`, `balanced`, and `quality` slots while retaining multi-turn context and automatic fallback to their primary model on any failure.
- When routing is enabled, each question incurs a pre-generation classification request bounded at `3.5 s`. After collecting real-usage data in `routing_metrics`, we will evaluate median/p90 decision latency, fallback share, and 30-second re-ask rates to decide whether pre-generation routing stays as is, skips short utterances, or defaults to off.
- **Experimental status:** TypeSafe `Jev`, self-hosted `Laya`, and the multi-step `Agents` design (see [`doc/DECISION_ROUTING_AND_DISPLAY.md`](../DECISION_ROUTING_AND_DISPLAY.md) and [`doc/SYSTEM_ONE_AGENTS_RESEARCH.md`](../SYSTEM_ONE_AGENTS_RESEARCH.md)) are **experimental** and receive no further feature work unless local routing metrics justify it.
