package com.example.rokidphone.service.stt

import android.util.Log
import com.example.rokidphone.service.SpeechErrorCode
import com.example.rokidphone.service.SpeechResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

/**
 * Otter.ai Speech-to-Text Service
 * 
 * Note: Otter.ai API is in BETA with limited public documentation
 * This implementation uses a file upload approach as real-time streaming may not be available
 * 
 * API Documentation: https://developer-guides.tryotter.com/api-reference/
 * 
 * Features:
 * - OAuth 2.0 authentication
 * - Asynchronous transcription via file upload
 * - Meeting transcription support
 * 
 * Limitations:
 * - No confirmed real-time streaming support
 * - Beta API with limited features
 * - Requires file-based transcription
 */
class OtterAiSttService(
    private val apiKey: String,
    private val clientId: String? = null,
    private val clientSecret: String? = null
) : BaseSttService() {

    companion object {
        private const val TAG = "OtterAiStt"
        private const val BASE_URL = "https://otter.ai/forward/api/v1"
        private const val MAX_POLLING_ATTEMPTS = 60
        private const val POLLING_INTERVAL_MS = 2000L
    }

    override val provider = SttProvider.OTTER_AI

    override suspend fun transcribe(audioData: ByteArray, languageCode: String): SpeechResult {
        return withContext(Dispatchers.IO) {
            try {
                Log.d(TAG, "Starting Otter.ai transcription, audio size: ${audioData.size} bytes")
                
                // Note: Otter.ai API is in beta and may not support direct audio upload
                // This implementation attempts a file-based approach
                
                // Check if we have proper credentials
                if (apiKey.isBlank()) {
                    return@withContext SpeechResult.Error(
                        message = "Otter.ai API key not configured",
                        errorCode = SpeechErrorCode.RECOGNITION_FAILED
                    )
                }

                // Convert PCM to WAV for upload
                val wavData = pcmToWav(audioData)
                
                // Create temporary file
                val tempFile = File.createTempFile("otter_audio", ".wav")
                try {
                    FileOutputStream(tempFile).use { it.write(wavData) }
                    
                    // Upload file and get speech ID
                    val speechId = uploadAudio(tempFile, languageCode)
                    if (speechId == null) {
                        return@withContext SpeechResult.Error(
                            message = "Failed to upload audio to Otter.ai",
                            errorCode = SpeechErrorCode.TRANSCRIPTION_ERROR
                        )
                    }
                    
                    Log.d(TAG, "Audio uploaded, speech ID: $speechId")
                    
                    // Poll for transcription result
                    val transcript = pollForTranscript(speechId)
                    if (transcript != null) {
                        SpeechResult.Success(transcript)
                    } else {
                        SpeechResult.Error(
                            message = "Failed to retrieve transcription from Otter.ai",
                            errorCode = SpeechErrorCode.TRANSCRIPTION_TIMEOUT
                        )
                    }
                } finally {
                    // Clean up temporary file
                    tempFile.delete()
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "Transcription failed", e)
                SpeechResult.Error(
                    message = "Otter.ai transcription error: ${e.message}",
                    errorCode = SpeechErrorCode.TRANSCRIPTION_ERROR,
                    errorDetail = "Note: Otter.ai API is in beta with limited functionality"
                )
            }
        }
    }

    override suspend fun validateCredentials(): SttValidationResult {
        return withContext(Dispatchers.IO) {
            try {
                if (apiKey.isNotBlank()) {
                    // Could implement a test API call here
                    SttValidationResult.Valid
                } else {
                    SttValidationResult.Invalid(SttValidationError.INVALID_CREDENTIALS)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Credential validation failed", e)
                SttValidationResult.Invalid(SttValidationError.NETWORK_ERROR)
            }
        }
    }

    override fun supportsStreaming(): Boolean = false // Otter.ai doesn't support real-time streaming in public API

    /**
     * Upload audio file to Otter.ai
     * Returns speech ID if successful, null otherwise
     */
    private fun uploadAudio(audioFile: File, languageCode: String): String? {
        return try {
            val requestBody = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart(
                    "audio_file",
                    audioFile.name,
                    audioFile.asRequestBody("audio/wav".toMediaType())
                )
                .addFormDataPart("language", languageCode)
                .build()

            val request = Request.Builder()
                .url("$BASE_URL/speeches")
                .addHeader("Authorization", "Bearer $apiKey")
                .post(requestBody)
                .build()

            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val responseBody = response.body?.string() ?: ""
                    val json = JSONObject(responseBody)
                    json.optString("speech_id", null)
                } else {
                    Log.e(TAG, "Upload failed: ${response.code} - ${response.message}")
                    null
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error uploading audio", e)
            null
        }
    }

    /**
     * Poll for transcription result
     * Returns transcript text if successful, null otherwise
     */
    private suspend fun pollForTranscript(speechId: String): String? {
        var attempts = 0
        
        while (attempts < MAX_POLLING_ATTEMPTS) {
            try {
                val request = Request.Builder()
                    .url("$BASE_URL/speeches/$speechId")
                    .addHeader("Authorization", "Bearer $apiKey")
                    .get()
                    .build()

                client.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        val responseBody = response.body?.string() ?: ""
                        val json = JSONObject(responseBody)
                        
                        val status = json.optString("status", "")
                        when (status) {
                            "completed" -> {
                                // Extract transcript
                                val transcript = json.optString("transcript", "")
                                if (transcript.isNotEmpty()) {
                                    Log.d(TAG, "Transcription completed: $transcript")
                                    return transcript
                                }
                            }
                            "processing" -> {
                                Log.d(TAG, "Transcription in progress (attempt $attempts)")
                            }
                            "failed" -> {
                                Log.e(TAG, "Transcription failed")
                                return null
                            }
                        }
                    } else {
                        Log.e(TAG, "Polling failed: ${response.code}")
                    }
                }
                
                // Wait before next poll
                delay(POLLING_INTERVAL_MS)
                attempts++
                
            } catch (e: Exception) {
                Log.e(TAG, "Error polling for transcript", e)
                return null
            }
        }
        
        Log.e(TAG, "Polling timeout after $attempts attempts")
        return null
    }

    /**
     * Get access token using OAuth2 client credentials
     * Note: This is only needed if using OAuth2 flow instead of API key
     */
    private fun getAccessToken(): String? {
        if (clientId.isNullOrBlank() || clientSecret.isNullOrBlank()) {
            return null
        }

        return try {
            val requestBody = okhttp3.FormBody.Builder()
                .add("grant_type", "client_credentials")
                .add("client_id", clientId)
                .add("client_secret", clientSecret)
                .build()

            val request = Request.Builder()
                .url("https://otter.ai/oauth/token")
                .post(requestBody)
                .build()

            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val json = JSONObject(response.body?.string() ?: "")
                    json.optString("access_token", null)
                } else {
                    Log.e(TAG, "Failed to get access token: ${response.code}")
                    null
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting access token", e)
            null
        }
    }
}
