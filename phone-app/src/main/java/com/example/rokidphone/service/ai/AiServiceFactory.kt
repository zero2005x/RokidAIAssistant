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
                systemPrompt = systemPrompt,
                temperature = settings.temperature,
                maxTokens = settings.maxTokens,
                topP = settings.topP
            )
            
            AiProvider.OPENAI -> OpenAiCompatibleService(
                apiKey = apiKey,
                baseUrl = settings.aiProvider.defaultBaseUrl,
                modelId = modelId,
                systemPrompt = systemPrompt,
                providerType = AiProvider.OPENAI,
                temperature = settings.temperature,
                maxTokens = settings.maxTokens,
                topP = settings.topP,
                frequencyPenalty = settings.frequencyPenalty,
                presencePenalty = settings.presencePenalty
            )
            
            AiProvider.ANTHROPIC -> AnthropicService(
                apiKey = apiKey,
                modelId = modelId,
                systemPrompt = systemPrompt,
                temperature = settings.temperature,
                maxTokens = settings.maxTokens,
                topP = settings.topP
            )
            
            AiProvider.DEEPSEEK -> OpenAiCompatibleService(
                apiKey = apiKey,
                baseUrl = settings.aiProvider.defaultBaseUrl,
                modelId = modelId,
                systemPrompt = systemPrompt,
                providerType = AiProvider.DEEPSEEK,
                temperature = settings.temperature,
                maxTokens = settings.maxTokens,
                topP = settings.topP,
                frequencyPenalty = settings.frequencyPenalty,
                presencePenalty = settings.presencePenalty
            )
            
            AiProvider.GROQ -> OpenAiCompatibleService(
                apiKey = apiKey,
                baseUrl = settings.aiProvider.defaultBaseUrl,
                modelId = modelId,
                systemPrompt = systemPrompt,
                providerType = AiProvider.GROQ,
                temperature = settings.temperature,
                maxTokens = settings.maxTokens,
                topP = settings.topP,
                frequencyPenalty = settings.frequencyPenalty,
                presencePenalty = settings.presencePenalty
            )
            
            AiProvider.XAI -> OpenAiCompatibleService(
                apiKey = apiKey,
                baseUrl = settings.aiProvider.defaultBaseUrl,
                modelId = modelId,
                systemPrompt = systemPrompt,
                providerType = AiProvider.XAI,
                temperature = settings.temperature,
                maxTokens = settings.maxTokens,
                topP = settings.topP,
                frequencyPenalty = settings.frequencyPenalty,
                presencePenalty = settings.presencePenalty
            )
            
            AiProvider.ALIBABA -> OpenAiCompatibleService(
                apiKey = apiKey,
                baseUrl = settings.aiProvider.defaultBaseUrl,
                modelId = modelId,
                systemPrompt = systemPrompt,
                providerType = AiProvider.ALIBABA,
                temperature = settings.temperature,
                maxTokens = settings.maxTokens,
                topP = settings.topP,
                frequencyPenalty = settings.frequencyPenalty,
                presencePenalty = settings.presencePenalty
            )
            
            AiProvider.ZHIPU -> OpenAiCompatibleService(
                apiKey = apiKey,
                baseUrl = settings.aiProvider.defaultBaseUrl,
                modelId = modelId,
                systemPrompt = systemPrompt,
                providerType = AiProvider.ZHIPU,
                temperature = settings.temperature,
                maxTokens = settings.maxTokens,
                topP = settings.topP,
                frequencyPenalty = settings.frequencyPenalty,
                presencePenalty = settings.presencePenalty
            )
            
            AiProvider.BAIDU -> BaiduService(
                apiKey = settings.baiduApiKey,
                secretKey = settings.baiduSecretKey,
                modelId = modelId,
                systemPrompt = systemPrompt,
                temperature = settings.temperature,
                topP = settings.topP
            )
            
            AiProvider.PERPLEXITY -> OpenAiCompatibleService(
                apiKey = apiKey,
                baseUrl = settings.aiProvider.defaultBaseUrl,
                modelId = modelId,
                systemPrompt = systemPrompt,
                providerType = AiProvider.PERPLEXITY,
                temperature = settings.temperature,
                maxTokens = settings.maxTokens,
                topP = settings.topP,
                frequencyPenalty = settings.frequencyPenalty,
                presencePenalty = settings.presencePenalty
            )
            
            AiProvider.MOONSHOT -> OpenAiCompatibleService(
                apiKey = apiKey,
                baseUrl = settings.aiProvider.defaultBaseUrl,
                modelId = modelId,
                systemPrompt = systemPrompt,
                providerType = AiProvider.MOONSHOT,
                temperature = settings.temperature,
                maxTokens = settings.maxTokens,
                topP = settings.topP,
                frequencyPenalty = settings.frequencyPenalty,
                presencePenalty = settings.presencePenalty
            )
            
            AiProvider.CUSTOM -> OpenAiCompatibleService(
                apiKey = settings.customApiKey,
                baseUrl = settings.getCurrentBaseUrl(),
                modelId = settings.customModelName.ifBlank { modelId },
                systemPrompt = systemPrompt,
                providerType = AiProvider.CUSTOM,
                temperature = settings.temperature,
                maxTokens = settings.maxTokens,
                topP = settings.topP,
                frequencyPenalty = settings.frequencyPenalty,
                presencePenalty = settings.presencePenalty
            )

            AiProvider.GEMINI_LIVE -> {
                // Gemini Live uses WebSocket streaming, not REST API.
                // Return a standard GeminiService as fallback for non-live operations
                // (e.g., analyzeImage). The actual live session is managed by
                // GeminiLiveSession in PhoneAIService.
                GeminiService(
                    apiKey = apiKey,
                    modelId = "gemini-2.5-flash",
                    systemPrompt = systemPrompt,
                    temperature = settings.temperature,
                    maxTokens = settings.maxTokens,
                    topP = settings.topP
                )
            }
        }
    }
    
    /**
     * Create service for testing connection
     */
    fun createTestService(settings: ApiSettings): OpenAiCompatibleService? {
        return when (settings.aiProvider) {
            AiProvider.GEMINI, AiProvider.ANTHROPIC, AiProvider.BAIDU, AiProvider.GEMINI_LIVE -> null // Not OpenAI-compatible
            
            AiProvider.OPENAI, AiProvider.DEEPSEEK, AiProvider.GROQ, 
            AiProvider.XAI, AiProvider.ALIBABA, AiProvider.ZHIPU, AiProvider.PERPLEXITY,
            AiProvider.MOONSHOT -> OpenAiCompatibleService(
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
