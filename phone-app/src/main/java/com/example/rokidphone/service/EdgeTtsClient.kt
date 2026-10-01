package com.example.rokidphone.service

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.*
import okio.ByteString
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.coroutineContext

/**
 * Microsoft Edge TTS Client (phone-app copy).
 *
 * Uses Edge browser's TTS WebSocket API for free, high-quality neural speech
 * synthesis.  This is a self-contained copy so that `phone-app` does not need
 * a module dependency on `app`.
 */
class EdgeTtsClient(
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val webSocketFactory: WebSocket.Factory = sharedHttpClient
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

        // Shared client: each OkHttpClient owns connection pool/dispatcher
        // threads, so it must be reused instead of created per instance.
        private val sharedHttpClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .writeTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .build()
        }

        // SimpleDateFormat is not thread-safe; keep one instance per thread.
        // The pattern claims GMT, so the time zone must be GMT explicitly.
        private val timestampFormat = object : ThreadLocal<SimpleDateFormat>() {
            override fun initialValue(): SimpleDateFormat =
                SimpleDateFormat("EEE MMM dd yyyy HH:mm:ss 'GMT'Z", Locale.US).apply {
                    timeZone = TimeZone.getTimeZone("GMT")
                }
        }
    }

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
        val audioData = ByteArrayOutputStream()
        val latch = CountDownLatch(1)
        val errorRef = arrayOfNulls<Exception>(1)
        val turnEndSeen = AtomicBoolean(false)
        var webSocket: WebSocket? = null
        try {
            Log.d(TAG, "Starting speech synthesis: voice=$voice, text=${text.take(50)}...")
            val requestId = generateRequestId()
            val wsUrl = "$WSS_URL?TrustedClientToken=${getTrustedToken()}&ConnectionId=$requestId"

            val request = Request.Builder()
                .url(wsUrl)
                .header("User-Agent", USER_AGENT)
                .header("Origin", "chrome-extension://jdiccldimpdaibmpdkjnbmckianbfold")
                .build()

            webSocket = webSocketFactory.newWebSocket(
                request,
                createWebSocketListener(requestId, text, voice, rate, pitch, volume, audioData, latch, errorRef, turnEndSeen)
            )

            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS)
            while (!latch.await(100, TimeUnit.MILLISECONDS)) {
                coroutineContext.ensureActive()
                if (System.nanoTime() >= deadline) {
                    webSocket?.cancel()
                    return@withContext Result.failure(Exception("Speech synthesis timeout"))
                }
            }

            errorRef[0]?.let {
                return@withContext Result.failure(it)
            }

            // The OkHttp reader thread writes to audioData; synchronize so a
            // racing write cannot corrupt the buffer while it is read here.
            val result = synchronized(audioData) { audioData.toByteArray() }
            Log.d(TAG, "Speech synthesis complete, size: ${result.size} bytes")

            if (result.isEmpty()) {
                return@withContext Result.failure(Exception("Synthesis result is empty"))
            }

            Result.success(result)
        } catch (e: CancellationException) {
            webSocket?.cancel()
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Speech synthesis failed", e)
            Result.failure(e)
        } finally {
            // Ensure the connection is released on every exit path (timeout,
            // error, cancellation) so sockets/reader threads cannot leak.
            webSocket?.cancel()
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
        errorRef: Array<Exception?>,
        turnEndSeen: AtomicBoolean
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
                // Written from the OkHttp reader thread; the coroutine thread
                // reads the buffer under the same monitor.
                synchronized(audioData) {
                    audioData.write(data, headerEnd, data.size - headerEnd)
                }
            } else {
                Log.w(TAG, "Discarding unparsable binary frame (${data.size} bytes)")
            }
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            if (text.contains("Path:turn.end")) {
                Log.d(TAG, "Received end signal")
                turnEndSeen.set(true)
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
            if (!turnEndSeen.get()) {
                // Server closed the socket before Path:turn.end: report the real
                // cause instead of silently returning truncated/empty audio.
                errorRef[0] = Exception("WebSocket closed before synthesis completed: code=$code, reason=$reason")
            }
            latch.countDown()
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
        val escapedText = escapeXml(text)
        // Derive the synthesis locale from the voice name (e.g. ko-KR-SunHiNeural -> ko-KR)
        val lang = voice.split('-').take(2).joinToString("-").ifBlank { "zh-CN" }

        val ssml = """
            <speak version='1.0' xmlns='http://www.w3.org/2001/10/synthesis' xml:lang='$lang'>
                <voice name='${escapeXml(voice)}'>
                    <prosody pitch='${escapeXml(pitch)}' rate='${escapeXml(rate)}' volume='${escapeXml(volume)}'>
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
        return timestampFormat.get().format(Date())
    }

    /** Escape a value for safe interpolation into SSML text or attributes. */
    private fun escapeXml(value: String): String = value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;")

    private fun findHeaderEnd(data: ByteArray): Int {
        val pathAudio = "Path:audio".toByteArray()

        // Inclusive upper bound: a match may end exactly at the end of the frame.
        for (i in 0..data.size - pathAudio.size) {
            if (!matchesAt(data, i, pathAudio)) continue
            // Only trust the explicit 0x00 0x82 body delimiter; guessing an
            // offset would corrupt the MP3 stream, so skip unparseable matches.
            return findBodyStart(data, i + pathAudio.size) ?: continue
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
