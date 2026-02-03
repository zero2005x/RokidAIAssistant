package com.example.rokidphone.service.stt

import android.util.Base64
import android.util.Log
import com.example.rokidphone.service.SpeechErrorCode
import com.example.rokidphone.service.SpeechResult
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Request
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString.Companion.toByteString
import org.json.JSONObject
import java.net.URLEncoder
import java.util.*
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.coroutines.resume

/**
 * Tencent Cloud Real-time ASR Service
 * 
 * API Documentation: https://cloud.tencent.com/document/product/1093/48982
 * 
 * Features:
 * - WebSocket real-time recognition
 * - High accuracy for Chinese
 * 
 * Auth: SecretId + SecretKey (HMAC-SHA1 signature per Tencent WebSocket API)
 * 
 * Required params: secretid, timestamp, expired, nonce, engine_model_type, voice_id, signature
 */
class TencentSttService(
    private val secretId: String,
    private val secretKey: String,
    private val appId: String,
    private val engineModelType: String = "16k_zh"
) : BaseSttService() {
    
    companion object {
        private const val TAG = "TencentSTT"
        private const val BASE_HOST = "asr.cloud.tencent.com"
    }
    
    override val provider = SttProvider.TENCENT_ASR
    
    /**
     * Generate WebSocket URL with proper HMAC-SHA1 signature per Tencent API spec.
     * 
     * Signature calculation:
     * 1. Sort all params (except signature) alphabetically
     * 2. Build sign string: host/path/appid?key1=value1&key2=value2...
     * 3. HMAC-SHA1 with secretKey, then Base64 encode
     * 4. URL-encode the signature
     */
    private fun buildSignedWebSocketUrl(): String {
        val timestamp = System.currentTimeMillis() / 1000
        val expired = timestamp + 86400  // 24 hours
        val nonce = (Math.random() * 1000000000).toLong()
        val voiceId = UUID.randomUUID().toString()
        
        // Build params map (sorted alphabetically for signing)
        val params = sortedMapOf(
            "engine_model_type" to engineModelType,
            "expired" to expired.toString(),
            "nonce" to nonce.toString(),
            "secretid" to secretId,
            "timestamp" to timestamp.toString(),
            "voice_format" to "1",  // PCM
            "voice_id" to voiceId
        )
        
        // Build query string for signing (sorted)
        val queryString = params.entries.joinToString("&") { "${it.key}=${it.value}" }
        
        // Sign string = host/path/appid?query (without wss://)
        val signString = "$BASE_HOST/asr/v2/$appId?$queryString"
        
        // HMAC-SHA1 signature
        val mac = Mac.getInstance("HmacSHA1")
        mac.init(SecretKeySpec(secretKey.toByteArray(Charsets.UTF_8), "HmacSHA1"))
        val signatureBytes = mac.doFinal(signString.toByteArray(Charsets.UTF_8))
        val signature = Base64.encodeToString(signatureBytes, Base64.NO_WRAP)
        
        // URL-encode the signature (must encode +, =, / etc.)
        val encodedSignature = URLEncoder.encode(signature, "UTF-8")
        
        // Build final URL
        return "wss://$BASE_HOST/asr/v2/$appId?$queryString&signature=$encodedSignature"
    }
    
    override suspend fun transcribe(audioData: ByteArray, languageCode: String): SpeechResult {
        return suspendCancellableCoroutine { continuation ->
            try {
                if (isAudioTooShort(audioData)) {
                    continuation.resume(SpeechResult.Error(
                        message = "Audio too short",
                        errorCode = SpeechErrorCode.AUDIO_TOO_SHORT
                    ))
                    return@suspendCancellableCoroutine
                }
                
                Log.d(TAG, "Tencent ASR: ${audioData.size} bytes")
                
                // Build signed WebSocket URL with proper HMAC-SHA1 signature
                val url = buildSignedWebSocketUrl()
                Log.d(TAG, "Connecting to Tencent ASR WebSocket")
                
                val request = Request.Builder().url(url).build()
                
                var resultText = ""
                var hasError = false
                var errorMessage = ""
                
                val webSocket = client.newWebSocket(request, object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: okhttp3.Response) {
                        Log.d(TAG, "WebSocket connected")
                        
                        // Send audio data
                        webSocket.send(audioData.toByteString())
                        
                        // Send end flag
                        val endFlag = JSONObject().apply {
                            put("type", "end")
                        }
                        webSocket.send(endFlag.toString())
                    }
                    
                    override fun onMessage(webSocket: WebSocket, text: String) {
                        try {
                            Log.d(TAG, "Received message: $text")
                            val json = JSONObject(text)
                            val code = json.optInt("code", -1)
                            
                            if (code == 0) {
                                // Check for final message
                                val isFinalMessage = json.optInt("final", 0) == 1
                                if (isFinalMessage) {
                                    Log.d(TAG, "Received final message")
                                    webSocket.close(1000, "Done")
                                    return
                                }
                                
                                val result = json.optJSONObject("result")
                                val voiceTextStr = result?.optString("voice_text_str")
                                
                                if (!voiceTextStr.isNullOrEmpty()) {
                                    resultText = voiceTextStr
                                }
                                
                                // Check if final result (slice_type=2 means sentence end)
                                val sliceType = result?.optInt("slice_type", -1) ?: -1
                                if (sliceType == 2) {
                                    Log.d(TAG, "Sentence complete: $resultText")
                                }
                            } else {
                                hasError = true
                                errorMessage = json.optString("message", "Unknown error")
                                Log.e(TAG, "Tencent ASR error: code=$code, message=$errorMessage")
                                webSocket.close(1000, "Error")
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Parse error", e)
                        }
                    }
                    
                    override fun onFailure(webSocket: WebSocket, t: Throwable, response: okhttp3.Response?) {
                        Log.e(TAG, "WebSocket error", t)
                        if (continuation.isActive) {
                            continuation.resume(SpeechResult.Error(
                                message = "Connection error: ${t.message}",
                                errorCode = SpeechErrorCode.NETWORK_ERROR
                            ))
                        }
                    }
                    
                    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                        if (continuation.isActive) {
                            if (hasError) {
                                continuation.resume(SpeechResult.Error(
                                    message = "Service error: $errorMessage",
                                    errorCode = SpeechErrorCode.RECOGNITION_FAILED
                                ))
                            } else if (resultText.isNotEmpty()) {
                                continuation.resume(SpeechResult.Success(resultText))
                            } else {
                                continuation.resume(SpeechResult.Error(
                                    message = "No speech detected",
                                    errorCode = SpeechErrorCode.NO_SPEECH_DETECTED
                                ))
                            }
                        }
                    }
                })
                
                continuation.invokeOnCancellation {
                    webSocket.close(1000, "Cancelled")
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "Transcription failed", e)
                if (continuation.isActive) {
                    continuation.resume(SpeechResult.Error(
                        message = "Tencent ASR error: ${e.message}",
                        errorCode = SpeechErrorCode.TRANSCRIPTION_ERROR
                    ))
                }
            }
        }
    }
    
    override suspend fun validateCredentials(): SttValidationResult {
        return if (secretId.isNotBlank() && secretKey.isNotBlank() && appId.isNotBlank()) {
            SttValidationResult.Valid
        } else {
            SttValidationResult.Invalid(SttValidationError.INVALID_CREDENTIALS)
        }
    }
}
