package com.example.rokidphone.service

import android.util.Base64
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Speech error codes for localization support
 */
enum class SpeechErrorCode {
    AUDIO_TOO_SHORT,
    UNABLE_TO_RECOGNIZE,
    UPLOAD_FAILED,
    CREATE_TRANSCRIPT_FAILED,
    TRANSCRIPTION_TIMEOUT,
    TRANSCRIPTION_ERROR,
    SERVICE_UNAVAILABLE,
    NO_SPEECH_DETECTED,
    NOT_SUPPORTED,
    PROVIDER_NOT_SUPPORTED,
    RECOGNITION_FAILED,
    NETWORK_ERROR,
    UNKNOWN;
    
    /**
     * Get the string resource ID for this error code
     */
    fun getStringResId(): Int {
        return when (this) {
            AUDIO_TOO_SHORT -> com.example.rokidphone.R.string.stt_error_audio_too_short
            UNABLE_TO_RECOGNIZE -> com.example.rokidphone.R.string.stt_error_unable_to_recognize
            UPLOAD_FAILED -> com.example.rokidphone.R.string.stt_error_upload_failed
            CREATE_TRANSCRIPT_FAILED -> com.example.rokidphone.R.string.stt_error_create_transcript_failed
            TRANSCRIPTION_TIMEOUT -> com.example.rokidphone.R.string.stt_error_transcription_timeout
            TRANSCRIPTION_ERROR -> com.example.rokidphone.R.string.stt_error_transcription_error
            SERVICE_UNAVAILABLE -> com.example.rokidphone.R.string.stt_error_service_unavailable
            NO_SPEECH_DETECTED -> com.example.rokidphone.R.string.stt_error_no_speech_detected
            NOT_SUPPORTED -> com.example.rokidphone.R.string.stt_error_not_supported
            PROVIDER_NOT_SUPPORTED -> com.example.rokidphone.R.string.stt_error_provider_not_supported
            RECOGNITION_FAILED -> com.example.rokidphone.R.string.stt_error_recognition_failed
            NETWORK_ERROR -> com.example.rokidphone.R.string.stt_error_transcription_error
            UNKNOWN -> com.example.rokidphone.R.string.stt_error_transcription_error
        }
    }
    
    /**
     * Check if this error code uses a format string (has %s placeholder)
     */
    fun requiresDetail(): Boolean {
        return this in listOf(TRANSCRIPTION_ERROR, PROVIDER_NOT_SUPPORTED, RECOGNITION_FAILED)
    }
}

/**
 * Speech recognition result
 */
sealed class SpeechResult {
    data class Success(val text: String) : SpeechResult()
    data class Error(
        val message: String, 
        val isNetworkError: Boolean = false,
        val errorCode: SpeechErrorCode? = null,
        val errorDetail: String? = null
    ) : SpeechResult() {
        /**
         * Get localized error message using Context
         */
        fun getLocalizedMessage(context: android.content.Context): String {
            return when {
                errorCode != null -> {
                    val resId = errorCode.getStringResId()
                    if (errorDetail != null && errorCode.requiresDetail()) {
                        context.getString(resId, errorDetail)
                    } else if (errorCode.requiresDetail()) {
                        // If format string but no detail, use a generic error message
                        context.getString(resId, "")
                    } else {
                        context.getString(resId)
                    }
                }
                else -> message
            }
        }
    }
}

/**
 * Gemini Speech Service
 * Handles speech recognition and AI conversation on phone side
 */
