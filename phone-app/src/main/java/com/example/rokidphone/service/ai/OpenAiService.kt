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
 * OpenAI Service Implementation
 * Supports GPT-5.2, GPT-4o, o3-pro, o4-mini and other models
 * 
 * API Docs: https://platform.openai.com/docs/api-reference
 */
class OpenAiService(
    apiKey: String,
    modelId: String = "gpt-4o",
    systemPrompt: String = "You are a friendly AI assistant. Please answer questions concisely."
) : BaseAiService(apiKey, modelId, systemPrompt), AiServiceProvider {
    
    companion object {
        private const val TAG = "OpenAiService"
        private const val BASE_URL = "https://api.openai.com/v1"
        private const val WHISPER_MODEL = "whisper-1"
    }
    
    override val provider = AiProvider.OPENAI
    
    /**
     * Speech Recognition - Using Whisper API
     */
    override suspend fun transcribe(pcmAudioData: ByteArray): SpeechResult {
        return withContext(Dispatchers.IO) {
            Log.d(TAG, "Starting transcription, audio size: ${pcmAudioData.size} bytes")
            
            if (pcmAudioData.size < 1000) {
                return@withContext SpeechResult.Error("Audio too short, please try again")
            }
            
            val wavData = pcmToWav(pcmAudioData)
            
            // Use multipart form data to upload audio
            val boundary = "----WebKitFormBoundary${System.currentTimeMillis()}"
            val requestBody = buildMultipartBody(boundary, wavData)
            
            val result = executeWithRetry(TAG) { attempt ->
                Log.d(TAG, "Sending Whisper request (attempt $attempt)")
                
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
        writer.write("--$boundary\r\n")
        writer.write("Content-Disposition: form-data; name=\"model\"\r\n\r\n")
        writer.write("$WHISPER_MODEL\r\n")
        
        // language field (optional, helps accuracy)
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
                Log.d(TAG, "Sending chat request (attempt $attempt)")
                
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
                            Log.d(TAG, "AI response: $text")
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
     * Image Analysis - Using Vision API
     */
    override suspend fun analyzeImage(imageData: ByteArray, prompt: String): String {
        return withContext(Dispatchers.IO) {
            Log.d(TAG, "Image analysis request, size: ${imageData.size} bytes")
            
            val imageBase64 = Base64.encodeToString(imageData, Base64.NO_WRAP)
            
            val messages = JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "system")
                    put("content", "You are an image analysis assistant. Please provide objective descriptions based on the image content. If unable to determine, please explain.")
                })
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
                put("model", "gpt-4o")  // Vision requires a model that supports images
                put("messages", messages)
                put("max_tokens", 500)
            }
            
            val result = executeWithRetry(TAG) { attempt ->
                Log.d(TAG, "Sending image analysis request (attempt $attempt)")
                
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
