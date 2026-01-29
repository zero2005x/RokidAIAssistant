package com.example.rokidphone.service.stt

import android.util.Base64
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
 * Google Cloud Speech-to-Text Service
 * 
 * API Documentation: https://cloud.google.com/speech-to-text/docs/reference/rest
 * 
 * Features:
 * - REST API for non-streaming recognition
 * - Supports multiple languages
 * - High accuracy
 * 
 * Auth: API Key (with X-Goog-User-Project header for quota tracking)
 * 
 * Required Headers:
 * - X-Goog-User-Project: For quota and billing attribution
 */
class GoogleCloudSttService(
    private val projectId: String,
    private val apiKey: String = "",
    private val serviceAccountJson: String = "",
    private val useServiceAccount: Boolean = false
) : BaseSttService() {
    
    companion object {
        private const val TAG = "GoogleCloudSTT"
        private const val BASE_URL = "https://speech.googleapis.com/v1"
    }
    
    override val provider = SttProvider.GOOGLE_CLOUD_STT
    
    override suspend fun transcribe(audioData: ByteArray, languageCode: String): SpeechResult {
        return withContext(Dispatchers.IO) {
            try {
                if (isAudioTooShort(audioData)) {
                    return@withContext SpeechResult.Error(
                        message = "Audio too short",
                        errorCode = SpeechErrorCode.AUDIO_TOO_SHORT
                    )
                }
                
                Log.d(TAG, "Transcribing ${audioData.size} bytes, language: $languageCode")
                
                // Encode audio to Base64
                val audioBase64 = Base64.encodeToString(audioData, Base64.NO_WRAP)
                
                // Build request JSON
                val requestJson = JSONObject().apply {
                    put("config", JSONObject().apply {
                        put("encoding", "LINEAR16")
                        put("sampleRateHertz", SAMPLE_RATE)
                        put("languageCode", languageCode)
                        put("enableAutomaticPunctuation", true)
                    })
                    put("audio", JSONObject().apply {
                        put("content", audioBase64)
                    })
                }
                
                // Build request URL
                val url = if (useServiceAccount) {
                    "$BASE_URL/speech:recognize"
                } else {
                    "$BASE_URL/speech:recognize?key=$apiKey"
                }
                
                val request = Request.Builder()
                    .url(url)
                    .addHeader("Content-Type", "application/json")
                    .addHeader("X-Goog-User-Project", projectId)  // Required for quota/billing attribution
                    .apply {
                        if (useServiceAccount) {
                            // TODO: Implement OAuth2 token from service account JSON
                            // For now, this requires additional library support
                            Log.w(TAG, "Service account auth not fully implemented, using API key")
                        }
                    }
                    .post(requestJson.toString().toRequestBody("application/json".toMediaType()))
                    .build()
                
                client.newCall(request).execute().use { response ->
                    val responseBody = response.body?.string()
                    Log.d(TAG, "Response code: ${response.code}")
                    
                    if (response.isSuccessful && responseBody != null) {
                        val json = JSONObject(responseBody)
                        val results = json.optJSONArray("results")
                        
                        if (results != null && results.length() > 0) {
                            val firstResult = results.getJSONObject(0)
                            val alternatives = firstResult.optJSONArray("alternatives")
                            
                            if (alternatives != null && alternatives.length() > 0) {
                                val transcript = alternatives.getJSONObject(0)
                                    .optString("transcript", "")
                                    .trim()
                                
                                if (transcript.isNotEmpty()) {
                                    Log.d(TAG, "Transcript: $transcript")
                                    return@withContext SpeechResult.Success(transcript)
                                }
                            }
                        }
                        
                        Log.w(TAG, "No transcript in response")
                        SpeechResult.Error(
                            message = "No speech detected",
                            errorCode = SpeechErrorCode.NO_SPEECH_DETECTED
                        )
                    } else {
                        Log.e(TAG, "API error: ${response.code}, body: $responseBody")
                        val errorMsg = try {
                            responseBody?.let { JSONObject(it).optJSONObject("error")?.optString("message") }
                                ?: "API error: ${response.code}"
                        } catch (e: Exception) {
                            "API error: ${response.code}"
                        }
                        SpeechResult.Error(
                            message = errorMsg,
                            errorCode = SpeechErrorCode.TRANSCRIPTION_ERROR
                        )
                    }
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "Transcription failed", e)
                SpeechResult.Error(
                    message = "Transcription error: ${e.message}",
                    errorCode = SpeechErrorCode.TRANSCRIPTION_ERROR
                )
            }
        }
    }
    
    override suspend fun validateCredentials(): SttValidationResult {
        return try {
            // Try a simple API call with minimal audio
            val testAudio = ByteArray(3200) // 0.2 seconds of silence
            val result = transcribe(testAudio)
            
            when (result) {
                is SpeechResult.Success -> SttValidationResult.Valid
                is SpeechResult.Error -> {
                    when (result.errorCode) {
                        SpeechErrorCode.AUDIO_TOO_SHORT,
                        SpeechErrorCode.NO_SPEECH_DETECTED -> SttValidationResult.Valid
                        else -> SttValidationResult.Invalid(SttValidationError.INVALID_CREDENTIALS)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Credential validation failed", e)
            SttValidationResult.Invalid(SttValidationError.NETWORK_ERROR)
        }
    }
}
