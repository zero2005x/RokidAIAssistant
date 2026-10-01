package com.example.rokidphone.ai.catalog

import com.example.rokidphone.data.AiProvider
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Parsing tests for every provider's Models-list endpoint.
 */
@RunWith(RobolectricTestRunner::class)
class ModelCatalogParsersTest {

    // ==================== OpenAI ====================

    @Test
    fun `OpenAI models parsing filters non-chat models`() {
        val body = """
            {"data": [
              {"id": "gpt-5.6", "object": "model"},
              {"id": "gpt-4o", "object": "model"},
              {"id": "whisper-1", "object": "model"},
              {"id": "tts-1", "object": "model"},
              {"id": "dall-e-3", "object": "model"},
              {"id": "text-embedding-3-large", "object": "model"}
            ]}
        """.trimIndent()

        val models = ModelCatalogParsers.parse(AiProvider.OPENAI, CatalogFormat.OPENAI_STYLE, body)

        assertThat(models.map { it.id }).containsExactly("gpt-5.6", "gpt-4o")
        assertThat(models.all { it.source == CatalogSource.LIVE }).isTrue()
        // gpt-5.6 is in the fallback capability map → vision resolved
        assertThat(models.first { it.id == "gpt-5.6" }.capabilities.imageInput).isTrue()
    }

    // ==================== Gemini ====================

    @Test
    fun `Gemini models parsing uses supportedGenerationMethods and excludes tts and image models`() {
        val body = """
            {"models": [
              {
                "name": "models/gemini-3.6-flash",
                "displayName": "Gemini 3.6 Flash",
                "supportedGenerationMethods": ["generateContent", "countTokens"],
                "inputTokenLimit": 1048576,
                "outputTokenLimit": 65536
              },
              {
                "name": "models/gemini-2.5-flash-preview-tts",
                "displayName": "Gemini TTS",
                "supportedGenerationMethods": ["generateContent"]
              },
              {
                "name": "models/imagen-4.0-generate-001",
                "displayName": "Imagen 4",
                "supportedGenerationMethods": ["predict"]
              },
              {
                "name": "models/gemini-embedding-001",
                "displayName": "Gemini Embedding",
                "supportedGenerationMethods": ["embedContent"]
              }
            ]}
        """.trimIndent()

        val models = ModelCatalogParsers.parse(AiProvider.GEMINI, CatalogFormat.GEMINI, body)

        assertThat(models.map { it.id }).containsExactly("gemini-3.6-flash")
        val flash = models.first()
        assertThat(flash.displayName).isEqualTo("Gemini 3.6 Flash")
        assertThat(flash.capabilities.maxContextTokens).isEqualTo(1048576L)
        assertThat(flash.capabilities.maxOutputTokens).isEqualTo(65536L)
        assertThat(flash.capabilities.imageInput).isTrue()
    }

    @Test
    fun `Gemini Live catalog keeps only bidiGenerateContent models`() {
        val body = """
            {"models": [
              {
                "name": "models/gemini-3.6-flash",
                "supportedGenerationMethods": ["generateContent"]
              },
              {
                "name": "models/gemini-2.5-flash-live",
                "supportedGenerationMethods": ["bidiGenerateContent"]
              }
            ]}
        """.trimIndent()

        val liveModels = ModelCatalogParsers.parse(AiProvider.GEMINI_LIVE, CatalogFormat.GEMINI, body)
        val chatModels = ModelCatalogParsers.parse(AiProvider.GEMINI, CatalogFormat.GEMINI, body)

        assertThat(liveModels.map { it.id }).containsExactly("gemini-2.5-flash-live")
        assertThat(liveModels.first().capabilities.realtime).isTrue()
        assertThat(chatModels.map { it.id }).containsExactly("gemini-3.6-flash")
    }

    @Test
    fun `Gemini models parsing trims descriptions and marks preview models`() {
        val longDescription = "d".repeat(200)
        val body = """
            {"models": [
              {
                "name": "models/gemini-2.5-pro-preview",
                "supportedGenerationMethods": ["generateContent"],
                "description": "$longDescription"
              }
            ]}
        """.trimIndent()

        val model = ModelCatalogParsers.parse(AiProvider.GEMINI, CatalogFormat.GEMINI, body).single()

        assertThat(model.status).isEqualTo(ModelStatus.PREVIEW)
        assertThat(model.description).hasLength(160)
    }

