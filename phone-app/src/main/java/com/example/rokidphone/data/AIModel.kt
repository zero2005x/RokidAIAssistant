package com.example.rokidphone.data

/**
 * AI Provider type for model categorization
 */
enum class Provider {
    GEMINI, CLAUDE, OPENAI, GROK, DEEPSEEK, QWEN, ZHIPU, MOONSHOT, PERPLEXITY, GROQ, MISTRAL
}

/**
 * Type-safe AI Model registry with provider-specific subclasses.
 * Each model entry includes metadata for display, filtering, and runtime decisions.
 *
 * @property modelId The exact string ID used in API requests
 * @property displayName Human-readable name for UI display
 * @property contextWindow Approximate maximum context window in tokens
 * @property provider The AI provider this model belongs to
 * @property isPreview Whether this model is in preview/experimental state
 * @property isDeprecated Whether this model is deprecated and should not be used
 */
sealed class AIModel(
    val modelId: String,
    val displayName: String,
    val contextWindow: Long,
    val provider: Provider,
    val isPreview: Boolean = false,
    val isDeprecated: Boolean = false
) {
    // ==================== Google Gemini ====================

    sealed class Gemini(
        modelId: String,
        displayName: String,
        contextWindow: Long,
        isPreview: Boolean = false,
        isDeprecated: Boolean = false
    ) : AIModel(modelId, displayName, contextWindow, Provider.GEMINI, isPreview, isDeprecated) {

        data object Gemini31Flash : Gemini(
            modelId = "gemini-3.1-flash",
            displayName = "Gemini 3.1 Flash",
            contextWindow = 1_000_000L
        )

        data object Gemini31FlashLite : Gemini(
            modelId = "gemini-3.1-flash-lite",
            displayName = "Gemini 3.1 Flash-Lite",
            contextWindow = 1_000_000L
        )

        data object Gemini31DeepThink : Gemini(
            modelId = "gemini-3.1-pro-deep-think",
            displayName = "Gemini 3.1 Pro Deep Think",
            contextWindow = 1_048_576L,
            isPreview = true
        )

        data object Gemini31ProPreview : Gemini(
            modelId = "gemini-3.1-pro-preview",
            displayName = "Gemini 3.1 Pro (Preview)",
            contextWindow = 1_048_576L,
            isPreview = true
        )

        data object Gemini3FlashPreview : Gemini(
            modelId = "gemini-3-flash-preview",
            displayName = "Gemini 3 Flash (Preview)",
            contextWindow = -1L, // TBD
            isPreview = true
        )

        data object Gemini25Pro : Gemini(
            modelId = "gemini-2.5-pro",
            displayName = "Gemini 2.5 Pro",
            contextWindow = 1_000_000L
        )

        data object Gemini25Flash : Gemini(
            modelId = "gemini-2.5-flash",
            displayName = "Gemini 2.5 Flash",
            contextWindow = 1_000_000L
        )

        data object Gemini25FlashLite : Gemini(
            modelId = "gemini-2.5-flash-lite",
            displayName = "Gemini 2.5 Flash-Lite",
            contextWindow = 1_000_000L
        )

        data object GeminiNano : Gemini(
            modelId = "gemini-nano",
            displayName = "Gemini Nano (On-Device)",
            contextWindow = 0L // Device-limited
        )

        companion object {
            fun all(): List<Gemini> = listOf(
                Gemini31Flash,
                Gemini31FlashLite,
                Gemini31DeepThink,
                Gemini31ProPreview,
                Gemini3FlashPreview,
                Gemini25Pro,
                Gemini25Flash,
                Gemini25FlashLite,
                GeminiNano
            )
        }
    }

    // ==================== Anthropic Claude ====================

    sealed class Claude(
        modelId: String,
        displayName: String,
        contextWindow: Long,
        isPreview: Boolean = false,
        isDeprecated: Boolean = false
    ) : AIModel(modelId, displayName, contextWindow, Provider.CLAUDE, isPreview, isDeprecated) {

        data object Opus47 : Claude(
            modelId = "claude-opus-4-7",
            displayName = "Claude Opus 4.7",
            contextWindow = 200_000L // 1M via beta header
        )

        // TODO: Verify exact API model ID for Claude Sonnet 4.7.
        data object Sonnet47 : Claude(
            modelId = "claude-sonnet-4-7",
            displayName = "Claude Sonnet 4.7 (TODO: verify ID)",
            contextWindow = 200_000L,
            isPreview = true
        )

        // TODO: Verify exact API model ID for legacy Claude Opus 4 line.
        data object Opus4 : Claude(
            modelId = "claude-opus-4",
            displayName = "Claude Opus 4 (TODO: verify ID)",
            contextWindow = 200_000L,
            isPreview = true
        )

        // TODO: Verify exact API model ID for legacy Claude Sonnet 4 line.
        data object Sonnet4 : Claude(
            modelId = "claude-sonnet-4",
            displayName = "Claude Sonnet 4 (TODO: verify ID)",
            contextWindow = 200_000L,
            isPreview = true
        )

        // TODO: Verify exact API model ID for Claude 3.7 Sonnet.
        data object Sonnet37 : Claude(
            modelId = "claude-3-7-sonnet-latest",
            displayName = "Claude 3.7 Sonnet (TODO: verify ID)",
            contextWindow = 200_000L
        )

        data object Opus46 : Claude(
            modelId = "claude-opus-4-6",
            displayName = "Claude Opus 4.6",
            contextWindow = 200_000L // 1M via beta header
        )

        data object Sonnet46 : Claude(
            modelId = "claude-sonnet-4-6",
            displayName = "Claude Sonnet 4.6",
            contextWindow = 200_000L // 1M via beta header
        )

        data object Haiku45 : Claude(
            modelId = "claude-haiku-4-5-20251001",
            displayName = "Claude Haiku 4.5",
            contextWindow = 200_000L
        )

        companion object {
            fun all(): List<Claude> = listOf(Sonnet47, Opus47, Opus46, Sonnet46, Haiku45, Opus4, Sonnet4, Sonnet37)

            /**
             * Whether this model supports 1M context via beta header.
             */
            fun supports1MContext(modelId: String): Boolean =
                modelId == Opus47.modelId ||
                modelId == Opus46.modelId ||
                modelId == Sonnet46.modelId
        }
    }

    // ==================== OpenAI ====================

    sealed class OpenAI(
        modelId: String,
        displayName: String,
        contextWindow: Long,
        isPreview: Boolean = false,
        isDeprecated: Boolean = false
    ) : AIModel(modelId, displayName, contextWindow, Provider.OPENAI, isPreview, isDeprecated) {

        data object Gpt54 : OpenAI(
            modelId = "gpt-5.4",
            displayName = "GPT-5.4",
            contextWindow = 1_000_000L
        )

        // TODO: Verify exact API model ID for GPT-5.5 once GA.
        data object Gpt55 : OpenAI(
            modelId = "gpt-5.5",
            displayName = "GPT-5.5 (TODO: verify ID)",
            contextWindow = 1_000_000L,
            isPreview = true
        )

        // TODO: Verify exact API model ID for o3-pro.
        data object O3Pro : OpenAI(
            modelId = "o3-pro",
            displayName = "o3 Pro (Reasoning, TODO: verify ID)",
            contextWindow = 200_000L,
            isPreview = true
        )

        data object Gpt54Pro : OpenAI(
            modelId = "gpt-5.4-pro",
            displayName = "GPT-5.4 Pro (Reasoning)",
            contextWindow = 1_000_000L
        )

        data object Gpt54Mini : OpenAI(
            modelId = "gpt-5.4-mini",
            displayName = "GPT-5.4 mini",
            contextWindow = 400_000L
        )

        data object Gpt54Nano : OpenAI(
            modelId = "gpt-5.4-nano",
            displayName = "GPT-5.4 nano",
            contextWindow = 400_000L
        )

        data object Gpt52 : OpenAI(
            modelId = "gpt-5.2",
            displayName = "GPT-5.2",
            contextWindow = 400_000L
        )

        data object Gpt52Codex : OpenAI(
            modelId = "gpt-5.2-codex",
            displayName = "GPT-5.2 Codex",
            contextWindow = 400_000L
        )

        data object Gpt51 : OpenAI(
            modelId = "gpt-5.1",
            displayName = "GPT-5.1",
            contextWindow = 400_000L
        )

        data object Gpt5Mini : OpenAI(
            modelId = "gpt-5-mini",
            displayName = "GPT-5 Mini",
            contextWindow = 128_000L
        )

        data object Gpt5Nano : OpenAI(
            modelId = "gpt-5-nano",
            displayName = "GPT-5 Nano",
            contextWindow = 128_000L
        )

        data object O3 : OpenAI(
            modelId = "o3",
            displayName = "o3 (Reasoning)",
            contextWindow = 200_000L
        )

        companion object {
            fun all(): List<OpenAI> = listOf(
                Gpt55, Gpt54, Gpt54Pro, Gpt54Mini, Gpt54Nano,
                Gpt52, Gpt52Codex, Gpt51, Gpt5Mini, Gpt5Nano,
                O3Pro, O3
            )
        }
    }

    // ==================== xAI Grok ====================

    sealed class Grok(
        modelId: String,
        displayName: String,
        contextWindow: Long,
        isPreview: Boolean = false,
        isDeprecated: Boolean = false,
        val isReasoningOnly: Boolean = false
    ) : AIModel(modelId, displayName, contextWindow, Provider.GROK, isPreview, isDeprecated) {

        data object Grok420BetaReasoning : Grok(
            modelId = "grok-4.20-beta-latest-reasoning",
            displayName = "Grok 4.20 Beta (Reasoning)",
            contextWindow = 2_000_000L,
            isPreview = true,
            isReasoningOnly = true
        )

        // TODO: Verify exact API model ID for Grok 4.3 once xAI publishes it.
        data object Grok43 : Grok(
            modelId = "grok-4.3",
            displayName = "Grok 4.3 (TODO: verify ID)",
            contextWindow = 2_000_000L,
            isPreview = true
        )

        data object Grok420 : Grok(
            modelId = "grok-4.20",
            displayName = "Grok 4.20 (Early Access)",
            contextWindow = -1L, // TBD
            isPreview = true
        )

        data object Grok4 : Grok(
            modelId = "grok-4",
            displayName = "Grok 4 (Reasoning)",
            contextWindow = 256_000L,
            isReasoningOnly = true
        )

        data object Grok41Fast : Grok(
            modelId = "grok-4.1-fast",
            displayName = "Grok 4.1 Fast",
            contextWindow = 2_000_000L
        )

        data object Grok3 : Grok(
            modelId = "grok-3",
            displayName = "Grok 3",
            contextWindow = 128_000L
        )

        data object Grok3Mini : Grok(
            modelId = "grok-3-mini",
            displayName = "Grok 3 Mini",
            contextWindow = 128_000L
        )

        data object GrokImage : Grok(
            modelId = "grok-2-image-1212",
            displayName = "Grok Imagine (Image Gen)",
            contextWindow = 0L // N/A — image generation
        )

        companion object {
            fun all(): List<Grok> = listOf(
                Grok43, Grok420BetaReasoning, Grok420, Grok4, Grok41Fast, Grok3, Grok3Mini, GrokImage
            )

            /**
             * Whether this model is a pure reasoning model that
             * rejects penalty, stop, and reasoning_effort params.
             */
            fun isReasoningOnly(modelId: String): Boolean =
                all().find { it.modelId == modelId }?.isReasoningOnly == true
        }
    }

    // ==================== DeepSeek ====================

    sealed class DeepSeek(
        modelId: String,
        displayName: String,
        contextWindow: Long,
        isPreview: Boolean = false,
        isDeprecated: Boolean = false,
        val isReasoner: Boolean = false
    ) : AIModel(modelId, displayName, contextWindow, Provider.DEEPSEEK, isPreview, isDeprecated) {

        data object Chat : DeepSeek(
            modelId = "deepseek-chat",
            displayName = "DeepSeek V3.2 (Chat)",
            contextWindow = 128_000L
        )

        data object Reasoner : DeepSeek(
            modelId = "deepseek-reasoner",
            displayName = "DeepSeek V3.2 (Reasoner)",
            contextWindow = 128_000L,
            isReasoner = true
        )

        data object Speciale : DeepSeek(
            modelId = "deepseek-v3.2-speciale",
            displayName = "DeepSeek V3.2 Speciale",
            contextWindow = 128_000L,
            isPreview = true,
            isReasoner = true
        )

        // TODO: Verify exact API model ID for DeepSeek V4 Pro.
        data object V4Pro : DeepSeek(
            modelId = "deepseek-v4-pro",
            displayName = "DeepSeek V4 Pro (TODO: verify ID)",
            contextWindow = 128_000L,
            isPreview = true
        )

        // TODO: Verify exact API model ID for DeepSeek V4 Flash Max.
        data object V4FlashMax : DeepSeek(
            modelId = "deepseek-v4-flash-max",
            displayName = "DeepSeek V4 Flash Max (TODO: verify ID)",
            contextWindow = 128_000L,
            isPreview = true
        )

        // TODO: Verify whether 'deepseek-r1' is still a valid public alias.
        data object R1 : DeepSeek(
            modelId = "deepseek-r1",
            displayName = "DeepSeek R1 (TODO: verify ID)",
            contextWindow = 128_000L,
            isPreview = true,
            isReasoner = true
        )

        companion object {
            fun all(): List<DeepSeek> = listOf(Chat, Reasoner, Speciale, V4Pro, V4FlashMax, R1)

            fun isReasoner(modelId: String): Boolean =
                all().find { it.modelId == modelId }?.isReasoner == true
        }
    }

    // ==================== Alibaba Qwen ====================

    sealed class Qwen(
        modelId: String,
        displayName: String,
        contextWindow: Long,
        isPreview: Boolean = false,
        isDeprecated: Boolean = false,
        val supportsThinkingMode: Boolean = false
    ) : AIModel(modelId, displayName, contextWindow, Provider.QWEN, isPreview, isDeprecated) {

        data object Qwen3Max : Qwen(
            modelId = "qwen3-max-2026-01-23",
            displayName = "Qwen3 Max (2026-01-23)",
            contextWindow = 262_144L,
            supportsThinkingMode = true
        )

        data object Qwen35Plus : Qwen(
            modelId = "qwen3.5-plus",
            displayName = "Qwen3.5 Plus",
            contextWindow = 131_072L
        )

        data object Qwen35Flash : Qwen(
            modelId = "qwen3.5-flash",
            displayName = "Qwen3.5 Flash",
            contextWindow = 131_072L
        )

        companion object {
            fun all(): List<Qwen> = listOf(Qwen3Max, Qwen35Plus, Qwen35Flash)

            fun supportsThinkingMode(modelId: String): Boolean =
                all().find { it.modelId == modelId }?.supportsThinkingMode == true
        }
    }

    // ==================== Zhipu GLM ====================

    sealed class Zhipu(
        modelId: String,
        displayName: String,
        contextWindow: Long,
        isPreview: Boolean = false,
        isDeprecated: Boolean = false
    ) : AIModel(modelId, displayName, contextWindow, Provider.ZHIPU, isPreview, isDeprecated) {

        data object Glm5 : Zhipu(
            modelId = "glm-5",
            displayName = "GLM-5",
            contextWindow = 128_000L
        )

        data object Glm51 : Zhipu(
            modelId = "glm-5.1",
            displayName = "GLM-5.1",
            contextWindow = 128_000L
        )

        data object Glm46V : Zhipu(
            modelId = "glm-4.6v",
            displayName = "GLM-4.6V (Vision)",
            contextWindow = 128_000L
        )

        data object Glm4Plus : Zhipu(
            modelId = "glm-4-plus",
            displayName = "GLM-4 Plus",
            contextWindow = 128_000L
        )

        companion object {
            fun all(): List<Zhipu> = listOf(Glm5, Glm51, Glm46V, Glm4Plus)
        }
    }

    // ==================== Moonshot Kimi ====================

    sealed class Moonshot(
        modelId: String,
        displayName: String,
        contextWindow: Long,
        isPreview: Boolean = false,
        isDeprecated: Boolean = false
    ) : AIModel(modelId, displayName, contextWindow, Provider.MOONSHOT, isPreview, isDeprecated) {

        data object KimiK25 : Moonshot(
            modelId = "kimi-k2.5",
            displayName = "Kimi K2.5 (Instant)",
            contextWindow = 256_000L
        )

        data object KimiK25Thinking : Moonshot(
            modelId = "kimi-k2.5-thinking",
            displayName = "Kimi K2.5 (Thinking)",
            contextWindow = 256_000L
        )

        companion object {
            fun all(): List<Moonshot> = listOf(KimiK25, KimiK25Thinking)

            fun isThinkingMode(modelId: String): Boolean =
                modelId == KimiK25Thinking.modelId
        }
    }

    // ==================== Perplexity ====================

    sealed class Perplexity(
        modelId: String,
        displayName: String,
        contextWindow: Long,
        isPreview: Boolean = false,
        isDeprecated: Boolean = false
    ) : AIModel(modelId, displayName, contextWindow, Provider.PERPLEXITY, isPreview, isDeprecated) {

        data object Sonar : Perplexity(
            modelId = "sonar",
            displayName = "Sonar",
            contextWindow = 128_000L
        )

        data object SonarPro : Perplexity(
            modelId = "sonar-pro",
            displayName = "Sonar Pro",
            contextWindow = 200_000L
        )

        data object SonarReasoningPro : Perplexity(
            modelId = "sonar-reasoning-pro",
            displayName = "Sonar Reasoning Pro",
            contextWindow = 128_000L
        )

        data object SonarDeepResearch : Perplexity(
            modelId = "sonar-deep-research",
            displayName = "Sonar Deep Research",
            contextWindow = 128_000L
        )

        companion object {
            fun all(): List<Perplexity> = listOf(Sonar, SonarPro, SonarReasoningPro, SonarDeepResearch)
        }
    }

    // ==================== Groq ====================

    sealed class Groq(
        modelId: String,
        displayName: String,
        contextWindow: Long,
        isPreview: Boolean = false,
        isDeprecated: Boolean = false
    ) : AIModel(modelId, displayName, contextWindow, Provider.GROQ, isPreview, isDeprecated) {

        data object KimiK2Instruct : Groq(
            modelId = "moonshotai/kimi-k2-instruct",
            displayName = "Kimi K2 Instruct (Groq)",
            contextWindow = 128_000L
        )

        data object GptOss120B : Groq(
            modelId = "openai/gpt-oss-120b",
            displayName = "GPT-OSS 120B (Groq)",
            contextWindow = 128_000L
        )

        data object GptOss20B : Groq(
            modelId = "openai/gpt-oss-20b",
            displayName = "GPT-OSS 20B (Groq)",
            contextWindow = 128_000L
        )

        data object Llama4Maverick : Groq(
            modelId = "meta-llama/llama-4-maverick-17b-128e-instruct",
            displayName = "Llama 4 Maverick 17B 128E (Groq)",
            contextWindow = 131_072L
        )

        data object Llama4Scout : Groq(
            modelId = "meta-llama/llama-4-scout-17b-16e-instruct",
            displayName = "Llama 4 Scout 17B 16E (Groq)",
            contextWindow = 131_072L
        )

        data object Qwen332B : Groq(
            modelId = "qwen/qwen3-32b",
            displayName = "Qwen3 32B (Groq)",
            contextWindow = 131_072L
        )

        companion object {
            fun all(): List<Groq> = listOf(
                KimiK2Instruct, GptOss120B, GptOss20B,
                Llama4Maverick, Llama4Scout, Qwen332B
            )
        }
    }

    // ==================== Mistral AI ====================

    sealed class Mistral(
        modelId: String,
        displayName: String,
        contextWindow: Long,
        isPreview: Boolean = false,
        isDeprecated: Boolean = false
    ) : AIModel(modelId, displayName, contextWindow, Provider.MISTRAL, isPreview, isDeprecated) {

        // TODO: Verify exact API model ID against https://docs.mistral.ai/getting-started/models/
        data object MistralMedium35 : Mistral(
            modelId = "mistral-medium-3.5",
            displayName = "Mistral Medium 3.5 (TODO: verify ID)",
            contextWindow = 128_000L,
            isPreview = true
        )

        data object MistralLarge : Mistral(
            modelId = "mistral-large-latest",
            displayName = "Mistral Large (latest)",
            contextWindow = 128_000L
        )

        // TODO: Verify exact API model ID for Ministral 3 (3B) edge tier.
        data object Ministral33B : Mistral(
            modelId = "ministral-3-3b",
            displayName = "Ministral 3 3B (TODO: verify ID)",
            contextWindow = 32_000L,
            isPreview = true
        )

        companion object {
            fun all(): List<Mistral> = listOf(MistralMedium35, MistralLarge, Ministral33B)
        }
    }

    companion object {
        /**
         * Returns all registered models across all providers.
         */
        fun allModels(): List<AIModel> =
            Gemini.all() + Claude.all() + OpenAI.all() + Grok.all() +
            DeepSeek.all() + Qwen.all() + Zhipu.all() +
            Moonshot.all() + Perplexity.all() + Groq.all() + Mistral.all()
    }
}

/**
 * Singleton repository for querying available AI models.
 * Filters out deprecated models by default.
 */
object ModelRepository {

    /**
     * Returns all non-deprecated models.
     */
    fun getAvailableModels(): List<AIModel> =
        AIModel.allModels().filter { !it.isDeprecated }

    /**
     * Returns available models grouped by provider.
     */
    fun getModelsByProvider(): Map<Provider, List<AIModel>> =
        getAvailableModels().groupBy { it.provider }

    /**
     * Returns available models for a specific provider.
     */
    fun getModelsForProvider(provider: Provider): List<AIModel> =
        getAvailableModels().filter { it.provider == provider }

    /**
     * Find a model by its exact model ID string.
     */
    fun findByModelId(modelId: String): AIModel? =
        AIModel.allModels().find { it.modelId == modelId }

    /**
     * Returns only preview models.
     */
    fun getPreviewModels(): List<AIModel> =
        getAvailableModels().filter { it.isPreview }

    /**
     * Returns only stable (non-preview, non-deprecated) models.
     */
    fun getStableModels(): List<AIModel> =
        getAvailableModels().filter { !it.isPreview }
}
