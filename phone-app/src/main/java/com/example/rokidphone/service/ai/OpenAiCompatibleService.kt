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
class OpenAiCompatibleService(
    apiKey: String,
    private val baseUrl: String,
    modelId: String,
    systemPrompt: String = "",
    private val providerType: AiProvider = AiProvider.OPENAI,
    temperature: Float = 0.7f,
    maxTokens: Int = 2048,
    topP: Float = 1.0f,
    frequencyPenalty: Float = 0.0f,
    presencePenalty: Float = 0.0f
) : BaseAiService(apiKey, modelId, systemPrompt, temperature, maxTokens, topP, frequencyPenalty, presencePenalty), AiServiceProvider {
    
    companion object {
        private const val TAG = "OpenAiCompatibleService"
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
                    transcribeWithWhisper(pcmAudioData)
                }
            }
        }
    }
    
    private suspend fun transcribeWithWhisper(pcmAudioData: ByteArray): SpeechResult {
        return withContext(Dispatchers.IO) {
            Log.d(TAG, "Starting Whisper transcription, audio size: ${pcmAudioData.size} bytes")
            
            if (pcmAudioData.size < 1000) {
                return@withContext SpeechResult.Error("Audio too short, please try again")
            }
            
            val wavData = pcmToWav(pcmAudioData)
            val boundary = "----WebKitFormBoundary${System.currentTimeMillis()}"
            val requestBody = buildMultipartBody(boundary, wavData)
            
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
    
    private fun buildMultipartBody(boundary: String, wavData: ByteArray): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        val writer = output.bufferedWriter()
        
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
        writer.write("zh\r\n")
        
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
            
            val requestJson = JSONObject().apply {
                put("model", modelId)
                put("messages", messages)
                put("temperature", temperature.toDouble())
                put("max_tokens", maxTokens)
                put("top_p", topP.toDouble())
                if (frequencyPenalty != 0.0f) put("frequency_penalty", frequencyPenalty.toDouble())
                if (presencePenalty != 0.0f) put("presence_penalty", presencePenalty.toDouble())
                put("stream", false)
            }
            
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
                        val text = choices?.optJSONObject(0)
                            ?.optJSONObject("message")
                            ?.optString("content", "")?.trim()
                        
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
            
            val messages = JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", imageContent)
                })
            }
            
            val requestJson = JSONObject().apply {
                put("model", modelId)
                put("messages", messages)
                put("max_tokens", maxTokens.coerceAtMost(4096))
                put("temperature", temperature.toDouble())
            }
            
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
                
                client.newCall(requestBuilder.build()).execute().use { response ->
                    val responseBody = response.body?.string()
                    
                    if (response.isSuccessful && responseBody != null) {
                        val json = JSONObject(responseBody)
                        val choices = json.optJSONArray("choices")
                        choices?.optJSONObject(0)
                            ?.optJSONObject("message")
                            ?.optString("content", "")?.trim()
                            ?: "Unable to analyze image."
                    } else {
                        Log.e(TAG, "Vision API error: ${response.code}")
                        "Image analysis failed."
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Vision error", e)
                "Image analysis error: ${e.message}"
            }
        }
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
                    put("max_tokens", 10)
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
