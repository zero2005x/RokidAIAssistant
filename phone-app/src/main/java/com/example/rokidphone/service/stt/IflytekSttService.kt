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
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * iFLYTEK (Xunfei) Speech-to-Text Service (Voice Dictation WebAPI)
 * 
 * API Docs: https://www.xfyun.cn/doc/asr/voicedictation/API.html
 * 
 * Authentication: HMAC-SHA256 signature with APIKey + APISecret
 * The signature is computed over host, date, and request-line
 * 
 * Note: This uses the REST API for pre-recorded audio.
 * For real-time streaming, WebSocket API should be used.
 */
class IflytekSttService(
    private val appId: String,
    private val apiKey: String,
    private val apiSecret: String
) : BaseSttService() {
    
    companion object {
        private const val TAG = "IflytekSttService"
        private const val HOST = "iat-api.xfyun.cn"
        private const val PATH = "/v2/iat"
        private const val BASE_URL = "https://$HOST$PATH"
    }
    
    override val provider = SttProvider.IFLYTEK
    
    override suspend fun transcribe(audioData: ByteArray, languageCode: String): SpeechResult {
        return withContext(Dispatchers.IO) {
            Log.d(TAG, "Starting iFLYTEK transcription, audio size: ${audioData.size} bytes")
            
            if (isAudioTooShort(audioData)) {
                return@withContext SpeechResult.Error(
                    message = "Audio too short",
                    errorCode = SpeechErrorCode.AUDIO_TOO_SHORT
                )
            }
            
            try {
                // Generate authorization
                val date = getHttpDate()
                val authorization = generateAuthorization(date)
                
                // Build request body
                val requestBody = buildRequestBody(audioData, languageCode)
                
                val result = executeWithRetry(TAG) { attempt ->
                    Log.d(TAG, "Sending iFLYTEK request (attempt $attempt)")
                    
                    val request = Request.Builder()
                        .url(BASE_URL)
                        .addHeader("Content-Type", "application/json")
                        .addHeader("Date", date)
                        .addHeader("Authorization", authorization)
                        .post(requestBody.toRequestBody("application/json".toMediaType()))
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
            } catch (e: Exception) {
                Log.e(TAG, "Transcription error: ${e.message}", e)
                SpeechResult.Error(
                    message = "Transcription error",
                    errorCode = SpeechErrorCode.TRANSCRIPTION_ERROR,
                    errorDetail = e.message
                )
            }
        }
    }
    
    private fun getHttpDate(): String {
        val sdf = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss z", Locale.US)
        sdf.timeZone = TimeZone.getTimeZone("GMT")
        return sdf.format(Date())
    }
    
    /**
     * Generate authorization header using HMAC-SHA256
     * Following iFLYTEK's authentication specification
     */
    private fun generateAuthorization(date: String): String {
        // Build signature origin string
        val signatureOrigin = "host: $HOST\ndate: $date\nPOST $PATH HTTP/1.1"
        
        // Calculate HMAC-SHA256 signature
        val mac = Mac.getInstance("HmacSHA256")
        val secretKeySpec = SecretKeySpec(apiSecret.toByteArray(Charsets.UTF_8), "HmacSHA256")
        mac.init(secretKeySpec)
        val signatureBytes = mac.doFinal(signatureOrigin.toByteArray(Charsets.UTF_8))
        val signature = Base64.encodeToString(signatureBytes, Base64.NO_WRAP)
        
        // Build authorization origin
        val authorizationOrigin = "api_key=\"$apiKey\", algorithm=\"hmac-sha256\", headers=\"host date request-line\", signature=\"$signature\""
        
        // Base64 encode the authorization
        return Base64.encodeToString(authorizationOrigin.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
    }
    
    private fun buildRequestBody(audioData: ByteArray, languageCode: String): String {
        // Convert PCM to base64
        val audioBase64 = Base64.encodeToString(audioData, Base64.NO_WRAP)
        
        // Map language code to iFLYTEK format
        val language = when {
            languageCode.startsWith("zh") -> "zh_cn"
            languageCode.startsWith("en") -> "en_us"
            else -> "zh_cn"  // Default to Chinese
        }
        
        val json = JSONObject().apply {
            put("common", JSONObject().apply {
                put("app_id", appId)
            })
            put("business", JSONObject().apply {
                put("language", language)
                put("domain", "iat")  // Daily conversation
                put("accent", "mandarin")  // Mandarin Chinese
                put("vad_eos", 3000)  // End-of-speech detection 3 seconds
                put("dwa", "wpgs")  // Dynamic correction
                put("pd", "game")  // Domain personalization
                put("ptt", 1)  // Punctuation
                put("rlang", "zh-cn")  // Result language
                put("vinfo", 1)
                put("nunum", 1)
            })
            put("data", JSONObject().apply {
                put("status", 2)  // Upload all audio at once
                put("format", "audio/L16;rate=16000")
                put("encoding", "raw")
                put("audio", audioBase64)
            })
        }
        
        return json.toString()
    }
    
    private fun parseTranscript(responseBody: String): String? {
        return try {
            val json = JSONObject(responseBody)
            val code = json.optInt("code", -1)
            
            if (code != 0) {
                val message = json.optString("message", "Unknown error")
                Log.e(TAG, "iFLYTEK error: code=$code, message=$message")
                return null
            }
            
            val data = json.optJSONObject("data") ?: return null
            val result = data.optJSONObject("result") ?: return null
            val ws = result.optJSONArray("ws") ?: return null
            
            // Extract text from word segments
            val transcript = StringBuilder()
            for (i in 0 until ws.length()) {
                val wsItem = ws.optJSONObject(i)
                val cw = wsItem?.optJSONArray("cw")
                if (cw != null && cw.length() > 0) {
                    val cwItem = cw.optJSONObject(0)
                    val word = cwItem?.optString("w", "") ?: ""
                    transcript.append(word)
                }
            }
            
            val text = transcript.toString().trim()
            if (text.isEmpty()) {
                Log.w(TAG, "Empty transcript from iFLYTEK")
                null
            } else {
                Log.d(TAG, "Transcription: $text")
                text
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
                // iFLYTEK doesn't have a simple health check endpoint
                // We'll send a short silent audio and check the response
                
                val date = getHttpDate()
                val authorization = generateAuthorization(date)
                
                // Create minimal silent audio (100ms of silence)
                val silentAudio = ByteArray(3200)  // 100ms @ 16kHz, 16-bit
                val requestBody = buildRequestBody(silentAudio, "zh-CN")
                
                val request = Request.Builder()
                    .url(BASE_URL)
                    .addHeader("Content-Type", "application/json")
                    .addHeader("Date", date)
                    .addHeader("Authorization", authorization)
                    .post(requestBody.toRequestBody("application/json".toMediaType()))
                    .build()
                
                client.newCall(request).execute().use { response ->
                    val body = response.body?.string()
                    
                    if (response.isSuccessful && body != null) {
                        val json = JSONObject(body)
                        val code = json.optInt("code", -1)
                        
                        // Code 0 = success, code 10160 = no speech detected (also valid credentials)
                        when (code) {
                            0, 10160 -> SttValidationResult.Valid
                            10105 -> SttValidationResult.Invalid(SttValidationError.INVALID_CREDENTIALS)  // Illegal app
                            10106 -> SttValidationResult.Invalid(SttValidationError.INVALID_CREDENTIALS)  // Incorrect app ID/key
                            10107 -> SttValidationResult.Invalid(SttValidationError.INVALID_CREDENTIALS)  // App not created
                            10114 -> SttValidationResult.Invalid(SttValidationError.RATE_LIMITED)  // Service quota exceeded
                            else -> {
                                Log.w(TAG, "iFLYTEK validation code: $code")
                                SttValidationResult.Invalid(SttValidationError.UNKNOWN)
                            }
                        }
                    } else {
                        SttValidationResult.Invalid(mapHttpStatusToError(response.code))
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
