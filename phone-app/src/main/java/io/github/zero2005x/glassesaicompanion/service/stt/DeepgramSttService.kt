package io.github.zero2005x.glassesaicompanion.service.stt

import android.util.Log
import io.github.zero2005x.glassesaicompanion.service.SpeechErrorCode
import io.github.zero2005x.glassesaicompanion.service.SpeechResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/**
 * Deepgram Speech-to-Text Service
 * 
 * API Docs: https://developers.deepgram.com/docs/getting-started-with-pre-recorded-audio
 * 
 * Authentication: API Key in Authorization header with "Token" scheme
 * Format: Authorization: Token YOUR_API_KEY
 */
class DeepgramSttService(
    private val apiKey: String,
    internal val baseUrl: String = DEFAULT_BASE_URL
) : BaseSttService() {
    
    companion object {
        private const val TAG = "DeepgramSttService"
        internal const val DEFAULT_BASE_URL = "https://api.deepgram.com/v1"
    }
    
    override val provider = SttProvider.DEEPGRAM
    
    override suspend fun transcribe(audioData: ByteArray, languageCode: String): SpeechResult {
        return withContext(Dispatchers.IO) {
            Log.d(TAG, "Starting Deepgram transcription, audio size: ${audioData.size} bytes")
            
            if (isAudioTooShort(audioData)) {
                return@withContext SpeechResult.Error(
                    message = "Audio too short",
                    errorCode = SpeechErrorCode.AUDIO_TOO_SHORT
                )
            }
            
            val wavData = pcmToWav(audioData)
            
            val url = baseUrl.toHttpUrl().newBuilder()
                .addPathSegment("listen")
                .addQueryParameter("model", "nova-2")
                .addQueryParameter("language", languageCode)
                .addQueryParameter("punctuate", "true")
                .addQueryParameter("smart_format", "true")
                .build()
            
            val result = try {
                executeWithRetry(TAG) { attempt ->
                    Log.d(TAG, "Sending Deepgram request (attempt $attempt)")
                
                    val request = Request.Builder()
                        .url(url)
                        .addHeader("Authorization", "Token $apiKey")
                        .addHeader("Content-Type", "audio/wav")
                        .post(wavData.toRequestBody("audio/wav".toMediaType()))
                        .build()
                
                    client.newCall(request).execute().use { response ->
                        val responseBody = response.body?.string()
                    
                        if (response.isSuccessful && responseBody != null) {
                            parseTranscript(responseBody)
                        } else {
                            Log.e(TAG, "Deepgram API error: HTTP ${response.code}")
                            if (response.code in 400..499 && response.code !in setOf(408, 429)) {
                                throw NonRetryableSttException(
                                    response.code,
                                    "Deepgram rejected the request (HTTP ${response.code})"
                                )
                            }
                            null
                        }
                    }
                }
            } catch (e: NonRetryableSttException) {
                return@withContext SpeechResult.Error(
                    message = e.message ?: "Deepgram rejected the request",
                    errorCode = SpeechErrorCode.TRANSCRIPTION_ERROR,
                    errorDetail = "HTTP ${e.statusCode}"
                )
            }
            
            if (result != null) {
                SpeechResult.Success(result)
            } else {
                SpeechResult.Error(
                    message = "Unable to recognize speech",
                    errorCode = SpeechErrorCode.UNABLE_TO_RECOGNIZE
                )
            }
        }
    }
    
    private fun parseTranscript(responseBody: String): String? {
        return try {
            val json = JSONObject(responseBody)
            val results = json.optJSONObject("results")
            val channels = results?.optJSONArray("channels")
            val channel = channels?.optJSONObject(0)
            val alternatives = channel?.optJSONArray("alternatives")
            val alternative = alternatives?.optJSONObject(0)
            val transcript = alternative?.optString("transcript", "")?.trim()
            
            if (transcript.isNullOrEmpty()) {
                Log.w(TAG, "Empty transcript in response")
                null
            } else {
                Log.d(TAG, "Deepgram transcription completed (${transcript.length} characters)")
                transcript
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing response: ${e.message}")
            null
        }
    }
    
    override suspend fun validateCredentials(): SttValidationResult {
        return withContext(Dispatchers.IO) {
            try {
                // Use projects endpoint to validate API key
                val request = Request.Builder()
                    .url("$baseUrl/projects")
                    .addHeader("Authorization", "Token $apiKey")
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
