# ADR 0008: Surface routing

- **Status:** Proposed
- **Date:** 2026-10-08

## Context

Not every answer belongs on a small head-mounted waveguide display. A quick voice question asked on the glasses suits a concise 1–2 page card on the glasses, a question typed in the phone chat screen is already being read on the phone, an audio-only interaction when the wearer is walking may only need voice playback, and a long multi-page explanation becomes tiring to swipe through on the glasses.

Letting an LLM freely decide where and how to deliver the answer risks unpredictable UI behavior, while dumping unbounded multi-page essays onto the glasses forces excessive touchpad swiping.

## Decision

We will choose the output **surface** before generation using deterministic rules (with optional bounded classifier refinement) and hand off overflowing answers to the phone during streaming:

1. **Three output surfaces:**
   - **Glasses (`glasses`):** Short visual answer rendered on the glasses (with optional TTS according to user settings).
   - **Phone handoff (`phone_handoff`):** Full answer rendered in the phone conversation view (with a phone notification if locked), while the glasses show a brief `"sent to phone"` note.
   - **Voice only (`voice_only`):** Spoken through TTS without occupying the glasses text viewport. (`silent` is out of scope.)
2. **Deterministic rules first, output contract to the model:**
   - A pure rule function selects the allowed surface(s) and a target length budget before generation based on deterministic signals: input source (glasses voice vs. phone chat vs. photo capture), Bluetooth glasses connection state, and TTS configuration.
   - The selected surface and length budget are injected into the system prompt as an output contract so the model tailors its conciseness to the destination surface, while the app retains deterministic control over actual delivery.
   - The chosen surface is persisted with each message and recorded in `routing_metrics`.
3. **Overflow handoff at page 3:**
   - When an answer displayed on the glasses (either during streaming under [ADR 0007](0007-streaming-to-the-glasses.md) or via the whole-answer fallback) reaches the page threshold (default **3 pages**, configurable, measured on the glasses via `MeasuredTextPaginator` per [ADR 0005](0005-layout-and-pagination-measured-on-glasses.md)), the glasses stop adding further pages and display a `"continued on phone"` notice.
   - The complete, untruncated answer remains available in the phone conversation history (and triggers a phone notification when the phone screen is locked).
4. **Optional classifier refinement (only when decision routing is enabled):**
   - After deterministic surface rules ship, `DecisionRouter` and `LlmDecisionClient` ([ADR 0004](0004-decision-routing-before-generation.md)) may optionally ask for a `surface` recommendation alongside `difficulty` within the same `3.5 s` budget.
   - Deterministic rules remain the authority: the classifier may only choose among surfaces permitted by the rules, and any timeout, uncertainty, or schema failure falls back to the deterministic rule result.

## Consequences

- Wearers get glanceable answers on the glasses for quick voice/photo queries and seamless continuation on the phone when an answer exceeds 3 pages.
- Surface selection is predictable, testable as a pure function, and never depends on an LLM call succeeding.
- Prompting the model with an explicit surface and length budget reduces unnecessary overflow, while the hard 3-page cutoff on the glasses guarantees the display stays ergonomic even when a model ignores length guidance.
