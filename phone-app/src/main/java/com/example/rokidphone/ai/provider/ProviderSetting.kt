package com.example.rokidphone.ai.provider

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

/** Mask a credential for safe logging: never print the raw value. */
private fun maskSecret(secret: String): String = if (secret.isBlank()) "(unset)" else "***"

/** True when [url] parses and uses an http/https scheme. */
private fun hasHttpScheme(url: String): Boolean =
    runCatching { java.net.URI(url.trim()) }.getOrNull()
        ?.scheme?.lowercase() in setOf("http", "https")

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
    abstract val providerApiKey: String?
    
    /**
     * Get the Base URL
     */
    abstract val providerBaseUrl: String
    
    /**
     * Validate if the setting is valid
     */
    abstract fun isValid(): Boolean
    
    /**
     * Gemini Provider Settings
     */
    @Serializable
    @SerialName("gemini")
    data class Gemini(
        override val id: String = "gemini",
        override val displayName: String = "Google Gemini",
        override val enabled: Boolean = false,
        @Transient val apiKey: String = "",
        val modelId: String = "gemini-3.8-flash",
        val baseUrl: String = "https://generativelanguage.googleapis.com/v1beta/"
    ) : ProviderSetting() {
        @Transient
        override val providerApiKey: String = apiKey
        @Transient
        override val providerBaseUrl: String = baseUrl
        override fun isValid(): Boolean = apiKey.isNotBlank()
        override fun toString(): String =
            "Gemini(id=$id, enabled=$enabled, modelId=$modelId, baseUrl=$baseUrl, apiKey=${maskSecret(apiKey)})"
    }
    
    /**
     * OpenAI Provider Settings
     */
    @Serializable
    @SerialName("openai")
    data class OpenAI(
        override val id: String = "openai",
        override val displayName: String = "OpenAI",
        override val enabled: Boolean = false,
        @Transient val apiKey: String = "",
        val modelId: String = "gpt-5.6-sol",
        val baseUrl: String = "https://api.openai.com/v1/",
        val organizationId: String = ""
    ) : ProviderSetting() {
        @Transient
        override val providerApiKey: String = apiKey
        @Transient
        override val providerBaseUrl: String = baseUrl
        override fun isValid(): Boolean = apiKey.isNotBlank()
        override fun toString(): String =
            "OpenAI(id=$id, enabled=$enabled, modelId=$modelId, baseUrl=$baseUrl, apiKey=${maskSecret(apiKey)})"
    }
    
    /**
     * Anthropic Provider Settings
     */
    @Serializable
    @SerialName("anthropic")
    data class Anthropic(
        override val id: String = "anthropic",
        override val displayName: String = "Anthropic Claude",
        override val enabled: Boolean = false,
        @Transient val apiKey: String = "",
        val modelId: String = "claude-opus-5",
        val baseUrl: String = "https://api.anthropic.com/v1/"
    ) : ProviderSetting() {
        @Transient
        override val providerApiKey: String = apiKey
        @Transient
        override val providerBaseUrl: String = baseUrl
        override fun isValid(): Boolean = apiKey.isNotBlank()
        override fun toString(): String =
            "Anthropic(id=$id, enabled=$enabled, modelId=$modelId, baseUrl=$baseUrl, apiKey=${maskSecret(apiKey)})"
    }
    
    /**
     * DeepSeek Provider Settings
     */
    @Serializable
    @SerialName("deepseek")
    data class DeepSeek(
        override val id: String = "deepseek",
        override val displayName: String = "DeepSeek",
        override val enabled: Boolean = false,
        @Transient val apiKey: String = "",
        val modelId: String = "deepseek-chat",
        val baseUrl: String = "https://api.deepseek.com/"
    ) : ProviderSetting() {
        @Transient
        override val providerApiKey: String = apiKey
        @Transient
        override val providerBaseUrl: String = baseUrl
        override fun isValid(): Boolean = apiKey.isNotBlank()
        override fun toString(): String =
            "DeepSeek(id=$id, enabled=$enabled, modelId=$modelId, baseUrl=$baseUrl, apiKey=${maskSecret(apiKey)})"
    }
    
    /**
     * Groq Provider Settings
     */
    @Serializable
    @SerialName("groq")
    data class Groq(
        override val id: String = "groq",
        override val displayName: String = "Groq",
        override val enabled: Boolean = false,
        @Transient val apiKey: String = "",
        val modelId: String = "openai/gpt-oss-120b",
        val baseUrl: String = "https://api.groq.com/openai/v1/"
    ) : ProviderSetting() {
        @Transient
        override val providerApiKey: String = apiKey
        @Transient
        override val providerBaseUrl: String = baseUrl
        override fun isValid(): Boolean = apiKey.isNotBlank()
        override fun toString(): String =
            "Groq(id=$id, enabled=$enabled, modelId=$modelId, baseUrl=$baseUrl, apiKey=${maskSecret(apiKey)})"
    }
    
    /**
     * xAI (Grok) Provider Settings
     */
    @Serializable
    @SerialName("xai")
    data class XAI(
        override val id: String = "xai",
        override val displayName: String = "xAI Grok",
        override val enabled: Boolean = false,
        @Transient val apiKey: String = "",
        val modelId: String = "grok-4.1-fast",
        val baseUrl: String = "https://api.x.ai/v1/"
    ) : ProviderSetting() {
        @Transient
        override val providerApiKey: String = apiKey
        @Transient
        override val providerBaseUrl: String = baseUrl
        override fun isValid(): Boolean = apiKey.isNotBlank()
        override fun toString(): String =
            "XAI(id=$id, enabled=$enabled, modelId=$modelId, baseUrl=$baseUrl, apiKey=${maskSecret(apiKey)})"
    }
    
    /**
     * Alibaba Qwen Provider Settings
     */
    @Serializable
    @SerialName("alibaba")
    data class Alibaba(
        override val id: String = "alibaba",
        override val displayName: String = "Alibaba Qwen",
        override val enabled: Boolean = false,
        @Transient val apiKey: String = "",
        val modelId: String = "qwen3.7-flash",
        val baseUrl: String = "https://dashscope.aliyuncs.com/compatible-mode/v1/"
    ) : ProviderSetting() {
        @Transient
        override val providerApiKey: String = apiKey
        @Transient
        override val providerBaseUrl: String = baseUrl
        override fun isValid(): Boolean = apiKey.isNotBlank()
        override fun toString(): String =
            "Alibaba(id=$id, enabled=$enabled, modelId=$modelId, baseUrl=$baseUrl, apiKey=${maskSecret(apiKey)})"
    }
    
    /**
     * Zhipu GLM Provider Settings
     */
    @Serializable
    @SerialName("zhipu")
    data class Zhipu(
        override val id: String = "zhipu",
        override val displayName: String = "Zhipu GLM",
        override val enabled: Boolean = false,
        @Transient val apiKey: String = "",
        // TODO: Verify the default model ID against https://open.bigmodel.cn/ before the next release
        val modelId: String = "glm-5.1",
        val baseUrl: String = "https://open.bigmodel.cn/api/paas/v4/"
    ) : ProviderSetting() {
        @Transient
        override val providerApiKey: String = apiKey
        @Transient
        override val providerBaseUrl: String = baseUrl
        override fun isValid(): Boolean = apiKey.isNotBlank()
        override fun toString(): String =
            "Zhipu(id=$id, enabled=$enabled, modelId=$modelId, baseUrl=$baseUrl, apiKey=${maskSecret(apiKey)})"
    }
    
    /**
     * Baidu Ernie Provider Settings
     * Requires API Key and Secret Key for OAuth authentication
     */
    @Serializable
    @SerialName("baidu")
    data class Baidu(
        override val id: String = "baidu",
        override val displayName: String = "Baidu Ernie",
        override val enabled: Boolean = false,
        @Transient val apiKey: String = "",
        @Transient val secretKey: String = "",
        val modelId: String = "ernie-4.0-8k",
        val baseUrl: String = "https://aip.baidubce.com/rpc/2.0/ai_custom/v1/wenxinworkshop/chat/"
    ) : ProviderSetting() {
        @Transient
        override val providerApiKey: String = apiKey
        @Transient
        override val providerBaseUrl: String = baseUrl
        override fun isValid(): Boolean = apiKey.isNotBlank() && secretKey.isNotBlank()
        override fun toString(): String =
            "Baidu(id=$id, enabled=$enabled, modelId=$modelId, baseUrl=$baseUrl, apiKey=${maskSecret(apiKey)}, secretKey=${maskSecret(secretKey)})"
    }
    
    /**
     * Perplexity Provider Settings
     */
    @Serializable
    @SerialName("perplexity")
    data class Perplexity(
        override val id: String = "perplexity",
        override val displayName: String = "Perplexity",
        override val enabled: Boolean = false,
        @Transient val apiKey: String = "",
        val modelId: String = "sonar-pro",
        val baseUrl: String = "https://api.perplexity.ai/"
    ) : ProviderSetting() {
        @Transient
        override val providerApiKey: String = apiKey
        @Transient
        override val providerBaseUrl: String = baseUrl
        override fun isValid(): Boolean = apiKey.isNotBlank()
        override fun toString(): String =
            "Perplexity(id=$id, enabled=$enabled, modelId=$modelId, baseUrl=$baseUrl, apiKey=${maskSecret(apiKey)})"
    }
    
    /**
     * Moonshot (Kimi) Provider Settings
     */
    @Serializable
    @SerialName("moonshot")
    data class Moonshot(
        override val id: String = "moonshot",
        override val displayName: String = "Moonshot (Kimi)",
        override val enabled: Boolean = false,
        @Transient val apiKey: String = "",
        val modelId: String = "kimi-k3",
        val baseUrl: String = "https://api.moonshot.cn/v1/"
    ) : ProviderSetting() {
        @Transient
        override val providerApiKey: String = apiKey
        @Transient
        override val providerBaseUrl: String = baseUrl
        override fun isValid(): Boolean = apiKey.isNotBlank()
        override fun toString(): String =
            "Moonshot(id=$id, enabled=$enabled, modelId=$modelId, baseUrl=$baseUrl, apiKey=${maskSecret(apiKey)})"
    }

    /**
     * Mistral AI Provider Settings (OpenAI-compatible)
     */
    @Serializable
    @SerialName("mistral")
    data class Mistral(
        override val id: String = "mistral",
        override val displayName: String = "Mistral AI",
        override val enabled: Boolean = false,
        @Transient val apiKey: String = "",
        // TODO: Verify the default model ID against https://docs.mistral.ai/getting-started/models/
        val modelId: String = "mistral-large-latest",
        val baseUrl: String = "https://api.mistral.ai/v1/"
    ) : ProviderSetting() {
        @Transient
        override val providerApiKey: String = apiKey
        @Transient
        override val providerBaseUrl: String = baseUrl
        override fun isValid(): Boolean = apiKey.isNotBlank()
        override fun toString(): String =
            "Mistral(id=$id, enabled=$enabled, modelId=$modelId, baseUrl=$baseUrl, apiKey=${maskSecret(apiKey)})"
    }
    
    /**
     * AnythingLLM Provider Settings
     * Document-grounded provider that relays text queries to a configured workspace
     * and exposes source/citation previews when available.
     */
    @Serializable
    @SerialName("anythingllm")
    data class AnythingLLM(
        override val id: String = "anythingllm",
        override val displayName: String = "AnythingLLM",
        override val enabled: Boolean = false,
        val serverUrl: String = "",
        @Transient val apiKey: String = "",
        val workspaceSlug: String = ""
    ) : ProviderSetting() {
        @Transient
        override val providerApiKey: String = apiKey
        @Transient
        override val providerBaseUrl: String = serverUrl
        override fun isValid(): Boolean =
            serverUrl.isNotBlank() && hasHttpScheme(serverUrl) &&
                apiKey.isNotBlank() && workspaceSlug.isNotBlank()
        override fun toString(): String =
            "AnythingLLM(id=$id, enabled=$enabled, serverUrl=$serverUrl, workspaceSlug=$workspaceSlug, apiKey=${maskSecret(apiKey)})"
    }

    /**
     * Custom OpenAI-compatible Provider Settings
     * Supports Ollama, LM Studio, vLLM, and other local deployments
     */
    @Serializable
    @SerialName("custom")
    data class Custom(
        override val id: String = "custom",
        override val displayName: String = "Custom Service",
        override val enabled: Boolean = false,
        @Transient val apiKey: String = "",  // Optional
        val modelId: String = "llama4",
        val baseUrl: String = "http://localhost:11434/v1/",
        val customName: String = ""
    ) : ProviderSetting() {
        @Transient
        override val providerApiKey: String? = apiKey.ifBlank { null }
        @Transient
        override val providerBaseUrl: String = baseUrl
        override fun isValid(): Boolean = baseUrl.isNotBlank() && hasHttpScheme(baseUrl)
        override fun toString(): String =
            "Custom(id=$id, enabled=$enabled, modelId=$modelId, baseUrl=$baseUrl, apiKey=${maskSecret(apiKey)})"
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
         * Create default settings from ID (single source of truth: [getDefaultProviders])
         */
        fun fromId(id: String): ProviderSetting? =
            getDefaultProviders().firstOrNull { it.id == id }
    }
}
