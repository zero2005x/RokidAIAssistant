package com.example.rokidphone.service.stt

import android.util.Log
import com.example.rokidphone.service.SpeechErrorCode
import com.example.rokidphone.service.SpeechResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString.Companion.toByteString
import org.json.JSONObject
import android.util.Base64
import java.net.URLEncoder
import java.util.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.coroutines.resume

/**
 * Volcano Engine ASR Service (ByteDance)
 * Implements real-time speech recognition using Volcano Engine ASR API
 * 
 * API Documentation: Volcano Engine Speech Recognition
 * 
 * Features:
 * - Real-time streaming ASR via WebSocket
 * - AK/SK authentication with signature
 * - Low-latency recognition
 * - Chinese and English support
 */
class VolcengineSttService(
    private val appId: String,
    private val accessKeyId: String,
    private val accessKeySecret: String,
    private val cluster: String = "volcengine_streaming_common",
    private val userId: String = UUID.randomUUID().toString(),
    private val audioFormat: String = "pcm"
) : BaseSttService() {

    companion object {
        private const val TAG = "VolcengineStt"
        private const val CONNECTION_TIMEOUT_SECONDS = 30L
        private const val RECOGNITION_TIMEOUT_SECONDS = 60L
        private const val WEBSOCKET_HOST = "openspeech.bytedance.com"
        private const val API_VERSION = "v1"
    }

    override val provider = SttProvider.VOLCENGINE

    override suspend fun transcribe(audioData: ByteArray, languageCode: String): SpeechResult {
        return withContext(Dispatchers.IO) {
            try {
                Log.d(TAG, "Starting Volcengine transcription, audio size: ${audioData.size} bytes")

                // Generate authentication token
                val token = generateToken()
                
                // Build WebSocket URL
                val wsUrl = buildWebSocketUrl(token)
                Log.d(TAG, "WebSocket URL: $wsUrl")

                val result = suspendCancellableCoroutine { continuation ->
                    val latch = CountDownLatch(1)
                    var finalTranscript = ""
                    var error: Exception? = null

                    val request = Request.Builder()
                        .url(wsUrl)
                        .build()

                    val listener = object : WebSocketListener() {
                        override fun onOpen(webSocket: WebSocket, response: Response) {
                            Log.d(TAG, "WebSocket connected")
                            
                            // Send full client request with start signal
                            val startRequest = JSONObject().apply {
                                put("app", JSONObject().apply {
                                    put("appid", appId)
                                    put("token", token)
                                    put("cluster", cluster)
                                })
                                put("user", JSONObject().apply {
                                    put("uid", userId)
                                })
                                put("audio", JSONObject().apply {
                                    put("format", audioFormat)
                                    put("rate", SAMPLE_RATE)
                                    put("bits", 16)
                                    put("channel", 1)
                                    put("language", if (languageCode.startsWith("zh")) "zh-CN" else "en-US")
                                })
                                put("request", JSONObject().apply {
                                    put("reqid", UUID.randomUUID().toString())
                                    put("nbest", 1)
                                    put("sequence", 1)
                                    put("with_itn", true)
                                })
                            }
                            
                            // Send as full-client-request
                            val message = JSONObject().apply {
                                put("full_client_request", startRequest)
                            }
                            webSocket.send(message.toString())
                            
                            // Send audio data
                            webSocket.send(audioData.toByteString())
                            
                            // Send finish signal
                            val finishMessage = JSONObject().apply {
                                put("signal", "finish")
                            }
                            webSocket.send(finishMessage.toString())
                        }

                        override fun onMessage(webSocket: WebSocket, text: String) {
                            try {
                                Log.d(TAG, "Received message: $text")
                                val json = JSONObject(text)
                                
                                when {
                                    json.has("result") -> {
                                        val result = json.getJSONObject("result")
                                        val text = result.optString("text", "")
                                        val isFinal = result.optBoolean("is_final", false)
                                        
                                        if (text.isNotEmpty()) {
                                            if (isFinal) {
                                                finalTranscript = text.trim()
                                                Log.d(TAG, "Final transcript: $finalTranscript")
                                            } else {
                                                Log.d(TAG, "Interim result: $text")
                                            }
                                        }
                                    }
                                    json.has("code") -> {
                                        val code = json.getInt("code")
                                        if (code != 1000) {
                                            val message = json.optString("message", "Unknown error")
                                            Log.e(TAG, "Volcengine error: $code - $message")
                                            error = Exception("Volcengine error: $message")
                                            webSocket.close(1000, "Error")
                                            latch.countDown()
                                        }
                                    }
                                    json.has("full_server_response") -> {
                                        // Final response
                                        val response = json.getJSONObject("full_server_response")
                                        if (response.has("result")) {
                                            val result = response.getJSONObject("result")
                                            if (result.has("text")) {
                                                finalTranscript = result.getString("text").trim()
                                                Log.d(TAG, "Final transcript from response: $finalTranscript")
                                            }
                                        }
                                        webSocket.close(1000, "Completed")
                                        latch.countDown()
                                    }
                                }
                            } catch (e: Exception) {
                                Log.e(TAG, "Error parsing message", e)
                                error = e
                            }
                        }

                        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                            Log.d(TAG, "WebSocket closed: $code - $reason")
                            latch.countDown()
                        }

                        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                            Log.e(TAG, "WebSocket failure", t)
                            error = Exception("WebSocket failure: ${t.message}", t)
                            latch.countDown()
                        }
                    }

                    val webSocket = client.newWebSocket(request, listener)

                    continuation.invokeOnCancellation {
                        webSocket.close(1000, "Cancelled")
                    }

                    // Wait for completion
                    val completed = latch.await(RECOGNITION_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    webSocket.close(1000, "Completed")

                    if (!completed) {
                        continuation.resume(
                            SpeechResult.Error(
                                message = "Volcengine recognition timeout",
                                errorCode = SpeechErrorCode.TRANSCRIPTION_TIMEOUT
                            )
                        )
                    } else if (error != null) {
                        continuation.resume(
                            SpeechResult.Error(
                                message = "Volcengine error: ${error?.message}",
                                errorCode = SpeechErrorCode.TRANSCRIPTION_ERROR
                            )
                        )
                    } else if (finalTranscript.isEmpty()) {
                        continuation.resume(
                            SpeechResult.Error(
                                message = "No transcription received",
                                errorCode = SpeechErrorCode.NO_SPEECH_DETECTED
                            )
                        )
                    } else {
                        continuation.resume(SpeechResult.Success(finalTranscript))
                    }
                }

                result
            } catch (e: Exception) {
                Log.e(TAG, "Transcription failed", e)
                SpeechResult.Error(
                    message = "Volcengine transcription error: ${e.message}",
                    errorCode = SpeechErrorCode.TRANSCRIPTION_ERROR
                )
            }
        }
    }

    override suspend fun validateCredentials(): SttValidationResult {
        return withContext(Dispatchers.IO) {
            try {
                if (appId.isNotBlank() && accessKeyId.isNotBlank() && accessKeySecret.isNotBlank()) {
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

    override fun supportsStreaming(): Boolean = true

    /**
     * Generate authentication token
     */
    private fun generateToken(): String {
        val timestamp = System.currentTimeMillis() / 1000
        val nonce = UUID.randomUUID().toString()
        
        // Create signature string
        val signString = "$accessKeyId$timestamp$nonce"
        
        // Generate HMAC-SHA256 signature
        val mac = Mac.getInstance("HmacSHA256")
        val secretKeySpec = SecretKeySpec(accessKeySecret.toByteArray(), "HmacSHA256")
        mac.init(secretKeySpec)
        
        val signatureBytes = mac.doFinal(signString.toByteArray())
        val signature = Base64.encodeToString(signatureBytes, Base64.NO_WRAP)
        
        // Build token
        return "$accessKeyId;$timestamp;$nonce;$signature"
    }

    /**
     * Build WebSocket URL for Volcengine ASR
     */
    private fun buildWebSocketUrl(token: String): String {
        val params = mapOf(
            "appid" to appId,
            "token" to token,
            "cluster" to cluster
        )
        
        val queryString = params.entries.joinToString("&") { (key, value) ->
            "$key=${URLEncoder.encode(value, "UTF-8")}"
        }
        
        return "wss://$WEBSOCKET_HOST/$API_VERSION/asr?$queryString"
    }
}
