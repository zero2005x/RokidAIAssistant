package com.example.rokidaiassistant.services

import android.util.Base64
import com.example.rokidaiassistant.data.Constants
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

/**
 * Request building and response parsing for [SpeechToTextService], driven through an
 * in-memory call factory so no HTTP request leaves the test.
 */
@RunWith(RobolectricTestRunner::class)
class SpeechToTextServiceTest {

    private val requests = mutableListOf<Request>()
    private var respond: (Request) -> Response = { error("Unexpected request: ${it.url}") }
    private val audio = ByteArray(3_200) { (it % 127).toByte() }

    private val service = SpeechToTextService(
        object : Call.Factory {
            override fun newCall(request: Request): Call {
                requests += request
                return mockk { every { execute() } answers { respond(request) } }
            }
        }
    )

    @After fun cleanup() = unmockkAll()

    private fun response(request: Request, code: Int = 200, body: String = "{}") = Response.Builder()
        .request(request).protocol(Protocol.HTTP_1_1).code(code).message("offline fixture")
        .body(body.toResponseBody()).build()

    private fun sentBody(): String {
        val buffer = Buffer()
        requests.last().body!!.writeTo(buffer)
        return buffer.readUtf8()
    }

    @Test
    fun `whisper receives a WAV upload and its text is returned`() = runBlocking {
        respond = { response(it, body = JSONObject().put("text", "  hello there  ").toString()) }

        val result = service.transcribeWithWhisper(audio, apiKey = "sk-1")

        assertThat(result.getOrNull()).isEqualTo("hello there")
        val request = requests.single()
        assertThat(request.url.toString()).isEqualTo("https://api.openai.com/v1/audio/transcriptions")
        assertThat(request.header("Authorization")).isEqualTo("Bearer sk-1")
        val body = sentBody()
        assertThat(body).contains("whisper-1")
        assertThat(body).contains("audio.wav")
        // The PCM is wrapped in a RIFF/WAVE container before upload.
        assertThat(body).contains("RIFF")
        assertThat(body).contains("WAVE")
    }

    @Test
    fun `whisper reports empty audio, api errors and unrecognised speech`() = runBlocking {
        assertThat(service.transcribeWithWhisper(ByteArray(0), "sk-1").exceptionOrNull())
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThat(requests).isEmpty()

        respond = { response(it, code = 401, body = "unauthorised") }
        assertThat(service.transcribeWithWhisper(audio, "sk-1").exceptionOrNull())
            .hasMessageThat().contains("401")

        respond = { response(it, body = JSONObject().put("text", "   ").toString()) }
        assertThat(service.transcribeWithWhisper(audio, "sk-1").getOrNull())
            .isEqualTo("(Unable to recognize speech)")

        respond = { throw IOException("dns failure") }
        assertThat(service.transcribeWithWhisper(audio, "sk-1").exceptionOrNull())
            .hasMessageThat().isEqualTo("dns failure")
    }

    @Test
    fun `google speech receives the encoded audio and its transcript is joined`() = runBlocking {
        respond = {
            response(
                it,
                body = JSONObject().put(
                    "results",
                    JSONArray()
                        .put(JSONObject().put("alternatives", JSONArray()
                            .put(JSONObject().put("transcript", "hello "))))
                        .put(JSONObject().put("alternatives", JSONArray()
                            .put(JSONObject().put("transcript", "there"))))
                        // A result with no alternatives contributes nothing.
                        .put(JSONObject())
                ).toString()
            )
        }

        val result = service.transcribeWithGoogle(audio, apiKey = "google-key")

        assertThat(result.getOrNull()).isEqualTo("hello there")
        val request = requests.single()
        assertThat(request.url.toString())
            .isEqualTo("https://speech.googleapis.com/v1/speech:recognize")
        assertThat(request.header("X-Goog-Api-Key")).isEqualTo("google-key")
        val body = JSONObject(sentBody())
        val config = body.getJSONObject("config")
        assertThat(config.getString("encoding")).isEqualTo("LINEAR16")
        assertThat(config.getInt("sampleRateHertz")).isEqualTo(Constants.AUDIO_SAMPLE_RATE)
        assertThat(config.getString("languageCode")).isEqualTo("zh-TW")
        assertThat(config.getBoolean("enableAutomaticPunctuation")).isTrue()
        assertThat(Base64.decode(body.getJSONObject("audio").getString("content"), Base64.NO_WRAP))
            .isEqualTo(audio)
    }

    @Test
    fun `google speech reports empty audio, api errors and empty results`() = runBlocking {
        assertThat(service.transcribeWithGoogle(ByteArray(0), "key").exceptionOrNull())
            .isInstanceOf(IllegalArgumentException::class.java)

        respond = { response(it, code = 403, body = "forbidden") }
        assertThat(service.transcribeWithGoogle(audio, "key").exceptionOrNull())
            .hasMessageThat().contains("403")

        respond = { response(it, body = "{}") }
        assertThat(service.transcribeWithGoogle(audio, "key").getOrNull())
            .isEqualTo("(Unable to recognize speech)")

        respond = { response(it, body = JSONObject().put("results", JSONArray()).toString()) }
        assertThat(service.transcribeWithGoogle(audio, "key").getOrNull())
            .isEqualTo("(Unable to recognize speech)")

        // Results present but every transcript blank.
        respond = {
            response(it, body = JSONObject().put("results", JSONArray()
                .put(JSONObject().put("alternatives", JSONArray()
                    .put(JSONObject().put("transcript", " "))))).toString())
        }
        assertThat(service.transcribeWithGoogle(audio, "key").getOrNull())
            .isEqualTo("(Unable to recognize speech)")

        respond = { throw IOException("offline") }
        assertThat(service.transcribeWithGoogle(audio, "key").exceptionOrNull())
            .hasMessageThat().isEqualTo("offline")
    }

    @Test
    fun `the combined entry point falls back to a placeholder when nothing is configured`() =
        runBlocking {
            // No OpenAI key is configured in the test build, so no request is attempted.
            val result = service.transcribe(audio)

            assertThat(result.isSuccess).isTrue()
            if (Constants.OPENAI_API_KEY.isBlank()) {
                assertThat(requests).isEmpty()
                assertThat(result.getOrNull()).contains("mock speech recognition result")
            }
        }
}
