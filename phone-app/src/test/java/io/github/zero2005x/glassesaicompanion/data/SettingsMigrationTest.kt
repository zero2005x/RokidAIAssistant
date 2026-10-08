package io.github.zero2005x.glassesaicompanion.data

import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Settings migration tests:
 *  - legacy single-model setting seeds the per-provider model map
 *  - DeepSeek legacy model IDs migrate to the V4 line
 *  - Baidu legacy credentials keep working (never deleted)
 *  - per-provider model memory survives provider switching
 *  - encrypted-storage failure never falls back to plaintext
 */
@RunWith(RobolectricTestRunner::class)
class SettingsMigrationTest {

    // ==================== Pure ApiSettings logic ====================

    @Test
    fun `deepseek legacy model ids migrate to v4`() {
        val settings = ApiSettings(
            aiProvider = AiProvider.DEEPSEEK,
            aiModelId = "deepseek-reasoner",
            providerModelIds = mapOf(AiProvider.DEEPSEEK.name to "deepseek-reasoner")
        )

        val migrated = settings.migrateLegacyModelIds()

        assertThat(migrated.aiModelId).isEqualTo("deepseek-v4-pro")
        assertThat(migrated.providerModelIds[AiProvider.DEEPSEEK.name]).isEqualTo("deepseek-v4-pro")
    }

    @Test
    fun `deepseek-chat migrates to deepseek-flash`() {
        val settings = ApiSettings(
            aiProvider = AiProvider.DEEPSEEK,
            aiModelId = "deepseek-chat",
            providerModelIds = mapOf(AiProvider.DEEPSEEK.name to "deepseek-chat")
        )

        val migrated = settings.migrateLegacyModelIds()

        assertThat(migrated.aiModelId).isEqualTo("deepseek-flash")
        // The migration map is a global id→id mapping: legacy IDs are migrated
        // on every provider, not only DeepSeek.
        val migratedOther = settings.copy(
            providerModelIds = mapOf(AiProvider.OPENAI.name to "deepseek-chat")
        ).migrateLegacyModelIds()
        assertThat(migratedOther.providerModelIds[AiProvider.OPENAI.name]).isEqualTo("deepseek-flash")
    }

    @Test
    fun `non-legacy models are not modified by migration`() {
        val settings = ApiSettings(
            aiProvider = AiProvider.OPENAI,
            aiModelId = "gpt-5.6",
            providerModelIds = mapOf(AiProvider.OPENAI.name to "gpt-5.6")
        )
        assertThat(settings.migrateLegacyModelIds()).isEqualTo(settings)
    }

    @Test
    fun `provider switching keeps each provider previous model`() {
        var settings = ApiSettings(aiProvider = AiProvider.OPENAI, aiModelId = "gpt-5.6")
        settings = settings.withModelForProvider(AiProvider.OPENAI, "gpt-5.6-terra")
        // Switch to Gemini, pick a model there.
        settings = settings.copy(aiProvider = AiProvider.GEMINI)
        settings = settings.withModelForProvider(AiProvider.GEMINI, "gemini-3.6-flash")

        // Switch back to OpenAI: the previous OpenAI model is restored.
        val restored = settings.getModelIdForProvider(AiProvider.OPENAI)
        assertThat(restored).isEqualTo("gpt-5.6-terra")
        assertThat(settings.getModelIdForProvider(AiProvider.GEMINI)).isEqualTo("gemini-3.6-flash")
    }

    @Test
    fun `retired Groq model migrates in primary and routing slot`() {
        val settings = ApiSettings(
            aiProvider = AiProvider.GROQ,
            aiModelId = "llama-3.3-70b-versatile",
            providerModelIds = mapOf(AiProvider.GROQ.name to "llama-3.3-70b-versatile"),
            fastRoutingModel = RoutingModel(AiProvider.GROQ, "qwen/qwen3.6-27b")
        )

        val migrated = settings.migrateLegacyModelIds()
        assertThat(migrated.aiModelId).isEqualTo("openai/gpt-oss-120b")
        assertThat(migrated.providerModelIds[AiProvider.GROQ.name]).isEqualTo("openai/gpt-oss-120b")
        assertThat(migrated.fastRoutingModel?.modelId).isEqualTo("qwen/qwen3.8-27b")
    }

