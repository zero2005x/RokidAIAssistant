package com.example.rokidphone.data

import androidx.annotation.StringRes
import com.example.rokidphone.R

/**
 * AI Service Providers
 */
enum class AiProvider(
    @StringRes val displayNameResId: Int,
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
        supportsVision = true   // GPT-4o Vision
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
        supportsVision = true
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
        website = "https://open.bigmodel.cn",
        defaultBaseUrl = "https://open.bigmodel.cn/api/paas/v4/",
        isOpenAiCompatible = true,
        supportsSpeech = false,
        supportsVision = true
    ),
    BAIDU(
        displayNameResId = R.string.provider_baidu,
        description = "Ernie Bot / Wenxin, requires API Key + Secret Key",
        website = "https://cloud.baidu.com",
        defaultBaseUrl = "https://aip.baidubce.com/rpc/2.0/ai_custom/v1/wenxinworkshop/chat/",
        isOpenAiCompatible = false,
        supportsSpeech = false,
        supportsVision = false
    ),
    PERPLEXITY(
        displayNameResId = R.string.provider_perplexity,
        description = "Real-time web search and reasoning (Sonar series)",
        website = "https://www.perplexity.ai",
        defaultBaseUrl = "https://api.perplexity.ai/",
        isOpenAiCompatible = true,
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
        fun fromName(name: String): AiProvider {
            return entries.find { it.name == name } ?: GEMINI
        }
    }
    
    /**
     * Check if this provider allows custom base URL
     */
    fun allowsCustomBaseUrl(): Boolean = this == CUSTOM
    
    /**
     * Check if this provider requires API key
     */
    fun requiresApiKey(): Boolean = this != CUSTOM
    
    /**
     * Check if this provider requires a secret key (Baidu OAuth)
     */
    fun requiresSecretKey(): Boolean = this == BAIDU
}

/**
 * Model Options
 */
data class ModelOption(
    val id: String,
    val displayName: String,
    val provider: AiProvider,
    val supportsAudio: Boolean = false,
    val description: String = ""
)

/**
 * Default Model List
 */
object AvailableModels {
    val geminiModels = listOf(
        ModelOption(
            id = "gemini-2.5-pro",
            displayName = "Gemini 2.5 Pro",
            provider = AiProvider.GEMINI,
            supportsAudio = true,
            description = "Google's most capable AI model, native multimodal reasoning"
        ),
        ModelOption(
            id = "gemini-2.5-flash",
            displayName = "Gemini 2.5 Flash",
            provider = AiProvider.GEMINI,
            supportsAudio = true,
            description = "Optimized for speed and efficiency, supports high-volume tasks"
        ),
        ModelOption(
            id = "gemini-2.5-flash-lite",
            displayName = "Gemini 2.5 Flash-Lite",
            provider = AiProvider.GEMINI,
            supportsAudio = true,
            description = "Extremely cost-effective lightweight model for low-latency applications"
        )
    )
    
    val openaiModels = listOf(
        ModelOption(
            id = "gpt-5",
            displayName = "GPT-5 (Omni)",
            provider = AiProvider.OPENAI,
            supportsAudio = false,
            description = "Flagship multimodal model with state-of-the-art vision analysis"
        ),
        ModelOption(
            id = "gpt-5.2",
            displayName = "GPT-5.2",
            provider = AiProvider.OPENAI,
            supportsAudio = false,
            description = "OpenAI's latest flagship. Unmatched general intelligence"
        ),
        ModelOption(
            id = "gpt-4o",
            displayName = "GPT-4o",
            provider = AiProvider.OPENAI,
            supportsAudio = false,
            description = "High-performance multimodal model, excellent for OCR and scene understanding"
        ),
        ModelOption(
            id = "gpt-4o-mini",
            displayName = "GPT-4o Mini",
            provider = AiProvider.OPENAI,
            supportsAudio = false,
            description = "Cost-efficient vision model, good for simple object detection"
        ),
        ModelOption(
            id = "o3-pro",
            displayName = "o3 Pro (Reasoning)",
            provider = AiProvider.OPENAI,
            supportsAudio = false,
            description = "Advanced reasoning model for complex science, coding, and math"
        ),
        ModelOption(
            id = "o4-mini",
            displayName = "o4 Mini",
            provider = AiProvider.OPENAI,
            supportsAudio = false,
            description = "Cost-effective reasoning model for STEM and technical analysis"
        )
    )
    
