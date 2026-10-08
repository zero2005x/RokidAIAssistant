package io.github.zero2005x.glassesaicompanion.ai.catalog

import io.github.zero2005x.glassesaicompanion.data.AiProvider

/**
 * Data-driven registry of every supported provider.
 *
 * Adding a provider means adding one entry here plus a request adapter for its
 * [ApiProtocol] (if the protocol is new). The factory, settings UI and model
 * catalog all read from this registry.
 */
object ProviderRegistry {

    private const val DOC_GEMINI = "Google AI for Developers - Gemini API models"
    private const val DOC_OPENAI = "OpenAI Platform docs - Models"
    private const val DOC_ANTHROPIC = "Anthropic docs - Models overview"
    private const val DOC_DEEPSEEK = "DeepSeek API docs - Models"
    private const val DOC_GROQ = "Groq docs - Models"
    private const val DOC_XAI = "xAI docs - Models"
    private const val DOC_ALIBABA = "Alibaba Cloud Model Studio docs - Models"
    private const val DOC_ZHIPU = "Z.AI docs - Models"
    private const val DOC_BAIDU = "Baidu Qianfan docs - Models (v2)"
    private const val DOC_PERPLEXITY = "Perplexity docs - Sonar models"
    private const val DOC_MOONSHOT = "Moonshot AI docs - Models"
    private const val DOC_MISTRAL = "Mistral AI docs - Models overview"

