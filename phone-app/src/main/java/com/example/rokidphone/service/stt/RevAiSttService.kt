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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/**
 * Rev.ai Streaming Speech-to-Text Service
 * Implements real-time speech recognition using Rev.ai WebSocket API
 * 
 * API Documentation: https://docs.rev.ai/api/streaming/
 * 
 * Features:
 * - Real-time streaming transcription
 * - Bearer token authentication
 * - Interim and final results
 * - Content filtering options
 * - Multiple language support
 */
class RevAiSttService(
    private val accessToken: String,
    private val language: String = "en",
    private val metadata: String? = null,
    private val customVocabularyId: String? = null,
    private val filterProfanity: Boolean = false
) : BaseSttService() {

    companion object {
        private const val TAG = "RevAiStt"
        private const val CONNECTION_TIMEOUT_SECONDS = 30L
        private const val RECOGNITION_TIMEOUT_SECONDS = 60L
        private const val WEBSOCKET_URL = "wss://api.rev.ai/speechtotext/v1/stream"
    }

    override val provider = SttProvider.REV_AI

    override suspend fun transcribe(audioData: ByteArray, languageCode: String): SpeechResult {
        return withContext(Dispatchers.IO) {
            try {
                Log.d(TAG, "Starting Rev.ai transcription, audio size: ${audioData.size} bytes")

                // Build WebSocket URL with parameters
                val wsUrl = buildWebSocketUrl(languageCode)
                Log.d(TAG, "WebSocket URL: $wsUrl")

                val result = suspendCancellableCoroutine { continuation ->
                    val latch = CountDownLatch(1)
                    var finalTranscript = StringBuilder()
                    var error: Exception? = null

                    val request = Request.Builder()
                        .url(wsUrl)
                        .addHeader("Authorization", "Bearer $accessToken")
                        .build()

                    val listener = object : WebSocketListener() {
                        override fun onOpen(webSocket: WebSocket, response: Response) {
                            Log.d(TAG, "WebSocket connected")
                            
                            // Send configuration message
                            val config = JSONObject().apply {
                                put("type", "connect")
                                put("content_type", "audio/x-raw;layout=interleaved;rate=16000;format=S16LE;channels=1")
                                if (metadata != null) {
                                    put("metadata", metadata)
                                }
                                if (customVocabularyId != null) {
                                    put("custom_vocabulary_id", customVocabularyId)
                                }
                                if (filterProfanity) {
                                    put("filter_profanity", true)
                                }
                            }
                            webSocket.send(config.toString())
                            
                            // Send audio data in chunks (recommended: 0.1 seconds)
                            val chunkSize = 3200 // 100ms of PCM 16k16bit data
                            var offset = 0
                            while (offset < audioData.size) {
                                val end = minOf(offset + chunkSize, audioData.size)
                                val chunk = audioData.copyOfRange(offset, end)
                                webSocket.send(chunk.toByteString())
                                offset = end
                            }
                            
                            // Send EOS (end of stream) message
                            val eosMessage = JSONObject().apply {
                                put("type", "eos")
                            }
                            webSocket.send(eosMessage.toString())
                        }

                        override fun onMessage(webSocket: WebSocket, text: String) {
                            try {
                                Log.d(TAG, "Received message: $text")
                                val json = JSONObject(text)
                                
                                when (json.optString("type")) {
                                    "connected" -> {
                                        Log.d(TAG, "Connected to Rev.ai")
                                        val id = json.optString("id", "")
                                        Log.d(TAG, "Connection ID: $id")
                                    }
                                    "partial" -> {
                                        // Interim result
                                        val elements = json.optJSONArray("elements")
                                        if (elements != null) {
                                            val text = extractTextFromElements(elements)
                                            Log.d(TAG, "Partial result: $text")
                                        }
                                    }
                                    "final" -> {
                                        // Final result
                                        val elements = json.optJSONArray("elements")
                                        if (elements != null) {
                                            val text = extractTextFromElements(elements)
                                            if (text.isNotEmpty()) {
                                                finalTranscript.append(text).append(" ")
                                                Log.d(TAG, "Final result: $text")
                                            }
                                        }
                                    }
                                    "close" -> {
                                        // Stream closed
                                        Log.d(TAG, "Stream closed by server")
                                        webSocket.close(1000, "Completed")
                                        latch.countDown()
                                    }
                                    "error" -> {
                                        val errorMsg = json.optString("message", "Unknown error")
                                        Log.e(TAG, "Rev.ai error: $errorMsg")
                                        error = Exception("Rev.ai error: $errorMsg")
                                        webSocket.close(1000, "Error")
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

                    val transcript = finalTranscript.toString().trim()

                    if (!completed) {
                        continuation.resume(
                            SpeechResult.Error(
                                message = "Rev.ai recognition timeout",
                                errorCode = SpeechErrorCode.TRANSCRIPTION_TIMEOUT
                            )
                        )
                    } else if (error != null) {
                        continuation.resume(
                            SpeechResult.Error(
                                message = "Rev.ai error: ${error?.message}",
                                errorCode = SpeechErrorCode.TRANSCRIPTION_ERROR
                            )
                        )
                    } else if (transcript.isEmpty()) {
                        continuation.resume(
                            SpeechResult.Error(
                                message = "No transcription received",
                                errorCode = SpeechErrorCode.NO_SPEECH_DETECTED
                            )
                        )
                    } else {
                        continuation.resume(SpeechResult.Success(transcript))
                    }
                }

                result
            } catch (e: Exception) {
                Log.e(TAG, "Transcription failed", e)
                SpeechResult.Error(
                    message = "Rev.ai transcription error: ${e.message}",
                    errorCode = SpeechErrorCode.TRANSCRIPTION_ERROR
                )
            }
        }
    }

    override suspend fun validateCredentials(): SttValidationResult {
        return withContext(Dispatchers.IO) {
            try {
                if (accessToken.isNotBlank()) {
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
     * Build WebSocket URL with query parameters
     */
    private fun buildWebSocketUrl(languageCode: String): String {
        val params = mutableListOf<String>()
        
        // Use provided language or default
        val lang = if (languageCode.isNotBlank()) languageCode else language
        params.add("language=$lang")
        
        return "$WEBSOCKET_URL?${params.joinToString("&")}"
    }

    /**
     * Extract text from Rev.ai elements array
     */
    private fun extractTextFromElements(elements: org.json.JSONArray): String {
        val textBuilder = StringBuilder()
        for (i in 0 until elements.length()) {
            val element = elements.getJSONObject(i)
            val type = element.optString("type")
            if (type == "text") {
                val value = element.optString("value", "")
                textBuilder.append(value)
            } else if (type == "punct") {
                val value = element.optString("value", "")
                textBuilder.append(value)
            }
        }
        return textBuilder.toString()
    }
}
