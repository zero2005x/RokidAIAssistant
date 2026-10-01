package com.example.rokidphone.service.stt

import com.example.rokidphone.service.SpeechErrorCode
import com.example.rokidphone.service.SpeechResult
import com.google.common.truth.Truth.assertThat
import io.mockk.*
import kotlinx.coroutines.runBlocking
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONObject
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
class AliyunStreamingTest {
    private val service = AliyunSttService("fixture-id", "fixture-secret", "fixture-app")
    private val transport = OfflineSttTransport(service)
    private val audio = ByteArray(2000) { (it % 127).toByte() }

    @After fun cleanup() = unmockkAll()

    private fun token() {
        transport.http = { request -> transport.response(request, body = """{"Token":{"Id":"offline-token","ExpireTime":4102444800}}""") }
    }

    private fun message(listener: WebSocketListener, name: String, result: String? = null, status: Int = 20000000) {
        val json = JSONObject().put("header", JSONObject().put("name", name).put("status", status))
        if (result != null) json.put("payload", JSONObject().put("result", result))
        listener.onMessage(transport.socket, json.toString())
    }

    @Test fun `stream sends audio only after start and returns the last nonblank result`() = runBlocking {
        token()
        transport.events = { listener ->
            assertThat(transport.sentBinary).isEmpty()
            message(listener, "TranscriptionStarted")
            message(listener, "TranscriptionResultChanged", " interim ")
            message(listener, "TranscriptionResultChanged", " ")
            message(listener, "TranscriptionResultChanged")
            listener.onMessage(transport.socket, "{}")
            message(listener, "TranscriptionCompleted", " final ")
            listener.onClosed(transport.socket, 1000, "done")
        }
        assertThat(service.transcribe(audio, "zh-TW")).isEqualTo(SpeechResult.Success("final"))
        assertThat(transport.sentBinary.single().toByteArray()).isEqualTo(audio)
        assertThat(transport.sentText.map { JSONObject(it).getJSONObject("header").getString("name") })
            .containsExactly("StartTranscription", "StopTranscription").inOrder()
        assertThat(transport.requests.first().url.queryParameter("Signature")).isNotEmpty()
        assertThat(transport.requests.last().header("X-NLS-Token")).isEqualTo("offline-token")
        assertThat(service.validateCredentials()).isEqualTo(SttValidationResult.Valid)
        assertThat(transport.requests.count { it.url.host.startsWith("nls-meta") }).isEqualTo(1)
        verify(exactly = 1) { transport.socket.close(1000, any()) }
    }

    @Test fun `completed response can use interim text but empty results fail`() = runBlocking {
        token()
        transport.events = { message(it, "TranscriptionResultChanged", " interim "); message(it, "TranscriptionCompleted") }
        assertThat(service.transcribe(audio, "zh")).isEqualTo(SpeechResult.Success("interim"))
        transport.events = { message(it, "TranscriptionCompleted", " ") }
        assertThat((service.transcribe(audio, "zh") as SpeechResult.Error).errorCode).isEqualTo(SpeechErrorCode.RECOGNITION_FAILED)
    }

    @Test fun `server errors malformed responses and premature close return actionable errors`() = runBlocking {
        token()
        val scenarios: List<Pair<(WebSocketListener) -> Unit, SpeechErrorCode>> = listOf(
            ({ listener: WebSocketListener -> message(listener, "TaskFailed") }) to SpeechErrorCode.RECOGNITION_FAILED,
            ({ listener: WebSocketListener -> message(listener, "unknown", status = 400) }) to SpeechErrorCode.RECOGNITION_FAILED,
            ({ listener: WebSocketListener -> listener.onMessage(transport.socket, "invalid-json") }) to SpeechErrorCode.RECOGNITION_FAILED,
            ({ listener: WebSocketListener -> listener.onFailure(transport.socket, IOException("offline"), null) }) to SpeechErrorCode.NETWORK_ERROR,
            ({ listener: WebSocketListener -> listener.onClosed(transport.socket, 1006, "gone") }) to SpeechErrorCode.NETWORK_ERROR
        )
        for ((event, code) in scenarios) {
            transport.events = event
            assertThat((service.transcribe(audio, "zh") as SpeechResult.Error).errorCode).isEqualTo(code)
        }
    }

    @Test fun `failed start audio and stop writes terminate without waiting for a timeout`() = runBlocking {
        token()
        transport.events = { message(it, "TranscriptionStarted") }
        every { transport.socket.send(any<String>()) } returns false
        assertThat((service.transcribe(audio, "zh") as SpeechResult.Error).errorCode).isEqualTo(SpeechErrorCode.NETWORK_ERROR)
        every { transport.socket.send(any<String>()) } returns true
        every { transport.socket.send(any<ByteString>()) } returns false
        assertThat((service.transcribe(audio, "zh") as SpeechResult.Error).message).contains("send audio")
        every { transport.socket.send(any<ByteString>()) } returns true
        every { transport.socket.send(any<String>()) } answers { !firstArg<String>().contains("StopTranscription") }
        assertThat((service.transcribe(audio, "zh") as SpeechResult.Error).message).contains("stop Aliyun")
    }

    @Test fun `invalid token responses never open a transcription connection`() = runBlocking {
        for ((code, body) in listOf(403 to "{}", 200 to "{}", 200 to "invalid-json", 200 to """{"Token":{"Id":""}}""")) {
            transport.http = { transport.response(it, code, body) }
            assertThat((service.transcribe(audio, "zh") as SpeechResult.Error).message).contains("access token")
        }
        transport.http = { throw IOException("offline") }
        assertThat(service.validateCredentials()).isInstanceOf(SttValidationResult.Invalid::class.java)
        verify(exactly = 0) { transport.client.newWebSocket(any(), any()) }
    }
}
