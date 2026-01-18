package com.example.rokidphone.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Settings Repository
 * Uses EncryptedSharedPreferences for secure API Key storage
 */
class SettingsRepository(context: Context) {
    
    companion object {
        private const val PREFS_NAME = "rokid_api_settings"
        
        // Keys for general settings
        private const val KEY_AI_PROVIDER = "ai_provider"
        private const val KEY_AI_MODEL = "ai_model"
        private const val KEY_SPEECH_SERVICE = "speech_service"
        private const val KEY_SPEECH_LANGUAGE = "speech_language"
        private const val KEY_RESPONSE_LANGUAGE = "response_language"
        private const val KEY_SYSTEM_PROMPT = "system_prompt"
        
        // Keys for API keys (stored encrypted)
        private const val KEY_GEMINI_API_KEY = "gemini_api_key"
        private const val KEY_OPENAI_API_KEY = "openai_api_key"
        private const val KEY_ANTHROPIC_API_KEY = "anthropic_api_key"
        private const val KEY_DEEPSEEK_API_KEY = "deepseek_api_key"
        private const val KEY_GROQ_API_KEY = "groq_api_key"
        private const val KEY_XAI_API_KEY = "xai_api_key"
        private const val KEY_ALIBABA_API_KEY = "alibaba_api_key"
        private const val KEY_ZHIPU_API_KEY = "zhipu_api_key"
        private const val KEY_BAIDU_API_KEY = "baidu_api_key"
        private const val KEY_BAIDU_SECRET_KEY = "baidu_secret_key"
        private const val KEY_PERPLEXITY_API_KEY = "perplexity_api_key"
        private const val KEY_CUSTOM_API_KEY = "custom_api_key"
        
        // Keys for custom provider settings
        private const val KEY_CUSTOM_BASE_URL = "custom_base_url"
        private const val KEY_CUSTOM_MODEL_NAME = "custom_model_name"
        
        @Volatile
        private var instance: SettingsRepository? = null
        
        fun getInstance(context: Context): SettingsRepository {
            return instance ?: synchronized(this) {
                instance ?: SettingsRepository(context.applicationContext).also { instance = it }
            }
        }
    }
    
    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()
    
