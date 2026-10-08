package io.github.zero2005x.glassesaicompanion.service.ai

import io.github.zero2005x.glassesaicompanion.ai.catalog.ProviderErrorKind

/**
 * Unified streaming event emitted by every provider that supports streaming.
 */
sealed interface AiStreamEvent {
    /** Incremental assistant text. */
    data class TextDelta(val text: String) : AiStreamEvent

    /**
     * Incremental tool-call fragment (arguments streamed as JSON text).
     *
     * Accumulation invariant: fragments with the same [index] belong to one
     * tool call. [id]/[name] are present on the first fragment of a call and
     * null on subsequent argument-only fragments; [argumentsDelta] must be
     * concatenated per [index].
     */
    data class ToolCallDelta(
        /** Provider-supplied slot index; fragments with the same index belong to one tool call. */
        val index: Int,
        /** Present on the first fragment of a call, null on subsequent argument-only fragments. */
        val id: String?,
        val name: String?,
        val argumentsDelta: String?
    ) : AiStreamEvent

    /** A citation/source reference attached to the answer (e.g. Perplexity Sonar). */
    data class Citation(val url: String, val title: String? = null) : AiStreamEvent

    /**
     * Token usage report. Informational only — the authoritative totals for a
     * stream are always [Completed.usage]; never aggregate these events
     * together with it.
     */
    data class Usage(val inputTokens: Long?, val outputTokens: Long?) : AiStreamEvent

    /** The model is reasoning; the reasoning text itself is never surfaced. */
    data object Thinking : AiStreamEvent

    /** Stream finished normally. [fullText] is the concatenation of all TextDelta text. */
    data class Completed(val fullText: String, val usage: Usage? = null) : AiStreamEvent

    /** Stream failed. */
    data class Error(
        val kind: ProviderErrorKind,
        val message: String,
        val httpStatus: Int? = null
    ) : AiStreamEvent
}
