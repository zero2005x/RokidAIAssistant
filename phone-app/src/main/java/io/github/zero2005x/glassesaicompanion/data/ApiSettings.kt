package io.github.zero2005x.glassesaicompanion.data

import android.util.Log
import androidx.annotation.StringRes
import io.github.zero2005x.glassesaicompanion.R
import io.github.zero2005x.glassesaicompanion.service.stt.SttProvider

/**
 * AI Service Providers
 */
enum class AiProvider(
    @param:StringRes val displayNameResId: Int,
    val description: String,
    val website: String,
    val defaultBaseUrl: String,
    val isOpenAiCompatible: Boolean = true,
    val supportsSpeech: Boolean = false,
    val supportsVision: Boolean = false
) {
    GEMINI(
        displayNameResId = R.string.provider_gemini,
        description = "Google's latest AI model, supports audio and vision",
        website = "https://ai.google.dev",
        defaultBaseUrl = "https://generativelanguage.googleapis.com/v1beta/",
        isOpenAiCompatible = false,
        supportsSpeech = true,
        supportsVision = true
    ),
    OPENAI(
        displayNameResId = R.string.provider_openai,
        description = "GPT series models, industry standard",
        website = "https://openai.com",
        defaultBaseUrl = "https://api.openai.com/v1/",
        isOpenAiCompatible = true,
        supportsSpeech = true,  // Whisper
        supportsVision = true   // GPT-5.x Vision
    ),
    ANTHROPIC(
        displayNameResId = R.string.provider_anthropic,
        description = "Claude series, powerful reasoning",
        website = "https://anthropic.com",
        defaultBaseUrl = "https://api.anthropic.com/v1/",
        isOpenAiCompatible = false,
        supportsSpeech = false,
        supportsVision = true
    ),
    DEEPSEEK(
        displayNameResId = R.string.provider_deepseek,
        description = "Cost-effective Chinese model",
        website = "https://deepseek.com",
        defaultBaseUrl = "https://api.deepseek.com/",
        isOpenAiCompatible = true,
        supportsSpeech = false,
        supportsVision = false
    ),
    GROQ(
        displayNameResId = R.string.provider_groq,
        description = "Ultra-fast inference, hardware accelerated",
        website = "https://groq.com",
        defaultBaseUrl = "https://api.groq.com/openai/v1/",
        isOpenAiCompatible = true,
        supportsSpeech = true,  // Whisper
        supportsVision = true   // Llama Vision
    ),
    XAI(
        displayNameResId = R.string.provider_xai,
        description = "Elon Musk's xAI, Grok models",
        website = "https://x.ai",
        defaultBaseUrl = "https://api.x.ai/v1/",
        isOpenAiCompatible = true,
        supportsSpeech = false,
        supportsVision = true   // Grok 4.5+ image input (model-level)
    ),
    ALIBABA(
        displayNameResId = R.string.provider_alibaba,
        description = "Tongyi Qianwen, powerful Chinese model",
        website = "https://dashscope.aliyun.com",
        defaultBaseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1/",
        isOpenAiCompatible = true,
        supportsSpeech = false,
        supportsVision = true
    ),
    ZHIPU(
        displayNameResId = R.string.provider_zhipu,
        description = "GLM series, strong Chinese capabilities",
        website = "https://z.ai/model-api",
        defaultBaseUrl = "https://api.z.ai/api/paas/v4/",
        isOpenAiCompatible = true,
        supportsSpeech = false,
        supportsVision = true   // GLM-V models only (model-level)
    ),
    BAIDU(
        displayNameResId = R.string.provider_baidu,
        description = "Ernie Bot / Wenxin via Qianfan v2 (legacy OAuth kept)",
        website = "https://cloud.baidu.com",
        defaultBaseUrl = "https://qianfan.baidubce.com/v2/",
        isOpenAiCompatible = false,
        supportsSpeech = false,
        supportsVision = true   // ERNIE VL models only (model-level)
    ),
    PERPLEXITY(
        displayNameResId = R.string.provider_perplexity,
        description = "Real-time web search and reasoning (Sonar series)",
        website = "https://www.perplexity.ai",
        defaultBaseUrl = "https://api.perplexity.ai/",
        isOpenAiCompatible = true,
        supportsSpeech = false,
        supportsVision = true   // Sonar image/attachment input (model-level)
    ),
    MOONSHOT(
        displayNameResId = R.string.provider_moonshot,
        description = "Kimi series, multimodal with video support",
        website = "https://moonshot.cn",
        defaultBaseUrl = "https://api.moonshot.ai/v1/",
        isOpenAiCompatible = true,
        supportsSpeech = false,
        supportsVision = true
    ),
    MISTRAL(
        displayNameResId = R.string.provider_mistral,
        description = "European frontier models from Mistral AI (OpenAI-compatible)",
        website = "https://mistral.ai",
        defaultBaseUrl = "https://api.mistral.ai/v1/",
        isOpenAiCompatible = true,
        supportsSpeech = false,
        supportsVision = true
    ),
    GEMINI_LIVE(
        displayNameResId = R.string.provider_gemini_live,
        description = "Gemini Live API - real-time bidirectional voice conversation",
        website = "https://ai.google.dev",
        defaultBaseUrl = "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent",
        isOpenAiCompatible = false,
        supportsSpeech = true,
        supportsVision = true
    ),
    ANYTHINGLLM(
        displayNameResId = R.string.provider_anythingllm,
        description = "Document-grounded retrieval via AnythingLLM workspace",
        website = "https://anythingllm.com",
        defaultBaseUrl = "http://localhost:3001",
        isOpenAiCompatible = false,
        supportsSpeech = false,
        supportsVision = false
    ),
    LOCAL_GEMMA(
        displayNameResId = R.string.provider_local_gemma,
        description = "On-device Gemma inference (no cloud, no API key)",
        website = "https://ai.google.dev/gemma",
        // Not a network endpoint: local models are read from the app-private
        // model directory. A non-empty sentinel keeps registry invariants
        // (every provider must expose a non-empty base URL) satisfied.
        defaultBaseUrl = "local://gemma/",
        isOpenAiCompatible = false,
        supportsSpeech = false,
        supportsVision = false
    ),
    CUSTOM(
        displayNameResId = R.string.provider_custom,
        description = "OpenAI-compatible API (Ollama, LM Studio, etc.)",
        website = "",
        defaultBaseUrl = "http://localhost:11434/v1/",
        isOpenAiCompatible = true,
        supportsSpeech = false,
        supportsVision = false
    );
    
    companion object {
        /** Parse a provider by exact enum [name]; null when unrecognised. */
        fun fromNameOrNull(name: String): AiProvider? = entries.find { it.name == name }

        /**
         * Parse a provider by enum [name]. Unrecognised names log a warning and
         * fall back to [GEMINI]; prefer [fromNameOrNull] when the caller can
         * handle unknown persisted values itself.
         */
        fun fromName(name: String): AiProvider {
            return fromNameOrNull(name) ?: GEMINI.also {
                Log.w("AiProvider", "Unknown provider name '$name', falling back to GEMINI")
            }
        }
    }
    
    /**
     * Check if this provider allows custom base URL
     */
    fun allowsCustomBaseUrl(): Boolean = this == CUSTOM
    
    /**
     * Check if this provider requires API key.
     *
     * [CUSTOM] talks to a user-hosted endpoint that may be open, and
     * [LOCAL_GEMMA] runs entirely on-device, so neither needs a cloud key.
     */
    fun requiresApiKey(): Boolean = this != CUSTOM && this != LOCAL_GEMMA

    /**
     * True when inference runs on-device with no network dependency.
     */
    fun isLocalInference(): Boolean = this == LOCAL_GEMMA
    
    /**
     * Check if this provider requires a secret key (Baidu OAuth)
     */
    fun requiresSecretKey(): Boolean = this == BAIDU
}

