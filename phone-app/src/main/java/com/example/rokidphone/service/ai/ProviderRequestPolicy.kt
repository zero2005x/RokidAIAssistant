package com.example.rokidphone.service.ai

import com.example.rokidphone.ai.catalog.ModelCapabilities
import com.example.rokidphone.data.AiProvider

/** Which JSON field carries the output-token limit. */
enum class TokenLimitField {
    MAX_TOKENS,
    MAX_COMPLETION_TOKENS,
    NONE
}

/** How an image is embedded in a chat request for this protocol. */
enum class ImageContentFormat {
    /** Chat Completions style: `{"type":"image_url","image_url":{"url":"data:..."}}`. */
    OPENAI_IMAGE_URL,

    /** Responses API style: `{"type":"input_image","image_url":"data:..."}`. */
    RESPONSES_INPUT_IMAGE,

    /** Provider/model does not accept images. */
    NONE
}

/**
 * Capability-driven request policy for one provider+model combination.
 *
 * Replaces model-ID string matching as the *cross-provider* mechanism: no
 * provider ever receives OpenAI-only parameters (`reasoning_effort`,
 * `verbosity`, `max_completion_tokens`) unless this policy says so.
 *
 * The OpenAI family rules remain ID-based because OpenAI's Models API does
 * not publish per-model parameter constraints; they are scoped strictly to
 * [AiProvider.OPENAI] and verified against OpenAI docs (2026-08-02).
 */
data class ProviderRequestPolicy(
    val tokenLimitField: TokenLimitField = TokenLimitField.MAX_TOKENS,
    /** temperature / top_p are accepted. */
    val allowSampling: Boolean = true,
    /** frequency_penalty / presence_penalty are accepted. */
    val allowPenalties: Boolean = true,
    val allowStop: Boolean = true,
    /** OpenAI o-series / GPT-5 reasoning effort parameter. */
    val supportsReasoningEffort: Boolean = false,
    /** OpenAI GPT-5 verbosity parameter. */
    val supportsVerbosity: Boolean = false,
    val streaming: Boolean = true,
    /** Whether the provider supports stream_options (e.g. {"include_usage": true}). */
    val supportsStreamOptions: Boolean = false,
    val imageContentFormat: ImageContentFormat = ImageContentFormat.OPENAI_IMAGE_URL,
    /** Provider exposes a working /audio/transcriptions endpoint. */
    val supportsAudioTranscriptions: Boolean = false,
    /** Transcription model to use on that endpoint (STT is decoupled from chat). */
    val transcriptionModel: String? = null
)

object ProviderRequestPolicies {

    /**
     * Resolve the effective policy.
     *
     * @param capabilities model-level capabilities from the catalog
     * @param reasoningEffort user override ("none" unlocks sampling on GPT-5)
     */
    fun resolve(
        provider: AiProvider,
        modelId: String,
        capabilities: ModelCapabilities,
        reasoningEffort: String? = null
    ): ProviderRequestPolicy = when (provider) {
        AiProvider.OPENAI -> openAiPolicy(modelId, reasoningEffort)
        AiProvider.GROQ -> ProviderRequestPolicy(
            tokenLimitField = TokenLimitField.MAX_COMPLETION_TOKENS,
            allowPenalties = false,
            supportsStreamOptions = true,
            supportsAudioTranscriptions = true,
            transcriptionModel = "whisper-large-v3-turbo",
            imageContentFormat = if (capabilities.imageInput) ImageContentFormat.OPENAI_IMAGE_URL else ImageContentFormat.NONE
        )
        AiProvider.XAI -> xaiPolicy(modelId, capabilities)
        AiProvider.DEEPSEEK -> ProviderRequestPolicy(
            supportsStreamOptions = true,
            // Reasoning models reject sampling params and return reasoning_content.
            allowSampling = !capabilities.reasoning,
            allowPenalties = !capabilities.reasoning,
            imageContentFormat = if (capabilities.imageInput) ImageContentFormat.OPENAI_IMAGE_URL else ImageContentFormat.NONE
        )
        AiProvider.PERPLEXITY -> ProviderRequestPolicy(
            // Sonar documents temperature/top_p but not penalty parameters.
            allowPenalties = false,
            imageContentFormat = if (capabilities.imageInput) ImageContentFormat.OPENAI_IMAGE_URL else ImageContentFormat.NONE
        )
        AiProvider.MOONSHOT -> ProviderRequestPolicy(
            // Thinking Kimi models require fixed sampling values; use server defaults.
            allowSampling = !modelId.startsWith("kimi-k"),
            allowPenalties = false,
            imageContentFormat = if (capabilities.imageInput) ImageContentFormat.OPENAI_IMAGE_URL else ImageContentFormat.NONE
        )
        AiProvider.ZHIPU -> ProviderRequestPolicy(
            // Zhipu does not accept frequency_penalty / presence_penalty.
            allowPenalties = false,
            imageContentFormat = if (capabilities.imageInput) ImageContentFormat.OPENAI_IMAGE_URL else ImageContentFormat.NONE
        )
        AiProvider.ALIBABA -> ProviderRequestPolicy(
            supportsStreamOptions = true,
            imageContentFormat = if (capabilities.imageInput) ImageContentFormat.OPENAI_IMAGE_URL else ImageContentFormat.NONE
        )
        AiProvider.MISTRAL, AiProvider.BAIDU, AiProvider.CUSTOM -> ProviderRequestPolicy(
            imageContentFormat = if (capabilities.imageInput) ImageContentFormat.OPENAI_IMAGE_URL else ImageContentFormat.NONE
        )
        AiProvider.LOCAL_GEMMA -> ProviderRequestPolicy(
            // On-device text model: no network params, no penalties, text-only.
            allowPenalties = false,
            streaming = capabilities.streaming,
            imageContentFormat = ImageContentFormat.NONE,
            supportsAudioTranscriptions = false
        )
        else -> ProviderRequestPolicy(imageContentFormat = ImageContentFormat.NONE)
    }