    private val prefs: SharedPreferences = try {
        EncryptedSharedPreferences.create(
            context,
            PREFS_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    } catch (e: Exception) {
        // Fallback to regular SharedPreferences if encryption fails
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }
    
    private val _settingsFlow = MutableStateFlow(loadSettings())
    val settingsFlow: StateFlow<ApiSettings> = _settingsFlow.asStateFlow()
    
    /**
     * Get current settings
     */
    fun getSettings(): ApiSettings = _settingsFlow.value
    
    /**
     * Load settings
     */
    private fun loadSettings(): ApiSettings {
        return ApiSettings(
            aiProvider = AiProvider.fromName(
                prefs.getString(KEY_AI_PROVIDER, AiProvider.GEMINI.name) ?: AiProvider.GEMINI.name
            ),
            aiModelId = prefs.getString(KEY_AI_MODEL, "gemini-2.5-flash") ?: "gemini-2.5-flash",
            geminiApiKey = prefs.getString(KEY_GEMINI_API_KEY, "") ?: "",
            openaiApiKey = prefs.getString(KEY_OPENAI_API_KEY, "") ?: "",
            anthropicApiKey = prefs.getString(KEY_ANTHROPIC_API_KEY, "") ?: "",
            deepseekApiKey = prefs.getString(KEY_DEEPSEEK_API_KEY, "") ?: "",
            groqApiKey = prefs.getString(KEY_GROQ_API_KEY, "") ?: "",
            xaiApiKey = prefs.getString(KEY_XAI_API_KEY, "") ?: "",
            alibabaApiKey = prefs.getString(KEY_ALIBABA_API_KEY, "") ?: "",
            zhipuApiKey = prefs.getString(KEY_ZHIPU_API_KEY, "") ?: "",
            baiduApiKey = prefs.getString(KEY_BAIDU_API_KEY, "") ?: "",
            baiduSecretKey = prefs.getString(KEY_BAIDU_SECRET_KEY, "") ?: "",
            perplexityApiKey = prefs.getString(KEY_PERPLEXITY_API_KEY, "") ?: "",
            customApiKey = prefs.getString(KEY_CUSTOM_API_KEY, "") ?: "",
            customBaseUrl = prefs.getString(KEY_CUSTOM_BASE_URL, "http://localhost:11434/v1/") 
                ?: "http://localhost:11434/v1/",
            customModelName = prefs.getString(KEY_CUSTOM_MODEL_NAME, "llama4") ?: "llama4",
            speechService = SpeechService.fromName(
                prefs.getString(KEY_SPEECH_SERVICE, SpeechService.GEMINI_AUDIO.name) ?: SpeechService.GEMINI_AUDIO.name
            ),
            speechLanguage = prefs.getString(KEY_SPEECH_LANGUAGE, "zh-TW") ?: "zh-TW",
            responseLanguage = prefs.getString(KEY_RESPONSE_LANGUAGE, "zh-TW") ?: "zh-TW",
            systemPrompt = prefs.getString(KEY_SYSTEM_PROMPT, 
                "You are a friendly AI assistant. Please answer questions concisely.") 
                ?: "You are a friendly AI assistant. Please answer questions concisely."
        )
    }
    
    /**
     * Save settings
     */
    fun saveSettings(settings: ApiSettings) {
        prefs.edit().apply {
            putString(KEY_AI_PROVIDER, settings.aiProvider.name)
            putString(KEY_AI_MODEL, settings.aiModelId)
            putString(KEY_GEMINI_API_KEY, settings.geminiApiKey)
            putString(KEY_OPENAI_API_KEY, settings.openaiApiKey)
            putString(KEY_ANTHROPIC_API_KEY, settings.anthropicApiKey)
            putString(KEY_DEEPSEEK_API_KEY, settings.deepseekApiKey)
            putString(KEY_GROQ_API_KEY, settings.groqApiKey)
            putString(KEY_XAI_API_KEY, settings.xaiApiKey)
            putString(KEY_ALIBABA_API_KEY, settings.alibabaApiKey)
            putString(KEY_ZHIPU_API_KEY, settings.zhipuApiKey)
            putString(KEY_BAIDU_API_KEY, settings.baiduApiKey)
            putString(KEY_BAIDU_SECRET_KEY, settings.baiduSecretKey)
            putString(KEY_PERPLEXITY_API_KEY, settings.perplexityApiKey)
            putString(KEY_CUSTOM_API_KEY, settings.customApiKey)
            putString(KEY_CUSTOM_BASE_URL, settings.customBaseUrl)
            putString(KEY_CUSTOM_MODEL_NAME, settings.customModelName)
            putString(KEY_SPEECH_SERVICE, settings.speechService.name)
            putString(KEY_SPEECH_LANGUAGE, settings.speechLanguage)
            putString(KEY_RESPONSE_LANGUAGE, settings.responseLanguage)
            putString(KEY_SYSTEM_PROMPT, settings.systemPrompt)
            apply()
        }
        _settingsFlow.value = settings
    }
    
    /**
     * Update single setting
     */
    fun updateAiProvider(provider: AiProvider) {
        val current = getSettings()
        // When switching provider, auto-select the first model of that provider
        val defaultModel = AvailableModels.getModelsForProvider(provider).firstOrNull()?.id 
            ?: current.aiModelId
        saveSettings(current.copy(aiProvider = provider, aiModelId = defaultModel))
    }
    
    fun updateAiModel(modelId: String) {
        saveSettings(getSettings().copy(aiModelId = modelId))
    }
    
    fun updateGeminiApiKey(apiKey: String) {
        saveSettings(getSettings().copy(geminiApiKey = apiKey))
    }
    
    fun updateOpenaiApiKey(apiKey: String) {
        saveSettings(getSettings().copy(openaiApiKey = apiKey))
    }
    
    fun updateAnthropicApiKey(apiKey: String) {
        saveSettings(getSettings().copy(anthropicApiKey = apiKey))
    }
    
    fun updateDeepseekApiKey(apiKey: String) {
        saveSettings(getSettings().copy(deepseekApiKey = apiKey))
    }
    
    fun updateGroqApiKey(apiKey: String) {
        saveSettings(getSettings().copy(groqApiKey = apiKey))
    }
    
    fun updateXaiApiKey(apiKey: String) {
        saveSettings(getSettings().copy(xaiApiKey = apiKey))
    }
    
    fun updateAlibabaApiKey(apiKey: String) {
        saveSettings(getSettings().copy(alibabaApiKey = apiKey))
    }
    
    fun updateZhipuApiKey(apiKey: String) {
        saveSettings(getSettings().copy(zhipuApiKey = apiKey))
    }
    
    fun updateBaiduApiKey(apiKey: String) {
        saveSettings(getSettings().copy(baiduApiKey = apiKey))
    }
    
    fun updateBaiduSecretKey(secretKey: String) {
        saveSettings(getSettings().copy(baiduSecretKey = secretKey))
    }
    
    fun updateCustomApiKey(apiKey: String) {
        saveSettings(getSettings().copy(customApiKey = apiKey))
    }
    
    fun updateCustomBaseUrl(baseUrl: String) {
        saveSettings(getSettings().copy(customBaseUrl = baseUrl))
    }
    
    fun updateCustomModelName(modelName: String) {
        saveSettings(getSettings().copy(customModelName = modelName))
    }
    
    fun updateSpeechService(service: SpeechService) {
        saveSettings(getSettings().copy(speechService = service))
    }
    
    fun updateSystemPrompt(prompt: String) {
        saveSettings(getSettings().copy(systemPrompt = prompt))
    }
}
