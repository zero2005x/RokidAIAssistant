package io.github.zero2005x.glassesaicompanion.ai.catalog

import android.util.Log
import io.github.zero2005x.glassesaicompanion.data.AiProvider
import org.json.JSONObject

/**
 * Parsers for each provider's Models-list endpoint.
 *
 * Inclusion in the chat catalog is metadata-driven (e.g. Gemini
 * `supportedGenerationMethods`, Mistral `capabilities.completion_chat`).
 * A small documented exclusion list removes known non-chat model families
 * (TTS, image generation, embeddings, transcription, safety guard models)
 * whose endpoints only expose IDs; this is a filter, not a capability claim.
 */
object ModelCatalogParsers {

    /** ID fragments of known non-chat model families on OpenAI-style model lists. */
    private val openAiStyleExclusions = listOf(
        // NOTE: "text-embedding" was removed — it is already subsumed by "embedding".
        "whisper", "tts", "dall-e", "embedding", "moderation",
        "davinci", "babbage", "realtime", "transcribe", "audio", "image",
        "playai", "guard", "safety", "rerank", "veo", "imagen", "imagine", "voice"
    )

    /** ID fragments of known non-chat model families on the Gemini model list. */
    private val geminiExclusions = listOf(
        "embedding", "aqa", "tts", "imagen", "veo", "native-audio", "image-generation"
    )

    fun parse(
        provider: AiProvider,
        format: CatalogFormat,
        body: String
    ): List<ModelInfo> = runCatching {
        when (format) {
            CatalogFormat.OPENAI_STYLE, CatalogFormat.BAIDU_QIANFAN_V2 ->
                parseOpenAiStyle(provider, body)
            CatalogFormat.GEMINI -> parseGemini(provider, body)
            CatalogFormat.ANTHROPIC -> parseAnthropic(provider, body)
            CatalogFormat.MISTRAL -> parseMistral(provider, body)
            CatalogFormat.NONE -> emptyList()
        }
    }.getOrElse {
        // Malformed/truncated/non-JSON payloads (HTML error pages, proxies)
        // must not crash the catalog refresh; treat them as "no models".
        Log.w(TAG, "Failed to parse model list for $provider", it)
        emptyList()
    }

    // ==================== OpenAI-style ====================

    private fun parseOpenAiStyle(provider: AiProvider, body: String): List<ModelInfo> {
        val data = JSONObject(body).optJSONArray("data") ?: return emptyList()
        val result = mutableListOf<ModelInfo>()
        for (i in 0 until data.length()) {
            val obj = data.optJSONObject(i) ?: continue
            val id = obj.optString("id").takeIf { it.isNotBlank() } ?: continue
            if (isExcludedNonChat(id, openAiStyleExclusions)) continue
            result += ModelInfo(
                id = id,
                displayName = id,
                provider = provider,
                capabilities = ModelCapabilityResolver.resolve(provider, id),
                status = ModelStatus.STABLE,
                source = CatalogSource.LIVE
            )
        }
        return result
    }

    // ==================== Gemini ====================

    private fun parseGemini(provider: AiProvider, body: String): List<ModelInfo> {
        val models = JSONObject(body).optJSONArray("models") ?: return emptyList()
        val wantLive = provider == AiProvider.GEMINI_LIVE
        val result = mutableListOf<ModelInfo>()
        for (i in 0 until models.length()) {
            val obj = models.optJSONObject(i) ?: continue
            // "name" is a resource path such as "models/<id>" or
            // "tunedModels/<id>"; only the last segment is the request ID.
            val rawName = obj.optString("name")
            val id = rawName.substringAfterLast('/').takeIf { it.isNotBlank() } ?: continue
            if (isExcludedNonChat(id, geminiExclusions)) continue

            val methods = mutableListOf<String>()
            obj.optJSONArray("supportedGenerationMethods")?.let { arr ->
                for (j in 0 until arr.length()) methods += arr.optString(j)
            }
            val isChatModel = "generateContent" in methods
            val isLiveModel = "bidiGenerateContent" in methods
            // Chat catalog excludes Live-only models and vice versa.
            if (wantLive && !isLiveModel) continue
            if (!wantLive && !isChatModel) continue

            val inputLimit = obj.optLong("inputTokenLimit").takeIf { it > 0 }
            val outputLimit = obj.optLong("outputTokenLimit").takeIf { it > 0 }
            val fallbackCaps = ModelCapabilityResolver.resolve(provider, id)
            val caps = fallbackCaps.copy(
                realtime = wantLive,
                maxContextTokens = inputLimit ?: fallbackCaps.maxContextTokens,
                maxOutputTokens = outputLimit ?: fallbackCaps.maxOutputTokens
            )
            result += ModelInfo(
                id = id,
                displayName = obj.optString("displayName").ifBlank { id },
                provider = provider,
                capabilities = caps,
                status = if (id.contains("preview") || id.contains("-exp")) ModelStatus.PREVIEW else ModelStatus.STABLE,
                source = CatalogSource.LIVE,
                description = obj.optString("description").takeIf { it.isNotBlank() }?.take(160) ?: ""
            )
        }
        return result
    }

