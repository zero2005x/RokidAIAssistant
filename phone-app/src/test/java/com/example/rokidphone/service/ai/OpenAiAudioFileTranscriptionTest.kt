package com.example.rokidphone.service.ai

import com.example.rokidphone.data.AiProvider
import com.example.rokidphone.service.SpeechResult
import com.example.rokidphone.testutil.MockWebServerRule
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import okhttp3.Headers.Companion.headersOf
import org.json.JSONObject
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * [OpenAiCompatibleService.transcribeAudioFile]: the already-encoded audio path,
 * which picks a file extension from the MIME type rather than wrapping PCM in WAV.
 */
@RunWith(RobolectricTestRunner::class)
class OpenAiAudioFileTranscriptionTest {

    @get:Rule
    val mockServer = MockWebServerRule()

    private val audio = ByteArray(4_000) { (it % 127).toByte() }

    private fun service(provider: AiProvider = AiProvider.OPENAI, modelId: String = "gpt-5.1") =
        OpenAiCompatibleService(
            apiKey = "sk-test",
            baseUrl = mockServer.baseUrl,
            modelId = modelId,
            providerType = provider
        )

    private fun enqueueText(text: String, code: Int = 200) = mockServer.server.enqueue(
        MockResponse(
            code = code,
            body = JSONObject().put("text", text).toString(),
            headers = headersOf("Content-Type", "application/json")
        )
    )

    @Test
    fun `an m4a upload is transcribed and named by its container`() = runTest {
        enqueueText("  hello from the recording  ")

        val result = service().transcribeAudioFile(audio, "audio/mp4", "en")

        assertThat(result).isEqualTo(SpeechResult.Success("hello from the recording"))

        val request = mockServer.server.takeRequest()
        assertThat(request.path).isEqualTo("/audio/transcriptions")
        assertThat(request.headers["Content-Type"]).startsWith("multipart/form-data; boundary=")
        assertThat(request.headers["Authorization"]).isEqualTo("Bearer sk-test")
        val body = request.body.readUtf8()
        assertThat(body).contains("filename=\"audio.m4a\"")
        assertThat(body).contains("whisper-1")
        assertThat(body).contains("en")
    }

    @Test
    fun `every supported container maps to its own extension`() = runTest {
        val expected = mapOf(
            "audio/mp4" to "m4a",
            "audio/m4a" to "m4a",
            "audio/mpeg" to "mp3",
            "audio/mp3" to "mp3",
            "audio/ogg" to "ogg",
            "audio/webm" to "webm",
            "audio/wav" to "wav",
            "audio/x-wav" to "wav",
            // The MIME type is matched case-insensitively.
            "AUDIO/WEBM" to "webm"
        )

        for ((mimeType, extension) in expected) {
            enqueueText("ok")

            assertThat(service().transcribeAudioFile(audio, mimeType, "en"))
                .isEqualTo(SpeechResult.Success("ok"))
            assertThat(mockServer.server.takeRequest().body.readUtf8())
                .contains("filename=\"audio.$extension\"")
        }
    }

    @Test
    fun `an unknown container is rejected before anything is uploaded`() = runTest {
        val result = service().transcribeAudioFile(audio, "audio/flac", "en")

        assertThat(result).isInstanceOf(SpeechResult.Error::class.java)
        assertThat((result as SpeechResult.Error).message)
            .isEqualTo("Unsupported audio format: audio/flac")
        assertThat(mockServer.server.requestCount).isEqualTo(0)
    }

    @Test
    fun `a provider without transcription support never reaches the network`() = runTest {
        val result = service(provider = AiProvider.LOCAL_GEMMA, modelId = "gemma-3n")
            .transcribeAudioFile(audio, "audio/wav", "en")

        assertThat(result).isInstanceOf(SpeechResult.Error::class.java)
        assertThat((result as SpeechResult.Error).message)
            .contains("does not support encoded audio transcription")
        assertThat(mockServer.server.requestCount).isEqualTo(0)
    }

    @Test
    fun `audio below a thousand bytes is rejected as too short`() = runTest {
        val result = service().transcribeAudioFile(ByteArray(999), "audio/wav", "en")

        assertThat(result).isInstanceOf(SpeechResult.Error::class.java)
        assertThat((result as SpeechResult.Error).message).contains("Audio too short")
        assertThat(mockServer.server.requestCount).isEqualTo(0)
    }

    @Test
    fun `a reply with no text is reported as no speech`() = runTest {
        enqueueText("   ")

        val result = service().transcribeAudioFile(audio, "audio/wav", "en")

        assertThat(result).isEqualTo(SpeechResult.Error("No speech detected"))
    }

    @Test
    fun `an api error is surfaced without leaking the key`() = runTest {
        mockServer.server.enqueue(
            MockResponse(
                code = 401,
                body = """{"error":{"message":"Incorrect API key provided: sk-test"}}""",
                headers = headersOf("Content-Type", "application/json")
            )
        )

        val result = service().transcribeAudioFile(audio, "audio/wav", "en")

        assertThat(result).isInstanceOf(SpeechResult.Error::class.java)
        assertThat((result as SpeechResult.Error).message).startsWith("Speech recognition failed:")
    }

    @Test
    fun `groq uploads against its own transcription model`() = runTest {
        enqueueText("groq heard it")

        val result = service(provider = AiProvider.GROQ, modelId = "llama-3.3-70b-versatile")
            .transcribeAudioFile(audio, "audio/ogg", "zh")

        assertThat(result).isEqualTo(SpeechResult.Success("groq heard it"))
        val body = mockServer.server.takeRequest().body.readUtf8()
        assertThat(body).contains("whisper-large-v3-turbo")
        assertThat(body).contains("filename=\"audio.ogg\"")
    }
}
