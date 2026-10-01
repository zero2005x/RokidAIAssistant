package com.example.rokidphone.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.rokidphone.R
import com.example.rokidphone.service.stt.SttProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The per-setting writers on [SettingsRepository] and the localized system prompt
 * handling. The sibling SettingsMigrationTest covers loading and legacy migration.
 */
@RunWith(RobolectricTestRunner::class)
class SettingsRepositoryUpdatesTest {

    private lateinit var context: Context
    private lateinit var repository: SettingsRepository

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        repository = SettingsRepository(context)
    }

    @Test
    fun `every credential writer stores into its own field`() {
        val writers = listOf<Pair<(String) -> Unit, (ApiSettings) -> String>>(
            repository::updateGeminiApiKey to { it.geminiApiKey },
            repository::updateOpenaiApiKey to { it.openaiApiKey },
            repository::updateAnthropicApiKey to { it.anthropicApiKey },
            repository::updateDeepseekApiKey to { it.deepseekApiKey },
            repository::updateGroqApiKey to { it.groqApiKey },
            repository::updateXaiApiKey to { it.xaiApiKey },
            repository::updateAlibabaApiKey to { it.alibabaApiKey },
            repository::updateZhipuApiKey to { it.zhipuApiKey },
            repository::updateBaiduApiKey to { it.baiduApiKey },
            repository::updateBaiduSecretKey to { it.baiduSecretKey },
            repository::updateBaiduQianfanApiKey to { it.baiduQianfanApiKey },
            repository::updatePerplexityApiKey to { it.perplexityApiKey },
            repository::updateMoonshotApiKey to { it.moonshotApiKey },
            repository::updateMistralApiKey to { it.mistralApiKey },
            repository::updateAnythingLlmApiKey to { it.anythingllmApiKey },
            repository::updateCustomApiKey to { it.customApiKey }
        )

        writers.forEachIndexed { index, (write, read) ->
            write("secret-$index")
            assertThat(read(repository.getSettings())).isEqualTo("secret-$index")
        }

        // Each writer leaves the others alone.
        val settings = repository.getSettings()
        assertThat(writers.map { (_, read) -> read(settings) }.toSet())
            .hasSize(writers.size)
    }

    @Test
    fun `endpoint and workspace writers store their own field`() {
        repository.updateAnythingLlmServerUrl("https://llm.example")
        repository.updateAnythingLlmWorkspaceSlug("workspace-1")
        repository.updateCustomBaseUrl("https://custom.example/v1/")
        repository.updateCustomModelName("my-model")
        repository.updateCustomProtocol("openai")
        repository.updateCustomModelsPath("/v1/models")
        repository.updateCustomCapabilityOverrides(setOf("vision", "audio"))
        repository.updateAlibabaRegion(AlibabaRegions.JAPAN)
        repository.updateAlibabaCustomBaseUrl("https://my.dashscope/v1/")
        repository.updateBaiduUseLegacyAuth(true)

        val settings = repository.getSettings()
        assertThat(settings.anythingllmServerUrl).isEqualTo("https://llm.example")
        assertThat(settings.anythingllmWorkspaceSlug).isEqualTo("workspace-1")
        assertThat(settings.customBaseUrl).isEqualTo("https://custom.example/v1/")
        assertThat(settings.customModelName).isEqualTo("my-model")
        assertThat(settings.customProtocol).isEqualTo("openai")
        assertThat(settings.customModelsPath).isEqualTo("/v1/models")
        assertThat(settings.customCapabilityOverrides).containsExactly("vision", "audio")
        assertThat(settings.alibabaRegion).isEqualTo(AlibabaRegions.JAPAN)
        assertThat(settings.alibabaCustomBaseUrl).isEqualTo("https://my.dashscope/v1/")
        assertThat(settings.baiduUseLegacyAuth).isTrue()
    }

    @Test
    fun `switching provider restores the model that provider last used`() {
        repository.updateAiProvider(AiProvider.OPENAI)
        repository.updateAiModel("gpt-5.5-pro")
        assertThat(repository.getSettings().aiModelId).isEqualTo("gpt-5.5-pro")

        repository.updateAiProvider(AiProvider.ANTHROPIC)
        repository.updateAiModel("claude-x")
        assertThat(repository.getSettings().aiModelId).isEqualTo("claude-x")

        // Coming back restores the earlier choice rather than a catalog default.
        repository.updateAiProvider(AiProvider.OPENAI)
        assertThat(repository.getSettings().aiProvider).isEqualTo(AiProvider.OPENAI)
        assertThat(repository.getSettings().aiModelId).isEqualTo("gpt-5.5-pro")
    }

    @Test
    fun `the speech provider is stored`() {
        repository.updateSttProvider(SttProvider.DEEPGRAM)

        assertThat(repository.getSettings().sttProvider).isEqualTo(SttProvider.DEEPGRAM)
    }

    @Test
    fun `updates are published on the settings flow`() {
        val before = repository.settingsFlow.value

        repository.updateGeminiApiKey("g-1")

        assertThat(repository.settingsFlow.value).isNotEqualTo(before)
        assertThat(repository.settingsFlow.value).isEqualTo(repository.getSettings())
    }

    @Test
    fun `the default system prompt comes from the app resources`() {
        assertThat(repository.getDefaultSystemPrompt())
            .isEqualTo(context.getString(R.string.default_system_prompt))
    }

    @Test
    fun `a default prompt is recognised in every supported language`() {
        for (language in AppLanguage.entries) {
            val localized = repository.getDefaultSystemPromptForLanguage(language)
            assertThat(localized).isNotEmpty()

            repository.updateSystemPrompt(localized)
            assertThat(repository.isUsingDefaultSystemPrompt()).isTrue()
        }
    }

    @Test
    fun `a prompt the user wrote is not treated as a default`() {
        repository.updateSystemPrompt("Answer only in haiku.")

        assertThat(repository.isUsingDefaultSystemPrompt()).isFalse()

        repository.resetSystemPromptToDefault()
        assertThat(repository.getSettings().systemPrompt).isEqualTo(repository.getDefaultSystemPrompt())
        assertThat(repository.isUsingDefaultSystemPrompt()).isTrue()
    }

    @Test
    fun `an empty prompt counts as the default`() {
        repository.updateSystemPrompt("")

        assertThat(repository.isUsingDefaultSystemPrompt()).isTrue()
    }

    @Test
    fun `secure storage availability is reported alongside its error`() {
        // Robolectric provides a working keystore, so storage is available here.
        assertThat(repository.isSecureStorageAvailable)
            .isEqualTo(repository.secureStorageError.value == null)
    }
}
