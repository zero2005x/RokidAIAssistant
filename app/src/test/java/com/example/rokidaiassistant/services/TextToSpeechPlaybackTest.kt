package com.example.rokidaiassistant.services

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString.Companion.toByteString
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.IOException
import java.util.Locale

/**
 * Covers the fallback chain of the app module's [TextToSpeechService]: Edge TTS,
 * then Google Translate, then system TTS. Both network backends are substituted,
 * so no socket is opened.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class TextToSpeechPlaybackTest {

    private lateinit var context: Context
    private val engine = mockk<TextToSpeech>(relaxed = true)
    private val callFactory = mockk<Call.Factory>()
    private val socket = mockk<WebSocket>(relaxed = true)

    /** What the fake Edge WebSocket replays once the client subscribes. */
    private var edgeResponse: (WebSocketListener, WebSocket) -> Unit = { _, _ -> }
    private var googleResponse: () -> Response = { error("no Google Translate request expected") }

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        context = ApplicationProvider.getApplicationContext()
        every { callFactory.newCall(any()) } answers {
            val request = firstArg<Request>()
            mockk<Call> { every { execute() } answers { googleResponse().newBuilder().request(request).build() } }
        }
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        unmockkAll()
    }

    private fun response(code: Int, body: ByteArray): Response = Response.Builder()
        .request(Request.Builder().url("https://translate.google.com/").build())
        .protocol(Protocol.HTTP_1_1).code(code).message("stub")
        .body(body.toResponseBody()).build()

    private fun audioFrame(payload: ByteArray) =
        ("Path:audio".encodeToByteArray() + byteArrayOf(0, 0x82.toByte()) + payload).toByteString()

    private fun service(systemTtsReady: Boolean = true): TextToSpeechService {
        val factory = object : WebSocket.Factory {
            override fun newWebSocket(request: Request, listener: WebSocketListener): WebSocket {
                listener.onOpen(socket, mockk(relaxed = true))
                edgeResponse(listener, socket)
                return socket
            }
        }
        val service = TextToSpeechService(
            context,
            preferredLocale = null,
            edgeTtsClient = EdgeTtsClient(Dispatchers.Unconfined, factory),
            httpClient = callFactory
        )
        field(service, "systemTts", engine)
        field(service, "isSystemTtsReady", systemTtsReady)
        every { engine.setLanguage(any()) } returns TextToSpeech.LANG_AVAILABLE
        every { engine.voices } returns emptySet()
        return service
    }

    private fun field(service: TextToSpeechService, name: String, value: Any?) {
        TextToSpeechService::class.java.getDeclaredField(name)
            .apply { isAccessible = true }.set(service, value)
    }

    @Test
    fun `blank text completes immediately without reaching any backend`() = runTest {
        var completed = 0
        assertThat(service().speak("   ") { completed++ }.isSuccess).isTrue()
        assertThat(completed).isEqualTo(1)
        verify(exactly = 0) { engine.speak(any<CharSequence>(), any(), any(), any()) }
    }

    @Test
    fun `synthesized audio is played and neither fallback is used`() = runTest {
        edgeResponse = { listener, ws ->
            listener.onMessage(ws, audioFrame(byteArrayOf(1, 2, 3)))
            listener.onMessage(ws, "Path:turn.end")
        }

        assertThat(service().speakWithEdgeTts("hello").isSuccess).isTrue()

        verify(exactly = 0) { callFactory.newCall(any()) }
        verify(exactly = 0) { engine.speak(any<CharSequence>(), any(), any(), any()) }
    }

    @Test
    fun `an Edge failure falls back to Google Translate audio`() = runTest {
        edgeResponse = { listener, ws -> listener.onFailure(ws, IOException("offline"), null) }
        googleResponse = { response(200, byteArrayOf(9, 9)) }

        assertThat(service().speakWithEdgeTts("hello").isSuccess).isTrue()

        val request = slot<Request>()
        verify { callFactory.newCall(capture(request)) }
        assertThat(request.captured.url.queryParameter("q")).isEqualTo("hello")
        assertThat(request.captured.url.queryParameter("tl")).isNotEmpty()
        verify(exactly = 0) { engine.speak(any<CharSequence>(), any(), any(), any()) }
    }

    @Test
    fun `empty Edge audio and an unusable Google response fall through to system speech`() = runTest {
        // Turn end with no audio frames, then a Google response that cannot be used.
        edgeResponse = { listener, ws -> listener.onMessage(ws, "Path:turn.end") }
        googleResponse = { response(503, ByteArray(0)) }

        service().speakWithEdgeTts("hello")
        verify { engine.speak("hello", TextToSpeech.QUEUE_FLUSH, any(), any()) }

        googleResponse = { throw IOException("dns failure") }
        service().speakWithEdgeTts("hello")
        verify(exactly = 2) { engine.speak("hello", TextToSpeech.QUEUE_FLUSH, any(), any()) }
    }

    @Test
    fun `system speech reports completion through the utterance listener`() = runTest {
        val listener = slot<UtteranceProgressListener>()
        every { engine.setOnUtteranceProgressListener(capture(listener)) } returns TextToSpeech.SUCCESS
        var completed = 0

        assertThat(service().speakWithSystemTts("hello") { completed++ }.isSuccess).isTrue()

        listener.captured.onStart("id")
        assertThat(completed).isEqualTo(0)
        listener.captured.onDone("id")
        assertThat(completed).isEqualTo(1)
        listener.captured.onError("id", TextToSpeech.ERROR_NETWORK)
        assertThat(completed).isEqualTo(2)
    }

    @Test
    fun `an unready engine fails fast and still notifies the caller`() = runTest {
        var completed = 0
        val result = service(systemTtsReady = false).speakWithSystemTts("hello") { completed++ }

        assertThat(result.isFailure).isTrue()
        assertThat(completed).isEqualTo(1)
        verify(exactly = 0) { engine.speak(any<CharSequence>(), any(), any(), any()) }
    }

    @Test
    fun `an unsupported locale falls back to the device default`() = runTest {
        // Built first: service() installs a catch-all setLanguage stub that would
        // otherwise override the per-locale stubs below.
        val service = service()

        every { engine.setLanguage(Locale.JAPANESE) } returns TextToSpeech.LANG_NOT_SUPPORTED
        service.speakWithSystemTts("こんにちは")
        verify { engine.setLanguage(Locale.getDefault()) }

        every { engine.setLanguage(Locale.JAPANESE) } returns TextToSpeech.LANG_MISSING_DATA
        service.speakWithSystemTts("こんにちは")
        verify(atLeast = 2) { engine.setLanguage(Locale.getDefault()) }
    }

    @Test
    fun `stop and release tear the engine down`() = runTest {
        val service = service()
        service.stop()
        verify { engine.stop() }
        service.release()
        verify { engine.shutdown() }
    }
}
