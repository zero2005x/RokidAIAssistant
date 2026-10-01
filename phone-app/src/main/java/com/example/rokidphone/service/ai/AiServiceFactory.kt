package com.example.rokidphone.service.ai

import android.util.Log
import com.example.rokidphone.ai.catalog.ApiProtocol
import com.example.rokidphone.ai.catalog.ModelCapabilityResolver
import com.example.rokidphone.ai.catalog.ProviderRegistry
import com.example.rokidphone.ai.provider.AnythingLLMProvider
import com.example.rokidphone.ai.provider.ChatMessage
import com.example.rokidphone.ai.provider.GenerationResult
import com.example.rokidphone.ai.provider.MessageRole
import com.example.rokidphone.ai.provider.ProviderSetting
import com.example.rokidphone.data.AiProvider
import com.example.rokidphone.data.ApiSettings
import com.example.rokidphone.service.SpeechResult
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * AI Service Factory.
 *
 * Services are constructed from [ProviderRegistry] descriptors instead of a
 * growing `when` block with repeated constructor arguments. The registry
 * decides the wire protocol; the factory only supplies credentials, the
 * per-provider model selection and user tuning parameters.
 */
object AiServiceFactory {

    private const val TAG = "AiServiceFactory"

    /** ApiSettings.customProtocol value that selects the OpenAI Responses API. */
    private const val CUSTOM_PROTOCOL_RESPONSES = "responses"

    /** Max AnythingLLM adapter history messages (user + assistant turns). */
    private const val ANYTHINGLLM_MAX_HISTORY_MESSAGES = 20

    /**
     * Create AI service based on settings
     */
    fun createService(settings: ApiSettings): AiServiceProvider {
        val apiKey = settings.getCurrentApiKey().trim()
        val systemPrompt = settings.systemPrompt
        val modelId = settings.getCurrentModelId()
        val descriptor = ProviderRegistry.descriptorFor(settings.aiProvider)

        return when (descriptor.protocol) {
            // Gemini Live uses WebSocket streaming, not REST API.
            // Return a standard GeminiService as fallback for non-live operations
            // (e.g., analyzeImage). The actual live session is managed by
            // GeminiLiveSession in PhoneAIService.
            ApiProtocol.GEMINI_GENERATE_CONTENT, ApiProtocol.GEMINI_LIVE -> GeminiService(
                apiKey = apiKey,
                modelId = modelId,
                systemPrompt = systemPrompt,
                temperature = settings.temperature,
                maxTokens = settings.maxTokens,
                topP = settings.topP
            )

            ApiProtocol.ANTHROPIC_MESSAGES -> AnthropicService(
                apiKey = apiKey,
                modelId = modelId,
                systemPrompt = systemPrompt,
                temperature = settings.temperature,
                maxTokens = settings.maxTokens,
                topP = settings.topP
            )

            ApiProtocol.OPENAI_RESPONSES -> OpenAiCompatibleService(
                apiKey = apiKey,
                baseUrl = descriptor.defaultBaseUrl,
                modelId = modelId,
                systemPrompt = systemPrompt,
                providerType = AiProvider.OPENAI,
                temperature = settings.temperature,
                maxTokens = settings.maxTokens,
                topP = settings.topP,
                frequencyPenalty = settings.frequencyPenalty,
                presencePenalty = settings.presencePenalty,
                useResponsesApi = ProviderRequestPolicies.openAiPrefersResponses(modelId)
            )

            ApiProtocol.BAIDU_QIANFAN_V2 -> {
                if (settings.isBaiduLegacyMode()) {
                    BaiduService(
                        apiKey = settings.baiduApiKey,
                        secretKey = settings.baiduSecretKey,
                        modelId = modelId,
                        systemPrompt = systemPrompt,
                        temperature = settings.temperature,
                        topP = settings.topP
                    )
                } else {
                    QianfanV2Service(
                        apiKey = settings.baiduQianfanApiKey,
                        modelId = modelId,
                        systemPrompt = systemPrompt,
                        temperature = settings.temperature,
                        maxTokens = settings.maxTokens,
                        topP = settings.topP,
                        frequencyPenalty = settings.frequencyPenalty,
                        presencePenalty = settings.presencePenalty
                    )
                }
            }

            ApiProtocol.ANYTHING_LLM -> createAnythingLlmAdapter(settings)

            ApiProtocol.OPENAI_CHAT_COMPLETIONS, ApiProtocol.CUSTOM_OPENAI_COMPATIBLE ->
                createChatCompletionsService(settings, modelId, apiKey, systemPrompt)

            // BAIDU_LEGACY_RPC is unreachable: ProviderRegistry never maps a
            // provider to it (Baidu uses BAIDU_QIANFAN_V2); legacy auth is
            // handled by the isBaiduLegacyMode() check in that branch.
            ApiProtocol.BAIDU_LEGACY_RPC -> throw IllegalStateException(
                "BAIDU_LEGACY_RPC is not mapped by ProviderRegistry"
            )

            // On-device inference: no credentials, no network. The engine is
            // supplied lazily; when absent the service degrades gracefully.
            ApiProtocol.LOCAL_INFERENCE -> LocalGemmaService(
                modelId = modelId,
                systemPrompt = systemPrompt
            )
        }
    }