/**
 * Providers usable for speech-to-text. Deliberately a curated list rather than
 * `AiProvider.entries.filter { it.supportsSpeech }`: GEMINI_LIVE also sets
 * supportsSpeech but shares the Gemini key and must not appear as a standalone
 * STT option.
 */
private val SPEECH_CAPABLE_STT_PROVIDERS = listOf(AiProvider.GEMINI, AiProvider.OPENAI, AiProvider.GROQ)

/**
 * Model Options
 * @param supportsAudio true if this model supports audio/speech input via the provider's STT API
 * @param supportsVision true if this model supports image/vision input
 */
data class ModelOption(
    val id: String,
    val displayName: String,
    val provider: AiProvider,
    val supportsAudio: Boolean = false,
    val supportsVision: Boolean = false,
    val isPreview: Boolean = false,
    val description: String = ""
)

/**
 * Legacy static model list, now backed by
 * [io.github.zero2005x.glassesaicompanion.ai.catalog.FallbackModelCatalog] (verified
 * 2026-08-02). Kept as a compatibility facade for callers that still consume
 * [ModelOption]; new code should use the ModelCatalogRepository four-tier
 * catalog (live → cache → fallback → manual).
 */
object AvailableModels {
    private fun toOption(model: io.github.zero2005x.glassesaicompanion.ai.catalog.ModelInfo) = ModelOption(
        id = model.id,
        displayName = model.displayName,
        provider = model.provider,
        supportsAudio = model.capabilities.audioInput,
        supportsVision = model.capabilities.imageInput,
        isPreview = model.status == io.github.zero2005x.glassesaicompanion.ai.catalog.ModelStatus.PREVIEW,
        description = model.description
    )

