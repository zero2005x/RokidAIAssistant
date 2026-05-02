package com.example.rokidphone.service.ai

import android.util.Log
import com.example.rokidphone.data.AiProvider
import com.example.rokidphone.service.SpeechResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/**
 * OpenAI-Compatible Service Implementation
 * Handles all OpenAI-compatible APIs including:
 * - OpenAI
 * - DeepSeek
 * - Groq
 * - Custom/Self-hosted (Ollama, LM Studio, vLLM, etc.)
 * 
 * This is a generic implementation that works with any OpenAI-compatible endpoint.
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
    /** "none" | "minimal" | "low" | "medium" | "high" | "xhigh" — GPT-5.x / o-series only. */
    val reasoningEffort: String? = null,
    /** "low" | "medium" | "high" — GPT-5.2+ only. */
    val verbosity: String? = null
) : BaseAiService(apiKey, modelId, systemPrompt, temperature, maxTokens, topP, frequencyPenalty, presencePenalty), AiServiceProvider {
    
    companion object {
        private const val TAG = "OpenAiCompatibleService"

        /**
         * Grok 4 is a pure reasoning model that rejects
         * presencePenalty, frequencyPenalty, stop, and reasoning_effort.
         */
        fun isReasoningOnlyModel(modelId: String): Boolean =
            modelId == "grok-4" || modelId.startsWith("grok-4-") && !modelId.startsWith("grok-4.")
    }
    
    override val provider = providerType
    
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

    /**
     * Emit the correct token-limit key for the current model.
     * GPT-5 / o-series use `max_completion_tokens`; everything else uses `max_tokens`.
     */
    private fun putTokenLimit(json: JSONObject, tokens: Int) {
        if (modelId.startsWith("o") || modelId.startsWith("gpt-5")) {
            json.put("max_completion_tokens", tokens)
        } else {
            json.put("max_tokens", tokens)
        }
    }

    /**
     * Whether the GPT-5.x / o-series `reasoning_effort` parameter applies to this model.
     */
    private fun requiresReasoningEffort(): Boolean =
        modelId.startsWith("o") || modelId.startsWith("gpt-5")

    /**
     * Whether the GPT-5.x `verbosity` parameter applies to this model (5.2 / 5.4 and later).
     */
    private fun requiresVerbosity(): Boolean =
        modelId.startsWith("gpt-5.2") || modelId.startsWith("gpt-5.4")

    /**
     * Whether sampling params (temperature, top_p, penalties, stop) should be omitted.
     * True for o-series, and for any GPT-5 model whose effective reasoning_effort is not "none".
     */
    private fun isSamplingLocked(effectiveEffort: String?): Boolean {
        if (modelId.startsWith("o")) return true
        if (modelId.startsWith("gpt-5") && effectiveEffort != null && effectiveEffort != "none") return true
        return false
    }

    /**
     * Hook for subclasses to mutate the chat request JSON just before it is sent
     * (e.g. DeepSeek reasoner strips `temperature`).
     */
    protected open fun postProcessRequestJson(json: JSONObject) {
        // Default: no-op. Subclasses override to inject provider-specific fields.
    }

    /**
     * Hook for subclasses to capture side-channel fields on the assistant message
     * (e.g. DeepSeek's `reasoning_content`). Return value is the text that will be
     * stored in history and returned to the caller; returning null keeps the default
     * behaviour of using `content`.
     */
    protected open fun onAssistantMessage(messageObj: JSONObject): String? = null
    
    /**
     * Speech Recognition - Using Whisper API (if supported)
     */
    override suspend fun transcribe(pcmAudioData: ByteArray, languageCode: String): SpeechResult {
        return withContext(Dispatchers.IO) {
            when (providerType) {
                AiProvider.CUSTOM -> {
                    // Custom providers typically don't support STT
                    SpeechResult.Error("Speech recognition not supported for custom providers")
                }
                AiProvider.DEEPSEEK -> {
                    SpeechResult.Error("DeepSeek does not support speech recognition")
                }
                else -> {
                    // OpenAI, Groq support Whisper
                    transcribeWithWhisper(pcmAudioData, languageCode)
                }
            }
        }
    }
    
    private suspend fun transcribeWithWhisper(pcmAudioData: ByteArray, languageCode: String): SpeechResult {
        return withContext(Dispatchers.IO) {
            Log.d(TAG, "Starting Whisper transcription, audio size: ${pcmAudioData.size} bytes")
            
            if (pcmAudioData.size < 1000) {
                return@withContext SpeechResult.Error("Audio too short, please try again")
            }
            
            val wavData = pcmToWav(pcmAudioData)
            val boundary = "----WebKitFormBoundary${System.currentTimeMillis()}"
            val requestBody = buildMultipartBody(boundary, wavData, languageCode)
            
            val url = buildUrl("audio/transcriptions")
            val authHeader = buildAuthHeader()
            
            try {
                val requestBuilder = Request.Builder()
                    .url(url)
                    .addHeader("Content-Type", "multipart/form-data; boundary=$boundary")
                    .post(requestBody.toRequestBody("multipart/form-data; boundary=$boundary".toMediaType()))
                
                if (authHeader.first.isNotBlank()) {
                    requestBuilder.addHeader(authHeader.first, authHeader.second)
                }
                
                client.newCall(requestBuilder.build()).execute().use { response ->
                    val responseBody = response.body?.string()
                    
                    if (response.isSuccessful && responseBody != null) {
                        val json = JSONObject(responseBody)
                        val text = json.optString("text", "").trim()
                        
                        if (text.isEmpty()) {
                            Log.w(TAG, "Empty transcription result")
                            SpeechResult.Error("No speech detected")
                        } else {
                            Log.d(TAG, "Transcription: $text")
                            SpeechResult.Success(text)
                        }
                    } else {
                        Log.e(TAG, "API error: ${response.code}, body: $responseBody")
                        SpeechResult.Error("Speech recognition failed: ${response.code}")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Whisper transcription error", e)
                SpeechResult.Error("Speech recognition error: ${e.message}")
            }
        }
    }
    
    private fun buildMultipartBody(boundary: String, wavData: ByteArray, languageCode: String): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        val writer = output.bufferedWriter()
        val normalizedLanguageCode = languageCode.substringBefore('-').ifBlank { "auto" }.lowercase()
        
        // file field
        writer.write("--$boundary\r\n")
        writer.write("Content-Disposition: form-data; name=\"file\"; filename=\"audio.wav\"\r\n")
        writer.write("Content-Type: audio/wav\r\n\r\n")
        writer.flush()
        output.write(wavData)
        writer.write("\r\n")
        
        // model field
        val whisperModel = when (providerType) {
            AiProvider.GROQ -> "whisper-large-v3-turbo"
            else -> "whisper-1"
        }
        writer.write("--$boundary\r\n")
        writer.write("Content-Disposition: form-data; name=\"model\"\r\n\r\n")
        writer.write("$whisperModel\r\n")
        
        // language field
        writer.write("--$boundary\r\n")
        writer.write("Content-Disposition: form-data; name=\"language\"\r\n\r\n")
        writer.write("$normalizedLanguageCode\r\n")
        
        writer.write("--$boundary--\r\n")
        writer.flush()
        
        return output.toByteArray()
    }
    
    /**
     * Text Chat - Using Chat Completions API
     */
    override suspend fun chat(userMessage: String): String {
        return withContext(Dispatchers.IO) {
            Log.d(TAG, "Chat request to $providerType: $userMessage")
            
            val messages = JSONArray().apply {
                // System prompt
                put(JSONObject().apply {
                    put("role", "system")
                    put("content", getFullSystemPrompt())
                })
                
                // Conversation history
                for ((role, content) in conversationHistory.takeLast(6)) {
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
            
            val isReasoningOnly = isReasoningOnlyModel(modelId)
            val effectiveEffort: String? = if (requiresReasoningEffort()) {
                // Default to "minimal" for short AR voice replies; user can override.
                reasoningEffort ?: "minimal"
            } else {
                null
            }
            val samplingLocked = isSamplingLocked(effectiveEffort)

            val requestJson = JSONObject().apply {
                put("model", modelId)
                put("messages", messages)

                // Sampling params are rejected by o-series and by GPT-5 when effort != "none",
                // and by pure reasoning models (Grok 4).
                if (!isReasoningOnly && !samplingLocked) {
                    put("temperature", temperature.toDouble())
                    put("top_p", topP.toDouble())
                    if (frequencyPenalty != 0.0f) put("frequency_penalty", frequencyPenalty.toDouble())
                    if (presencePenalty != 0.0f) put("presence_penalty", presencePenalty.toDouble())
                }

                putTokenLimit(this, maxTokens)

                if (effectiveEffort != null) {
                    put("reasoning_effort", effectiveEffort)
                }
                if (requiresVerbosity()) {
                    put("verbosity", verbosity ?: "medium")
                }

                put("stream", false)
            }
            postProcessRequestJson(requestJson)
            
            val url = buildUrl("chat/completions")
            val authHeader = buildAuthHeader()
            
            try {
                val requestBuilder = Request.Builder()
                    .url(url)
                    .addHeader("Content-Type", "application/json")
                    .post(requestJson.toString().toRequestBody("application/json".toMediaType()))
                
                if (authHeader.first.isNotBlank()) {
                    requestBuilder.addHeader(authHeader.first, authHeader.second)
                }
                
                Log.d(TAG, "Sending chat request to: $url")
                
                client.newCall(requestBuilder.build()).execute().use { response ->
                    val responseBody = response.body?.string()
                    
                    if (response.isSuccessful && responseBody != null) {
                        val json = JSONObject(responseBody)
                        val choices = json.optJSONArray("choices")
                        val messageObj = choices?.optJSONObject(0)?.optJSONObject("message")
                        if (messageObj != null) onAssistantMessage(messageObj)
                        val text = messageObj?.optString("content", "")?.trim()
                        
                        if (!text.isNullOrEmpty()) {
                            addToHistory(userMessage, text)
                            Log.d(TAG, "Response: $text")
                            text
                        } else {
                            "Sorry, I couldn't generate a response."
                        }
                    } else {
                        Log.e(TAG, "API error: ${response.code}, body: $responseBody")
                        parseErrorMessage(responseBody) ?: "Sorry, the service is temporarily unavailable."
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Chat error", e)
                "Sorry, an error occurred: ${e.message}"
            }
        }
    }
    
    /**
     * Image Analysis - Using vision-capable models
     */
    override suspend fun analyzeImage(imageData: ByteArray, prompt: String): String {
        return withContext(Dispatchers.IO) {
            if (!providerType.supportsVision) {
                return@withContext "This provider does not support image analysis."
            }

            Log.d(TAG, "Analyzing image with $providerType")

            val messages = buildVisionMessages(imageData, prompt)
            val requestJson = buildVisionRequest(messages)

            try {
                val request = buildVisionHttpRequest(requestJson)
                client.newCall(request).execute().use { response ->
                    extractVisionResponse(response)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Vision error", e)
                "Image analysis error: ${e.message}"
            }
        }
    }

    private fun buildVisionMessages(imageData: ByteArray, prompt: String): JSONArray {
        val base64Image = android.util.Base64.encodeToString(imageData, android.util.Base64.NO_WRAP)
        val imageContent = JSONArray().apply {
            put(JSONObject().apply {
                put("type", "text")
                put("text", prompt)
            })
            put(JSONObject().apply {
                put("type", "image_url")
                put("image_url", JSONObject().apply {
                    put("url", "data:image/jpeg;base64,$base64Image")
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

    private fun buildVisionRequest(messages: JSONArray): JSONObject {
        val effectiveEffort: String? = if (requiresReasoningEffort()) {
            reasoningEffort ?: "minimal"
        } else {
            null
        }
        val samplingLocked = isSamplingLocked(effectiveEffort)

        return JSONObject().apply {
            put("model", modelId)
            put("messages", messages)
            putTokenLimit(this, maxTokens.coerceAtMost(4096))
            if (!samplingLocked) {
                put("temperature", temperature.toDouble())
            }
            if (effectiveEffort != null) {
                put("reasoning_effort", effectiveEffort)
            }
            if (requiresVerbosity()) {
                put("verbosity", verbosity ?: "medium")
            }
        }
    }

    private fun buildVisionHttpRequest(requestJson: JSONObject): Request {
        val url = buildUrl("chat/completions")
        val authHeader = buildAuthHeader()

        val requestBuilder = Request.Builder()
            .url(url)
            .addHeader("Content-Type", "application/json")
            .post(requestJson.toString().toRequestBody("application/json".toMediaType()))

        if (authHeader.first.isNotBlank()) {
            requestBuilder.addHeader(authHeader.first, authHeader.second)
        }
        return requestBuilder.build()
    }

    private fun extractVisionResponse(response: okhttp3.Response): String {
        val responseBody = response.body?.string()
        if (!response.isSuccessful || responseBody == null) {
            Log.e(TAG, "Vision API error: ${response.code}")
            return "Image analysis failed."
        }
        val json = JSONObject(responseBody)
        val choices = json.optJSONArray("choices")
        return choices?.optJSONObject(0)
            ?.optJSONObject("message")
            ?.optString("content", "")?.trim()
            ?: "Unable to analyze image."
    }
    
    /**
     * Test Connection - Verify API connectivity
     */
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
                
                val requestJson = JSONObject().apply {
                    put("model", modelId)
                    put("messages", messages)
                    putTokenLimit(this, 10)
                }
                
                val url = buildUrl("chat/completions")
                val authHeader = buildAuthHeader()
                
                val requestBuilder = Request.Builder()
                    .url(url)
                    .addHeader("Content-Type", "application/json")
                    .post(requestJson.toString().toRequestBody("application/json".toMediaType()))
                
                if (authHeader.first.isNotBlank()) {
                    requestBuilder.addHeader(authHeader.first, authHeader.second)
                }
                
                client.newCall(requestBuilder.build()).execute().use { response ->
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