    // ==================== OpenAI family rules (OpenAI provider only) ====================

    /** o-series (o3, o4-mini...) — reasoning-only, classic chat shape. */
    internal fun isOpenAiOSeries(modelId: String): Boolean =
        modelId.matches(Regex("^o\\d.*"))

    internal fun isOpenAiGpt5Family(modelId: String): Boolean =
        modelId.startsWith("gpt-5") || modelId.startsWith("gpt-6")

    /** Verbosity was introduced with GPT-5. */
    internal fun openAiSupportsVerbosity(modelId: String): Boolean = isOpenAiGpt5Family(modelId)

    /** Omit unspecified or unsupported effort instead of forcing `minimal` on every model. */
    fun openAiReasoningEffort(modelId: String, requested: String?): String? {
        if (requested == null) return null
        val originalGpt5 = modelId == "gpt-5" ||
            modelId.startsWith("gpt-5-mini") || modelId.startsWith("gpt-5-nano") ||
            modelId.startsWith("gpt-5-2025")
        val allowed = when {
            modelId.startsWith("gpt-6-astra") -> setOf("low", "medium", "high", "xhigh", "max")
            modelId.startsWith("gpt-6-sol") || modelId.startsWith("gpt-6-luna") ->
                setOf("none", "low", "medium", "high", "xhigh", "max")
            modelId == "gpt-5-pro" || modelId.startsWith("gpt-5-pro-") -> setOf("high")
            modelId.contains("-pro") && isOpenAiGpt5Family(modelId) -> setOf("medium", "high", "xhigh")
            isOpenAiOSeries(modelId) -> setOf("low", "medium", "high")
            originalGpt5 -> setOf("minimal", "low", "medium", "high")
            modelId.startsWith("gpt-5.1") -> setOf("none", "low", "medium", "high")
            isOpenAiGpt5Family(modelId) -> setOf("none", "low", "medium", "high", "xhigh")
            else -> emptySet()
        }
        return requested.takeIf { it in allowed }
    }

    /** Models verified as Responses-API-first on the OpenAI platform (2026-08-02). */
    fun openAiPrefersResponses(modelId: String): Boolean =
        modelId.startsWith("gpt-5.6") || modelId.startsWith("gpt-6")

    private fun openAiPolicy(modelId: String, reasoningEffort: String?): ProviderRequestPolicy {
        val oSeries = isOpenAiOSeries(modelId)
        val gpt5 = isOpenAiGpt5Family(modelId)
        val reasoningModel = oSeries || gpt5
        val effectiveEffort = openAiReasoningEffort(modelId, reasoningEffort)
        val samplingLocked = oSeries || (gpt5 && effectiveEffort != "none")
        return ProviderRequestPolicy(
            tokenLimitField = if (reasoningModel) TokenLimitField.MAX_COMPLETION_TOKENS else TokenLimitField.MAX_TOKENS,
            allowSampling = !samplingLocked,
            allowPenalties = !samplingLocked,
            supportsReasoningEffort = reasoningModel,
            supportsVerbosity = openAiSupportsVerbosity(modelId),
            supportsStreamOptions = true,
            supportsAudioTranscriptions = true,
            transcriptionModel = "gpt-transcribe",
            imageContentFormat = ImageContentFormat.OPENAI_IMAGE_URL
        )
    }

    // ==================== xAI family rules ====================

    /**
     * Grok 4 (pure reasoning) rejects penalties and stop. `grok-4.x` models
     * (4.1-fast, 4.20, 4.5) are regular chat models.
     */
    internal fun isXaiReasoningOnly(modelId: String): Boolean =
        modelId == "grok-4" || (modelId.startsWith("grok-4-") && !modelId.startsWith("grok-4."))

    private fun xaiPolicy(modelId: String, capabilities: ModelCapabilities): ProviderRequestPolicy {
        val reasoningOnly = isXaiReasoningOnly(modelId)
        return ProviderRequestPolicy(
            allowSampling = !reasoningOnly,
            allowPenalties = !reasoningOnly,
            allowStop = !reasoningOnly,
            imageContentFormat = if (capabilities.imageInput) ImageContentFormat.OPENAI_IMAGE_URL else ImageContentFormat.NONE
        )
    }
}