    /** Chat Completions family: provider-specific subclasses where required. */
    private fun createChatCompletionsService(
        settings: ApiSettings,
        modelId: String,
        apiKey: String,
        systemPrompt: String
    ): OpenAiCompatibleService {
        return when (settings.aiProvider) {
            AiProvider.DEEPSEEK -> DeepSeekService(
                apiKey = apiKey,
                baseUrl = settings.aiProvider.defaultBaseUrl,
                modelId = modelId,
                systemPrompt = systemPrompt,
                temperature = settings.temperature,
                maxTokens = settings.maxTokens,
                topP = settings.topP,
                frequencyPenalty = settings.frequencyPenalty,
                presencePenalty = settings.presencePenalty
            )

            AiProvider.PERPLEXITY -> PerplexityService(
                apiKey = apiKey,
                modelId = modelId,
                systemPrompt = systemPrompt,
                temperature = settings.temperature,
                maxTokens = settings.maxTokens,
                topP = settings.topP
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
                presencePenalty = settings.presencePenalty,
                useResponsesApi = settings.customProtocol == CUSTOM_PROTOCOL_RESPONSES,
                capabilityOverrides = ModelCapabilityResolver
                    .overridesFromKeys(
                        AiProvider.CUSTOM,
                        settings.customCapabilityOverrides,
                        modelId = settings.customModelName.ifBlank { modelId }
                    )
            )

            else -> OpenAiCompatibleService(
                apiKey = apiKey,
                baseUrl = if (settings.aiProvider == AiProvider.ALIBABA) {
                    settings.getCurrentBaseUrl()
                } else {
                    settings.aiProvider.defaultBaseUrl
                },
                modelId = modelId,
                systemPrompt = systemPrompt,
                providerType = settings.aiProvider,
                temperature = settings.temperature,
                maxTokens = settings.maxTokens,
                topP = settings.topP,
                frequencyPenalty = settings.frequencyPenalty,
                presencePenalty = settings.presencePenalty
            )
        }
    }

    private fun createAnythingLlmAdapter(settings: ApiSettings): AiServiceProvider {
        val providerSetting = ProviderSetting.AnythingLLM(
            serverUrl = settings.anythingllmServerUrl,
            apiKey = settings.anythingllmApiKey,
            workspaceSlug = settings.anythingllmWorkspaceSlug
        )
        val anythingLlmProvider = AnythingLLMProvider()
        // Guarded by historyMutex; the user turn is committed only on success
        // and the window is bounded so long sessions cannot grow without limit.
        val history = mutableListOf<ChatMessage>()
        val historyMutex = Mutex()
        return object : AiServiceProvider {
            override val provider: AiProvider = AiProvider.ANYTHINGLLM
            override suspend fun transcribe(pcmAudioData: ByteArray, languageCode: String): SpeechResult =
                SpeechResult.Error("AnythingLLM does not support speech recognition")
            override suspend fun chat(userMessage: String): String {
                historyMutex.lock()
                try {
                    val pending = history + ChatMessage(MessageRole.USER, userMessage)
                    return when (val result = anythingLlmProvider.generateText(providerSetting, pending)) {
                        is GenerationResult.Success -> {
                            history.add(ChatMessage(MessageRole.USER, userMessage))
                            history.add(ChatMessage(MessageRole.ASSISTANT, result.text))
                            while (history.size > ANYTHINGLLM_MAX_HISTORY_MESSAGES) {
                                history.removeAt(0)
                            }
                            result.text
                        }
                        is GenerationResult.Error -> {
                            Log.w(TAG, "AnythingLLM chat failed (code=${result.code}): ${result.message}")
                            "Error: ${result.message}"
                        }
                    }
                } finally {
                    historyMutex.unlock()
                }
            }
            override suspend fun analyzeImage(imageData: ByteArray, prompt: String): String =
                "AnythingLLM does not support image analysis"
            override fun clearHistory() {
                // clearHistory is non-suspend; block briefly on the same mutex.
                runBlocking { historyMutex.withLock { history.clear() } }
            }
        }
    }

