package com.example.rokidphone.service.ai

import android.util.Base64
import android.util.Log
import com.example.rokidphone.data.AiProvider
import com.example.rokidphone.service.SpeechResult
import kotlinx.coroutines.CoroutineDispatcher
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
    systemPrompt: String = "",
    temperature: Float = 0.7f,
    maxTokens: Int = 2048,
    topP: Float = 1.0f,
    internal val baseUrl: String = DEFAULT_BASE_URL,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : BaseAiService(apiKey, modelId, systemPrompt, temperature, maxTokens, topP), AiServiceProvider {
    
    companion object {
        private const val TAG = "GeminiService"
        internal const val DEFAULT_BASE_URL = "https://generativelanguage.googleapis.com/v1beta/models"
        private const val ERROR_API_KEY_NOT_CONFIGURED = "API key not configured. Please set up an API key in Settings."
    }
    
    override val provider = AiProvider.GEMINI
    
    private val apiUrl: String
        get() = "$baseUrl/$modelId:generateContent"
    
    /**
     * Speech Recognition - Gemini native audio support
     */
    override suspend fun transcribe(pcmAudioData: ByteArray, languageCode: String): SpeechResult {
        return withContext(ioDispatcher) {
            Log.d(TAG, "Starting transcription, audio size: ${pcmAudioData.size} bytes, language: $languageCode")
            
            if (apiKey.isBlank()) {
                Log.e(TAG, "API key is not configured")
                return@withContext SpeechResult.Error(ERROR_API_KEY_NOT_CONFIGURED)
            }
            
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
                                put("text", """Transcribe the speech in this audio to text.
The speaker is speaking ${getLanguageDisplayName(languageCode)}. Output the transcription in the original language spoken.
Rules:
1. Only output the actual spoken words, nothing else
2. If the audio contains no clear speech, only noise, silence, or unintelligible sounds, respond with exactly: Unable to recognize
3. Do not output timestamps, time codes, or numbers like "00:00"
4. Do not describe the audio or add any explanation
5. If you hear beeps, static, or mechanical sounds instead of speech, respond with: Unable to recognize""")
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
                    parseTranscriptionResponse(response, attempt)
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
     * Transcribe pre-encoded audio file (M4A, MP3, etc.)
     * Sends encoded audio directly to Gemini with the correct MIME type,
     * bypassing the PCM-to-WAV conversion used by transcribe().
     */
    override suspend fun transcribeAudioFile(audioData: ByteArray, mimeType: String, languageCode: String): SpeechResult {
        return withContext(ioDispatcher) {
            Log.d(TAG, "Starting audio file transcription, size: ${audioData.size} bytes, mimeType: $mimeType, language: $languageCode")
            
            if (apiKey.isBlank()) {
                Log.e(TAG, "API key is not configured")
                return@withContext SpeechResult.Error(ERROR_API_KEY_NOT_CONFIGURED)
            }
            
            if (audioData.size < 1000) {
                return@withContext SpeechResult.Error("Audio too short, please try again")
            }
            
            // Send encoded audio directly (no PCM-to-WAV conversion)
            val audioBase64 = Base64.encodeToString(audioData, Base64.NO_WRAP)
            
            val requestJson = JSONObject().apply {
                put("contents", JSONArray().apply {
                    put(JSONObject().apply {
                        put("parts", JSONArray().apply {
                            put(JSONObject().apply {
                                put("inline_data", JSONObject().apply {
                                    put("mime_type", mimeType)
                                    put("data", audioBase64)
                                })
                            })
                            put(JSONObject().apply {
                                put("text", """Transcribe the speech in this audio to text.
The speaker is speaking ${getLanguageDisplayName(languageCode)}. Output the transcription in the original language spoken.
Rules:
1. Only output the actual spoken words, nothing else
2. If the audio contains no clear speech, only noise, silence, or unintelligible sounds, respond with exactly: Unable to recognize
3. Do not output timestamps, time codes, or numbers like "00:00"
4. Do not describe the audio or add any explanation
5. If you hear beeps, static, or mechanical sounds instead of speech, respond with: Unable to recognize""")
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
                Log.d(TAG, "Sending audio file transcription request to Gemini (attempt $attempt)")
                
                val request = Request.Builder()
                    .url("$apiUrl?key=$apiKey")
                    .addHeader("Content-Type", "application/json")
                    .post(requestJson.toString().toRequestBody("application/json".toMediaType()))
                    .build()
                
                client.newCall(request).execute().use { response ->
                    parseTranscriptionResponse(response, attempt)
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
        return withContext(ioDispatcher) {
            Log.d(TAG, "Chat request: $userMessage")
            
            if (apiKey.isBlank()) {
                Log.e(TAG, "API key is not configured")
                return@withContext ERROR_API_KEY_NOT_CONFIGURED
            }
            
            val contents = JSONArray()
            
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
                put("systemInstruction", JSONObject().apply {
                    put("parts", JSONArray().put(JSONObject().put("text", getFullSystemPrompt())))
                })
                put("contents", contents)
                put("generationConfig", JSONObject().apply {
                    put("temperature", temperature.toDouble())
                    put("maxOutputTokens", maxTokens)
                    put("topP", topP.toDouble())
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
                        // Handle 503 (overloaded) with longer delay
                        if (response.code == 503 || response.code == 429) {
                            Log.w(TAG, "Server overloaded (${response.code}), will retry with longer delay...")
                            kotlinx.coroutines.delay(2000L * attempt) // Exponential backoff
                        }
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
        return withContext(ioDispatcher) {
            Log.d(TAG, "Image analysis request, size: ${imageData.size} bytes")
            
            if (apiKey.isBlank()) {
                Log.e(TAG, "API key is not configured")
                return@withContext "Sorry, unable to analyze this image. API key not configured."
            }
            
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
                    put("temperature", temperature.toDouble())
                    put("maxOutputTokens", maxTokens.coerceAtMost(4096))
                })
            }
            
            val result = executeWithRetry(TAG) { attempt ->
                Log.d(TAG, "Sending image analysis request to Gemini (attempt $attempt)")
                
                val request = Request.Builder()
                    .url("$apiUrl?key=$apiKey")
                    .addHeader("Content-Type", "application/json")
                    .post(requestJson.toString().toRequestBody("application/json".toMediaType()))
                    .build()
                
                try {
                    client.newCall(request).execute().use { response ->
                        val responseBody = response.body?.string()
                        
                        if (response.isSuccessful && responseBody != null) {
                            val json = JSONObject(responseBody)
                            val text = extractTextFromResponse(json)
                            if (text.isNullOrBlank()) {
                                Log.w(TAG, "Empty response from Gemini, response: $responseBody")
                                null
                            } else {
                                Log.d(TAG, "Image analysis successful, response length: ${text.length}")
                                text
                            }
                        } else {
                            Log.e(TAG, "API error: ${response.code}, body: $responseBody")
                            // Handle 503 (overloaded) with longer delay
                            if (response.code == 503 || response.code == 429) {
                                Log.w(TAG, "Server overloaded (${response.code}), will retry with longer delay...")
                                kotlinx.coroutines.delay(2000L * attempt) // Exponential backoff
                            }
                            // Parse error message if available
                            try {
                                val errorJson = JSONObject(responseBody ?: "{}")
                                val errorMsg = errorJson.optJSONObject("error")?.optString("message")
                                if (!errorMsg.isNullOrEmpty()) {
                                    Log.e(TAG, "Gemini API error message: $errorMsg")
                                }
                            } catch (e: Exception) { /* ignore parse errors */ }
                            null
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Network error during image analysis: ${e.message}", e)
                    throw e // rethrow for retry logic
                }
            }
            
            result ?: "Sorry, unable to analyze this image."
        }
    }
    
    /**
     * Shared helper: parse an HTTP response from a transcription request.
     * Returns the transcribed text on success, or null if the response is invalid.
     */
    private suspend fun parseTranscriptionResponse(
        response: okhttp3.Response,
        attempt: Int
    ): String? {
        val responseBody = response.body.string()
        if (!response.isSuccessful) {
            Log.e(TAG, "API error: ${response.code}, body: $responseBody")
            handleRetryableError(response.code, attempt)
            return null
        }
        val json = JSONObject(responseBody)
        val text = extractTextFromResponse(json)
        if (isValidTranscription(text)) {
            Log.d(TAG, "Transcription: $text")
            return text
        }
        if (!text.isNullOrEmpty()) {
            Log.d(TAG, "Filtered invalid transcription: $text")
        }
        return null
    }

    /**
     * Check whether a transcription result is valid spoken text
     */
    private fun isValidTranscription(text: String?): Boolean {
        return !text.isNullOrEmpty() &&
                !text.contains("Unable to recognize") &&
                !isGeminiErrorResponse(text) &&
                !isInvalidTranscription(text)
    }

    /**
     * Handle retryable HTTP error codes with exponential backoff
     */
    private suspend fun handleRetryableError(code: Int, attempt: Int) {
        if (code == 503 || code == 429) {
            Log.w(TAG, "Server overloaded ($code), will retry with longer delay...")
            kotlinx.coroutines.delay(2000L * attempt)
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
    
    /**
     * Detect Gemini error/apology responses that indicate no valid speech was recognized
     * These are full sentences from Gemini explaining it couldn't transcribe
     */
    private fun isGeminiErrorResponse(text: String): Boolean {
        val lowerText = text.lowercase()
        
        // Common Gemini apology patterns when it can't recognize speech
        val errorPatterns = listOf(
            "i'm sorry",
            "i am sorry", 
            "cannot recognize",
            "cannot provide a transcription",
            "cannot transcribe",
            "no discernible speech",
            "no speech",
            "only noise",
            "appears to contain only noise",
            "unable to transcribe",
            "no audio content",
            "empty audio",
            "silence"
        )
        
        if (errorPatterns.any { lowerText.contains(it) }) {
            Log.d(TAG, "Detected Gemini error response")
            return true
        }
        
        return false
    }
    
    /**
     * Detect invalid transcription patterns that indicate noise/silence was misinterpreted
     * These patterns include:
     * - Repeated timestamp patterns (00:00, 00:01, etc.)
     * - Repeated zeros or colons
     * - Very repetitive short patterns
     */
    private fun isInvalidTranscription(text: String): Boolean {
        val trimmedText = text.trim()
        
        // Empty or very short text
        if (trimmedText.length < 2) return true
        
        // Pattern: mostly zeros, colons, and spaces (like "00:00:00:00..." or "00:00 00:01...")
        val timestampPattern = Regex("^[0-9: \\n]+$")
        if (timestampPattern.matches(trimmedText)) {
            Log.d(TAG, "Detected timestamp-only pattern")
            return true
        }
        
        // Pattern: repeated timestamp format (00:00, 00:01, etc.)
        val repeatedTimestampPattern = Regex("(\\d{2}:\\d{2}[:\\s]*){3,}")
        if (repeatedTimestampPattern.containsMatchIn(trimmedText)) {
            Log.d(TAG, "Detected repeated timestamp pattern")
            return true
        }
        
        // Pattern: more than 50% of text is zeros, colons, or newlines
        val invalidChars = trimmedText.count { it == '0' || it == ':' || it == '\n' || it == ' ' }
        val ratio = invalidChars.toFloat() / trimmedText.length
        if (ratio > 0.7f && trimmedText.length > 10) {
            Log.d(TAG, "Detected high ratio of invalid chars: $ratio")
            return true
        }
        
        // Pattern: very repetitive content (same short sequence repeated many times)
        if (trimmedText.length >= 20) {
            val firstFew = trimmedText.take(5)
            val occurrences = trimmedText.windowed(5, 1).count { it == firstFew }
            if (occurrences > trimmedText.length / 8) {
                Log.d(TAG, "Detected highly repetitive pattern")
                return true
            }
        }
        
        return false
    }
    
    /**
     * Convert language code to display name for transcription prompt
     */
    private fun getLanguageDisplayName(languageCode: String): String {
        return when {
            languageCode.startsWith("zh-TW") || languageCode.startsWith("zh-Hant") -> "Traditional Chinese (繁體中文)"
            languageCode.startsWith("zh-CN") || languageCode.startsWith("zh-Hans") || languageCode.startsWith("zh") -> "Simplified Chinese (简体中文)"
            languageCode.startsWith("ja") -> "Japanese (日本語)"
            languageCode.startsWith("ko") -> "Korean (한국어)"
            languageCode.startsWith("en") -> "English"
            languageCode.startsWith("fr") -> "French (Français)"
            languageCode.startsWith("es") -> "Spanish (Español)"
            languageCode.startsWith("it") -> "Italian (Italiano)"
            languageCode.startsWith("ru") -> "Russian (Русский)"
            languageCode.startsWith("uk") -> "Ukrainian (Українська)"
            languageCode.startsWith("th") -> "Thai (ไทย)"
            languageCode.startsWith("vi") -> "Vietnamese (Tiếng Việt)"
            languageCode.startsWith("ar") -> "Arabic (العربية)"
            else -> "the language with code '$languageCode'"
        }
    }
}