    private val catalog get() = io.github.zero2005x.glassesaicompanion.ai.catalog.FallbackModelCatalog

    // The fallback catalog is static; map it to ModelOption once instead of per access.
    val geminiModels by lazy { catalog.geminiModels.map(::toOption) }
    val openaiModels by lazy { catalog.openaiModels.map(::toOption) }
    val anthropicModels by lazy { catalog.anthropicModels.map(::toOption) }
    val deepseekModels by lazy { catalog.deepseekModels.map(::toOption) }
    val groqModels by lazy { catalog.groqModels.map(::toOption) }
    val anythingllmModels by lazy { catalog.anythingllmModels.map(::toOption) }
    val customModels by lazy { catalog.customModels.map(::toOption) }
    val xaiModels by lazy { catalog.xaiModels.map(::toOption) }
    val alibabaModels by lazy { catalog.alibabaModels.map(::toOption) }
    val zhipuModels by lazy { catalog.zhipuModels.map(::toOption) }
    val baiduModels by lazy { catalog.baiduModels.map(::toOption) }
    val perplexityModels by lazy { catalog.perplexityModels.map(::toOption) }
    val moonshotModels by lazy { catalog.moonshotModels.map(::toOption) }
    val geminiLiveModels by lazy { catalog.geminiLiveModels.map(::toOption) }
    val mistralModels by lazy { catalog.mistralModels.map(::toOption) }

    fun getModelsForProvider(provider: AiProvider): List<ModelOption> =
        catalog.modelsFor(provider).map(::toOption)

    private val byId: Map<String, ModelOption> by lazy { allModels.associateBy { it.id } }

    fun findModel(modelId: String): ModelOption? = byId[modelId]

    val allModels: List<ModelOption> by lazy {
        AiProvider.entries.flatMap { getModelsForProvider(it) }
    }
}

/**
 * Alibaba Cloud Model Studio regional endpoints.
 *
 * NOTE: regional hostnames follow the documented DashScope naming scheme;
 * verify the current hostname for each region in the Alibaba Cloud Model
 * Studio docs before relying on it (last checked 2026-08-02).
 */
object AlibabaRegions {
    const val CHINA = "china"
    const val SINGAPORE = "singapore"
    const val US = "us"
    const val GERMANY = "germany"
    const val JAPAN = "japan"
    const val CUSTOM = "custom"

    val all = listOf(CHINA, SINGAPORE, US, GERMANY, JAPAN, CUSTOM)

    fun baseUrlFor(region: String, customBaseUrl: String = ""): String = when (region) {
        CHINA -> "https://dashscope.aliyuncs.com/compatible-mode/v1/"
        SINGAPORE -> "https://dashscope-intl.aliyuncs.com/compatible-mode/v1/"
        US -> "https://dashscope-us.aliyuncs.com/compatible-mode/v1/"
        GERMANY -> "https://dashscope-de.aliyuncs.com/compatible-mode/v1/"
        JAPAN -> "https://dashscope-jp.aliyuncs.com/compatible-mode/v1/"
        CUSTOM -> customBaseUrl.ifBlank { baseUrlFor(CHINA) }
        else -> baseUrlFor(CHINA)
    }
}


/**
 * Provider-specific configuration
 */
data class ProviderConfig(
    val apiKey: String = "",
    val baseUrl: String = "",
    val customModelName: String = ""
)

/**
 * API Settings
 */