    // ==================== Anthropic ====================

    @Test
    fun `Anthropic models parsing reads capabilities metadata when present`() {
        val body = """
            {"data": [
              {
                "type": "model",
                "id": "claude-sonnet-5",
                "display_name": "Claude Sonnet 5",
                "created_at": "2026-01-01T00:00:00Z",
                "capabilities": {
                  "image_input": {"supported": true},
                  "extended_thinking": {"supported": true}
                }
              },
              {
                "type": "model",
                "id": "claude-unknown-future",
                "display_name": "Claude Future",
                "created_at": "2027-01-01T00:00:00Z"
              }
            ], "has_more": false}
        """.trimIndent()

        val models = ModelCatalogParsers.parse(AiProvider.ANTHROPIC, CatalogFormat.ANTHROPIC, body)

        assertThat(models).hasSize(2)
        val sonnet = models.first { it.id == "claude-sonnet-5" }
        assertThat(sonnet.displayName).isEqualTo("Claude Sonnet 5")
        assertThat(sonnet.capabilities.imageInput).isTrue()
        assertThat(sonnet.capabilities.reasoning).isTrue()
        // Unknown model falls back to conservative provider default.
        val future = models.first { it.id == "claude-unknown-future" }
        assertThat(future.capabilities.imageInput).isTrue() // Anthropic default vision
        assertThat(future.capabilities.textInput).isTrue()
    }

    // ==================== DeepSeek / Groq / xAI (OpenAI-style) ====================

    @Test
    fun `DeepSeek models parsing resolves reasoning capability from fallback map`() {
        val body = """{"data": [{"id": "deepseek-v4-flash"}, {"id": "deepseek-v4-pro"}]}"""

        val models = ModelCatalogParsers.parse(AiProvider.DEEPSEEK, CatalogFormat.OPENAI_STYLE, body)

        assertThat(models.map { it.id }).containsExactly("deepseek-v4-flash", "deepseek-v4-pro")
        assertThat(models.first { it.id == "deepseek-v4-pro" }.capabilities.reasoning).isTrue()
        assertThat(models.first { it.id == "deepseek-v4-flash" }.capabilities.reasoning).isFalse()
        // Since 2026-09-10 the retired Flash ID routes to vision-capable V4.1 Flash.
        // https://api-docs.deepseek.com/updates/
        assertThat(models.first { it.id == "deepseek-v4-flash" }.capabilities.imageInput).isTrue()
        assertThat(models.first { it.id == "deepseek-v4-pro" }.capabilities.imageInput).isFalse()
    }

    @Test
    fun `Groq models parsing excludes whisper transcription models from chat list`() {
        val body = """
            {"data": [
              {"id": "llama-3.3-70b-versatile"},
              {"id": "whisper-large-v3-turbo"},
              {"id": "whisper-large-v3"},
              {"id": "playai-tts"}
            ]}
        """.trimIndent()

        val models = ModelCatalogParsers.parse(AiProvider.GROQ, CatalogFormat.OPENAI_STYLE, body)

        assertThat(models.map { it.id }).containsExactly("llama-3.3-70b-versatile")
    }

    @Test
    fun `xAI models parsing excludes image generation and keeps vision flag from map`() {
        val body = """
            {"data": [
              {"id": "grok-4.5"},
              {"id": "grok-2-image-1212"},
              {"id": "grok-imagine-video"}
            ]}
        """.trimIndent()

        val models = ModelCatalogParsers.parse(AiProvider.XAI, CatalogFormat.OPENAI_STYLE, body)

        assertThat(models.map { it.id }).containsExactly("grok-4.5")
        assertThat(models.first().capabilities.imageInput).isTrue()
    }

    // ==================== Baidu Qianfan v2 ====================

    @Test
    fun `Baidu v2 models parsing resolves VL capability from fallback map`() {
        val body = """
            {"data": [
              {"id": "ernie-5.1"},
              {"id": "ernie-4.5-turbo-vl"}
            ]}
        """.trimIndent()

        val models = ModelCatalogParsers.parse(AiProvider.BAIDU, CatalogFormat.BAIDU_QIANFAN_V2, body)

        assertThat(models.map { it.id }).containsExactly("ernie-5.1", "ernie-4.5-turbo-vl")
        assertThat(models.first { it.id == "ernie-5.1" }.capabilities.imageInput).isFalse()
        assertThat(models.first { it.id == "ernie-4.5-turbo-vl" }.capabilities.imageInput).isTrue()
    }

