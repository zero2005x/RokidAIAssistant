package com.example.rokidphone.data

import com.example.rokidphone.service.stt.SttProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Covers the credential and validation queries on [ApiSettings], the legacy
 * [AvailableModels] facade and the Alibaba region endpoints. The sibling
 * ApiSettingsTest covers the per-provider key and model accessors.
 */
@RunWith(RobolectricTestRunner::class)
class ApiSettingsValidationTest {

    @Test
    fun `provider names round-trip and unknown names fall back to Gemini`() {
        assertThat(AiProvider.fromNameOrNull("OPENAI")).isEqualTo(AiProvider.OPENAI)
        assertThat(AiProvider.fromNameOrNull("nope")).isNull()
        assertThat(AiProvider.fromName("ANTHROPIC")).isEqualTo(AiProvider.ANTHROPIC)
        assertThat(AiProvider.fromName("nope")).isEqualTo(AiProvider.GEMINI)
    }

    @Test
    fun `provider capability flags describe how each one is configured`() {
        assertThat(AiProvider.CUSTOM.allowsCustomBaseUrl()).isTrue()
        assertThat(AiProvider.GEMINI.allowsCustomBaseUrl()).isFalse()
        // A user-hosted endpoint may be open, and on-device inference has no cloud key.
        assertThat(AiProvider.CUSTOM.requiresApiKey()).isFalse()
        assertThat(AiProvider.LOCAL_GEMMA.requiresApiKey()).isFalse()
        assertThat(AiProvider.OPENAI.requiresApiKey()).isTrue()
        assertThat(AiProvider.LOCAL_GEMMA.isLocalInference()).isTrue()
        assertThat(AiProvider.GEMINI.isLocalInference()).isFalse()
        assertThat(AiProvider.BAIDU.requiresSecretKey()).isTrue()
        assertThat(AiProvider.GEMINI.requiresSecretKey()).isFalse()
    }

    @Test
    fun `the legacy model facade exposes the fallback catalog`() {
        assertThat(AvailableModels.allModels).isNotEmpty()
        assertThat(AvailableModels.geminiModels).isNotEmpty()
        assertThat(AvailableModels.geminiModels).isEqualTo(
            AvailableModels.getModelsForProvider(AiProvider.GEMINI)
        )
        for (list in listOf(
            AvailableModels.openaiModels, AvailableModels.anthropicModels,
            AvailableModels.deepseekModels, AvailableModels.groqModels,
            AvailableModels.anythingllmModels, AvailableModels.customModels,
            AvailableModels.xaiModels, AvailableModels.alibabaModels,
            AvailableModels.zhipuModels, AvailableModels.baiduModels,
            AvailableModels.perplexityModels, AvailableModels.moonshotModels,
            AvailableModels.geminiLiveModels, AvailableModels.mistralModels
        )) {
            assertThat(list).isNotEmpty()
        }

        val first = AvailableModels.allModels.first()
        assertThat(AvailableModels.findModel(first.id)).isEqualTo(first)
        assertThat(AvailableModels.findModel("no-such-model")).isNull()
        assertThat(AvailableModels.allModels.map { it.provider }.toSet())
            .containsAnyIn(AiProvider.entries)
    }

    @Test
    fun `model options and provider configs keep their defaults`() {
        val option = ModelOption(id = "m", displayName = "M", provider = AiProvider.GEMINI)
        assertThat(option.supportsAudio).isFalse()
        assertThat(option.supportsVision).isFalse()
        assertThat(option.isPreview).isFalse()
        assertThat(option.description).isEmpty()
        assertThat(option.copy(supportsVision = true).supportsVision).isTrue()

        val config = ProviderConfig()
        assertThat(config.apiKey).isEmpty()
        assertThat(config.baseUrl).isEmpty()
        assertThat(config.customModelName).isEmpty()
    }

