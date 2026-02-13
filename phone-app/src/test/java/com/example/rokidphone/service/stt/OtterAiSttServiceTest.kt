package com.example.rokidphone.service.stt

import com.example.rokidphone.testutil.MockWebServerRule
import com.example.rokidphone.testutil.TestFixtures
import com.example.rokidphone.service.SpeechResult
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
 * Unit tests for OtterAiSttService.
 * Tests async upload-and-poll workflow via MockWebServer.
 */
@RunWith(RobolectricTestRunner::class)
class OtterAiSttServiceTest {

    @get:Rule
    val mockServer = MockWebServerRule()

    private fun createService(
        apiKey: String = "test-otter-key"
    ): OtterAiSttService = OtterAiSttService(
        apiKey = apiKey,
        baseUrl = mockServer.baseUrlNoSlash,
        pollingIntervalMs = 10L, // Very fast polling for tests
        maxPollingAttempts = 3
    )

    private fun jsonResponse(body: String, code: Int = 200) = MockResponse(
        code = code,
        body = body,
        headers = headersOf("Content-Type", "application/json")
    )

    // ==================== Transcribe Tests ====================

    @Test
    fun `transcribe - full workflow upload then poll until completed`() = runTest {
        val service = createService()
        // 1. Upload response
        mockServer.server.enqueue(jsonResponse("""{"speech_id":"sp-123"}"""))
        // 2. First poll - still processing
        mockServer.server.enqueue(jsonResponse("""{"status":"processing"}"""))
        // 3. Second poll - completed
        mockServer.server.enqueue(jsonResponse("""{"status":"completed","transcript":"Hello from Otter"}"""))

        val result = service.transcribe(TestFixtures.createTestPcmAudio(), "en-US")

        assertThat(result).isInstanceOf(SpeechResult.Success::class.java)
        assertThat((result as SpeechResult.Success).text).isEqualTo("Hello from Otter")
    }

    @Test
    fun `transcribe - upload uses Bearer auth header`() = runTest {
        val service = createService(apiKey = "otter-bearer-key")
        mockServer.server.enqueue(jsonResponse("""{"speech_id":"sp-1"}"""))
        mockServer.server.enqueue(jsonResponse("""{"status":"completed","transcript":"test"}"""))

        service.transcribe(TestFixtures.createTestPcmAudio(), "en-US")

        val uploadRequest = mockServer.server.takeRequest()
        assertThat(uploadRequest.headers["Authorization"]).isEqualTo("Bearer otter-bearer-key")
    }

    @Test
    fun `transcribe - upload is multipart POST to speeches endpoint`() = runTest {
        val service = createService()
        mockServer.server.enqueue(jsonResponse("""{"speech_id":"sp-1"}"""))
        mockServer.server.enqueue(jsonResponse("""{"status":"completed","transcript":"test"}"""))

        service.transcribe(TestFixtures.createTestPcmAudio(), "en-US")

        val uploadRequest = mockServer.server.takeRequest()
        assertThat(uploadRequest.method).isEqualTo("POST")
        assertThat(uploadRequest.path).contains("/speeches")
        val contentType = uploadRequest.headers["Content-Type"] ?: ""
        assertThat(contentType).contains("multipart/form-data")
    }

    @Test
    fun `transcribe - poll uses GET with Bearer auth`() = runTest {
        val service = createService(apiKey = "poll-key")
        mockServer.server.enqueue(jsonResponse("""{"speech_id":"sp-456"}"""))
        mockServer.server.enqueue(jsonResponse("""{"status":"completed","transcript":"done"}"""))

        service.transcribe(TestFixtures.createTestPcmAudio(), "en-US")

        mockServer.server.takeRequest() // skip upload
        val pollRequest = mockServer.server.takeRequest()
        assertThat(pollRequest.method).isEqualTo("GET")
        assertThat(pollRequest.path).contains("/speeches/sp-456")
        assertThat(pollRequest.headers["Authorization"]).isEqualTo("Bearer poll-key")
    }

    @Test
    fun `transcribe - polling timeout returns error`() = runTest {
        val service = createService()
        mockServer.server.enqueue(jsonResponse("""{"speech_id":"sp-timeout"}"""))
        // All polls return processing - will exceed maxPollingAttempts (3)
        repeat(5) {
            mockServer.server.enqueue(jsonResponse("""{"status":"processing"}"""))
        }

        val result = service.transcribe(TestFixtures.createTestPcmAudio(), "en-US")

        assertThat(result).isInstanceOf(SpeechResult.Error::class.java)
    }

    @Test
    fun `transcribe - poll returns failed status`() = runTest {
        val service = createService()
        mockServer.server.enqueue(jsonResponse("""{"speech_id":"sp-fail"}"""))
        mockServer.server.enqueue(jsonResponse("""{"status":"failed","error":"Processing failed"}"""))

        val result = service.transcribe(TestFixtures.createTestPcmAudio(), "en-US")

        assertThat(result).isInstanceOf(SpeechResult.Error::class.java)
    }

    @Test
    fun `transcribe - upload failure returns error`() = runTest {
        val service = createService()
        mockServer.server.enqueue(jsonResponse("""{"error":"Upload failed"}""", 500))

        val result = service.transcribe(TestFixtures.createTestPcmAudio(), "en-US")

        assertThat(result).isInstanceOf(SpeechResult.Error::class.java)
    }

    @Test
    fun `transcribe - audio too short returns error`() = runBlocking {
        val service = createService()

        val result = service.transcribe(TestFixtures.createTooShortAudio(), "en-US")

        assertThat(result).isInstanceOf(SpeechResult.Error::class.java)
    }

    @Test
    fun `transcribe - empty audio returns error`() = runBlocking {
        val service = createService()

        val result = service.transcribe(TestFixtures.createEmptyAudio(), "en-US")

        assertThat(result).isInstanceOf(SpeechResult.Error::class.java)
    }

    // ==================== Validate Credentials ====================

    @Test
    fun `validateCredentials - non-blank key returns Valid`() = runTest {
        val service = createService(apiKey = "any-key")

        val result = service.validateCredentials()

        assertThat(result).isInstanceOf(SttValidationResult.Valid::class.java)
    }

    @Test
    fun `validateCredentials - blank key returns Invalid`() = runTest {
        val service = OtterAiSttService(
            apiKey = "   ",
            baseUrl = mockServer.baseUrlNoSlash
        )

        val result = service.validateCredentials()

        assertThat(result).isInstanceOf(SttValidationResult.Invalid::class.java)
    }

    // ==================== Provider Metadata ====================

    @Test
    fun `provider returns OTTER_AI`() {
        val service = createService()
        assertThat(service.provider).isEqualTo(SttProvider.OTTER_AI)
    }

    @Test
    fun `supportsStreaming returns false`() {
        val service = createService()
        assertThat(service.supportsStreaming()).isFalse()
    }

    @Test
    fun `default constants are correct`() {
        assertThat(OtterAiSttService.DEFAULT_BASE_URL)
            .isEqualTo("https://otter.ai/forward/api/v1")
        assertThat(OtterAiSttService.DEFAULT_POLLING_INTERVAL_MS).isEqualTo(2000L)
        assertThat(OtterAiSttService.DEFAULT_MAX_POLLING_ATTEMPTS).isEqualTo(60)
    }
}
