package com.example.rokidphone.ai.catalog

import com.example.rokidphone.data.AiProvider

/**
 * Static fallback model catalog.
 *
 * Used only when the provider's Models API is unreachable AND there is no
 * successful cached response. Every list records the official documentation
 * it was verified against (see [ProviderDescriptor.fallbackDocSource]) and
 * [LAST_VERIFIED_DATE]. The provider's live Models API always wins.
 *
 * Update procedure:
 *  1. Check each provider's official models documentation.
 *  2. Update / add / remove IDs; mark preview & deprecated entries correctly.
 *  3. Bump [LAST_VERIFIED_DATE].
 *  4. Never let a preview or deprecated model be the only default for a provider.
 */
object FallbackModelCatalog {

    const val LAST_VERIFIED_DATE = "2026-09-30"

    private fun chatCaps(
        vision: Boolean = false,
        audioIn: Boolean = false,
        tools: Boolean = true,
        reasoning: Boolean = false,
        context: Long? = null,
        maxOut: Long? = null
    ) = ModelCapabilities(
        imageInput = vision,
        audioInput = audioIn,
        streaming = true,
        toolCalling = tools,
        structuredOutput = true,
        reasoning = reasoning,
        maxContextTokens = context,
        maxOutputTokens = maxOut
    )

    private fun m(
        id: String,
        name: String,
        provider: AiProvider,
        caps: ModelCapabilities,
        status: ModelStatus = ModelStatus.STABLE,
        description: String = ""
    ) = ModelInfo(id, name, provider, caps, status, CatalogSource.FALLBACK, description)

    // ==================== Google Gemini ====================
    private val geminiCaps = chatCaps(vision = true, audioIn = true, context = 1_000_000L)

    val geminiModels = listOf(
        m("gemini-3.8-flash", "Gemini 3.8 Flash", AiProvider.GEMINI, geminiCaps,
            description = "Current stable Flash with multimodal input"),
        m("gemini-3.6-flash", "Gemini 3.6 Flash", AiProvider.GEMINI, geminiCaps,
            description = "Latest stable Flash, 1M context, multimodal"),
        m("gemini-3.5-flash", "Gemini 3.5 Flash", AiProvider.GEMINI, geminiCaps,
            description = "Stable 3.5 Flash, balanced cost/latency"),
        m("gemini-3.5-flash-lite", "Gemini 3.5 Flash-Lite", AiProvider.GEMINI, geminiCaps,
            description = "Stable lite tier, cheapest 3.5 model"),
        m("gemini-3.1-flash-lite", "Gemini 3.1 Flash-Lite", AiProvider.GEMINI, geminiCaps,
            description = "Stable 3.1 lite tier, 1M context"),
        m("gemini-2.5-pro", "Gemini 2.5 Pro", AiProvider.GEMINI, geminiCaps,
            description = "Stable. Most capable 2.5 reasoning model"),
        m("gemini-2.5-flash", "Gemini 2.5 Flash", AiProvider.GEMINI, geminiCaps,
            description = "Stable. Best price-performance in 2.5 series"),
        m("gemini-2.5-flash-lite", "Gemini 2.5 Flash-Lite", AiProvider.GEMINI, geminiCaps,
            description = "Stable. Fastest & cheapest in 2.5 series")
    )

    val geminiLiveModels = listOf(
        m("gemini-3.8-live", "Gemini 3.8 Live", AiProvider.GEMINI_LIVE,
            ModelCapabilities(imageInput = true, audioInput = true, audioOutput = true,
                streaming = true, realtime = true),
            description = "Stable real-time audio conversation model"),
        m("gemini-3.8-live-extended-thinking", "Gemini 3.8 Live Extended Thinking", AiProvider.GEMINI_LIVE,
            ModelCapabilities(imageInput = true, audioInput = true, audioOutput = true,
                streaming = true, realtime = true, reasoning = true),
            description = "Live audio with extended reasoning"),
        m(
            "gemini-2.5-flash-exp", "Gemini 2.5 Flash (Live)", AiProvider.GEMINI_LIVE,
            ModelCapabilities(
                imageInput = true, audioInput = true, audioOutput = true,
                streaming = true, realtime = true
            ),
            ModelStatus.PREVIEW,
            "Live API bidirectional audio model (preview; Live models ship on v1beta only)"
        )
    )