    private val descriptors: Map<AiProvider, ProviderDescriptor> = listOf(
        ProviderDescriptor(
            id = AiProvider.GEMINI,
            displayName = "Google Gemini",
            defaultBaseUrl = "https://generativelanguage.googleapis.com/v1beta/",
            protocol = ApiProtocol.GEMINI_GENERATE_CONTENT,
            catalogFormat = CatalogFormat.GEMINI,
            modelsEndpointPath = "models",
            requiresApiKey = true,
            allowsCustomBaseUrl = false,
            fallbackDocSource = DOC_GEMINI
        ),
        ProviderDescriptor(
            id = AiProvider.OPENAI,
            displayName = "OpenAI",
            defaultBaseUrl = "https://api.openai.com/v1/",
            protocol = ApiProtocol.OPENAI_RESPONSES,
            catalogFormat = CatalogFormat.OPENAI_STYLE,
            modelsEndpointPath = "models",
            requiresApiKey = true,
            allowsCustomBaseUrl = false,
            fallbackDocSource = DOC_OPENAI
        ),
        ProviderDescriptor(
            id = AiProvider.ANTHROPIC,
            displayName = "Anthropic",
            defaultBaseUrl = "https://api.anthropic.com/v1/",
            protocol = ApiProtocol.ANTHROPIC_MESSAGES,
            catalogFormat = CatalogFormat.ANTHROPIC,
            modelsEndpointPath = "models",
            requiresApiKey = true,
            allowsCustomBaseUrl = false,
            fallbackDocSource = DOC_ANTHROPIC
        ),
        ProviderDescriptor(
            id = AiProvider.DEEPSEEK,
            displayName = "DeepSeek",
            defaultBaseUrl = "https://api.deepseek.com/",
            protocol = ApiProtocol.OPENAI_CHAT_COMPLETIONS,
            catalogFormat = CatalogFormat.OPENAI_STYLE,
            modelsEndpointPath = "models",
            requiresApiKey = true,
            allowsCustomBaseUrl = false,
            fallbackDocSource = DOC_DEEPSEEK
        ),
        ProviderDescriptor(
            id = AiProvider.GROQ,
            displayName = "Groq",
            defaultBaseUrl = "https://api.groq.com/openai/v1/",
            protocol = ApiProtocol.OPENAI_CHAT_COMPLETIONS,
            catalogFormat = CatalogFormat.OPENAI_STYLE,
            modelsEndpointPath = "models",
            requiresApiKey = true,
            allowsCustomBaseUrl = false,
            fallbackDocSource = DOC_GROQ
        ),
        ProviderDescriptor(
            id = AiProvider.XAI,
            displayName = "xAI",
            defaultBaseUrl = "https://api.x.ai/v1/",
            protocol = ApiProtocol.OPENAI_CHAT_COMPLETIONS,
            catalogFormat = CatalogFormat.OPENAI_STYLE,
            modelsEndpointPath = "models",
            requiresApiKey = true,
            allowsCustomBaseUrl = false,
            fallbackDocSource = DOC_XAI
        ),
        ProviderDescriptor(
            id = AiProvider.ALIBABA,
            displayName = "Alibaba Cloud Model Studio",
            defaultBaseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1/",
            protocol = ApiProtocol.OPENAI_CHAT_COMPLETIONS,
            catalogFormat = CatalogFormat.OPENAI_STYLE,
            modelsEndpointPath = "models",
            requiresApiKey = true,
            allowsCustomBaseUrl = true,
            supportsRegionalEndpoint = true,
            fallbackDocSource = DOC_ALIBABA
        ),
        ProviderDescriptor(
            id = AiProvider.ZHIPU,
            displayName = "Z.AI (GLM)",
            defaultBaseUrl = "https://api.z.ai/api/paas/v4/",
            protocol = ApiProtocol.OPENAI_CHAT_COMPLETIONS,
            catalogFormat = CatalogFormat.OPENAI_STYLE,
            modelsEndpointPath = "models",
            requiresApiKey = true,
            allowsCustomBaseUrl = false,
            fallbackDocSource = DOC_ZHIPU
        ),
        ProviderDescriptor(
            id = AiProvider.BAIDU,
            displayName = "Baidu Qianfan",
            defaultBaseUrl = "https://qianfan.baidubce.com/v2/",
            protocol = ApiProtocol.BAIDU_QIANFAN_V2,
            catalogFormat = CatalogFormat.BAIDU_QIANFAN_V2,
            modelsEndpointPath = "models",
            requiresApiKey = true,
            allowsCustomBaseUrl = false,
            fallbackDocSource = DOC_BAIDU
        ),
        ProviderDescriptor(
            id = AiProvider.PERPLEXITY,
            displayName = "Perplexity",
            defaultBaseUrl = "https://api.perplexity.ai/",
            protocol = ApiProtocol.OPENAI_CHAT_COMPLETIONS,
            // /v1/models reflects the Agent API, not Sonar chat models —
            // Sonar uses the verified fallback list + manual entry instead.
            catalogFormat = CatalogFormat.NONE,
            modelsEndpointPath = null,
            requiresApiKey = true,
            allowsCustomBaseUrl = false,
            fallbackDocSource = DOC_PERPLEXITY
        ),
        ProviderDescriptor(
            id = AiProvider.MOONSHOT,
            displayName = "Moonshot (Kimi)",
            defaultBaseUrl = "https://api.moonshot.ai/v1/",
            protocol = ApiProtocol.OPENAI_CHAT_COMPLETIONS,
            catalogFormat = CatalogFormat.OPENAI_STYLE,
            modelsEndpointPath = "models",
            requiresApiKey = true,
            allowsCustomBaseUrl = false,
            fallbackDocSource = DOC_MOONSHOT
        ),
        ProviderDescriptor(
            id = AiProvider.MISTRAL,
            displayName = "Mistral AI",
            defaultBaseUrl = "https://api.mistral.ai/v1/",
            protocol = ApiProtocol.OPENAI_CHAT_COMPLETIONS,
            catalogFormat = CatalogFormat.MISTRAL,
            modelsEndpointPath = "models",
            requiresApiKey = true,
            allowsCustomBaseUrl = false,
            fallbackDocSource = DOC_MISTRAL
        ),
        ProviderDescriptor(
            id = AiProvider.GEMINI_LIVE,
            displayName = "Gemini Live",
            // WebSocket endpoint: there is no usable HTTP models list for the
            // Live API here, so this provider has no remote catalog and uses
            // the verified fallback list (its models ship on v1beta only).
            defaultBaseUrl = "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent",
            protocol = ApiProtocol.GEMINI_LIVE,
            catalogFormat = CatalogFormat.NONE,
            modelsEndpointPath = null,
            requiresApiKey = true,
            allowsCustomBaseUrl = false,
            fallbackDocSource = DOC_GEMINI
        ),
        ProviderDescriptor(
            id = AiProvider.ANYTHINGLLM,
            displayName = "AnythingLLM",
            // Cleartext http:// is intentional here: the default targets a
            // loopback self-hosted server; users exposing a remote instance
            // should supply an https:// URL via the custom base URL setting.
            defaultBaseUrl = "http://localhost:3001",
            protocol = ApiProtocol.ANYTHING_LLM,
            catalogFormat = CatalogFormat.NONE,
            modelsEndpointPath = null,
            requiresApiKey = true,
            allowsCustomBaseUrl = true,
            fallbackDocSource = "AnythingLLM developer API docs"
        ),
        ProviderDescriptor(
            id = AiProvider.LOCAL_GEMMA,
            displayName = "On-Device Gemma",
            defaultBaseUrl = "local://gemma/",
            protocol = ApiProtocol.LOCAL_INFERENCE,
            // No remote models endpoint: installed models are discovered by
            // scanning the app-private model directory; verified defaults come
            // from the fallback catalog.
            catalogFormat = CatalogFormat.NONE,
            modelsEndpointPath = null,
            requiresApiKey = false,
            allowsCustomBaseUrl = false,
            fallbackDocSource = "Google AI Edge / MediaPipe LLM Inference - Gemma models"
        ),
        ProviderDescriptor(
            id = AiProvider.CUSTOM,
            displayName = "Custom (OpenAI-compatible)",
            defaultBaseUrl = "http://localhost:11434/v1/",
            protocol = ApiProtocol.CUSTOM_OPENAI_COMPATIBLE,
            catalogFormat = CatalogFormat.OPENAI_STYLE,
            modelsEndpointPath = "models",
            requiresApiKey = false,
            allowsCustomBaseUrl = true,
            fallbackDocSource = "OpenAI-compatible server docs"
        )
    ).also { list ->
        require(list.distinctBy { it.id }.size == list.size) {
            "Duplicate ProviderDescriptor ids: " +
                list.groupBy { it.id }.filterValues { it.size > 1 }.keys
        }
    }.associateBy { it.id }

    init {
        require(isComplete()) {
            "Missing ProviderDescriptor for: " + (AiProvider.entries.toSet() - descriptors.keys)
        }
    }

    /** Cached ordered list so [all] does not rebuild it on every call. */
    private val ordered: List<ProviderDescriptor> = AiProvider.entries.map { descriptorFor(it) }

    fun descriptorFor(provider: AiProvider): ProviderDescriptor =
        requireNotNull(descriptors[provider]) { "No ProviderDescriptor registered for $provider" }

    fun all(): List<ProviderDescriptor> = ordered

    /** Every provider must be registered; exposed for factory coverage tests. */
    fun isComplete(): Boolean = descriptors.keys.containsAll(AiProvider.entries.toSet())
}
