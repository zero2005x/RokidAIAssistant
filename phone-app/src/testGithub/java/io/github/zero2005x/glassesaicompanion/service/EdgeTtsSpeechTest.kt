package io.github.zero2005x.glassesaicompanion.service

import android.speech.tts.TextToSpeech
import io.github.zero2005x.glassesaicompanion.data.TtsProvider
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.spyk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import com.google.common.truth.Truth.assertThat
import java.io.IOException
import okhttp3.Request
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Edge TTS exists only in the GitHub flavor; the Play flavor falls back to the system engine. */
@RunWith(RobolectricTestRunner::class)
@OptIn(ExperimentalCoroutinesApi::class)
class EdgeTtsSpeechTest : TextToSpeechServiceTestBase() {
    /**
     * Drives [TextToSpeechService] against a real [EdgeTtsClient] wired to a fake
     * WebSocket. The client returns `Result<ByteArray>`, and mockk re-boxes value
     * classes it hands back, so a stubbed client would deliver a `Result` where the
     * caller expects the byte array; serving the frames instead keeps the contract
     * honest and exercises the client's own parsing.
     */
    private var edgeResponse: (WebSocketListener, WebSocket) -> Unit = { _, _ -> }
    private val sentFrames = mutableListOf<String>()

    private fun audioFrame(payload: ByteArray): ByteString =
        ("Path:audio".encodeToByteArray() + byteArrayOf(0, 0x82.toByte()) + payload).toByteString()

    private fun useEdge(scope: TestScope) {
        service = spyk(service)
        val factory = object : WebSocket.Factory {
            override fun newWebSocket(request: Request, listener: WebSocketListener): WebSocket {
                val socket = mockk<WebSocket>(relaxed = true)
                every { socket.send(capture(sentFrames)) } returns true
                listener.onOpen(socket, mockk(relaxed = true))
                edgeResponse(listener, socket)
                return socket
            }
        }
        field("edgeTtsClient", EdgeTtsClient(Dispatchers.Unconfined, factory))
        field("ttsScope", scope)
        field("mainDispatcher", Dispatchers.Unconfined)
        settings = settings.copy(ttsProvider = TtsProvider.EDGE_TTS)
        coEvery { service.playAudioData(any()) } just Runs
    }

    @Test
    fun `Edge speech applies the selected Korean voice and delivers encoded audio before playback`() {
        val scope = TestScope()
        useEdge(scope)
        // ttsSpeechRate is a multiplier (1.25 -> +25%); ttsPitch is an offset (0.2 -> +12Hz).
        settings = settings.copy(ttsVoiceOverride = "ko-KR-SunHiNeural", ttsSpeechRate = 1.25f, ttsPitch = 0.2f)
        edgeResponse = { listener, socket ->
            listener.onMessage(socket, audioFrame(byteArrayOf(1, 2)))
            listener.onMessage(socket, audioFrame(byteArrayOf(3)))
            listener.onMessage(socket, "Path:turn.end")
        }
        val chunks = mutableListOf<ByteArray>()
        service.speak("안녕 你好") { chunks += it }
        scope.runCurrent()
        assertThat(chunks.single()).isEqualTo(byteArrayOf(1, 2, 3))
        val ssml = sentFrames.last()
        assertThat(ssml).contains("xml:lang='ko-KR'")
        assertThat(ssml).contains("name='ko-KR-SunHiNeural'")
        assertThat(ssml).contains("rate='+25%'")
        assertThat(ssml).contains("pitch='+12Hz'")
        // Han characters are dropped so a Korean voice does not read them aloud.
        assertThat(ssml).contains("안녕")
        assertThat(ssml).doesNotContain("你好")
        coVerify(exactly = 1) { service.playAudioData(byteArrayOf(1, 2, 3)) }
        verify(exactly = 0) { engine.speak(any<CharSequence>(), any(), any(), any()) }
    }

    @Test
    fun `empty truncated and failed Edge requests fall back to system speech`() {
        val scope = TestScope()
        useEdge(scope)
        settings = settings.copy(ttsVoiceOverride = "", speechLanguage = "en-US")
        // Turn end with no audio frames.
        edgeResponse = { listener, socket -> listener.onMessage(socket, "Path:turn.end") }
        service.speak("hello") { error("Empty audio must not be delivered") }
        scope.runCurrent()
        // Socket closed before the server signalled turn end.
        edgeResponse = { listener, socket -> listener.onClosed(socket, 1006, "interrupted") }
        service.speak("hello") { error("Truncated audio must not be delivered") }
        scope.runCurrent()
        // Transport failure.
        edgeResponse = { listener, socket -> listener.onFailure(socket, IOException("offline"), null) }
        service.speak("hello") { error("Failed audio must not be delivered") }
        scope.runCurrent()
        assertThat(sentFrames.last()).contains("name='en-US-JennyNeural'")
        verify(exactly = 3) { engine.speak("hello", TextToSpeech.QUEUE_FLUSH, null, null) }
        coVerify(exactly = 0) { service.playAudioData(any()) }
    }
}
