package com.example.rokidphone.service.stt

import com.example.rokidphone.service.SpeechResult
import com.example.rokidphone.testutil.MockWebServerRule
import com.example.rokidphone.testutil.TestFixtures
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import okhttp3.Headers.Companion.headersOf
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Unit tests for DeepgramSttService.
 * Tests REST-based transcription using MockWebServer.
 */
@RunWith(RobolectricTestRunner::class)
class DeepgramSttServiceTest {

    @get:Rule
    val mockServer = MockWebServerRule()

    private fun createService(
        apiKey: String = "test-api-key"
    ): DeepgramSttService = DeepgramSttService(
        apiKey = apiKey,
        baseUrl = mockServer.baseUrlNoSlash
    )

    private fun jsonResponse(body: String, code: Int = 200) = MockResponse(
        code = code,
        body = body,
        headers = headersOf("Content-Type", "application/json")
    )

    // ==================== Transcribe Tests ====================

    @Test
    fun `transcribe - success returns recognized text`() = runTest {
        val service = createService()
        mockServer.server.enqueue(
            jsonResponse(TestFixtures.MockResponses.deepgramTranscribeSuccess("Hello world"))
        )

        val result = service.transcribe(TestFixtures.createTestPcmAudio(), "en-US")

        assertThat(result).isInstanceOf(SpeechResult.Success::class.java)
        assertThat((result as SpeechResult.Success).text).isEqualTo("Hello world")
    }

    @Test
    fun `transcribe - request uses correct auth header`() = runTest {
        val service = createService(apiKey = "dg-test-key-123")
        mockServer.server.enqueue(
            jsonResponse(TestFixtures.MockResponses.deepgramTranscribeSuccess("test"))
        )

        service.transcribe(TestFixtures.createTestPcmAudio(), "en-US")

        val request = mockServer.server.takeRequest()
        assertThat(request.headers["Authorization"]).isEqualTo("Token dg-test-key-123")
    }

    @Test
    fun `transcribe - request URL contains correct query parameters`() = runTest {
        val service = createService()
        mockServer.server.enqueue(
            jsonResponse(TestFixtures.MockResponses.deepgramTranscribeSuccess("test"))
        )

        service.transcribe(TestFixtures.createTestPcmAudio(), "zh-TW")

        val request = mockServer.server.takeRequest()
        assertThat(request.path).contains("model=nova-2")
        assertThat(request.path).contains("language=zh-TW")
        assertThat(request.path).contains("punctuate=true")
        assertThat(request.path).contains("smart_format=true")
    }

    @Test
    fun `transcribe - sends WAV audio body`() = runTest {
        val service = createService()
        mockServer.server.enqueue(
            jsonResponse(TestFixtures.MockResponses.deepgramTranscribeSuccess("test"))
        )

        service.transcribe(TestFixtures.createTestPcmAudio(), "en-US")

        val request = mockServer.server.takeRequest()
        assertThat(request.headers["Content-Type"]).contains("audio/wav")
        // WAV header starts with "RIFF"
        val bodyBytes = request.body.readByteArray()
        assertThat(bodyBytes.size).isGreaterThan(44) // WAV header is 44 bytes
        assertThat(String(bodyBytes, 0, 4)).isEqualTo("RIFF")
    }

    @Test
    fun `transcribe - audio too short returns error`() = runTest {
        val service = createService()

        val result = service.transcribe(TestFixtures.createTooShortAudio(), "en-US")

        assertThat(result).isInstanceOf(SpeechResult.Error::class.java)
    }

    @Test
    fun `transcribe - empty audio returns error`() = runTest {
        val service = createService()

        val result = service.transcribe(TestFixtures.createEmptyAudio(), "en-US")

        assertThat(result).isInstanceOf(SpeechResult.Error::class.java)
    }

    @Test
    fun `transcribe - server error returns error result`() = runBlocking {
        val service = createService()
        repeat(3) {
            mockServer.server.enqueue(
                jsonResponse("""{"error":"Internal server error"}""", 500)
            )
        }

        val result = service.transcribe(TestFixtures.createTestPcmAudio(), "en-US")

        assertThat(result).isInstanceOf(SpeechResult.Error::class.java)
    }

    @Test
    fun `transcribe - 401 unauthorized returns error`() = runBlocking {
        val service = createService()
        repeat(3) {
            mockServer.server.enqueue(
                jsonResponse("""{"error":"Invalid API key"}""", 401)
            )
        }

        val result = service.transcribe(TestFixtures.createTestPcmAudio(), "en-US")

        assertThat(result).isInstanceOf(SpeechResult.Error::class.java)
    }

    @Test
    fun `transcribe - malformed JSON response returns error`() = runBlocking {
        val service = createService()
        repeat(3) {
            mockServer.server.enqueue(
                jsonResponse(TestFixtures.MockResponses.malformedJson())
            )
        }

        val result = service.transcribe(TestFixtures.createTestPcmAudio(), "en-US")

        assertThat(result).isInstanceOf(SpeechResult.Error::class.java)
    }

    @Test
    fun `transcribe - empty transcript returns error`() = runBlocking {
        val service = createService()
        repeat(3) {
            mockServer.server.enqueue(
                jsonResponse(TestFixtures.MockResponses.deepgramTranscribeSuccess(""))
            )
        }

        val result = service.transcribe(TestFixtures.createTestPcmAudio(), "en-US")

        assertThat(result).isInstanceOf(SpeechResult.Error::class.java)
    }

    // ==================== ValidateCredentials Tests ====================

    @Test
    fun `validateCredentials - valid key returns Valid`() = runTest {
        val service = createService()
        mockServer.server.enqueue(
            jsonResponse("""{"projects":[{"project_id":"test-id"}]}""")
        )

        val result = service.validateCredentials()

        assertThat(result).isInstanceOf(SttValidationResult.Valid::class.java)
    }

    @Test
    fun `validateCredentials - 401 returns Invalid with INVALID_CREDENTIALS`() = runTest {
        val service = createService()
        mockServer.server.enqueue(
            jsonResponse("""{"error":"Unauthorized"}""", 401)
        )

        val result = service.validateCredentials()

        assertThat(result).isInstanceOf(SttValidationResult.Invalid::class.java)
        assertThat((result as SttValidationResult.Invalid).error)
            .isEqualTo(SttValidationError.INVALID_CREDENTIALS)
    }

    @Test
    fun `validateCredentials - 403 returns Invalid with INVALID_CREDENTIALS`() = runTest {
        val service = createService()
        mockServer.server.enqueue(
            jsonResponse("""{"error":"Forbidden"}""", 403)
        )

        val result = service.validateCredentials()

        assertThat(result).isInstanceOf(SttValidationResult.Invalid::class.java)
        assertThat((result as SttValidationResult.Invalid).error)
            .isEqualTo(SttValidationError.INVALID_CREDENTIALS)
    }

    @Test
    fun `validateCredentials - request uses GET method`() = runTest {
        val service = createService()
        mockServer.server.enqueue(
            jsonResponse("""{"projects":[]}""")
        )

        service.validateCredentials()

        val request = mockServer.server.takeRequest()
        assertThat(request.method).isEqualTo("GET")
        assertThat(request.path).contains("/projects")
    }

    // ==================== Provider Metadata ====================

    @Test
    fun `provider returns DEEPGRAM`() {
        val service = createService()
        assertThat(service.provider).isEqualTo(SttProvider.DEEPGRAM)
    }

    @Test
    fun `default base URL matches expected Deepgram endpoint`() {
        assertThat(DeepgramSttService.DEFAULT_BASE_URL)
            .isEqualTo("https://api.deepgram.com/v1")
    }
}
