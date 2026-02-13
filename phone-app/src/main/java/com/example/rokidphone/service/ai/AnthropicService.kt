package com.example.rokidphone.service.ai

import android.util.Base64
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
 * Anthropic Claude Service Implementation
 * 
 * API Docs: https://docs.anthropic.com/claude/reference
 * 
 * Features:
 * - Powerful reasoning capabilities
 * - Vision support (Claude 3 series)
 * - Long context support
 */
class AnthropicService(
    apiKey: String,
    modelId: String = "claude-sonnet-4-5",
    systemPrompt: String = "",
    temperature: Float = 0.7f,
    maxTokens: Int = 2048,
    topP: Float = 1.0f,
    internal val baseUrl: String = DEFAULT_BASE_URL
) : BaseAiService(apiKey, modelId, systemPrompt, temperature, maxTokens, topP), AiServiceProvider {
    
    companion object {
        private const val TAG = "AnthropicService"
        internal const val DEFAULT_BASE_URL = "https://api.anthropic.com/v1"
        internal const val API_VERSION = "2023-06-01"
    }
    
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
            Log.d(TAG, "Chat request: $userMessage")
            
            val messages = JSONArray().apply {
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
                put("max_tokens", maxTokens)
                put("temperature", temperature.toDouble())
                put("top_p", topP.toDouble())
                put("system", getFullSystemPrompt())
                put("messages", messages)
            }
            
            val result = executeWithRetry(TAG) { attempt ->
                Log.d(TAG, "Sending chat request to Anthropic (attempt $attempt)")
                
                val request = Request.Builder()
                    .url("$baseUrl/messages")
                    .addHeader("x-api-key", apiKey)
                    .addHeader("anthropic-version", API_VERSION)
                    .addHeader("Content-Type", "application/json")
                    .post(requestJson.toString().toRequestBody("application/json".toMediaType()))
                    .build()
                
                client.newCall(request).execute().use { response ->
                    val responseBody = response.body?.string()
                    
                    if (response.isSuccessful && responseBody != null) {
                        val json = JSONObject(responseBody)
                        val content = json.optJSONArray("content")
                        val text = content?.optJSONObject(0)?.optString("text", "")?.trim()
                        
                        if (!text.isNullOrEmpty()) {
                            addToHistory(userMessage, text)
                            Log.d(TAG, "Claude response: $text")
                            text
                        } else null
                    } else {
                        Log.e(TAG, "API error: ${response.code}, body: $responseBody")
                        null
                    }
                }
            }
            
            result ?: "Sorry, Claude service is temporarily unavailable. Please try again later."
        }
    }
    
    /**
     * Image Analysis - Using Vision API
     */
    override suspend fun analyzeImage(imageData: ByteArray, prompt: String): String {
        return withContext(Dispatchers.IO) {
            Log.d(TAG, "Image analysis request, size: ${imageData.size} bytes")
            
            val imageBase64 = Base64.encodeToString(imageData, Base64.NO_WRAP)
            
            val messages = JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", JSONArray().apply {
                        put(JSONObject().apply {
                            put("type", "image")
                            put("source", JSONObject().apply {
                                put("type", "base64")
                                put("media_type", "image/jpeg")
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
                put("max_tokens", maxTokens.coerceAtMost(4096))
                put("system", "You are an image analysis assistant. Please provide objective descriptions based on the image content. If unable to determine, please explain.")
                put("messages", messages)
            }
            
            val result = executeWithRetry(TAG) { attempt ->
                Log.d(TAG, "Sending image analysis request to Anthropic (attempt $attempt)")
                
                val request = Request.Builder()
                    .url("$baseUrl/messages")
                    .addHeader("x-api-key", apiKey)
                    .addHeader("anthropic-version", API_VERSION)
                    .addHeader("Content-Type", "application/json")
                    .post(requestJson.toString().toRequestBody("application/json".toMediaType()))
                    .build()
                
                client.newCall(request).execute().use { response ->
                    val responseBody = response.body?.string()
                    
                    if (response.isSuccessful && responseBody != null) {
                        val json = JSONObject(responseBody)
                        val content = json.optJSONArray("content")
                        content?.optJSONObject(0)?.optString("text", "")?.trim()
                    } else {
                        Log.e(TAG, "API error: ${response.code}, body: $responseBody")
                        null
                    }
                }
            }
            
            result ?: "Sorry, unable to analyze this image."
        }
    }
}