    // ==================== OpenAI ====================
    private val openAiChatCaps = chatCaps(vision = true, reasoning = true, context = 1_000_000L)

    val openaiModels = listOf(
        m("gpt-6-astra", "GPT-6 Astra", AiProvider.OPENAI, openAiChatCaps,
            description = "Flagship quality; Responses API"),
        m("gpt-6-sol", "GPT-6 Sol", AiProvider.OPENAI, openAiChatCaps,
            description = "Balanced GPT-6 tier; Responses API"),
        m("gpt-6-luna", "GPT-6 Luna", AiProvider.OPENAI, openAiChatCaps,
            description = "Fast and efficient GPT-6 tier; Responses API"),
        m("gpt-5.6", "GPT-5.6", AiProvider.OPENAI, openAiChatCaps,
            description = "2026 flagship; Responses API preferred"),
        m("gpt-5.6-terra", "GPT-5.6 Terra", AiProvider.OPENAI, openAiChatCaps,
            description = "GPT-5.6 tier optimized for long agentic tasks"),
        m("gpt-5.6-luna", "GPT-5.6 Luna", AiProvider.OPENAI, openAiChatCaps,
            description = "GPT-5.6 cost-efficient tier"),
        m(
            "gpt-5.4", "GPT-5.4", AiProvider.OPENAI, openAiChatCaps, ModelStatus.LEGACY,
            "Previous flagship, kept for backward compatibility"
        ),
        m(
            "gpt-4o", "GPT-4o", AiProvider.OPENAI,
            chatCaps(vision = true, audioIn = true, reasoning = false, context = 128_000L),
            ModelStatus.LEGACY, "Legacy multimodal flagship"
        )
    )

    // ==================== Anthropic ====================
    private val claudeCaps = chatCaps(vision = true, reasoning = true, context = 200_000L)

    val anthropicModels = listOf(
        m("claude-sonnet-5-5", "Claude Sonnet 5.5", AiProvider.ANTHROPIC,
            chatCaps(vision = true, reasoning = true, context = 1_000_000L, maxOut = 128_000L),
            description = "Current stable Sonnet"),
        m("claude-opus-5-5", "Claude Opus 5.5", AiProvider.ANTHROPIC,
            chatCaps(vision = true, reasoning = true, context = 1_000_000L, maxOut = 128_000L),
            description = "Current stable Opus"),
        m("claude-sonnet-5", "Claude Sonnet 5", AiProvider.ANTHROPIC, claudeCaps,
            description = "Balanced 5-series flagship"),
        m("claude-opus-5", "Claude Opus 5", AiProvider.ANTHROPIC, claudeCaps,
            description = "Most capable 5-series model"),
        m("claude-fable-5", "Claude Fable 5", AiProvider.ANTHROPIC, claudeCaps,
            description = "5-series creative/writing tier"),
        m("claude-opus-4-8", "Claude Opus 4.8", AiProvider.ANTHROPIC, claudeCaps,
            description = "Stable 4.8 flagship, agents & coding"),
        m("claude-haiku-4-5-20251001", "Claude Haiku 4.5", AiProvider.ANTHROPIC, claudeCaps,
            description = "Fastest frontier model, vision-capable")
    )

    // ==================== DeepSeek ====================
    val deepseekModels = listOf(
        m("deepseek-flash", "DeepSeek V4.1 Flash", AiProvider.DEEPSEEK,
            chatCaps(vision = true, tools = true, context = 128_000L),
            description = "Current V4.1 Flash; image input"),
        m(
            "deepseek-v4-flash", "DeepSeek V4 Flash", AiProvider.DEEPSEEK,
            chatCaps(vision = true, tools = true, context = 128_000L), ModelStatus.DEPRECATED,
            "Temporary alias for deepseek-flash"
        ),
        m(
            "deepseek-v4-pro", "DeepSeek V4 Pro", AiProvider.DEEPSEEK,
            chatCaps(tools = true, reasoning = true, context = 128_000L),
            description = "V4 reasoning model; returns reasoning_content separately"
        ),
        m(
            "deepseek-chat", "DeepSeek Chat (legacy alias)", AiProvider.DEEPSEEK,
            chatCaps(tools = true, context = 128_000L), ModelStatus.DEPRECATED,
            "Legacy alias for the V3 chat model; migrated to deepseek-v4-flash"
        ),
        m(
            "deepseek-reasoner", "DeepSeek Reasoner (legacy alias)", AiProvider.DEEPSEEK,
            chatCaps(tools = true, reasoning = true, context = 128_000L), ModelStatus.DEPRECATED,
            "Legacy alias for the V3 reasoner; migrated to deepseek-v4-pro"
        )
    )