data class ApiSettings(
    val glassesDisplayMetrics: com.example.rokidcommon.protocol.GlassesDisplayMetrics? = null,
    val glassesDisplayConfig: com.example.rokidcommon.protocol.GlassesDisplayConfig =
        com.example.rokidcommon.protocol.GlassesDisplayConfig(),
    // AI Chat settings
    val aiProvider: AiProvider = AiProvider.GEMINI,
    val aiModelId: String = "gemini-3.8-flash",

    // Per-provider model memory: provider name -> last selected model ID.
    // Switching providers restores the model the user picked for that provider.
    val providerModelIds: Map<String, String> = emptyMap(),

    // Optional System One model routing for text requests. Empty slots use the
    // primary model; credentials for Jev and Laya are stored with the other keys.
    val decisionRoutingEnabled: Boolean = false,
    val decisionBackend: DecisionBackend = DecisionBackend.JEV,
    val decisionGeminiModel: String = "gemini-3.8-flash",
    val decisionOpenaiModel: String = "gpt-6-luna",
    val jevApiKey: String = "",
    val layaBaseUrl: String = "",
    val layaApiKey: String = "",
    val fastRoutingModel: RoutingModel? = null,
    val balancedRoutingModel: RoutingModel? = null,
    val qualityRoutingModel: RoutingModel? = null,

    // API Keys for each provider
    val geminiApiKey: String = "",
    val openaiApiKey: String = "",
    val anthropicApiKey: String = "",
    val deepseekApiKey: String = "",
    val groqApiKey: String = "",
    val xaiApiKey: String = "",
    val alibabaApiKey: String = "",
    val zhipuApiKey: String = "",
    val baiduApiKey: String = "",
    val baiduSecretKey: String = "",  // Baidu legacy: API Key + Secret Key OAuth
    // Baidu Qianfan v2: single bearer API key (preferred). When blank and the
    // legacy key pair exists, legacy mode is used (see baiduUseLegacyAuth).
    val baiduQianfanApiKey: String = "",
    val baiduUseLegacyAuth: Boolean = false,
    val perplexityApiKey: String = "",
    val moonshotApiKey: String = "",
    val mistralApiKey: String = "",
    val customApiKey: String = "",

    // AnythingLLM settings
    val anythingllmServerUrl: String = "",
    val anythingllmApiKey: String = "",
    val anythingllmWorkspaceSlug: String = "",

    // Custom base URLs (for providers that support it)
    val customBaseUrl: String = "http://localhost:11434/v1/",
    val customModelName: String = "llama4",
    // Custom endpoint protocol: "chat_completions" (default) or "responses"
    val customProtocol: String = "chat_completions",
    // Custom endpoint models-list path (default "models")
    val customModelsPath: String = "models",
    // Manual capability overrides for the Custom model: e.g. "vision", "audio_input"
    val customCapabilityOverrides: Set<String> = emptySet(),

    // Alibaba Cloud Model Studio region (see AlibabaRegions)
    val alibabaRegion: String = AlibabaRegions.CHINA,
    val alibabaCustomBaseUrl: String = "",

    // Speech recognition settings
    val sttProvider: SttProvider = SttProvider.GEMINI,
    // Empty string = SettingsRepository will resolve to device locale on first run.
    // TODO: UI should display the resolved locale tag so users know what is active.
    val speechLanguage: String = "",
    
    // === STT Provider Credentials ===
    
    // Deepgram
    val deepgramApiKey: String = "",
    
    // AssemblyAI
    val assemblyaiApiKey: String = "",
    
    // Google Cloud Speech-to-Text
    val gcpProjectId: String = "",
    val gcpApiKey: String = "",
    val gcpServiceAccountJson: String = "",
    val gcpUseServiceAccount: Boolean = false,
    
    // Microsoft Azure AI Speech
    val azureSpeechKey: String = "",
    val azureSpeechRegion: String = "",
    
    // Amazon Transcribe
    val awsAccessKeyId: String = "",
    val awsSecretAccessKey: String = "",
    val awsRegion: String = "us-east-1",
    
    // IBM Watson Speech to Text
    val ibmApiKey: String = "",
    val ibmServiceUrl: String = "",
    
    // iFLYTEK (Xunfei)
    val iflytekAppId: String = "",
    val iflytekApiKey: String = "",
    val iflytekApiSecret: String = "",
    
    // Huawei Cloud SIS
    val huaweiAk: String = "",
    val huaweiSk: String = "",
    val huaweiRegion: String = "cn-north-4",
    val huaweiProjectId: String = "",
    
    // Volcengine (ByteDance)
    val volcengineAk: String = "",
    val volcangineSk: String = "",
    val volcengineAppId: String = "",
    
    // Alibaba Cloud ASR
    val aliyunAccessKeyId: String = "",
    val aliyunAccessKeySecret: String = "",
    val aliyunAppKey: String = "",
    
    // Tencent Cloud ASR
    val tencentSecretId: String = "",
    val tencentSecretKey: String = "",
    val tencentAppId: String = "",
    val tencentEngineModelType: String = "16k_zh",
    
    // Baidu Cloud ASR
    val baiduAsrApiKey: String = "",
    val baiduAsrSecretKey: String = "",
    
    // Rev.ai
    val revaiAccessToken: String = "",
    
    // Speechmatics
    val speechmaticsApiKey: String = "",
    
    // Otter.ai
    val otteraiApiKey: String = "",
    
    // LLM Generation Parameters
    val temperature: Float = 0.7f,
    val maxTokens: Int = 2048,
    val topP: Float = 1.0f,
    val frequencyPenalty: Float = 0.0f,
    val presencePenalty: Float = 0.0f,
    
    // AI response settings
    // Empty string = SettingsRepository will resolve to device locale on first run.
    // TODO: UI should display the resolved locale tag so users know what is active.
    val responseLanguage: String = "",
    // Note: The default value is set to empty string here. 
    // The actual default (localized) is provided by SettingsRepository.getDefaultSystemPrompt()
    val systemPrompt: String = "",
    
    // TTS settings
    val ttsProvider: TtsProvider = TtsProvider.EDGE_TTS,
    val ttsVoiceOverride: String = "",
    val ttsSpeechRate: Float = 1.0f,
    val ttsPitch: Float = 0.0f,
    val systemTtsSpeechRate: Float = 1.0f,
    val systemTtsPitch: Float = 1.0f,

    // Recording settings
    // Auto-analyze recordings with AI after stopping (default: true)
    val autoAnalyzeRecordings: Boolean = true,
    
    // Glasses push settings
    // Send text chat AI responses to glasses display (default: true)
    val pushChatToGlasses: Boolean = true,
    // Send phone recording results (transcript + AI response) to glasses display (default: true)
    val pushRecordingToGlasses: Boolean = true
) {
    /** Baidu: Qianfan v2 key preferred; legacy key pair when in legacy mode. */
    fun getBaiduEffectiveApiKey(): String =
        if (baiduUseLegacyAuth) baiduApiKey else baiduQianfanApiKey.ifBlank { baiduApiKey }

    /** True when Baidu should use the legacy API Key + Secret Key OAuth flow. */
    fun isBaiduLegacyMode(): Boolean =
        baiduUseLegacyAuth || (baiduQianfanApiKey.isBlank() && baiduApiKey.isNotBlank() && baiduSecretKey.isNotBlank())

    /**
     * Get current AI provider's API Key
     */
    fun getCurrentApiKey(): String = getApiKeyForProvider(aiProvider)

    /**
     * Get API Key for specified provider
     */
    fun getApiKeyForProvider(provider: AiProvider): String {
        return when (provider) {
            AiProvider.GEMINI -> geminiApiKey
            AiProvider.OPENAI -> openaiApiKey
            AiProvider.ANTHROPIC -> anthropicApiKey
            AiProvider.DEEPSEEK -> deepseekApiKey
            AiProvider.GROQ -> groqApiKey
            AiProvider.XAI -> xaiApiKey
            AiProvider.ALIBABA -> alibabaApiKey
            AiProvider.ZHIPU -> zhipuApiKey
            AiProvider.BAIDU -> getBaiduEffectiveApiKey()
            AiProvider.PERPLEXITY -> perplexityApiKey
            AiProvider.MOONSHOT -> moonshotApiKey
            AiProvider.MISTRAL -> mistralApiKey
            AiProvider.GEMINI_LIVE -> geminiApiKey  // Shares Gemini API key
            AiProvider.ANYTHINGLLM -> anythingllmApiKey
            AiProvider.LOCAL_GEMMA -> ""  // On-device: no API key
            AiProvider.CUSTOM -> customApiKey
        }
    }

    /**
     * Get base URL for current provider
     */
    fun getCurrentBaseUrl(): String {
        return when (aiProvider) {
            AiProvider.CUSTOM -> customBaseUrl.ifBlank { AiProvider.CUSTOM.defaultBaseUrl }
            AiProvider.ANYTHINGLLM -> anythingllmServerUrl.ifBlank { AiProvider.ANYTHINGLLM.defaultBaseUrl }
            AiProvider.ALIBABA -> AlibabaRegions.baseUrlFor(alibabaRegion, alibabaCustomBaseUrl)
            else -> aiProvider.defaultBaseUrl
        }
    }

    /**
     * Get model ID for current provider
     */
    fun getCurrentModelId(): String = getModelIdForProvider(aiProvider)

    /**
     * Get the model the user last selected for [provider]; falls back to the
     * legacy single [aiModelId] for the active provider, then to the verified
     * fallback default.
     */
    fun getModelIdForProvider(provider: AiProvider): String {
        // CUSTOM: honour the per-provider selection written by withModelForProvider()
        // first, then the explicit custom model name; never leak another
        // provider's aiModelId into a Custom endpoint request.
        if (provider == AiProvider.CUSTOM) {
            providerModelIds[AiProvider.CUSTOM.name]?.takeIf { it.isNotBlank() }?.let { return it }
            return customModelName.ifBlank {
                io.github.zero2005x.glassesaicompanion.ai.catalog.FallbackModelCatalog.defaultModelFor(provider)
            }
        }
        providerModelIds[provider.name]?.takeIf { it.isNotBlank() }?.let { return it }
        if (provider == aiProvider && aiModelId.isNotBlank()) return aiModelId
        return io.github.zero2005x.glassesaicompanion.ai.catalog.FallbackModelCatalog.defaultModelFor(provider)
    }

    /** Return a copy with [modelId] stored as the selection for [provider]. */
    fun withModelForProvider(provider: AiProvider, modelId: String): ApiSettings {
        val newMap = providerModelIds.toMutableMap().apply { put(provider.name, modelId) }
        return copy(
            providerModelIds = newMap,
            aiModelId = if (provider == aiProvider) modelId else aiModelId
        )
    }

    /** Apply legacy model-ID migrations (e.g. DeepSeek V3 aliases → V4). */
    fun migrateLegacyModelIds(): ApiSettings {
        // The catalog keys migrations by provider; flatten to a single global
        // id→id map and apply it to every provider's stored model id as well
        // as the legacy active id.
        val migrations = io.github.zero2005x.glassesaicompanion.ai.catalog.FallbackModelCatalog.legacyModelMigration
            .values.flatMap { it.entries }.associate { it.toPair() }
        val newMap = providerModelIds.mapValues { (_, id) -> migrations[id] ?: id }
        val newActive = migrations[aiModelId] ?: aiModelId
        fun migrateSlot(slot: RoutingModel?): RoutingModel? = slot?.let {
            val replacements = io.github.zero2005x.glassesaicompanion.ai.catalog.FallbackModelCatalog
                .legacyModelMigration[it.provider]
            it.copy(modelId = replacements?.get(it.modelId) ?: it.modelId)
        }
        val newFast = migrateSlot(fastRoutingModel)
        val newBalanced = migrateSlot(balancedRoutingModel)
        val newQuality = migrateSlot(qualityRoutingModel)
        return if (newMap != providerModelIds || newActive != aiModelId ||
            newFast != fastRoutingModel || newBalanced != balancedRoutingModel || newQuality != qualityRoutingModel
        ) {
            copy(providerModelIds = newMap, aiModelId = newActive,
                fastRoutingModel = newFast, balancedRoutingModel = newBalanced,
                qualityRoutingModel = newQuality)
        } else this
    }

    /**
     * Check if settings are valid
     */
    fun isValid(): Boolean {
        return when (aiProvider) {
            AiProvider.CUSTOM -> customBaseUrl.isNotBlank() && isValidUrl(customBaseUrl)
            AiProvider.BAIDU ->
                if (isBaiduLegacyMode()) baiduApiKey.isNotBlank() && baiduSecretKey.isNotBlank()
                else baiduQianfanApiKey.isNotBlank()
            AiProvider.ANYTHINGLLM ->
                anythingllmServerUrl.isNotBlank() && anythingllmApiKey.isNotBlank() && anythingllmWorkspaceSlug.isNotBlank()
            // On-device provider needs no cloud credentials; the service layer
            // surfaces a clear error if no model file is actually installed.
            AiProvider.LOCAL_GEMMA -> true
            else -> getCurrentApiKey().isNotBlank()
        }
    }

    /**
     * Check if specified provider has API Key configured
     */
    fun isProviderConfigured(provider: AiProvider): Boolean {
        return when (provider) {
            // customBaseUrl has a non-blank default, so require an explicit API key
            // or a user-changed (non-default) URL before treating CUSTOM as configured
            AiProvider.CUSTOM -> customApiKey.isNotBlank() ||
                (customBaseUrl != AiProvider.CUSTOM.defaultBaseUrl && isValidUrl(customBaseUrl))
            AiProvider.BAIDU ->
                baiduQianfanApiKey.isNotBlank() || (baiduApiKey.isNotBlank() && baiduSecretKey.isNotBlank())
            AiProvider.GEMINI_LIVE -> geminiApiKey.isNotBlank()  // Shares Gemini API key
            AiProvider.ANYTHINGLLM ->
                anythingllmServerUrl.isNotBlank() && anythingllmApiKey.isNotBlank() && anythingllmWorkspaceSlug.isNotBlank()
            AiProvider.LOCAL_GEMMA -> true  // On-device: no cloud credential needed
            else -> getApiKeyForProvider(provider).isNotBlank()
        }
    }

    /**
     * Check if any speech recognition service is available
     */
    fun hasSpeechServiceConfigured(): Boolean {
        return hasSelectedSttCredentials()
    }

    /**
     * Get list of configured STT providers
     */
    fun getConfiguredSttProviders(): List<AiProvider> {
        return SPEECH_CAPABLE_STT_PROVIDERS.filter { isProviderConfigured(it) }
    }
    
    /**
     * Get list of missing API keys for core functionality
     */
    fun getMissingApiKeys(): List<AiProvider> {
        val missing = mutableListOf<AiProvider>()
        if (!isProviderConfigured(aiProvider)) {
            missing.add(aiProvider)
        }
        return missing
    }
    
    /**
     * Check if any API key is configured at all
     * Returns true if at least one provider has an API key set
     */
    fun hasAnyApiKeyConfigured(): Boolean {
        return geminiApiKey.isNotBlank() ||
               openaiApiKey.isNotBlank() ||
               anthropicApiKey.isNotBlank() ||
               deepseekApiKey.isNotBlank() ||
               groqApiKey.isNotBlank() ||
               xaiApiKey.isNotBlank() ||
               alibabaApiKey.isNotBlank() ||
               zhipuApiKey.isNotBlank() ||
               (baiduQianfanApiKey.isNotBlank() || (baiduApiKey.isNotBlank() && baiduSecretKey.isNotBlank())) ||
               perplexityApiKey.isNotBlank() ||
               moonshotApiKey.isNotBlank() ||
               mistralApiKey.isNotBlank() ||
               (anythingllmApiKey.isNotBlank() && anythingllmServerUrl.isNotBlank()) ||
               // customBaseUrl is non-blank by default; only count CUSTOM when the
               // user set an API key or changed the URL away from the default
               (customApiKey.isNotBlank() ||
                   (customBaseUrl.isNotBlank() && customBaseUrl != AiProvider.CUSTOM.defaultBaseUrl))
    }
    
    /**
     * Get list of all configured providers
     */
    fun getConfiguredProviders(): List<AiProvider> {
        return AiProvider.entries.filter { isProviderConfigured(it) }
    }
    
    /**
     * Validate URL format
     */
    private fun isValidUrl(url: String): Boolean = isValidHttpUrl(url)
}

