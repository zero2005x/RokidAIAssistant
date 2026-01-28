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
import java.security.MessageDigest
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
 * 
 * Features:
 * - Real-time WebSocket ASR
 * - High accuracy for Chinese
 * - Supports streaming
 * 
 * Auth: AccessKey ID + AccessKey Secret + AppKey
 */
class AliyunSttService(
    private val accessKeyId: String,
    private val accessKeySecret: String,
    private val appKey: String
) : BaseSttService() {
    
    companion object {
        private const val TAG = "AliyunSTT"
        private const val WEBSOCKET_URL = "wss://nls-gateway.cn-shanghai.aliyuncs.com/ws/v1"
    }
    
    override val provider = SttProvider.ALIBABA_ASR
    
    private fun generateToken(): String {
        // Simplified token generation
        // In production, should request token from Aliyun Token Service
        val timestamp = System.currentTimeMillis()
        val nonce = UUID.randomUUID().toString()
        
        val stringToSign = "GET\n" +
                "/ws/v1\n" +
                "AccessKeyId=$accessKeyId&" +
                "Action=GetToken&" +
                "Format=JSON&" +
                "SignatureMethod=HMAC-SHA1&" +
                "SignatureNonce=$nonce&" +
                "SignatureVersion=1.0&" +
                "Timestamp=${Date(timestamp)}&" +
                "Version=2019-02-28"
        
        val mac = Mac.getInstance("HmacSHA1")
        mac.init(SecretKeySpec(accessKeySecret.toByteArray(), "HmacSHA1"))
        val signature = Base64.encodeToString(mac.doFinal(stringToSign.toByteArray()), Base64.NO_WRAP)
        
        return signature // Simplified - should be actual token from API
    }
    
    override suspend fun transcribe(audioData: ByteArray, languageCode: String): SpeechResult {
        return suspendCoroutine { continuation ->
            try {
                if (isAudioTooShort(audioData)) {
                    continuation.resume(SpeechResult.Error(
                        message = "Audio too short",
                        errorCode = SpeechErrorCode.AUDIO_TOO_SHORT
                    ))
                    return@suspendCoroutine
                }
                
                Log.d(TAG, "Aliyun ASR: ${audioData.size} bytes")
                
                val latch = CountDownLatch(1)
                var result: SpeechResult = SpeechResult.Error(
                    message = "Timeout",
                    errorCode = SpeechErrorCode.TRANSCRIPTION_TIMEOUT
                )
                
                val token = generateToken()
                val url = "$WEBSOCKET_URL?token=$token"
                
                val request = Request.Builder()
                    .url(url)
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
                
                continuation.resume(result)
                
            } catch (e: Exception) {
                Log.e(TAG, "Transcription failed", e)
                continuation.resume(SpeechResult.Error(
                    message = "Aliyun ASR error: ${e.message}",
                    errorCode = SpeechErrorCode.TRANSCRIPTION_ERROR
                ))
            }
        }
    }
    
    override suspend fun validateCredentials(): SttValidationResult {
        return try {
            if (accessKeyId.isNotBlank() && accessKeySecret.isNotBlank() && appKey.isNotBlank()) {
                SttValidationResult.Valid
            } else {
                SttValidationResult.Invalid(SttValidationError.INVALID_CREDENTIALS)
            }
        } catch (e: Exception) {
            SttValidationResult.Invalid(SttValidationError.NETWORK_ERROR)
        }
    }
}
