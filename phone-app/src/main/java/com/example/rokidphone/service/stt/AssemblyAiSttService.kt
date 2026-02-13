package com.example.rokidphone.service.stt

import android.util.Log
import com.example.rokidphone.service.SpeechErrorCode
import com.example.rokidphone.service.SpeechResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/**
 * AssemblyAI Speech-to-Text Service
 * 
 * API Docs: https://www.assemblyai.com/docs/getting-started/transcribe-an-audio-file
 * 
 * Authentication: API Key in Authorization header (direct value, not Bearer)
 * Format: Authorization: YOUR_API_KEY
 * 
 * Note: AssemblyAI uses an async workflow - upload audio, create transcript, poll for result
 */
class AssemblyAiSttService(
    private val apiKey: String,
    internal val baseUrl: String = DEFAULT_BASE_URL,
    internal val pollIntervalMs: Long = DEFAULT_POLL_INTERVAL_MS,
    internal val maxPollAttempts: Int = DEFAULT_MAX_POLL_ATTEMPTS
) : BaseSttService() {
    
    companion object {
        private const val TAG = "AssemblyAiSttService"
        internal const val DEFAULT_BASE_URL = "https://api.assemblyai.com/v2"
        internal const val DEFAULT_POLL_INTERVAL_MS = 1000L
        internal const val DEFAULT_MAX_POLL_ATTEMPTS = 60  // 60 seconds max wait
    }
    
    override val provider = SttProvider.ASSEMBLYAI
    
    override suspend fun transcribe(audioData: ByteArray, languageCode: String): SpeechResult {
        return withContext(Dispatchers.IO) {
            Log.d(TAG, "Starting AssemblyAI transcription, audio size: ${audioData.size} bytes")
            
            if (isAudioTooShort(audioData)) {
                return@withContext SpeechResult.Error(
                    message = "Audio too short",
                    errorCode = SpeechErrorCode.AUDIO_TOO_SHORT
                )
            }
            
            val wavData = pcmToWav(audioData)
            
            try {
                // Step 1: Upload audio file
                val uploadUrl = uploadAudio(wavData)
                if (uploadUrl == null) {
                    return@withContext SpeechResult.Error(
                        message = "Failed to upload audio",
                        errorCode = SpeechErrorCode.UPLOAD_FAILED
                    )
                }
                
                // Step 2: Create transcript request
                val transcriptId = createTranscript(uploadUrl, languageCode)
                if (transcriptId == null) {
                    return@withContext SpeechResult.Error(
                        message = "Failed to create transcript",
                        errorCode = SpeechErrorCode.CREATE_TRANSCRIPT_FAILED
                    )
                }
                
                // Step 3: Poll for result
                val transcript = pollForResult(transcriptId)
                if (transcript != null) {
                    SpeechResult.Success(transcript)
                } else {
                    SpeechResult.Error(
                        message = "Transcription failed or timed out",
                        errorCode = SpeechErrorCode.TRANSCRIPTION_TIMEOUT
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "Transcription error: ${e.message}")
                SpeechResult.Error(
                    message = "Transcription error",
                    errorCode = SpeechErrorCode.TRANSCRIPTION_ERROR,
                    errorDetail = e.message
                )
            }
        }
    }
    
    private fun uploadAudio(audioData: ByteArray): String? {
        Log.d(TAG, "Uploading audio to AssemblyAI...")
        
        val request = Request.Builder()
            .url("$baseUrl/upload")
            .addHeader("Authorization", apiKey)
            .addHeader("Content-Type", "audio/wav")
            .post(audioData.toRequestBody("audio/wav".toMediaType()))
            .build()
        
        return try {
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val body = response.body?.string()
                    val json = JSONObject(body ?: "{}")
                    val uploadUrl = json.optString("upload_url")
                    Log.d(TAG, "Audio uploaded successfully")
                    uploadUrl.ifEmpty { null }
                } else {
                    Log.e(TAG, "Upload failed: ${response.code}")
                    null
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Upload error: ${e.message}")
            null
        }
    }
    
    private fun createTranscript(audioUrl: String, languageCode: String): String? {
        Log.d(TAG, "Creating transcript...")
        
        // Map language codes
        val language = when {
            languageCode.startsWith("zh") -> "zh"
            languageCode.startsWith("en") -> "en"
            languageCode.startsWith("ja") -> "ja"
            languageCode.startsWith("ko") -> "ko"
            else -> languageCode.split("-").firstOrNull() ?: "en"
        }
        
        val requestBody = JSONObject().apply {
            put("audio_url", audioUrl)
            put("language_code", language)
            put("punctuate", true)
            put("format_text", true)
        }
        
        val request = Request.Builder()
            .url("$baseUrl/transcript")
            .addHeader("Authorization", apiKey)
            .addHeader("Content-Type", "application/json")
            .post(requestBody.toString().toRequestBody("application/json".toMediaType()))
            .build()
        
        return try {
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val body = response.body?.string()
                    val json = JSONObject(body ?: "{}")
                    val id = json.optString("id")
                    Log.d(TAG, "Transcript created with id: $id")
                    id.ifEmpty { null }
                } else {
                    Log.e(TAG, "Create transcript failed: ${response.code}")
                    null
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Create transcript error: ${e.message}")
            null
        }
    }
    
    private suspend fun pollForResult(transcriptId: String): String? {
        Log.d(TAG, "Polling for transcript result...")
        
        repeat(maxPollAttempts) { attempt ->
            val request = Request.Builder()
                .url("$baseUrl/transcript/$transcriptId")
                .addHeader("Authorization", apiKey)
                .get()
                .build()
            
            try {
                client.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        val body = response.body?.string()
                        val json = JSONObject(body ?: "{}")
                        val status = json.optString("status")
                        
                        when (status) {
                            "completed" -> {
                                val text = json.optString("text", "").trim()
                                Log.d(TAG, "Transcription completed: $text")
                                return text.ifEmpty { null }
                            }
                            "error" -> {
                                val error = json.optString("error")
                                Log.e(TAG, "Transcription error: $error")
                                return null
                            }
                            else -> {
                                Log.d(TAG, "Status: $status, waiting... (attempt ${attempt + 1})")
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Poll error: ${e.message}")
            }
            
            kotlinx.coroutines.delay(pollIntervalMs)
        }
        
        Log.e(TAG, "Polling timed out after $maxPollAttempts attempts")
        return null
    }
    
    override suspend fun validateCredentials(): SttValidationResult {
        return withContext(Dispatchers.IO) {
            try {
                // Use a simple GET request to check API key
                val request = Request.Builder()
                    .url("$baseUrl/transcript")  // List transcripts endpoint
                    .addHeader("Authorization", apiKey)
                    .get()
                    .build()
                
                client.newCall(request).execute().use { response ->
                    when {
                        response.isSuccessful -> SttValidationResult.Valid
                        else -> SttValidationResult.Invalid(mapHttpStatusToError(response.code))
                    }
                }
            } catch (e: java.net.SocketTimeoutException) {
                SttValidationResult.Invalid(SttValidationError.TIMEOUT)
            } catch (e: java.io.IOException) {
                SttValidationResult.Invalid(SttValidationError.NETWORK_ERROR)
            } catch (e: Exception) {
                Log.e(TAG, "Validation error: ${e.message}")
                SttValidationResult.Invalid(SttValidationError.UNKNOWN)
            }
        }
    }
}
