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
 * IBM Watson Speech to Text Service
 * Implements real-time speech recognition using IBM Watson WebSocket API
 * 
 * API Documentation: https://cloud.ibm.com/apidocs/speech-to-text
 * 
 * Features:
 * - Real-time streaming recognition
 * - IAM API key authentication
 * - Multiple language models
 * - Interim results support
 * - Smart formatting
 */
class IbmWatsonSttService(
    private val apiKey: String,
    private val serviceUrl: String,
    private val model: String = "en-US_BroadbandModel",
    private val interimResults: Boolean = false,
    private val smartFormatting: Boolean = true
) : BaseSttService() {

    companion object {
        private const val TAG = "IbmWatsonStt"
        private const val CONNECTION_TIMEOUT_SECONDS = 30L
        private const val RECOGNITION_TIMEOUT_SECONDS = 60L
    }

    override val provider = SttProvider.IBM_WATSON

    override suspend fun transcribe(audioData: ByteArray, languageCode: String): SpeechResult {
        return withContext(Dispatchers.IO) {
            try {
                Log.d(TAG, "Starting IBM Watson transcription, audio size: ${audioData.size} bytes")

                // Get IAM access token
                val accessToken = getAccessToken()
                if (accessToken == null) {
                    return@withContext SpeechResult.Error(
                        message = "Failed to obtain IBM Watson access token",
                        errorCode = SpeechErrorCode.RECOGNITION_FAILED
                    )
                }

                // Build WebSocket URL
                val wsUrl = buildWebSocketUrl(model)
                Log.d(TAG, "WebSocket URL: $wsUrl")

                val result = suspendCancellableCoroutine { continuation ->
                    val latch = CountDownLatch(1)
                    var finalTranscript = ""
                    var error: Exception? = null

                    val request = Request.Builder()
                        .url(wsUrl)
                        .addHeader("Authorization", "Bearer $accessToken")
                        .build()

                    val listener = object : WebSocketListener() {
                        override fun onOpen(webSocket: WebSocket, response: Response) {
                            Log.d(TAG, "WebSocket connected")
                            
                            // Send start message with configuration
                            val startMessage = JSONObject().apply {
                                put("action", "start")
                                put("content-type", "audio/l16;rate=16000;channels=1")
                                put("interim_results", interimResults)
                                put("smart_formatting", smartFormatting)
                                put("max_alternatives", 1)
                            }
                            webSocket.send(startMessage.toString())
                            
                            // Send audio data
                            webSocket.send(audioData.toByteString())
                            
                            // Send stop message
                            val stopMessage = JSONObject().apply {
                                put("action", "stop")
                            }
                            webSocket.send(stopMessage.toString())
                        }

                        override fun onMessage(webSocket: WebSocket, text: String) {
                            try {
                                Log.d(TAG, "Received message: $text")
                                val json = JSONObject(text)
                                
                                when {
                                    json.has("results") -> {
                                        val results = json.getJSONArray("results")
                                        if (results.length() > 0) {
                                            val result = results.getJSONObject(0)
                                            val isFinal = result.optBoolean("final", false)
                                            
                                            if (isFinal) {
                                                val alternatives = result.getJSONArray("alternatives")
                                                if (alternatives.length() > 0) {
                                                    val transcript = alternatives.getJSONObject(0)
                                                        .getString("transcript")
                                                    finalTranscript = transcript.trim()
                                                    Log.d(TAG, "Final transcript: $finalTranscript")
                                                }
                                            }
                                        }
                                    }
                                    json.has("state") -> {
                                        val state = json.getString("state")
                                        if (state == "listening") {
                                            Log.d(TAG, "Watson is listening")
                                        }
                                    }
                                    json.has("error") -> {
                                        val errorMsg = json.getString("error")
                                        Log.e(TAG, "Watson error: $errorMsg")
                                        error = Exception("Watson error: $errorMsg")
                                        webSocket.close(1000, "Error received")
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
                                message = "IBM Watson recognition timeout",
                                errorCode = SpeechErrorCode.TRANSCRIPTION_TIMEOUT
                            )
                        )
                    } else if (error != null) {
                        continuation.resume(
                            SpeechResult.Error(
                                message = "IBM Watson error: ${error?.message}",
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
                    message = "IBM Watson transcription error: ${e.message}",
                    errorCode = SpeechErrorCode.TRANSCRIPTION_ERROR
                )
            }
        }
    }

    override suspend fun validateCredentials(): SttValidationResult {
        return withContext(Dispatchers.IO) {
            try {
                // Try to get access token
                val token = getAccessToken()
                if (token != null) {
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
     * Get IAM access token from IBM Watson
     */
    private fun getAccessToken(): String? {
        return try {
            val request = Request.Builder()
                .url("https://iam.cloud.ibm.com/identity/token")
                .post(
                    okhttp3.FormBody.Builder()
                        .add("grant_type", "urn:ibm:params:oauth:grant-type:apikey")
                        .add("apikey", apiKey)
                        .build()
                )
                .build()

            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val json = JSONObject(response.body?.string() ?: "")
                    json.optString("access_token", null)
                } else {
                    Log.e(TAG, "Failed to get access token: ${response.code}")
                    null
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting access token", e)
            null
        }
    }

    /**
     * Build WebSocket URL for Watson Speech to Text
     */
    private fun buildWebSocketUrl(model: String): String {
        val baseUrl = serviceUrl.removeSuffix("/")
        return "$baseUrl/v1/recognize?model=$model"
    }
}
