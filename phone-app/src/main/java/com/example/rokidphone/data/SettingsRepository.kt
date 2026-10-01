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
import kotlinx.coroutines.flow.updateAndGet
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
        private const val KEY_MISTRAL_API_KEY = "mistral_api_key"
        private const val KEY_CUSTOM_API_KEY = "custom_api_key"
        private const val KEY_BAIDU_QIANFAN_API_KEY = "baidu_qianfan_api_key"
        private const val KEY_BAIDU_USE_LEGACY_AUTH = "baidu_use_legacy_auth"

        // Per-provider model memory (JSON map: provider name -> model id)
        private const val KEY_PROVIDER_MODEL_IDS = "provider_model_ids"
        private const val KEY_DECISION_ROUTING_ENABLED = "decision_routing_enabled"
        private const val KEY_GLASSES_DISPLAY_METRICS = "glasses_display_metrics"
        private const val KEY_GLASSES_DISPLAY_CONFIG = "glasses_display_config"
        private const val KEY_DECISION_BACKEND = "decision_backend"
        private const val KEY_DECISION_GEMINI_MODEL = "decision_gemini_model"
        private const val KEY_DECISION_OPENAI_MODEL = "decision_openai_model"
        private const val KEY_JEV_API_KEY = "jev_api_key"
        private const val KEY_LAYA_BASE_URL = "laya_base_url"
        private const val KEY_LAYA_API_KEY = "laya_api_key"
        private const val KEY_FAST_ROUTING_MODEL = "fast_routing_model"
        private const val KEY_BALANCED_ROUTING_MODEL = "balanced_routing_model"
        private const val KEY_QUALITY_ROUTING_MODEL = "quality_routing_model"

        // Alibaba Cloud Model Studio region
        private const val KEY_ALIBABA_REGION = "alibaba_region"
        private const val KEY_ALIBABA_CUSTOM_BASE_URL = "alibaba_custom_base_url"

        // Custom endpoint protocol settings
        private const val KEY_CUSTOM_PROTOCOL = "custom_protocol"
        private const val KEY_CUSTOM_MODELS_PATH = "custom_models_path"
        private const val KEY_CUSTOM_CAPABILITY_OVERRIDES = "custom_capability_overrides"

        // Keys for AnythingLLM provider settings (stored encrypted)
        private const val KEY_ANYTHINGLLM_SERVER_URL = "anythingllm_server_url"
        private const val KEY_ANYTHINGLLM_API_KEY = "anythingllm_api_key"
        private const val KEY_ANYTHINGLLM_WORKSPACE_SLUG = "anythingllm_workspace_slug"

        // Keys for custom provider settings
        private const val KEY_CUSTOM_BASE_URL = "custom_base_url"
        private const val KEY_CUSTOM_MODEL_NAME = "custom_model_name"
        
        // Keys for recording settings
        private const val KEY_AUTO_ANALYZE_RECORDINGS = "auto_analyze_recordings"
        private const val KEY_PUSH_CHAT_TO_GLASSES = "push_chat_to_glasses"
        private const val KEY_PUSH_RECORDING_TO_GLASSES = "push_recording_to_glasses"
        
        // Keys for TTS settings
        private const val KEY_TTS_PROVIDER = "tts_provider"
        private const val KEY_TTS_VOICE_OVERRIDE = "tts_voice_override"
        private const val KEY_TTS_SPEECH_RATE = "tts_speech_rate"
        private const val KEY_TTS_PITCH = "tts_pitch"
        private const val KEY_SYSTEM_TTS_SPEECH_RATE = "system_tts_speech_rate"
        private const val KEY_SYSTEM_TTS_PITCH = "system_tts_pitch"

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
    
    /**
     * Non-null when Android Keystore-backed encrypted storage could not be
     * initialized. In that case settings are kept in memory only for the
     * session — they are NEVER silently written to plaintext preferences.
     * UI should surface this to the user.
     */
    private val _secureStorageError = MutableStateFlow<String?>(null)
    val secureStorageError: StateFlow<String?> = _secureStorageError.asStateFlow()

    val isSecureStorageAvailable: Boolean
        get() = _secureStorageError.value == null

    private val prefs: SharedPreferences = try {
        val masterKey = MasterKey.Builder(context, MasterKey.DEFAULT_MASTER_KEY_ALIAS)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            PREFS_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    } catch (e: Exception) {
        android.util.Log.e(
            "SettingsRepository",
            "Secure storage unavailable; settings will be kept in memory only (never persisted in plaintext)",
            e
        )
        _secureStorageError.value = context.getString(R.string.secure_storage_unavailable)
        InMemorySharedPreferences()
    }
    
    private val _settingsFlow = MutableStateFlow(run {
        val loaded = loadSettings()
        val migrated = loaded.migrateLegacyModelIds()
        if (migrated != loaded) {
            // Persist migration result so it runs only once.
            persistSettings(migrated)
        }
        migrated
    })
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
        
        val savedProvider = AiProvider.fromName(
            prefs.getString(KEY_AI_PROVIDER, AiProvider.GEMINI.name) ?: AiProvider.GEMINI.name
        )
        val legacyModelId = prefs.getString(KEY_AI_MODEL, "gemini-3.8-flash") ?: "gemini-3.8-flash"
        val providerModelIds = parseProviderModelIds(
            prefs.getString(KEY_PROVIDER_MODEL_IDS, null),
            savedProvider,
            legacyModelId
        )

        val baiduQianfanKey = prefs.getString(KEY_BAIDU_QIANFAN_API_KEY, "") ?: ""
        val baiduLegacyKey = prefs.getString(KEY_BAIDU_API_KEY, "") ?: ""
        val baiduLegacySecret = prefs.getString(KEY_BAIDU_SECRET_KEY, "") ?: ""
        // Migration: existing users with only the legacy key pair keep legacy mode
        // until they add a Qianfan API key. Their credentials are never deleted.
        val baiduUseLegacyAuth = if (prefs.contains(KEY_BAIDU_USE_LEGACY_AUTH)) {
            prefs.getBoolean(KEY_BAIDU_USE_LEGACY_AUTH, false)
        } else {
            baiduQianfanKey.isBlank() && baiduLegacyKey.isNotBlank() && baiduLegacySecret.isNotBlank()
        }

        return ApiSettings(
            aiProvider = savedProvider,
            aiModelId = legacyModelId,
            providerModelIds = providerModelIds,
            glassesDisplayMetrics = com.example.rokidcommon.protocol.GlassesDisplayMetrics.fromJson(prefs.getString(KEY_GLASSES_DISPLAY_METRICS, null)),
            glassesDisplayConfig = com.example.rokidcommon.protocol.GlassesDisplayConfig
                .fromJson(prefs.getString(KEY_GLASSES_DISPLAY_CONFIG, null))
                ?: com.example.rokidcommon.protocol.GlassesDisplayConfig(),
            decisionRoutingEnabled = prefs.getBoolean(KEY_DECISION_ROUTING_ENABLED, false),
            decisionBackend = runCatching {
                DecisionBackend.valueOf(prefs.getString(KEY_DECISION_BACKEND, DecisionBackend.JEV.name) ?: "")
            }.getOrDefault(DecisionBackend.JEV),
            decisionGeminiModel = prefs.getString(KEY_DECISION_GEMINI_MODEL, "gemini-3.8-flash") ?: "gemini-3.8-flash",
            decisionOpenaiModel = prefs.getString(KEY_DECISION_OPENAI_MODEL, "gpt-6-luna") ?: "gpt-6-luna",
            jevApiKey = prefs.getString(KEY_JEV_API_KEY, "") ?: "",
            layaBaseUrl = prefs.getString(KEY_LAYA_BASE_URL, "") ?: "",
            layaApiKey = prefs.getString(KEY_LAYA_API_KEY, "") ?: "",
            fastRoutingModel = parseRoutingModel(prefs.getString(KEY_FAST_ROUTING_MODEL, null)),
            balancedRoutingModel = parseRoutingModel(prefs.getString(KEY_BALANCED_ROUTING_MODEL, null)),
            qualityRoutingModel = parseRoutingModel(prefs.getString(KEY_QUALITY_ROUTING_MODEL, null)),
            geminiApiKey = prefs.getString(KEY_GEMINI_API_KEY, "") ?: "",
            openaiApiKey = prefs.getString(KEY_OPENAI_API_KEY, "") ?: "",
            anthropicApiKey = prefs.getString(KEY_ANTHROPIC_API_KEY, "") ?: "",
            deepseekApiKey = prefs.getString(KEY_DEEPSEEK_API_KEY, "") ?: "",
            groqApiKey = prefs.getString(KEY_GROQ_API_KEY, "") ?: "",
            xaiApiKey = prefs.getString(KEY_XAI_API_KEY, "") ?: "",
            alibabaApiKey = prefs.getString(KEY_ALIBABA_API_KEY, "") ?: "",
            zhipuApiKey = prefs.getString(KEY_ZHIPU_API_KEY, "") ?: "",
            baiduApiKey = baiduLegacyKey,
            baiduSecretKey = baiduLegacySecret,
            baiduQianfanApiKey = baiduQianfanKey,
            baiduUseLegacyAuth = baiduUseLegacyAuth,
            alibabaRegion = prefs.getString(KEY_ALIBABA_REGION, AlibabaRegions.CHINA) ?: AlibabaRegions.CHINA,
            alibabaCustomBaseUrl = prefs.getString(KEY_ALIBABA_CUSTOM_BASE_URL, "") ?: "",
            perplexityApiKey = prefs.getString(KEY_PERPLEXITY_API_KEY, "") ?: "",
            moonshotApiKey = prefs.getString(KEY_MOONSHOT_API_KEY, "") ?: "",
            mistralApiKey = prefs.getString(KEY_MISTRAL_API_KEY, "") ?: "",
            customApiKey = prefs.getString(KEY_CUSTOM_API_KEY, "") ?: "",
            customProtocol = prefs.getString(KEY_CUSTOM_PROTOCOL, "chat_completions") ?: "chat_completions",
            customModelsPath = prefs.getString(KEY_CUSTOM_MODELS_PATH, "models") ?: "models",
            customCapabilityOverrides = parseCapabilityOverrides(
                prefs.getString(KEY_CUSTOM_CAPABILITY_OVERRIDES, "") ?: ""
            ),
            anythingllmServerUrl = prefs.getString(KEY_ANYTHINGLLM_SERVER_URL, "") ?: "",
            anythingllmApiKey = prefs.getString(KEY_ANYTHINGLLM_API_KEY, "") ?: "",
            anythingllmWorkspaceSlug = prefs.getString(KEY_ANYTHINGLLM_WORKSPACE_SLUG, "") ?: "",
            customBaseUrl = prefs.getString(KEY_CUSTOM_BASE_URL, "http://localhost:11434/v1/")
                ?: "http://localhost:11434/v1/",
            customModelName = prefs.getString(KEY_CUSTOM_MODEL_NAME, "llama4") ?: "llama4",
            sttProvider = SttProvider.fromNameOrNull(
                prefs.getString(KEY_STT_PROVIDER, SttProvider.GEMINI.name) ?: SttProvider.GEMINI.name
            ) ?: SttProvider.GEMINI,
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
            ttsProvider = TtsProvider.fromName(
                prefs.getString(KEY_TTS_PROVIDER, TtsProvider.EDGE_TTS.name) ?: TtsProvider.EDGE_TTS.name
            ),
            ttsVoiceOverride = prefs.getString(KEY_TTS_VOICE_OVERRIDE, "") ?: "",
            ttsSpeechRate = prefs.getFloat(KEY_TTS_SPEECH_RATE, 1.0f),
            ttsPitch = prefs.getFloat(KEY_TTS_PITCH, 0.0f),
            systemTtsSpeechRate = prefs.getFloat(KEY_SYSTEM_TTS_SPEECH_RATE, 1.0f),
            systemTtsPitch = prefs.getFloat(KEY_SYSTEM_TTS_PITCH, 1.0f),
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
        
        // Check if it's a default prompt from any other language (cached: resolving a
        // per-language prompt creates a Configuration + context, which is expensive).
        return localizedDefaultPrompts.values.any { it == prompt }
    }

    /** Cached per-language default prompts (resources for a language never change at runtime). */
    private val localizedDefaultPrompts: Map<AppLanguage, String> by lazy {
        AppLanguage.entries.mapNotNull { lang ->
            try {
                lang to getDefaultSystemPromptForLanguage(lang)
            } catch (e: Exception) {
                android.util.Log.w("SettingsRepository", "Failed to resolve default prompt for $lang", e)
                null
            }
        }.toMap()
    }
    
    /**
     * Parse the persisted per-provider model map, seeding it from the legacy
     * single-model setting on first launch after upgrade (migration).
     */
    private fun parseProviderModelIds(
        json: String?,
        activeProvider: AiProvider,
        legacyModelId: String
    ): Map<String, String> {
        if (!json.isNullOrBlank()) {
            try {
                val obj = org.json.JSONObject(json)
                val map = mutableMapOf<String, String>()
                obj.keys().forEach { key ->
                    obj.optString(key).takeIf { it.isNotBlank() }?.let { map[key] = it }
                }
                if (map.isNotEmpty()) return map
            } catch (e: Exception) {
                // Corrupted value: reseed below. Log it — otherwise the per-provider
                // selections are silently discarded with no way to diagnose the data loss.
                android.util.Log.w("SettingsRepository", "Failed to parse provider model ids; reseeding from legacy model id", e)
            }
        }
        return if (legacyModelId.isNotBlank()) mapOf(activeProvider.name to legacyModelId) else emptyMap()
    }

    private fun serializeProviderModelIds(map: Map<String, String>): String {
        val obj = org.json.JSONObject()
        map.forEach { (k, v) -> obj.put(k, v) }
        return obj.toString()
    }

    private fun parseRoutingModel(raw: String?): RoutingModel? = runCatching {
        if (raw.isNullOrBlank()) return@runCatching null
        val json = org.json.JSONObject(raw)
        val provider = AiProvider.valueOf(json.getString("provider"))
        val modelId = json.getString("modelId").trim()
        if (modelId.isBlank()) null else RoutingModel(provider, modelId)
    }.getOrNull()

    private fun serializeRoutingModel(value: RoutingModel?): String? = value?.let {
        org.json.JSONObject().put("provider", it.provider.name).put("modelId", it.modelId).toString()
    }

    /**
     * Parse capability overrides. Current format is a JSON array (a comma join is lossy
     * for values containing ','); falls back to the legacy comma-separated format so
     * existing installs keep their overrides.
     */
    private fun parseCapabilityOverrides(raw: String): Set<String> {
        if (raw.isBlank()) return emptySet()
        return try {
            val array = org.json.JSONArray(raw)
            (0 until array.length()).map { array.getString(it).trim() }.filter { it.isNotBlank() }.toSet()
        } catch (e: org.json.JSONException) {
            raw.split(',').map { it.trim() }.filter { it.isNotBlank() }.toSet()
        }
    }

    /**
     * Save settings
     */
    fun saveSettings(settings: ApiSettings) {
        persistSettings(settings)
        _settingsFlow.value = settings
    }

    private fun persistSettings(settings: ApiSettings) {
        prefs.edit().apply {
            putString(KEY_AI_PROVIDER, settings.aiProvider.name)
            putString(KEY_AI_MODEL, settings.aiModelId)
            putString(KEY_PROVIDER_MODEL_IDS, serializeProviderModelIds(settings.providerModelIds))
            putBoolean(KEY_DECISION_ROUTING_ENABLED, settings.decisionRoutingEnabled)
            putString(KEY_GLASSES_DISPLAY_METRICS, settings.glassesDisplayMetrics?.toJson())
            putString(KEY_GLASSES_DISPLAY_CONFIG, settings.glassesDisplayConfig.normalized().toJson())
            putString(KEY_DECISION_BACKEND, settings.decisionBackend.name)
            putString(KEY_DECISION_GEMINI_MODEL, settings.decisionGeminiModel)
            putString(KEY_DECISION_OPENAI_MODEL, settings.decisionOpenaiModel)
            putString(KEY_JEV_API_KEY, settings.jevApiKey)
            putString(KEY_LAYA_BASE_URL, settings.layaBaseUrl)
            putString(KEY_LAYA_API_KEY, settings.layaApiKey)
            putString(KEY_FAST_ROUTING_MODEL, serializeRoutingModel(settings.fastRoutingModel))
            putString(KEY_BALANCED_ROUTING_MODEL, serializeRoutingModel(settings.balancedRoutingModel))
            putString(KEY_QUALITY_ROUTING_MODEL, serializeRoutingModel(settings.qualityRoutingModel))
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
            putString(KEY_BAIDU_QIANFAN_API_KEY, settings.baiduQianfanApiKey)
            putBoolean(KEY_BAIDU_USE_LEGACY_AUTH, settings.baiduUseLegacyAuth)
            putString(KEY_ALIBABA_REGION, settings.alibabaRegion)
            putString(KEY_ALIBABA_CUSTOM_BASE_URL, settings.alibabaCustomBaseUrl)
            putString(KEY_CUSTOM_PROTOCOL, settings.customProtocol)
            putString(KEY_CUSTOM_MODELS_PATH, settings.customModelsPath)
            putString(KEY_CUSTOM_CAPABILITY_OVERRIDES, org.json.JSONArray(settings.customCapabilityOverrides.toList()).toString())
            putString(KEY_PERPLEXITY_API_KEY, settings.perplexityApiKey)
            putString(KEY_MOONSHOT_API_KEY, settings.moonshotApiKey)
            putString(KEY_MISTRAL_API_KEY, settings.mistralApiKey)
            putString(KEY_CUSTOM_API_KEY, settings.customApiKey)
            putString(KEY_ANYTHINGLLM_SERVER_URL, settings.anythingllmServerUrl)
            putString(KEY_ANYTHINGLLM_API_KEY, settings.anythingllmApiKey)
            putString(KEY_ANYTHINGLLM_WORKSPACE_SLUG, settings.anythingllmWorkspaceSlug)
            putString(KEY_CUSTOM_BASE_URL, settings.customBaseUrl)
            putString(KEY_CUSTOM_MODEL_NAME, settings.customModelName)
            putString(KEY_STT_PROVIDER, settings.sttProvider.name)
            putString(KEY_SPEECH_LANGUAGE, settings.speechLanguage)
            putString(KEY_RESPONSE_LANGUAGE, settings.responseLanguage)
            putString(KEY_SYSTEM_PROMPT, settings.systemPrompt)
            putString(KEY_TTS_PROVIDER, settings.ttsProvider.name)
            putString(KEY_TTS_VOICE_OVERRIDE, settings.ttsVoiceOverride)
            putFloat(KEY_TTS_SPEECH_RATE, settings.ttsSpeechRate)
            putFloat(KEY_TTS_PITCH, settings.ttsPitch)
            putFloat(KEY_SYSTEM_TTS_SPEECH_RATE, settings.systemTtsSpeechRate)
            putFloat(KEY_SYSTEM_TTS_PITCH, settings.systemTtsPitch)
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
    }

    /**
     * Atomically apply [transform] to the current settings and persist the result.
     * Replaces the non-atomic getSettings() -> copy() -> saveSettings() pattern, where
     * two concurrent updates could silently drop one another's changes.
     */
    private inline fun updateSettings(transform: (ApiSettings) -> ApiSettings) {
        val updated = _settingsFlow.updateAndGet(transform)
        persistSettings(updated)
    }

    /**
     * Update single setting
     */
    fun updateGlassesDisplayMetrics(metrics: com.example.rokidcommon.protocol.GlassesDisplayMetrics) {
        updateSettings { it.copy(glassesDisplayMetrics = metrics) }
    }

    fun updateAiProvider(provider: AiProvider) {
        // Restore the model the user last selected for this provider
        // (per-provider model memory), not the first catalog entry.
        updateSettings { it.copy(aiProvider = provider, aiModelId = it.getModelIdForProvider(provider)) }
    }

    fun updateAiModel(modelId: String) {
        updateSettings { it.withModelForProvider(it.aiProvider, modelId) }
    }

    fun updateBaiduQianfanApiKey(apiKey: String) {
        updateSettings { it.copy(baiduQianfanApiKey = apiKey) }
    }

    fun updateBaiduUseLegacyAuth(useLegacy: Boolean) {
        updateSettings { it.copy(baiduUseLegacyAuth = useLegacy) }
    }

    fun updateAlibabaRegion(region: String) {
        updateSettings { it.copy(alibabaRegion = region) }
    }

    fun updateAlibabaCustomBaseUrl(baseUrl: String) {
        updateSettings { it.copy(alibabaCustomBaseUrl = baseUrl) }
    }

    fun updateCustomProtocol(protocol: String) {
        updateSettings { it.copy(customProtocol = protocol) }
    }

    fun updateCustomModelsPath(path: String) {
        updateSettings { it.copy(customModelsPath = path) }
    }

    fun updateCustomCapabilityOverrides(overrides: Set<String>) {
        updateSettings { it.copy(customCapabilityOverrides = overrides) }
    }

    fun updateGeminiApiKey(apiKey: String) {
        updateSettings { it.copy(geminiApiKey = apiKey) }
    }
    
    fun updateOpenaiApiKey(apiKey: String) {
        updateSettings { it.copy(openaiApiKey = apiKey) }
    }
    
    fun updateAnthropicApiKey(apiKey: String) {
        updateSettings { it.copy(anthropicApiKey = apiKey) }
    }
    
    fun updateDeepseekApiKey(apiKey: String) {
        updateSettings { it.copy(deepseekApiKey = apiKey) }
    }
    
    fun updateGroqApiKey(apiKey: String) {
        updateSettings { it.copy(groqApiKey = apiKey) }
    }
    
    fun updateXaiApiKey(apiKey: String) {
        updateSettings { it.copy(xaiApiKey = apiKey) }
    }
    
    fun updateAlibabaApiKey(apiKey: String) {
        updateSettings { it.copy(alibabaApiKey = apiKey) }
    }
    
    fun updateZhipuApiKey(apiKey: String) {
        updateSettings { it.copy(zhipuApiKey = apiKey) }
    }
    
    fun updateBaiduApiKey(apiKey: String) {
        updateSettings { it.copy(baiduApiKey = apiKey) }
    }
    
    fun updateBaiduSecretKey(secretKey: String) {
        updateSettings { it.copy(baiduSecretKey = secretKey) }
    }
    
    fun updatePerplexityApiKey(apiKey: String) {
        updateSettings { it.copy(perplexityApiKey = apiKey) }
    }
    
    fun updateMoonshotApiKey(apiKey: String) {
        updateSettings { it.copy(moonshotApiKey = apiKey) }
    }
    
    fun updateMistralApiKey(apiKey: String) {
        updateSettings { it.copy(mistralApiKey = apiKey) }
    }

    fun updateAnythingLlmServerUrl(url: String) {
        updateSettings { it.copy(anythingllmServerUrl = url) }
    }

    fun updateAnythingLlmApiKey(apiKey: String) {
        updateSettings { it.copy(anythingllmApiKey = apiKey) }
    }

    fun updateAnythingLlmWorkspaceSlug(slug: String) {
        updateSettings { it.copy(anythingllmWorkspaceSlug = slug) }
    }

    fun updateCustomApiKey(apiKey: String) {
        updateSettings { it.copy(customApiKey = apiKey) }
    }
    
    fun updateCustomBaseUrl(baseUrl: String) {
        updateSettings { it.copy(customBaseUrl = baseUrl) }
    }
    
    fun updateCustomModelName(modelName: String) {
        updateSettings { it.copy(customModelName = modelName) }
    }
    
    fun updateSttProvider(provider: SttProvider) {
        updateSettings { it.copy(sttProvider = provider) }
    }
    
    fun updateSystemPrompt(prompt: String) {
        updateSettings { it.copy(systemPrompt = prompt) }
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
        
        // Check against all language defaults (cached)
        return localizedDefaultPrompts.values.any { it == currentPrompt }
    }
    
    /**
     * Reset system prompt to default (localized)
     */
    fun resetSystemPromptToDefault() {
        updateSystemPrompt(getDefaultSystemPrompt())
    }
}