/**
 * Settings validation result
 */
sealed class SettingsValidationResult {
    object Valid : SettingsValidationResult()
    data class MissingApiKey(val provider: AiProvider) : SettingsValidationResult()
    data class MissingSpeechService(val requiredProviders: List<AiProvider>) : SettingsValidationResult()
    data class InvalidConfiguration(val message: String) : SettingsValidationResult()
}

/**
 * Validate settings for specific use case
 */
fun ApiSettings.validateForChat(): SettingsValidationResult {
    return when {
        aiProvider == AiProvider.CUSTOM && !isValidHttpUrl(customBaseUrl) ->
            SettingsValidationResult.InvalidConfiguration("Invalid custom provider URL")
        aiProvider == AiProvider.BAIDU && !isProviderConfigured(AiProvider.BAIDU) ->
            SettingsValidationResult.MissingApiKey(AiProvider.BAIDU)
        aiProvider == AiProvider.ANYTHINGLLM && !isValid() ->
            SettingsValidationResult.InvalidConfiguration(
                "Please configure server URL, API key, and workspace slug for AnythingLLM"
            )
        // On-device inference requires no API key; a missing model file is
        // reported later by LocalGemmaService, not as a missing-key error.
        aiProvider == AiProvider.LOCAL_GEMMA ->
            SettingsValidationResult.Valid
        aiProvider == AiProvider.CUSTOM -> SettingsValidationResult.Valid
        getCurrentApiKey().isBlank() ->
            SettingsValidationResult.MissingApiKey(aiProvider)
        else -> SettingsValidationResult.Valid
    }
}

