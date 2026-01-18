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
 * Gemini Service Implementation (Refactored)
 * Supports Gemini 2.5 Pro, 2.5 Flash, 2.5 Flash-Lite models
 * 
 * API Docs: https://ai.google.dev/docs
 * 
 * Features:
 * - Native audio input support (speech recognition)
 * - Multimodal support (images, audio, video, PDF)
 * - Long context support
 */
class GeminiService(
    apiKey: String,
    modelId: String = "gemini-2.5-flash",
    systemPrompt: String = "You are a friendly AI assistant. Please answer questions concisely."
) : BaseAiService(apiKey, modelId, systemPrompt), AiServiceProvider {
    
    companion object {
        private const val TAG = "GeminiService"
        private const val BASE_URL = "https://generativelanguage.googleapis.com/v1beta/models"
    }
    
    override val provider = AiProvider.GEMINI
    
    private val apiUrl: String
        get() = "$BASE_URL/$modelId:generateContent"
    
    /**
     * Speech Recognition - Gemini native audio support
     */
    override suspend fun transcribe(pcmAudioData: ByteArray): SpeechResult {
        return withContext(Dispatchers.IO) {
            Log.d(TAG, "Starting transcription, audio size: ${pcmAudioData.size} bytes")
            
            if (pcmAudioData.size < 1000) {
                return@withContext SpeechResult.Error("Audio too short, please try again")
            }
            
            val wavData = pcmToWav(pcmAudioData)
            val audioBase64 = Base64.encodeToString(wavData, Base64.NO_WRAP)
            
            val requestJson = JSONObject().apply {
                put("contents", JSONArray().apply {
                    put(JSONObject().apply {
                        put("parts", JSONArray().apply {
                            put(JSONObject().apply {
                                put("inline_data", JSONObject().apply {
                                    put("mime_type", "audio/wav")
                                    put("data", audioBase64)
                                })
                            })
                            put(JSONObject().apply {
                                put("text", "Please transcribe this audio into text. Only output the transcribed text content, do not add any explanation. If unclear, respond with 'Unable to recognize'.")
                            })
                        })
                    })
                })
                put("generationConfig", JSONObject().apply {
                    put("temperature", 0.1)
                    put("maxOutputTokens", 500)
                })
            }
            
            val result = executeWithRetry(TAG) { attempt ->
                Log.d(TAG, "Sending transcription request to Gemini (attempt $attempt)")
                
                val request = Request.Builder()
                    .url("$apiUrl?key=$apiKey")
                    .addHeader("Content-Type", "application/json")
                    .post(requestJson.toString().toRequestBody("application/json".toMediaType()))
                    .build()
                
                client.newCall(request).execute().use { response ->
                    val responseBody = response.body?.string()
                    
                    if (response.isSuccessful && responseBody != null) {
                        val json = JSONObject(responseBody)
                        val text = extractTextFromResponse(json)
                        
                        if (text.isNullOrEmpty() || text.contains("Unable to recognize")) {
                            null
                        } else {
                            Log.d(TAG, "Transcription: $text")
                            text
                        }
                    } else {
                        Log.e(TAG, "API error: ${response.code}, body: $responseBody")
                        null
                    }
                }
            }
            
            if (result != null) {
                SpeechResult.Success(result)
            } else {
                SpeechResult.Error("Unable to recognize speech, please try again")
            }
        }
    }
    
    /**
     * Text Chat
     */
    override suspend fun chat(userMessage: String): String {
        return withContext(Dispatchers.IO) {
            Log.d(TAG, "Chat request: $userMessage")
            
            val contents = JSONArray()
            
            // System prompt
            contents.put(JSONObject().apply {
                put("role", "user")
                put("parts", JSONArray().apply {
                    put(JSONObject().put("text", getFullSystemPrompt()))
                })
            })
            contents.put(JSONObject().apply {
                put("role", "model")
                put("parts", JSONArray().apply {
                    put(JSONObject().put("text", "Understood, I will assist you accordingly!"))
                })
            })
            
            // Conversation history
            for ((role, content) in conversationHistory.takeLast(6)) {
                val geminiRole = if (role == "user") "user" else "model"
                contents.put(JSONObject().apply {
                    put("role", geminiRole)
                    put("parts", JSONArray().apply {
                        put(JSONObject().put("text", content))
                    })
                })
            }
            
            // Current user message
            contents.put(JSONObject().apply {
                put("role", "user")
                put("parts", JSONArray().apply {
                    put(JSONObject().put("text", userMessage))
                })
            })
            
            val requestJson = JSONObject().apply {
                put("contents", contents)
                put("generationConfig", JSONObject().apply {
                    put("temperature", 0.7)
                    put("maxOutputTokens", 500)
                })
            }
            
            val result = executeWithRetry(TAG) { attempt ->
                Log.d(TAG, "Sending chat request to Gemini (attempt $attempt)")
                
                val request = Request.Builder()
                    .url("$apiUrl?key=$apiKey")
                    .addHeader("Content-Type", "application/json")
                    .post(requestJson.toString().toRequestBody("application/json".toMediaType()))
                    .build()
                
                client.newCall(request).execute().use { response ->
                    val responseBody = response.body?.string()
                    
                    if (response.isSuccessful && responseBody != null) {
                        val json = JSONObject(responseBody)
                        val text = extractTextFromResponse(json)
                        
                        if (!text.isNullOrEmpty()) {
                            addToHistory(userMessage, text)
                            Log.d(TAG, "Gemini response: $text")
                            text
                        } else null
                    } else {
                        Log.e(TAG, "API error: ${response.code}, body: $responseBody")
                        null
                    }
                }
            }
            
            result ?: "Sorry, AI service is temporarily unavailable. Please try again later."
        }
    }
    
    /**
     * Image Analysis
     */
    override suspend fun analyzeImage(imageData: ByteArray, prompt: String): String {
        return withContext(Dispatchers.IO) {
            Log.d(TAG, "Image analysis request, size: ${imageData.size} bytes")
            
            val imageBase64 = Base64.encodeToString(imageData, Base64.NO_WRAP)
            
            val requestJson = JSONObject().apply {
                put("contents", JSONArray().apply {
                    put(JSONObject().apply {
                        put("parts", JSONArray().apply {
                            put(JSONObject().apply {
                                put("inline_data", JSONObject().apply {
                                    put("mime_type", "image/jpeg")
                                    put("data", imageBase64)
                                })
                            })
                            put(JSONObject().apply {
                                put("text", prompt)
                            })
                        })
                    })
                })
                put("generationConfig", JSONObject().apply {
                    put("temperature", 0.4)
                    put("maxOutputTokens", 500)
                })
            }
            
            val result = executeWithRetry(TAG) { attempt ->
                Log.d(TAG, "Sending image analysis request to Gemini (attempt $attempt)")
                
                val request = Request.Builder()
                    .url("$apiUrl?key=$apiKey")
                    .addHeader("Content-Type", "application/json")
                    .post(requestJson.toString().toRequestBody("application/json".toMediaType()))
                    .build()
                
                client.newCall(request).execute().use { response ->
                    val responseBody = response.body?.string()
                    
                    if (response.isSuccessful && responseBody != null) {
                        val json = JSONObject(responseBody)
                        extractTextFromResponse(json)
                    } else {
                        Log.e(TAG, "API error: ${response.code}, body: $responseBody")
                        null
                    }
                }
            }
            
            result ?: "Sorry, unable to analyze this image."
        }
    }
    
    private fun extractTextFromResponse(json: JSONObject): String? {
        val candidates = json.optJSONArray("candidates")
        if (candidates != null && candidates.length() > 0) {
            val content = candidates.getJSONObject(0).optJSONObject("content")
            val parts = content?.optJSONArray("parts")
            if (parts != null && parts.length() > 0) {
                return parts.getJSONObject(0).optString("text", "").trim()
            }
        }
        return null
    }
}
