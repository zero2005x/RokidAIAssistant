package com.example.rokidphone.ai.provider

import android.content.Context
import com.example.rokidphone.data.AiProvider
import com.example.rokidphone.data.ApiSettings
import com.example.rokidphone.data.SettingsRepository
import com.example.rokidphone.service.ai.AiServiceFactory
import com.example.rokidphone.service.ai.AiServiceProvider
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ProviderManagerTest {

    private val context = mockk<Context>(relaxed = true)
    private val settingsRepository = mockk<SettingsRepository>(relaxed = true)
    private var settings = ApiSettings()

    @Before
    fun setUp() {
        mockkObject(SettingsRepository.Companion, AiServiceFactory)
        every { SettingsRepository.getInstance(any()) } returns settingsRepository
        every { settingsRepository.getSettings() } answers { settings }
        every { AiServiceFactory.createService(any()) } answers { mockk<AiServiceProvider>() }
    }

    @After
    fun tearDown() = unmockkAll()

    private fun manager(): ProviderManager = ProviderManager::class.java
        .getDeclaredConstructor(Context::class.java)
        .apply { isAccessible = true }
        .newInstance(context)

    @Test
    fun `the active provider setting mirrors the configured provider and model`() {
        settings = ApiSettings(
            aiProvider = AiProvider.ANTHROPIC, aiModelId = "claude-x", anthropicApiKey = "key-a"
        )
        val active = manager().activeProviderSetting.value

        assertThat(active).isInstanceOf(ProviderSetting.Anthropic::class.java)
        assertThat((active as ProviderSetting.Anthropic).apiKey).isEqualTo("key-a")
        assertThat(active.modelId).isEqualTo("claude-x")
    }

    @Test
    fun `every provider maps to a provider setting`() {
        val manager = manager()
        for (provider in AiProvider.entries) {
            settings = ApiSettings(aiProvider = provider, aiModelId = "model-for-$provider")
            manager.refreshFromSettings()
            assertThat(manager.activeProviderSetting.value).isNotNull()
        }
    }

    @Test
    fun `providers sharing or lacking credentials reuse the right setting shape`() {
        settings = ApiSettings(aiProvider = AiProvider.GEMINI_LIVE, geminiApiKey = "shared-gemini")
        val live = manager().activeProviderSetting.value
        assertThat(live).isInstanceOf(ProviderSetting.Gemini::class.java)
        assertThat((live as ProviderSetting.Gemini).apiKey).isEqualTo("shared-gemini")

        settings = ApiSettings(aiProvider = AiProvider.LOCAL_GEMMA)
        val local = manager().activeProviderSetting.value
        assertThat(local).isInstanceOf(ProviderSetting.Custom::class.java)
        // On-device inference carries no credential.
        assertThat((local as ProviderSetting.Custom).apiKey).isEmpty()
        assertThat(local.baseUrl).isEqualTo(AiProvider.LOCAL_GEMMA.defaultBaseUrl)
    }

    @Test
    fun `only providers with complete credentials are listed as configured`() {
        settings = ApiSettings(
            geminiApiKey = "g", openaiApiKey = " ", anthropicApiKey = "a",
            baiduApiKey = "b", baiduSecretKey = "", // incomplete pair
            customBaseUrl = "https://custom.example", customApiKey = "c",
            anythingllmServerUrl = "https://llm.example", anythingllmWorkspaceSlug = ""
        )
        val configured = manager().configuredProviders.value

        assertThat(configured.map { it::class.java }).containsExactly(
            ProviderSetting.Gemini::class.java,
            ProviderSetting.Anthropic::class.java,
            ProviderSetting.Custom::class.java
        )

        settings = settings.copy(baiduSecretKey = "s", anythingllmWorkspaceSlug = "w")
        val manager = manager()
        assertThat(manager.configuredProviders.value.map { it::class.java }).containsAtLeast(
            ProviderSetting.Baidu::class.java,
            ProviderSetting.AnythingLLM::class.java
        )
        assertThat(manager().configuredProviders.value).hasSize(5)
    }

    @Test
    fun `the active service is cached until the settings change`() = runTest {
        settings = ApiSettings(aiProvider = AiProvider.GEMINI, geminiApiKey = "g")
        val manager = manager()

        val first = manager.getActiveService()
        assertThat(manager.getActiveService()).isSameInstanceAs(first)
        verify(exactly = 1) { AiServiceFactory.createService(any()) }

        settings = settings.copy(aiModelId = "another-model")
        assertThat(manager.getActiveService()).isNotSameInstanceAs(first)
        verify(exactly = 2) { AiServiceFactory.createService(any()) }
    }

    @Test
    fun `invalidateCache forces the next call to rebuild the service`() = runTest {
        val manager = manager()
        val first = manager.getActiveService()
        manager.invalidateCache()
        assertThat(manager.getActiveService()).isNotSameInstanceAs(first)
    }

    @Test
    fun `a failing factory yields no service instead of propagating`() = runTest {
        every { AiServiceFactory.createService(any()) } throws IllegalStateException("bad key")
        val manager = manager()
        assertThat(manager.getActiveService()).isNull()
        assertThat(manager.getServiceForProvider("openai")).isNull()
    }

    @Test
    fun `services can be requested by provider id, including dashed aliases`() = runTest {
        val manager = manager()
        assertThat(manager.getServiceForProvider("OPENAI")).isNotNull()
        assertThat(manager.getServiceForProvider("gemini-live")).isNotNull()
        assertThat(manager.getServiceForProvider("not-a-provider")).isNull()
    }

    @Test
    fun `switching provider or model clears the cached service`() = runTest {
        val manager = manager()
        val first = manager.getActiveService()

        manager.switchProvider(AiProvider.OPENAI)
        verify { settingsRepository.updateAiProvider(AiProvider.OPENAI) }
        assertThat(manager.getActiveService()).isNotSameInstanceAs(first)

        val second = manager.getActiveService()
        manager.switchModel("gpt-5")
        verify { settingsRepository.updateAiModel("gpt-5") }
        assertThat(manager.getActiveService()).isNotSameInstanceAs(second)
    }

    @Test
    fun `each provider's api key is written to its own setting`() {
        val manager = manager()
        for (provider in AiProvider.entries) {
            manager.updateApiKey(provider, "key-for-$provider")
        }

        verify { settingsRepository.updateOpenaiApiKey("key-for-${AiProvider.OPENAI}") }
        verify { settingsRepository.updateAnthropicApiKey("key-for-${AiProvider.ANTHROPIC}") }
        verify { settingsRepository.updateDeepseekApiKey("key-for-${AiProvider.DEEPSEEK}") }
        verify { settingsRepository.updateGroqApiKey("key-for-${AiProvider.GROQ}") }
        verify { settingsRepository.updateXaiApiKey("key-for-${AiProvider.XAI}") }
        verify { settingsRepository.updateAlibabaApiKey("key-for-${AiProvider.ALIBABA}") }
        verify { settingsRepository.updateZhipuApiKey("key-for-${AiProvider.ZHIPU}") }
        verify { settingsRepository.updateBaiduApiKey("key-for-${AiProvider.BAIDU}") }
        verify { settingsRepository.updatePerplexityApiKey("key-for-${AiProvider.PERPLEXITY}") }
        verify { settingsRepository.updateMoonshotApiKey("key-for-${AiProvider.MOONSHOT}") }
        verify { settingsRepository.updateMistralApiKey("key-for-${AiProvider.MISTRAL}") }
        verify { settingsRepository.updateAnythingLlmApiKey("key-for-${AiProvider.ANYTHINGLLM}") }
        verify { settingsRepository.updateCustomApiKey("key-for-${AiProvider.CUSTOM}") }
        // Gemini Live shares the Gemini credential, and the on-device model stores none.
        verify { settingsRepository.updateGeminiApiKey("key-for-${AiProvider.GEMINI}") }
        verify { settingsRepository.updateGeminiApiKey("key-for-${AiProvider.GEMINI_LIVE}") }
    }

    @Test
    fun `capability queries report the providers declaring them`() {
        val manager = manager()
        assertThat(manager.getSpeechProviders())
            .containsExactlyElementsIn(AiProvider.entries.filter { it.supportsSpeech })
        assertThat(manager.getVisionProviders())
            .containsExactlyElementsIn(AiProvider.entries.filter { it.supportsVision })
        assertThat(manager.getVisionProviders()).isNotEmpty()
    }
}
