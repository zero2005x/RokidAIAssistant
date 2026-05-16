package com.example.rokidphone.ai.provider

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Unit tests for ProviderSetting sealed class hierarchy.
 * Validates isValid(), default values, fromId(), and getDefaultProviders().
 *
 * ProviderSetting 密封類別單元測試。
 * 驗證 isValid()、預設值、fromId() 及 getDefaultProviders()。
 */
class ProviderSettingTest {

    // ==================== isValid() Tests 驗證測試 ====================

    @Test
    fun `Gemini isValid returns true when apiKey is not blank`() {
        // Gemini 有 API Key 時 isValid 應回傳 true
        val setting = ProviderSetting.Gemini(apiKey = "test-key")
        assertThat(setting.isValid()).isTrue()
    }

    @Test
    fun `Gemini isValid returns false when apiKey is blank`() {
        // Gemini 無 API Key 時 isValid 應回傳 false
        val setting = ProviderSetting.Gemini(apiKey = "")
        assertThat(setting.isValid()).isFalse()
    }

    @Test
    fun `OpenAI isValid returns true when apiKey is not blank`() {
        val setting = ProviderSetting.OpenAI(apiKey = "sk-test")
        assertThat(setting.isValid()).isTrue()
    }

    @Test
    fun `OpenAI isValid returns false when apiKey is blank`() {
        val setting = ProviderSetting.OpenAI(apiKey = "")
        assertThat(setting.isValid()).isFalse()
    }

    @Test
    fun `Anthropic isValid returns true when apiKey is not blank`() {
        val setting = ProviderSetting.Anthropic(apiKey = "sk-ant-test")
        assertThat(setting.isValid()).isTrue()
    }

    @Test
    fun `DeepSeek isValid returns true when apiKey is not blank`() {
        val setting = ProviderSetting.DeepSeek(apiKey = "ds-key")
        assertThat(setting.isValid()).isTrue()
    }

    @Test
    fun `Groq isValid returns true when apiKey is not blank`() {
        val setting = ProviderSetting.Groq(apiKey = "gsk-key")
        assertThat(setting.isValid()).isTrue()
    }

    @Test
    fun `XAI isValid returns true when apiKey is not blank`() {
        val setting = ProviderSetting.XAI(apiKey = "xai-key")
        assertThat(setting.isValid()).isTrue()
    }

    @Test
    fun `Alibaba isValid returns true when apiKey is not blank`() {
        val setting = ProviderSetting.Alibaba(apiKey = "ali-key")
        assertThat(setting.isValid()).isTrue()
    }

    @Test
    fun `Zhipu isValid returns true when apiKey is not blank`() {
        val setting = ProviderSetting.Zhipu(apiKey = "zhipu-key")
        assertThat(setting.isValid()).isTrue()
    }

    @Test
    fun `Perplexity isValid returns true when apiKey is not blank`() {
        val setting = ProviderSetting.Perplexity(apiKey = "pplx-key")
        assertThat(setting.isValid()).isTrue()
    }

    @Test
    fun `Moonshot isValid returns true when apiKey is not blank`() {
        val setting = ProviderSetting.Moonshot(apiKey = "moon-key")
        assertThat(setting.isValid()).isTrue()
    }

    // ==================== Baidu Special Validation 百度特殊驗證 ====================

    @Test
    fun `Baidu isValid returns true when both apiKey and secretKey are not blank`() {
        // 百度需要 apiKey 和 secretKey 都不為空
        val setting = ProviderSetting.Baidu(apiKey = "baidu-key", secretKey = "baidu-secret")
        assertThat(setting.isValid()).isTrue()
    }

    @Test
    fun `Baidu isValid returns false when apiKey is blank`() {
        val setting = ProviderSetting.Baidu(apiKey = "", secretKey = "baidu-secret")
        assertThat(setting.isValid()).isFalse()
    }

    @Test
    fun `Baidu isValid returns false when secretKey is blank`() {
        val setting = ProviderSetting.Baidu(apiKey = "baidu-key", secretKey = "")
        assertThat(setting.isValid()).isFalse()
    }

    @Test
    fun `Baidu isValid returns false when both keys are blank`() {
        val setting = ProviderSetting.Baidu(apiKey = "", secretKey = "")
        assertThat(setting.isValid()).isFalse()
    }

    // ==================== AnythingLLM Validation AnythingLLM 驗證 ====================

    @Test
    fun `AnythingLLM isValid returns true when all three fields are not blank`() {
        val setting = ProviderSetting.AnythingLLM(
            serverUrl = "http://localhost:3001",
            apiKey = "my-key",
            workspaceSlug = "workspace"
        )
        assertThat(setting.isValid()).isTrue()
    }

