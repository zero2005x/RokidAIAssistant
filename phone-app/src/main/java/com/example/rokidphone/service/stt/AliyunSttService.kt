package com.example.rokidphone.service.stt

import android.util.Base64
import android.util.Log
import com.example.rokidphone.service.SpeechErrorCode
import com.example.rokidphone.service.SpeechResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.net.URLEncoder
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/**
 * Aliyun (阿里云) NLS Speech Recognition Service
 * 
 * API Documentation: https://help.aliyun.com/document_detail/90727.html
 * Token API: https://help.aliyun.com/zh/isi/getting-started/use-http-or-https-to-obtain-an-access-token
 * 
 * Features:
 * - Real-time WebSocket ASR
 * - High accuracy for Chinese
 * - Supports streaming
 * 
 * Auth: AccessKey ID + AccessKey Secret + AppKey
 * Token must be obtained from Aliyun Token Service API
 */
class AliyunSttService(
    private val accessKeyId: String,
    private val accessKeySecret: String,
    private val appKey: String
) : BaseSttService() {
    
    companion object {
        private const val TAG = "AliyunSTT"
        private const val WEBSOCKET_URL = "wss://nls-gateway.cn-shanghai.aliyuncs.com/ws/v1"
        private const val TOKEN_API_URL = "https://nls-meta.cn-shanghai.aliyuncs.com/"
    }
    
    override val provider = SttProvider.ALIBABA_ASR
    
    // Cached token
    private var cachedToken: String? = null
    private var tokenExpireTime: Long = 0
    
    /**
     * Get access token from Aliyun NLS Token Service
     * Uses OpenAPI signature v1 (HMAC-SHA1)
     * 
     * Reference: https://help.aliyun.com/zh/isi/getting-started/use-http-or-https-to-obtain-an-access-token
     */
    private suspend fun getAccessToken(): String? {
        // Return cached token if still valid (with 5 min buffer)
        if (cachedToken != null && System.currentTimeMillis() < tokenExpireTime - 300000) {
            return cachedToken
        }
        
        return withContext(Dispatchers.IO) {
            try {
                val timestamp = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
                    timeZone = TimeZone.getTimeZone("UTC")
                }.format(Date())
                
                val nonce = UUID.randomUUID().toString()
                
                // Build sorted parameters map
                val params = sortedMapOf(
                    "AccessKeyId" to accessKeyId,
                    "Action" to "CreateToken",
                    "Format" to "JSON",
                    "RegionId" to "cn-shanghai",
                    "SignatureMethod" to "HMAC-SHA1",
                    "SignatureNonce" to nonce,
                    "SignatureVersion" to "1.0",
                    "Timestamp" to timestamp,
                    "Version" to "2019-02-28"
                )
                
                // Build query string (URL encoded)
                val queryString = params.entries.joinToString("&") { (key, value) ->
                    "${percentEncode(key)}=${percentEncode(value)}"
                }
                
                // Build string to sign: GET&/&<encoded query string>
                val stringToSign = "GET&${percentEncode("/")}&${percentEncode(queryString)}"
                
                // Calculate HMAC-SHA1 signature
                val mac = Mac.getInstance("HmacSHA1")
                mac.init(SecretKeySpec("$accessKeySecret&".toByteArray(Charsets.UTF_8), "HmacSHA1"))
                val signatureBytes = mac.doFinal(stringToSign.toByteArray(Charsets.UTF_8))
                val signature = Base64.encodeToString(signatureBytes, Base64.NO_WRAP)
                
                // Build final URL with signature
                val url = "$TOKEN_API_URL?$queryString&Signature=${percentEncode(signature)}"
                
                Log.d(TAG, "Requesting token from Aliyun NLS API")
                
                val request = Request.Builder()
                    .url(url)
                    .get()
                    .build()
                
                client.newCall(request).execute().use { response ->
                    val responseBody = response.body?.string()
                    
                    if (response.isSuccessful && responseBody != null) {
                        val json = JSONObject(responseBody)
                        val tokenObj = json.optJSONObject("Token")
                        
                        if (tokenObj != null) {
                            cachedToken = tokenObj.optString("Id")
                            val expireTime = tokenObj.optLong("ExpireTime", 0)
                            tokenExpireTime = expireTime * 1000 // Convert to milliseconds
                            Log.d(TAG, "Token obtained successfully, expires at: $expireTime")
                            return@withContext cachedToken
                        } else {
                            Log.e(TAG, "Token not found in response: $responseBody")
                            return@withContext null
                        }
                    } else {
                        Log.e(TAG, "Token request failed: ${response.code}, body: $responseBody")
                        return@withContext null
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to get access token", e)
                null
            }
        }
    }
    
    /**
     * URL encode with RFC 3986 (percent encoding)
     */
    private fun percentEncode(value: String): String {
        return try {
            URLEncoder.encode(value, "UTF-8")
                .replace("+", "%20")
                .replace("*", "%2A")
                .replace("%7E", "~")
        } catch (e: Exception) {
            value
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
                
                Log.d(TAG, "Aliyun ASR: ${audioData.size} bytes")
                
                // Get access token from Aliyun Token Service
                val token = getAccessToken()
                if (token.isNullOrBlank()) {
                    return@withContext SpeechResult.Error(
                        message = "Failed to obtain Aliyun access token",
                        errorCode = SpeechErrorCode.RECOGNITION_FAILED
                    )
                }
                
                val latch = CountDownLatch(1)
                var result: SpeechResult = SpeechResult.Error(
                    message = "Timeout",
                    errorCode = SpeechErrorCode.TRANSCRIPTION_TIMEOUT
                )
                
                val url = "$WEBSOCKET_URL?token=$token"
                
                val request = Request.Builder()
                    .url(url)
                    .addHeader("X-NLS-Token", token)  // Also add token in header as per docs
                    .build()
                
                val webSocket = client.newWebSocket(request, object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: okhttp3.Response) {
                        Log.d(TAG, "WebSocket connected")
                        
                        // Send start command
                        val startCommand = JSONObject().apply {
                            put("header", JSONObject().apply {
                                put("message_id", UUID.randomUUID().toString())
                                put("task_id", UUID.randomUUID().toString())
                                put("namespace", "SpeechTranscriber")
                                put("name", "StartTranscription")
                                put("appkey", appKey)
                            })
                            put("payload", JSONObject().apply {
                                put("format", "pcm")
                                put("sample_rate", SAMPLE_RATE)
                                put("enable_intermediate_result", false)
                                put("enable_punctuation_prediction", true)
                                put("enable_inverse_text_normalization", true)
                            })
                        }
                        webSocket.send(startCommand.toString())
                        
                        // Send audio data
                        val audioBase64 = Base64.encodeToString(audioData, Base64.NO_WRAP)
                        webSocket.send(audioBase64)
                        
                        // Send stop command
                        val stopCommand = JSONObject().apply {
                            put("header", JSONObject().apply {
                                put("message_id", UUID.randomUUID().toString())
                                put("task_id", UUID.randomUUID().toString())
                                put("namespace", "SpeechTranscriber")
                                put("name", "StopTranscription")
                                put("appkey", appKey)
                            })
                        }
                        webSocket.send(stopCommand.toString())
                    }
                    
                    override fun onMessage(webSocket: WebSocket, text: String) {
                        try {
                            val json = JSONObject(text)
                            val header = json.optJSONObject("header")
                            val name = header?.optString("name")
                            
                            if (name == "TranscriptionResultChanged" || name == "TranscriptionCompleted") {
                                val payload = json.optJSONObject("payload")
                                val transcript = payload?.optString("result")?.trim()
                                
                                if (!transcript.isNullOrEmpty()) {
                                    result = SpeechResult.Success(transcript)
                                    webSocket.close(1000, "Done")
                                    latch.countDown()
                                }
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to parse message", e)
                        }
                    }
                    
                    override fun onFailure(webSocket: WebSocket, t: Throwable, response: okhttp3.Response?) {
                        Log.e(TAG, "WebSocket error", t)
                        result = SpeechResult.Error(
                            message = "Connection error: ${t.message}",
                            errorCode = SpeechErrorCode.NETWORK_ERROR
                        )
                        latch.countDown()
                    }
                    
                    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                        latch.countDown()
                    }
                })
                
                // Wait for result with timeout
                if (!latch.await(30, TimeUnit.SECONDS)) {
                    webSocket.close(1000, "Timeout")
                    result = SpeechResult.Error(
                        message = "Timeout",
                        errorCode = SpeechErrorCode.TRANSCRIPTION_TIMEOUT
                    )
                }
                
                result
                
            } catch (e: Exception) {
                Log.e(TAG, "Transcription failed", e)
                SpeechResult.Error(
                    message = "Aliyun ASR error: ${e.message}",
                    errorCode = SpeechErrorCode.TRANSCRIPTION_ERROR
                )
            }
        }
    }
    
    override suspend fun validateCredentials(): SttValidationResult {
        return try {
            if (accessKeyId.isBlank() || accessKeySecret.isBlank() || appKey.isBlank()) {
                return SttValidationResult.Invalid(SttValidationError.INVALID_CREDENTIALS)
            }
            
            // Actually validate by trying to get a token
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
