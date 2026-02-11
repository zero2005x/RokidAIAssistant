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
 * Groq Service Implementation
 * Ultra-fast AI inference service, OpenAI compatible API
 * 
 * API Docs: https://console.groq.com/docs
 * 
 * Features:
 * - Ultra-fast inference speed (hardware accelerated)
 * - OpenAI compatible format
 * - Supports Llama, Mixtral, Gemma and other open source models
 * - Supports Whisper speech recognition
 */
class GroqService(
    apiKey: String,
    modelId: String = "llama-3.3-70b-versatile",
    systemPrompt: String = ""
) : BaseAiService(apiKey, modelId, systemPrompt), AiServiceProvider {
    
    companion object {
        private const val TAG = "GroqService"
        private const val BASE_URL = "https://api.groq.com/openai/v1"
        private const val WHISPER_MODEL = "whisper-large-v3-turbo"
    }
    
    override val provider = AiProvider.GROQ
    
    /**
     * Speech Recognition - Using Groq Whisper API (ultra-fast)
     */
    override suspend fun transcribe(pcmAudioData: ByteArray, languageCode: String): SpeechResult {
        return withContext(Dispatchers.IO) {
            Log.d(TAG, "Starting transcription with Groq Whisper, audio size: ${pcmAudioData.size} bytes")
            
            if (pcmAudioData.size < 1000) {
                return@withContext SpeechResult.Error("Audio too short, please try again")
            }
            
            val wavData = pcmToWav(pcmAudioData)
            
            // Use multipart form data to upload audio
            val boundary = "----WebKitFormBoundary${System.currentTimeMillis()}"
            val requestBody = buildMultipartBody(boundary, wavData, languageCode)
            
            val result = executeWithRetry(TAG) { attempt ->
                Log.d(TAG, "Sending Groq Whisper request (attempt $attempt)")
                
                val request = Request.Builder()
                    .url("$BASE_URL/audio/transcriptions")
                    .addHeader("Authorization", "Bearer $apiKey")
                    .addHeader("Content-Type", "multipart/form-data; boundary=$boundary")
                    .post(requestBody.toRequestBody("multipart/form-data; boundary=$boundary".toMediaType()))
                    .build()
                
                client.newCall(request).execute().use { response ->
                    val responseBody = response.body?.string()
                    
                    if (response.isSuccessful && responseBody != null) {
                        val json = JSONObject(responseBody)
                        val text = json.optString("text", "").trim()
                        
                        if (text.isEmpty()) {
                            Log.w(TAG, "Empty transcription result")
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
    
    private fun buildMultipartBody(boundary: String, wavData: ByteArray, languageCode: String): ByteArray {
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
        writer.write("--$boundary\r\n")
        writer.write("Content-Disposition: form-data; name=\"model\"\r\n\r\n")
        writer.write("$WHISPER_MODEL\r\n")
        
        // language field
        writer.write("--$boundary\r\n")
        writer.write("Content-Disposition: form-data; name=\"language\"\r\n\r\n")
        writer.write("${languageCode.substringBefore("-")}\r\n")
        
        writer.write("--$boundary--\r\n")
        writer.flush()
        
        return output.toByteArray()
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
            }
            
            val result = executeWithRetry(TAG) { attempt ->
                Log.d(TAG, "Sending chat request to Groq (attempt $attempt)")
                
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
                            Log.d(TAG, "Groq response: $text")
                            text
                        } else null
                    } else {
                        Log.e(TAG, "API error: ${response.code}, body: $responseBody")
                        null
                    }
                }
            }
            
            result ?: "Sorry, Groq service is temporarily unavailable. Please try again later."
        }
    }
    
    /**
     * Image Analysis - Groq uses Llama Vision support
     */
    override suspend fun analyzeImage(imageData: ByteArray, prompt: String): String {
        return withContext(Dispatchers.IO) {
            Log.d(TAG, "Image analysis request, size: ${imageData.size} bytes")
            
            val imageBase64 = android.util.Base64.encodeToString(imageData, android.util.Base64.NO_WRAP)
            
            val messages = JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", JSONArray().apply {
                        put(JSONObject().apply {
                            put("type", "image_url")
                            put("image_url", JSONObject().apply {
                                put("url", "data:image/jpeg;base64,$imageBase64")
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
                put("model", "llama-3.2-90b-vision-preview")  // Llama Vision model
                put("messages", messages)
                put("max_tokens", 500)
            }
            
            val result = executeWithRetry(TAG) { attempt ->
                Log.d(TAG, "Sending image analysis request to Groq (attempt $attempt)")
                
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
                        choices?.optJSONObject(0)
                            ?.optJSONObject("message")
                            ?.optString("content", "")?.trim()
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