    // ==================== Groq ====================
    val groqModels = listOf(
        m(
            "openai/gpt-oss-120b", "GPT-OSS 120B (production)", AiProvider.GROQ,
            chatCaps(tools = true, context = 131_072L),
            description = "OpenAI open-weight 120B hosted on Groq"
        ),
        m(
            "openai/gpt-oss-20b", "GPT-OSS 20B (production)", AiProvider.GROQ,
            chatCaps(tools = true, context = 131_072L),
            description = "OpenAI open-weight 20B hosted on Groq"
        ),
        m("qwen/qwen3.8-27b", "Qwen 3.8 27B", AiProvider.GROQ,
            chatCaps(vision = true, tools = true, reasoning = true, context = 131_072L),
            description = "Replacement for Qwen 3.6 27B"),
        m("llama-3.3-70b-versatile", "Llama 3.3 70B (retired)", AiProvider.GROQ,
            chatCaps(tools = true, context = 131_072L), ModelStatus.DEPRECATED,
            "Retired on 2026-08-16; migrate to GPT-OSS 120B"),
        m(
            "meta-llama/llama-4-scout-17b-16e-instruct", "Llama 4 Scout (preview)", AiProvider.GROQ,
            chatCaps(vision = true, tools = true, context = 131_072L), ModelStatus.PREVIEW,
            "Preview multimodal MoE; preview models may be removed by Groq"
        ),
        m(
            "meta-llama/llama-4-maverick-17b-128e-instruct", "Llama 4 Maverick (preview)", AiProvider.GROQ,
            chatCaps(vision = true, tools = true, context = 131_072L), ModelStatus.PREVIEW,
            "Preview multimodal MoE; preview models may be removed by Groq"
        )
    )

    /** Groq transcription is a separate endpoint with its own models, never chat models. */
    val groqTranscriptionModels = listOf(
        m(
            "whisper-large-v3-turbo", "Whisper Large v3 Turbo", AiProvider.GROQ,
            ModelCapabilities(audioInput = true, transcription = true, textOutput = true, streaming = false),
            description = "Groq hosted Whisper transcription"
        ),
        m(
            "whisper-large-v3", "Whisper Large v3", AiProvider.GROQ,
            ModelCapabilities(audioInput = true, transcription = true, streaming = false),
            description = "Groq hosted Whisper transcription"
        )
    )

    // ==================== xAI ====================
    val xaiModels = listOf(
        m("grok-4.7", "Grok 4.7", AiProvider.XAI,
            chatCaps(vision = true, tools = true, reasoning = true, context = 500_000L),
            description = "Current Grok flagship with image input"),
        m(
            "grok-4.5", "Grok 4.5", AiProvider.XAI,
            chatCaps(vision = true, tools = true, reasoning = true, context = 256_000L),
            description = "Multimodal Grok 4.5 with image input"
        ),
        m(
            "grok-4.1-fast", "Grok 4.1 Fast", AiProvider.XAI,
            chatCaps(tools = true, context = 2_000_000L),
            description = "Fast, low-cost tier, 2M context"
        ),
        m(
            "grok-4", "Grok 4 (reasoning)", AiProvider.XAI,
            chatCaps(tools = true, reasoning = true, context = 256_000L),
            description = "Pure reasoning flagship; rejects penalties/stop"
        ),
        m(
            "grok-3", "Grok 3", AiProvider.XAI,
            chatCaps(tools = true, context = 131_072L), ModelStatus.LEGACY,
            "Previous stable general-purpose model"
        )
    )