/**
 * Volatile in-memory SharedPreferences used ONLY when Android Keystore-backed
 * encrypted storage fails to initialize. Nothing is written to disk, so API
 * keys are never persisted in plaintext. Settings last for the session.
 */
private class InMemorySharedPreferences : SharedPreferences {

    // All map access is guarded by this single prefs-level lock: a plain mutableMapOf
    // shared by every Editor and getter can corrupt or throw ConcurrentModificationException.
    private val lock = Any()
    private val data = mutableMapOf<String, Any?>()
    private val listeners = java.util.concurrent.CopyOnWriteArrayList<SharedPreferences.OnSharedPreferenceChangeListener>()

    override fun getAll(): Map<String, *> = synchronized(lock) { data.toMap() }

    @Suppress("UNCHECKED_CAST")
    override fun getString(key: String, defValue: String?): String? = synchronized(lock) { data[key] as? String ?: defValue }

    @Suppress("UNCHECKED_CAST")
    override fun getStringSet(key: String, defValues: Set<String>?): Set<String>? =
        synchronized(lock) { (data[key] as? Set<String>) ?: defValues }

    override fun getInt(key: String, defValue: Int): Int = synchronized(lock) { data[key] as? Int ?: defValue }

    override fun getLong(key: String, defValue: Long): Long = synchronized(lock) { data[key] as? Long ?: defValue }

