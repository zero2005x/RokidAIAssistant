package com.example.rokidphone.service.stt

import com.example.rokidphone.service.SpeechResult
import com.example.rokidphone.testutil.MockWebServerRule
import com.example.rokidphone.testutil.TestFixtures
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import okhttp3.Headers.Companion.headersOf
import org.json.JSONObject
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Unit tests for GoogleCloudSttService.
 * Tests REST-based speech recognition using MockWebServer.
 */
@RunWith(RobolectricTestRunner::class)
class GoogleCloudSttServiceTest {

    @get:Rule
    val mockServer = MockWebServerRule()

    private fun createService(
        projectId: String = "test-project",
        apiKey: String = "test-api-key"
    ): GoogleCloudSttService = GoogleCloudSttService(
        projectId = projectId,
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
            jsonResponse(TestFixtures.MockResponses.googleCloudSttSuccess("Hello world"))
        )

        val result = service.transcribe(TestFixtures.createTestPcmAudio(), "en-US")

        assertThat(result).isInstanceOf(SpeechResult.Success::class.java)
        assertThat((result as SpeechResult.Success).text).isEqualTo("Hello world")
    }

    @Test
    fun `transcribe - request uses API key in query parameter`() = runTest {
        val service = createService(apiKey = "gcp-test-key-789")
        mockServer.server.enqueue(
            jsonResponse(TestFixtures.MockResponses.googleCloudSttSuccess("test"))
        )

        service.transcribe(TestFixtures.createTestPcmAudio(), "en-US")

        val request = mockServer.server.takeRequest()
        assertThat(request.path).contains("key=gcp-test-key-789")
    }

    @Test
    fun `transcribe - request body format is correct`() = runTest {
        val service = createService()
        mockServer.server.enqueue(
            jsonResponse(TestFixtures.MockResponses.googleCloudSttSuccess("test"))
        )

        service.transcribe(TestFixtures.createTestPcmAudio(), "zh-TW")

        val request = mockServer.server.takeRequest()
        val body = JSONObject(request.body.readUtf8())
        // Verify config
        val config = body.getJSONObject("config")
        assertThat(config.getString("encoding")).isEqualTo("LINEAR16")
        assertThat(config.getInt("sampleRateHertz")).isEqualTo(16000)
        assertThat(config.getString("languageCode")).isEqualTo("zh-TW")
        // Verify audio
        val audio = body.getJSONObject("audio")
        assertThat(audio.has("content")).isTrue()
        assertThat(audio.getString("content")).isNotEmpty()
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
                jsonResponse("""{"error":{"code":500,"message":"Internal error"}}""", 500)
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
                jsonResponse("""{"error":{"code":401,"message":"Invalid API key"}}""", 401)
            )
        }

        val result = service.transcribe(TestFixtures.createTestPcmAudio(), "en-US")

        assertThat(result).isInstanceOf(SpeechResult.Error::class.java)
    }

    // ==================== Provider Metadata ====================

    @Test
    fun `provider returns GOOGLE_CLOUD_STT`() {
        val service = createService()
        assertThat(service.provider).isEqualTo(SttProvider.GOOGLE_CLOUD_STT)
    }

    @Test
    fun `default base URL matches expected Google Cloud endpoint`() {
        assertThat(GoogleCloudSttService.DEFAULT_BASE_URL)
            .isEqualTo("https://speech.googleapis.com/v1")
    }
}
