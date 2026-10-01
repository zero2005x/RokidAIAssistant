package com.example.rokidphone.service.ai

import com.example.rokidphone.service.SpeechResult
import com.example.rokidphone.testutil.MockWebServerRule
import com.example.rokidphone.data.AiProvider
import com.example.rokidphone.data.ApiSettings
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import okhttp3.Headers.Companion.headersOf
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AiServiceFactoryTest {

    @get:Rule
    val mockServer = MockWebServerRule()

    private fun fullyConfiguredSettings(provider: AiProvider): ApiSettings {
        return ApiSettings(
            aiProvider = provider,
            aiModelId = "test-model",
            geminiApiKey = "gemini-key",
            openaiApiKey = "openai-key",
            anthropicApiKey = "anthropic-key",
            deepseekApiKey = "deepseek-key",
            groqApiKey = "groq-key",
            xaiApiKey = "xai-key",
            alibabaApiKey = "alibaba-key",
            zhipuApiKey = "zhipu-key",
            baiduApiKey = "baidu-key",
            baiduSecretKey = "baidu-secret",
            perplexityApiKey = "perplexity-key",
            moonshotApiKey = "moonshot-key",
            mistralApiKey = "mistral-key",
            anythingllmServerUrl = mockServer.baseUrlNoSlash,
            anythingllmApiKey = "anythingllm-key",
            anythingllmWorkspaceSlug = "docs",
            customApiKey = "custom-key",
            customBaseUrl = "http://localhost:11434/v1/",
            customModelName = "custom-model",
            systemPrompt = "test prompt"
        )
    }

    @Test
    fun `createService returns GeminiService for GEMINI provider`() {
        // 測試：GEMINI 應建立 GeminiService
        val service = AiServiceFactory.createService(fullyConfiguredSettings(AiProvider.GEMINI))
        assertThat(service).isInstanceOf(GeminiService::class.java)
        assertThat(service.provider).isEqualTo(AiProvider.GEMINI)
    }

    @Test
    fun `createService returns AnthropicService for ANTHROPIC provider`() {
        // 測試：ANTHROPIC 應建立 AnthropicService
        val service = AiServiceFactory.createService(fullyConfiguredSettings(AiProvider.ANTHROPIC))
        assertThat(service).isInstanceOf(AnthropicService::class.java)
        assertThat(service.provider).isEqualTo(AiProvider.ANTHROPIC)
    }

    @Test
    fun `createService returns BaiduService for BAIDU provider`() {
        // 測試：BAIDU 應建立 BaiduService
        val service = AiServiceFactory.createService(fullyConfiguredSettings(AiProvider.BAIDU))
        assertThat(service).isInstanceOf(BaiduService::class.java)
        assertThat(service.provider).isEqualTo(AiProvider.BAIDU)
    }

    @Test
    fun `createService returns OpenAiCompatibleService for OpenAI-compatible providers`() {
        // 測試：OpenAI 相容供應商應建立 OpenAiCompatibleService
        val providers = listOf(
            AiProvider.OPENAI,
            AiProvider.DEEPSEEK,
            AiProvider.GROQ,
            AiProvider.XAI,
            AiProvider.ALIBABA,
            AiProvider.ZHIPU,
            AiProvider.PERPLEXITY,
            AiProvider.MOONSHOT,
            AiProvider.MISTRAL,
            AiProvider.CUSTOM
        )

        for (provider in providers) {
            val service = AiServiceFactory.createService(fullyConfiguredSettings(provider))
            assertThat(service).isInstanceOf(OpenAiCompatibleService::class.java)
            assertThat(service.provider).isEqualTo(provider)
        }
    }

    @Test
    fun `createService returns DeepSeekService for DEEPSEEK provider`() {
        // v0.12.1: DEEPSEEK routes through the dedicated DeepSeekService subclass.
        val service = AiServiceFactory.createService(fullyConfiguredSettings(AiProvider.DEEPSEEK))
        assertThat(service).isInstanceOf(DeepSeekService::class.java)
        assertThat(service.provider).isEqualTo(AiProvider.DEEPSEEK)
    }

    @Test
    fun `createService returns AnythingLLM adapter for ANYTHINGLLM provider`() = runTest {
        mockServer.server.enqueue(
            MockResponse(
                code = 200,
                body = """{"textResponse":"Docs answer","sources":[]}""",
                headers = headersOf("Content-Type", "application/json")
            )
        )

        val service = AiServiceFactory.createService(fullyConfiguredSettings(AiProvider.ANYTHINGLLM))

        assertThat(service.provider).isEqualTo(AiProvider.ANYTHINGLLM)
        assertThat(service.transcribe(byteArrayOf(), "en")).isInstanceOf(SpeechResult.Error::class.java)
        assertThat(service.analyzeImage(byteArrayOf(), "describe")).contains("does not support image analysis")
        assertThat(service.chat("What changed?")).isEqualTo("Docs answer")

        val request = mockServer.server.takeRequest()
        assertThat(request.path).isEqualTo("/api/v1/workspace/docs/chat")
        service.clearHistory()
    }

    @Test
    fun `createService returns QianfanV2Service for BAIDU when qianfan credentials are configured`() {
        val settings = fullyConfiguredSettings(AiProvider.BAIDU).copy(
            baiduApiKey = "",
            baiduSecretKey = "",
            baiduQianfanApiKey = "qianfan-key"
        )

        val service = AiServiceFactory.createService(settings)

        assertThat(service).isInstanceOf(QianfanV2Service::class.java)
        assertThat(service.provider).isEqualTo(AiProvider.BAIDU)
    }

    @Test
    fun `createTestService returns DeepSeekService for DEEPSEEK provider`() {
        val service = AiServiceFactory.createTestService(fullyConfiguredSettings(AiProvider.DEEPSEEK))
        assertThat(service).isInstanceOf(DeepSeekService::class.java)
    }

    @Test
    fun `createTestService returns null for non OpenAI-compatible providers`() {
        val providers = listOf(
            AiProvider.GEMINI,
            AiProvider.ANTHROPIC,
            AiProvider.BAIDU,
            AiProvider.GEMINI_LIVE,
            AiProvider.ANYTHINGLLM
        )

        for (provider in providers) {
            assertThat(AiServiceFactory.createTestService(fullyConfiguredSettings(provider))).isNull()
        }
    }

    @Test
    fun `every AiProvider enum value has a factory case`() {
        // 測試：所有 AiProvider 都能建立對應服務
        for (provider in AiProvider.entries) {
            val service = AiServiceFactory.createService(fullyConfiguredSettings(provider))
            assertThat(service).isNotNull()
        }
    }

    @Test
    fun `createTestService returns non-null for all compatible providers`() {
        // 測試：OpenAI 相容 provider 的 createTestService 應可建立服務
        val providers = listOf(
            AiProvider.OPENAI,
            AiProvider.DEEPSEEK,
            AiProvider.GROQ,
            AiProvider.XAI,
            AiProvider.ALIBABA,
            AiProvider.ZHIPU,
            AiProvider.PERPLEXITY,
            AiProvider.MOONSHOT,
            AiProvider.MISTRAL,
            AiProvider.CUSTOM
        )

        for (provider in providers) {
            val service = AiServiceFactory.createTestService(fullyConfiguredSettings(provider))
            assertThat(service).isNotNull()
        }
    }

    @Test
    fun `createService returns LocalGemmaService for LOCAL_GEMMA provider`() {
        // 測試：LOCAL_GEMMA 應建立裝置端 LocalGemmaService（不需 API key）
        val settings = fullyConfiguredSettings(AiProvider.LOCAL_GEMMA)
            .copy(aiModelId = "gemma-3n-E2B-it")
        val service = AiServiceFactory.createService(settings)
        assertThat(service).isInstanceOf(LocalGemmaService::class.java)
        assertThat(service.provider).isEqualTo(AiProvider.LOCAL_GEMMA)
    }

    @Test
    fun `createTestService returns null for on-device LOCAL_GEMMA provider`() {
        // 測試：裝置端 provider 沒有可測試的雲端連線端點
        val service = AiServiceFactory.createTestService(fullyConfiguredSettings(AiProvider.LOCAL_GEMMA))
        assertThat(service).isNull()
    }

    @Test
    fun `createBaiduTestService only returns legacy Baidu test services`() {
        val legacy = fullyConfiguredSettings(AiProvider.BAIDU).copy(
            baiduUseLegacyAuth = true,
            baiduQianfanApiKey = ""
        )
        val qianfan = fullyConfiguredSettings(AiProvider.BAIDU).copy(
            baiduApiKey = "",
            baiduSecretKey = "",
            baiduQianfanApiKey = "qianfan-key"
        )

        assertThat(AiServiceFactory.createBaiduTestService(legacy))
            .isInstanceOf(BaiduService::class.java)
        assertThat(AiServiceFactory.createBaiduTestService(qianfan)).isNull()
        assertThat(AiServiceFactory.createBaiduTestService(fullyConfiguredSettings(AiProvider.OPENAI))).isNull()
    }

    @Test
    fun `createSpeechService only supports Gemini OpenAI and Groq with non blank keys`() {
        assertThat(AiServiceFactory.createSpeechService(AiProvider.GEMINI, "")).isNull()
        assertThat(AiServiceFactory.createSpeechService(AiProvider.ANTHROPIC, "k")).isNull()

        assertThat(AiServiceFactory.createSpeechService(AiProvider.GEMINI, "g"))
            .isInstanceOf(GeminiService::class.java)
        assertThat(AiServiceFactory.createSpeechService(AiProvider.OPENAI, "o"))
            .isInstanceOf(OpenAiCompatibleService::class.java)
        assertThat(AiServiceFactory.createSpeechService(AiProvider.GROQ, "q"))
            .isInstanceOf(OpenAiCompatibleService::class.java)
    }
}