    @Test
    fun `every Alibaba region resolves to an endpoint and unknown regions use China`() {
        val china = AlibabaRegions.baseUrlFor(AlibabaRegions.CHINA)
        assertThat(AlibabaRegions.all).hasSize(6)
        for (region in AlibabaRegions.all - AlibabaRegions.CUSTOM) {
            assertThat(AlibabaRegions.baseUrlFor(region)).startsWith("https://dashscope")
        }
        assertThat(AlibabaRegions.baseUrlFor(AlibabaRegions.SINGAPORE)).contains("-intl.")
        assertThat(AlibabaRegions.baseUrlFor(AlibabaRegions.US)).contains("-us.")
        assertThat(AlibabaRegions.baseUrlFor(AlibabaRegions.GERMANY)).contains("-de.")
        assertThat(AlibabaRegions.baseUrlFor(AlibabaRegions.JAPAN)).contains("-jp.")
        assertThat(AlibabaRegions.baseUrlFor(AlibabaRegions.CUSTOM, "https://my.host/v1/"))
            .isEqualTo("https://my.host/v1/")
        // A custom region with no URL, and an unrecognised region, both fall back.
        assertThat(AlibabaRegions.baseUrlFor(AlibabaRegions.CUSTOM, "  ")).isEqualTo(china)
        assertThat(AlibabaRegions.baseUrlFor("mars")).isEqualTo(china)
    }

    @Test
    fun `the active base url follows the provider and its region`() {
        assertThat(ApiSettings(aiProvider = AiProvider.ALIBABA, alibabaRegion = AlibabaRegions.JAPAN)
            .getCurrentBaseUrl()).contains("-jp.")
        assertThat(ApiSettings(aiProvider = AiProvider.CUSTOM, customBaseUrl = "https://mine/v1/")
            .getCurrentBaseUrl()).isEqualTo("https://mine/v1/")
        assertThat(ApiSettings(aiProvider = AiProvider.CUSTOM, customBaseUrl = "")
            .getCurrentBaseUrl()).isEqualTo(AiProvider.CUSTOM.defaultBaseUrl)
        assertThat(ApiSettings(aiProvider = AiProvider.ANYTHINGLLM, anythingllmServerUrl = "")
            .getCurrentBaseUrl()).isEqualTo(AiProvider.ANYTHINGLLM.defaultBaseUrl)
        assertThat(ApiSettings(aiProvider = AiProvider.GEMINI).getCurrentBaseUrl())
            .isEqualTo(AiProvider.GEMINI.defaultBaseUrl)
    }

    @Test
    fun `providers with special credential shapes are recognised as configured`() {
        // CUSTOM: the default URL alone is not configuration.
        assertThat(ApiSettings().isProviderConfigured(AiProvider.CUSTOM)).isFalse()
        assertThat(ApiSettings(customApiKey = "k").isProviderConfigured(AiProvider.CUSTOM)).isTrue()
        assertThat(ApiSettings(customBaseUrl = "https://self.hosted/v1/")
            .isProviderConfigured(AiProvider.CUSTOM)).isTrue()
        assertThat(ApiSettings(customBaseUrl = "not a url")
            .isProviderConfigured(AiProvider.CUSTOM)).isFalse()

        // Baidu accepts either the Qianfan key or the legacy key/secret pair.
        assertThat(ApiSettings(baiduQianfanApiKey = "q").isProviderConfigured(AiProvider.BAIDU)).isTrue()
        assertThat(ApiSettings(baiduApiKey = "k").isProviderConfigured(AiProvider.BAIDU)).isFalse()
        assertThat(ApiSettings(baiduApiKey = "k", baiduSecretKey = "s")
            .isProviderConfigured(AiProvider.BAIDU)).isTrue()

        // Gemini Live shares the Gemini key; AnythingLLM needs all three fields.
        assertThat(ApiSettings(geminiApiKey = "g").isProviderConfigured(AiProvider.GEMINI_LIVE)).isTrue()
        assertThat(ApiSettings(anythingllmServerUrl = "u", anythingllmApiKey = "k")
            .isProviderConfigured(AiProvider.ANYTHINGLLM)).isFalse()
        assertThat(ApiSettings(anythingllmServerUrl = "u", anythingllmApiKey = "k",
            anythingllmWorkspaceSlug = "w").isProviderConfigured(AiProvider.ANYTHINGLLM)).isTrue()

        // On-device inference is always available.
        assertThat(ApiSettings().isProviderConfigured(AiProvider.LOCAL_GEMMA)).isTrue()
        assertThat(ApiSettings(openaiApiKey = "o").isProviderConfigured(AiProvider.OPENAI)).isTrue()
        assertThat(ApiSettings().isProviderConfigured(AiProvider.OPENAI)).isFalse()
    }

