package com.example.rokidphone.ai.provider

import android.content.Context
import android.util.Log
import com.example.rokidphone.data.AiProvider
import com.example.rokidphone.data.ApiSettings
import com.example.rokidphone.data.SettingsRepository
import com.example.rokidphone.service.ai.AiServiceFactory
import com.example.rokidphone.service.ai.AiServiceProvider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Provider Manager
 * Centrally manages all AI Provider instances and settings
 * Based on RikkaHub's ProviderManager design
 */
class ProviderManager private constructor(
    private val context: Context
) {
    companion object {
        private const val TAG = "ProviderManager"
        
        @Volatile
        private var instance: ProviderManager? = null
        
        fun getInstance(context: Context): ProviderManager {
            return instance ?: synchronized(this) {
                instance ?: ProviderManager(context.applicationContext).also { instance = it }
            }
        }
    }
    
    private val settingsRepository = SettingsRepository.getInstance(context)
    private val mutex = Mutex()
    
    // Cached AI service instance
    private var cachedService: AiServiceProvider? = null
    private var cachedSettings: ApiSettings? = null
    
    // Currently active provider setting
    private val _activeProviderSetting = MutableStateFlow<ProviderSetting?>(null)
    val activeProviderSetting: StateFlow<ProviderSetting?> = _activeProviderSetting.asStateFlow()
    
    // List of all configured providers
    private val _configuredProviders = MutableStateFlow<List<ProviderSetting>>(emptyList())
    val configuredProviders: StateFlow<List<ProviderSetting>> = _configuredProviders.asStateFlow()
    
    init {
        // Initialize from existing settings
        refreshFromSettings()
    }
    
    /**
     * Sync settings from SettingsRepository
     */
    fun refreshFromSettings() {
        val settings = settingsRepository.getSettings()
        _activeProviderSetting.value = convertToProviderSetting(settings)
        _configuredProviders.value = getAllConfiguredProviders(settings)
    }
    
    /**
     * Get the currently active AI service
     */
    suspend fun getActiveService(): AiServiceProvider? = mutex.withLock {
        val currentSettings = settingsRepository.getSettings()
        
        // Return cached service if settings haven't changed
        if (cachedSettings == currentSettings && cachedService != null) {
            return cachedService
        }
        
        // Create new service
        return try {
            val service = AiServiceFactory.createService(currentSettings)
            cachedService = service
            cachedSettings = currentSettings
            Log.d(TAG, "Created new AI service: ${currentSettings.aiProvider}")
            service
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create AI service", e)
            null
        }
    }
    
    /**
     * Get service by Provider ID
     */
    suspend fun getServiceForProvider(providerId: String): AiServiceProvider? = mutex.withLock {
        val settings = settingsRepository.getSettings()
        val provider = AiProvider.fromName(providerId.uppercase())
        
        // Create temporary settings
        val tempSettings = settings.copy(aiProvider = provider)
        
        return try {
            AiServiceFactory.createService(tempSettings)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create service for provider: $providerId", e)
            null
        }
    }
    
    /**
     * Switch the active Provider
     */
    fun switchProvider(provider: AiProvider) {
        settingsRepository.updateAiProvider(provider)
        cachedService = null
        cachedSettings = null
        refreshFromSettings()
    }
    
    /**
     * Switch the model
     */
    fun switchModel(modelId: String) {
        settingsRepository.updateAiModel(modelId)
        cachedService = null
        cachedSettings = null
        refreshFromSettings()
    }
    
    /**
     * Update API Key
     */
    fun updateApiKey(provider: AiProvider, apiKey: String) {
        when (provider) {
            AiProvider.GEMINI -> settingsRepository.updateGeminiApiKey(apiKey)
            AiProvider.OPENAI -> settingsRepository.updateOpenaiApiKey(apiKey)
            AiProvider.ANTHROPIC -> settingsRepository.updateAnthropicApiKey(apiKey)
            AiProvider.DEEPSEEK -> settingsRepository.updateDeepseekApiKey(apiKey)
            AiProvider.GROQ -> settingsRepository.updateGroqApiKey(apiKey)
            AiProvider.XAI -> settingsRepository.updateXaiApiKey(apiKey)
            AiProvider.ALIBABA -> settingsRepository.updateAlibabaApiKey(apiKey)
            AiProvider.ZHIPU -> settingsRepository.updateZhipuApiKey(apiKey)
            AiProvider.BAIDU -> settingsRepository.updateBaiduApiKey(apiKey)
            AiProvider.PERPLEXITY -> settingsRepository.updatePerplexityApiKey(apiKey)
            AiProvider.MOONSHOT -> settingsRepository.updateMoonshotApiKey(apiKey)
            AiProvider.GEMINI_LIVE -> settingsRepository.updateGeminiApiKey(apiKey)  // Shares Gemini API key
            AiProvider.CUSTOM -> settingsRepository.updateCustomApiKey(apiKey)
        }
        cachedService = null
        cachedSettings = null
        refreshFromSettings()
    }
    
    /**
     * Clear service cache (when settings change)
     */
    fun invalidateCache() {
        cachedService = null
        cachedSettings = null
    }
    
    /**
     * List providers that support speech recognition
     */
    fun getSpeechProviders(): List<AiProvider> {
        return AiProvider.entries.filter { it.supportsSpeech }
    }
    
    /**
     * List providers that support vision
     */
    fun getVisionProviders(): List<AiProvider> {
        return AiProvider.entries.filter { it.supportsVision }
    }
    
    /**
     * Convert ApiSettings to ProviderSetting
     */
    private fun convertToProviderSetting(settings: ApiSettings): ProviderSetting {
        return when (settings.aiProvider) {
            AiProvider.GEMINI -> ProviderSetting.Gemini(
                apiKey = settings.geminiApiKey,
                modelId = settings.aiModelId
            )
            AiProvider.OPENAI -> ProviderSetting.OpenAI(
                apiKey = settings.openaiApiKey,
                modelId = settings.aiModelId
            )
            AiProvider.ANTHROPIC -> ProviderSetting.Anthropic(
                apiKey = settings.anthropicApiKey,
                modelId = settings.aiModelId
            )
            AiProvider.DEEPSEEK -> ProviderSetting.DeepSeek(
                apiKey = settings.deepseekApiKey,
                modelId = settings.aiModelId
            )
            AiProvider.GROQ -> ProviderSetting.Groq(
                apiKey = settings.groqApiKey,
                modelId = settings.aiModelId
            )
            AiProvider.XAI -> ProviderSetting.XAI(
                apiKey = settings.xaiApiKey,
                modelId = settings.aiModelId
            )
            AiProvider.ALIBABA -> ProviderSetting.Alibaba(
                apiKey = settings.alibabaApiKey,
                modelId = settings.aiModelId
            )
            AiProvider.ZHIPU -> ProviderSetting.Zhipu(
                apiKey = settings.zhipuApiKey,
                modelId = settings.aiModelId
            )
            AiProvider.BAIDU -> ProviderSetting.Baidu(
                apiKey = settings.baiduApiKey,
                secretKey = settings.baiduSecretKey,
                modelId = settings.aiModelId
            )
            AiProvider.PERPLEXITY -> ProviderSetting.Perplexity(
                apiKey = settings.perplexityApiKey,
                modelId = settings.aiModelId
            )
            AiProvider.MOONSHOT -> ProviderSetting.Moonshot(
                apiKey = settings.moonshotApiKey,
                modelId = settings.aiModelId
            )
            AiProvider.CUSTOM -> ProviderSetting.Custom(
                apiKey = settings.customApiKey,
                modelId = settings.customModelName,
                baseUrl = settings.customBaseUrl
            )
            AiProvider.GEMINI_LIVE -> ProviderSetting.Gemini(
                apiKey = settings.geminiApiKey,
                modelId = settings.aiModelId
            )
        }
    }
    
    /**
     * Get all configured Providers
     */
    private fun getAllConfiguredProviders(settings: ApiSettings): List<ProviderSetting> {
        return buildList {
            if (settings.geminiApiKey.isNotBlank()) {
                add(ProviderSetting.Gemini(apiKey = settings.geminiApiKey))
            }
            if (settings.openaiApiKey.isNotBlank()) {
                add(ProviderSetting.OpenAI(apiKey = settings.openaiApiKey))
            }
            if (settings.anthropicApiKey.isNotBlank()) {
                add(ProviderSetting.Anthropic(apiKey = settings.anthropicApiKey))
            }
            if (settings.deepseekApiKey.isNotBlank()) {
                add(ProviderSetting.DeepSeek(apiKey = settings.deepseekApiKey))
            }
            if (settings.groqApiKey.isNotBlank()) {
                add(ProviderSetting.Groq(apiKey = settings.groqApiKey))
            }
            if (settings.xaiApiKey.isNotBlank()) {
                add(ProviderSetting.XAI(apiKey = settings.xaiApiKey))
            }
            if (settings.alibabaApiKey.isNotBlank()) {
                add(ProviderSetting.Alibaba(apiKey = settings.alibabaApiKey))
            }
            if (settings.zhipuApiKey.isNotBlank()) {
                add(ProviderSetting.Zhipu(apiKey = settings.zhipuApiKey))
            }
            if (settings.baiduApiKey.isNotBlank() && settings.baiduSecretKey.isNotBlank()) {
                add(ProviderSetting.Baidu(
                    apiKey = settings.baiduApiKey,
                    secretKey = settings.baiduSecretKey
                ))
            }
            if (settings.perplexityApiKey.isNotBlank()) {
                add(ProviderSetting.Perplexity(apiKey = settings.perplexityApiKey))
            }
            if (settings.moonshotApiKey.isNotBlank()) {
                add(ProviderSetting.Moonshot(apiKey = settings.moonshotApiKey))
            }
            if (settings.customBaseUrl.isNotBlank()) {
                add(ProviderSetting.Custom(
                    apiKey = settings.customApiKey,
                    baseUrl = settings.customBaseUrl,
                    modelId = settings.customModelName
                ))
            }
        }
    }
}
