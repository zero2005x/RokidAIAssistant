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
 * Speechmatics Real-time Speech-to-Text Service
 * Implements real-time speech recognition using Speechmatics WebSocket API
 * 
 * API Documentation: https://docs.speechmatics.com/api-ref/realtime-transcription-websocket
 * 
 * Features:
 * - Real-time WebSocket transcription
 * - JWT token authentication
 * - Speaker diarization support
 * - Multiple language support
 * - Entity recognition
 */
class SpeechmaticsSttService(
    private val apiKey: String,
    private val language: String = "en",
    private val enableDiarization: Boolean = false,
    private val enableEntities: Boolean = false,
    private val operatingPoint: String = "standard"
) : BaseSttService() {

    companion object {
        private const val TAG = "SpeechmaticsStt"
        private const val CONNECTION_TIMEOUT_SECONDS = 30L
        private const val RECOGNITION_TIMEOUT_SECONDS = 60L
        private const val WEBSOCKET_URL = "wss://eu2.rt.speechmatics.com/v2"
    }

    override val provider = SttProvider.SPEECHMATICS

    override suspend fun transcribe(audioData: ByteArray, languageCode: String): SpeechResult {
        return withContext(Dispatchers.IO) {
            try {
                Log.d(TAG, "Starting Speechmatics transcription, audio size: ${audioData.size} bytes")

                // Generate JWT token
                val jwtToken = generateJwtToken()
                val wsUrl = "$WEBSOCKET_URL?jwt=$jwtToken"
                Log.d(TAG, "WebSocket URL prepared")

                val result = suspendCancellableCoroutine { continuation ->
                    val latch = CountDownLatch(1)
                    var finalTranscript = StringBuilder()
                    var error: Exception? = null

                    val request = Request.Builder()
                        .url(wsUrl)
                        .build()

                    val listener = object : WebSocketListener() {
                        override fun onOpen(webSocket: WebSocket, response: Response) {
                            Log.d(TAG, "WebSocket connected")
                            
                            // Send StartRecognition message
                            val lang = if (languageCode.isNotBlank()) languageCode else language
                            val startMessage = JSONObject().apply {
                                put("message", "StartRecognition")
                                put("audio_format", JSONObject().apply {
                                    put("type", "raw")
                                    put("encoding", "pcm_s16le")
                                    put("sample_rate", SAMPLE_RATE)
                                })
                                put("transcription_config", JSONObject().apply {
                                    put("language", lang)
                                    put("operating_point", operatingPoint)
                                    put("enable_partials", false)
                                    put("max_delay", 2.0)
                                    if (enableDiarization) {
                                        put("diarization", "speaker")
                                    }
                                    if (enableEntities) {
                                        put("enable_entities", true)
                                    }
                                })
                            }
                            webSocket.send(startMessage.toString())
                            
                            // Send AddAudio message with audio data
                            val addAudioMessage = JSONObject().apply {
                                put("message", "AddAudio")
                            }
                            webSocket.send(addAudioMessage.toString())
                            
                            // Send audio data in chunks
                            val chunkSize = 8000 // Approximately 250ms
                            var offset = 0
                            while (offset < audioData.size) {
                                val end = minOf(offset + chunkSize, audioData.size)
                                val chunk = audioData.copyOfRange(offset, end)
                                webSocket.send(chunk.toByteString())
                                offset = end
                            }
                            
                            // Send EndOfStream message
                            val eosMessage = JSONObject().apply {
                                put("message", "EndOfStream")
                            }
                            webSocket.send(eosMessage.toString())
                        }

                        override fun onMessage(webSocket: WebSocket, text: String) {
                            try {
                                Log.d(TAG, "Received message: $text")
                                val json = JSONObject(text)
                                
                                when (json.optString("message")) {
                                    "RecognitionStarted" -> {
                                        Log.d(TAG, "Recognition started")
                                    }
                                    "AudioAdded" -> {
                                        Log.d(TAG, "Audio added")
                                    }
                                    "AddPartialTranscript" -> {
                                        // Interim result
                                        val metadata = json.optJSONObject("metadata")
                                        val transcript = metadata?.optString("transcript", "")
                                        Log.d(TAG, "Partial transcript: $transcript")
                                    }
                                    "AddTranscript" -> {
                                        // Final result
                                        val metadata = json.optJSONObject("metadata")
                                        val transcript = metadata?.optString("transcript", "")
                                        if (!transcript.isNullOrEmpty()) {
                                            finalTranscript.append(transcript).append(" ")
                                            Log.d(TAG, "Final transcript: $transcript")
                                        }
                                    }
                                    "EndOfTranscript" -> {
                                        Log.d(TAG, "End of transcript")
                                        webSocket.close(1000, "Completed")
                                        latch.countDown()
                                    }
                                    "Warning" -> {
                                        val warningType = json.optString("type")
                                        val reason = json.optString("reason")
                                        Log.w(TAG, "Warning: $warningType - $reason")
                                    }
                                    "Error" -> {
                                        val errorType = json.optString("type")
                                        val reason = json.optString("reason")
                                        Log.e(TAG, "Speechmatics error: $errorType - $reason")
                                        error = Exception("Speechmatics error: $reason")
                                        webSocket.close(1000, "Error")
                                        latch.countDown()
                                    }
                                    "Info" -> {
                                        val infoType = json.optString("type")
                                        Log.d(TAG, "Info: $infoType")
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
                                message = "Speechmatics recognition timeout",
                                errorCode = SpeechErrorCode.TRANSCRIPTION_TIMEOUT
                            )
                        )
                    } else if (error != null) {
                        continuation.resume(
                            SpeechResult.Error(
                                message = "Speechmatics error: ${error?.message}",
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
                    message = "Speechmatics transcription error: ${e.message}",
                    errorCode = SpeechErrorCode.TRANSCRIPTION_ERROR
                )
            }
        }
    }

    override suspend fun validateCredentials(): SttValidationResult {
        return withContext(Dispatchers.IO) {
            try {
                if (apiKey.isNotBlank()) {
                    // Could implement a test connection here
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
     * Generate JWT token from API key
     * Note: This is a simplified version. In production, you should implement proper JWT generation
     * or use a library like java-jwt
     */
    private fun generateJwtToken(): String {
        // For Speechmatics, the API key IS the JWT token in most cases
        // If you need to generate a JWT from credentials, implement proper JWT signing here
        return apiKey
    }
}