    // ==================== Alibaba Cloud Model Studio ====================
    val alibabaModels = listOf(
        m("qwen3.8-max", "Qwen3.8 Max", AiProvider.ALIBABA,
            chatCaps(vision = true, tools = true, context = 1_000_000L),
            description = "Current multimodal Qwen flagship"),
        m("qwen3.8-flash", "Qwen3.8 Flash", AiProvider.ALIBABA,
            chatCaps(vision = true, tools = true, context = 1_000_000L),
            description = "Fast multimodal Qwen tier"),
        m(
            "qwen3.7-max", "Qwen3.7 Max", AiProvider.ALIBABA,
            chatCaps(tools = true, context = 262_144L),
            description = "Qwen3.7 flagship"
        ),
        m(
            "qwen3.7-plus", "Qwen3.7 Plus", AiProvider.ALIBABA,
            chatCaps(tools = true, context = 131_072L),
            description = "Balanced Qwen3.7 tier"
        ),
        m(
            "qwen3.7-flash", "Qwen3.7 Flash", AiProvider.ALIBABA,
            chatCaps(tools = true, context = 131_072L),
            description = "Fast & cost-effective Qwen3.7 tier"
        ),
        m(
            "qwen2.5-vl-72b", "Qwen2.5 VL 72B", AiProvider.ALIBABA,
            chatCaps(vision = true, tools = true, context = 131_072L),
            description = "Vision-language model (multimodal)"
        ),
        m(
            "qwen2.5-vl-32b", "Qwen2.5 VL 32B", AiProvider.ALIBABA,
            chatCaps(vision = true, tools = true, context = 131_072L),
            description = "Balanced vision-language model (multimodal)"
        )
    )

    // ==================== Z.AI / GLM ====================
    val zhipuModels = listOf(
        m(
            "glm-5.1", "GLM-5.1", AiProvider.ZHIPU,
            chatCaps(tools = true, context = 131_072L),
            description = "Flagship bilingual model (text)"
        ),
        m(
            "glm-5v-turbo", "GLM-5V Turbo (vision)", AiProvider.ZHIPU,
            chatCaps(vision = true, tools = true, context = 131_072L),
            description = "Vision-enabled GLM-5 series model"
        ),
        m(
            "glm-4.7-flash", "GLM-4.7 Flash", AiProvider.ZHIPU,
            chatCaps(tools = true, context = 131_072L),
            description = "Fast low-cost tier; free quota may apply (see Z.AI console)"
        ),
        m(
            "glm-4.7", "GLM-4.7", AiProvider.ZHIPU,
            chatCaps(tools = true, context = 131_072L),
            description = "Previous flagship (text)"
        ),
        m(
            "glm-4.6v", "GLM-4.6V (vision)", AiProvider.ZHIPU,
            chatCaps(vision = true, tools = true, context = 131_072L), ModelStatus.LEGACY,
            "Previous vision variant"
        )
    )

    // ==================== Baidu Qianfan ====================
    val baiduModels = listOf(
        m(
            "ernie-5.1", "ERNIE 5.1", AiProvider.BAIDU,
            chatCaps(tools = true, context = 131_072L),
            description = "Latest ERNIE flagship on Qianfan v2"
        ),
        m(
            "ernie-5.0", "ERNIE 5.0", AiProvider.BAIDU,
            chatCaps(tools = true, context = 131_072L),
            description = "ERNIE 5.0 general model"
        ),
        m(
            "ernie-4.5-turbo-128k", "ERNIE 4.5 Turbo 128K", AiProvider.BAIDU,
            chatCaps(tools = true, context = 131_072L),
            description = "Fast ERNIE 4.5 tier, 128K context"
        ),
        m(
            "ernie-4.5-turbo-vl", "ERNIE 4.5 Turbo VL (vision)", AiProvider.BAIDU,
            chatCaps(vision = true, tools = true, context = 131_072L),
            description = "Multimodal ERNIE 4.5 tier with image input"
        ),
        m(
            "ernie-4.0-8k", "ERNIE 4.0 8K (legacy)", AiProvider.BAIDU,
            chatCaps(context = 8_000L), ModelStatus.LEGACY,
            "Legacy model from the API Key + Secret Key era"
        ),
        m(
            "ernie-3.5-8k", "ERNIE 3.5 8K (legacy)", AiProvider.BAIDU,
            chatCaps(context = 8_000L), ModelStatus.LEGACY,
            "Legacy model from the API Key + Secret Key era"
        )
    )

    // ==================== Perplexity (Sonar API) ====================
    val perplexityModels = listOf(
        m(
            "sonar", "Sonar", AiProvider.PERPLEXITY,
            chatCaps(vision = true, tools = false, context = 128_000L),
            description = "Lightweight grounded search model"
        ),
        m(
            "sonar-pro", "Sonar Pro", AiProvider.PERPLEXITY,
            chatCaps(vision = true, tools = false, context = 200_000L),
            description = "Advanced grounded search, citations & search results"
        ),
        m(
            "sonar-reasoning-pro", "Sonar Reasoning Pro", AiProvider.PERPLEXITY,
            chatCaps(vision = true, tools = false, reasoning = true, context = 128_000L),
            description = "Reasoning tier with Chain-of-Thought (CoT not surfaced)"
        ),
        m(
            "sonar-deep-research", "Sonar Deep Research", AiProvider.PERPLEXITY,
            chatCaps(vision = true, tools = false, reasoning = true, context = 128_000L),
            description = "Exhaustive research reports; long-running"
        )
    )

