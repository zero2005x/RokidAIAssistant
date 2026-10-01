package com.example.rokidphone.service

import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString.Companion.toByteString
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
class EdgeTtsClientTest {
    private val socket = mockk<WebSocket>(relaxed = true)
    private val factory = mockk<WebSocket.Factory>()
    private val messages = mutableListOf<String>()
    private lateinit var request: Request

    private fun client(events: (WebSocketListener) -> Unit): EdgeTtsClient {
        every { socket.send(capture(messages)) } returns true
        every { factory.newWebSocket(any(), any()) } answers {
            request = firstArg()
            val listener = secondArg<WebSocketListener>()
            listener.onOpen(socket, mockk<Response>())
            events(listener)
            socket
        }
        return EdgeTtsClient(webSocketFactory = factory)
    }

    @Test
    fun `synthesis escapes SSML and concatenates audio frames until turn end`() = runTest {
        val service = client { listener ->
            listener.onMessage(socket, byteArrayOf(1, 2).toByteString())
            listener.onMessage(socket, "Path:audio without delimiter".encodeToByteArray().toByteString())
            listener.onMessage(socket, ("header Path:audio".encodeToByteArray() +
                byteArrayOf(0, 0x82.toByte(), 1, 2)).toByteString())
            listener.onMessage(socket, ("Path:audio".encodeToByteArray() +
                byteArrayOf(0, 0x82.toByte(), 3)).toByteString())
            listener.onMessage(socket, "Path:audio.metadata")
            listener.onMessage(socket, "Path:turn.end")
            listener.onClosed(socket, 1000, "Completed")
        }
        val result = service.synthesize("<&\"'>", voice = "ko-KR-SunHiNeural", rate = "-10%")
        assertThat(result.getOrThrow()).isEqualTo(byteArrayOf(1, 2, 3))
        assertThat(request.url.queryParameter("ConnectionId")).matches("[0-9a-f]{32}")
        assertThat(request.header("Origin")).startsWith("chrome-extension://")
        assertThat(messages).hasSize(2)
        assertThat(messages[0]).contains("Path:speech.config")
        assertThat(messages[0]).contains("audio-24khz-48kbitrate-mono-mp3")
        assertThat(messages[1]).contains("xml:lang='ko-KR'")
        assertThat(messages[1]).contains("&lt;&amp;&quot;&apos;&gt;")
        assertThat(messages[1]).contains("rate='-10%'")
        verify { socket.close(1000, "Completed") }
        verify { socket.cancel() }
    }

    @Test
    fun `closing before turn end rejects truncated audio`() = runTest {
        val result = client { it.onClosed(socket, 1006, "interrupted") }.synthesize("hello")
        assertThat(result.exceptionOrNull()!!.message).contains("before synthesis completed")
        verify { socket.cancel() }
    }

    @Test
    fun `websocket failure returns the cause and cancels the connection`() = runTest {
        val result = client { it.onFailure(socket, IOException("offline"), null) }.synthesize("hello")
        assertThat(result.exceptionOrNull()!!.message).contains("offline")
        verify { socket.cancel() }
    }

    @Test
    fun `turn end without audio is an error and blank voice uses default language`() = runTest {
        val result = client { it.onMessage(socket, "Path:turn.end") }.synthesize("hello", voice = "")
        assertThat(result.exceptionOrNull()!!.message).contains("empty")
        assertThat(messages[1]).contains("xml:lang='zh-CN'")
        verify { socket.cancel() }
    }

    @Test
    fun `connection setup exception is returned as failure`() = runTest {
        every { factory.newWebSocket(any(), any()) } throws IOException("cannot connect")
        val result = EdgeTtsClient(webSocketFactory = factory).synthesize("hello")
        assertThat(result.exceptionOrNull()!!.message).isEqualTo("cannot connect")
    }

    @Test
    fun `cancellation is propagated instead of converted into a provider failure`() = runTest {
        every { factory.newWebSocket(any(), any()) } throws CancellationException("cancelled")
        val failure = runCatching {
            EdgeTtsClient(webSocketFactory = factory).synthesize("hello")
        }.exceptionOrNull()
        assertThat(failure).isInstanceOf(CancellationException::class.java)
    }
}
