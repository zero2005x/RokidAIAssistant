package com.example.rokidphone.ai.provider

import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

/**
 * Provider Settings - Type-safe multi-provider support using sealed class
 * Based on RikkaHub's design pattern, each provider has its own setting type
 */
@Serializable
sealed class ProviderSetting {
    abstract val id: String
    abstract val displayName: String
    abstract val enabled: Boolean
    
    /**
     * Get the current API Key (if available)
     */
    @Transient
    abstract val providerApiKey: String?
    
    /**
     * Get the Base URL
     */
    @Transient
    abstract val providerBaseUrl: String
    
    /**
     * Validate if the setting is valid
     */
    abstract fun isValid(): Boolean
    
    /**
     * Gemini Provider Settings
     */
    @Serializable
    data class Gemini(
        override val id: String = "gemini",
        override val displayName: String = "Google Gemini",
        override val enabled: Boolean = true,
        val apiKey: String = "",
        val modelId: String = "gemini-3.1-flash",
        val baseUrl: String = "https://generativelanguage.googleapis.com/v1beta/"
    ) : ProviderSetting() {
        @Transient
        override val providerApiKey: String = apiKey
        @Transient
        override val providerBaseUrl: String = baseUrl
        override fun isValid(): Boolean = apiKey.isNotBlank()
    }
    
    /**
     * OpenAI Provider Settings
     */
    @Serializable
    data class OpenAI(
        override val id: String = "openai",
        override val displayName: String = "OpenAI",
        override val enabled: Boolean = true,
        val apiKey: String = "",
        val modelId: String = "gpt-5.4",
        val baseUrl: String = "https://api.openai.com/v1/",
        val organizationId: String = ""
    ) : ProviderSetting() {
        @Transient
        override val providerApiKey: String = apiKey
        @Transient
        override val providerBaseUrl: String = baseUrl
        override fun isValid(): Boolean = apiKey.isNotBlank()
    }
    
    /**
     * Anthropic Provider Settings
     */
    @Serializable
    data class Anthropic(
        override val id: String = "anthropic",
        override val displayName: String = "Anthropic Claude",
        override val enabled: Boolean = true,
        val apiKey: String = "",
        val modelId: String = "claude-opus-4-7",
        val baseUrl: String = "https://api.anthropic.com/v1/"
    ) : ProviderSetting() {
        @Transient
        override val providerApiKey: String = apiKey
        @Transient
        override val providerBaseUrl: String = baseUrl
        override fun isValid(): Boolean = apiKey.isNotBlank()
    }
    
    /**
     * DeepSeek Provider Settings
     */
    @Serializable
    data class DeepSeek(
        override val id: String = "deepseek",
        override val displayName: String = "DeepSeek",
        override val enabled: Boolean = true,
        val apiKey: String = "",
        val modelId: String = "deepseek-chat",
        val baseUrl: String = "https://api.deepseek.com/"
    ) : ProviderSetting() {
        @Transient
        override val providerApiKey: String = apiKey
        @Transient
        override val providerBaseUrl: String = baseUrl
        override fun isValid(): Boolean = apiKey.isNotBlank()
    }
    
    /**
     * Groq Provider Settings
     */
    @Serializable
    data class Groq(
        override val id: String = "groq",
        override val displayName: String = "Groq",
        override val enabled: Boolean = true,
        val apiKey: String = "",
        val modelId: String = "openai/gpt-oss-120b",
        val baseUrl: String = "https://api.groq.com/openai/v1/"
    ) : ProviderSetting() {
        @Transient
        override val providerApiKey: String = apiKey
        @Transient
        override val providerBaseUrl: String = baseUrl
        override fun isValid(): Boolean = apiKey.isNotBlank()
    }
    
    /**
     * xAI (Grok) Provider Settings
     */
    @Serializable
    data class XAI(
        override val id: String = "xai",
        override val displayName: String = "xAI Grok",
        override val enabled: Boolean = true,
        val apiKey: String = "",
        val modelId: String = "grok-4.20-beta-latest-reasoning",
        val baseUrl: String = "https://api.x.ai/v1/"
    ) : ProviderSetting() {
        @Transient
        override val providerApiKey: String = apiKey
        @Transient
        override val providerBaseUrl: String = baseUrl
        override fun isValid(): Boolean = apiKey.isNotBlank()
    }
    
    /**
     * Alibaba Qwen Provider Settings
     */
    @Serializable
    data class Alibaba(
        override val id: String = "alibaba",
        override val displayName: String = "Alibaba Qwen",
        override val enabled: Boolean = true,
        val apiKey: String = "",
        val modelId: String = "qwen3-max-2026-01-23",
        val baseUrl: String = "https://dashscope.aliyuncs.com/compatible-mode/v1/"
    ) : ProviderSetting() {
        @Transient
        override val providerApiKey: String = apiKey
        @Transient
        override val providerBaseUrl: String = baseUrl
        override fun isValid(): Boolean = apiKey.isNotBlank()
    }
    
    /**
     * Zhipu GLM Provider Settings
     */
    @Serializable
    data class Zhipu(
        override val id: String = "zhipu",
        override val displayName: String = "Zhipu GLM",
        override val enabled: Boolean = true,
        val apiKey: String = "",
        val modelId: String = "glm-5.1",
        val baseUrl: String = "https://open.bigmodel.cn/api/paas/v4/"
    ) : ProviderSetting() {
        @Transient
        override val providerApiKey: String = apiKey
        @Transient
        override val providerBaseUrl: String = baseUrl
        override fun isValid(): Boolean = apiKey.isNotBlank()
    }
    
