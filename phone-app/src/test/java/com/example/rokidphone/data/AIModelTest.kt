package com.example.rokidphone.data

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Unit tests for AIModel sealed class hierarchy and ModelRepository.
 * Validates model IDs, provider categorization, and repository filtering.
 */
class AIModelTest {

    // ==================== Gemini Model ID Tests ====================

    @Test
    fun `Gemini 3_1 Pro Preview has correct model ID`() {
        assertThat(AIModel.Gemini.Gemini31ProPreview.modelId).isEqualTo("gemini-3.1-pro-preview")
    }

    @Test
    fun `Gemini 3 Flash Preview has correct model ID`() {
        assertThat(AIModel.Gemini.Gemini3FlashPreview.modelId).isEqualTo("gemini-3-flash-preview")
    }

    @Test
    fun `Gemini 2_5 Pro has correct model ID`() {
        assertThat(AIModel.Gemini.Gemini25Pro.modelId).isEqualTo("gemini-2.5-pro")
    }

    @Test
    fun `Gemini 2_5 Flash has correct model ID`() {
        assertThat(AIModel.Gemini.Gemini25Flash.modelId).isEqualTo("gemini-2.5-flash")
    }

    @Test
    fun `Gemini 2_5 Flash-Lite has correct model ID`() {
        assertThat(AIModel.Gemini.Gemini25FlashLite.modelId).isEqualTo("gemini-2.5-flash-lite")
    }

    @Test
    fun `Gemini Nano has correct model ID`() {
        assertThat(AIModel.Gemini.GeminiNano.modelId).isEqualTo("gemini-nano")
    }

    @Test
    fun `all Gemini models belong to GEMINI provider`() {
        AIModel.Gemini.all().forEach { model ->
            assertThat(model.provider).isEqualTo(Provider.GEMINI)
        }
    }

    @Test
    fun `Gemini preview models are marked correctly`() {
        assertThat(AIModel.Gemini.Gemini31ProPreview.isPreview).isTrue()
        assertThat(AIModel.Gemini.Gemini3FlashPreview.isPreview).isTrue()
        assertThat(AIModel.Gemini.Gemini25Pro.isPreview).isFalse()
        assertThat(AIModel.Gemini.Gemini25Flash.isPreview).isFalse()
    }

    // ==================== Claude Model ID Tests ====================

    @Test
    fun `Claude Opus 4_6 has correct model ID`() {
        assertThat(AIModel.Claude.Opus46.modelId).isEqualTo("claude-opus-4-6")
    }

    @Test
    fun `Claude Sonnet 4_6 has correct model ID`() {
        assertThat(AIModel.Claude.Sonnet46.modelId).isEqualTo("claude-sonnet-4-6")
    }

    @Test
    fun `Claude Haiku 4_5 has correct model ID`() {
        assertThat(AIModel.Claude.Haiku45.modelId).isEqualTo("claude-haiku-4-5-20251001")
    }

    @Test
    fun `all Claude models belong to CLAUDE provider`() {
        AIModel.Claude.all().forEach { model ->
            assertThat(model.provider).isEqualTo(Provider.CLAUDE)
        }
    }

    @Test
    fun `Claude 1M context support is correct for Opus and Sonnet 4_6`() {
        assertThat(AIModel.Claude.supports1MContext("claude-opus-4-6")).isTrue()
        assertThat(AIModel.Claude.supports1MContext("claude-sonnet-4-6")).isTrue()
        assertThat(AIModel.Claude.supports1MContext("claude-haiku-4-5-20251001")).isFalse()
        assertThat(AIModel.Claude.supports1MContext("claude-3-opus-20240229")).isFalse()
    }

    @Test
    fun `no Claude models are deprecated`() {
        AIModel.Claude.all().forEach { model ->
            assertThat(model.isDeprecated).isFalse()
        }
    }

    // ==================== OpenAI Model ID Tests ====================

    @Test
    fun `GPT-5_2 has correct model ID`() {
        assertThat(AIModel.OpenAI.Gpt52.modelId).isEqualTo("gpt-5.2")
    }

    @Test
    fun `GPT-5_2 Codex has correct model ID`() {
        assertThat(AIModel.OpenAI.Gpt52Codex.modelId).isEqualTo("gpt-5.2-codex")
    }

