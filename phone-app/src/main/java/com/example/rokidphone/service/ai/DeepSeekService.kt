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
 * DeepSeek Service Implementation
 * OpenAI compatible API, affordable pricing, supports Chinese
 * 
 * API Docs: https://platform.deepseek.com/api-docs
 * 
 * Features:
 * - OpenAI compatible format
 * - Supports deepseek-chat, deepseek-reasoner and other models
 * - Pricing is about 1/10 of OpenAI
 */
class DeepSeekService(
    apiKey: String,
    modelId: String = "deepseek-chat",
    systemPrompt: String = "You are a friendly AI assistant. Please answer questions concisely."
) : BaseAiService(apiKey, modelId, systemPrompt), AiServiceProvider {
    
    companion object {
        private const val TAG = "DeepSeekService"
        private const val BASE_URL = "https://api.deepseek.com/v1"
    }
    
    override val provider = AiProvider.DEEPSEEK
    
    /**
     * Speech Recognition - DeepSeek currently does not support STT
     * Recommend using with other STT services (like Whisper)
     */
    override suspend fun transcribe(pcmAudioData: ByteArray): SpeechResult {
        return SpeechResult.Error("DeepSeek does not support speech recognition, please use another service")
    }
    
    /**
     * Text Chat - Using Chat Completions API
     */
    override suspend fun chat(userMessage: String): String {
        return withContext(Dispatchers.IO) {
            Log.d(TAG, "Chat request: $userMessage")
            
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
                put("temperature", 0.7)
                put("max_tokens", 500)
                put("stream", false)
            }
            
            val result = executeWithRetry(TAG) { attempt ->
                Log.d(TAG, "Sending chat request to DeepSeek (attempt $attempt)")
                
                val request = Request.Builder()
                    .url("$BASE_URL/chat/completions")
                    .addHeader("Authorization", "Bearer $apiKey")
                    .addHeader("Content-Type", "application/json")
                    .post(requestJson.toString().toRequestBody("application/json".toMediaType()))
                    .build()
                
                client.newCall(request).execute().use { response ->
                    val responseBody = response.body?.string()
                    
                    if (response.isSuccessful && responseBody != null) {
                        val json = JSONObject(responseBody)
                        val choices = json.optJSONArray("choices")
                        val text = choices?.optJSONObject(0)
                            ?.optJSONObject("message")
                            ?.optString("content", "")?.trim()
                        
                        if (!text.isNullOrEmpty()) {
                            addToHistory(userMessage, text)
                            Log.d(TAG, "DeepSeek response: $text")
                            text
                        } else null
                    } else {
                        Log.e(TAG, "API error: ${response.code}, body: $responseBody")
                        null
                    }
                }
            }
            
            result ?: "Sorry, DeepSeek service is temporarily unavailable. Please try again later."
        }
    }
    
    /**
     * Image Analysis - DeepSeek currently does not support vision
     */
    override suspend fun analyzeImage(imageData: ByteArray, prompt: String): String {
        return "DeepSeek currently does not support image analysis."
    }
}