    val anthropicModels = listOf(
        ModelOption(
            id = "claude-opus-4.5",
            displayName = "Claude 4.5 Opus",
            provider = AiProvider.ANTHROPIC,
            supportsAudio = false,
            description = "Anthropic's most powerful model, excelling in complex nuance"
        ),
        ModelOption(
            id = "claude-sonnet-4.5",
            displayName = "Claude 4.5 Sonnet",
            provider = AiProvider.ANTHROPIC,
            supportsAudio = false,
            description = "The best balance of intelligence and speed for enterprise workloads"
        ),
        ModelOption(
            id = "claude-haiku-4.5",
            displayName = "Claude 4.5 Haiku",
            provider = AiProvider.ANTHROPIC,
            supportsAudio = false,
            description = "Blazing fast model for instant responses and simple tasks"
        )
    )
    
    val deepseekModels = listOf(
        ModelOption(
            id = "deepseek-chat",
            displayName = "DeepSeek V3.2",
            provider = AiProvider.DEEPSEEK,
            supportsAudio = false,
            description = "Latest V3.2 generation. Highly capable general-purpose model"
        ),
        ModelOption(
            id = "deepseek-reasoner",
            displayName = "DeepSeek R1 (2025)",
            provider = AiProvider.DEEPSEEK,
            supportsAudio = false,
            description = "Specialized reasoning model (Chain of Thought) for complex logic"
        ),
        ModelOption(
            id = "deepseek-vl-3",
            displayName = "DeepSeek VL 3",
            provider = AiProvider.DEEPSEEK,
            supportsAudio = false,
            description = "Dedicated vision-language model decoupled from main chat for cost efficiency"
        )
    )
    
    val groqModels = listOf(
        ModelOption(
            id = "llama-4-70b-versatile",
            displayName = "Llama 4 70B",
            provider = AiProvider.GROQ,
            supportsAudio = false,
            description = "Meta's next-gen open model running at extreme speeds on Groq LPUs"
        ),
        ModelOption(
            id = "llama-3.3-70b-versatile",
            displayName = "Llama 3.3 70B",
            provider = AiProvider.GROQ,
            supportsAudio = false,
            description = "Reliable and powerful open model, optimized for tool use"
        ),
        ModelOption(
            id = "qwen-3-32b",
            displayName = "Qwen 3 32B",
            provider = AiProvider.GROQ,
            supportsAudio = false,
            description = "Powerful mid-sized model, excellent at coding and multilingual tasks"
        ),
        ModelOption(
            id = "llama-4-vision-90b",
            displayName = "Llama 4 Vision 90B",
            provider = AiProvider.GROQ,
            supportsAudio = false,
            description = "Meta's open multimodal powerhouse running at extreme speeds on Groq"
        ),
        ModelOption(
            id = "llama-3.2-11b-vision-preview",
            displayName = "Llama 3.2 11B Vision",
            provider = AiProvider.GROQ,
            supportsAudio = false,
            description = "Lightweight vision model perfect for fast image description"
        ),
        ModelOption(
            id = "llava-v1.8-72b",
            displayName = "LLaVA v1.8 72B",
            provider = AiProvider.GROQ,
            supportsAudio = false,
            description = "Latest iteration of popular open-source LLaVA, highly accurate visual reasoning"
        )
    )
    
    val customModels = listOf(
        ModelOption(
            id = "custom",
            displayName = "Custom Model",
            provider = AiProvider.CUSTOM,
            supportsAudio = false,
            description = "User-defined model name"
        ),
        ModelOption(
            id = "llama4",
            displayName = "Llama 4 (Ollama)",
            provider = AiProvider.CUSTOM,
            supportsAudio = false,
            description = "Local Llama 4 model via Ollama"
        ),
        ModelOption(
            id = "deepseek-r1",
            displayName = "DeepSeek R1 (Ollama)",
            provider = AiProvider.CUSTOM,
            supportsAudio = false,
            description = "Local DeepSeek reasoning model"
        ),
        ModelOption(
            id = "minicpm-v-2.6",
            displayName = "MiniCPM-V 2.6 (Ollama)",
            provider = AiProvider.CUSTOM,
            supportsAudio = false,
            description = "Efficient local vision model, GPT-4V level performance on mobile parameters"
        ),
        ModelOption(
            id = "moondream2",
            displayName = "Moondream 2 (Ollama)",
            provider = AiProvider.CUSTOM,
            supportsAudio = false,
            description = "Tiny vision model designed to run on almost any hardware"
        )
    )
    