    @Test
    fun `GPT-5_1 has correct model ID`() {
        assertThat(AIModel.OpenAI.Gpt51.modelId).isEqualTo("gpt-5.1")
    }

    @Test
    fun `GPT-5 Mini has correct model ID`() {
        assertThat(AIModel.OpenAI.Gpt5Mini.modelId).isEqualTo("gpt-5-mini")
    }

    @Test
    fun `GPT-5 Nano has correct model ID`() {
        assertThat(AIModel.OpenAI.Gpt5Nano.modelId).isEqualTo("gpt-5-nano")
    }

    @Test
    fun `o3 has correct model ID`() {
        assertThat(AIModel.OpenAI.O3.modelId).isEqualTo("o3")
    }

    @Test
    fun `all OpenAI models belong to OPENAI provider`() {
        AIModel.OpenAI.all().forEach { model ->
            assertThat(model.provider).isEqualTo(Provider.OPENAI)
        }
    }

    @Test
    fun `no OpenAI models are deprecated`() {
        AIModel.OpenAI.all().forEach { model ->
            assertThat(model.isDeprecated).isFalse()
        }
    }

    // ==================== Cross-Provider Tests ====================

    @Test
    fun `allModels returns models from all registered providers`() {
        val all = AIModel.allModels()
        val providers = all.map { it.provider }.toSet()
        // v0.12.0 expanded the registry; Mistral added as 11th provider.
        assertThat(providers).containsExactly(
            Provider.GEMINI, Provider.CLAUDE, Provider.OPENAI, Provider.GROK,
            Provider.DEEPSEEK, Provider.QWEN, Provider.ZHIPU, Provider.MOONSHOT,
            Provider.PERPLEXITY, Provider.GROQ, Provider.MISTRAL
        )
    }

    @Test
    fun `all model IDs are unique`() {
        val allIds = AIModel.allModels().map { it.modelId }
        assertThat(allIds).containsNoDuplicates()
    }

    @Test
    fun `no deprecated model IDs in registry`() {
        // Verify none of the old deprecated model IDs are present
        val allIds = AIModel.allModels().map { it.modelId }.toSet()
        val deprecatedIds = listOf(
            "gemini-2.0-flash", "gemini-2.0-flash-lite",
            "gemini-1.5-pro", "gemini-1.5-flash",
            "claude-sonnet-4-5-20250929", "claude-opus-4-1-20250805",
            "claude-3-7-sonnet-20250219", "claude-3-5-sonnet-20241022",
            "gpt-4o", "gpt-4o-mini", "gpt-4.1", "o4-mini"
        )
        deprecatedIds.forEach { id ->
            assertThat(allIds).doesNotContain(id)
        }
    }

    // ==================== ModelRepository Tests ====================

    @Test
    fun `getAvailableModels excludes deprecated models`() {
        val available = ModelRepository.getAvailableModels()
        available.forEach { model ->
            assertThat(model.isDeprecated).isFalse()
        }
    }

    @Test
    fun `getAvailableModels returns non-empty list`() {
        assertThat(ModelRepository.getAvailableModels()).isNotEmpty()
    }

    @Test
    fun `getModelsByProvider groups correctly`() {
        val grouped = ModelRepository.getModelsByProvider()
        assertThat(grouped).containsKey(Provider.GEMINI)
        assertThat(grouped).containsKey(Provider.CLAUDE)
        assertThat(grouped).containsKey(Provider.OPENAI)
        assertThat(grouped).containsKey(Provider.GROK)
    }

    @Test
    fun `getModelsForProvider returns only matching provider`() {
        val claudeModels = ModelRepository.getModelsForProvider(Provider.CLAUDE)
        claudeModels.forEach { model ->
            assertThat(model.provider).isEqualTo(Provider.CLAUDE)
        }
        // v0.12.0: 4 from prior refresh + 4 newly added = 8 Claude models
        assertThat(claudeModels).hasSize(8)
    }

    @Test
    fun `findByModelId returns correct model`() {
        val model = ModelRepository.findByModelId("claude-sonnet-4-6")
        assertThat(model).isNotNull()
        assertThat(model).isInstanceOf(AIModel.Claude.Sonnet46::class.java)
        assertThat(model!!.displayName).isEqualTo("Claude Sonnet 4.6")
    }

