package io.github.zero2005x.glassesaicompanion.service.ai

import io.github.zero2005x.glassesaicompanion.ai.catalog.ModelCapabilities
import io.github.zero2005x.glassesaicompanion.ai.catalog.ModelCapabilityResolver
import io.github.zero2005x.glassesaicompanion.data.AiProvider
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test

/**
 * Parameter policy tests: no provider ever receives parameters it does not
 * support (reasoning_effort, verbosity, penalties, wrong token-limit field,
 * wrong image content format).
 */
class ProviderRequestPolicyTest {

    private fun policyFor(
        provider: AiProvider,
        modelId: String,
        reasoningEffort: String? = null
    ): ProviderRequestPolicy {
        val caps = ModelCapabilityResolver.resolve(provider, modelId)
        return ProviderRequestPolicies.resolve(provider, modelId, caps, reasoningEffort)
    }

    // ==================== OpenAI ====================

    @Test
    fun `OpenAI gpt-5 family uses max_completion_tokens and reasoning_effort`() {
        val p = policyFor(AiProvider.OPENAI, "gpt-5.6")
        assertThat(p.tokenLimitField).isEqualTo(TokenLimitField.MAX_COMPLETION_TOKENS)
        assertThat(p.supportsReasoningEffort).isTrue()
        assertThat(p.supportsVerbosity).isTrue() // 5.6 >= 5.2
        assertThat(p.allowSampling).isFalse() // locked while effort != none
    }

    @Test
    fun `OpenAI reasoning_effort none unlocks sampling`() {
        val p = policyFor(AiProvider.OPENAI, "gpt-5.6", reasoningEffort = "none")
        assertThat(p.allowSampling).isTrue()
        assertThat(p.allowPenalties).isTrue()
    }

    @Test
    fun `OpenAI gpt-4o uses classic parameters`() {
        val p = policyFor(AiProvider.OPENAI, "gpt-4o")
        assertThat(p.tokenLimitField).isEqualTo(TokenLimitField.MAX_TOKENS)
        assertThat(p.supportsReasoningEffort).isFalse()
        assertThat(p.supportsVerbosity).isFalse()
        assertThat(p.allowSampling).isTrue()
    }

    @Test
    fun `OpenAI verbosity is supported since GPT-5`() {
        assertThat(ProviderRequestPolicies.openAiSupportsVerbosity("gpt-5.1")).isTrue()
        assertThat(ProviderRequestPolicies.openAiSupportsVerbosity("gpt-5.2")).isTrue()
        assertThat(ProviderRequestPolicies.openAiSupportsVerbosity("gpt-5.6-luna")).isTrue()
        assertThat(ProviderRequestPolicies.openAiSupportsVerbosity("gpt-4o")).isFalse()
    }

    // ==================== Non-OpenAI providers never get OpenAI params ====================

    @Test
    fun `non-OpenAI providers never receive reasoning_effort or verbosity`() {
        val providers = listOf(
            AiProvider.GROQ to "llama-3.3-70b-versatile",
            AiProvider.XAI to "grok-4.1-fast",
            AiProvider.DEEPSEEK to "deepseek-v4-flash",
            AiProvider.ALIBABA to "qwen3.7-max",
            AiProvider.ZHIPU to "glm-5.1",
            AiProvider.MOONSHOT to "kimi-k2.5",
            AiProvider.MISTRAL to "mistral-medium-3-5",
            AiProvider.PERPLEXITY to "sonar-pro",
            AiProvider.BAIDU to "ernie-5.1",
            AiProvider.CUSTOM to "gpt-5.6" // even an OpenAI-named model on a custom endpoint
        )
        for ((provider, modelId) in providers) {
            val p = policyFor(provider, modelId)
            assertWithMessage("$provider/$modelId").that(p.supportsReasoningEffort).isFalse()
            assertWithMessage("$provider/$modelId").that(p.supportsVerbosity).isFalse()
            val tokenField = if (provider == AiProvider.GROQ) TokenLimitField.MAX_COMPLETION_TOKENS else TokenLimitField.MAX_TOKENS
            assertWithMessage("$provider/$modelId").that(p.tokenLimitField).isEqualTo(tokenField)
        }
    }

    @Test
    fun `DeepSeek reasoning model strips sampling params`() {
        val p = policyFor(AiProvider.DEEPSEEK, "deepseek-v4-pro")
        assertThat(p.allowSampling).isFalse()
        assertThat(p.allowPenalties).isFalse()

        val chat = policyFor(AiProvider.DEEPSEEK, "deepseek-v4-flash")
        assertThat(chat.allowSampling).isTrue()
    }

    @Test
    fun `Perplexity Sonar never receives penalty params`() {
        val p = policyFor(AiProvider.PERPLEXITY, "sonar-pro")
        assertThat(p.allowPenalties).isFalse()
        assertThat(p.allowSampling).isTrue()
    }