    override fun getFloat(key: String, defValue: Float): Float = synchronized(lock) { data[key] as? Float ?: defValue }

    override fun getBoolean(key: String, defValue: Boolean): Boolean = synchronized(lock) { data[key] as? Boolean ?: defValue }

    override fun contains(key: String): Boolean = synchronized(lock) { data.containsKey(key) }

    override fun edit(): SharedPreferences.Editor = Editor()

    override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        listeners.add(listener)
    }

    override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        listeners.remove(listener)
    }

    private inner class Editor : SharedPreferences.Editor {
        private val pending = mutableMapOf<String, Any?>()
        private val removals = mutableSetOf<String>()
        private var clearAll = false

        override fun putString(key: String, value: String?): SharedPreferences.Editor = apply { pending[key] = value }
        override fun putStringSet(key: String, values: Set<String>?): SharedPreferences.Editor = apply { pending[key] = values }
        override fun putInt(key: String, value: Int): SharedPreferences.Editor = apply { pending[key] = value }
        override fun putLong(key: String, value: Long): SharedPreferences.Editor = apply { pending[key] = value }
        override fun putFloat(key: String, value: Float): SharedPreferences.Editor = apply { pending[key] = value }
        override fun putBoolean(key: String, value: Boolean): SharedPreferences.Editor = apply { pending[key] = value }
        override fun remove(key: String): SharedPreferences.Editor = apply { removals.add(key) }
        override fun clear(): SharedPreferences.Editor = apply { clearAll = true }

        override fun commit(): Boolean {
            applyChanges()
            return true
        }

        override fun apply() {
            applyChanges()
        }

        private fun applyChanges() {
            // Lock the shared prefs object (not this Editor) for mutual exclusion
            // between different Editor instances.
            val changedKeys = synchronized(lock) {
                if (clearAll) data.clear()
                removals.forEach { data.remove(it) }
                pending.forEach { (k, v) -> data[k] = v }
                val keys = pending.keys + removals
                // Reset pending state: a second apply()/commit() must not re-apply stale changes.
                pending.clear()
                removals.clear()
                clearAll = false
                keys
            }
            // Notify listeners outside the lock to avoid re-entrancy/deadlock.
            listeners.forEach { listener ->
                changedKeys.forEach { key -> listener.onSharedPreferenceChanged(this@InMemorySharedPreferences, key) }
            }
        }
    }
}
