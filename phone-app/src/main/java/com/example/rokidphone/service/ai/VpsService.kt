package com.example.rokidphone.service.ai

import android.util.Log
import com.example.rokidphone.data.AiProvider
import com.example.rokidphone.service.SpeechResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URLDecoder
import java.util.concurrent.TimeUnit

/**
 * VPS AI Service
 *
 * Sends photos and text to a personal VPS running Claude Code,
 * which analyses images with Claude Vision and returns TTS audio + text.
 *
 * Endpoint: POST {baseUrl}/voice/photo  (multipart: image + prompt + session_id)
 * Response: audio/mpeg body, X-Transcript header with text response
 *
 * Endpoint: POST {baseUrl}/voice        (JSON: text + session_id)
 * Response: audio/ogg body, X-Transcript header with text response
 */
class VpsService(
    private val baseUrl: String,
    private val authToken: String = "",
    private val sessionId: String = "glasses-main"
) : AiServiceProvider {

    companion object {
        private const val TAG = "VpsService"
    }

    override val provider: AiProvider = AiProvider.VPS

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    // Store last audio response for playback
    var lastAudioResponse: ByteArray? = null
        private set

    override suspend fun transcribe(
        pcmAudioData: ByteArray,
        languageCode: String
    ): SpeechResult {
        // VPS doesn't do standalone STT — audio goes through /voice/audio
        // which transcribes + processes + returns TTS all at once.
        // For now, return an error suggesting to use a different STT provider.
        return SpeechResult.Error("VPS does not support standalone speech-to-text")
    }

    override suspend fun chat(userMessage: String): String = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Chat request: ${userMessage.take(100)}")

            val jsonBody = """
                {"text": ${escapeJson(userMessage)}, "session_id": ${escapeJson(sessionId)}}
            """.trimIndent()

            val requestBuilder = Request.Builder()
                .url("${baseUrl.trimEnd('/')}/voice")
                .post(jsonBody.toRequestBody("application/json".toMediaType()))

            if (authToken.isNotBlank()) {
                requestBuilder.addHeader("Authorization", "Bearer $authToken")
            }

            val response = client.newCall(requestBuilder.build()).execute()

            if (!response.isSuccessful) {
                val errorBody = response.body?.string() ?: "Unknown error"
                Log.e(TAG, "Chat failed: ${response.code} - $errorBody")
                return@withContext "Error: ${response.code} - $errorBody"
            }

            // Extract transcript from header
            val transcript = response.header("X-Transcript")?.let {
                URLDecoder.decode(it, "UTF-8")
            } ?: ""

            // Store audio for playback
            lastAudioResponse = response.body?.bytes()

            Log.d(TAG, "Chat response: ${transcript.take(100)}")
            transcript.ifBlank { "Response received but no transcript available." }
        } catch (e: Exception) {
            Log.e(TAG, "Chat error", e)
            "Error communicating with VPS: ${e.message}"
        }
    }

    override suspend fun analyzeImage(
        imageData: ByteArray,
        prompt: String
    ): String = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Photo analysis: ${imageData.size} bytes, prompt: ${prompt.take(100)}")

            val requestBody = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart(
                    "image", "photo.jpg",
                    imageData.toRequestBody("image/jpeg".toMediaType())
                )
                .addFormDataPart("prompt", prompt)
                .addFormDataPart("session_id", sessionId)
                .build()

            val requestBuilder = Request.Builder()
                .url("${baseUrl.trimEnd('/')}/voice/photo")
                .post(requestBody)

            if (authToken.isNotBlank()) {
                requestBuilder.addHeader("Authorization", "Bearer $authToken")
            }

            val response = client.newCall(requestBuilder.build()).execute()

            if (!response.isSuccessful) {
                val errorBody = response.body?.string() ?: "Unknown error"
                Log.e(TAG, "Photo analysis failed: ${response.code} - $errorBody")
                return@withContext "Error analysing photo: ${response.code}"
            }

            // Extract transcript from header
            val transcript = response.header("X-Transcript")?.let {
                URLDecoder.decode(it, "UTF-8")
            } ?: ""

            // Store audio for playback
            lastAudioResponse = response.body?.bytes()

            val elapsed = response.header("X-Duration-Ms") ?: "?"
            Log.d(TAG, "Photo analysis done in ${elapsed}ms: ${transcript.take(100)}")

            transcript.ifBlank { "Photo received but no analysis available." }
        } catch (e: Exception) {
            Log.e(TAG, "Photo analysis error", e)
            "Error analysing photo: ${e.message}"
        }
    }

    override fun clearHistory() {
        lastAudioResponse = null
    }

    private fun escapeJson(value: String): String {
        return "\"${value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")}\""
    }
}
