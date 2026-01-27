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
 * Microsoft Azure AI Speech Service (Speech-to-Text)
 * 
 * API Docs: https://learn.microsoft.com/en-us/azure/ai-services/speech-service/rest-speech-to-text
 * 
 * Authentication: Subscription Key in Ocp-Apim-Subscription-Key header
 * Region: Determines the endpoint URL
 * 
 * Endpoint format: https://{region}.stt.speech.microsoft.com/speech/recognition/conversation/cognitiveservices/v1
 */
class AzureSpeechSttService(
    private val subscriptionKey: String,
    private val region: String
) : BaseSttService() {
    
    companion object {
        private const val TAG = "AzureSpeechSttService"
    }
    
    override val provider = SttProvider.AZURE_SPEECH
    
    private val endpoint: String
        get() = "https://$region.stt.speech.microsoft.com/speech/recognition/conversation/cognitiveservices/v1"
    
    override suspend fun transcribe(audioData: ByteArray, languageCode: String): SpeechResult {
        return withContext(Dispatchers.IO) {
            Log.d(TAG, "Starting Azure Speech transcription, audio size: ${audioData.size} bytes")
            
            if (isAudioTooShort(audioData)) {
                return@withContext SpeechResult.Error(
                    message = "Audio too short",
                    errorCode = SpeechErrorCode.AUDIO_TOO_SHORT
                )
            }
            
            val wavData = pcmToWav(audioData)
            
            // Map language code to Azure format
            val azureLanguage = mapLanguageCode(languageCode)
            
            val url = "$endpoint?language=$azureLanguage&format=detailed"
            
            val result = executeWithRetry(TAG) { attempt ->
                Log.d(TAG, "Sending Azure Speech request (attempt $attempt)")
                
                val request = Request.Builder()
                    .url(url)
                    .addHeader("Ocp-Apim-Subscription-Key", subscriptionKey)
                    .addHeader("Content-Type", "audio/wav; codecs=audio/pcm; samplerate=16000")
                    .addHeader("Accept", "application/json")
                    .post(wavData.toRequestBody("audio/wav".toMediaType()))
                    .build()
                
                client.newCall(request).execute().use { response ->
                    val responseBody = response.body?.string()
                    
                    if (response.isSuccessful && responseBody != null) {
                        parseTranscript(responseBody)
                    } else {
                        Log.e(TAG, "API error: ${response.code}, body: $responseBody")
                        null
                    }
                }
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
    
    private fun mapLanguageCode(languageCode: String): String {
        // Azure uses BCP-47 language tags
        return when {
            languageCode.startsWith("zh-CN") || languageCode == "zh" -> "zh-CN"
            languageCode.startsWith("zh-TW") -> "zh-TW"
            languageCode.startsWith("zh-HK") -> "zh-HK"
            languageCode.startsWith("en") -> "en-US"
            languageCode.startsWith("ja") -> "ja-JP"
            languageCode.startsWith("ko") -> "ko-KR"
            languageCode.startsWith("fr") -> "fr-FR"
            languageCode.startsWith("de") -> "de-DE"
            languageCode.startsWith("es") -> "es-ES"
            languageCode.startsWith("it") -> "it-IT"
            languageCode.startsWith("ru") -> "ru-RU"
            languageCode.startsWith("th") -> "th-TH"
            languageCode.startsWith("vi") -> "vi-VN"
            languageCode.startsWith("ar") -> "ar-SA"
            else -> languageCode
        }
    }
    
    private fun parseTranscript(responseBody: String): String? {
        return try {
            val json = JSONObject(responseBody)
            val status = json.optString("RecognitionStatus")
            
            if (status == "Success") {
                // For detailed format, get the best result
                val nBest = json.optJSONArray("NBest")
                if (nBest != null && nBest.length() > 0) {
                    val best = nBest.getJSONObject(0)
                    val display = best.optString("Display", "").trim()
                    if (display.isNotEmpty()) {
                        Log.d(TAG, "Transcription: $display")
                        return display
                    }
                }
                
                // Fallback to DisplayText
                val displayText = json.optString("DisplayText", "").trim()
                if (displayText.isNotEmpty()) {
                    Log.d(TAG, "Transcription: $displayText")
                    displayText
                } else {
                    null
                }
            } else {
                Log.w(TAG, "Recognition status: $status")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing response: ${e.message}")
            null
        }
    }
    
    override suspend fun validateCredentials(): SttValidationResult {
        return withContext(Dispatchers.IO) {
            try {
                // Send a minimal audio request to validate credentials
                // Azure doesn't have a simple health check endpoint that validates the key
                // So we'll use the token endpoint to validate
                val tokenUrl = "https://$region.api.cognitive.microsoft.com/sts/v1.0/issueToken"
                
                val request = Request.Builder()
                    .url(tokenUrl)
                    .addHeader("Ocp-Apim-Subscription-Key", subscriptionKey)
                    .addHeader("Content-Type", "application/x-www-form-urlencoded")
                    .post("".toRequestBody("application/x-www-form-urlencoded".toMediaType()))
                    .build()
                
                client.newCall(request).execute().use { response ->
                    when {
                        response.isSuccessful -> SttValidationResult.Valid
                        response.code == 404 -> SttValidationResult.Invalid(SttValidationError.WRONG_ENDPOINT_OR_REGION)
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