    val xaiModels = listOf(
        ModelOption(
            id = "grok-4",
            displayName = "Grok 4",
            provider = AiProvider.XAI,
            supportsAudio = false,
            description = "xAI's smartest model with real-time X platform knowledge"
        ),
        ModelOption(
            id = "grok-4-fast",
            displayName = "Grok 4 Fast",
            provider = AiProvider.XAI,
            supportsAudio = false,
            description = "Low-latency version of Grok 4 for quick interactions"
        ),
        ModelOption(
            id = "grok-3",
            displayName = "Grok 3",
            provider = AiProvider.XAI,
            supportsAudio = false,
            description = "Previous flagship, stable performance with strong reasoning"
        ),
        ModelOption(
            id = "grok-2-vision-1212",
            displayName = "Grok 2 Vision",
            provider = AiProvider.XAI,
            supportsAudio = false,
            description = "xAI's first official vision-capable model, strong at document analysis"
        )
    )
    
    val alibabaModels = listOf(
        ModelOption(
            id = "qwen3-max",
            displayName = "Qwen 3 Max",
            provider = AiProvider.ALIBABA,
            supportsAudio = false,
            description = "Alibaba's most powerful model, top-tier performance"
        ),
        ModelOption(
            id = "qwen3-plus",
            displayName = "Qwen 3 Plus",
            provider = AiProvider.ALIBABA,
            supportsAudio = false,
            description = "Balanced model offering strong performance at a lower cost"
        ),
        ModelOption(
            id = "qwen3-turbo",
            displayName = "Qwen 3 Turbo",
            provider = AiProvider.ALIBABA,
            supportsAudio = false,
            description = "High-speed model optimized for simple queries and high throughput"
        ),
        ModelOption(
            id = "qwen3-vl-max",
            displayName = "Qwen 3 VL Max",
            provider = AiProvider.ALIBABA,
            supportsAudio = false,
            description = "Flagship vision-language model. State-of-the-art OCR and diagram understanding"
        ),
        ModelOption(
            id = "qwen3-vl-plus",
            displayName = "Qwen 3 VL Plus",
            provider = AiProvider.ALIBABA,
            supportsAudio = false,
            description = "Optimized visual understanding model, balancing resolution and speed"
        ),
        ModelOption(
            id = "qwen-vl-max",
            displayName = "Qwen-VL Max",
            provider = AiProvider.ALIBABA,
            supportsAudio = false,
            description = "Top-tier visual understanding from Alibaba (Qwen 2.5/3 VL based)"
        ),
        ModelOption(
            id = "qwen-vl-plus",
            displayName = "Qwen-VL Plus",
            provider = AiProvider.ALIBABA,
            supportsAudio = false,
            description = "Balanced speed/performance for vision tasks"
        )
    )
    
    val zhipuModels = listOf(
        ModelOption(
            id = "glm-4.7",
            displayName = "GLM-4.7",
            provider = AiProvider.ZHIPU,
            supportsAudio = false,
            description = "Zhipu's 2026 flagship. State-of-the-art Chinese/English bilingual"
        ),
        ModelOption(
            id = "glm-4-plus",
            displayName = "GLM-4 Plus",
            provider = AiProvider.ZHIPU,
            supportsAudio = false,
            description = "Enhanced version of GLM-4 with better long-context handling"
        ),
        ModelOption(
            id = "glm-4-flash",
            displayName = "GLM-4 Flash",
            provider = AiProvider.ZHIPU,
            supportsAudio = false,
            description = "Free/Low-cost high speed model"
        ),
        ModelOption(
            id = "glm-4v-plus",
            displayName = "GLM-4V Plus",
            provider = AiProvider.ZHIPU,
            supportsAudio = false,
            description = "Flagship multimodal model for Chinese UI understanding and video analysis"
        ),
        ModelOption(
            id = "glm-4v-flash",
            displayName = "GLM-4V Flash",
            provider = AiProvider.ZHIPU,
            supportsAudio = false,
            description = "High-speed vision model, ideal for real-time video frame processing"
        ),
        ModelOption(
            id = "glm-4v",
            displayName = "GLM-4V",
            provider = AiProvider.ZHIPU,
            supportsAudio = false,
            description = "Standard vision model for image dialogue"
        )
    )
    
