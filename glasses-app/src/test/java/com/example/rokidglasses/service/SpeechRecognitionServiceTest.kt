package com.example.rokidglasses.service

import android.util.Base64
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import kotlinx.coroutines.runBlocking
import okhttp3.Call
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Request building and response handling for [SpeechRecognitionService], driven
 * through an in-memory call factory so no request leaves the test.
 */
@RunWith(RobolectricTestRunner::class)
class SpeechRecognitionServiceTest {

    private val requests = mutableListOf<Request>()
    private var respond: (Request) -> Response = { error("Unexpected request: ${it.url}") }
    private val audio = ByteArray(2_000) { (it % 127).toByte() }

    private val service = SpeechRecognitionService(
        apiKey = "gemini-key",
        client = object : Call.Factory {
            override fun newCall(request: Request): Call {
                requests += request
                return mockk { every { execute() } answers { respond(request) } }
            }
        }
    )

    @After fun cleanup() = unmockkAll()

    private fun response(request: Request, code: Int = 200, body: String = "{}") =
        Response.Builder()
            .request(request).protocol(Protocol.HTTP_1_1).code(code).message("offline fixture")
            .body(body.toResponseBody()).build()

    /** A Gemini reply whose first candidate part carries [text]. */
    private fun candidateWith(text: String): String = JSONObject().put(
        "candidates",
        JSONArray().put(
            JSONObject().put(
                "content",
                JSONObject().put("parts", JSONArray().put(JSONObject().put("text", text)))
            )
        )
    ).toString()

    private fun sentBody(): JSONObject {
        val buffer = Buffer()
        requests.last().body!!.writeTo(buffer)
        return JSONObject(buffer.readUtf8())
    }

    private fun uploadedAudio(): ByteArray {
        val part = sentBody().getJSONArray("contents").getJSONObject(0)
            .getJSONArray("parts").getJSONObject(0).getJSONObject("inline_data")
        assertThat(part.getString("mime_type")).isEqualTo("audio/wav")
        return Base64.decode(part.getString("data"), Base64.NO_WRAP)
    }

    @Test
    fun `the audio is wrapped in a WAV container and the transcript returned`() = runBlocking {
        respond = { response(it, body = candidateWith("  hello there  ")) }

        val result = service.transcribe(audio)

        assertThat(result).isEqualTo(TranscriptionResult.Success("hello there"))

        val wav = uploadedAudio()
        assertThat(String(wav, 0, 4)).isEqualTo("RIFF")
        assertThat(String(wav, 8, 4)).isEqualTo("WAVE")
        assertThat(String(wav, 12, 4)).isEqualTo("fmt ")
        assertThat(String(wav, 36, 4)).isEqualTo("data")
        // The PCM follows the 44-byte header untouched.
        assertThat(wav.size).isEqualTo(44 + audio.size)
        assertThat(wav.copyOfRange(44, wav.size)).isEqualTo(audio)
    }

    @Test
    fun `the WAV header describes 16-bit mono at 16kHz`() = runBlocking {
        respond = { response(it, body = candidateWith("x")) }
        service.transcribe(audio)

        // Every numeric header field is little-endian.
        val wav = ByteBuffer.wrap(uploadedAudio()).order(ByteOrder.LITTLE_ENDIAN)
        assertThat(wav.getInt(4)).isEqualTo(36 + audio.size)   // RIFF chunk size
        assertThat(wav.getInt(16)).isEqualTo(16)               // fmt chunk size
        assertThat(wav.getShort(20)).isEqualTo(1.toShort())    // PCM
        assertThat(wav.getShort(22)).isEqualTo(1.toShort())    // mono
        assertThat(wav.getInt(24)).isEqualTo(16_000)           // sample rate
        assertThat(wav.getInt(28)).isEqualTo(32_000)           // byte rate
        assertThat(wav.getShort(32)).isEqualTo(2.toShort())    // block align
        assertThat(wav.getShort(34)).isEqualTo(16.toShort())   // bits per sample
        assertThat(wav.getInt(40)).isEqualTo(audio.size)       // data chunk size
    }

