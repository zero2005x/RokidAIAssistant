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
 * Streaming behaviour of [TencentSttService] over an offline WebSocket.
 * The sibling TencentSttServiceTest covers provider metadata.
 */
@RunWith(RobolectricTestRunner::class)
class TencentStreamingTest {

    private val service = TencentSttService("secret-id", "secret-key", "app-1")
    private val transport = OfflineSttTransport(service)
    private val audio = ByteArray(32_000) { (it % 127).toByte() }

    @After fun cleanup() = unmockkAll()

    private fun result(
        listener: WebSocketListener, text: String?, sliceType: Int = 0, isFinal: Int = 0
    ) {
        val json = JSONObject().put("code", 0).put("final", isFinal)
        if (text != null) {
            json.put("result", JSONObject().put("voice_text_str", text).put("slice_type", sliceType))
        }
        listener.onMessage(transport.socket, json.toString())
    }

    @Test
    fun `the session is signed, the audio uploaded and the final text returned`() = runBlocking {
        transport.events = { listener ->
            result(listener, "你好", sliceType = 0)
            result(listener, "你好世界", sliceType = 2)
            result(listener, null)
            result(listener, "你好世界", isFinal = 1)
            listener.onClosed(transport.socket, 1000, "Done")
        }

        assertThat(service.transcribe(audio, "zh-CN")).isEqualTo(SpeechResult.Success("你好世界"))

        assertThat(transport.sentBinary.single().toByteArray()).isEqualTo(audio)
        assertThat(JSONObject(transport.sentText.single()).getString("type")).isEqualTo("end")
        val url = transport.requests.single().url
        assertThat(url.host).isEqualTo("asr.cloud.tencent.com")
        assertThat(url.encodedPath).isEqualTo("/asr/v2/app-1")
        assertThat(url.queryParameter("secretid")).isEqualTo("secret-id")
        assertThat(url.queryParameter("engine_model_type")).isEqualTo("16k_zh")
        assertThat(url.queryParameter("voice_format")).isEqualTo("1")
        assertThat(url.queryParameter("voice_id")).isNotEmpty()
        assertThat(url.queryParameter("signature")).isNotEmpty()
        verify { transport.socket.close(1000, "Done") }
    }

    @Test
    fun `audio shorter than the minimum never opens a connection`() = runBlocking {
        val result = service.transcribe(ByteArray(8), "zh") as SpeechResult.Error

        assertThat(result.errorCode).isEqualTo(SpeechErrorCode.AUDIO_TOO_SHORT)
        verify(exactly = 0) { transport.client.newWebSocket(any(), any()) }
    }

    @Test
    fun `a session that recognises nothing reports no speech`() = runBlocking {
        transport.events = { it.onClosed(transport.socket, 1000, "Done") }

        assertThat((service.transcribe(audio, "zh") as SpeechResult.Error).errorCode)
            .isEqualTo(SpeechErrorCode.NO_SPEECH_DETECTED)
    }

    @Test
    fun `a service error is reported when the socket closes`() = runBlocking {
        transport.events = { listener ->
            listener.onMessage(
                transport.socket,
                JSONObject().put("code", 4001).put("message", "invalid signature").toString()
            )
            listener.onClosed(transport.socket, 1000, "Error")
        }

        val result = service.transcribe(audio, "zh") as SpeechResult.Error
        assertThat(result.errorCode).isEqualTo(SpeechErrorCode.RECOGNITION_FAILED)
        assertThat(result.message).contains("invalid signature")
    }

    @Test
    fun `malformed frames are skipped without ending the session`() = runBlocking {
        transport.events = { listener ->
            listener.onMessage(transport.socket, "not json")
            result(listener, "recovered", isFinal = 1)
            listener.onClosed(transport.socket, 1000, "Done")
        }

        assertThat(service.transcribe(audio, "zh")).isEqualTo(SpeechResult.Success("recovered"))
    }

    @Test
    fun `a transport failure is reported as a network error`() = runBlocking {
        transport.events = { it.onFailure(transport.socket, IOException("offline"), null) }

        val result = service.transcribe(audio, "zh") as SpeechResult.Error
        assertThat(result.errorCode).isEqualTo(SpeechErrorCode.NETWORK_ERROR)
        assertThat(result.message).contains("offline")
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
            TencentSttService(" ", "k", "a"),
            TencentSttService("s", " ", "a"),
            TencentSttService("s", "k", " ")
        )) {
            assertThat(blanked.validateCredentials())
                .isInstanceOf(SttValidationResult.Invalid::class.java)
        }
    }
}