    @Test
    fun `AnythingLLM isValid returns false when serverUrl is blank`() {
        val setting = ProviderSetting.AnythingLLM(serverUrl = "", apiKey = "key", workspaceSlug = "slug")
        assertThat(setting.isValid()).isFalse()
    }

    @Test
    fun `AnythingLLM isValid returns false when apiKey is blank`() {
        val setting = ProviderSetting.AnythingLLM(serverUrl = "http://host", apiKey = "", workspaceSlug = "slug")
        assertThat(setting.isValid()).isFalse()
    }

    @Test
    fun `AnythingLLM isValid returns false when workspaceSlug is blank`() {
        val setting = ProviderSetting.AnythingLLM(serverUrl = "http://host", apiKey = "key", workspaceSlug = "")
        assertThat(setting.isValid()).isFalse()
    }

    @Test
    fun `AnythingLLM has correct default values`() {
        val setting = ProviderSetting.AnythingLLM()
        assertThat(setting.id).isEqualTo("anythingllm")
        assertThat(setting.displayName).isEqualTo("AnythingLLM")
        assertThat(setting.enabled).isTrue()
        assertThat(setting.serverUrl).isEmpty()
        assertThat(setting.apiKey).isEmpty()
        assertThat(setting.workspaceSlug).isEmpty()
        assertThat(setting.providerBaseUrl).isEmpty()
    }

    @Test
    fun `AnythingLLM providerApiKey reflects apiKey`() {
        val setting = ProviderSetting.AnythingLLM(apiKey = "ak-secret")
        assertThat(setting.providerApiKey).isEqualTo("ak-secret")
    }

    // ==================== Custom Provider Validation 自訂供應商驗證 ====================

    @Test
    fun `Custom isValid returns true when baseUrl is not blank`() {
        // Custom 只需要 baseUrl 不為空（apiKey 為選填）
        val setting = ProviderSetting.Custom(baseUrl = "http://localhost:11434/v1/")
        assertThat(setting.isValid()).isTrue()
    }

    @Test
    fun `Custom isValid returns true even when apiKey is blank`() {
        val setting = ProviderSetting.Custom(apiKey = "", baseUrl = "http://localhost:11434/v1/")
        assertThat(setting.isValid()).isTrue()
    }

    @Test
    fun `Custom isValid returns false when baseUrl is blank`() {
        val setting = ProviderSetting.Custom(baseUrl = "")
        assertThat(setting.isValid()).isFalse()
    }

    @Test
    fun `Custom providerApiKey returns null when apiKey is blank`() {
        // Custom 的 providerApiKey 在 apiKey 為空時應回傳 null
        val setting = ProviderSetting.Custom(apiKey = "")
        assertThat(setting.providerApiKey).isNull()
    }

    @Test
    fun `Custom providerApiKey returns key when apiKey is not blank`() {
        val setting = ProviderSetting.Custom(apiKey = "my-key")
        assertThat(setting.providerApiKey).isEqualTo("my-key")
    }

    // ==================== Default Values Tests 預設值測試 ====================

    @Test
    fun `Gemini has correct default values`() {
        val setting = ProviderSetting.Gemini()
        assertThat(setting.id).isEqualTo("gemini")
        assertThat(setting.displayName).isEqualTo("Google Gemini")
        assertThat(setting.enabled).isTrue()
        assertThat(setting.modelId).isEqualTo("gemini-3.1-flash")
        assertThat(setting.baseUrl).contains("generativelanguage.googleapis.com")
    }

    @Test
    fun `OpenAI has correct default values`() {
        val setting = ProviderSetting.OpenAI()
        assertThat(setting.id).isEqualTo("openai")
        assertThat(setting.displayName).isEqualTo("OpenAI")
        assertThat(setting.baseUrl).contains("api.openai.com")
        assertThat(setting.organizationId).isEmpty()
    }

    @Test
    fun `Baidu has correct default values`() {
        val setting = ProviderSetting.Baidu()
        assertThat(setting.id).isEqualTo("baidu")
        assertThat(setting.displayName).isEqualTo("Baidu Ernie")
        assertThat(setting.secretKey).isEmpty()
        assertThat(setting.baseUrl).contains("baidubce.com")
    }

    @Test
    fun `Custom has correct default values`() {
        val setting = ProviderSetting.Custom()
        assertThat(setting.id).isEqualTo("custom")
        assertThat(setting.displayName).isEqualTo("Custom Service")
        assertThat(setting.baseUrl).contains("localhost:11434")
        assertThat(setting.customName).isEmpty()
    }

    // ==================== providerApiKey / providerBaseUrl Tests ====================

