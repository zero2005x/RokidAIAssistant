package io.github.zero2005x.glassesaicompanion.service.ai

import android.util.Log
import io.github.zero2005x.glassesaicompanion.ai.catalog.ModelCapabilityResolver
import io.github.zero2005x.glassesaicompanion.ai.catalog.ProviderApiException
import io.github.zero2005x.glassesaicompanion.ai.catalog.ProviderErrorKind
import io.github.zero2005x.glassesaicompanion.data.AiProvider
import io.github.zero2005x.glassesaicompanion.service.SpeechResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/**
 * OpenAI-Compatible Service Implementation.
 *
 * Covers Chat Completions (OpenAI, DeepSeek, Groq, xAI, Alibaba, Zhipu,
 * Moonshot, Mistral, Perplexity, Custom) and, when [useResponsesApi] is set,
 * the OpenAI Responses API with automatic Chat-Completions fallback.
 *
 * Request parameters are decided by [ProviderRequestPolicies] (capability
 * driven), never by passing OpenAI-specific parameters to other providers.
 */
open class OpenAiCompatibleService(
    apiKey: String,
    private val baseUrl: String,
    modelId: String,
    systemPrompt: String = "",
    private val providerType: AiProvider = AiProvider.OPENAI,
    temperature: Float = 0.7f,
    maxTokens: Int = 2048,
    topP: Float = 1.0f,
    frequencyPenalty: Float = 0.0f,
    presencePenalty: Float = 0.0f,
    /** "none" | "minimal" | "low" | "medium" | "high" | "xhigh" — OpenAI reasoning models only. */
    val reasoningEffort: String? = null,
    /** "low" | "medium" | "high" — OpenAI GPT-5.2+ only. */
    val verbosity: String? = null,
    /** Use the OpenAI Responses API instead of Chat Completions (falls back automatically). */
    private val useResponsesApi: Boolean = false,
    /**
     * User-declared capability overrides (Custom endpoints only). When set,
     * these win over catalog/provider-default resolution — e.g. enabling
     * image input for a local LM Studio model that supports vision.
     */
    private val capabilityOverrides: io.github.zero2005x.glassesaicompanion.ai.catalog.ModelCapabilities? = null
) : BaseAiService(apiKey, modelId, systemPrompt, temperature, maxTokens, topP, frequencyPenalty, presencePenalty), AiServiceProvider {

    companion object {
        private const val TAG = "OpenAiCompatibleService"

        /**
         * Grok 4 is a pure reasoning model that rejects
         * presencePenalty, frequencyPenalty, stop, and reasoning_effort.
         */
        fun isReasoningOnlyModel(modelId: String): Boolean =
            ProviderRequestPolicies.isXaiReasoningOnly(modelId)
    }

    override val provider = providerType

    /** Effective capabilities: user overrides (Custom) → catalog → provider default. */
    private val effectiveCapabilities: io.github.zero2005x.glassesaicompanion.ai.catalog.ModelCapabilities
        get() = capabilityOverrides ?: ModelCapabilityResolver.resolve(providerType, modelId)

    /** Effective request policy for the current provider+model. */
    private val policy: ProviderRequestPolicy
        get() = ProviderRequestPolicies.resolve(
            providerType,
            modelId,
            effectiveCapabilities,
            reasoningEffort
        )

    /**
     * Build the full endpoint URL
     */
    private fun buildUrl(endpoint: String): String {
        val normalizedBase = baseUrl.trimEnd('/')
        val normalizedEndpoint = endpoint.trimStart('/')
        return "$normalizedBase/$normalizedEndpoint"
    }

    /**
     * Build authorization header based on provider type
     */
    private fun buildAuthHeader(): Pair<String, String> {
        return if (apiKey.isNotBlank()) {
            "Authorization" to "Bearer $apiKey"
        } else {
            // For local models (Ollama, LM Studio) that may not need auth
            "" to ""
        }
    }

    /** Emit the token-limit field dictated by the policy (if any). */
    private fun putTokenLimit(json: JSONObject, tokens: Int) {
        when (policy.tokenLimitField) {
            TokenLimitField.MAX_COMPLETION_TOKENS -> json.put("max_completion_tokens", tokens)
            TokenLimitField.MAX_TOKENS -> json.put("max_tokens", tokens)
            TokenLimitField.NONE -> Unit
        }
    }

    private fun requiresReasoningEffort(): Boolean = policy.supportsReasoningEffort

    private fun requiresVerbosity(): Boolean = policy.supportsVerbosity

    /** Sampling params locked out (reasoning models). */
    private fun isSamplingLocked(): Boolean = !policy.allowSampling

    private fun effectiveReasoningEffort(): String? =
        if (requiresReasoningEffort()) ProviderRequestPolicies.openAiReasoningEffort(modelId, reasoningEffort) else null

    /**
     * Hook for subclasses to mutate the chat request JSON just before it is sent
     * (e.g. DeepSeek reasoner strips `temperature`).
     */
    protected open fun postProcessRequestJson(json: JSONObject) {
        if (providerType == AiProvider.ZHIPU || providerType == AiProvider.MOONSHOT) {
            if (json.has("temperature")) {
                json.put("temperature", json.getDouble("temperature").coerceIn(0.0, 1.0))
            }
        }
        if (providerType == AiProvider.ZHIPU && json.has("top_p")) {
            json.put("top_p", json.getDouble("top_p").coerceIn(0.01, 1.0))
        }
        // Original open Qwen3 hybrid models only support thinking in streaming mode.
        val streamingThinkingModels = setOf(
            "qwen3-235b-a22b", "qwen3-32b", "qwen3-30b-a3b", "qwen3-14b",
            "qwen3-8b", "qwen3-4b", "qwen3-1.7b", "qwen3-0.6b"
        )
        if (providerType == AiProvider.ALIBABA && !json.optBoolean("stream") &&
            modelId in streamingThinkingModels) {
            json.put("enable_thinking", false)
        }
    }

    /**
     * Hook for subclasses to capture side-channel fields on the assistant message
     * (e.g. DeepSeek's `reasoning_content`). Return value is the text that will be
     * stored in history and returned to the caller; returning null keeps the default
     * behaviour of using `content`.
     */
    protected open fun onAssistantMessage(messageObj: JSONObject): String? = null

    /**
     * Hook for subclasses to augment the final text with typed metadata that the
     * UI cannot render yet (e.g. Perplexity citations). Never loses data.
     */
    protected open fun augmentResponseText(fullJson: JSONObject, text: String): String = text

    // ==================== Speech recognition (STT decoupled from chat) ====================

    override suspend fun transcribe(pcmAudioData: ByteArray, languageCode: String): SpeechResult {
        return withContext(Dispatchers.IO) {
            val sttPolicy = policy
            if (!sttPolicy.supportsAudioTranscriptions || sttPolicy.transcriptionModel == null) {
                return@withContext when (providerType) {
                    AiProvider.CUSTOM ->
                        SpeechResult.Error("Speech recognition not supported for custom providers")
                    else ->
                        SpeechResult.Error("$providerType does not support speech recognition via this endpoint")
                }
            }
            transcribeWithWhisper(pcmAudioData, languageCode, sttPolicy.transcriptionModel)
        }
    }

    override suspend fun transcribeAudioFile(
        audioData: ByteArray,
        mimeType: String,
        languageCode: String
    ): SpeechResult = withContext(Dispatchers.IO) {
        val sttPolicy = policy
        val transcriptionModel = sttPolicy.transcriptionModel
        if (!sttPolicy.supportsAudioTranscriptions || transcriptionModel == null) {
            return@withContext SpeechResult.Error(
                "$providerType does not support encoded audio transcription via this endpoint"
            )
        }
        val extension = when (mimeType.lowercase()) {
            "audio/mp4", "audio/m4a" -> "m4a"
            "audio/mpeg", "audio/mp3" -> "mp3"
            "audio/ogg" -> "ogg"
            "audio/webm" -> "webm"
            "audio/wav", "audio/x-wav" -> "wav"
            else -> return@withContext SpeechResult.Error("Unsupported audio format: $mimeType")
        }
        transcribeAudio(
            audioData = audioData,
            languageCode = languageCode,
            transcriptionModel = transcriptionModel,
            fileName = "audio.$extension",
            mimeType = mimeType
        )
    }

    private suspend fun transcribeWithWhisper(
        pcmAudioData: ByteArray,
        languageCode: String,
        transcriptionModel: String
    ): SpeechResult = transcribeAudio(
        audioData = pcmToWav(pcmAudioData),
        languageCode = languageCode,
        transcriptionModel = transcriptionModel,
        fileName = "audio.wav",
        mimeType = "audio/wav"
    )

    private suspend fun transcribeAudio(
        audioData: ByteArray,
        languageCode: String,
        transcriptionModel: String,
        fileName: String,
        mimeType: String
    ): SpeechResult {
        if (audioData.size < 1000) return SpeechResult.Error("Audio too short, please try again")
        Log.d(TAG, "Starting transcription, audio size: ${audioData.size} bytes")
        val boundary = "----RokidBoundary${System.currentTimeMillis()}"
        val requestBody = buildMultipartBody(
            boundary,
            audioData,
            languageCode,
            transcriptionModel,
            fileName,
            mimeType
        )
        val authHeader = buildAuthHeader()
        val requestBuilder = Request.Builder()
            .url(buildUrl("audio/transcriptions"))
            .addHeader("Content-Type", "multipart/form-data; boundary=$boundary")
            .post(requestBody.toRequestBody("multipart/form-data; boundary=$boundary".toMediaType()))
        if (authHeader.first.isNotBlank()) requestBuilder.addHeader(authHeader.first, authHeader.second)

        return try {
            client.newCall(requestBuilder.build()).execute().use { response ->
                val body = response.body?.string()
                if (response.isSuccessful && body != null) {
                    JSONObject(body).optString("text").trim().takeIf { it.isNotEmpty() }
                        ?.let { SpeechResult.Success(it) }
                        ?: SpeechResult.Error("No speech detected")
                } else {
                    val error = ProviderApiException.fromHttpStatus(response.code, body)
                    Log.e(TAG, "Transcription API error: ${error.kind}")
                    SpeechResult.Error("Speech recognition failed: ${error.message}")
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Transcription error", e)
            SpeechResult.Error("Speech recognition error: ${ProviderApiException.sanitize(e.message)}")
        }
    }

    private fun buildMultipartBody(
        boundary: String,
        wavData: ByteArray,
        languageCode: String,
        transcriptionModel: String,
        fileName: String,
        mimeType: String
    ): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        val writer = output.bufferedWriter()
        val normalizedLanguageCode = languageCode.substringBefore('-').ifBlank { "auto" }.lowercase()

        // file field
        writer.write("--$boundary\r\n")
        writer.write("Content-Disposition: form-data; name=\"file\"; filename=\"$fileName\"\r\n")
        writer.write("Content-Type: $mimeType\r\n\r\n")
        writer.flush()
        output.write(wavData)
        writer.write("\r\n")

        // model field — STT model comes from the policy, never from the chat model
        writer.write("--$boundary\r\n")
        writer.write("Content-Disposition: form-data; name=\"model\"\r\n\r\n")
        writer.write("$transcriptionModel\r\n")

        // Match the official Python SDK's bracket serialization for multipart arrays.
        val languageField = if (transcriptionModel == "gpt-transcribe") "languages[]" else "language"
        writer.write("--$boundary\r\n")
        writer.write("Content-Disposition: form-data; name=\"$languageField\"\r\n\r\n")
        writer.write("$normalizedLanguageCode\r\n")

        writer.write("--$boundary--\r\n")
        writer.flush()

        return output.toByteArray()
    }

    // ==================== Chat (non-streaming, compatibility API) ====================

    override suspend fun chat(userMessage: String): String {
        return withContext(Dispatchers.IO) {
            Log.d(TAG, "Chat request to $providerType: $userMessage")
            if (useResponsesApi) {
                chatViaResponses(userMessage, streaming = false)
                    ?: chatViaChatCompletions(userMessage) // Responses rejected → fallback
            } else {
                chatViaChatCompletions(userMessage)
            }
        }
    }

    private fun buildMessagesJson(userMessage: String): JSONArray {
        return JSONArray().apply {
            put(JSONObject().apply {
                put("role", "system")
                put("content", getFullSystemPrompt())
            })
            for ((role, content) in conversationHistory.takeLast(6)) {
                put(JSONObject().apply {
                    put("role", role)
                    put("content", content)
                })
            }
            put(JSONObject().apply {
                put("role", "user")
                put("content", userMessage)
            })
        }
    }

    /** Apply the request policy to a Chat Completions body. */
    private fun buildChatCompletionsBody(messages: JSONArray, stream: Boolean): JSONObject {
        val p = policy
        val json = JSONObject().apply {
            put("model", modelId)
            put("messages", messages)

            if (!isSamplingLocked()) {
                put("temperature", temperature.toDouble())
                put("top_p", topP.toDouble())
                if (p.allowPenalties) {
                    if (frequencyPenalty != 0.0f) put("frequency_penalty", frequencyPenalty.toDouble())
                    if (presencePenalty != 0.0f) put("presence_penalty", presencePenalty.toDouble())
                }
            }

            putTokenLimit(this, maxTokens)

            effectiveReasoningEffort()?.let { put("reasoning_effort", it) }
            if (requiresVerbosity()) {
                put("verbosity", verbosity ?: "medium")
            }

            put("stream", stream)
            if (stream && p.supportsStreamOptions) {
                put("stream_options", JSONObject().put("include_usage", true))
            }
        }
        postProcessRequestJson(json)
        return json
    }

    private fun buildJsonRequest(url: String, body: JSONObject): Request {
        val authHeader = buildAuthHeader()
        val requestBuilder = Request.Builder()
            .url(url)
            .addHeader("Content-Type", "application/json")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
        if (authHeader.first.isNotBlank()) {
            requestBuilder.addHeader(authHeader.first, authHeader.second)
        }
        return requestBuilder.build()
    }

    private fun chatViaChatCompletions(userMessage: String): String {
        val requestJson = buildChatCompletionsBody(buildMessagesJson(userMessage), stream = false)
        val request = buildJsonRequest(buildUrl("chat/completions"), requestJson)

        return try {
            client.newCall(request).execute().use { response ->
                val responseBody = response.body?.string()

                if (response.isSuccessful && responseBody != null) {
                    val json = JSONObject(responseBody)
                    val choices = json.optJSONArray("choices")
                    val messageObj = choices?.optJSONObject(0)?.optJSONObject("message")
                    if (messageObj != null) onAssistantMessage(messageObj)
                    val text = ChatContentParser.extractText(messageObj?.opt("content"))

                    if (!text.isNullOrEmpty()) {
                        val finalText = augmentResponseText(json, text)
                        addToHistory(userMessage, finalText)
                        Log.d(TAG, "Response received (${finalText.length} chars)")
                        finalText
                    } else {
                        lastChatError = "empty_response"
                        "Sorry, I couldn't generate a response."
                    }
                } else {
                    val error = ProviderApiException.fromHttpStatus(response.code, responseBody)
                    Log.e(TAG, "API error: ${error.kind} (${error.httpStatus})")
                    lastChatError = error.message
                    error.message ?: "Sorry, the service is temporarily unavailable."
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Chat error", e)
            lastChatError = ProviderApiException.sanitize(e.message)
            "Sorry, an error occurred: ${ProviderApiException.sanitize(e.message)}"
        }
    }

    // ==================== OpenAI Responses API ====================

    private fun buildResponsesInput(userMessage: String): JSONArray {
        fun item(role: String, text: String): JSONObject {
            val partType = if (role == "assistant") "output_text" else "input_text"
            return JSONObject().apply {
                put("role", role)
                put("content", JSONArray().apply {
                    put(JSONObject().apply {
                        put("type", partType)
                        put("text", text)
                    })
                })
            }
        }
        return JSONArray().apply {
            put(item("system", getFullSystemPrompt()))
            for ((role, content) in conversationHistory.takeLast(6)) {
                put(item(if (role == "assistant") "assistant" else "user", content))
            }
            put(item("user", userMessage))
        }
    }

    private fun buildResponsesBody(input: JSONArray, stream: Boolean): JSONObject {
        val p = policy
        return JSONObject().apply {
            put("model", modelId)
            put("input", input)
            put("stream", stream)
            if (p.tokenLimitField != TokenLimitField.NONE) {
                put("max_output_tokens", maxTokens)
            }
            if (!isSamplingLocked()) {
                put("temperature", temperature.toDouble())
                put("top_p", topP.toDouble())
            }
            effectiveReasoningEffort()?.let {
                put("reasoning", JSONObject().put("effort", it))
            }
            if (requiresVerbosity()) {
                put("text", JSONObject().put("verbosity", verbosity ?: "medium"))
            }
        }
    }

    /**
     * @return the response text, or null when the endpoint rejected the request
     *   with 400/404/422 (caller falls back to Chat Completions)
     */
    private fun chatViaResponses(userMessage: String, streaming: Boolean): String? {
        val body = buildResponsesBody(buildResponsesInput(userMessage), stream = streaming)
        val request = buildJsonRequest(buildUrl("responses"), body)

        return try {
            client.newCall(request).execute().use { response ->
                val responseBody = response.body?.string()
                when {
                    response.code == 400 || response.code == 404 || response.code == 422 -> {
                        Log.w(TAG, "Responses API rejected (${response.code}); falling back to Chat Completions")
                        null
                    }
                    response.isSuccessful && responseBody != null -> {
                        val json = JSONObject(responseBody)
                        val text = extractResponsesText(json)
                        if (!text.isNullOrEmpty()) {
                            addToHistory(userMessage, text)
                            text
                        } else {
                            lastChatError = "empty_response"
                            "Sorry, I couldn't generate a response."
                        }
                    }
                    else -> {
                        val error = ProviderApiException.fromHttpStatus(response.code, responseBody)
                        Log.e(TAG, "Responses API error: ${error.kind}")
                        lastChatError = error.message
                        error.message
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Responses API error", e)
            lastChatError = ProviderApiException.sanitize(e.message)
            "Sorry, an error occurred: ${ProviderApiException.sanitize(e.message)}"
        }
    }

    private fun extractResponsesText(json: JSONObject): String? {
        val output = json.optJSONArray("output") ?: return null
        val sb = StringBuilder()
        for (i in 0 until output.length()) {
            val item = output.optJSONObject(i) ?: continue
            if (item.optString("type") != "message") continue
            val content = item.optJSONArray("content") ?: continue
            for (j in 0 until content.length()) {
                val part = content.optJSONObject(j) ?: continue
                if (part.optString("type") == "output_text") {
                    sb.append(part.optString("text"))
                }
            }
        }
        return sb.toString().trim().takeIf { it.isNotEmpty() }
    }

    // ==================== Streaming ====================

    override fun streamChat(userMessage: String): Flow<AiStreamEvent> = channelFlow {
        val p = policy
        if (!p.streaming) {
            val text = chat(userMessage)
            trySend(AiStreamEvent.TextDelta(text))
            trySend(AiStreamEvent.Completed(text))
            close()
            awaitClose { }
            return@channelFlow
        }

        val (url, body) = if (useResponsesApi) {
            buildUrl("responses") to buildResponsesBody(buildResponsesInput(userMessage), stream = true)
        } else {
            buildUrl("chat/completions") to buildChatCompletionsBody(buildMessagesJson(userMessage), stream = true)
        }
        val request = buildJsonRequest(url, body)
        val call = client.newCall(request)

        val fullText = StringBuilder()
        var usage: AiStreamEvent.Usage? = null
        var failed = false
        val emittedCitations = mutableSetOf<String>()

        try {
            call.execute().use { response ->
                if (!response.isSuccessful) {
                    failed = true
                    val error = ProviderApiException.fromHttpStatus(
                        response.code,
                        response.body?.string(),
                        response.header("Retry-After")
                    )
                    trySend(AiStreamEvent.Error(error.kind, error.message ?: "Provider error", error.httpStatus))
                    return@use
                }
                val source = response.body?.source() ?: run {
                    failed = true
                    trySend(
                        AiStreamEvent.Error(
                            ProviderErrorKind.UNKNOWN,
                            "Empty response body (HTTP ${response.code})"
                        )
                    )
                    return@use
                }
                SseParser.readEvents(source) { event ->
                    when (event) {
                        is SseParser.SseEvent.Done -> Unit
                        is SseParser.SseEvent.Data -> {
                            val parsed = parseStreamPayload(
                                event.payload, fullText, emittedCitations
                            )
                            parsed.usage?.let { usage = it }
                            if (parsed.hasError) {
                                failed = true
                            }
                            parsed.events.forEach { trySend(it) }
                        }
                    }
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            failed = true
            trySend(
                AiStreamEvent.Error(
                    ProviderErrorKind.NETWORK_UNAVAILABLE,
                    ProviderApiException.sanitize(e.message)
                )
            )
        } finally {
            if (!failed) {
                val text = fullText.toString()
                if (text.isNotEmpty()) addToHistory(userMessage, text)
                trySend(AiStreamEvent.Completed(text, usage))
            }
            close()
        }
        // Cancels the in-flight OkHttp call when the collector is cancelled
        // (and runs harmlessly after normal completion).
        awaitClose { call.cancel() }
    }.flowOn(Dispatchers.IO)

    private class StreamParseResult(
        val events: List<AiStreamEvent>,
        val usage: AiStreamEvent.Usage?,
        val hasError: Boolean = false
    )

    /** Parse one SSE payload from either Chat Completions or Responses streams. */
    private fun parseStreamPayload(
        payload: String,
        fullText: StringBuilder,
        emittedCitations: MutableSet<String>
    ): StreamParseResult {
        val events = mutableListOf<AiStreamEvent>()
        var usage: AiStreamEvent.Usage? = null
        try {
            val json = JSONObject(payload)

            if (json.has("error")) {
                val errObj = json.optJSONObject("error")
                val message = errObj?.optString("message").takeUnless { it.isNullOrBlank() }
                    ?: json.optString("error")
                events += AiStreamEvent.Error(
                    ProviderErrorKind.UNKNOWN,
                    ProviderApiException.sanitize(message)
                )
                return StreamParseResult(events, null, hasError = true)
            }

            // ---------- Responses API events ----------
            when (json.optString("type")) {
                "response.output_text.delta" -> {
                    val delta = json.optString("delta")
                    if (delta.isNotEmpty()) {
                        fullText.append(delta)
                        events += AiStreamEvent.TextDelta(delta)
                    }
                    return StreamParseResult(events, null)
                }
                "response.reasoning_summary_text.delta", "response.reasoning_text.delta" -> {
                    // Never surface reasoning text; only report the state.
                    events += AiStreamEvent.Thinking
                    return StreamParseResult(events, null)
                }
                "response.completed" -> {
                    usage = json.optJSONObject("response")?.optJSONObject("usage")?.let { u ->
                        AiStreamEvent.Usage(
                            u.optLong("input_tokens").takeIf { it > 0 },
                            u.optLong("output_tokens").takeIf { it > 0 }
                        )
                    }
                    return StreamParseResult(events, usage)
                }
                "response.failed", "response.incomplete", "error" -> {
                    val msg = json.optJSONObject("response")?.optJSONObject("error")?.optString("message")
                        ?: json.optJSONObject("response")?.optJSONObject("incomplete_details")?.optString("reason")
                        ?: json.optString("message", "Response did not complete")
                    events += AiStreamEvent.Error(
                        ProviderErrorKind.UNKNOWN,
                        ProviderApiException.sanitize(msg)
                    )
                    return StreamParseResult(events, null, hasError = true)
                }
                "response.output_item.done" -> {
                    val item = json.optJSONObject("item")
                    if (item != null && item.optString("type") == "function_call") {
                        events += AiStreamEvent.ToolCallDelta(
                            index = 0,
                            id = item.optString("call_id").ifBlank { null },
                            name = item.optString("name").ifBlank { null },
                            argumentsDelta = item.optString("arguments").ifBlank { null }
                        )
                    }
                    return StreamParseResult(events, null)
                }
            }

            // ---------- Chat Completions chunks ----------
            val choices = json.optJSONArray("choices")
            if (choices != null && choices.length() > 0) {
                val choice = choices.optJSONObject(0)
                val delta = choice?.optJSONObject("delta")
                if (delta != null) {
                    val content = ChatContentParser.extractText(delta.opt("content")) ?: ""
                    if (content.isNotEmpty()) {
                        fullText.append(content)
                        events += AiStreamEvent.TextDelta(content)
                    }
                    if (delta.has("reasoning_content")) {
                        // DeepSeek-style reasoning side channel: never surfaced.
                        events += AiStreamEvent.Thinking
                    }
                    delta.optJSONArray("tool_calls")?.let { toolCalls ->
                        for (i in 0 until toolCalls.length()) {
                            val tc = toolCalls.optJSONObject(i) ?: continue
                            val fn = tc.optJSONObject("function")
                            events += AiStreamEvent.ToolCallDelta(
                                index = tc.optInt("index", i),
                                id = tc.optString("id").ifBlank { null },
                                name = fn?.optString("name")?.ifBlank { null },
                                argumentsDelta = fn?.optString("arguments")?.ifBlank { null }
                            )
                        }
                    }
                }
            }

            // Usage arrives on the final chunk when include_usage is set.
            json.optJSONObject("usage")?.let { u ->
                val input = u.optLong("prompt_tokens", u.optLong("input_tokens", 0))
                val output = u.optLong("completion_tokens", u.optLong("output_tokens", 0))
                if (input > 0 || output > 0) {
                    usage = AiStreamEvent.Usage(
                        input.takeIf { it > 0 },
                        output.takeIf { it > 0 }
                    )
                }
            }

            // Perplexity Sonar: citations / search_results are typed metadata.
            json.optJSONArray("citations")?.let { citations ->
                for (i in 0 until citations.length()) {
                    val url = citations.optString(i)
                    if (!url.isNullOrBlank() && emittedCitations.add(url)) {
                        events += AiStreamEvent.Citation(url)
                    }
                }
            }
        } catch (e: Exception) {
            // Malformed event: skip it, never kill the stream.
            Log.w(TAG, "Skipping malformed stream event")
        }
        return StreamParseResult(events, usage)
    }

    // ==================== Image analysis ====================

    override suspend fun analyzeImage(imageData: ByteArray, prompt: String): String {
        return withContext(Dispatchers.IO) {
            lastChatError = null
            if (!effectiveCapabilities.imageInput || policy.imageContentFormat == ImageContentFormat.NONE) {
                lastChatError = "This provider does not support image analysis."
                return@withContext lastChatError!!
            }

            val prepared = try {
                ImagePayloadHelper.prepare(imageData)
            } catch (e: ProviderImageException) {
                lastChatError = ProviderApiException.sanitize(e.message)
                return@withContext "Image analysis error: ${e.message}"
            }

            Log.d(TAG, "Analyzing image with $providerType (mime=${prepared.mimeType})")

            if (useResponsesApi) {
                analyzeImageViaResponses(prepared, prompt)
                    ?: analyzeImageViaChatCompletions(prepared, prompt)
            } else {
                analyzeImageViaChatCompletions(prepared, prompt)
            }
        }
    }

    private fun buildVisionMessages(prepared: ImagePayloadHelper.PreparedImage, prompt: String): JSONArray {
        val base64Image = android.util.Base64.encodeToString(prepared.data, android.util.Base64.NO_WRAP)
        val imageContent = JSONArray().apply {
            put(JSONObject().apply {
                put("type", "text")
                put("text", prompt)
            })
            put(JSONObject().apply {
                put("type", "image_url")
                put("image_url", JSONObject().apply {
                    put("url", "data:${prepared.mimeType};base64,$base64Image")
                })
            })
        }
        return JSONArray().apply {
            put(JSONObject().apply {
                put("role", "user")
                put("content", imageContent)
            })
        }
    }

    private fun analyzeImageViaChatCompletions(
        prepared: ImagePayloadHelper.PreparedImage,
        prompt: String
    ): String {
        val messages = buildVisionMessages(prepared, prompt)
        val requestJson = JSONObject().apply {
            put("model", modelId)
            put("messages", messages)
            putTokenLimit(this, maxTokens.coerceAtMost(4096))
            if (!isSamplingLocked()) {
                put("temperature", temperature.toDouble())
            }
            effectiveReasoningEffort()?.let { put("reasoning_effort", it) }
            if (requiresVerbosity()) {
                put("verbosity", verbosity ?: "medium")
            }
        }
        postProcessRequestJson(requestJson)

        return try {
            val request = buildJsonRequest(buildUrl("chat/completions"), requestJson)
            client.newCall(request).execute().use { response ->
                extractVisionResponse(response)
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            lastChatError = ProviderApiException.sanitize(e.message)
            Log.e(TAG, "Vision error", e)
            "Image analysis error: ${ProviderApiException.sanitize(e.message)}"
        }
    }

    private fun analyzeImageViaResponses(
        prepared: ImagePayloadHelper.PreparedImage,
        prompt: String
    ): String? {
        val base64Image = android.util.Base64.encodeToString(prepared.data, android.util.Base64.NO_WRAP)
        val input = JSONArray().apply {
            put(JSONObject().apply {
                put("role", "user")
                put("content", JSONArray().apply {
                    put(JSONObject().apply {
                        put("type", "input_text")
                        put("text", prompt)
                    })
                    put(JSONObject().apply {
                        put("type", "input_image")
                        put("image_url", "data:${prepared.mimeType};base64,$base64Image")
                    })
                })
            })
        }
        val body = buildResponsesBody(input, stream = false)
        val request = buildJsonRequest(buildUrl("responses"), body)
        return try {
            client.newCall(request).execute().use { response ->
                val responseBody = response.body?.string()
                when {
                    response.code == 400 || response.code == 404 || response.code == 422 -> null
                    response.isSuccessful && responseBody != null -> {
                        val text = extractResponsesText(JSONObject(responseBody))
                        if (text.isNullOrBlank()) lastChatError = "empty_response"
                        text ?: "Unable to analyze image."
                    }
                    else -> {
                        lastChatError = ProviderApiException.fromHttpStatus(response.code, responseBody).message
                        Log.e(TAG, "Responses vision error: ${response.code}")
                        "Image analysis failed."
                    }
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            lastChatError = ProviderApiException.sanitize(e.message)
            "Image analysis error: ${ProviderApiException.sanitize(e.message)}"
        }
    }

    private fun extractVisionResponse(response: okhttp3.Response): String {
        val responseBody = response.body?.string()
        if (!response.isSuccessful || responseBody == null) {
            lastChatError = ProviderApiException.fromHttpStatus(response.code, responseBody).message
            Log.e(TAG, "Vision API error: ${response.code}")
            return "Image analysis failed."
        }
        val json = JSONObject(responseBody)
        val choices = json.optJSONArray("choices")
        val messageObj = choices?.optJSONObject(0)?.optJSONObject("message")
        val text = ChatContentParser.extractText(messageObj?.opt("content"))
        lastChatError = if (text.isNullOrBlank()) "empty_response" else null
        return text ?: "Unable to analyze image."
    }

    // ==================== Connection test ====================

    suspend fun testConnection(): Result<String> {
        return withContext(Dispatchers.IO) {
            try {
                Log.d(TAG, "Testing connection to: $baseUrl")

                // Try to list models (most providers support this)
                val modelsUrl = buildUrl("models")
                val authHeader = buildAuthHeader()

                val requestBuilder = Request.Builder()
                    .url(modelsUrl)
                    .get()

                if (authHeader.first.isNotBlank()) {
                    requestBuilder.addHeader(authHeader.first, authHeader.second)
                }

                client.newCall(requestBuilder.build()).execute().use { response ->
                    if (response.isSuccessful) {
                        val responseBody = response.body?.string()
                        val json = JSONObject(responseBody ?: "{}")
                        val data = json.optJSONArray("data")
                        val modelCount = data?.length() ?: 0
                        Result.success("Connected successfully! Found $modelCount models.")
                    } else if (response.code == 401) {
                        Result.failure(Exception("Authentication failed. Please check your API key."))
                    } else if (response.code == 404) {
                        // Some providers don't have /models endpoint, try a simple chat
                        testWithSimpleChat()
                    } else {
                        Result.failure(Exception("Connection failed: ${response.code} ${response.message}"))
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Connection test failed", e)
                Result.failure(e)
            }
        }
    }

    private suspend fun testWithSimpleChat(): Result<String> {
        return withContext(Dispatchers.IO) {
            try {
                val messages = JSONArray().apply {
                    put(JSONObject().apply {
                        put("role", "user")
                        put("content", "Hello")
                    })
                }

                val endpoint = if (useResponsesApi) "responses" else "chat/completions"
                val requestJson = if (useResponsesApi) {
                    buildResponsesBody(buildResponsesInput("Hello"), stream = false)
                } else {
                    buildChatCompletionsBody(messages, stream = false)
                }

                val request = buildJsonRequest(buildUrl(endpoint), requestJson)

                client.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        Result.success("Connected successfully!")
                    } else {
                        val body = response.body?.string()
                        val errorMsg = parseErrorMessage(body) ?: "Connection failed: ${response.code}"
                        Result.failure(Exception(errorMsg))
                    }
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }

    /**
     * Parse error message from API response
     */
    private fun parseErrorMessage(responseBody: String?): String? {
        if (responseBody == null) return null
        return try {
            val json = JSONObject(responseBody)
            json.optJSONObject("error")?.optString("message")
                ?: json.optString("message")
                ?: json.optString("error")
        } catch (e: Exception) {
            null
        }
    }
}
