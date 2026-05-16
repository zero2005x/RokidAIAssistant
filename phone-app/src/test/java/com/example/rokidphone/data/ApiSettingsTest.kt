package com.example.rokidphone.data

import com.example.rokidphone.service.stt.SttProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ApiSettingsTest {

    @Test
    fun `getCurrentApiKey returns key of selected provider`() {
        // 測試：應回傳目前選擇 provider 的 API key
        val settings = ApiSettings(
            aiProvider = AiProvider.OPENAI,
            openaiApiKey = "openai-key",
            geminiApiKey = "gemini-key"
        )

        assertThat(settings.getCurrentApiKey()).isEqualTo("openai-key")
    }

    @Test
    fun `getCurrentApiKey for GEMINI_LIVE uses geminiApiKey`() {
        // 測試：GEMINI_LIVE 與 GEMINI 共用金鑰
        val settings = ApiSettings(
            aiProvider = AiProvider.GEMINI_LIVE,
            geminiApiKey = "gemini-live-key"
        )

        assertThat(settings.getCurrentApiKey()).isEqualTo("gemini-live-key")
    }

    @Test
    fun `AnythingLLM uses its API key and server URL fields`() {
        val settings = ApiSettings(
            aiProvider = AiProvider.ANYTHINGLLM,
            anythingllmServerUrl = "https://anything.example",
            anythingllmApiKey = "anything-key",
            anythingllmWorkspaceSlug = "docs"
        )

        assertThat(settings.getCurrentApiKey()).isEqualTo("anything-key")
        assertThat(settings.getApiKeyForProvider(AiProvider.ANYTHINGLLM)).isEqualTo("anything-key")
        assertThat(settings.getCurrentBaseUrl()).isEqualTo("https://anything.example")
        assertThat(settings.isValid()).isTrue()
        assertThat(settings.isProviderConfigured(AiProvider.ANYTHINGLLM)).isTrue()
    }

    @Test
    fun `AnythingLLM falls back to provider default base URL when server URL is blank`() {
        val settings = ApiSettings(aiProvider = AiProvider.ANYTHINGLLM)

        assertThat(settings.getCurrentBaseUrl()).isEqualTo(AiProvider.ANYTHINGLLM.defaultBaseUrl)
    }

    @Test
    fun `custom provider base url and model use custom fields when set`() {
        // 測試：CUSTOM provider 應使用自訂 baseUrl 與 modelName
        val settings = ApiSettings(
            aiProvider = AiProvider.CUSTOM,
            aiModelId = "fallback-model",
            customBaseUrl = "http://127.0.0.1:11434/v1/",
            customModelName = "llama-custom"
        )

        assertThat(settings.getCurrentBaseUrl()).isEqualTo("http://127.0.0.1:11434/v1/")
        assertThat(settings.getCurrentModelId()).isEqualTo("llama-custom")
    }

    @Test
    fun `custom provider falls back to defaults when blank`() {
        // 測試：CUSTOM 欄位為空時應回退預設值
        val settings = ApiSettings(
            aiProvider = AiProvider.CUSTOM,
            aiModelId = "fallback-model",
            customBaseUrl = "",
            customModelName = ""
        )

        assertThat(settings.getCurrentBaseUrl()).isEqualTo(AiProvider.CUSTOM.defaultBaseUrl)
        assertThat(settings.getCurrentModelId()).isEqualTo("fallback-model")
    }

    @Test
    fun `isValid handles Baidu and custom URL validation`() {
        // 測試：Baidu 需 key+secret；Custom 需合法 URL
        val baiduInvalid = ApiSettings(aiProvider = AiProvider.BAIDU, baiduApiKey = "k", baiduSecretKey = "")
        val baiduValid = ApiSettings(aiProvider = AiProvider.BAIDU, baiduApiKey = "k", baiduSecretKey = "s")
        val customInvalid = ApiSettings(aiProvider = AiProvider.CUSTOM, customBaseUrl = "ws://invalid")
        val customValid = ApiSettings(aiProvider = AiProvider.CUSTOM, customBaseUrl = "https://example.com/v1")
        val anythingLlmInvalid = ApiSettings(
            aiProvider = AiProvider.ANYTHINGLLM,
            anythingllmServerUrl = "https://anything.example",
            anythingllmApiKey = "key"
        )

        assertThat(baiduInvalid.isValid()).isFalse()
        assertThat(baiduValid.isValid()).isTrue()
        assertThat(customInvalid.isValid()).isFalse()
        assertThat(customValid.isValid()).isTrue()
        assertThat(anythingLlmInvalid.isValid()).isFalse()
    }

    @Test
    fun `speech configuration and configured stt providers reflect available keys`() {
        // 測試：語音服務可用性與 provider 清單應與設定一致
        val none = ApiSettings()
        val configured = ApiSettings(geminiApiKey = "g", openaiApiKey = "o")

        assertThat(none.hasSpeechServiceConfigured()).isFalse()
        assertThat(configured.hasSpeechServiceConfigured()).isTrue()
        assertThat(configured.getConfiguredSttProviders())
            .containsExactly(AiProvider.GEMINI, AiProvider.OPENAI)
    }

    @Test
    fun `validateForChat returns expected result types`() {
        // 測試：聊天設定驗證應回傳正確結果型別
        val customInvalid = ApiSettings(aiProvider = AiProvider.CUSTOM, customBaseUrl = "invalid-url")
        val baiduMissing = ApiSettings(aiProvider = AiProvider.BAIDU, baiduApiKey = "", baiduSecretKey = "")
        val anythingLlmInvalid = ApiSettings(aiProvider = AiProvider.ANYTHINGLLM, anythingllmApiKey = "key")
        val validOpenai = ApiSettings(aiProvider = AiProvider.OPENAI, openaiApiKey = "ok")
        val validAnythingLlm = ApiSettings(
            aiProvider = AiProvider.ANYTHINGLLM,
            anythingllmServerUrl = "https://anything.example",
            anythingllmApiKey = "key",
            anythingllmWorkspaceSlug = "docs"
        )

        assertThat(customInvalid.validateForChat()).isInstanceOf(SettingsValidationResult.InvalidConfiguration::class.java)
        assertThat(baiduMissing.validateForChat()).isInstanceOf(SettingsValidationResult.MissingApiKey::class.java)
        assertThat(anythingLlmInvalid.validateForChat())
            .isInstanceOf(SettingsValidationResult.InvalidConfiguration::class.java)
        assertThat(validOpenai.validateForChat()).isEqualTo(SettingsValidationResult.Valid)
        assertThat(validAnythingLlm.validateForChat()).isEqualTo(SettingsValidationResult.Valid)
    }

    @Test
    fun `validateForSpeech returns missing service when no stt capable providers configured`() {
        // 測試：沒有可用 STT provider 時應回傳 MissingSpeechService
        val missing = ApiSettings()
        val valid = ApiSettings(groqApiKey = "groq")

        assertThat(missing.validateForSpeech()).isInstanceOf(SettingsValidationResult.MissingSpeechService::class.java)
        assertThat(valid.validateForSpeech()).isEqualTo(SettingsValidationResult.Valid)
    }

    @Test
    fun `toSttCredentials maps selected provider and credentials`() {
        // 測試：ApiSettings 轉換後應保留 STT provider 與憑證欄位
        val settings = ApiSettings(
            sttProvider = SttProvider.DEEPGRAM,
            deepgramApiKey = "deepgram-key",
            assemblyaiApiKey = "assembly-key",
            azureSpeechKey = "azure-key"
        )

        val credentials = settings.toSttCredentials()

        assertThat(credentials.selectedProvider).isEqualTo(SttProvider.DEEPGRAM.name)
        assertThat(credentials.deepgramApiKey).isEqualTo("deepgram-key")
        assertThat(credentials.assemblyaiApiKey).isEqualTo("assembly-key")
        assertThat(credentials.azureSpeechKey).isEqualTo("azure-key")
    }

    // ==================== TTS settings ====================

    @Test
    fun `default TTS settings use EDGE_TTS with auto-detect`() {
        // 測試：預設 TTS 設定應使用 EDGE_TTS 引擎並自動偵測語音
        val settings = ApiSettings()

        assertThat(settings.ttsProvider).isEqualTo(TtsProvider.EDGE_TTS)
        assertThat(settings.ttsVoiceOverride).isEmpty()
        assertThat(settings.ttsSpeechRate).isEqualTo(1.0f)
        assertThat(settings.ttsPitch).isEqualTo(0.0f)
        assertThat(settings.systemTtsSpeechRate).isEqualTo(1.0f)
        assertThat(settings.systemTtsPitch).isEqualTo(1.0f)
    }

    @Test
    fun `copy with TTS provider preserves other TTS fields`() {
        // 測試：複製時更改 provider 應保留其他 TTS 欄位
        val original = ApiSettings(
            ttsProvider = TtsProvider.EDGE_TTS,
            ttsVoiceOverride = "ko-KR-SunHiNeural",
            ttsSpeechRate = 1.5f,
            ttsPitch = -0.2f,
            systemTtsSpeechRate = 0.8f,
            systemTtsPitch = 1.2f
        )
        val copied = original.copy(ttsProvider = TtsProvider.SYSTEM_TTS)

        assertThat(copied.ttsProvider).isEqualTo(TtsProvider.SYSTEM_TTS)
        assertThat(copied.ttsVoiceOverride).isEqualTo("ko-KR-SunHiNeural")
        assertThat(copied.ttsSpeechRate).isEqualTo(1.5f)
        assertThat(copied.ttsPitch).isEqualTo(-0.2f)
        assertThat(copied.systemTtsSpeechRate).isEqualTo(0.8f)
        assertThat(copied.systemTtsPitch).isEqualTo(1.2f)
    }

    @Test
    fun `TTS settings equality check`() {
        // 測試：相同 TTS 設定的 ApiSettings 應相等
        val a = ApiSettings(
            ttsProvider = TtsProvider.GOOGLE_TRANSLATE_TTS,
            ttsVoiceOverride = "en-US-JennyNeural",
            ttsSpeechRate = 1.2f
        )
        val b = ApiSettings(
            ttsProvider = TtsProvider.GOOGLE_TRANSLATE_TTS,
            ttsVoiceOverride = "en-US-JennyNeural",
            ttsSpeechRate = 1.2f
        )
        assertThat(a).isEqualTo(b)
    }

    @Test
    fun `TTS settings inequality when provider differs`() {
        // 測試：不同 TTS provider 的 ApiSettings 應不相等
        val a = ApiSettings(ttsProvider = TtsProvider.EDGE_TTS)
        val b = ApiSettings(ttsProvider = TtsProvider.SYSTEM_TTS)
        assertThat(a).isNotEqualTo(b)
    }
}