    // ==================== Moonshot ====================
    val moonshotModels = listOf(
        m("kimi-k2.6", "Kimi K2.6", AiProvider.MOONSHOT,
            chatCaps(vision = true, tools = true, context = 262_144L),
            description = "Current general-purpose text and vision model"),
        m(
            "kimi-k2.5", "Kimi K2.5 (instant)", AiProvider.MOONSHOT,
            chatCaps(vision = true, tools = true, context = 262_144L),
            description = "Multimodal Kimi with image & video understanding"
        ),
        m(
            "kimi-k2.5-thinking", "Kimi K2.5 (thinking)", AiProvider.MOONSHOT,
            chatCaps(vision = true, tools = true, reasoning = true, context = 262_144L),
            description = "K2.5 with extended reasoning"
        ),
        m(
            "moonshot-v1-128k", "Moonshot V1 128K", AiProvider.MOONSHOT,
            chatCaps(tools = true, context = 131_072L), ModelStatus.LEGACY,
            "Legacy long-context model"
        ),
        m(
            "moonshot-v1-32k", "Moonshot V1 32K", AiProvider.MOONSHOT,
            chatCaps(tools = true, context = 32_768L), ModelStatus.LEGACY,
            "Legacy balanced model"
        ),
        m(
            "moonshot-v1-8k", "Moonshot V1 8K", AiProvider.MOONSHOT,
            chatCaps(tools = true, context = 8_192L), ModelStatus.LEGACY,
            "Legacy short-context model"
        )
    )

    // ==================== Mistral ====================
    val mistralModels = listOf(
        m(
            "mistral-medium-3-5", "Mistral Medium 3.5", AiProvider.MISTRAL,
            chatCaps(vision = true, tools = true, context = 262_144L),
            description = "Mid-tier balanced multimodal model"
        ),
        m(
            "mistral-large-2512", "Mistral Large (2512)", AiProvider.MISTRAL,
            chatCaps(vision = true, tools = true, context = 262_144L),
            description = "Flagship large tier"
        ),
        m(
            "mistral-small-2603", "Mistral Small (2603)", AiProvider.MISTRAL,
            chatCaps(tools = true, context = 131_072L),
            description = "Cost-efficient small tier (text)"
        ),
        m(
            "ministral-3-8b", "Ministral 3 8B (multimodal)", AiProvider.MISTRAL,
            chatCaps(vision = true, tools = true, context = 131_072L),
            description = "Edge-class multimodal model"
        )
    )

    // ==================== AnythingLLM / Custom ====================
    val anythingllmModels = listOf(
        m(
            "workspace", "Workspace model", AiProvider.ANYTHINGLLM,
            ModelCapabilities(streaming = false),
            description = "Capability unknown: determined by the AnythingLLM workspace LLM"
        )
    )

    val customModels = listOf(
        m(
            "custom", "Custom model", AiProvider.CUSTOM,
            ModelCapabilities(streaming = true),
            description = "User-defined model ID on an OpenAI-compatible endpoint"
        ),
        m(
            "llama4", "Llama 4 (Ollama)", AiProvider.CUSTOM,
            ModelCapabilities(streaming = true),
            description = "Local Llama 4 via Ollama"
        ),
        m(
            "minicpm-v-2.6", "MiniCPM-V 2.6 (Ollama)", AiProvider.CUSTOM,
            ModelCapabilities(imageInput = true, streaming = true),
            description = "Local vision model"
        ),
        m(
            "moondream2", "Moondream 2 (Ollama)", AiProvider.CUSTOM,
            ModelCapabilities(imageInput = true, streaming = true),
            description = "Tiny local vision model"
        )
    )

    // ==================== On-Device Gemma (local inference) ====================
    // Capabilities are intentionally conservative: text in/out with streaming.
    // These entries describe models the user can install locally; the actual
    // download/placement is managed outside the fallback catalog.
    private fun localGemmaCaps(context: Long) = ModelCapabilities(
        textInput = true,
        textOutput = true,
        streaming = true,
        maxContextTokens = context
    )

