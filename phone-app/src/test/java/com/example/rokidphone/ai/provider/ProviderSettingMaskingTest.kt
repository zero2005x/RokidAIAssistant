package com.example.rokidphone.ai.provider

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Credential masking in [ProviderSetting.toString] and the URL scheme check behind
 * the AnythingLLM and Custom validation. The sibling ProviderSettingTest covers
 * isValid() and the default values.
 */
class ProviderSettingMaskingTest {

    private val secret = "sk-super-secret-value"

    /** Every variant, configured with the same credential. */
    private val configured: List<ProviderSetting> = listOf(
        ProviderSetting.Gemini(apiKey = secret),
        ProviderSetting.OpenAI(apiKey = secret),
        ProviderSetting.Anthropic(apiKey = secret),
        ProviderSetting.DeepSeek(apiKey = secret),
        ProviderSetting.Groq(apiKey = secret),
        ProviderSetting.XAI(apiKey = secret),
        ProviderSetting.Alibaba(apiKey = secret),
        ProviderSetting.Zhipu(apiKey = secret),
        ProviderSetting.Baidu(apiKey = secret, secretKey = secret),
        ProviderSetting.Perplexity(apiKey = secret),
        ProviderSetting.Moonshot(apiKey = secret),
        ProviderSetting.Mistral(apiKey = secret),
        ProviderSetting.AnythingLLM(apiKey = secret),
        ProviderSetting.Custom(apiKey = secret)
    )

    @Test
    fun `no provider ever prints its raw credential`() {
        for (provider in configured) {
            val printed = provider.toString()

            assertThat(printed).doesNotContain(secret)
            assertThat(printed).contains("apiKey=***")
            // The non-secret identity stays legible so logs remain useful.
            assertThat(printed).contains("id=${provider.id}")
        }
    }

    @Test
    fun `an unconfigured provider prints its key as unset`() {
        for (provider in ProviderSetting.getDefaultProviders()) {
            assertThat(provider.toString()).contains("apiKey=(unset)")
        }
    }

    @Test
    fun `baidu masks both halves of its credential`() {
        val printed = ProviderSetting.Baidu(apiKey = secret, secretKey = "other-secret").toString()

        assertThat(printed).doesNotContain(secret)
        assertThat(printed).doesNotContain("other-secret")
        assertThat(printed).contains("apiKey=***")
        assertThat(printed).contains("secretKey=***")

        assertThat(ProviderSetting.Baidu().toString()).contains("secretKey=(unset)")
    }

    @Test
    fun `a whitespace only key counts as unset`() {
        assertThat(ProviderSetting.Gemini(apiKey = "   ").toString()).contains("apiKey=(unset)")
    }

    @Test
    fun `the printed form names the model and endpoint`() {
        val printed = ProviderSetting.OpenAI(apiKey = secret).toString()

        assertThat(printed).startsWith("OpenAI(")
        assertThat(printed).contains("modelId=")
        assertThat(printed).contains("baseUrl=https://api.openai.com/v1/")
        assertThat(printed).contains("enabled=false")
    }

    @Test
    fun `anythingllm prints its workspace rather than a model`() {
        val printed = ProviderSetting.AnythingLLM(
            serverUrl = "https://llm.example", apiKey = secret, workspaceSlug = "docs"
        ).toString()

        assertThat(printed).contains("serverUrl=https://llm.example")
        assertThat(printed).contains("workspaceSlug=docs")
        assertThat(printed).doesNotContain(secret)
    }

    @Test
    fun `a server url must use an http scheme to be valid`() {
        val complete = ProviderSetting.AnythingLLM(
            serverUrl = "https://llm.example", apiKey = secret, workspaceSlug = "docs"
        )
        assertThat(complete.isValid()).isTrue()

        for (url in listOf(
            "ftp://llm.example",
            "file:///etc/passwd",
            "llm.example",
            "://broken",
            "   "
        )) {
            assertThat(complete.copy(serverUrl = url).isValid()).isFalse()
        }

        // Plain http and a surrounding-whitespace URL are both accepted.
        assertThat(complete.copy(serverUrl = "http://localhost:3001").isValid()).isTrue()
        assertThat(complete.copy(serverUrl = "  https://llm.example  ").isValid()).isTrue()
    }

    @Test
    fun `a custom endpoint is rejected unless its url uses an http scheme`() {
        val custom = ProviderSetting.Custom()

        // The shipped default points at a local Ollama install.
        assertThat(custom.isValid()).isTrue()
        assertThat(custom.copy(baseUrl = "https://vllm.example/v1/").isValid()).isTrue()

        for (url in listOf("", "   ", "not a url", "ftp://host/v1", "://broken")) {
            assertThat(custom.copy(baseUrl = url).isValid()).isFalse()
        }
    }
}