/**
 * Validate settings for speech recognition
 */
fun ApiSettings.validateForSpeech(): SettingsValidationResult {
    if (hasSelectedSttCredentials()) {
        return SettingsValidationResult.Valid
    }

    val builtInProvider = when (sttProvider) {
        SttProvider.GEMINI -> AiProvider.GEMINI
        SttProvider.OPENAI_WHISPER -> AiProvider.OPENAI
        SttProvider.GROQ_WHISPER -> AiProvider.GROQ
        else -> null
    }
    return if (builtInProvider != null) {
        SettingsValidationResult.MissingSpeechService(listOf(builtInProvider))
    } else {
        SettingsValidationResult.InvalidConfiguration(
            "Missing credentials for the selected speech provider: ${sttProvider.name}"
        )
    }
}

private fun ApiSettings.hasSelectedSttCredentials(): Boolean = when (sttProvider) {
    SttProvider.GEMINI -> geminiApiKey.isNotBlank()
    SttProvider.OPENAI_WHISPER -> openaiApiKey.isNotBlank()
    SttProvider.GROQ_WHISPER -> groqApiKey.isNotBlank()
    else -> toSttCredentials().hasCredentialsForProvider(sttProvider)
}

/**
 * Convert ApiSettings to SttCredentials for use with SttServiceFactory
 */
