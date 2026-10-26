# ADR 0007: Streaming to the glasses

- **Status:** Proposed
- **Date:** 2026-10-08

## Context

In the current voice and recording flow (`PhoneAIService`), the phone waits for the AI provider to finish generating the entire answer before cleaning markdown, sending `AI_RESPONSE_TEXT` (`0x21`) over Bluetooth SPP, and starting TTS playback. Even when individual providers support SSE or WebSocket streaming (`AiServiceProvider.streamChat`), the wearer sees only a processing spinner on the glasses until the last token arrives—often several seconds after speech recognition completes.

Streaming directly to a head-mounted display introduces three wearable-specific challenges:

1. **Backward compatibility:** Phone and glasses APKs update independently ([ADR 0002](0002-shared-binary-protocol-and-capability-handshake.md)); an older glasses build does not understand incremental delta messages.
2. **Reading stability:** Re-paginating and auto-advancing pages while text streams in would yank the text out from under the wearer's eyes mid-sentence.
3. **Speech latency and error safety:** Waiting for the full response delays TTS, but speaking raw partial tokens or speaking a stream that later aborts violates [ADR 0006](0006-errors-never-shown-or-spoken-as-answers.md).

## Decision

We will stream answer text deltas from the phone to the glasses and speak completed sentences incrementally, gated behind the capability handshake:

1. **Protocol extension behind the capability handshake:**
   - Introduce streaming message types in the AI range (`0x20–0x2F` in `MessageType`) for incremental text deltas and an end-of-answer marker carrying the final cleaned text.
   - The glasses advertise streaming support in the connection-time `DISPLAY_METRICS` (`0x33`) capability handshake ([ADR 0002](0002-shared-binary-protocol-and-capability-handshake.md)).
   - When connected to glasses that do not advertise streaming support, `PhoneAIService` falls back to sending the complete answer once via `AI_RESPONSE_TEXT` (`0x21`).
   - Pre-generation decision routing ([ADR 0004](0004-decision-routing-before-generation.md)) still runs before generation begins; `PhoneAIService` invokes `AiServiceProvider.streamChat` once the target model is selected.
2. **Page-1 hold and flicker-free measured pagination on the glasses:**
   - As deltas arrive, `GlassesViewModel` appends them and re-paginates using `MeasuredTextPaginator` ([ADR 0005](0005-layout-and-pagination-measured-on-glasses.md)).
   - **Page 1 stays on screen** while text arrives—there is no automatic page advance. When the streamed text overflows page 1, the glasses display a `"more ▸"` indicator and allow the user to swipe to subsequent pages at their own pace.
   - To avoid visual flicker, page 1 only re-renders when its own measured slice changes.
3. **Sentence-by-sentence TTS:**
   - The phone splits incoming stream text on sentence boundaries (including CJK punctuation such as `。！？`), applies `cleanMarkdown` per completed sentence, and queues each finished sentence to `TextToSpeechService`.
   - Starting a new question or cancelling stops the active TTS queue immediately.
   - If a stream terminates with an error (`AiStreamEvent.Error`), no partial answer is committed to history, TTS stops, and the phone sends `AI_ERROR` (`0x2F`) ([ADR 0006](0006-errors-never-shown-or-spoken-as-answers.md)).
4. **Latency target and on-device measurement:**
   - Target **time to first text on the glasses is ≈ 2.5 s** from the end of speech recording.
   - The glasses report when the first text delta is rendered so the phone can populate `timeToFirstTextMs` in `RoutingMetricEntity` (`routing_metrics`), separating decision latency, model time-to-first-token, and Bluetooth transfer time.

## Consequences

- Wearers begin reading page 1 and hearing the first completed sentence as soon as generation starts, without waiting for long answers to finish.
- Holding page 1 prevents disorienting auto-scroll on the waveguide display while still letting users swipe forward as pages fill.
- Older glasses builds continue working unchanged via the whole-answer `AI_RESPONSE_TEXT` fallback.
- Once streaming ships in `v1.3.0`, median `time_to_first_text_ms` after two weeks of real use will be recorded in this ADR and the status updated to **Accepted**.