    @Test
    fun `findByModelId returns null for unknown ID`() {
        assertThat(ModelRepository.findByModelId("nonexistent-model")).isNull()
    }

    @Test
    fun `findByModelId returns null for deprecated old IDs`() {
        assertThat(ModelRepository.findByModelId("gpt-4o")).isNull()
        assertThat(ModelRepository.findByModelId("claude-3-opus-20240229")).isNull()
    }

    @Test
    fun `getPreviewModels returns only preview models`() {
        val previews = ModelRepository.getPreviewModels()
        previews.forEach { model ->
            assertThat(model.isPreview).isTrue()
        }
        // Should include Gemini 3.1 Pro Preview, Gemini 3 Flash Preview, and Grok 4.20
        assertThat(previews.map { it.modelId }).containsAtLeast(
            "gemini-3.1-pro-preview",
            "gemini-3-flash-preview",
            "grok-4.20"
        )
    }

    @Test
    fun `getStableModels excludes preview models`() {
        val stable = ModelRepository.getStableModels()
        stable.forEach { model ->
            assertThat(model.isPreview).isFalse()
        }
    }

    // ==================== Context Window Tests ====================

    @Test
    fun `Gemini 2_5 models have 1M context window`() {
        assertThat(AIModel.Gemini.Gemini25Pro.contextWindow).isEqualTo(1_000_000L)
        assertThat(AIModel.Gemini.Gemini25Flash.contextWindow).isEqualTo(1_000_000L)
        assertThat(AIModel.Gemini.Gemini25FlashLite.contextWindow).isEqualTo(1_000_000L)
    }

    @Test
    fun `Claude models have 200K context window`() {
        assertThat(AIModel.Claude.Opus46.contextWindow).isEqualTo(200_000L)
        assertThat(AIModel.Claude.Sonnet46.contextWindow).isEqualTo(200_000L)
        assertThat(AIModel.Claude.Haiku45.contextWindow).isEqualTo(200_000L)
    }

    @Test
    fun `GPT-5_x flagship models have ~400K context window`() {
        assertThat(AIModel.OpenAI.Gpt52.contextWindow).isEqualTo(400_000L)
        assertThat(AIModel.OpenAI.Gpt51.contextWindow).isEqualTo(400_000L)
        assertThat(AIModel.OpenAI.Gpt52Codex.contextWindow).isEqualTo(400_000L)
    }

    @Test
    fun `GPT-5 mini models have ~128K context window`() {
        assertThat(AIModel.OpenAI.Gpt5Mini.contextWindow).isEqualTo(128_000L)
        assertThat(AIModel.OpenAI.Gpt5Nano.contextWindow).isEqualTo(128_000L)
    }

    @Test
    fun `o3 has 200K context window`() {
        assertThat(AIModel.OpenAI.O3.contextWindow).isEqualTo(200_000L)
    }

    // ==================== Grok Model ID Tests ====================

    @Test
    fun `Grok 4_20 has correct model ID and is preview`() {
        assertThat(AIModel.Grok.Grok420.modelId).isEqualTo("grok-4.20")
        assertThat(AIModel.Grok.Grok420.isPreview).isTrue()
    }

    @Test
    fun `Grok 4 has correct model ID and is reasoning-only`() {
        assertThat(AIModel.Grok.Grok4.modelId).isEqualTo("grok-4")
        assertThat(AIModel.Grok.Grok4.isReasoningOnly).isTrue()
    }

    @Test
    fun `Grok 4_1 Fast has correct model ID and 2M context`() {
        assertThat(AIModel.Grok.Grok41Fast.modelId).isEqualTo("grok-4.1-fast")
        assertThat(AIModel.Grok.Grok41Fast.contextWindow).isEqualTo(2_000_000L)
        assertThat(AIModel.Grok.Grok41Fast.isReasoningOnly).isFalse()
    }

    @Test
    fun `Grok 3 has correct model ID`() {
        assertThat(AIModel.Grok.Grok3.modelId).isEqualTo("grok-3")
    }

    @Test
    fun `Grok 3 Mini has correct model ID`() {
        assertThat(AIModel.Grok.Grok3Mini.modelId).isEqualTo("grok-3-mini")
    }

