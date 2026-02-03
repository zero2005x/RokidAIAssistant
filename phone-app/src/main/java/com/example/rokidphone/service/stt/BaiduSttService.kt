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

/**
 * Baidu Cloud ASR Service
 * 
 * API Documentation: https://cloud.baidu.com/doc/SPEECH/s/Vkh8j0x7z
 * 
 * Features:
 * - REST API for short audio
 * - High accuracy for Chinese
 * 
 * Auth: API Key + Secret Key (to obtain Access Token)
 */
class BaiduSttService(
    private val apiKey: String,
    private val secretKey: String
) : BaseSttService() {
    
    companion object {
        private const val TAG = "BaiduSTT"
        private const val TOKEN_URL = "https://aip.baidubce.com/oauth/2.0/token"
        private const val ASR_URL = "https://vop.baidu.com/server_api"
    }
    
    override val provider = SttProvider.BAIDU_ASR
    
    private var accessToken: String? = null
    private var tokenExpireTime: Long = 0
    
    private suspend fun getAccessToken(): String? {
        // Check if token is still valid
        if (accessToken != null && System.currentTimeMillis() < tokenExpireTime) {
            return accessToken
        }
        
        return withContext(Dispatchers.IO) {
            try {
                val url = "$TOKEN_URL?grant_type=client_credentials&client_id=$apiKey&client_secret=$secretKey"
                
                val request = Request.Builder()
                    .url(url)
                    .post("".toRequestBody())
                    .build()
                
                client.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        val json = JSONObject(response.body?.string() ?: "")
                        accessToken = json.optString("access_token")
                        val expiresIn = json.optInt("expires_in", 2592000) // 30 days default
                        tokenExpireTime = System.currentTimeMillis() + (expiresIn * 1000L)
                        accessToken
                    } else {
                        Log.e(TAG, "Failed to get access token: ${response.code}")
                        null
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Token request failed", e)
                null
            }
        }
    }
    
    override suspend fun transcribe(audioData: ByteArray, languageCode: String): SpeechResult {
        return withContext(Dispatchers.IO) {
            try {
                if (isAudioTooShort(audioData)) {
                    return@withContext SpeechResult.Error(
                        message = "Audio too short",
                        errorCode = SpeechErrorCode.AUDIO_TOO_SHORT
                    )
                }
                
                val token = getAccessToken()
                if (token == null) {
                    return@withContext SpeechResult.Error(
                        message = "Failed to get access token",
                        errorCode = SpeechErrorCode.RECOGNITION_FAILED
                    )
                }
                
                Log.d(TAG, "Baidu ASR: ${audioData.size} bytes")
                
                // Convert PCM to Base64
                val audioBase64 = Base64.encodeToString(audioData, Base64.NO_WRAP)
                val audioLen = audioData.size
                
                // Build request JSON
                val requestJson = JSONObject().apply {
                    put("format", "pcm")
                    put("rate", SAMPLE_RATE)
                    put("channel", CHANNELS)
                    put("cuid", "RokidAIAssistant")
                    put("token", token)
                    put("speech", audioBase64)
                    put("len", audioLen)
                    put("dev_pid", 1537) // 1537: Mandarin, 1737: English
                }
                
                val request = Request.Builder()
                    .url(ASR_URL)
                    .addHeader("Content-Type", "application/json")
                    .post(requestJson.toString().toRequestBody("application/json".toMediaType()))
                    .build()
                
                client.newCall(request).execute().use { response ->
                    val responseBody = response.body?.string()
                    
                    if (response.isSuccessful && responseBody != null) {
                        val json = JSONObject(responseBody)
                        val errNo = json.optInt("err_no", -1)
                        
                        if (errNo == 0) {
                            val resultArray = json.optJSONArray("result")
                            if (resultArray != null && resultArray.length() > 0) {
                                val transcript = resultArray.getString(0).trim()
                                Log.d(TAG, "Transcript: $transcript")
                                return@withContext SpeechResult.Success(transcript)
                            }
                        } else {
                            val errMsg = json.optString("err_msg", "Unknown error")
                            Log.e(TAG, "Baidu API error: $errNo - $errMsg")
                            return@withContext SpeechResult.Error(
                                message = errMsg,
                                errorCode = SpeechErrorCode.RECOGNITION_FAILED
                            )
                        }
                    }
                    
                    SpeechResult.Error(
                        message = "No speech detected",
                        errorCode = SpeechErrorCode.NO_SPEECH_DETECTED
                    )
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "Transcription failed", e)
                SpeechResult.Error(
                    message = "Baidu ASR error: ${e.message}",
                    errorCode = SpeechErrorCode.TRANSCRIPTION_ERROR
                )
            }
        }
    }
    
    override suspend fun validateCredentials(): SttValidationResult {
        return try {
            val token = getAccessToken()
            if (token != null) {
                SttValidationResult.Valid
            } else {
                SttValidationResult.Invalid(SttValidationError.INVALID_CREDENTIALS)
            }
        } catch (e: Exception) {
            SttValidationResult.Invalid(SttValidationError.NETWORK_ERROR)
        }
    }
}
