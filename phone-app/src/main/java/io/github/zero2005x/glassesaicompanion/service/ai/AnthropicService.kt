package io.github.zero2005x.glassesaicompanion.service.ai

import android.util.Base64
import android.util.Log
import io.github.zero2005x.glassesaicompanion.BuildConfig
import io.github.zero2005x.glassesaicompanion.ai.catalog.ProviderApiException
import io.github.zero2005x.glassesaicompanion.ai.catalog.ProviderErrorKind
import io.github.zero2005x.glassesaicompanion.data.AiProvider
import io.github.zero2005x.glassesaicompanion.service.SpeechResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * Anthropic Claude Service Implementation
 * 
 * API Docs: https://docs.anthropic.com/claude/reference
 * 
 * Features:
 * - Powerful reasoning capabilities (Claude 4.6 with Extended/Adaptive Thinking)
 * - Vision support (Claude 4.x series)
 * - Long context support (200K standard, 1M beta with header)
 * 
 * IMPORTANT: Claude 4.x enforces that temperature and top_p cannot both be set
 * in the same request body. This service only sends temperature by default.
 */
class AnthropicService(
    apiKey: String,
    modelId: String = "claude-sonnet-4-6",
    systemPrompt: String = "",
    temperature: Float = 0.7f,
    maxTokens: Int = 2048,
    topP: Float = 1.0f,
    internal val baseUrl: String = DEFAULT_BASE_URL,
    /**
     * When requestedContextTokens > 200_000, the beta header for 1M context
     * will be injected automatically for Opus/Sonnet 4.6 models.
     */
    private val requestedContextTokens: Long = 0L
) : BaseAiService(apiKey, modelId, systemPrompt, temperature, maxTokens, topP), AiServiceProvider {
    
    companion object {
        private const val TAG = "AnthropicService"
        internal const val DEFAULT_BASE_URL = "https://api.anthropic.com/v1"
        internal const val API_VERSION = "2023-06-01"
        internal const val BETA_CONTEXT_1M = "context-1m-2025-08-07"
        /** Number of recent history entries (user+assistant pairs) resent per request. */
        private const val HISTORY_TURNS = 6
        /** Cap on max_tokens for vision requests. */
        private const val MAX_VISION_TOKENS = 4096

        /** Claude 4.7 and later reject custom sampling parameters. */
        internal fun supportsTemperature(modelId: String): Boolean {
            val version = Regex("^claude-(?:opus|sonnet|haiku)-(\\d+)(?:-(\\d{1,2})(?:-|$))?")
                .find(modelId) ?: return modelId.startsWith("claude-3")
            val major = version.groupValues[1].toIntOrNull() ?: return false
            val minor = version.groupValues[2].toIntOrNull() ?: 0
            return major < 4 || (major == 4 && minor <= 6)
        }
    }
    
    /**
     * Whether to inject the 1M token context beta header.
     * Only applicable for claude-opus-4-6 and claude-sonnet-4-6.
     */
    private val needs1MContext: Boolean
        get() = requestedContextTokens > 200_000 &&
                (modelId == "claude-opus-4-6" || modelId == "claude-sonnet-4-6")
    
    override val provider = AiProvider.ANTHROPIC
    
    /**
     * Speech Recognition - Anthropic does not support STT
     */
    override suspend fun transcribe(pcmAudioData: ByteArray, languageCode: String): SpeechResult {
        return SpeechResult.Error("Anthropic Claude does not support speech recognition, please use another service")
    }
    
    /**
     * Text Chat - Using Messages API
     */
    override suspend fun chat(userMessage: String): String {
        return withContext(Dispatchers.IO) {
            if (BuildConfig.DEBUG) Log.d(TAG, "Chat request: $userMessage")

            val messages = buildChatMessages(userMessage)
            val requestJson = buildChatRequest(messages)

            val result = executeWithRetry(TAG) { attempt ->
                Log.d(TAG, "Sending chat request to Anthropic (attempt $attempt)")
                val request = buildAnthropicRequest("$baseUrl/messages", requestJson)

                client.newCall(request).execute().use { response ->
                    parseChatResponse(response, userMessage)
                }
            }

            result ?: run {
                lastChatError = lastChatError ?: "empty_response"
                "Sorry, Claude service is temporarily unavailable. Please try again later."
            }
        }
    }

    private fun buildChatMessages(userMessage: String): JSONArray {
        return JSONArray().apply {
            // Conversation history
            for ((role, content) in conversationHistory.takeLast(HISTORY_TURNS)) {
                put(JSONObject().apply {
                    put("role", role)
                    put("content", content)
                })
            }
            // Current user message
            put(JSONObject().apply {
                put("role", "user")
                put("content", userMessage)
            })
        }
    }

    private fun buildChatRequest(messages: JSONArray): JSONObject {
        return JSONObject().apply {
            put("model", modelId)
            put("max_tokens", maxTokens)
            // Claude 4.x: Do NOT set both temperature and top_p simultaneously
            if (supportsTemperature(modelId)) {
                put("temperature", temperature.toDouble().coerceIn(0.0, 1.0))
            }
            put("system", getFullSystemPrompt())
            put("messages", messages)
        }
    }

    private fun buildAnthropicRequest(url: String, body: JSONObject): Request {
        val requestBuilder = Request.Builder()
            .url(url)
            .addHeader("x-api-key", apiKey)
            .addHeader("anthropic-version", API_VERSION)
            .addHeader("Content-Type", "application/json")

        if (needs1MContext) {
            requestBuilder.addHeader("anthropic-beta", BETA_CONTEXT_1M)
        }

        return requestBuilder
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
    }

    /**
     * Streaming chat via the Messages SSE API.
     *
     * `thinking_delta` blocks are never surfaced as text — only a
     * [AiStreamEvent.Thinking] state marker is emitted; the final answer is
     * assembled from `text_delta` events only.
     */
    override fun streamChat(userMessage: String): Flow<AiStreamEvent> = channelFlow {
        val messages = buildChatMessages(userMessage)
        val requestJson = buildChatRequest(messages).apply {
            put("stream", true)
        }
        val request = buildAnthropicRequest("$baseUrl/messages", requestJson)
        val call = client.newCall(request)

        val fullText = StringBuilder()
        var inputTokens: Long? = null
        var outputTokens: Long? = null
        var failed = false

        // Register cancellation BEFORE the blocking execute()/read loop so a
        // cancelled collector aborts the in-flight HTTP request immediately.
        currentCoroutineContext().job.invokeOnCompletion { call.cancel() }

        // trySend drops events when the channel is full or closed; never discard
        // the result silently or the UI transcript diverges from persisted history.
        fun emitEvent(event: AiStreamEvent) {
            if (trySend(event).isFailure) {
                Log.w(TAG, "Stream event dropped (channel closed or buffer full): ${event::class.simpleName}")
                failed = true
            }
        }

        try {
            call.execute().use { response ->
                if (!response.isSuccessful) {
                    failed = true
                    val error = ProviderApiException.fromHttpStatus(
                        response.code, response.body?.string(), response.header("Retry-After")
                    )
                    emitEvent(AiStreamEvent.Error(error.kind, error.message ?: "Provider error", error.httpStatus))
                    return@use
                }
                val source = response.body?.source() ?: run {
                    failed = true
                    emitEvent(
                        AiStreamEvent.Error(
                            ProviderErrorKind.UNKNOWN,
                            "Empty response body (HTTP ${response.code})"
                        )
                    )
                    return@use
                }
                SseParser.readEvents(source) { event ->
                    if (event is SseParser.SseEvent.Data) {
                        try {
                            val json = JSONObject(event.payload)
                            when (json.optString("type")) {
                                "content_block_delta" -> {
                                    val delta = json.optJSONObject("delta")
                                    when (delta?.optString("type")) {
                                        "text_delta" -> {
                                            val text = delta.optString("text", "")
                                            if (text.isNotEmpty()) {
                                                fullText.append(text)
                                                emitEvent(AiStreamEvent.TextDelta(text))
                                            }
                                        }
                                        "thinking_delta", "signature_delta" -> {
                                            // Reasoning content is never shown to the user.
                                            emitEvent(AiStreamEvent.Thinking)
                                        }
                                    }
                                }
                                "message_start" -> {
                                    inputTokens = json.optJSONObject("message")
                                        ?.optJSONObject("usage")
                                        ?.optLong("input_tokens")
                                        ?.takeIf { it > 0 }
                                }
                                "message_delta" -> {
                                    outputTokens = json.optJSONObject("usage")
                                        ?.optLong("output_tokens")
                                        ?.takeIf { it > 0 }
                                }
                                "error" -> {
                                    failed = true
                                    val msg = json.optJSONObject("error")?.optString("message")
                                    emitEvent(
                                        AiStreamEvent.Error(
                                            ProviderErrorKind.UNKNOWN,
                                            ProviderApiException.sanitize(msg)
                                        )
                                    )
                                }
                            }
                        } catch (e: JSONException) {
                            Log.w(TAG, "Skipping malformed Anthropic stream event", e)
                        }
                    }
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            failed = true
            emitEvent(
                AiStreamEvent.Error(
                    ProviderErrorKind.NETWORK_UNAVAILABLE,
                    ProviderApiException.sanitize(e.message)
                )
            )
        } finally {
            if (!failed) {
                val text = fullText.toString()
                if (text.isNotEmpty()) addToHistory(userMessage, text)
                val usage = if (inputTokens != null || outputTokens != null) {
                    AiStreamEvent.Usage(inputTokens, outputTokens)
                } else null
                emitEvent(AiStreamEvent.Completed(text, usage))
            }
            close()
        }
        awaitClose { call.cancel() }
    }.flowOn(Dispatchers.IO)

    private fun parseChatResponse(response: okhttp3.Response, userMessage: String): String? {
        val responseBody = response.body?.string()
        if (!response.isSuccessful) lastChatError = ProviderApiException.fromHttpStatus(response.code, responseBody).message
        if (!response.isSuccessful || responseBody == null) {
            if (BuildConfig.DEBUG) {
                Log.e(TAG, "API error: ${response.code}, body: $responseBody")
            } else {
                Log.e(TAG, "API error: ${response.code}")
            }
            return null
        }
        val json = runCatching { JSONObject(responseBody) }.getOrElse {
            Log.w(TAG, "Malformed Anthropic response body", it)
            return null
        }
        val text = ChatContentParser.extractText(json.opt("content"))
        if (text.isNullOrEmpty()) return null

        addToHistory(userMessage, text)
        if (BuildConfig.DEBUG) Log.d(TAG, "Claude response: $text")
        return text
    }
    
    /**
     * Image Analysis - Using Vision API
     */
    override suspend fun analyzeImage(imageData: ByteArray, prompt: String): String {
        return withContext(Dispatchers.IO) {
            Log.d(TAG, "Image analysis request, size: ${imageData.size} bytes")

            val prepared = try {
                ImagePayloadHelper.prepare(imageData)
            } catch (e: ProviderImageException) {
                return@withContext "Sorry, unable to analyze this image: ${e.message}"
            }
            val imageBase64 = Base64.encodeToString(prepared.data, Base64.NO_WRAP)

            val messages = JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", JSONArray().apply {
                        put(JSONObject().apply {
                            put("type", "image")
                            put("source", JSONObject().apply {
                                put("type", "base64")
                                put("media_type", prepared.mimeType)
                                put("data", imageBase64)
                            })
                        })
                        put(JSONObject().apply {
                            put("type", "text")
                            put("text", prompt)
                        })
                    })
                })
            }
            
            val requestJson = JSONObject().apply {
                put("model", modelId)
                put("max_tokens", maxTokens.coerceAtMost(MAX_VISION_TOKENS))
                // Claude 4.x: Do NOT set both temperature and top_p simultaneously
                if (supportsTemperature(modelId)) {
                    put("temperature", temperature.toDouble().coerceIn(0.0, 1.0))
                }
                put("system", "You are an image analysis assistant. Please provide objective descriptions based on the image content. If unable to determine, please explain.")
                put("messages", messages)
            }
            
            val result = executeWithRetry(TAG) { attempt ->
                Log.d(TAG, "Sending image analysis request to Anthropic (attempt $attempt)")
                
                val request = buildAnthropicRequest("$baseUrl/messages", requestJson)
                
                client.newCall(request).execute().use { response ->
                    val responseBody = response.body?.string()
                    
                    if (response.isSuccessful && responseBody != null) {
                        val json = runCatching { JSONObject(responseBody) }.getOrElse {
                            Log.w(TAG, "Malformed Anthropic vision response body", it)
                            null
                        }
                        ChatContentParser.extractText(json?.opt("content"))
                    } else {
                        if (BuildConfig.DEBUG) {
                            Log.e(TAG, "API error: ${response.code}, body: $responseBody")
                        } else {
                            Log.e(TAG, "API error: ${response.code}")
                        }
                        null
                    }
                }
            }
            
            result ?: "Sorry, unable to analyze this image."
        }
    }
}