    // ==================== Mistral ====================

    @Test
    fun `Mistral models parsing reads capability metadata`() {
        val body = """
            {"data": [
              {
                "id": "mistral-medium-3-5",
                "object": "model",
                "capabilities": {
                  "completion_chat": true,
                  "function_calling": true,
                  "vision": true,
                  "completion_fim": false
                },
                "max_context_length": 262144
              },
              {
                "id": "voxtral-mini-transcribe",
                "object": "model",
                "capabilities": {
                  "completion_chat": false,
                  "transcription": true
                }
              }
            ]}
        """.trimIndent()

        val models = ModelCatalogParsers.parse(AiProvider.MISTRAL, CatalogFormat.MISTRAL, body)

        // STT-only model must not appear in the chat catalog.
        assertThat(models.map { it.id }).containsExactly("mistral-medium-3-5")
        val model = models.first()
        assertThat(model.capabilities.imageInput).isTrue()
        assertThat(model.capabilities.toolCalling).isTrue()
        assertThat(model.capabilities.maxContextTokens).isEqualTo(262144L)
    }

    @Test
    fun `parse returns empty list for malformed payloads`() {
        assertThat(ModelCatalogParsers.parse(AiProvider.OPENAI, CatalogFormat.OPENAI_STYLE, "<html>bad gateway"))
            .isEmpty()
    }

    // ==================== Perplexity ====================

    @Test
    fun `Perplexity Sonar catalog is fallback-driven and separate from Agent API models`() {
        // Sonar has no usable models endpoint — descriptor says so.
        val descriptor = ProviderRegistry.descriptorFor(AiProvider.PERPLEXITY)
        assertThat(descriptor.hasRemoteCatalog).isFalse()

        // Fallback covers the documented Sonar chat models.
        val fallbackIds = FallbackModelCatalog.modelsFor(AiProvider.PERPLEXITY).map { it.id }
        assertThat(fallbackIds).containsAtLeast(
            "sonar", "sonar-pro", "sonar-reasoning-pro", "sonar-deep-research"
        )
    }

    // ==================== On-device Gemma (local inference) ====================

    @Test
    fun `LOCAL_GEMMA descriptor is keyless local-inference with no remote catalog`() {
        val descriptor = ProviderRegistry.descriptorFor(AiProvider.LOCAL_GEMMA)
        assertThat(descriptor.protocol).isEqualTo(ApiProtocol.LOCAL_INFERENCE)
        assertThat(descriptor.catalogFormat).isEqualTo(CatalogFormat.NONE)
        assertThat(descriptor.requiresApiKey).isFalse()
        assertThat(descriptor.hasRemoteCatalog).isFalse()

        val fallbackIds = FallbackModelCatalog.modelsFor(AiProvider.LOCAL_GEMMA).map { it.id }
        assertThat(fallbackIds).containsAtLeast("gemma-3n-E2B-it", "gemma-3n-E4B-it")
    }

    // ==================== Fallback catalog invariants ====================

    @Test
    fun `fallback model IDs are unique per provider`() {
        for (provider in AiProvider.entries) {
            val ids = FallbackModelCatalog.modelsFor(provider).map { it.id }
            assertThat(ids).containsNoDuplicates()
        }
    }

    @Test
    fun `default model is never preview or deprecated when a stable model exists`() {
        for (provider in AiProvider.entries) {
            val models = FallbackModelCatalog.modelsFor(provider)
            val hasStable = models.any { it.status == ModelStatus.STABLE }
            val defaultId = FallbackModelCatalog.defaultModelFor(provider)
            val default = models.first { it.id == defaultId }
            if (hasStable) {
                assertWithMessage(provider.name).that(default.status).isEqualTo(ModelStatus.STABLE)
            } else {
                // Gemini Live currently only ships preview models; it is the
                // documented exception and must not gain a fake "stable" default.
                assertWithMessage(provider.name).that(provider).isEqualTo(AiProvider.GEMINI_LIVE)
            }
        }
    }

    @Test
    fun `every protocol has a registered descriptor`() {
        assertThat(ProviderRegistry.isComplete()).isTrue()
        for (provider in AiProvider.entries) {
            val descriptor = ProviderRegistry.descriptorFor(provider)
            assertThat(descriptor.defaultBaseUrl).isNotEmpty()
        }
    }
}