    @Test
    fun `baidu credential selection and legacy mode follow qianfan and legacy settings`() {
        val qianfan = ApiSettings(
            aiProvider = AiProvider.BAIDU,
            baiduApiKey = "legacy-key",
            baiduSecretKey = "legacy-secret",
            baiduQianfanApiKey = "qianfan-key"
        )
        val legacyAuto = ApiSettings(
            aiProvider = AiProvider.BAIDU,
            baiduApiKey = "legacy-key",
            baiduSecretKey = "legacy-secret"
        )
        val legacyForced = qianfan.copy(baiduUseLegacyAuth = true)

        assertThat(qianfan.isBaiduLegacyMode()).isFalse()
        assertThat(qianfan.getCurrentApiKey()).isEqualTo("qianfan-key")
        assertThat(legacyAuto.isBaiduLegacyMode()).isTrue()
        assertThat(legacyAuto.getCurrentApiKey()).isEqualTo("legacy-key")
        assertThat(legacyForced.isBaiduLegacyMode()).isTrue()
        assertThat(legacyForced.getCurrentApiKey()).isEqualTo("legacy-key")
    }

    @Test
    fun `configured provider lists and missing keys reflect the credentials present`() {
        val empty = ApiSettings(aiProvider = AiProvider.OPENAI)
        assertThat(empty.hasAnyApiKeyConfigured()).isFalse()
        assertThat(empty.getMissingApiKeys()).containsExactly(AiProvider.OPENAI)
        assertThat(empty.getConfiguredSttProviders()).isEmpty()
        // LOCAL_GEMMA needs no credential, so it is always in the configured list.
        assertThat(empty.getConfiguredProviders()).containsExactly(AiProvider.LOCAL_GEMMA)

        val configured = ApiSettings(
            aiProvider = AiProvider.OPENAI, openaiApiKey = "o", geminiApiKey = "g", groqApiKey = "q"
        )
        assertThat(configured.hasAnyApiKeyConfigured()).isTrue()
        assertThat(configured.getMissingApiKeys()).isEmpty()
        assertThat(configured.getConfiguredSttProviders())
            .containsExactly(AiProvider.GEMINI, AiProvider.OPENAI, AiProvider.GROQ)
        assertThat(configured.getConfiguredProviders()).contains(AiProvider.GEMINI_LIVE)
    }

    @Test
    fun `each credential shape on its own counts as having a key`() {
        val fields = listOf<(ApiSettings) -> ApiSettings>(
            { it.copy(geminiApiKey = "v") }, { it.copy(openaiApiKey = "v") },
            { it.copy(anthropicApiKey = "v") }, { it.copy(deepseekApiKey = "v") },
            { it.copy(groqApiKey = "v") }, { it.copy(xaiApiKey = "v") },
            { it.copy(alibabaApiKey = "v") }, { it.copy(zhipuApiKey = "v") },
            { it.copy(baiduQianfanApiKey = "v") }, { it.copy(perplexityApiKey = "v") },
            { it.copy(moonshotApiKey = "v") }, { it.copy(mistralApiKey = "v") },
            { it.copy(anythingllmApiKey = "v", anythingllmServerUrl = "u") },
            { it.copy(customApiKey = "v") },
            { it.copy(customBaseUrl = "https://elsewhere/v1/") }
        )
        for (withField in fields) {
            assertThat(withField(ApiSettings()).hasAnyApiKeyConfigured()).isTrue()
        }
        // A partial Baidu pair is not a usable credential.
        assertThat(ApiSettings(baiduApiKey = "v").hasAnyApiKeyConfigured()).isFalse()
    }

    @Test
    fun `provider model memory keeps selections isolated and migrates legacy ids`() {
        val remembered = ApiSettings(
            aiProvider = AiProvider.OPENAI,
            aiModelId = "gpt-5.6"
        )
            .withModelForProvider(AiProvider.OPENAI, "gpt-5.6")
            .withModelForProvider(AiProvider.CUSTOM, "llama-self-hosted")
            .withModelForProvider(AiProvider.DEEPSEEK, "deepseek-chat")

        assertThat(remembered.getModelIdForProvider(AiProvider.OPENAI)).isEqualTo("gpt-5.6")
        assertThat(remembered.copy(aiProvider = AiProvider.CUSTOM, customModelName = "").getCurrentModelId())
            .isEqualTo("llama-self-hosted")
        assertThat(remembered.getModelIdForProvider(AiProvider.ANTHROPIC))
            .isEqualTo(com.example.rokidphone.ai.catalog.FallbackModelCatalog.defaultModelFor(AiProvider.ANTHROPIC))

        val migrated = remembered.copy(aiProvider = AiProvider.DEEPSEEK, aiModelId = "deepseek-reasoner")
            .migrateLegacyModelIds()

        assertThat(migrated.aiModelId).isEqualTo("deepseek-v4-pro")
        assertThat(migrated.providerModelIds[AiProvider.DEEPSEEK.name]).isEqualTo("deepseek-v4-flash")
        assertThat(migrated.providerModelIds[AiProvider.OPENAI.name]).isEqualTo("gpt-5.6")
    }

