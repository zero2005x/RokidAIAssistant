package com.example.rokidphone.service.stt

import com.example.rokidphone.service.SpeechErrorCode
import com.example.rokidphone.service.SpeechResult
import com.google.common.truth.Truth.assertThat
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.IOException

/**
 * Streaming behaviour of [IbmWatsonSttService] over an offline IAM endpoint and
 * WebSocket. The sibling IbmWatsonSttServiceTest covers provider metadata.
 */
@RunWith(RobolectricTestRunner::class)
class IbmWatsonStreamingTest {

    private val service = IbmWatsonSttService("fixture-key", "https://api.eu-gb.speech-to-text.watson.cloud.ibm.com/")
    private val transport = OfflineSttTransport(service)
    private val audio = ByteArray(2_000) { (it % 127).toByte() }

    @After fun cleanup() = unmockkAll()

    private fun token() {
        transport.http = { request ->
            transport.response(request, body = """{"access_token":"offline-iam-token"}""")
        }
    }

    private fun result(listener: WebSocketListener, transcript: String?, isFinal: Boolean = true) {
        val alternatives = JSONArray()
        if (transcript != null) alternatives.put(JSONObject().put("transcript", transcript))
        val json = JSONObject().put(
            "results",
            JSONArray().put(JSONObject().put("final", isFinal).put("alternatives", alternatives))
        )
        listener.onMessage(transport.socket, json.toString())
    }

    @Test
    fun `a final alternative is returned and the session is configured and closed`() = runBlocking {
        token()
        transport.events = { listener ->
            listener.onMessage(transport.socket, JSONObject().put("state", "listening").toString())
            // Interim and empty result sets are ignored.
            result(listener, "ignored interim", isFinal = false)
            listener.onMessage(transport.socket, JSONObject().put("results", JSONArray()).toString())
            result(listener, "  hello watson  ")
        }

        assertThat(service.transcribe(audio, "en-US")).isEqualTo(SpeechResult.Success("hello watson"))

        val start = JSONObject(transport.sentText.first())
        assertThat(start.getString("action")).isEqualTo("start")
        assertThat(start.getString("content-type")).contains("rate=16000")
        assertThat(start.getBoolean("smart_formatting")).isTrue()
        assertThat(start.getBoolean("interim_results")).isFalse()
        assertThat(JSONObject(transport.sentText.last()).getString("action")).isEqualTo("stop")
        assertThat(transport.sentBinary.single().toByteArray()).isEqualTo(audio)
        // The https service URL becomes a wss WebSocket URL carrying the model.
        val socketRequest = transport.requests.last()
        assertThat(socketRequest.url.scheme).isEqualTo("https") // OkHttp normalises wss to https
        assertThat(socketRequest.url.encodedPath).endsWith("/v1/recognize")
        assertThat(socketRequest.url.queryParameter("model")).isEqualTo("en-US_BroadbandModel")
        assertThat(socketRequest.header("Authorization")).isEqualTo("Bearer offline-iam-token")
        verify { transport.socket.close(1000, "Final result received") }
    }

    @Test
    fun `a final result with no alternatives leaves the session waiting`() = runBlocking {
        token()
        transport.events = { listener ->
            result(listener, transcript = null)
            listener.onClosed(transport.socket, 1000, "bye")
        }

        assertThat((service.transcribe(audio, "en") as SpeechResult.Error).errorCode)
            .isEqualTo(SpeechErrorCode.NO_SPEECH_DETECTED)
    }

    @Test
    fun `server errors, malformed frames and transport failures are reported`() = runBlocking {
        token()
        transport.events = {
            it.onMessage(transport.socket, JSONObject().put("error", "model not found").toString())
        }
        val rejected = service.transcribe(audio, "en") as SpeechResult.Error
        assertThat(rejected.errorCode).isEqualTo(SpeechErrorCode.TRANSCRIPTION_ERROR)
        assertThat(rejected.message).contains("model not found")

        transport.events = { it.onMessage(transport.socket, "not json") }
        assertThat((service.transcribe(audio, "en") as SpeechResult.Error).errorCode)
            .isEqualTo(SpeechErrorCode.TRANSCRIPTION_ERROR)

        transport.events = { it.onFailure(transport.socket, IOException("offline"), null) }
        assertThat((service.transcribe(audio, "en") as SpeechResult.Error).message).contains("offline")
    }

    @Test
    fun `no websocket is opened when an IAM token cannot be obtained`() = runBlocking {
        for ((code, body) in listOf(401 to "{}", 200 to "{}", 200 to "not json", 200 to """{"access_token":""}""")) {
            transport.http = { transport.response(it, code, body) }
            val result = service.transcribe(audio, "en") as SpeechResult.Error
            assertThat(result.errorCode).isEqualTo(SpeechErrorCode.RECOGNITION_FAILED)
            assertThat(result.message).contains("access token")
            assertThat(service.validateCredentials())
                .isInstanceOf(SttValidationResult.Invalid::class.java)
        }

        transport.http = { throw IOException("offline") }
        assertThat((service.transcribe(audio, "en") as SpeechResult.Error).errorCode)
            .isEqualTo(SpeechErrorCode.RECOGNITION_FAILED)

        token()
        assertThat(service.validateCredentials()).isEqualTo(SttValidationResult.Valid)
        verify(exactly = 0) { transport.client.newWebSocket(any(), any()) }
    }

    @Test
    fun `an http service url is downgraded to a ws websocket url`() = runBlocking {
        val plain = IbmWatsonSttService("k", "http://watson.local/", model = "de-DE_BroadbandModel")
        val offline = OfflineSttTransport(plain)
        offline.http = { offline.response(it, body = """{"access_token":"t"}""") }
        offline.events = { it.onClosed(offline.socket, 1000, "bye") }

        plain.transcribe(ByteArray(4), "de")

        val url = offline.requests.last().url
        assertThat(url.scheme).isEqualTo("http") // OkHttp normalises ws to http
        assertThat(url.queryParameter("model")).isEqualTo("de-DE_BroadbandModel")
    }
}
