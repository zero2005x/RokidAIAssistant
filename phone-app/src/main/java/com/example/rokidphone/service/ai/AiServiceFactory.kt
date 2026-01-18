package com.example.rokidphone.service.ai

import com.example.rokidphone.data.AiProvider
import com.example.rokidphone.data.ApiSettings

/**
 * AI Service Factory
 * Creates corresponding AI service instance based on settings
 */
object AiServiceFactory {
    
    /**
     * Create AI service based on settings
     */
    fun createService(settings: ApiSettings): AiServiceProvider {
        val apiKey = settings.getCurrentApiKey()
        val systemPrompt = settings.systemPrompt
        val modelId = settings.getCurrentModelId()
        
        return when (settings.aiProvider) {
            AiProvider.GEMINI -> GeminiService(
                apiKey = apiKey,
                modelId = modelId,
                systemPrompt = systemPrompt
            )
            
            AiProvider.OPENAI -> OpenAiCompatibleService(
                apiKey = apiKey,
                baseUrl = settings.aiProvider.defaultBaseUrl,
                modelId = modelId,
                systemPrompt = systemPrompt,
                providerType = AiProvider.OPENAI
            )
            
            AiProvider.ANTHROPIC -> AnthropicService(
                apiKey = apiKey,
                modelId = modelId,
                systemPrompt = systemPrompt
            )
            
            AiProvider.DEEPSEEK -> OpenAiCompatibleService(
                apiKey = apiKey,
                baseUrl = settings.aiProvider.defaultBaseUrl,
                modelId = modelId,
                systemPrompt = systemPrompt,
                providerType = AiProvider.DEEPSEEK
            )
            
            AiProvider.GROQ -> OpenAiCompatibleService(
                apiKey = apiKey,
                baseUrl = settings.aiProvider.defaultBaseUrl,
                modelId = modelId,
                systemPrompt = systemPrompt,
                providerType = AiProvider.GROQ
            )
            
            AiProvider.XAI -> OpenAiCompatibleService(
                apiKey = apiKey,
                baseUrl = settings.aiProvider.defaultBaseUrl,
                modelId = modelId,
                systemPrompt = systemPrompt,
                providerType = AiProvider.XAI
            )
            
            AiProvider.ALIBABA -> OpenAiCompatibleService(
                apiKey = apiKey,
                baseUrl = settings.aiProvider.defaultBaseUrl,
                modelId = modelId,
                systemPrompt = systemPrompt,
                providerType = AiProvider.ALIBABA
            )
            
            AiProvider.ZHIPU -> OpenAiCompatibleService(
                apiKey = apiKey,
                baseUrl = settings.aiProvider.defaultBaseUrl,
                modelId = modelId,
                systemPrompt = systemPrompt,
                providerType = AiProvider.ZHIPU
            )
            
            AiProvider.BAIDU -> BaiduService(
                apiKey = settings.baiduApiKey,
                secretKey = settings.baiduSecretKey,
                modelId = modelId,
                systemPrompt = systemPrompt
            )
            
            AiProvider.PERPLEXITY -> OpenAiCompatibleService(
                apiKey = apiKey,
                baseUrl = settings.aiProvider.defaultBaseUrl,
                modelId = modelId,
                systemPrompt = systemPrompt,
                providerType = AiProvider.PERPLEXITY
            )
            
            AiProvider.CUSTOM -> OpenAiCompatibleService(
                apiKey = settings.customApiKey,
                baseUrl = settings.getCurrentBaseUrl(),
                modelId = settings.customModelName.ifBlank { modelId },
                systemPrompt = systemPrompt,
                providerType = AiProvider.CUSTOM
            )
        }
    }
    
    /**
     * Create service for testing connection
     */
    fun createTestService(settings: ApiSettings): OpenAiCompatibleService? {
        return when (settings.aiProvider) {
            AiProvider.GEMINI, AiProvider.ANTHROPIC, AiProvider.BAIDU -> null // Not OpenAI-compatible
            
            AiProvider.OPENAI, AiProvider.DEEPSEEK, AiProvider.GROQ, 
            AiProvider.XAI, AiProvider.ALIBABA, AiProvider.ZHIPU, AiProvider.PERPLEXITY -> OpenAiCompatibleService(
                apiKey = settings.getCurrentApiKey(),
                baseUrl = settings.aiProvider.defaultBaseUrl,
                modelId = settings.getCurrentModelId(),
                systemPrompt = "",
                providerType = settings.aiProvider
            )
            
            AiProvider.CUSTOM -> OpenAiCompatibleService(
                apiKey = settings.customApiKey,
                baseUrl = settings.getCurrentBaseUrl(),
                modelId = settings.customModelName.ifBlank { settings.aiModelId },
                systemPrompt = "",
                providerType = AiProvider.CUSTOM
            )
        }
    }
    
    /**
     * Create Baidu test service
     */
    fun createBaiduTestService(settings: ApiSettings): BaiduService? {
        return if (settings.aiProvider == AiProvider.BAIDU) {
            BaiduService(
                apiKey = settings.baiduApiKey,
                secretKey = settings.baiduSecretKey,
                modelId = settings.getCurrentModelId(),
                systemPrompt = ""
            )
        } else null
    }
    
    /**
     * Create service by provider (for speech recognition service selection)
     */
    fun createSpeechService(provider: AiProvider, apiKey: String): AiServiceProvider? {
        return when (provider) {
            AiProvider.GEMINI -> GeminiService(apiKey = apiKey)
            AiProvider.OPENAI -> OpenAiCompatibleService(
                apiKey = apiKey,
                baseUrl = AiProvider.OPENAI.defaultBaseUrl,
                modelId = "gpt-4o",
                providerType = AiProvider.OPENAI
            )
            AiProvider.GROQ -> OpenAiCompatibleService(
                apiKey = apiKey,
                baseUrl = AiProvider.GROQ.defaultBaseUrl,
                modelId = "llama-4-70b-versatile",
                providerType = AiProvider.GROQ
            )
            AiProvider.XAI -> OpenAiCompatibleService(
                apiKey = apiKey,
                baseUrl = AiProvider.XAI.defaultBaseUrl,
                modelId = "grok-4",
                providerType = AiProvider.XAI
            )
            // DeepSeek, Anthropic, Alibaba, Zhipu, Baidu, Custom do not support STT
            else -> null
        }
    }
}
