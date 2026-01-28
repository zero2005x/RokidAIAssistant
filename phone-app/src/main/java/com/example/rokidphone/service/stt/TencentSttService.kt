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
import java.security.MessageDigest
import java.util.*
import java.util.concurrent.TimeUnit
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.coroutines.resume

/**
 * Tencent Cloud (腾讯云) Real-time ASR Service
 * 
 * API Documentation: https://cloud.tencent.com/document/product/1093
 * 
 * Features:
 * - WebSocket real-time recognition
 * - High accuracy for Chinese
 * 
 * Auth: SecretId + SecretKey (HMAC signature)
 */
class TencentSttService(
    private val secretId: String,
    private val secretKey: String,
    private val appId: String,
    private val engineModelType: String = "16k_zh"
) : BaseSttService() {
    
    companion object {
        private const val TAG = "TencentSTT"
        private const val WEBSOCKET_URL = "wss://asr.cloud.tencent.com/asr/v2/"
    }
    
    override val provider = SttProvider.TENCENT_ASR
    
    private fun generateSignature(timestamp: Long): String {
        val dateTime = Date(timestamp * 1000)
        val source = "asr.cloud.tencent.com/asr/v2/$secretId"
        
        val mac = Mac.getInstance("HmacSHA1")
        mac.init(SecretKeySpec(secretKey.toByteArray(), "HmacSHA1"))
        val signature = mac.doFinal(source.toByteArray())
        
        return Base64.encodeToString(signature, Base64.NO_WRAP)
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
                
                val timestamp = System.currentTimeMillis() / 1000
                val signature = generateSignature(timestamp)
                
                val url = "$WEBSOCKET_URL$appId?secretid=$secretId&timestamp=$timestamp&expired=${timestamp + 3600}&signature=$signature&engine_model_type=$engineModelType"
                
                val request = Request.Builder().url(url).build()
                
                var resultText = ""
                var hasError = false
                
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
                            val json = JSONObject(text)
                            val code = json.optInt("code", -1)
                            
                            if (code == 0) {
                                val result = json.optJSONObject("result")
                                val voiceTextStr = result?.optString("voice_text_str")
                                
                                if (!voiceTextStr.isNullOrEmpty()) {
                                    resultText = voiceTextStr
                                }
                                
                                // Check if final result
                                val isFinal = result?.optInt("slice_type") == 2
                                if (isFinal) {
                                    webSocket.close(1000, "Done")
                                }
                            } else {
                                hasError = true
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
                                message = "Connection error",
                                errorCode = SpeechErrorCode.NETWORK_ERROR
                            ))
                        }
                    }
                    
                    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                        if (continuation.isActive) {
                            if (hasError) {
                                continuation.resume(SpeechResult.Error(
                                    message = "Service error",
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
