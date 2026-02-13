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

        assertThat(baiduInvalid.isValid()).isFalse()
        assertThat(baiduValid.isValid()).isTrue()
        assertThat(customInvalid.isValid()).isFalse()
        assertThat(customValid.isValid()).isTrue()
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
        val validOpenai = ApiSettings(aiProvider = AiProvider.OPENAI, openaiApiKey = "ok")

        assertThat(customInvalid.validateForChat()).isInstanceOf(SettingsValidationResult.InvalidConfiguration::class.java)
        assertThat(baiduMissing.validateForChat()).isInstanceOf(SettingsValidationResult.MissingApiKey::class.java)
        assertThat(validOpenai.validateForChat()).isEqualTo(SettingsValidationResult.Valid)
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
}