    /**
     * Baidu Ernie Provider Settings
     * Requires API Key and Secret Key for OAuth authentication
     */
    @Serializable
    data class Baidu(
        override val id: String = "baidu",
        override val displayName: String = "Baidu Ernie",
        override val enabled: Boolean = true,
        val apiKey: String = "",
        val secretKey: String = "",
        val modelId: String = "ernie-4.0-8k",
        val baseUrl: String = "https://aip.baidubce.com/rpc/2.0/ai_custom/v1/wenxinworkshop/chat/"
    ) : ProviderSetting() {
        @Transient
        override val providerApiKey: String = apiKey
        @Transient
        override val providerBaseUrl: String = baseUrl
        override fun isValid(): Boolean = apiKey.isNotBlank() && secretKey.isNotBlank()
    }
    
    /**
     * Perplexity Provider Settings
     */
    @Serializable
    data class Perplexity(
        override val id: String = "perplexity",
        override val displayName: String = "Perplexity",
        override val enabled: Boolean = true,
        val apiKey: String = "",
        val modelId: String = "sonar-pro",
        val baseUrl: String = "https://api.perplexity.ai/"
    ) : ProviderSetting() {
        @Transient
        override val providerApiKey: String = apiKey
        @Transient
        override val providerBaseUrl: String = baseUrl
        override fun isValid(): Boolean = apiKey.isNotBlank()
    }
    
    /**
     * Moonshot (Kimi) Provider Settings
     */
    @Serializable
    data class Moonshot(
        override val id: String = "moonshot",
        override val displayName: String = "Moonshot (Kimi)",
        override val enabled: Boolean = true,
        val apiKey: String = "",
        val modelId: String = "kimi-k2.5",
        val baseUrl: String = "https://api.moonshot.cn/v1/"
    ) : ProviderSetting() {
        @Transient
        override val providerApiKey: String = apiKey
        @Transient
        override val providerBaseUrl: String = baseUrl
        override fun isValid(): Boolean = apiKey.isNotBlank()
    }

    /**
     * Mistral AI Provider Settings (OpenAI-compatible)
     */
    @Serializable
    data class Mistral(
        override val id: String = "mistral",
        override val displayName: String = "Mistral AI",
        override val enabled: Boolean = true,
        val apiKey: String = "",
        // TODO: Verify the default model ID against https://docs.mistral.ai/getting-started/models/
        val modelId: String = "mistral-large-latest",
        val baseUrl: String = "https://api.mistral.ai/v1/"
    ) : ProviderSetting() {
        @Transient
        override val providerApiKey: String = apiKey
        @Transient
        override val providerBaseUrl: String = baseUrl
        override fun isValid(): Boolean = apiKey.isNotBlank()
    }
    
    /**
     * AnythingLLM Provider Settings
     * Document-grounded provider that relays text queries to a configured workspace
     * and exposes source/citation previews when available.
     */
    @Serializable
    data class AnythingLLM(
        override val id: String = "anythingllm",
        override val displayName: String = "AnythingLLM",
        override val enabled: Boolean = true,
        val serverUrl: String = "",
        val apiKey: String = "",
        val workspaceSlug: String = ""
    ) : ProviderSetting() {
        @Transient
        override val providerApiKey: String = apiKey
        @Transient
        override val providerBaseUrl: String = serverUrl
        override fun isValid(): Boolean =
            serverUrl.isNotBlank() && apiKey.isNotBlank() && workspaceSlug.isNotBlank()
    }

    /**
     * Custom OpenAI-compatible Provider Settings
     * Supports Ollama, LM Studio, vLLM, and other local deployments
     */
    @Serializable
    data class Custom(
        override val id: String = "custom",
        override val displayName: String = "Custom Service",
        override val enabled: Boolean = true,
        val apiKey: String = "",  // Optional
        val modelId: String = "llama4",
        val baseUrl: String = "http://localhost:11434/v1/",
        val customName: String = ""
    ) : ProviderSetting() {
        @Transient
        override val providerApiKey: String? = apiKey.ifBlank { null }
        @Transient
        override val providerBaseUrl: String = baseUrl
        override fun isValid(): Boolean = baseUrl.isNotBlank()
    }
    
    companion object {
        /**
         * Get the default list of provider settings
         */
        fun getDefaultProviders(): List<ProviderSetting> = listOf(
            Gemini(),
            OpenAI(),
            Anthropic(),
            DeepSeek(),
            Groq(),
            XAI(),
            Alibaba(),
            Zhipu(),
            Baidu(),
            Perplexity(),
            Moonshot(),
            Mistral(),
            AnythingLLM(),
            Custom()
        )
        
        /**
         * Create default settings from ID
         */
        fun fromId(id: String): ProviderSetting? = when (id) {
            "gemini" -> Gemini()
            "openai" -> OpenAI()
            "anthropic" -> Anthropic()
            "deepseek" -> DeepSeek()
            "groq" -> Groq()
            "xai" -> XAI()
            "alibaba" -> Alibaba()
            "zhipu" -> Zhipu()
            "baidu" -> Baidu()
            "perplexity" -> Perplexity()
            "moonshot" -> Moonshot()
            "mistral" -> Mistral()
            "anythingllm" -> AnythingLLM()
            "custom" -> Custom()
            else -> null
        }
    }
}