    val localGemmaModels = listOf(
        m(
            "gemma-3n-E2B-it", "Gemma 3n E2B (on-device)", AiProvider.LOCAL_GEMMA,
            localGemmaCaps(context = 32_768L),
            description = "Compact on-device Gemma (E2B); lowest RAM footprint"
        ),
        m(
            "gemma-3n-E4B-it", "Gemma 3n E4B (on-device)", AiProvider.LOCAL_GEMMA,
            localGemmaCaps(context = 32_768L),
            description = "Larger on-device Gemma (E4B); needs more RAM/storage"
        )
    )

    /** Verified fallback list for a provider (chat/live models only — never STT/TTS/image-gen). */
    fun modelsFor(provider: AiProvider): List<ModelInfo> = when (provider) {
        AiProvider.GEMINI -> geminiModels
        AiProvider.OPENAI -> openaiModels
        AiProvider.ANTHROPIC -> anthropicModels
        AiProvider.DEEPSEEK -> deepseekModels
        AiProvider.GROQ -> groqModels
        AiProvider.XAI -> xaiModels
        AiProvider.ALIBABA -> alibabaModels
        AiProvider.ZHIPU -> zhipuModels
        AiProvider.BAIDU -> baiduModels
        AiProvider.PERPLEXITY -> perplexityModels
        AiProvider.MOONSHOT -> moonshotModels
        AiProvider.MISTRAL -> mistralModels
        AiProvider.GEMINI_LIVE -> geminiLiveModels
        AiProvider.ANYTHINGLLM -> anythingllmModels
        AiProvider.LOCAL_GEMMA -> localGemmaModels
        AiProvider.CUSTOM -> customModels
    }

    fun find(provider: AiProvider, modelId: String): ModelInfo? =
        modelsFor(provider).find { it.id == modelId }

    /** Transcription-only lookup; kept separate so chat selection never resolves an STT model. */
    fun findTranscription(modelId: String): ModelInfo? =
        groqTranscriptionModels.find { it.id == modelId }

    /**
     * The default model for a provider: first STABLE entry. A preview or
     * deprecated model is never the sole default when a stable entry exists.
     * Never crashes on an (accidentally) empty catalog — returns "" instead.
     */
    fun defaultModelFor(provider: AiProvider): String =
        modelsFor(provider).let { list ->
            list.firstOrNull { it.isSelectableDefault }?.id
                ?: list.firstOrNull { it.status != ModelStatus.DEPRECATED }?.id
                ?: list.firstOrNull()?.id
                ?: ""
        }

    /** Every fallback entry indexed by (provider, id) for O(1) capability resolution. */
    private val index: Map<AiProvider, Map<String, ModelInfo>> by lazy {
        AiProvider.entries.associateWith { p -> modelsFor(p).associateBy { it.id } }
    }

    fun capabilityFor(provider: AiProvider, modelId: String): ModelCapabilities? =
        index[provider]?.get(modelId)?.capabilities

    /** Legacy model ID → replacement per provider, applied to stored settings on migration. */
    val legacyModelMigration: Map<AiProvider, Map<String, String>> = mapOf(
        AiProvider.DEEPSEEK to mapOf(
            "deepseek-chat" to "deepseek-flash",
            "deepseek-v4-flash" to "deepseek-flash",
            "deepseek-reasoner" to "deepseek-v4-pro"
        ),
        AiProvider.GROQ to mapOf(
            "llama-3.3-70b-versatile" to "openai/gpt-oss-120b",
            "qwen/qwen3.6-27b" to "qwen/qwen3.8-27b"
        )
    )

    init {
        // Catalog integrity: provider consistency + unique IDs per provider.
        // Declared last so all lists above are initialized before this runs.
        AiProvider.entries.forEach { provider ->
            val models = modelsFor(provider)
            require(models.all { it.provider == provider }) {
                "FallbackModelCatalog: provider mismatch in list for $provider"
            }
            require(models.distinctBy { it.id }.size == models.size) {
                "FallbackModelCatalog: duplicate model IDs for $provider"
            }
        }
        require(groqTranscriptionModels.all { it.provider == AiProvider.GROQ }) {
            "FallbackModelCatalog: provider mismatch in groqTranscriptionModels"
        }
    }
}
