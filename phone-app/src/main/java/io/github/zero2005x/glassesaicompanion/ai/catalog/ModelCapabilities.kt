package io.github.zero2005x.glassesaicompanion.ai.catalog

/**
 * Model-level capability flags.
 *
 * Capabilities MUST come from (in priority order):
 *  1. the provider's Models API metadata (live),
 *  2. the verified fallback capability map in [FallbackModelCatalog],
 *  3. a conservative provider default (text-only),
 *  4. a manual user override (Custom provider).
 *
 * They are never inferred from substrings in the model ID.
 *
 * Construction and `copy` MUST use named arguments: with this many Boolean
 * flags, a single mis-ordered positional argument silently flips a capability
 * with no compiler error.
 */
data class ModelCapabilities(
    val textInput: Boolean = true,
    val textOutput: Boolean = true,
    val imageInput: Boolean = false,
    val audioInput: Boolean = false,
    val audioOutput: Boolean = false,
    val streaming: Boolean = false,
    val toolCalling: Boolean = false,
    val structuredOutput: Boolean = false,
    val reasoning: Boolean = false,
    val realtime: Boolean = false,
    val transcription: Boolean = false,
    val maxContextTokens: Long? = null,
    val maxOutputTokens: Long? = null
) {
    init {
        // null means "unknown"; 0/negative is a parse artefact, not a real limit.
        require(maxContextTokens == null || maxContextTokens > 0) {
            "maxContextTokens must be positive or null (unknown): $maxContextTokens"
        }
        require(maxOutputTokens == null || maxOutputTokens > 0) {
            "maxOutputTokens must be positive or null (unknown): $maxOutputTokens"
        }
    }

    companion object {
        /** Conservative default: plain text chat only. */
        val TEXT_ONLY = ModelCapabilities()

        fun textChat(streaming: Boolean = false, tools: Boolean = false) = ModelCapabilities(
            streaming = streaming,
            toolCalling = tools
        )
    }
}

/** Lifecycle state of a model as published by the provider. */
enum class ModelStatus {
    STABLE,
    PREVIEW,
    DEPRECATED,
    LEGACY,

    /** Provider reported a status this app does not recognise. */
    UNKNOWN
}

/** Where the model catalog currently in use came from. */
enum class CatalogSource {
    /** Fetched from the provider API during this refresh. */
    LIVE,

    /** Loaded from the on-device cache of a previous successful fetch. */
    CACHED,

    /** Static, verified-at-build-time fallback list. */
    FALLBACK,

    /** Manually entered by the user. */
    MANUAL
}