class GeminiSpeechService(
    private val apiKey: String,
    private val modelId: String = "gemini-2.5-flash",
    private val systemPrompt: String = ""
) {
    companion object {
        private const val TAG = "GeminiSpeechService"
        private const val BASE_API_URL = "https://generativelanguage.googleapis.com/v1beta/models"
        private const val MAX_RETRIES = 3
        private const val RETRY_DELAY_MS = 1000L
    }
    
    // Build API URL based on model ID
    private val apiUrl: String
        get() = "$BASE_API_URL/$modelId:generateContent"
    
    private val client = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .build()
    
    // Conversation history
    private val conversationHistory = mutableListOf<Pair<String, String>>() // (role, content)
    
    /**
     * Convert PCM audio data to WAV format
     */
    private fun pcmToWav(pcmData: ByteArray, sampleRate: Int = 16000, channels: Int = 1, bitsPerSample: Int = 16): ByteArray {
        val byteRate = sampleRate * channels * bitsPerSample / 8
        val blockAlign = channels * bitsPerSample / 8
        val dataSize = pcmData.size
        val totalSize = 36 + dataSize
        
        val output = ByteArrayOutputStream()
        
        // RIFF header
        output.write("RIFF".toByteArray())
        output.write(intToBytes(totalSize, 4))
        output.write("WAVE".toByteArray())
        
        // fmt chunk
        output.write("fmt ".toByteArray())
        output.write(intToBytes(16, 4))
        output.write(intToBytes(1, 2))
        output.write(intToBytes(channels, 2))
        output.write(intToBytes(sampleRate, 4))
        output.write(intToBytes(byteRate, 4))
        output.write(intToBytes(blockAlign, 2))
        output.write(intToBytes(bitsPerSample, 2))
        
        // data chunk
        output.write("data".toByteArray())
        output.write(intToBytes(dataSize, 4))
        output.write(pcmData)
        
        return output.toByteArray()
    }
    
    private fun intToBytes(value: Int, numBytes: Int): ByteArray {
        val bytes = ByteArray(numBytes)
        for (i in 0 until numBytes) {
            bytes[i] = (value shr (8 * i) and 0xFF).toByte()
        }
        return bytes
    }
    
    /**
     * Speech recognition - Convert audio to text (with auto-retry mechanism)
     */
    suspend fun transcribe(pcmAudioData: ByteArray): SpeechResult {
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
                                put("text", """Transcribe the speech in this audio to text.
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
            
            // API request with retry logic
            var lastException: Exception? = null
            
            for (attempt in 1..MAX_RETRIES) {
                try {
                    Log.d(TAG, "Sending transcription request to model: $modelId (attempt $attempt/$MAX_RETRIES)")
                    
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
                            
                            if (text.isNullOrEmpty() || 
                                text.contains("Unable to recognize") || 
                                isGeminiErrorResponse(text) ||
                                isInvalidTranscription(text)) {
                                return@withContext SpeechResult.Error("Unable to recognize speech, please try again")
                            }
                            
                            Log.d(TAG, "Transcription: $text")
                            return@withContext SpeechResult.Success(text)
                        } else {
                            Log.e(TAG, "API error: ${response.code}, body: $responseBody")
                            // API error - do not retry
                            return@withContext SpeechResult.Error("AI service temporarily unavailable, please try again later")
                        }
                    }
                } catch (e: Exception) {
                    lastException = e
                    val isNetworkError = isNetworkException(e)
                    
                    if (isNetworkError && attempt < MAX_RETRIES) {
                        Log.w(TAG, "Network error on attempt $attempt, retrying in ${RETRY_DELAY_MS}ms...", e)
                        kotlinx.coroutines.delay(RETRY_DELAY_MS * attempt) // Exponential backoff
                        continue
                    } else {
                        break
                    }
                }
            }
            
            // All retry attempts failed
            Log.e(TAG, "All retry attempts failed", lastException)
            return@withContext SpeechResult.Error(
                getFriendlyErrorMessage(lastException),
                isNetworkError = isNetworkException(lastException)
            )
        }
    }
    
    /**
     * Check if the exception is network-related
     */
    private fun isNetworkException(e: Exception?): Boolean {
        return e is UnknownHostException ||
               e is ConnectException ||
               e is SocketTimeoutException ||
               e?.message?.contains("Network is unreachable") == true ||
               e?.message?.contains("Failed to connect") == true
    }
    
    /**
     * Convert technical errors to user-friendly messages
     */
    private fun getFriendlyErrorMessage(e: Exception?): String {
        return when {
            e == null -> "Unknown error occurred, please try again"
            e is UnknownHostException -> "Network connection failed, please check your network settings"
            e is SocketTimeoutException -> "Connection timed out, please check your network and try again"
            e is ConnectException -> "Cannot connect to server, please check your network settings"
            e.message?.contains("Network is unreachable") == true -> "Network unreachable, please ensure WiFi or mobile data is connected"
            e.message?.contains("Failed to connect") == true -> "Connection failed, please try again later"
            e.message?.contains("timeout") == true -> "Connection timed out, please try again later"
            else -> "An error occurred, please try again"
        }
    }
    
    /**
     * AI Chat - Process user questions and return responses (with auto-retry mechanism)
     */
    suspend fun chat(userMessage: String): String {
        return withContext(Dispatchers.IO) {
            Log.d(TAG, "Chat request: $userMessage")
            
            // Get current date and time
            val dateFormat = SimpleDateFormat("yyyy-MM-dd EEEE HH:mm", Locale.getDefault())
            val currentDateTime = dateFormat.format(Date())
                
                // Build system prompt with date
                val fullSystemPrompt = "$systemPrompt\n\nCurrent date and time: $currentDateTime"
                
                // Build conversation with history
                val contents = JSONArray()
                
                // System prompt - use systemPrompt with date
                contents.put(JSONObject().apply {
                    put("role", "user")
                    put("parts", JSONArray().apply {
                        put(JSONObject().put("text", fullSystemPrompt))
                    })
                })
                contents.put(JSONObject().apply {
                    put("role", "model")
                    put("parts", JSONArray().apply {
                        put(JSONObject().put("text", "OK, I will assist you as instructed!"))
                    })
                })
                
                // Add conversation history
                for ((role, content) in conversationHistory.takeLast(6)) {
                    contents.put(JSONObject().apply {
                        put("role", role)
                        put("parts", JSONArray().apply {
                            put(JSONObject().put("text", content))
                        })
                    })
                }
                
                // Add current user message
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
                        put("maxOutputTokens", 200)
                    })
                }
                
                // API request with retry logic
                var lastException: Exception? = null
                
                for (attempt in 1..MAX_RETRIES) {
                    try {
                        Log.d(TAG, "Sending chat request (attempt $attempt/$MAX_RETRIES)")
                        
                        val request = Request.Builder()
                            .url("$apiUrl?key=$apiKey")
                            .addHeader("Content-Type", "application/json")
                            .post(requestJson.toString().toRequestBody("application/json".toMediaType()))
                            .build()
                        
                        client.newCall(request).execute().use { response ->
                            val responseBody = response.body?.string()
                            
                            if (response.isSuccessful && responseBody != null) {
                                val json = JSONObject(responseBody)
                                val text = extractTextFromResponse(json) ?: "Sorry, I cannot answer this question."
                                
                                // Record conversation history
                                conversationHistory.add("user" to userMessage)
                                conversationHistory.add("model" to text)
                                
                                // Limit history length
                                while (conversationHistory.size > 10) {
                                    conversationHistory.removeAt(0)
                                }
                                
                                Log.d(TAG, "AI response: $text")
                                return@withContext text
                            } else {
                                Log.e(TAG, "Chat API error: ${response.code}")
                                return@withContext "Sorry, AI service is temporarily unavailable. Please try again later."
                            }
                        }
                    } catch (e: Exception) {
                        lastException = e
                        val isNetworkError = isNetworkException(e)
                        
                        if (isNetworkError && attempt < MAX_RETRIES) {
                            Log.w(TAG, "Network error on attempt $attempt, retrying...", e)
                            kotlinx.coroutines.delay(RETRY_DELAY_MS * attempt)
                            continue
                        } else {
                            break
                        }
                    }
                }
                
                // All retry attempts failed
                Log.e(TAG, "All chat retry attempts failed", lastException)
                return@withContext "Sorry, ${getFriendlyErrorMessage(lastException)}"
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
            Log.d(TAG, "Detected Gemini error response: $text")
            return true
        }
        
        return false
    }
    
    /**
     * Check if transcription result is invalid (timestamps, repeated zeros, etc.)
     * This happens when Gemini misinterprets silence/noise as timestamps
     */
    private fun isInvalidTranscription(text: String): Boolean {
        // Pattern 1: Only contains numbers, colons, spaces, and newlines (timestamp-like)
        val timestampOnlyPattern = Regex("^[0-9: \\n]+$")
        if (timestampOnlyPattern.matches(text)) {
            Log.d(TAG, "Invalid transcription detected: timestamp-only pattern")
            return true
        }
        
        // Pattern 2: Contains repeated timestamp patterns like "00:00" or "00:06 00:07 00:08"
        val repeatedTimestampPattern = Regex("(\\d{2}:\\d{2}[:\\s]*){3,}")
        if (repeatedTimestampPattern.containsMatchIn(text)) {
            Log.d(TAG, "Invalid transcription detected: repeated timestamp pattern")
            return true
        }
        
        // Pattern 3: High ratio of zeros, colons, or newlines (likely noise interpreted as timestamps)
        val invalidChars = text.count { it == '0' || it == ':' || it == '\n' }
        if (text.length > 10 && invalidChars.toFloat() / text.length > 0.7f) {
            Log.d(TAG, "Invalid transcription detected: high ratio of zeros/colons/newlines")
            return true
        }
        
        // Pattern 4: Very repetitive content (same short sequence repeated many times)
        if (text.length > 20) {
            val firstFew = text.take(5)
            val occurrences = text.windowed(5).count { it == firstFew }
            if (occurrences > text.length / 10) {
                Log.d(TAG, "Invalid transcription detected: highly repetitive content")
                return true
            }
        }
        
        return false
    }
    
    /**
     * Clear conversation history
     */
    fun clearHistory() {
        conversationHistory.clear()
    }
}