    /**
     * Create service for testing connection
     */
    fun createTestService(settings: ApiSettings): OpenAiCompatibleService? {
        return when (settings.aiProvider) {
            AiProvider.GEMINI, AiProvider.ANTHROPIC,
            AiProvider.GEMINI_LIVE, AiProvider.ANYTHINGLLM,
            AiProvider.LOCAL_GEMMA -> null // Not OpenAI-compatible

            AiProvider.BAIDU -> if (settings.isBaiduLegacyMode()) {
                null // Legacy OAuth flow: use createBaiduTestService
            } else {
                QianfanV2Service(
                    apiKey = settings.baiduQianfanApiKey,
                    modelId = settings.getCurrentModelId(),
                    systemPrompt = ""
                )
            }

            AiProvider.DEEPSEEK -> DeepSeekService(
                apiKey = settings.getCurrentApiKey(),
                baseUrl = settings.aiProvider.defaultBaseUrl,
                modelId = settings.getCurrentModelId(),
                systemPrompt = ""
            )

            AiProvider.PERPLEXITY -> PerplexityService(
                apiKey = settings.getCurrentApiKey(),
                modelId = settings.getCurrentModelId(),
                systemPrompt = ""
            )

            AiProvider.OPENAI -> OpenAiCompatibleService(
                apiKey = settings.getCurrentApiKey(),
                baseUrl = settings.aiProvider.defaultBaseUrl,
                modelId = settings.getCurrentModelId(),
                systemPrompt = "",
                providerType = settings.aiProvider,
                useResponsesApi = ProviderRequestPolicies.openAiPrefersResponses(settings.getCurrentModelId())
            )

            AiProvider.GROQ,
            AiProvider.XAI, AiProvider.ALIBABA, AiProvider.ZHIPU,
            AiProvider.MOONSHOT, AiProvider.MISTRAL -> OpenAiCompatibleService(
                apiKey = settings.getCurrentApiKey(),
                baseUrl = if (settings.aiProvider == AiProvider.ALIBABA) {
                    settings.getCurrentBaseUrl()
                } else {
                    settings.aiProvider.defaultBaseUrl
                },
                modelId = settings.getCurrentModelId(),
                systemPrompt = "",
                providerType = settings.aiProvider
            )

            AiProvider.CUSTOM -> OpenAiCompatibleService(
                apiKey = settings.customApiKey,
                baseUrl = settings.getCurrentBaseUrl(),
                modelId = settings.customModelName.ifBlank { settings.getCurrentModelId() },
                systemPrompt = "",
                providerType = AiProvider.CUSTOM,
                useResponsesApi = settings.customProtocol == CUSTOM_PROTOCOL_RESPONSES,
                capabilityOverrides = ModelCapabilityResolver
                    .overridesFromKeys(
                        AiProvider.CUSTOM,
                        settings.customCapabilityOverrides,
                        modelId = settings.customModelName.ifBlank { settings.getCurrentModelId() }
                    )
            )
        }
    }

    /**
     * Create Baidu legacy test service (API Key + Secret Key OAuth)
     */
    fun createBaiduTestService(settings: ApiSettings): BaiduService? {
        return if (settings.aiProvider == AiProvider.BAIDU && settings.isBaiduLegacyMode()) {
            BaiduService(
                apiKey = settings.baiduApiKey,
                secretKey = settings.baiduSecretKey,
                modelId = settings.getCurrentModelId(),
                systemPrompt = ""
            )
        } else null
    }

    /**
     * Create service by provider (for speech recognition service selection).
     * STT is decoupled from chat: only providers with a real transcription
     * endpoint get a service here.
     */
    fun createSpeechService(provider: AiProvider, apiKey: String): AiServiceProvider? {
        if (apiKey.isBlank()) return null
        return when (provider) {
            AiProvider.GEMINI -> GeminiService(apiKey = apiKey)
            AiProvider.OPENAI -> OpenAiCompatibleService(
                apiKey = apiKey,
                baseUrl = AiProvider.OPENAI.defaultBaseUrl,
                modelId = "gpt-5.1",
                providerType = AiProvider.OPENAI
            )
            AiProvider.GROQ -> OpenAiCompatibleService(
                apiKey = apiKey,
                baseUrl = AiProvider.GROQ.defaultBaseUrl,
                modelId = "openai/gpt-oss-120b",
                providerType = AiProvider.GROQ
            )
            // All other chat providers do not expose a transcription endpoint.
            else -> null
        }
    }
}
