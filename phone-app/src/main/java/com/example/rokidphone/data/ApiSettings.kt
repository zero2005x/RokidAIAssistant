package com.example.rokidphone.data

import androidx.annotation.StringRes
import com.example.rokidphone.R
import com.example.rokidphone.service.stt.SttProvider

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
    GEMINI_LIVE(
        displayNameResId = R.string.provider_gemini_live,
        description = "Gemini Live API - real-time bidirectional voice conversation",
        website = "https://ai.google.dev",
        defaultBaseUrl = "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent",
        isOpenAiCompatible = false,
        supportsSpeech = true,
        supportsVision = true
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
 * @param supportsAudio true if this model supports audio/speech input via the provider's STT API
 * @param supportsVision true if this model supports image/vision input
 */
data class ModelOption(
    val id: String,
    val displayName: String,
    val provider: AiProvider,
    val supportsAudio: Boolean = false,
    val supportsVision: Boolean = false,
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
            supportsVision = true,
            description = "Google's most capable AI model, native multimodal reasoning"
        ),
        ModelOption(
            id = "gemini-2.5-flash",
            displayName = "Gemini 2.5 Flash",
            provider = AiProvider.GEMINI,
            supportsAudio = true,
            supportsVision = true,
            description = "Optimized for speed and efficiency, supports high-volume tasks"
        ),
        ModelOption(
            id = "gemini-2.5-flash-lite",
            displayName = "Gemini 2.5 Flash-Lite",
            provider = AiProvider.GEMINI,
            supportsAudio = true,
            supportsVision = true,
            description = "Extremely cost-effective lightweight model for low-latency applications"
        )
    )
    
    val openaiModels = listOf(
        ModelOption(
            id = "gpt-5",
            displayName = "GPT-5 (Omni)",
            provider = AiProvider.OPENAI,
            supportsAudio = false,
            supportsVision = true,
            description = "Flagship multimodal model with state-of-the-art vision analysis"
        ),
        ModelOption(
            id = "gpt-5.2",
            displayName = "GPT-5.2",
            provider = AiProvider.OPENAI,
            supportsAudio = false,
            supportsVision = true,
            description = "OpenAI's latest flagship. Unmatched general intelligence"
        ),
        ModelOption(
            id = "gpt-4o",
            displayName = "GPT-4o",
            provider = AiProvider.OPENAI,
            supportsAudio = false,
            supportsVision = true,
            description = "High-performance multimodal model, excellent for OCR and scene understanding"
        ),
        ModelOption(
            id = "gpt-4o-mini",
            displayName = "GPT-4o Mini",
            provider = AiProvider.OPENAI,
            supportsAudio = false,
            supportsVision = true,
            description = "Cost-efficient vision model, good for simple object detection"
        ),
        ModelOption(
            id = "o3-pro",
            displayName = "o3 Pro (Reasoning)",
            provider = AiProvider.OPENAI,
            supportsAudio = false,
            supportsVision = false,
            description = "Advanced reasoning model for complex science, coding, and math"
        ),
        ModelOption(
            id = "o4-mini",
            displayName = "o4 Mini",
            provider = AiProvider.OPENAI,
            supportsAudio = false,
            supportsVision = false,
            description = "Cost-effective reasoning model for STEM and technical analysis"
        )
    )
    
    val anthropicModels = listOf(
        ModelOption(
            id = "claude-opus-4-5",
            displayName = "Claude 4.5 Opus",
            provider = AiProvider.ANTHROPIC,
            supportsAudio = false,
            supportsVision = true,
            description = "Anthropic's most powerful model, excelling in complex nuance"
        ),
        ModelOption(
            id = "claude-sonnet-4-5",
            displayName = "Claude 4.5 Sonnet",
            provider = AiProvider.ANTHROPIC,
            supportsAudio = false,
            supportsVision = true,
            description = "The best balance of intelligence and speed for enterprise workloads"
        ),
        ModelOption(
            id = "claude-haiku-4-5",
            displayName = "Claude 4.5 Haiku",
            provider = AiProvider.ANTHROPIC,
            supportsAudio = false,
            supportsVision = true,
            description = "Blazing fast model for instant responses and simple tasks"
        ),
        // Legacy Claude 3.5 models for backward compatibility
        ModelOption(
            id = "claude-3-5-sonnet-latest",
            displayName = "Claude 3.5 Sonnet (Legacy)",
            provider = AiProvider.ANTHROPIC,
            supportsAudio = false,
            supportsVision = true,
            description = "Previous generation Sonnet model for stability"
        ),
        ModelOption(
            id = "claude-3-5-haiku-latest",
            displayName = "Claude 3.5 Haiku (Legacy)",
            provider = AiProvider.ANTHROPIC,
            supportsAudio = false,
            supportsVision = true,
            description = "Previous generation Haiku model for simple tasks"
        )
    )
    
    val deepseekModels = listOf(
        ModelOption(
            id = "deepseek-chat",
            displayName = "DeepSeek V3.2",
            provider = AiProvider.DEEPSEEK,
            supportsAudio = false,
            supportsVision = false,
            description = "Latest V3.2 generation. Highly capable general-purpose model"
        ),
        ModelOption(
            id = "deepseek-reasoner",
            displayName = "DeepSeek R1 (2025)",
            provider = AiProvider.DEEPSEEK,
            supportsAudio = false,
            supportsVision = false,
            description = "Specialized reasoning model (Chain of Thought) for complex logic"
        ),
        ModelOption(
            id = "deepseek-vl-3",
            displayName = "DeepSeek VL 3",
            provider = AiProvider.DEEPSEEK,
            supportsAudio = false,
            supportsVision = true,
            description = "Dedicated vision-language model decoupled from main chat for cost efficiency"
        )
    )
    
    val groqModels = listOf(
        ModelOption(
            id = "llama-3.3-70b-versatile",
            displayName = "Llama 3.3 70B",
            provider = AiProvider.GROQ,
            supportsAudio = false,
            supportsVision = false,
            description = "Reliable and powerful open model, optimized for tool use"
        ),
        ModelOption(
            id = "llama-3.1-8b-instant",
            displayName = "Llama 3.1 8B",
            provider = AiProvider.GROQ,
            supportsAudio = false,
            supportsVision = false,
            description = "Fast and cost-effective model for simple tasks"
        ),
        ModelOption(
            id = "llama-3.2-11b-vision-preview",
            displayName = "Llama 3.2 11B Vision",
            provider = AiProvider.GROQ,
            supportsAudio = false,
            supportsVision = true,
            description = "Lightweight vision model perfect for fast image description"
        ),
        ModelOption(
            id = "llama-3.2-90b-vision-preview",
            displayName = "Llama 3.2 90B Vision",
            provider = AiProvider.GROQ,
            supportsAudio = false,
            supportsVision = true,
            description = "Meta's open multimodal powerhouse running at extreme speeds on Groq"
        ),
        ModelOption(
            id = "mixtral-8x7b-32768",
            displayName = "Mixtral 8x7B",
            provider = AiProvider.GROQ,
            supportsAudio = false,
            supportsVision = false,
            description = "Mistral's mixture-of-experts model with 32K context"
        ),
        ModelOption(
            id = "gemma2-9b-it",
            displayName = "Gemma 2 9B",
            provider = AiProvider.GROQ,
            supportsAudio = false,
            supportsVision = false,
            description = "Google's lightweight and efficient open model"
        )
    )
    
    val customModels = listOf(
        ModelOption(
            id = "custom",
            displayName = "Custom Model",
            provider = AiProvider.CUSTOM,
            supportsAudio = false,
            supportsVision = false,
            description = "User-defined model name"
        ),
        ModelOption(
            id = "llama4",
            displayName = "Llama 4 (Ollama)",
            provider = AiProvider.CUSTOM,
            supportsAudio = false,
            supportsVision = false,
            description = "Local Llama 4 model via Ollama"
        ),
        ModelOption(
            id = "deepseek-r1",
            displayName = "DeepSeek R1 (Ollama)",
            provider = AiProvider.CUSTOM,
            supportsAudio = false,
            supportsVision = false,
            description = "Local DeepSeek reasoning model"
        ),
        ModelOption(
            id = "minicpm-v-2.6",
            displayName = "MiniCPM-V 2.6 (Ollama)",
            provider = AiProvider.CUSTOM,
            supportsAudio = false,
            supportsVision = true,
            description = "Efficient local vision model, GPT-4V level performance on mobile parameters"
        ),
        ModelOption(
            id = "moondream2",
            displayName = "Moondream 2 (Ollama)",
            provider = AiProvider.CUSTOM,
            supportsAudio = false,
            supportsVision = true,
            description = "Tiny vision model designed to run on almost any hardware"
        )
    )
    
    val xaiModels = listOf(
        ModelOption(
            id = "grok-3",
            displayName = "Grok 3",
            provider = AiProvider.XAI,
            supportsAudio = false,
            supportsVision = false,
            description = "xAI's most powerful model with strong reasoning capabilities"
        ),
        ModelOption(
            id = "grok-3-fast",
            displayName = "Grok 3 Fast",
            provider = AiProvider.XAI,
            supportsAudio = false,
            supportsVision = false,
            description = "Low-latency version of Grok 3 for quick interactions"
        ),
        ModelOption(
            id = "grok-2-latest",
            displayName = "Grok 2",
            provider = AiProvider.XAI,
            supportsAudio = false,
            supportsVision = false,
            description = "Previous flagship, stable performance with real-time knowledge"
        ),
        ModelOption(
            id = "grok-2-vision-latest",
            displayName = "Grok 2 Vision",
            provider = AiProvider.XAI,
            supportsAudio = false,
            supportsVision = true,
            description = "xAI's vision-capable model, strong at document analysis"
        )
    )
    
    val alibabaModels = listOf(
        ModelOption(
            id = "qwen3-max",
            displayName = "Qwen 3 Max",
            provider = AiProvider.ALIBABA,
            supportsAudio = false,
            supportsVision = false,
            description = "Alibaba's most powerful model, top-tier performance"
        ),
        ModelOption(
            id = "qwen3-plus",
            displayName = "Qwen 3 Plus",
            provider = AiProvider.ALIBABA,
            supportsAudio = false,
            supportsVision = false,
            description = "Balanced model offering strong performance at a lower cost"
        ),
        ModelOption(
            id = "qwen3-turbo",
            displayName = "Qwen 3 Turbo",
            provider = AiProvider.ALIBABA,
            supportsAudio = false,
            supportsVision = false,
            description = "High-speed model optimized for simple queries and high throughput"
        ),
        ModelOption(
            id = "qwen3-vl-max",
            displayName = "Qwen 3 VL Max",
            provider = AiProvider.ALIBABA,
            supportsAudio = false,
            supportsVision = true,
            description = "Flagship vision-language model. State-of-the-art OCR and diagram understanding"
        ),
        ModelOption(
            id = "qwen3-vl-plus",
            displayName = "Qwen 3 VL Plus",
            provider = AiProvider.ALIBABA,
            supportsAudio = false,
            supportsVision = true,
            description = "Optimized visual understanding model, balancing resolution and speed"
        ),
        ModelOption(
            id = "qwen-vl-max",
            displayName = "Qwen-VL Max",
            provider = AiProvider.ALIBABA,
            supportsAudio = false,
            supportsVision = true,
            description = "Top-tier visual understanding from Alibaba (Qwen 2.5/3 VL based)"
        ),
        ModelOption(
            id = "qwen-vl-plus",
            displayName = "Qwen-VL Plus",
            provider = AiProvider.ALIBABA,
            supportsAudio = false,
            supportsVision = true,
            description = "Balanced speed/performance for vision tasks"
        )
    )
    
    val zhipuModels = listOf(
        ModelOption(
            id = "glm-4.7",
            displayName = "GLM-4.7",
            provider = AiProvider.ZHIPU,
            supportsAudio = false,
            supportsVision = false,
            description = "Zhipu's 2026 flagship. State-of-the-art Chinese/English bilingual"
        ),
        ModelOption(
            id = "glm-4-plus",
            displayName = "GLM-4 Plus",
            provider = AiProvider.ZHIPU,
            supportsAudio = false,
            supportsVision = false,
            description = "Enhanced version of GLM-4 with better long-context handling"
        ),
        ModelOption(
            id = "glm-4-flash",
            displayName = "GLM-4 Flash",
            provider = AiProvider.ZHIPU,
            supportsAudio = false,
            supportsVision = false,
            description = "Free/Low-cost high speed model"
        ),
        ModelOption(
            id = "glm-4v-plus",
            displayName = "GLM-4V Plus",
            provider = AiProvider.ZHIPU,
            supportsAudio = false,
            supportsVision = true,
            description = "Flagship multimodal model for Chinese UI understanding and video analysis"
        ),
        ModelOption(
            id = "glm-4v-flash",
            displayName = "GLM-4V Flash",
            provider = AiProvider.ZHIPU,
            supportsAudio = false,
            supportsVision = true,
            description = "High-speed vision model, ideal for real-time video frame processing"
        ),
        ModelOption(
            id = "glm-4v",
            displayName = "GLM-4V",
            provider = AiProvider.ZHIPU,
            supportsAudio = false,
            supportsVision = true,
            description = "Standard vision model for image dialogue"
        )
    )
    
    val baiduModels = listOf(
        ModelOption(
            id = "ernie-5.0",
            displayName = "ERNIE 5.0",
            provider = AiProvider.BAIDU,
            supportsAudio = false,
            supportsVision = false,
            description = "Baidu's latest foundation model, massive knowledge base update"
        ),
        ModelOption(
            id = "ernie-x1",
            displayName = "ERNIE X1 (Reasoning)",
            provider = AiProvider.BAIDU,
            supportsAudio = false,
            supportsVision = false,
            description = "Specialized model for complex reasoning and logic tasks"
        ),
        ModelOption(
            id = "ernie-4.5-turbo",
            displayName = "ERNIE 4.5 Turbo",
            provider = AiProvider.BAIDU,
            supportsAudio = false,
            supportsVision = false,
            description = "Speed-optimized version of the highly capable Ernie 4.5"
        ),
        ModelOption(
            id = "ernie-vision-pro",
            displayName = "ERNIE Vision Pro",
            provider = AiProvider.BAIDU,
            supportsAudio = false,
            supportsVision = true,
            description = "Enterprise-grade vision endpoint, specialized in document and receipt parsing"
        ),
        ModelOption(
            id = "ERNIE-Bot-4",
            displayName = "ERNIE 4.0 (Vision)",
            provider = AiProvider.BAIDU,
            supportsAudio = false,
            supportsVision = true,
            description = "Flagship model supports image inputs in latest API versions"
        ),
        ModelOption(
            id = "Fuyu-8B",
            displayName = "Fuyu-8B",
            provider = AiProvider.BAIDU,
            supportsAudio = false,
            supportsVision = true,
            description = "Specialized vision-language model available on Qianfan platform"
        )
    )
    
    val perplexityModels = listOf(
        ModelOption(
            id = "sonar-pro",
            displayName = "Sonar Pro",
            provider = AiProvider.PERPLEXITY,
            supportsAudio = false,
            supportsVision = false,
            description = "Enterprise-grade model with high-fidelity web search and complex reasoning"
        ),
        ModelOption(
            id = "sonar",
            displayName = "Sonar",
            provider = AiProvider.PERPLEXITY,
            supportsAudio = false,
            supportsVision = false,
            description = "Optimized for speed and efficiency, ideal for real-time search queries"
        ),
        ModelOption(
            id = "sonar-reasoning-pro",
            displayName = "Sonar Reasoning Pro",
            provider = AiProvider.PERPLEXITY,
            supportsAudio = false,
            supportsVision = false,
            description = "Advanced reasoning model powered by DeepSeek-R1, showing Chain-of-Thought"
        ),
        ModelOption(
            id = "sonar-reasoning",
            displayName = "Sonar Reasoning",
            provider = AiProvider.PERPLEXITY,
            supportsAudio = false,
            supportsVision = false,
            description = "Cost-effective reasoning model for logical queries with web grounding"
        ),
        ModelOption(
            id = "r1-1776",
            displayName = "Perplexity R1",
            provider = AiProvider.PERPLEXITY,
            supportsAudio = false,
            supportsVision = false,
            description = "DeepSeek R1 distilled by Perplexity for enhanced reasoning"
        )
    )
    
    val geminiLiveModels = listOf(
        ModelOption(
            id = "gemini-2.5-flash-preview-native-audio-dialog",
            displayName = "Gemini 2.5 Flash (Live Audio)",
            provider = AiProvider.GEMINI_LIVE,
            supportsAudio = true,
            supportsVision = true,
            description = "Real-time bidirectional audio conversation with native voice"
        ),
        ModelOption(
            id = "gemini-2.0-flash-live-001",
            displayName = "Gemini 2.0 Flash (Live)",
            provider = AiProvider.GEMINI_LIVE,
            supportsAudio = true,
            supportsVision = true,
            description = "Stable live streaming model for real-time voice interactions"
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
            AiProvider.GEMINI_LIVE -> geminiLiveModels
            AiProvider.CUSTOM -> customModels
        }
    }
    
    fun findModel(modelId: String): ModelOption? {
        return allModels.find { it.id == modelId }
    }
    
    val allModels: List<ModelOption>
        get() = geminiModels + openaiModels + anthropicModels + deepseekModels + groqModels + 
                xaiModels + alibabaModels + zhipuModels + baiduModels + perplexityModels + geminiLiveModels + customModels
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
    val sttProvider: SttProvider = SttProvider.GEMINI,
    val speechLanguage: String = "zh-TW",
    
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
    
    // AI response settings
    val responseLanguage: String = "zh-TW",
    // Note: The default value is set to empty string here. 
    // The actual default (localized) is provided by SettingsRepository.getDefaultSystemPrompt()
    val systemPrompt: String = "",
    
    // Recording settings
    // Auto-analyze recordings with AI after stopping (default: true)
    val autoAnalyzeRecordings: Boolean = true
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
            AiProvider.GEMINI_LIVE -> geminiApiKey  // Shares Gemini API key
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
            AiProvider.GEMINI_LIVE -> geminiApiKey  // Shares Gemini API key
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
            AiProvider.GEMINI_LIVE -> geminiApiKey.isNotBlank()  // Shares Gemini API key
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
               (baiduApiKey.isNotBlank() && baiduSecretKey.isNotBlank()) ||
               perplexityApiKey.isNotBlank() ||
               (customApiKey.isNotBlank() || customBaseUrl.isNotBlank())
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
 * Convert ApiSettings to SttCredentials for use with SttServiceFactory
 */
fun ApiSettings.toSttCredentials(): com.example.rokidphone.service.stt.SttCredentials {
    return com.example.rokidphone.service.stt.SttCredentials(
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