    // ==================== Anthropic ====================

    private fun parseAnthropic(provider: AiProvider, body: String): List<ModelInfo> {
        val data = JSONObject(body).optJSONArray("data") ?: return emptyList()
        val result = mutableListOf<ModelInfo>()
        for (i in 0 until data.length()) {
            val obj = data.optJSONObject(i) ?: continue
            val id = obj.optString("id").takeIf { it.isNotBlank() } ?: continue
            // Prefer live capability metadata when Anthropic returns it. Only
            // fields actually present are overridden; absent keys keep the
            // fallback/provider-default values (optBoolean would report false).
            val liveCaps = obj.optJSONObject("capabilities")?.let { caps ->
                val base = ModelCapabilityResolver.resolve(provider, id)
                base.copy(
                    imageInput = if (caps.has("image_input")) {
                        caps.optJSONObject("image_input")?.optBoolean("supported")
                            ?: caps.optBoolean("image_input")
                    } else base.imageInput,
                    toolCalling = if (caps.has("tool_use")) {
                        caps.optJSONObject("tool_use")?.optBoolean("supported")
                            ?: caps.optBoolean("tool_use")
                    } else base.toolCalling,
                    reasoning = if (caps.has("extended_thinking")) {
                        caps.optJSONObject("extended_thinking")?.optBoolean("supported")
                            ?: caps.optBoolean("extended_thinking")
                    } else base.reasoning,
                    maxContextTokens = obj.optLong("max_input_tokens").takeIf { it > 0 }
                        ?: base.maxContextTokens
                )
            }
            result += ModelInfo(
                id = id,
                displayName = obj.optString("display_name").ifBlank { id },
                provider = provider,
                capabilities = ModelCapabilityResolver.resolve(provider, id, liveCaps),
                status = ModelStatus.STABLE,
                source = CatalogSource.LIVE
            )
        }
        return result
    }

    // ==================== Mistral ====================

    private fun parseMistral(provider: AiProvider, body: String): List<ModelInfo> {
        val data = JSONObject(body).optJSONArray("data") ?: return emptyList()
        val result = mutableListOf<ModelInfo>()
        for (i in 0 until data.length()) {
            val obj = data.optJSONObject(i) ?: continue
            val id = obj.optString("id").takeIf { it.isNotBlank() } ?: continue
            val capsObj = obj.optJSONObject("capabilities")
            // Only chat-completion models belong in the chat catalog. STT/TTS/OCR
            // capabilities are independent and never imply chat support.
            if (capsObj != null && !capsObj.optBoolean("completion_chat", true)) continue

            // Only fields actually present are overridden; absent keys keep the
            // fallback/provider-default values (optBoolean would report false).
            val liveCaps = capsObj?.let { caps ->
                val base = ModelCapabilityResolver.resolve(provider, id)
                base.copy(
                    imageInput = if (caps.has("vision")) caps.optBoolean("vision") else base.imageInput,
                    audioInput = if (caps.has("audio") || caps.has("audio_input")) {
                        caps.optJSONObject("audio")?.optBoolean("input") ?: caps.optBoolean("audio_input")
                    } else base.audioInput,
                    toolCalling = if (caps.has("function_calling")) caps.optBoolean("function_calling") else base.toolCalling,
                    structuredOutput = if (caps.has("structured_output")) caps.optBoolean("structured_output") else base.structuredOutput,
                    reasoning = if (caps.has("reasoning")) caps.optBoolean("reasoning") else base.reasoning,
                    maxContextTokens = obj.optLong("max_context_length").takeIf { it > 0 }
                        ?: base.maxContextTokens
                )
            }
            result += ModelInfo(
                id = id,
                displayName = obj.optString("name").ifBlank { id },
                provider = provider,
                capabilities = ModelCapabilityResolver.resolve(provider, id, liveCaps),
                status = ModelStatus.STABLE,
                source = CatalogSource.LIVE,
                description = obj.optString("description").takeIf { it.isNotBlank() }?.take(160) ?: ""
            )
        }
        return result
    }

    private fun isExcludedNonChat(id: String, exclusions: List<String>): Boolean {
        val lower = id.lowercase()
        return exclusions.any { lower.contains(it) }
    }

    private const val TAG = "ModelCatalogParsers"
}
