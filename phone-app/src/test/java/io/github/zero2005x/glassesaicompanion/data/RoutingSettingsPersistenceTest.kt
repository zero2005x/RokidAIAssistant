package io.github.zero2005x.glassesaicompanion.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.rokidcommon.protocol.GlassesDisplayConfig
import com.example.rokidcommon.protocol.GlassesDisplayMetrics
import com.example.rokidcommon.protocol.GlassesFont
import io.github.zero2005x.glassesaicompanion.service.ai.ProviderRequestPolicies
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * How the decision-routing settings are stored and read back, plus the model-id
 * migration and reasoning-effort rules the routing slots rely on.
 */
@RunWith(RobolectricTestRunner::class)
class RoutingSettingsPersistenceTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun parse(repo: SettingsRepository, raw: String?): RoutingModel? =
        SettingsRepository::class.java.getDeclaredMethod("parseRoutingModel", String::class.java)
            .apply { isAccessible = true }.invoke(repo, raw) as RoutingModel?

    private fun serialize(repo: SettingsRepository, value: RoutingModel?): String? =
        SettingsRepository::class.java.getDeclaredMethod("serializeRoutingModel", RoutingModel::class.java)
            .apply { isAccessible = true }.invoke(repo, value) as String?

    // ==================== Routing slot encoding ====================

    @Test
    fun aRoutingSlotSurvivesBeingSerialised() {
        val repo = SettingsRepository(context)
        val slot = RoutingModel(AiProvider.ANTHROPIC, "claude-sonnet-5-5")

        val stored = serialize(repo, slot)

        assertThat(stored).isNotNull()
        assertThat(parse(repo, stored)).isEqualTo(slot)
        assertThat(serialize(repo, null)).isNull()
    }

    @Test
    fun unusableStoredSlotsAreTreatedAsEmpty() {
        val repo = SettingsRepository(context)

        assertThat(parse(repo, null)).isNull()
        assertThat(parse(repo, "")).isNull()
        assertThat(parse(repo, "   ")).isNull()
        assertThat(parse(repo, "not json")).isNull()
        assertThat(parse(repo, """{"provider":"NO_SUCH_PROVIDER","modelId":"m"}""")).isNull()
        assertThat(parse(repo, """{"provider":"OPENAI"}""")).isNull()
        assertThat(parse(repo, """{"provider":"OPENAI","modelId":"   "}""")).isNull()
        // Surrounding whitespace in a stored model id is trimmed.
        assertThat(parse(repo, """{"provider":"OPENAI","modelId":" gpt-6-luna "}"""))
            .isEqualTo(RoutingModel(AiProvider.OPENAI, "gpt-6-luna"))
    }

    // ==================== Round trip through the store ====================

    @Test
    fun routingAndGlassesSettingsAreReadBackByAFreshRepository() {
        val repo = SettingsRepository(context)
        val metrics = GlassesDisplayMetrics(480, 640, 1.5f, 1.2f)
        val config = GlassesDisplayConfig(fontSizeSp = 24, font = GlassesFont.MONOSPACE,
            widthPercent = 60, heightPercent = 50, leftPercent = 20, topPercent = 30)
        repo.saveSettings(ApiSettings(
            decisionRoutingEnabled = true,
            decisionBackend = DecisionBackend.LAYA,
            decisionGeminiModel = "gemini-3.8-flash",
            decisionOpenaiModel = "gpt-6-luna",
            jevApiKey = "jev",
            layaBaseUrl = "http://192.168.1.20:8000",
            layaApiKey = "laya",
            fastRoutingModel = RoutingModel(AiProvider.GROQ, "openai/gpt-oss-120b"),
            balancedRoutingModel = RoutingModel(AiProvider.OPENAI, "gpt-6-luna"),
            qualityRoutingModel = RoutingModel(AiProvider.ANTHROPIC, "claude-sonnet-5-5"),
            glassesDisplayConfig = config
        ))
        repo.updateGlassesDisplayMetrics(metrics)

        assertThat(repo.getSettings().glassesDisplayMetrics).isEqualTo(metrics)
        if (!repo.isSecureStorageAvailable) {
            // Without a keystore nothing is written, so there is nothing to read back.
            assertThat(repo.secureStorageError.value).isNotNull()
            return
        }

        val reloaded = SettingsRepository(context).getSettings()
        assertThat(reloaded.decisionRoutingEnabled).isTrue()
        assertThat(reloaded.decisionBackend).isEqualTo(DecisionBackend.LAYA)
        assertThat(reloaded.layaBaseUrl).isEqualTo("http://192.168.1.20:8000")
        assertThat(reloaded.fastRoutingModel).isEqualTo(RoutingModel(AiProvider.GROQ, "openai/gpt-oss-120b"))
        assertThat(reloaded.balancedRoutingModel).isEqualTo(RoutingModel(AiProvider.OPENAI, "gpt-6-luna"))
        assertThat(reloaded.qualityRoutingModel).isEqualTo(RoutingModel(AiProvider.ANTHROPIC, "claude-sonnet-5-5"))
        assertThat(reloaded.glassesDisplayMetrics).isEqualTo(metrics)
        assertThat(reloaded.glassesDisplayConfig).isEqualTo(config)
    }

    // ==================== Legacy model migration ====================

    @Test
    fun aLegacyActiveModelIsMigratedOnItsOwn() {
        val settings = ApiSettings(aiProvider = AiProvider.DEEPSEEK, aiModelId = "deepseek-chat",
            providerModelIds = emptyMap())

        val migrated = settings.migrateLegacyModelIds()

        assertThat(migrated.aiModelId).isEqualTo("deepseek-flash")
        assertThat(migrated.providerModelIds).isEmpty()
    }

    @Test
    fun eachRoutingSlotIsMigratedIndependently() {
        val legacyGroq = RoutingModel(AiProvider.GROQ, "llama-3.3-70b-versatile")

        val balanced = ApiSettings(balancedRoutingModel = legacyGroq).migrateLegacyModelIds()
        assertThat(balanced.balancedRoutingModel).isEqualTo(RoutingModel(AiProvider.GROQ, "openai/gpt-oss-120b"))
        assertThat(balanced.fastRoutingModel).isNull()

        val quality = ApiSettings(qualityRoutingModel = RoutingModel(AiProvider.GROQ, "qwen/qwen3.6-27b"))
            .migrateLegacyModelIds()
        assertThat(quality.qualityRoutingModel).isEqualTo(RoutingModel(AiProvider.GROQ, "qwen/qwen3.8-27b"))
    }

    @Test
    fun slotsWithNothingToMigrateLeaveTheSettingsUntouched() {
        val settings = ApiSettings(
            // A provider with no migration table at all, and a mapped provider with a current model.
            fastRoutingModel = RoutingModel(AiProvider.ANTHROPIC, "claude-haiku-4-5"),
            balancedRoutingModel = RoutingModel(AiProvider.DEEPSEEK, "deepseek-v4-pro"),
            qualityRoutingModel = RoutingModel(AiProvider.GROQ, "openai/gpt-oss-120b")
        )

        assertThat(settings.migrateLegacyModelIds()).isSameInstanceAs(settings)
    }

    // ==================== Reasoning effort per model ====================

    @Test
    fun gpt6ModelsOnlyAcceptTheEffortsTheyDocument() {
        // Astra has no "none" level; Sol and Luna do.
        assertThat(ProviderRequestPolicies.openAiReasoningEffort("gpt-6-astra", "max")).isEqualTo("max")
        assertThat(ProviderRequestPolicies.openAiReasoningEffort("gpt-6-astra-1", "none")).isNull()
        assertThat(ProviderRequestPolicies.openAiReasoningEffort("gpt-6-sol", "none")).isEqualTo("none")
        assertThat(ProviderRequestPolicies.openAiReasoningEffort("gpt-6-luna", "xhigh")).isEqualTo("xhigh")
        assertThat(ProviderRequestPolicies.openAiReasoningEffort("gpt-6-luna", null)).isNull()
        assertThat(ProviderRequestPolicies.openAiReasoningEffort("gpt-4o", "high")).isNull()
    }

    @Test
    fun onlyRecentOpenAiFamiliesPreferTheResponsesApi() {
        assertThat(ProviderRequestPolicies.openAiPrefersResponses("gpt-5.6-terra")).isTrue()
        assertThat(ProviderRequestPolicies.openAiPrefersResponses("gpt-6-luna")).isTrue()
        assertThat(ProviderRequestPolicies.openAiPrefersResponses("gpt-5.1")).isFalse()
        assertThat(ProviderRequestPolicies.openAiPrefersResponses("gpt-4o")).isFalse()
    }
}
