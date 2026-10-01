package com.example.rokidphone.service.stt

import com.example.rokidphone.service.SpeechErrorCode
import com.example.rokidphone.service.SpeechResult
import com.google.common.truth.Truth.assertThat
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import okhttp3.WebSocketListener
import org.json.JSONObject
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.IOException

/**
 * Streaming behaviour of [SpeechmaticsSttService] over an offline WebSocket.
 * The sibling SpeechmaticsSttServiceTest covers credential validation and metadata.
 */
@RunWith(RobolectricTestRunner::class)
class SpeechmaticsStreamingTest {

    private val service = SpeechmaticsSttService("fixture-key")
    private val transport = OfflineSttTransport(service)
    // Larger than the 8000-byte chunk so the chunking loop runs more than once.
    private val audio = ByteArray(20_000) { (it % 127).toByte() }

    @After fun cleanup() = unmockkAll()

    private fun transcript(listener: WebSocketListener, message: String, text: String? = null) {
        val json = JSONObject().put("message", message)
        if (text != null) json.put("metadata", JSONObject().put("transcript", text))
        listener.onMessage(transport.socket, json.toString())
    }

    private fun sentMessages() = transport.sentText.map { JSONObject(it).getString("message") }

    @Test
    fun `the session is announced, chunked and closed, and final segments are joined`() = runBlocking {
        transport.events = { listener ->
            transcript(listener, "RecognitionStarted")
            transcript(listener, "AudioAdded")
            transcript(listener, "AddPartialTranscript", "ignored interim")
            transcript(listener, "AddTranscript", "hello")
            transcript(listener, "AddTranscript", "")
            transcript(listener, "AddTranscript", "world")
            transcript(listener, "Info")
            transcript(listener, "Warning")
            transcript(listener, "EndOfTranscript")
        }

        assertThat(service.transcribe(audio, "en-US")).isEqualTo(SpeechResult.Success("hello world"))

        assertThat(sentMessages())
            .containsExactly("StartRecognition", "AddAudio", "EndOfStream").inOrder()
        val start = JSONObject(transport.sentText.first())
        assertThat(start.getJSONObject("audio_format").getString("encoding")).isEqualTo("pcm_s16le")
        assertThat(start.getJSONObject("transcription_config").getString("language")).isEqualTo("en-US")
        // 20000 bytes at 8000 per frame.
        assertThat(transport.sentBinary).hasSize(3)
        assertThat(transport.sentBinary.fold(ByteArray(0)) { acc, b -> acc + b.toByteArray() })
            .isEqualTo(audio)
        assertThat(transport.requests.single().header("Authorization")).isEqualTo("Bearer fixture-key")
        verify { transport.socket.close(1000, "Completed") }
    }

    @Test
    fun `the configured language is used when the caller does not name one`() = runBlocking {
        val configured = SpeechmaticsSttService(
            "fixture-key", language = "de", enableDiarization = true, enableEntities = true,
            operatingPoint = "enhanced"
        )
        val offline = OfflineSttTransport(configured)
        offline.events = { transcript(it, "EndOfTranscript") }

        configured.transcribe(ByteArray(4), "")

        val config = JSONObject(offline.sentText.first()).getJSONObject("transcription_config")
        assertThat(config.getString("language")).isEqualTo("de")
        assertThat(config.getString("operating_point")).isEqualTo("enhanced")
        assertThat(config.getString("diarization")).isEqualTo("speaker")
        assertThat(config.getBoolean("enable_entities")).isTrue()
    }

    @Test
    fun `a session that ends without any transcript is reported as no speech`() = runBlocking {
        transport.events = { transcript(it, "EndOfTranscript") }
        val result = service.transcribe(audio, "en") as SpeechResult.Error
        assertThat(result.errorCode).isEqualTo(SpeechErrorCode.NO_SPEECH_DETECTED)

        // A socket closed by the server, with nothing transcribed, is the same outcome.
        transport.events = { it.onClosed(transport.socket, 1000, "bye") }
        assertThat((service.transcribe(audio, "en") as SpeechResult.Error).errorCode)
            .isEqualTo(SpeechErrorCode.NO_SPEECH_DETECTED)
    }

    @Test
    fun `server errors, malformed frames and transport failures are reported`() = runBlocking {
        transport.events = { listener ->
            listener.onMessage(
                transport.socket,
                JSONObject().put("message", "Error").put("type", "not_authorised")
                    .put("reason", "bad key").toString()
            )
        }
        val rejected = service.transcribe(audio, "en") as SpeechResult.Error
        assertThat(rejected.errorCode).isEqualTo(SpeechErrorCode.TRANSCRIPTION_ERROR)
        assertThat(rejected.message).contains("bad key")

        transport.events = { it.onMessage(transport.socket, "not json") }
        assertThat((service.transcribe(audio, "en") as SpeechResult.Error).errorCode)
            .isEqualTo(SpeechErrorCode.TRANSCRIPTION_ERROR)

        transport.events = { it.onFailure(transport.socket, IOException("offline"), null) }
        val failed = service.transcribe(audio, "en") as SpeechResult.Error
        assertThat(failed.errorCode).isEqualTo(SpeechErrorCode.TRANSCRIPTION_ERROR)
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