    @Test
    fun `Gemini providerApiKey matches apiKey`() {
        val setting = ProviderSetting.Gemini(apiKey = "gem-key")
        assertThat(setting.providerApiKey).isEqualTo("gem-key")
    }

    @Test
    fun `Anthropic providerBaseUrl matches baseUrl`() {
        val setting = ProviderSetting.Anthropic()
        assertThat(setting.providerBaseUrl).isEqualTo(setting.baseUrl)
    }

    // ==================== fromId() Tests ====================

    @Test
    fun `fromId returns correct provider for each known ID`() {
        // fromId 應為所有已知 ID 回傳正確的 ProviderSetting 子類別
        assertThat(ProviderSetting.fromId("gemini")).isInstanceOf(ProviderSetting.Gemini::class.java)
        assertThat(ProviderSetting.fromId("openai")).isInstanceOf(ProviderSetting.OpenAI::class.java)
        assertThat(ProviderSetting.fromId("anthropic")).isInstanceOf(ProviderSetting.Anthropic::class.java)
        assertThat(ProviderSetting.fromId("deepseek")).isInstanceOf(ProviderSetting.DeepSeek::class.java)
        assertThat(ProviderSetting.fromId("groq")).isInstanceOf(ProviderSetting.Groq::class.java)
        assertThat(ProviderSetting.fromId("xai")).isInstanceOf(ProviderSetting.XAI::class.java)
        assertThat(ProviderSetting.fromId("alibaba")).isInstanceOf(ProviderSetting.Alibaba::class.java)
        assertThat(ProviderSetting.fromId("zhipu")).isInstanceOf(ProviderSetting.Zhipu::class.java)
        assertThat(ProviderSetting.fromId("baidu")).isInstanceOf(ProviderSetting.Baidu::class.java)
        assertThat(ProviderSetting.fromId("perplexity")).isInstanceOf(ProviderSetting.Perplexity::class.java)
        assertThat(ProviderSetting.fromId("moonshot")).isInstanceOf(ProviderSetting.Moonshot::class.java)
        assertThat(ProviderSetting.fromId("anythingllm")).isInstanceOf(ProviderSetting.AnythingLLM::class.java)
        assertThat(ProviderSetting.fromId("custom")).isInstanceOf(ProviderSetting.Custom::class.java)
    }

    @Test
    fun `fromId returns null for unknown ID`() {
        // 未知的 ID 應回傳 null
        assertThat(ProviderSetting.fromId("unknown")).isNull()
        assertThat(ProviderSetting.fromId("")).isNull()
        assertThat(ProviderSetting.fromId("GEMINI")).isNull() // case-sensitive
    }

    // ==================== getDefaultProviders() Tests ====================

    @Test
    fun `getDefaultProviders returns exactly 14 providers`() {
        // 預設供應商清單應包含 14 個供應商（新增 AnythingLLM）
        val providers = ProviderSetting.getDefaultProviders()
        assertThat(providers).hasSize(14)
    }

    @Test
    fun `getDefaultProviders returns all unique IDs`() {
        // 所有預設供應商的 ID 應唯一
        val providers = ProviderSetting.getDefaultProviders()
        val ids = providers.map { it.id }
        assertThat(ids).containsNoDuplicates()
    }

    @Test
    fun `getDefaultProviders contains all expected provider types`() {
        val providers = ProviderSetting.getDefaultProviders()
        val types = providers.map { it::class }
        assertThat(types).containsExactly(
            ProviderSetting.Gemini::class,
            ProviderSetting.OpenAI::class,
            ProviderSetting.Anthropic::class,
            ProviderSetting.DeepSeek::class,
            ProviderSetting.Groq::class,
            ProviderSetting.XAI::class,
            ProviderSetting.Alibaba::class,
            ProviderSetting.Zhipu::class,
            ProviderSetting.Baidu::class,
            ProviderSetting.Perplexity::class,
            ProviderSetting.Moonshot::class,
            ProviderSetting.Mistral::class,
            ProviderSetting.AnythingLLM::class,
            ProviderSetting.Custom::class
        ).inOrder()
    }

    @Test
    fun `all default providers have non-blank displayName`() {
        ProviderSetting.getDefaultProviders().forEach { provider ->
            assertThat(provider.displayName).isNotEmpty()
        }
    }

    @Test
    fun `all default providers are enabled by default`() {
        // 預設供應商應全部啟用
        ProviderSetting.getDefaultProviders().forEach { provider ->
            assertThat(provider.enabled).isTrue()
        }
    }

    @Test
    fun `all default providers have non-blank baseUrl`() {
        ProviderSetting.getDefaultProviders()
            .filterNot { it is ProviderSetting.AnythingLLM }
            .forEach { provider ->
                assertThat(provider.providerBaseUrl).isNotEmpty()
            }
    }
}
