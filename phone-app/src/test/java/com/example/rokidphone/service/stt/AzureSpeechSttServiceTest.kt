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
 * Unit tests for AzureSpeechSttService.
 * Tests REST-based speech recognition with subscription key auth.
 */
@RunWith(RobolectricTestRunner::class)
class AzureSpeechSttServiceTest {

    @get:Rule
    val mockServer = MockWebServerRule()

    private fun createService(
        subscriptionKey: String = "test-sub-key",
        region: String = "eastus"
    ): AzureSpeechSttService = AzureSpeechSttService(
        subscriptionKey = subscriptionKey,
        region = region,
        baseEndpoint = mockServer.baseUrlNoSlash,
        baseTokenEndpoint = mockServer.baseUrlNoSlash
    )

    private fun jsonResponse(body: String, code: Int = 200) = MockResponse(
        code = code,
        body = body,
        headers = headersOf("Content-Type", "application/json")
    )

    private fun tokenResponse(code: Int = 200) = MockResponse(
        code = code,
        body = "fake-token-string",
        headers = headersOf("Content-Type", "text/plain")
    )

    // ==================== Transcribe Tests ====================

    @Test
    fun `transcribe - success extracts DisplayText from response`() = runTest {
        val service = createService()
        mockServer.server.enqueue(jsonResponse("""
            {
                "RecognitionStatus": "Success",
                "DisplayText": "Hello from Azure",
                "NBest": [{"Display": "Hello from Azure", "Confidence": 0.95}]
            }
        """.trimIndent()))

        val result = service.transcribe(TestFixtures.createTestPcmAudio(), "en-US")

        assertThat(result).isInstanceOf(SpeechResult.Success::class.java)
        assertThat((result as SpeechResult.Success).text).contains("Hello from Azure")
    }

    @Test
    fun `transcribe - request includes subscription key header`() = runTest {
        val service = createService(subscriptionKey = "azure-key-123")
        mockServer.server.enqueue(jsonResponse("""
            {"RecognitionStatus":"Success","DisplayText":"test"}
        """.trimIndent()))

        service.transcribe(TestFixtures.createTestPcmAudio(), "en-US")

        val request = mockServer.server.takeRequest()
        assertThat(request.headers["Ocp-Apim-Subscription-Key"]).isEqualTo("azure-key-123")
    }

    @Test
    fun `transcribe - request includes language in query parameter`() = runTest {
        val service = createService()
        mockServer.server.enqueue(jsonResponse("""
            {"RecognitionStatus":"Success","DisplayText":"test"}
        """.trimIndent()))

        service.transcribe(TestFixtures.createTestPcmAudio(), "zh-TW")

        val request = mockServer.server.takeRequest()
        assertThat(request.path).contains("language=")
    }

    @Test
    fun `transcribe - sends WAV audio body with correct content type`() = runTest {
        val service = createService()
        mockServer.server.enqueue(jsonResponse("""
            {"RecognitionStatus":"Success","DisplayText":"test"}
        """.trimIndent()))

        service.transcribe(TestFixtures.createTestPcmAudio(), "en-US")

        val request = mockServer.server.takeRequest()
        val contentType = request.headers["Content-Type"] ?: ""
        assertThat(contentType).contains("audio/wav")
        // WAV header starts with RIFF
        val body = request.body.peek().readByteArray()
        assertThat(body.size).isGreaterThan(44) // WAV header is 44 bytes
        val riffHeader = String(body, 0, 4, Charsets.US_ASCII)
        assertThat(riffHeader).isEqualTo("RIFF")
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
    fun `transcribe - server error after retries returns error`() = runBlocking {
        val service = createService()
        repeat(3) {
            mockServer.server.enqueue(jsonResponse("""{"error":"Internal Server Error"}""", 500))
        }

        val result = service.transcribe(TestFixtures.createTestPcmAudio(), "en-US")

        assertThat(result).isInstanceOf(SpeechResult.Error::class.java)
    }

    @Test
    fun `transcribe - 401 unauthorized returns error`() = runBlocking {
        val service = createService()
        repeat(3) {
            mockServer.server.enqueue(jsonResponse("""{"error":"Unauthorized"}""", 401))
        }

        val result = service.transcribe(TestFixtures.createTestPcmAudio(), "en-US")

        assertThat(result).isInstanceOf(SpeechResult.Error::class.java)
    }

    // ==================== Validate Credentials ====================

    @Test
    fun `validateCredentials - valid key returns Valid`() = runTest {
        val service = createService()
        mockServer.server.enqueue(tokenResponse(200))

        val result = service.validateCredentials()

        assertThat(result).isInstanceOf(SttValidationResult.Valid::class.java)
    }

    @Test
    fun `validateCredentials - 401 returns Invalid`() = runTest {
        val service = createService()
        mockServer.server.enqueue(tokenResponse(401))

        val result = service.validateCredentials()

        assertThat(result).isInstanceOf(SttValidationResult.Invalid::class.java)
    }

    @Test
    fun `validateCredentials - 404 returns WRONG_ENDPOINT_OR_REGION`() = runTest {
        val service = createService()
        mockServer.server.enqueue(tokenResponse(404))

        val result = service.validateCredentials()

        assertThat(result).isInstanceOf(SttValidationResult.Invalid::class.java)
        val error = (result as SttValidationResult.Invalid).error
        assertThat(error).isEqualTo(SttValidationError.WRONG_ENDPOINT_OR_REGION)
    }

    @Test
    fun `validateCredentials - sends subscription key header`() = runTest {
        val service = createService(subscriptionKey = "validate-key-xyz")
        mockServer.server.enqueue(tokenResponse(200))

        service.validateCredentials()

        val request = mockServer.server.takeRequest()
        assertThat(request.headers["Ocp-Apim-Subscription-Key"]).isEqualTo("validate-key-xyz")
    }

    // ==================== Provider Metadata ====================

    @Test
    fun `provider returns AZURE_SPEECH`() {
        val service = createService()
        assertThat(service.provider).isEqualTo(SttProvider.AZURE_SPEECH)
    }
}
