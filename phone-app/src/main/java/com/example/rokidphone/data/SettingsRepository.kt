package com.example.rokidphone.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.example.rokidphone.R
import com.example.rokidphone.service.stt.SttProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

/**
 * Settings Repository
 * Uses EncryptedSharedPreferences for secure API Key storage
 */
class SettingsRepository(private val context: Context) {
    
    companion object {
        private const val PREFS_NAME = "rokid_api_settings"
        
        // Keys for general settings
        private const val KEY_AI_PROVIDER = "ai_provider"
        private const val KEY_AI_MODEL = "ai_model"
        private const val KEY_STT_PROVIDER = "stt_provider"
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
        private const val KEY_MOONSHOT_API_KEY = "moonshot_api_key"
        private const val KEY_CUSTOM_API_KEY = "custom_api_key"
        
        // Keys for custom provider settings
        private const val KEY_CUSTOM_BASE_URL = "custom_base_url"
        private const val KEY_CUSTOM_MODEL_NAME = "custom_model_name"
        
        // Keys for recording settings
        private const val KEY_AUTO_ANALYZE_RECORDINGS = "auto_analyze_recordings"
        private const val KEY_PUSH_CHAT_TO_GLASSES = "push_chat_to_glasses"
        private const val KEY_PUSH_RECORDING_TO_GLASSES = "push_recording_to_glasses"
        
        // Keys for LLM parameters
        private const val KEY_TEMPERATURE = "llm_temperature"
        private const val KEY_MAX_TOKENS = "llm_max_tokens"
        private const val KEY_TOP_P = "llm_top_p"
        private const val KEY_FREQUENCY_PENALTY = "llm_frequency_penalty"
        private const val KEY_PRESENCE_PENALTY = "llm_presence_penalty"
        
        @Volatile
        private var instance: SettingsRepository? = null
        
        fun getInstance(context: Context): SettingsRepository {
            return instance ?: synchronized(this) {
                instance ?: SettingsRepository(context.applicationContext).also { instance = it }
            }
        }
    }
    
    private val masterKey = MasterKey.Builder(context, MasterKey.DEFAULT_MASTER_KEY_ALIAS)
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
        // Get saved system prompt or use current locale's default
        val savedSystemPrompt = prefs.getString(KEY_SYSTEM_PROMPT, null)
        val currentLocaleDefault = context.getString(R.string.default_system_prompt)
        
