package com.example.rokidphone.service.ai

import com.example.rokidphone.data.AiProvider
import com.example.rokidphone.data.ApiSettings
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class AiServiceFactoryTest {

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
            AiProvider.CUSTOM
        )

        for (provider in providers) {
            val service = AiServiceFactory.createService(fullyConfiguredSettings(provider))
            assertThat(service).isInstanceOf(OpenAiCompatibleService::class.java)
            assertThat(service.provider).isEqualTo(provider)
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
            AiProvider.CUSTOM
        )

        for (provider in providers) {
            val service = AiServiceFactory.createTestService(fullyConfiguredSettings(provider))
            assertThat(service).isNotNull()
        }
    }
}