    @Test
    fun `legacy aiModelId is used when provider has no stored model`() {
        val settings = ApiSettings(aiProvider = AiProvider.OPENAI, aiModelId = "gpt-4o")
        assertThat(settings.getModelIdForProvider(AiProvider.OPENAI)).isEqualTo("gpt-4o")
        // Another provider falls back to its verified default, not the active model.
        assertThat(settings.getModelIdForProvider(AiProvider.GEMINI)).isEqualTo("gemini-3.8-flash")
    }

    @Test
    fun `baidu legacy credentials auto-detect legacy mode and stay valid`() {
        val legacy = ApiSettings(
            aiProvider = AiProvider.BAIDU,
            baiduApiKey = "legacy-key",
            baiduSecretKey = "legacy-secret"
        )
        assertThat(legacy.isBaiduLegacyMode()).isTrue()
        assertThat(legacy.isValid()).isTrue()
        assertThat(legacy.getBaiduEffectiveApiKey()).isEqualTo("legacy-key")

        val qianfan = ApiSettings(
            aiProvider = AiProvider.BAIDU,
            baiduQianfanApiKey = "qianfan-key"
        )
        assertThat(qianfan.isBaiduLegacyMode()).isFalse()
        assertThat(qianfan.isValid()).isTrue()
        assertThat(qianfan.getBaiduEffectiveApiKey()).isEqualTo("qianfan-key")

        // Qianfan key wins over the legacy pair when both exist (upgrade path).
        val both = legacy.copy(baiduQianfanApiKey = "qianfan-key")
        assertThat(both.getBaiduEffectiveApiKey()).isEqualTo("qianfan-key")
    }

    @Test
    fun `alibaba region drives the base url`() {
        val settings = ApiSettings(aiProvider = AiProvider.ALIBABA, alibabaRegion = AlibabaRegions.SINGAPORE)
        assertThat(settings.getCurrentBaseUrl()).isEqualTo("https://dashscope-intl.aliyuncs.com/compatible-mode/v1/")

        val custom = settings.copy(
            alibabaRegion = AlibabaRegions.CUSTOM,
            alibabaCustomBaseUrl = "https://my-workspace.example.com/compatible-mode/v1/"
        )
        assertThat(custom.getCurrentBaseUrl()).isEqualTo("https://my-workspace.example.com/compatible-mode/v1/")
    }

    // ==================== Repository round-trip (Robolectric) ====================

    @Test
    fun `repository persists per-provider models and migrates on load`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val repo = SettingsRepository(context)

        // Simulate an upgraded user: DeepSeek selected with a legacy model.
        repo.saveSettings(
            ApiSettings(
                aiProvider = AiProvider.DEEPSEEK,
                aiModelId = "deepseek-chat",
                providerModelIds = mapOf(AiProvider.DEEPSEEK.name to "deepseek-chat")
            )
        )
        assertThat(repo.getSettings().providerModelIds[AiProvider.DEEPSEEK.name])
            .isEqualTo("deepseek-chat")

        if (repo.isSecureStorageAvailable) {
            // A fresh instance reads the same encrypted store and migrates.
            val reloaded = SettingsRepository(context)
            assertThat(reloaded.getSettings().providerModelIds[AiProvider.DEEPSEEK.name])
                .isEqualTo("deepseek-flash")
        } else {
            // Keystore unavailable (e.g. some test environments): the failure is
            // explicit and NOTHING was written to plaintext preferences.
            assertThat(repo.secureStorageError.value).isNotNull()
            val plaintextPrefs = context.getSharedPreferences("rokid_api_settings", 0)
            assertThat(plaintextPrefs.contains("deepseek_api_key")).isFalse()
        }
    }

    @Test
    fun `repository updateAiProvider restores stored model instead of resetting`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val repo = SettingsRepository(context)

        repo.saveSettings(ApiSettings(aiProvider = AiProvider.OPENAI))
        repo.updateAiModel("gpt-5.6-luna")
        repo.updateAiProvider(AiProvider.GEMINI)
        assertThat(repo.getSettings().aiProvider).isEqualTo(AiProvider.GEMINI)

        repo.updateAiProvider(AiProvider.OPENAI)
        assertThat(repo.getSettings().aiModelId).isEqualTo("gpt-5.6-luna")
    }
}