    @Test
    fun `Moonshot and Zhipu never receive penalty params`() {
        val moonshot = policyFor(AiProvider.MOONSHOT, "kimi-k2.5")
        assertThat(moonshot.allowPenalties).isFalse()

        val zhipu = policyFor(AiProvider.ZHIPU, "glm-5.1")
        assertThat(zhipu.allowPenalties).isFalse()
    }

    @Test
    fun `stream options enabled only for supported providers`() {
        assertThat(policyFor(AiProvider.OPENAI, "gpt-5.1").supportsStreamOptions).isTrue()
        assertThat(policyFor(AiProvider.GROQ, "llama-3.3-70b-versatile").supportsStreamOptions).isTrue()
        assertThat(policyFor(AiProvider.MOONSHOT, "kimi-k2.5").supportsStreamOptions).isFalse()
        assertThat(policyFor(AiProvider.ZHIPU, "glm-5.1").supportsStreamOptions).isFalse()

        assertThat(policyFor(AiProvider.DEEPSEEK, "deepseek-chat").supportsStreamOptions).isTrue()
        assertThat(policyFor(AiProvider.PERPLEXITY, "sonar").supportsStreamOptions).isFalse()
        assertThat(policyFor(AiProvider.CUSTOM, "custom-model").supportsStreamOptions).isFalse()
    }

    @Test
    fun `Grok 4 reasoning-only strips penalties and stop`() {
        val p = policyFor(AiProvider.XAI, "grok-4")
        assertThat(p.allowPenalties).isFalse()
        assertThat(p.allowStop).isFalse()
        assertThat(p.allowSampling).isFalse()

        val fast = policyFor(AiProvider.XAI, "grok-4.1-fast")
        assertThat(fast.allowPenalties).isTrue()
        assertThat(fast.allowStop).isTrue()
    }

    // ==================== Image format gating ====================

    @Test
    fun `image content format is NONE for text-only models`() {
        assertThat(policyFor(AiProvider.DEEPSEEK, "deepseek-v4-pro").imageContentFormat)
            .isEqualTo(ImageContentFormat.NONE)
        assertThat(policyFor(AiProvider.ZHIPU, "glm-5.1").imageContentFormat)
            .isEqualTo(ImageContentFormat.NONE)
        assertThat(policyFor(AiProvider.ALIBABA, "qwen3.7-max").imageContentFormat)
            .isEqualTo(ImageContentFormat.NONE)
    }

    @Test
    fun `image content format is OPENAI_IMAGE_URL for vision models`() {
        assertThat(policyFor(AiProvider.ALIBABA, "qwen2.5-vl-72b").imageContentFormat)
            .isEqualTo(ImageContentFormat.OPENAI_IMAGE_URL)
        assertThat(policyFor(AiProvider.ZHIPU, "glm-5v-turbo").imageContentFormat)
            .isEqualTo(ImageContentFormat.OPENAI_IMAGE_URL)
        assertThat(policyFor(AiProvider.BAIDU, "ernie-4.5-turbo-vl").imageContentFormat)
            .isEqualTo(ImageContentFormat.OPENAI_IMAGE_URL)
    }

    // ==================== STT decoupling ====================

    @Test
    fun `only providers with real transcription endpoints support audio transcriptions`() {
        assertThat(policyFor(AiProvider.OPENAI, "gpt-5.6").supportsAudioTranscriptions).isTrue()
        assertThat(policyFor(AiProvider.GROQ, "llama-3.3-70b-versatile").supportsAudioTranscriptions).isTrue()

        val noStt = listOf(
            AiProvider.XAI to "grok-4.5",
            AiProvider.DEEPSEEK to "deepseek-v4-flash",
            AiProvider.ALIBABA to "qwen3.7-max",
            AiProvider.ZHIPU to "glm-5.1",
            AiProvider.MISTRAL to "mistral-medium-3-5",
            AiProvider.MOONSHOT to "kimi-k2.5",
            AiProvider.PERPLEXITY to "sonar",
            AiProvider.BAIDU to "ernie-5.1",
            AiProvider.CUSTOM to "anything"
        )
        for ((provider, modelId) in noStt) {
            assertWithMessage("$provider").that(policyFor(provider, modelId).supportsAudioTranscriptions)
                .isFalse()
        }
    }

    @Test
    fun `Groq transcription model is whisper-large-v3-turbo not a chat model`() {
        val p = policyFor(AiProvider.GROQ, "llama-3.3-70b-versatile")
        assertThat(p.transcriptionModel).isEqualTo("whisper-large-v3-turbo")
    }

    @Test
    fun `capabilities defaults are conservative for unknown models`() {
        val caps = ModelCapabilities.TEXT_ONLY
        assertThat(caps.imageInput).isFalse()
        assertThat(caps.streaming).isFalse()
        assertThat(caps.transcription).isFalse()
    }
}
