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
import java.io.ByteArrayOutputStream
import java.net.URLDecoder
import java.util.concurrent.TimeUnit

/**
 * Result of a combined voice processing request (audio in -> AI response out).
 */
data class VoiceResult(
    val userTranscript: String,
    val aiResponseText: String,
    val aiResponseAudio: ByteArray?
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as VoiceResult
        return userTranscript == other.userTranscript &&
                aiResponseText == other.aiResponseText &&
                aiResponseAudio?.contentEquals(other.aiResponseAudio ?: byteArrayOf()) == true
    }

    override fun hashCode(): Int {
        var result = userTranscript.hashCode()
        result = 31 * result + aiResponseText.hashCode()
        result = 31 * result + (aiResponseAudio?.contentHashCode() ?: 0)
        return result
    }
}

/**
 * Private Server AI Service
 *
 * Sends audio, photos, text, and video to a self-hosted server for AI processing.
 * The server implements these endpoints:
 *
 * POST {baseUrl}/voice        (JSON: text + session_id)  -> text chat
 * POST {baseUrl}/voice        (multipart: audio + session_id) -> STT + AI + TTS
 * POST {baseUrl}/voice/photo  (multipart: image + prompt + session_id) -> photo analysis
 * POST {baseUrl}/voice/video  (multipart: video + prompt + session_id) -> video analysis
 *
 * All endpoints return: audio body + X-Transcript header (AI response text).
 * Multipart /voice also returns X-User-Transcript header (user's speech).
 *
 * Base URL and auth token are configured in app settings.
 */