    @Test
    fun `the key travels in the query and the model is asked for a plain transcript`() =
        runBlocking {
            respond = { response(it, body = candidateWith("x")) }
            service.transcribe(audio)

            val request = requests.single()
            assertThat(request.method).isEqualTo("POST")
            assertThat(request.url.encodedPath)
                .isEqualTo("/v1beta/models/gemini-2.5-flash:generateContent")
            assertThat(request.url.queryParameter("key")).isEqualTo("gemini-key")
            assertThat(request.header("Content-Type")).isEqualTo("application/json")

            val body = sentBody()
            val instruction = body.getJSONArray("contents").getJSONObject(0)
                .getJSONArray("parts").getJSONObject(1).getString("text")
            assertThat(instruction).contains("transcribe")
            val config = body.getJSONObject("generationConfig")
            assertThat(config.getDouble("temperature")).isEqualTo(0.1)
            assertThat(config.getInt("maxOutputTokens")).isEqualTo(500)
        }

    @Test
    fun `the default client is built when no transport is supplied`() {
        // Constructing without a client evaluates the shared default, which is the
        // configuration the app ships with. Building it opens no connection.
        val shipped = SpeechRecognitionService(apiKey = "gemini-key")

        assertThat(shipped).isNotNull()
    }

    @Test
    fun `audio below a thousand bytes never reaches the network`() = runBlocking {
        val result = service.transcribe(ByteArray(999))

        assertThat(result).isEqualTo(TranscriptionResult.Error("Audio too short"))
        assertThat(requests).isEmpty()
    }

    @Test
    fun `a reply saying nothing was heard is reported as an error`() = runBlocking {
        for (text in listOf("Unable to recognize", "there was no sound at all", "")) {
            respond = { response(it, body = candidateWith(text)) }

            val result = service.transcribe(audio) as TranscriptionResult.Error
            assertThat(result.message).isEqualTo("Unable to recognize speech")
            assertThat(result.isNetworkError).isFalse()
        }
    }

    @Test
    fun `a reply without a usable candidate is reported as unparseable`() = runBlocking {
        val unusable = listOf(
            "{}",
            JSONObject().put("candidates", JSONArray()).toString(),
            // A candidate with no content at all.
            JSONObject().put("candidates", JSONArray().put(JSONObject())).toString(),
            // Content present but no parts.
            JSONObject().put(
                "candidates",
                JSONArray().put(JSONObject().put("content", JSONObject()))
            ).toString(),
            // Parts present but empty.
            JSONObject().put(
                "candidates",
                JSONArray().put(
                    JSONObject().put("content", JSONObject().put("parts", JSONArray()))
                )
            ).toString()
        )

        for (body in unusable) {
            respond = { response(it, body = body) }

            assertThat(service.transcribe(audio))
                .isEqualTo(TranscriptionResult.Error("Unable to parse response"))
        }
    }

    @Test
    fun `an unsuccessful response carries its status code`() = runBlocking {
        respond = { response(it, code = 429, body = "rate limited") }

        val result = service.transcribe(audio) as TranscriptionResult.Error
        assertThat(result.message).isEqualTo("API Error: 429")
        assertThat(result.isNetworkError).isFalse()

        // An error response with an empty body is handled the same way.
        respond = { response(it, code = 500, body = "") }
        assertThat((service.transcribe(audio) as TranscriptionResult.Error).message)
            .isEqualTo("API Error: 500")
    }

    @Test
    fun `connection problems are flagged as network errors`() = runBlocking {
        respond = { throw UnknownHostException("generativelanguage.googleapis.com") }

        val offline = service.transcribe(audio) as TranscriptionResult.Error
        assertThat(offline.isNetworkError).isTrue()
        assertThat(offline.message).contains("Network connection failed")

        respond = { throw SocketTimeoutException("read timed out") }

        val timeout = service.transcribe(audio) as TranscriptionResult.Error
        assertThat(timeout.isNetworkError).isTrue()
        assertThat(timeout.message).contains("Connection timeout")
    }

    @Test
    fun `any other failure is reported without claiming a network fault`() = runBlocking {
        respond = { throw IOException("connection reset") }

        val result = service.transcribe(audio) as TranscriptionResult.Error
        assertThat(result.message).isEqualTo("Error: connection reset")
        assertThat(result.isNetworkError).isFalse()

        // A malformed body fails while parsing rather than while connecting.
        respond = { response(it, body = "not json") }
        val malformed = service.transcribe(audio) as TranscriptionResult.Error
        assertThat(malformed.message).startsWith("Error:")
        assertThat(malformed.isNetworkError).isFalse()
    }
}