    @Test
    fun `Grok Image has correct model ID`() {
        assertThat(AIModel.Grok.GrokImage.modelId).isEqualTo("grok-2-image-1212")
    }

    @Test
    fun `all Grok models belong to GROK provider`() {
        AIModel.Grok.all().forEach { model ->
            assertThat(model.provider).isEqualTo(Provider.GROK)
        }
    }

    @Test
    fun `Grok isReasoningOnly identifies grok-4 correctly`() {
        assertThat(AIModel.Grok.isReasoningOnly("grok-4")).isTrue()
        assertThat(AIModel.Grok.isReasoningOnly("grok-4.1-fast")).isFalse()
        assertThat(AIModel.Grok.isReasoningOnly("grok-3")).isFalse()
        assertThat(AIModel.Grok.isReasoningOnly("grok-3-mini")).isFalse()
    }

    @Test
    fun `Grok 4 has 256K context window`() {
        assertThat(AIModel.Grok.Grok4.contextWindow).isEqualTo(256_000L)
    }

    @Test
    fun `Grok 3 models have 128K context window`() {
        assertThat(AIModel.Grok.Grok3.contextWindow).isEqualTo(128_000L)
        assertThat(AIModel.Grok.Grok3Mini.contextWindow).isEqualTo(128_000L)
    }

    // ==================== v0.12.0 April 2026 refresh ====================

    // --- Gemini 3.1 family ---

    @Test
    fun `Gemini 3_1 Flash has correct model ID and 1M context`() {
        assertThat(AIModel.Gemini.Gemini31Flash.modelId).isEqualTo("gemini-3.1-flash")
        assertThat(AIModel.Gemini.Gemini31Flash.contextWindow).isEqualTo(1_000_000L)
        assertThat(AIModel.Gemini.Gemini31Flash.isPreview).isFalse()
    }

    @Test
    fun `Gemini 3_1 Flash-Lite has correct model ID`() {
        assertThat(AIModel.Gemini.Gemini31FlashLite.modelId).isEqualTo("gemini-3.1-flash-lite")
        assertThat(AIModel.Gemini.Gemini31FlashLite.contextWindow).isEqualTo(1_000_000L)
    }

    @Test
    fun `Gemini 3_1 Deep Think has correct model ID and is preview`() {
        assertThat(AIModel.Gemini.Gemini31DeepThink.modelId).isEqualTo("gemini-3.1-pro-deep-think")
        assertThat(AIModel.Gemini.Gemini31DeepThink.isPreview).isTrue()
    }

    // --- Claude Opus 4.7 ---

    @Test
    fun `Claude Opus 4_7 is registered and supports 1M context`() {
        assertThat(AIModel.Claude.Opus47.modelId).isEqualTo("claude-opus-4-7")
        assertThat(AIModel.Claude.Opus47.contextWindow).isEqualTo(200_000L)
        assertThat(AIModel.Claude.supports1MContext("claude-opus-4-7")).isTrue()
    }

    // --- OpenAI GPT-5.4 family ---

    @Test
    fun `GPT-5_4 flagship models have 1M context`() {
        assertThat(AIModel.OpenAI.Gpt54.modelId).isEqualTo("gpt-5.4")
        assertThat(AIModel.OpenAI.Gpt54.contextWindow).isEqualTo(1_000_000L)
        assertThat(AIModel.OpenAI.Gpt54Pro.modelId).isEqualTo("gpt-5.4-pro")
        assertThat(AIModel.OpenAI.Gpt54Pro.contextWindow).isEqualTo(1_000_000L)
    }

    @Test
    fun `GPT-5_4 mini and nano have 400K context`() {
        assertThat(AIModel.OpenAI.Gpt54Mini.contextWindow).isEqualTo(400_000L)
        assertThat(AIModel.OpenAI.Gpt54Nano.contextWindow).isEqualTo(400_000L)
    }

    // --- Grok 4.20 Beta Reasoning ---

    @Test
    fun `Grok 4_20 Beta Reasoning is preview and reasoning-only with 2M context`() {
        val m = AIModel.Grok.Grok420BetaReasoning
        assertThat(m.modelId).isEqualTo("grok-4.20-beta-latest-reasoning")
        assertThat(m.isPreview).isTrue()
        assertThat(m.isReasoningOnly).isTrue()
        assertThat(m.contextWindow).isEqualTo(2_000_000L)
    }