    val baiduModels = listOf(
        ModelOption(
            id = "ernie-5.0",
            displayName = "ERNIE 5.0",
            provider = AiProvider.BAIDU,
            supportsAudio = false,
            description = "Baidu's latest foundation model, massive knowledge base update"
        ),
        ModelOption(
            id = "ernie-x1",
            displayName = "ERNIE X1 (Reasoning)",
            provider = AiProvider.BAIDU,
            supportsAudio = false,
            description = "Specialized model for complex reasoning and logic tasks"
        ),
        ModelOption(
            id = "ernie-4.5-turbo",
            displayName = "ERNIE 4.5 Turbo",
            provider = AiProvider.BAIDU,
            supportsAudio = false,
            description = "Speed-optimized version of the highly capable Ernie 4.5"
        ),
        ModelOption(
            id = "ernie-vision-pro",
            displayName = "ERNIE Vision Pro",
            provider = AiProvider.BAIDU,
            supportsAudio = false,
            description = "Enterprise-grade vision endpoint, specialized in document and receipt parsing"
        ),
        ModelOption(
            id = "ERNIE-Bot-4",
            displayName = "ERNIE 4.0 (Vision)",
            provider = AiProvider.BAIDU,
            supportsAudio = false,
            description = "Flagship model supports image inputs in latest API versions"
        ),
        ModelOption(
            id = "Fuyu-8B",
            displayName = "Fuyu-8B",
            provider = AiProvider.BAIDU,
            supportsAudio = false,
            description = "Specialized vision-language model available on Qianfan platform"
        )
    )
    
    val perplexityModels = listOf(
        ModelOption(
            id = "sonar-pro",
            displayName = "Sonar Pro",
            provider = AiProvider.PERPLEXITY,
            supportsAudio = false,
            description = "Enterprise-grade model with high-fidelity web search and complex reasoning"
        ),
        ModelOption(
            id = "sonar",
            displayName = "Sonar",
            provider = AiProvider.PERPLEXITY,
            supportsAudio = false,
            description = "Optimized for speed and efficiency, ideal for real-time search queries"
        ),
        ModelOption(
            id = "sonar-reasoning-pro",
            displayName = "Sonar Reasoning Pro",
            provider = AiProvider.PERPLEXITY,
            supportsAudio = false,
            description = "Advanced reasoning model powered by DeepSeek-R1, showing Chain-of-Thought"
        ),
        ModelOption(
            id = "sonar-reasoning",
            displayName = "Sonar Reasoning",
            provider = AiProvider.PERPLEXITY,
            supportsAudio = false,
            description = "Cost-effective reasoning model for logical queries with web grounding"
        ),
        ModelOption(
            id = "r1-1776",
            displayName = "Perplexity R1",
            provider = AiProvider.PERPLEXITY,
            supportsAudio = false,
            description = "DeepSeek R1 distilled by Perplexity for enhanced reasoning"
        )
    )
    
    fun getModelsForProvider(provider: AiProvider): List<ModelOption> {
        return when (provider) {
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
            AiProvider.CUSTOM -> customModels
        }
    }
    
    fun findModel(modelId: String): ModelOption? {
        return allModels.find { it.id == modelId }
    }
    
    val allModels: List<ModelOption>
        get() = geminiModels + openaiModels + anthropicModels + deepseekModels + groqModels + 
                xaiModels + alibabaModels + zhipuModels + baiduModels + perplexityModels + customModels
}

/**
 * Speech Recognition Service
 */
enum class SpeechService(@StringRes val displayNameResId: Int) {
    GEMINI_AUDIO(R.string.speech_service_gemini),
    OPENAI_WHISPER(R.string.speech_service_openai_whisper),
    GOOGLE_CLOUD_STT(R.string.speech_service_google_cloud);
    