    @Test
    fun `chat validation reports the first blocking misconfiguration`() {
        assertThat(ApiSettings(aiProvider = AiProvider.CUSTOM, customBaseUrl = "not a url").validateForChat())
            .isInstanceOf(SettingsValidationResult.InvalidConfiguration::class.java)
        assertThat(ApiSettings(aiProvider = AiProvider.CUSTOM, customBaseUrl = "https://mine/v1/")
            .validateForChat()).isEqualTo(SettingsValidationResult.Valid)

        assertThat(ApiSettings(aiProvider = AiProvider.BAIDU).validateForChat())
            .isEqualTo(SettingsValidationResult.MissingApiKey(AiProvider.BAIDU))

        val anythingLlm = ApiSettings(aiProvider = AiProvider.ANYTHINGLLM).validateForChat()
        assertThat(anythingLlm).isInstanceOf(SettingsValidationResult.InvalidConfiguration::class.java)
        assertThat((anythingLlm as SettingsValidationResult.InvalidConfiguration).message)
            .contains("workspace slug")

        // On-device inference needs no key; a missing model file is reported later.
        assertThat(ApiSettings(aiProvider = AiProvider.LOCAL_GEMMA).validateForChat())
            .isEqualTo(SettingsValidationResult.Valid)
        assertThat(ApiSettings(aiProvider = AiProvider.OPENAI).validateForChat())
            .isEqualTo(SettingsValidationResult.MissingApiKey(AiProvider.OPENAI))
        assertThat(ApiSettings(aiProvider = AiProvider.OPENAI, openaiApiKey = "o").validateForChat())
            .isEqualTo(SettingsValidationResult.Valid)
    }

    @Test
    fun `speech validation names the provider whose credentials are missing`() {
        assertThat(ApiSettings(sttProvider = SttProvider.GEMINI, geminiApiKey = "g").validateForSpeech())
            .isEqualTo(SettingsValidationResult.Valid)
        assertThat(ApiSettings(sttProvider = SttProvider.OPENAI_WHISPER, openaiApiKey = "o")
            .validateForSpeech()).isEqualTo(SettingsValidationResult.Valid)
        assertThat(ApiSettings(sttProvider = SttProvider.GROQ_WHISPER, groqApiKey = "q")
            .validateForSpeech()).isEqualTo(SettingsValidationResult.Valid)

        assertThat(ApiSettings(sttProvider = SttProvider.GEMINI).validateForSpeech())
            .isEqualTo(SettingsValidationResult.MissingSpeechService(listOf(AiProvider.GEMINI)))
        assertThat(ApiSettings(sttProvider = SttProvider.OPENAI_WHISPER).validateForSpeech())
            .isEqualTo(SettingsValidationResult.MissingSpeechService(listOf(AiProvider.OPENAI)))
        assertThat(ApiSettings(sttProvider = SttProvider.GROQ_WHISPER).validateForSpeech())
            .isEqualTo(SettingsValidationResult.MissingSpeechService(listOf(AiProvider.GROQ)))

        // A third-party STT provider has no built-in AiProvider to point at.
        val external = ApiSettings(sttProvider = SttProvider.DEEPGRAM).validateForSpeech()
        assertThat(external).isInstanceOf(SettingsValidationResult.InvalidConfiguration::class.java)
        assertThat((external as SettingsValidationResult.InvalidConfiguration).message)
            .contains(SttProvider.DEEPGRAM.name)
    }

    @Test
    fun `speech availability tracks the selected stt provider's credentials`() {
        assertThat(ApiSettings(sttProvider = SttProvider.GEMINI).hasSpeechServiceConfigured()).isFalse()
        assertThat(ApiSettings(sttProvider = SttProvider.GEMINI, geminiApiKey = "g")
            .hasSpeechServiceConfigured()).isTrue()
    }
}