    // --- DeepSeek ---

    @Test
    fun `DeepSeek registry exposes Chat Reasoner and Speciale`() {
        val ids = AIModel.DeepSeek.all().map { it.modelId }
        // includes legacy + newly added preview entries
        assertThat(ids).containsAtLeast("deepseek-chat", "deepseek-reasoner", "deepseek-v3.2-speciale")
    }

    @Test
    fun `DeepSeek isReasoner identifies reasoner and speciale only`() {
        assertThat(AIModel.DeepSeek.isReasoner("deepseek-reasoner")).isTrue()
        assertThat(AIModel.DeepSeek.isReasoner("deepseek-v3.2-speciale")).isTrue()
        assertThat(AIModel.DeepSeek.isReasoner("deepseek-chat")).isFalse()
        assertThat(AIModel.DeepSeek.isReasoner("unknown")).isFalse()
    }

    @Test
    fun `DeepSeek Speciale is preview`() {
        assertThat(AIModel.DeepSeek.Speciale.isPreview).isTrue()
        assertThat(AIModel.DeepSeek.Chat.isPreview).isFalse()
        assertThat(AIModel.DeepSeek.Reasoner.isPreview).isFalse()
    }

    // --- Qwen ---

    @Test
    fun `Qwen registry includes Qwen3 Max 2026-01-23 with thinking mode`() {
        assertThat(AIModel.Qwen.Qwen3Max.modelId).isEqualTo("qwen3-max-2026-01-23")
        assertThat(AIModel.Qwen.Qwen3Max.supportsThinkingMode).isTrue()
        assertThat(AIModel.Qwen.supportsThinkingMode("qwen3-max-2026-01-23")).isTrue()
        assertThat(AIModel.Qwen.supportsThinkingMode("qwen3.5-plus")).isFalse()
    }

    // --- Zhipu ---

    @Test
    fun `Zhipu registry includes GLM-5 5_1 4_6V 4-Plus`() {
        val ids = AIModel.Zhipu.all().map { it.modelId }
        assertThat(ids).containsExactly("glm-5", "glm-5.1", "glm-4.6v", "glm-4-plus")
    }

    // --- Moonshot ---

    @Test
    fun `Moonshot registry includes Kimi K2_5 and K2_5 Thinking`() {
        val ids = AIModel.Moonshot.all().map { it.modelId }
        assertThat(ids).containsExactly("kimi-k2.5", "kimi-k2.5-thinking")
    }

    @Test
    fun `Moonshot isThinkingMode identifies thinking variant only`() {
        assertThat(AIModel.Moonshot.isThinkingMode("kimi-k2.5-thinking")).isTrue()
        assertThat(AIModel.Moonshot.isThinkingMode("kimi-k2.5")).isFalse()
    }

    // --- Perplexity ---

    @Test
    fun `Perplexity registry includes Sonar family`() {
        val ids = AIModel.Perplexity.all().map { it.modelId }
        assertThat(ids).containsExactly("sonar", "sonar-pro", "sonar-reasoning-pro", "sonar-deep-research")
    }

    // --- Groq ---

    @Test
    fun `Groq registry includes cross-provider hosted models`() {
        val ids = AIModel.Groq.all().map { it.modelId }.toSet()
        assertThat(ids).containsAtLeast(
            "moonshotai/kimi-k2-instruct",
            "openai/gpt-oss-120b",
            "openai/gpt-oss-20b",
            "meta-llama/llama-4-maverick-17b-128e-instruct",
            "meta-llama/llama-4-scout-17b-16e-instruct",
            "qwen/qwen3-32b"
        )
    }

    // --- Cross-provider coverage ---

    @Test
    fun `getModelsByProvider contains all ten providers`() {
        val grouped = ModelRepository.getModelsByProvider()
        assertThat(grouped.keys).containsExactly(
            Provider.GEMINI, Provider.CLAUDE, Provider.OPENAI, Provider.GROK,
            Provider.DEEPSEEK, Provider.QWEN, Provider.ZHIPU, Provider.MOONSHOT,
            Provider.PERPLEXITY, Provider.GROQ, Provider.MISTRAL
        )
    }
}