class PrivateServerService(
    private val baseUrl: String,
    private val authToken: String = "",
    private val sessionId: String = "glasses-main"
) : AiServiceProvider {

    companion object {
        private const val TAG = "PrivateServerService"
    }

    override val provider: AiProvider = AiProvider.PRIVATE_SERVER

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    // Store last audio response for playback
    var lastAudioResponse: ByteArray? = null
        private set

    // Store last AI response text (from combined voice flow)
    var lastAiResponseText: String? = null
        private set

    /**
     * Transcribe audio by sending it to the VPS.
     * The VPS does STT + Claude + TTS in one call.
     * This method returns only the user's speech transcript.
     * The AI response and audio are cached in [lastAiResponseText] and [lastAudioResponse].
     */
    override suspend fun transcribe(
        pcmAudioData: ByteArray,
        languageCode: String
    ): SpeechResult = withContext(Dispatchers.IO) {
        try {
            val wavData = pcmToWav(pcmAudioData)
            Log.d(TAG, "Transcribe: sending ${wavData.size} bytes to VPS")

            val result = processVoiceAudio(wavData, "audio/wav")
            if (result != null) {
                SpeechResult.Success(result.userTranscript)
            } else {
                SpeechResult.Error("Failed to transcribe audio via server")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Transcribe error", e)
            SpeechResult.Error("Transcription error: ${e.message}")
        }
    }

    /**
     * Combined voice processing: send raw audio, get back user transcript +
     * AI response text + AI response audio in a single request.
     *
     * @param audioData Encoded audio (WAV, OGG, etc.)
     * @param mimeType MIME type of the audio
     * @return VoiceResult or null on failure
     */
    suspend fun processVoiceAudio(
        audioData: ByteArray,
        mimeType: String = "audio/wav"
    ): VoiceResult? = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "processVoiceAudio: ${audioData.size} bytes ($mimeType)")

            val ext = when {
                mimeType.contains("ogg") -> "ogg"
                mimeType.contains("mp4") || mimeType.contains("m4a") -> "m4a"
                else -> "wav"
            }

            val requestBody = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart(
                    "audio", "recording.$ext",
                    audioData.toRequestBody(mimeType.toMediaType())
                )
                .addFormDataPart("session_id", sessionId)
                .addFormDataPart("source", "glasses")
                .build()

            val requestBuilder = Request.Builder()
                .url("${baseUrl.trimEnd('/')}/voice")
                .post(requestBody)

            requestBuilder.addVoiceAuth()

            val response = client.newCall(requestBuilder.build()).execute()

            if (!response.isSuccessful) {
                val errorBody = response.body?.string() ?: "Unknown error"
                Log.e(TAG, "Voice audio failed: ${response.code} - $errorBody")
                return@withContext null
            }

            val aiTranscript = response.header("X-Transcript")?.let {
                URLDecoder.decode(it, "UTF-8")
            } ?: ""

            val userTranscript = response.header("X-User-Transcript")?.let {
                URLDecoder.decode(it, "UTF-8")
            } ?: ""

            val audioResponse = response.body?.bytes()

            // Cache for playback
            lastAudioResponse = audioResponse
            lastAiResponseText = aiTranscript

            Log.d(TAG, "Voice audio done: user='${userTranscript.take(80)}' ai='${aiTranscript.take(80)}'")

            VoiceResult(
                userTranscript = userTranscript.ifBlank { "[speech not recognized]" },
                aiResponseText = aiTranscript.ifBlank { "Response received." },
                aiResponseAudio = audioResponse
            )
        } catch (e: Exception) {
            Log.e(TAG, "processVoiceAudio error", e)
            null
        }
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

            requestBuilder.addVoiceAuth()

            val response = client.newCall(requestBuilder.build()).execute()

            if (!response.isSuccessful) {
                val errorBody = response.body?.string() ?: "Unknown error"
                Log.e(TAG, "Chat failed: ${response.code} - $errorBody")
                return@withContext "Error: ${response.code} - $errorBody"
            }

            val transcript = response.header("X-Transcript")?.let {
                URLDecoder.decode(it, "UTF-8")
            } ?: ""

            lastAudioResponse = response.body?.bytes()
            lastAiResponseText = transcript

            Log.d(TAG, "Chat response: ${transcript.take(100)}")
            transcript.ifBlank { "Response received but no transcript available." }
        } catch (e: Exception) {
            Log.e(TAG, "Chat error", e)
            "Error communicating with server: ${e.message}"
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

            requestBuilder.addBearerAuth()

            val response = client.newCall(requestBuilder.build()).execute()

            if (!response.isSuccessful) {
                val errorBody = response.body?.string() ?: "Unknown error"
                Log.e(TAG, "Photo analysis failed: ${response.code} - $errorBody")
                return@withContext "Error analysing photo: ${response.code}"
            }

            val transcript = response.header("X-Transcript")?.let {
                URLDecoder.decode(it, "UTF-8")
            } ?: ""

            lastAudioResponse = response.body?.bytes()
            lastAiResponseText = transcript

            val elapsed = response.header("X-Duration-Ms") ?: "?"
            Log.d(TAG, "Photo analysis done in ${elapsed}ms: ${transcript.take(100)}")

            transcript.ifBlank { "Photo received but no analysis available." }
        } catch (e: Exception) {
            Log.e(TAG, "Photo analysis error", e)
            "Error analysing photo: ${e.message}"
        }
    }

    /**
     * Analyze a video clip with AI.
     *
     * @param videoData Video file bytes (MP4, etc.)
     * @param prompt User prompt describing what to analyze
     * @return AI response text
     */
    suspend fun analyzeVideo(
        videoData: ByteArray,
        prompt: String = "Describe what you see in this video"
    ): String = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Video analysis: ${videoData.size} bytes, prompt: ${prompt.take(100)}")

            val requestBody = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart(
                    "video", "recording.mp4",
                    videoData.toRequestBody("video/mp4".toMediaType())
                )
                .addFormDataPart("prompt", prompt)
                .addFormDataPart("session_id", sessionId)
                .build()

            val requestBuilder = Request.Builder()
                .url("${baseUrl.trimEnd('/')}/voice/video")
                .post(requestBody)

            requestBuilder.addBearerAuth()

            val response = client.newCall(requestBuilder.build()).execute()

            if (!response.isSuccessful) {
                val errorBody = response.body?.string() ?: "Unknown error"
                Log.e(TAG, "Video analysis failed: ${response.code} - $errorBody")
                return@withContext "Error analysing video: ${response.code}"
            }

            val transcript = response.header("X-Transcript")?.let {
                URLDecoder.decode(it, "UTF-8")
            } ?: ""

            lastAudioResponse = response.body?.bytes()
            lastAiResponseText = transcript

            val elapsed = response.header("X-Duration-Ms") ?: "?"
            Log.d(TAG, "Video analysis done in ${elapsed}ms: ${transcript.take(100)}")

            transcript.ifBlank { "Video received but no analysis available." }
        } catch (e: Exception) {
            Log.e(TAG, "Video analysis error", e)
            "Error analysing video: ${e.message}"
        }
    }

    override fun clearHistory() {
        lastAudioResponse = null
        lastAiResponseText = null
    }

    /**
     * Add auth header appropriate for the endpoint.
     * /voice uses X-Auth-Token; /voice/photo and /voice/video use Authorization: Bearer.
     */
    private fun Request.Builder.addVoiceAuth(): Request.Builder {
        if (authToken.isNotBlank()) {
            addHeader("X-Auth-Token", authToken)
        }
        return this
    }

    private fun Request.Builder.addBearerAuth(): Request.Builder {
        if (authToken.isNotBlank()) {
            addHeader("Authorization", "Bearer $authToken")
        }
        return this
    }

    private fun escapeJson(value: String): String {
        return "\"${value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")}\""
    }

    /**
     * Convert PCM audio data to WAV format for server upload.
     */
    private fun pcmToWav(
        pcmData: ByteArray,
        sampleRate: Int = 16000,
        channels: Int = 1,
        bitsPerSample: Int = 16
    ): ByteArray {
        val byteRate = sampleRate * channels * bitsPerSample / 8
        val blockAlign = channels * bitsPerSample / 8
        val dataSize = pcmData.size
        val totalSize = 36 + dataSize

        val output = ByteArrayOutputStream()

        // RIFF header
        output.write("RIFF".toByteArray())
        output.write(intToBytes(totalSize, 4))
        output.write("WAVE".toByteArray())

        // fmt chunk
        output.write("fmt ".toByteArray())
        output.write(intToBytes(16, 4))
        output.write(intToBytes(1, 2))       // PCM
        output.write(intToBytes(channels, 2))
        output.write(intToBytes(sampleRate, 4))
        output.write(intToBytes(byteRate, 4))
        output.write(intToBytes(blockAlign, 2))
        output.write(intToBytes(bitsPerSample, 2))

        // data chunk
        output.write("data".toByteArray())
        output.write(intToBytes(dataSize, 4))
        output.write(pcmData)

        return output.toByteArray()
    }

    private fun intToBytes(value: Int, numBytes: Int): ByteArray {
        val bytes = ByteArray(numBytes)
        for (i in 0 until numBytes) {
            bytes[i] = (value shr (8 * i) and 0xFF).toByte()
        }
        return bytes
    }
}
