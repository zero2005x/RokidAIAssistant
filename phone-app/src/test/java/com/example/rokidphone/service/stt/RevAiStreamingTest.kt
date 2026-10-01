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
 * Streaming behaviour of [RevAiSttService] over an offline WebSocket.
 * The sibling RevAiSttServiceTest covers credential validation and metadata.
 */
@RunWith(RobolectricTestRunner::class)
class RevAiStreamingTest {

    private val service = RevAiSttService("fixture-token")
    private val transport = OfflineSttTransport(service)
    // Larger than the 3200-byte chunk so the chunking loop runs more than once.
    private val audio = ByteArray(8_000) { (it % 127).toByte() }

    @After fun cleanup() = unmockkAll()

    private fun elements(vararg parts: Pair<String, String>) = JSONArray().apply {
        parts.forEach { (type, value) ->
            put(JSONObject().put("type", type).put("value", value))
        }
    }

    private fun event(listener: WebSocketListener, type: String, build: JSONObject.() -> Unit = {}) {
        listener.onMessage(transport.socket, JSONObject().put("type", type).apply(build).toString())
    }

    @Test
    fun `words and punctuation are joined across final results`() = runBlocking {
        transport.events = { listener ->
            event(listener, "connected") { put("id", "job-1") }
            event(listener, "partial") { put("elements", elements("text" to "ignored")) }
            event(listener, "final") {
                put("elements", elements("text" to "hello", "punct" to ", ", "text" to "world"))
            }
            // An element type the extractor does not know is skipped.
            event(listener, "final") { put("elements", elements("unknown" to "x", "punct" to "!")) }
            event(listener, "final") { put("elements", elements()) }
            event(listener, "final")
            event(listener, "close")
        }

        assertThat(service.transcribe(audio, "en-US")).isEqualTo(SpeechResult.Success("hello, world !"))

        val config = JSONObject(transport.sentText.first())
        assertThat(config.getString("type")).isEqualTo("connect")
        assertThat(config.getString("content_type")).contains("rate=16000")
        assertThat(JSONObject(transport.sentText.last()).getString("type")).isEqualTo("eos")
        assertThat(transport.sentBinary).hasSize(3) // 8000 bytes at 3200 per frame
        assertThat(transport.requests.single().url.queryParameter("language")).isEqualTo("en-US")
        assertThat(transport.requests.single().header("Authorization")).isEqualTo("Bearer fixture-token")
        verify { transport.socket.close(1000, "Completed") }
    }

    @Test
    fun `optional job settings are sent and the configured language is the fallback`() = runBlocking {
        val configured = RevAiSttService(
            "fixture-token", language = "fr", metadata = "session-7",
            customVocabularyId = "vocab-1", filterProfanity = true
        )
        val offline = OfflineSttTransport(configured)
        offline.events = { it.onMessage(offline.socket, JSONObject().put("type", "close").toString()) }

        configured.transcribe(ByteArray(10), "")

        val config = JSONObject(offline.sentText.first())
        assertThat(config.getString("metadata")).isEqualTo("session-7")
        assertThat(config.getString("custom_vocabulary_id")).isEqualTo("vocab-1")
        assertThat(config.getBoolean("filter_profanity")).isTrue()
        assertThat(offline.requests.single().url.queryParameter("language")).isEqualTo("fr")
    }

    @Test
    fun `a stream that ends with nothing recognised reports no speech`() = runBlocking {
        transport.events = { event(it, "close") }
        assertThat((service.transcribe(audio, "en") as SpeechResult.Error).errorCode)
            .isEqualTo(SpeechErrorCode.NO_SPEECH_DETECTED)

        transport.events = { it.onClosed(transport.socket, 1000, "bye") }
        assertThat((service.transcribe(audio, "en") as SpeechResult.Error).errorCode)
            .isEqualTo(SpeechErrorCode.NO_SPEECH_DETECTED)
    }

    @Test
    fun `server errors, malformed frames and transport failures are reported`() = runBlocking {
        transport.events = { event(it, "error") { put("message", "invalid token") } }
        val rejected = service.transcribe(audio, "en") as SpeechResult.Error
        assertThat(rejected.errorCode).isEqualTo(SpeechErrorCode.TRANSCRIPTION_ERROR)
        assertThat(rejected.message).contains("invalid token")

        transport.events = { it.onMessage(transport.socket, "not json") }
        assertThat((service.transcribe(audio, "en") as SpeechResult.Error).errorCode)
            .isEqualTo(SpeechErrorCode.TRANSCRIPTION_ERROR)

        transport.events = { it.onFailure(transport.socket, IOException("offline"), null) }
        val failed = service.transcribe(audio, "en") as SpeechResult.Error
        assertThat(failed.message).contains("offline")
    }

    @Test
    fun `a connection that cannot be opened is reported rather than thrown`() = runBlocking {
        transport.events = { throw IOException("no route to host") }

        val result = service.transcribe(audio, "en") as SpeechResult.Error

        assertThat(result.errorCode).isEqualTo(SpeechErrorCode.TRANSCRIPTION_ERROR)
        assertThat(result.message).contains("no route to host")
    }
}
