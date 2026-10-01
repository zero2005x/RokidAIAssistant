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
 * Streaming behaviour of [HuaweiSisSttService] over an offline WebSocket. Audio is
 * uploaded from a dedicated sender thread that paces itself at 100ms per 3200-byte
 * chunk, so these tests keep the payload to a single chunk.
 */
@RunWith(RobolectricTestRunner::class)
class HuaweiSisStreamingTest {

    private val service = HuaweiSisSttService(
        accessKey = "ak", secretKey = "sk", projectId = "project-1"
    )
    private val transport = OfflineSttTransport(service)
    private val audio = ByteArray(3_200) { (it % 127).toByte() }

    @After fun cleanup() = unmockkAll()

    private fun segments(listener: WebSocketListener, vararg texts: String?) {
        val segments = JSONArray()
        texts.forEach { text ->
            val segment = JSONObject()
            if (text != null) segment.put("result", JSONObject().put("text", text))
            segments.put(segment)
        }
        listener.onMessage(
            transport.socket,
            JSONObject().put("status", 0).put("segments", segments).toString()
        )
    }

    private fun complete(listener: WebSocketListener) {
        listener.onMessage(transport.socket, JSONObject().put("trace_id", "t-1").toString())
    }

    @Test
    fun `segments are joined and the session is signed, configured and closed`() = runBlocking {
        transport.events = { listener ->
            listener.onMessage(transport.socket, JSONObject().put("status", 1).toString())
            listener.onMessage(transport.socket, JSONObject().put("status", 2).toString())
            // A segment without a result, and one with empty text, contribute nothing.
            segments(listener, " 你好", null, "", "世界 ")
            complete(listener)
        }

        assertThat(service.transcribe(audio, "zh-CN")).isEqualTo(SpeechResult.Success("你好世界"))

        val start = JSONObject(transport.sentText.first())
        assertThat(start.getString("command")).isEqualTo("START")
        assertThat(start.getJSONObject("config").getString("audio_format")).isEqualTo("pcm16k16bit")
        assertThat(start.getJSONObject("config").getString("property"))
            .isEqualTo("chinese_16k_common")
        assertThat(start.getJSONObject("config").getString("add_punc")).isEqualTo("yes")

        val url = transport.requests.single().url
        assertThat(url.host).isEqualTo("sis-ext.cn-north-4.myhuaweicloud.com")
        assertThat(url.encodedPath).isEqualTo("/v1/project-1/rasr/short-stream")
        assertThat(url.queryParameter("projectId")).isEqualTo("project-1")
        assertThat(url.queryParameter("nonce")).isNotEmpty()
        // HMAC-SHA256 rendered as lowercase hex.
        assertThat(url.queryParameter("signature")).matches("[0-9a-f]{64}")
        verify { transport.socket.close(1000, "Completed") }
    }

    @Test
    fun `a session that recognises nothing reports no speech`() = runBlocking {
        transport.events = { complete(it) }
        assertThat((service.transcribe(audio, "zh") as SpeechResult.Error).errorCode)
            .isEqualTo(SpeechErrorCode.NO_SPEECH_DETECTED)

        transport.events = { it.onClosed(transport.socket, 1000, "bye") }
        assertThat((service.transcribe(audio, "zh") as SpeechResult.Error).errorCode)
            .isEqualTo(SpeechErrorCode.NO_SPEECH_DETECTED)
    }

    @Test
    fun `server errors, malformed frames and transport failures are reported`() = runBlocking {
        transport.events = {
            it.onMessage(
                transport.socket,
                JSONObject().put("status", 9).put("message", "invalid signature").toString()
            )
        }
        val rejected = service.transcribe(audio, "zh") as SpeechResult.Error
        assertThat(rejected.errorCode).isEqualTo(SpeechErrorCode.TRANSCRIPTION_ERROR)
        assertThat(rejected.message).contains("invalid signature")

        transport.events = { it.onMessage(transport.socket, "not json") }
        assertThat((service.transcribe(audio, "zh") as SpeechResult.Error).errorCode)
            .isEqualTo(SpeechErrorCode.TRANSCRIPTION_ERROR)

        transport.events = { it.onFailure(transport.socket, IOException("offline"), null) }
        assertThat((service.transcribe(audio, "zh") as SpeechResult.Error).message).contains("offline")
    }

    @Test
    fun `a connection that cannot be opened is reported rather than thrown`() = runBlocking {
        transport.events = { throw IOException("no route to host") }

        val result = service.transcribe(audio, "zh") as SpeechResult.Error

        assertThat(result.errorCode).isEqualTo(SpeechErrorCode.TRANSCRIPTION_ERROR)
        assertThat(result.message).contains("no route to host")
    }

    @Test
    fun `every credential must be present`() = runBlocking {
        assertThat(service.validateCredentials()).isEqualTo(SttValidationResult.Valid)
        for (blanked in listOf(
            HuaweiSisSttService(" ", "sk", projectId = "p"),
            HuaweiSisSttService("ak", " ", projectId = "p"),
            HuaweiSisSttService("ak", "sk", projectId = " ")
        )) {
            assertThat(blanked.validateCredentials())
                .isInstanceOf(SttValidationResult.Invalid::class.java)
        }
    }
}
