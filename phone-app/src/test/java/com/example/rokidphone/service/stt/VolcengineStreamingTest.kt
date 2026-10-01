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
 * Streaming behaviour of [VolcengineSttService] over an offline WebSocket.
 * The sibling VolcengineSttServiceTest covers credential validation and metadata.
 */
@RunWith(RobolectricTestRunner::class)
class VolcengineStreamingTest {

    private val service = VolcengineSttService("app-1", "ak", "sk")
    private val transport = OfflineSttTransport(service)
    private val audio = ByteArray(2_000) { (it % 127).toByte() }

    @After fun cleanup() = unmockkAll()

    private fun result(listener: WebSocketListener, text: String, isFinal: Boolean) {
        listener.onMessage(
            transport.socket,
            JSONObject().put(
                "result", JSONObject().put("text", text).put("is_final", isFinal)
            ).toString()
        )
    }

    private fun fullResponse(listener: WebSocketListener, text: String?) {
        val result = JSONObject()
        if (text != null) result.put("text", text)
        listener.onMessage(
            transport.socket,
            JSONObject().put("full_server_response", JSONObject().put("result", result)).toString()
        )
    }

    @Test
    fun `the session is signed and the last final result is returned`() = runBlocking {
        transport.events = { listener ->
            result(listener, "interim", isFinal = false)
            result(listener, "", isFinal = true)
            result(listener, "  你好世界  ", isFinal = true)
            listener.onMessage(transport.socket, JSONObject().put("code", 1000).toString())
            fullResponse(listener, null)
        }

        assertThat(service.transcribe(audio, "zh-TW")).isEqualTo(SpeechResult.Success("你好世界"))

        val start = JSONObject(transport.sentText.first()).getJSONObject("full_client_request")
        assertThat(start.getJSONObject("app").getString("appid")).isEqualTo("app-1")
        assertThat(start.getJSONObject("app").getString("cluster"))
            .isEqualTo("volcengine_streaming_common")
        assertThat(start.getJSONObject("audio").getString("language")).isEqualTo("zh-CN")
        assertThat(start.getJSONObject("audio").getInt("rate")).isEqualTo(16000)
        assertThat(JSONObject(transport.sentText.last()).getString("signal")).isEqualTo("finish")
        assertThat(transport.sentBinary.single().toByteArray()).isEqualTo(audio)

        // The URL carries the app id and an AK/timestamp/nonce/signature token.
        val url = transport.requests.single().url
        assertThat(url.host).isEqualTo("openspeech.bytedance.com")
        assertThat(url.queryParameter("appid")).isEqualTo("app-1")
        assertThat(url.queryParameter("token")!!.split(";")).hasSize(4)
        assertThat(url.queryParameter("token")).startsWith("ak;")
        verify { transport.socket.close(1000, "Completed") }
    }

    @Test
    fun `a non-Chinese request asks for English and the final response carries the text`() = runBlocking {
        transport.events = { fullResponse(it, "  hello there  ") }

        assertThat(service.transcribe(audio, "en-US")).isEqualTo(SpeechResult.Success("hello there"))

        val audioConfig = JSONObject(transport.sentText.first())
            .getJSONObject("full_client_request").getJSONObject("audio")
        assertThat(audioConfig.getString("language")).isEqualTo("en-US")
    }

    @Test
    fun `a session that recognises nothing reports no speech`() = runBlocking {
        transport.events = { fullResponse(it, null) }
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
                JSONObject().put("code", 4003).put("message", "quota exceeded").toString()
            )
        }
        val rejected = service.transcribe(audio, "zh") as SpeechResult.Error
        assertThat(rejected.errorCode).isEqualTo(SpeechErrorCode.TRANSCRIPTION_ERROR)
        assertThat(rejected.message).contains("quota exceeded")

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
            VolcengineSttService(" ", "ak", "sk"),
            VolcengineSttService("app-1", " ", "sk"),
            VolcengineSttService("app-1", "ak", " ")
        )) {
            assertThat(blanked.validateCredentials())
                .isInstanceOf(SttValidationResult.Invalid::class.java)
        }
    }
}
