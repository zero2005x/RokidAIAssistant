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
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.coroutines.resume

/**
 * Huawei Cloud Speech Interaction Service (SIS)
 * Implements real-time ASR using Huawei Cloud SIS API
 * 
 * API Documentation: https://support.huaweicloud.com/intl/en-us/sdkreference-sis/
 * 
 * Features:
 * - Real-time ASR with WebSocket
 * - AK/SK authentication with signature v4
 * - Chinese language optimization
 * - Support for multiple audio formats
 */
class HuaweiSisSttService(
    private val accessKey: String,
    private val secretKey: String,
    private val region: String = "cn-north-4",
    private val projectId: String,
    private val audioFormat: String = "pcm16k16bit",
    private val property: String = "chinese_16k_common"
) : BaseSttService() {

    companion object {
        private const val TAG = "HuaweiSisStt"
        private const val CONNECTION_TIMEOUT_SECONDS = 30L
        private const val RECOGNITION_TIMEOUT_SECONDS = 60L
        private const val SERVICE_NAME = "sis"
        private const val API_VERSION = "v1"
    }

    override val provider = SttProvider.HUAWEI_SIS

    override suspend fun transcribe(audioData: ByteArray, languageCode: String): SpeechResult {
        return withContext(Dispatchers.IO) {
            try {
                Log.d(TAG, "Starting Huawei SIS transcription, audio size: ${audioData.size} bytes")

                // Generate authentication parameters
                val timestamp = System.currentTimeMillis()
                val nonce = UUID.randomUUID().toString()
                
                // Build WebSocket URL with authentication
                val wsUrl = buildWebSocketUrl(timestamp, nonce)
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
                            
                            // Send start message
                            val startMessage = JSONObject().apply {
                                put("command", "START")
                                put("config", JSONObject().apply {
                                    put("audio_format", audioFormat)
                                    put("property", property)
                                    put("add_punc", "yes")
                                    put("vocabulary_id", "")
                                    put("digit_norm", "yes")
                                })
                            }
                            webSocket.send(startMessage.toString())
                            
                            // Send audio data in chunks
                            val chunkSize = 3200 // 100ms of PCM 16k16bit data
                            var offset = 0
                            while (offset < audioData.size) {
                                val end = minOf(offset + chunkSize, audioData.size)
                                val chunk = audioData.copyOfRange(offset, end)
                                webSocket.send(chunk.toByteString())
                                offset = end
                                Thread.sleep(100) // Simulate real-time streaming
                            }
                            
                            // Send end message
                            val endMessage = JSONObject().apply {
                                put("command", "END")
                            }
                            webSocket.send(endMessage.toString())
                        }

                        override fun onMessage(webSocket: WebSocket, text: String) {
                            try {
                                Log.d(TAG, "Received message: $text")
                                val json = JSONObject(text)
                                
                                when {
                                    json.has("status") -> {
                                        val status = json.getInt("status")
                                        when (status) {
                                            0 -> {
                                                // Success
                                                if (json.has("segments")) {
                                                    val segments = json.getJSONArray("segments")
                                                    val textBuilder = StringBuilder()
                                                    for (i in 0 until segments.length()) {
                                                        val segment = segments.getJSONObject(i)
                                                        if (segment.has("result")) {
                                                            val result = segment.getJSONObject("result")
                                                            val text = result.optString("text", "")
                                                            if (text.isNotEmpty()) {
                                                                textBuilder.append(text)
                                                            }
                                                        }
                                                    }
                                                    finalTranscript = textBuilder.toString().trim()
                                                    Log.d(TAG, "Final transcript: $finalTranscript")
                                                }
                                            }
                                            1 -> {
                                                // Intermediate result
                                                Log.d(TAG, "Intermediate result received")
                                            }
                                            2 -> {
                                                // End of speech
                                                Log.d(TAG, "End of speech")
                                            }
                                            else -> {
                                                // Error
                                                val errorMsg = json.optString("message", "Unknown error")
                                                Log.e(TAG, "Huawei SIS error: $errorMsg")
                                                error = Exception("Huawei SIS error: $errorMsg")
                                            }
                                        }
                                    }
                                    json.has("trace_id") -> {
                                        // Completion message
                                        Log.d(TAG, "Recognition completed")
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
                                message = "Huawei SIS recognition timeout",
                                errorCode = SpeechErrorCode.TRANSCRIPTION_TIMEOUT
                            )
                        )
                    } else if (error != null) {
                        continuation.resume(
                            SpeechResult.Error(
                                message = "Huawei SIS error: ${error?.message}",
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
                    message = "Huawei SIS transcription error: ${e.message}",
                    errorCode = SpeechErrorCode.TRANSCRIPTION_ERROR
                )
            }
        }
    }

    override suspend fun validateCredentials(): SttValidationResult {
        return withContext(Dispatchers.IO) {
            try {
                // Simple validation - check if credentials are not empty
                if (accessKey.isNotBlank() && secretKey.isNotBlank() && projectId.isNotBlank()) {
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
     * Build WebSocket URL with Huawei Cloud authentication
     */
    private fun buildWebSocketUrl(timestamp: Long, nonce: String): String {
        val host = "sis-ext.$region.myhuaweicloud.com"
        val path = "/$API_VERSION/$projectId/rasr/short-stream"
        
        // Generate signature
        val signature = generateSignature(timestamp, nonce)
        
        // Build URL with query parameters
        val params = mapOf(
            "projectId" to projectId,
            "timestamp" to timestamp.toString(),
            "nonce" to nonce,
            "signature" to signature
        )
        
        val queryString = params.entries.joinToString("&") { (key, value) ->
            "$key=${URLEncoder.encode(value, "UTF-8")}"
        }
        
        return "wss://$host$path?$queryString"
    }

    /**
     * Generate HMAC-SHA256 signature for authentication
     */
    private fun generateSignature(timestamp: Long, nonce: String): String {
        val stringToSign = "$accessKey$timestamp$nonce"
        
        val mac = Mac.getInstance("HmacSHA256")
        val secretKeySpec = SecretKeySpec(secretKey.toByteArray(), "HmacSHA256")
        mac.init(secretKeySpec)
        
        val signatureBytes = mac.doFinal(stringToSign.toByteArray())
        return signatureBytes.joinToString("") { "%02x".format(it) }
    }
}