        // Sync system prompt with current app language if it's a default prompt from another language
        val systemPrompt = if (savedSystemPrompt == null) {
            currentLocaleDefault
        } else if (isDefaultPromptFromDifferentLocale(savedSystemPrompt)) {
            // User is using a default prompt but from a different language - update to current locale
            android.util.Log.d("SettingsRepository", "Syncing system prompt to current locale")
            prefs.edit().putString(KEY_SYSTEM_PROMPT, currentLocaleDefault).apply()
            currentLocaleDefault
        } else {
            savedSystemPrompt
        }
        
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
            moonshotApiKey = prefs.getString(KEY_MOONSHOT_API_KEY, "") ?: "",
            customApiKey = prefs.getString(KEY_CUSTOM_API_KEY, "") ?: "",
            customBaseUrl = prefs.getString(KEY_CUSTOM_BASE_URL, "http://localhost:11434/v1/") 
                ?: "http://localhost:11434/v1/",
            customModelName = prefs.getString(KEY_CUSTOM_MODEL_NAME, "llama4") ?: "llama4",
            sttProvider = SttProvider.fromName(
                prefs.getString(KEY_STT_PROVIDER, SttProvider.GEMINI.name) ?: SttProvider.GEMINI.name
            ),
            // Use device locale (e.g. "ko-KR") as the first-run default so new users get
            // the correct TTS and response language automatically.
            // Existing users who already have a saved value keep their preference unchanged.
            speechLanguage = prefs.getString(
                KEY_SPEECH_LANGUAGE,
                Locale.getDefault().toLanguageTag()
            ) ?: Locale.getDefault().toLanguageTag(),
            responseLanguage = prefs.getString(
                KEY_RESPONSE_LANGUAGE,
                Locale.getDefault().toLanguageTag()
            ) ?: Locale.getDefault().toLanguageTag(),
            systemPrompt = systemPrompt,
            autoAnalyzeRecordings = prefs.getBoolean(KEY_AUTO_ANALYZE_RECORDINGS, true),
            pushChatToGlasses = prefs.getBoolean(KEY_PUSH_CHAT_TO_GLASSES, true),
            pushRecordingToGlasses = prefs.getBoolean(KEY_PUSH_RECORDING_TO_GLASSES, true),
            temperature = prefs.getFloat(KEY_TEMPERATURE, 0.7f),
            maxTokens = prefs.getInt(KEY_MAX_TOKENS, 2048),
            topP = prefs.getFloat(KEY_TOP_P, 1.0f),
            frequencyPenalty = prefs.getFloat(KEY_FREQUENCY_PENALTY, 0.0f),
            presencePenalty = prefs.getFloat(KEY_PRESENCE_PENALTY, 0.0f)
        )
    }
    
    /**
     * Check if a system prompt is a default prompt from a different locale than the current app locale
     */
    private fun isDefaultPromptFromDifferentLocale(prompt: String): Boolean {
        val currentDefault = context.getString(R.string.default_system_prompt)
        
        // If it matches current locale's default, it's fine
        if (prompt == currentDefault) return false
        
        // Check if it's a default prompt from any other language
        for (lang in AppLanguage.entries) {
            try {
                val langDefault = getDefaultSystemPromptForLanguage(lang)
                if (prompt == langDefault) {
                    // It's a default prompt from a different language - needs sync
                    return true
                }
            } catch (e: Exception) {
                // Ignore errors
            }
        }
        
        // It's a custom prompt, don't touch it
        return false
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
            putString(KEY_MOONSHOT_API_KEY, settings.moonshotApiKey)
            putString(KEY_CUSTOM_API_KEY, settings.customApiKey)
            putString(KEY_CUSTOM_BASE_URL, settings.customBaseUrl)
            putString(KEY_CUSTOM_MODEL_NAME, settings.customModelName)
            putString(KEY_STT_PROVIDER, settings.sttProvider.name)
            putString(KEY_SPEECH_LANGUAGE, settings.speechLanguage)
            putString(KEY_RESPONSE_LANGUAGE, settings.responseLanguage)
            putString(KEY_SYSTEM_PROMPT, settings.systemPrompt)
            putBoolean(KEY_AUTO_ANALYZE_RECORDINGS, settings.autoAnalyzeRecordings)
            putBoolean(KEY_PUSH_CHAT_TO_GLASSES, settings.pushChatToGlasses)
            putBoolean(KEY_PUSH_RECORDING_TO_GLASSES, settings.pushRecordingToGlasses)
            putFloat(KEY_TEMPERATURE, settings.temperature)
            putInt(KEY_MAX_TOKENS, settings.maxTokens)
            putFloat(KEY_TOP_P, settings.topP)
            putFloat(KEY_FREQUENCY_PENALTY, settings.frequencyPenalty)
            putFloat(KEY_PRESENCE_PENALTY, settings.presencePenalty)
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
    
    fun updatePerplexityApiKey(apiKey: String) {
        saveSettings(getSettings().copy(perplexityApiKey = apiKey))
    }
    
    fun updateMoonshotApiKey(apiKey: String) {
        saveSettings(getSettings().copy(moonshotApiKey = apiKey))
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
    
    fun updateSttProvider(provider: SttProvider) {
        saveSettings(getSettings().copy(sttProvider = provider))
    }
    
    fun updateSystemPrompt(prompt: String) {
        saveSettings(getSettings().copy(systemPrompt = prompt))
    }
    
    /**
     * Get the default system prompt in the current locale
     */
    fun getDefaultSystemPrompt(): String {
        return context.getString(R.string.default_system_prompt)
    }
    
    /**
     * Get the default system prompt for a specific language
     */
    fun getDefaultSystemPromptForLanguage(language: AppLanguage): String {
        val locale = LanguageManager.getLocale(language)
        val configuration = android.content.res.Configuration(context.resources.configuration)
        configuration.setLocale(locale)
        val localizedContext = context.createConfigurationContext(configuration)
        return localizedContext.getString(R.string.default_system_prompt)
    }
    
    /**
     * Check if the current system prompt is a default prompt (any language)
     */
    fun isUsingDefaultSystemPrompt(): Boolean {
        val currentPrompt = getSettings().systemPrompt
        if (currentPrompt.isEmpty()) return true
        
        // Check against all language defaults
        return AppLanguage.entries.any { lang ->
            try {
                getDefaultSystemPromptForLanguage(lang) == currentPrompt
            } catch (e: Exception) {
                false
            }
        }
    }
    
    /**
     * Reset system prompt to default (localized)
     */
    fun resetSystemPromptToDefault() {
        updateSystemPrompt(getDefaultSystemPrompt())
    }
}