    companion object {
        fun fromName(name: String): SpeechService {
            return entries.find { it.name == name } ?: GEMINI_AUDIO
        }
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
    // AI Chat settings
    val aiProvider: AiProvider = AiProvider.GEMINI,
    val aiModelId: String = "gemini-2.5-flash",
    
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
    val baiduSecretKey: String = "",  // Baidu requires both API Key and Secret Key
    val perplexityApiKey: String = "",
    val customApiKey: String = "",
    
    // Custom base URLs (for providers that support it)
    val customBaseUrl: String = "http://localhost:11434/v1/",
    val customModelName: String = "llama4",
    
    // Speech recognition settings
    val speechService: SpeechService = SpeechService.GEMINI_AUDIO,
    val speechLanguage: String = "zh-TW",
    
    // AI response settings
    val responseLanguage: String = "zh-TW",
    val systemPrompt: String = "You are a friendly AI assistant. Please answer questions concisely."
) {
    /**
     * Get current AI provider's API Key
     */
    fun getCurrentApiKey(): String {
        return when (aiProvider) {
            AiProvider.GEMINI -> geminiApiKey
            AiProvider.OPENAI -> openaiApiKey
            AiProvider.ANTHROPIC -> anthropicApiKey
            AiProvider.DEEPSEEK -> deepseekApiKey
            AiProvider.GROQ -> groqApiKey
            AiProvider.XAI -> xaiApiKey
            AiProvider.ALIBABA -> alibabaApiKey
            AiProvider.ZHIPU -> zhipuApiKey
            AiProvider.BAIDU -> baiduApiKey
            AiProvider.PERPLEXITY -> perplexityApiKey
            AiProvider.CUSTOM -> customApiKey
        }
    }
    
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
            AiProvider.BAIDU -> baiduApiKey
            AiProvider.PERPLEXITY -> perplexityApiKey
            AiProvider.CUSTOM -> customApiKey
        }
    }
    
    /**
     * Get base URL for current provider
     */
    fun getCurrentBaseUrl(): String {
        return when (aiProvider) {
            AiProvider.CUSTOM -> customBaseUrl.ifBlank { AiProvider.CUSTOM.defaultBaseUrl }
            else -> aiProvider.defaultBaseUrl
        }
    }
    
    /**
     * Get model ID for current provider
     */
    fun getCurrentModelId(): String {
        return when (aiProvider) {
            AiProvider.CUSTOM -> customModelName.ifBlank { aiModelId }
            else -> aiModelId
        }
    }
    
    /**
     * Check if settings are valid
     */
    fun isValid(): Boolean {
        return when (aiProvider) {
            AiProvider.CUSTOM -> customBaseUrl.isNotBlank() && isValidUrl(customBaseUrl)
            AiProvider.BAIDU -> baiduApiKey.isNotBlank() && baiduSecretKey.isNotBlank()
            else -> getCurrentApiKey().isNotBlank()
        }
    }
    
    /**
     * Check if specified provider has API Key configured
     */
    fun isProviderConfigured(provider: AiProvider): Boolean {
        return when (provider) {
            AiProvider.CUSTOM -> customBaseUrl.isNotBlank() && isValidUrl(customBaseUrl)
            AiProvider.BAIDU -> baiduApiKey.isNotBlank() && baiduSecretKey.isNotBlank()
            else -> getApiKeyForProvider(provider).isNotBlank()
        }
    }
    
    /**
     * Check if any speech recognition service is available
     */
    fun hasSpeechServiceConfigured(): Boolean {
        val sttProviders = listOf(AiProvider.GEMINI, AiProvider.OPENAI, AiProvider.GROQ, AiProvider.XAI)
        return sttProviders.any { isProviderConfigured(it) }
    }
    
    /**
     * Get list of configured STT providers
     */
    fun getConfiguredSttProviders(): List<AiProvider> {
        val sttProviders = listOf(AiProvider.GEMINI, AiProvider.OPENAI, AiProvider.GROQ, AiProvider.XAI)
        return sttProviders.filter { isProviderConfigured(it) }
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
     * Validate URL format
     */
    private fun isValidUrl(url: String): Boolean {
        return try {
            val trimmed = url.trim()
            trimmed.startsWith("http://") || trimmed.startsWith("https://")
        } catch (e: Exception) {
            false
        }
    }
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
        aiProvider == AiProvider.CUSTOM && !isValidUrl(customBaseUrl) -> 
            SettingsValidationResult.InvalidConfiguration("Invalid custom provider URL")
        aiProvider == AiProvider.BAIDU && (baiduApiKey.isBlank() || baiduSecretKey.isBlank()) ->
            SettingsValidationResult.MissingApiKey(AiProvider.BAIDU)
        getCurrentApiKey().isBlank() ->
            SettingsValidationResult.MissingApiKey(aiProvider)
        else -> SettingsValidationResult.Valid
    }
}

/**
 * Validate settings for speech recognition
 */
fun ApiSettings.validateForSpeech(): SettingsValidationResult {
    val sttProviders = listOf(AiProvider.GEMINI, AiProvider.OPENAI, AiProvider.GROQ, AiProvider.XAI)
    val configuredStt = sttProviders.filter { isProviderConfigured(it) }
    
    return if (configuredStt.isEmpty()) {
        SettingsValidationResult.MissingSpeechService(sttProviders)
    } else {
        SettingsValidationResult.Valid
    }
}

/**
 * Extension function to check if URL is valid
 */
private fun ApiSettings.isValidUrl(url: String): Boolean {
    return try {
        val trimmed = url.trim()
        trimmed.startsWith("http://") || trimmed.startsWith("https://")
    } catch (e: Exception) {
        false
    }
}