fun ApiSettings.toSttCredentials(): io.github.zero2005x.glassesaicompanion.service.stt.SttCredentials {
    return io.github.zero2005x.glassesaicompanion.service.stt.SttCredentials(
        selectedProvider = sttProvider.name,
        deepgramApiKey = deepgramApiKey,
        assemblyaiApiKey = assemblyaiApiKey,
        gcpProjectId = gcpProjectId,
        gcpApiKey = gcpApiKey,
        gcpServiceAccountJson = gcpServiceAccountJson,
        gcpUseServiceAccount = gcpUseServiceAccount,
        azureSpeechKey = azureSpeechKey,
        azureSpeechRegion = azureSpeechRegion,
        awsAccessKeyId = awsAccessKeyId,
        awsSecretAccessKey = awsSecretAccessKey,
        awsRegion = awsRegion,
        ibmApiKey = ibmApiKey,
        ibmServiceUrl = ibmServiceUrl,
        iflytekAppId = iflytekAppId,
        iflytekApiKey = iflytekApiKey,
        iflytekApiSecret = iflytekApiSecret,
        huaweiAk = huaweiAk,
        huaweiSk = huaweiSk,
        huaweiRegion = huaweiRegion,
        huaweiProjectId = huaweiProjectId,
        volcengineAk = volcengineAk,
        volcangineSk = volcangineSk,
        volcengineAppId = volcengineAppId,
        aliyunAccessKeyId = aliyunAccessKeyId,
        aliyunAccessKeySecret = aliyunAccessKeySecret,
        aliyunAppKey = aliyunAppKey,
        tencentSecretId = tencentSecretId,
        tencentSecretKey = tencentSecretKey,
        tencentAppId = tencentAppId,
        tencentEngineModelType = tencentEngineModelType,
        baiduAsrApiKey = baiduAsrApiKey,
        baiduAsrSecretKey = baiduAsrSecretKey,
        revaiAccessToken = revaiAccessToken,
        speechmaticsApiKey = speechmaticsApiKey,
        otteraiApiKey = otteraiApiKey
    )
}

/**
 * Check whether [url] uses an http(s) scheme. Single implementation shared by
 * the ApiSettings member and the file-level validation extensions.
 */
private fun isValidHttpUrl(url: String): Boolean =
    url.trim().let { it.startsWith("http://") || it.startsWith("https://") }
