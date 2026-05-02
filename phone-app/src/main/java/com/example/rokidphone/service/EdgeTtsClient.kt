package com.example.rokidphone.service

import android.util.Log
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.*
import okio.ByteString
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Microsoft Edge TTS Client (phone-app copy).
 *
 * Uses Edge browser's TTS WebSocket API for free, high-quality neural speech
 * synthesis.  This is a self-contained copy so that `phone-app` does not need
 * a module dependency on `app`.
 */
class EdgeTtsClient(
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {

    companion object {
        private const val TAG = "EdgeTtsClient"

        // Edge TTS WebSocket endpoint
        private const val WSS_URL =
            "wss://speech.platform.bing.com/consumer/speech/synthesize/readaloud/edge/v1"

        // User Agent
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36 Edg/120.0.0.0"

        // Default voice
        const val VOICE_XIAOXIAO = "zh-CN-XiaoxiaoNeural"

        // Timeout duration
        private const val TIMEOUT_SECONDS = 30L
    }

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .writeTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()

    /**
     * Synthesize speech.
     *
     * @param text   Text to synthesize
     * @param voice  Voice name (e.g. "ko-KR-SunHiNeural")
     * @param rate   Speech rate ("-50%" ~ "+100%")
     * @param pitch  Pitch ("-50Hz" ~ "+50Hz")
     * @param volume Volume ("-50%" ~ "+50%")
     * @return MP3 audio data wrapped in [Result]
     */
    suspend fun synthesize(
        text: String,
        voice: String = VOICE_XIAOXIAO,
        rate: String = "+0%",
        pitch: String = "+0Hz",
        volume: String = "+0%"
    ): Result<ByteArray> = withContext(ioDispatcher) {
        try {
            Log.d(TAG, "Starting speech synthesis: voice=$voice, text=${text.take(50)}...")

            val audioData = ByteArrayOutputStream()
            val latch = CountDownLatch(1)
            val errorRef = arrayOfNulls<Exception>(1)

            val requestId = generateRequestId()
            val wsUrl = "$WSS_URL?TrustedClientToken=${getTrustedToken()}&ConnectionId=$requestId"

            val request = Request.Builder()
                .url(wsUrl)
                .header("User-Agent", USER_AGENT)
                .header("Origin", "chrome-extension://jdiccldimpdaibmpdkjnbmckianbfold")
                .build()

            val webSocket = httpClient.newWebSocket(
                request,
                createWebSocketListener(requestId, text, voice, rate, pitch, volume, audioData, latch, errorRef)
            )

            if (!latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                webSocket.cancel()
                return@withContext Result.failure(Exception("Speech synthesis timeout"))
            }

            errorRef[0]?.let {
                return@withContext Result.failure(it)
            }

            val result = audioData.toByteArray()
            Log.d(TAG, "Speech synthesis complete, size: ${result.size} bytes")

            if (result.isEmpty()) {
                return@withContext Result.failure(Exception("Synthesis result is empty"))
            }

            Result.success(result)
        } catch (e: Exception) {
            Log.e(TAG, "Speech synthesis failed", e)
            Result.failure(e)
        }
    }

    @Suppress("LongParameterList")
    private fun createWebSocketListener(
        requestId: String,
        text: String,
        voice: String,
        rate: String,
        pitch: String,
        volume: String,
        audioData: ByteArrayOutputStream,
        latch: CountDownLatch,
        errorRef: Array<Exception?>
    ): WebSocketListener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            Log.d(TAG, "WebSocket connection successful")
            webSocket.send(buildConfigMessage())
            webSocket.send(buildSsmlMessage(requestId, text, voice, rate, pitch, volume))
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            val data = bytes.toByteArray()
            val headerEnd = findHeaderEnd(data)
            if (headerEnd > 0 && headerEnd < data.size) {
                audioData.write(data, headerEnd, data.size - headerEnd)
            }
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            if (text.contains("Path:turn.end")) {
                Log.d(TAG, "Received end signal")
                webSocket.close(1000, "Completed")
                latch.countDown()
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            Log.e(TAG, "WebSocket error", t)
            errorRef[0] = Exception("WebSocket error: ${t.message}")
            latch.countDown()
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            Log.d(TAG, "WebSocket closed: code=$code, reason=$reason")
            if (latch.count > 0) latch.countDown()
        }
    }

    private fun generateRequestId(): String =
        UUID.randomUUID().toString().replace("-", "")

    private fun getTrustedToken(): String =
        "6A5AA1D4EAFF4E9FB37E23D68491D6F4"

    private fun buildConfigMessage(): String {
        val timestamp = getTimestamp()
        return """
            X-Timestamp:$timestamp
            Content-Type:application/json; charset=utf-8
            Path:speech.config

            {"context":{"synthesis":{"audio":{"metadataoptions":{"sentenceBoundaryEnabled":"false","wordBoundaryEnabled":"false"},"outputFormat":"audio-24khz-48kbitrate-mono-mp3"}}}}
        """.trimIndent()
    }

    private fun buildSsmlMessage(
        requestId: String,
        text: String,
        voice: String,
        rate: String,
        pitch: String,
        volume: String
    ): String {
        val timestamp = getTimestamp()
        val escapedText = text
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")

        val ssml = """
            <speak version='1.0' xmlns='http://www.w3.org/2001/10/synthesis' xml:lang='zh-CN'>
                <voice name='$voice'>
                    <prosody pitch='$pitch' rate='$rate' volume='$volume'>
                        $escapedText
                    </prosody>
                </voice>
            </speak>
        """.trimIndent()

        return """
            X-RequestId:$requestId
            Content-Type:application/ssml+xml
            X-Timestamp:$timestamp
            Path:ssml

            $ssml
        """.trimIndent()
    }

    private fun getTimestamp(): String {
        val sdf = SimpleDateFormat("EEE MMM dd yyyy HH:mm:ss 'GMT'Z", Locale.US)
        return sdf.format(Date())
    }

    private fun findHeaderEnd(data: ByteArray): Int {
        val pathAudio = "Path:audio".toByteArray()

        for (i in 0 until data.size - pathAudio.size) {
            if (!matchesAt(data, i, pathAudio)) continue
            val bodyStart = findBodyStart(data, i + pathAudio.size)
            return bodyStart ?: (i + pathAudio.size + 2)
        }
        return -1
    }

    private fun matchesAt(data: ByteArray, offset: Int, pattern: ByteArray): Boolean {
        for (j in pattern.indices) {
            if (data[offset + j] != pattern[j]) return false
        }
        return true
    }

    private fun findBodyStart(data: ByteArray, from: Int): Int? {
        for (k in from until data.size - 1) {
            if (data[k] == 0x00.toByte() && data[k + 1] == 0x82.toByte()) {
                return k + 2
            }
        }
        return null
    }
}
