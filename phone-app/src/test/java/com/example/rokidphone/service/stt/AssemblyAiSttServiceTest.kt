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
 * Unit tests for AssemblyAiSttService.
 * Tests the async upload → create transcript → poll workflow using MockWebServer.
 */
@RunWith(RobolectricTestRunner::class)
class AssemblyAiSttServiceTest {

    @get:Rule
    val mockServer = MockWebServerRule()

    private fun createService(
        apiKey: String = "test-api-key"
    ): AssemblyAiSttService = AssemblyAiSttService(
        apiKey = apiKey,
        baseUrl = mockServer.baseUrlNoSlash,
        pollIntervalMs = 10L,     // Fast polling for tests
        maxPollAttempts = 5
    )

    private fun jsonResponse(body: String, code: Int = 200) = MockResponse(
        code = code,
        body = body,
        headers = headersOf("Content-Type", "application/json")
    )

    // ==================== Transcribe Tests ====================

    @Test
    fun `transcribe - full workflow success`() = runTest {
        val service = createService()
        // Step 1: Upload audio → returns upload URL
        mockServer.server.enqueue(
            jsonResponse(TestFixtures.MockResponses.assemblyAiUploadSuccess("https://cdn.example.com/audio.wav"))
        )
        // Step 2: Create transcript → returns transcript ID
        mockServer.server.enqueue(
            jsonResponse(TestFixtures.MockResponses.assemblyAiTranscriptCreated("tx-123"))
        )
        // Step 3: Poll — first returns processing
        mockServer.server.enqueue(
            jsonResponse(TestFixtures.MockResponses.assemblyAiTranscriptProcessing("tx-123"))
        )
        // Step 4: Poll — second returns completed
        mockServer.server.enqueue(
            jsonResponse(TestFixtures.MockResponses.assemblyAiTranscriptCompleted("tx-123", "Hello world"))
        )

        val result = service.transcribe(TestFixtures.createTestPcmAudio(), "en-US")

        assertThat(result).isInstanceOf(SpeechResult.Success::class.java)
        assertThat((result as SpeechResult.Success).text).isEqualTo("Hello world")
    }

    @Test
    fun `transcribe - upload request uses correct auth header`() = runTest {
        val service = createService(apiKey = "aai-test-key-456")
        mockServer.server.enqueue(
            jsonResponse(TestFixtures.MockResponses.assemblyAiUploadSuccess("https://cdn.example.com/audio.wav"))
        )
        mockServer.server.enqueue(
            jsonResponse(TestFixtures.MockResponses.assemblyAiTranscriptCreated("tx-1"))
        )
        mockServer.server.enqueue(
            jsonResponse(TestFixtures.MockResponses.assemblyAiTranscriptCompleted("tx-1", "test"))
        )

        service.transcribe(TestFixtures.createTestPcmAudio(), "en-US")

        // Check upload request auth header
        val uploadRequest = mockServer.server.takeRequest()
        assertThat(uploadRequest.headers["Authorization"]).isEqualTo("aai-test-key-456")
    }

    @Test
    fun `transcribe - upload sends WAV audio data`() = runTest {
        val service = createService()
        mockServer.server.enqueue(
            jsonResponse(TestFixtures.MockResponses.assemblyAiUploadSuccess("https://cdn.example.com/audio.wav"))
        )
        mockServer.server.enqueue(
            jsonResponse(TestFixtures.MockResponses.assemblyAiTranscriptCreated("tx-1"))
        )
        mockServer.server.enqueue(
            jsonResponse(TestFixtures.MockResponses.assemblyAiTranscriptCompleted("tx-1", "test"))
        )

        service.transcribe(TestFixtures.createTestPcmAudio(), "en-US")

        val uploadRequest = mockServer.server.takeRequest()
        assertThat(uploadRequest.path).isEqualTo("/upload")
        assertThat(uploadRequest.headers["Content-Type"]).contains("audio/wav")
        // Verify WAV header
        val bodyBytes = uploadRequest.body.readByteArray()
        assertThat(bodyBytes.size).isGreaterThan(44)
        assertThat(String(bodyBytes, 0, 4)).isEqualTo("RIFF")
    }

    @Test
    fun `transcribe - create transcript request format`() = runTest {
        val service = createService()
        mockServer.server.enqueue(
            jsonResponse(TestFixtures.MockResponses.assemblyAiUploadSuccess("https://cdn.example.com/uploaded.wav"))
        )
        mockServer.server.enqueue(
            jsonResponse(TestFixtures.MockResponses.assemblyAiTranscriptCreated("tx-1"))
        )
        mockServer.server.enqueue(
            jsonResponse(TestFixtures.MockResponses.assemblyAiTranscriptCompleted("tx-1", "test"))
        )

        service.transcribe(TestFixtures.createTestPcmAudio(), "en-US")

        mockServer.server.takeRequest() // skip upload
        val createRequest = mockServer.server.takeRequest()
        assertThat(createRequest.path).isEqualTo("/transcript")
        assertThat(createRequest.method).isEqualTo("POST")
        val body = JSONObject(createRequest.body.readUtf8())
        assertThat(body.getString("audio_url")).isEqualTo("https://cdn.example.com/uploaded.wav")
        assertThat(body.getBoolean("punctuate")).isTrue()
        assertThat(body.getBoolean("format_text")).isTrue()
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
    fun `transcribe - upload failure returns error`() = runBlocking {
        val service = createService()
        repeat(3) {
            mockServer.server.enqueue(
                jsonResponse("""{"error":"Upload failed"}""", 500)
            )
        }

        val result = service.transcribe(TestFixtures.createTestPcmAudio(), "en-US")

        assertThat(result).isInstanceOf(SpeechResult.Error::class.java)
    }

    @Test
    fun `transcribe - transcript creation returns error status`() = runBlocking {
        val service = createService()
        mockServer.server.enqueue(
            jsonResponse(TestFixtures.MockResponses.assemblyAiUploadSuccess("https://cdn.example.com/audio.wav"))
        )
        repeat(3) {
            mockServer.server.enqueue(
                jsonResponse("""{"error":"Invalid audio"}""", 400)
            )
        }

        val result = service.transcribe(TestFixtures.createTestPcmAudio(), "en-US")

        assertThat(result).isInstanceOf(SpeechResult.Error::class.java)
    }

    @Test
    fun `transcribe - polling timeout returns error`() = runTest {
        val service = AssemblyAiSttService(
            apiKey = "test-key",
            baseUrl = mockServer.baseUrlNoSlash,
            pollIntervalMs = 10L,
            maxPollAttempts = 2   // Only 2 poll attempts
        )
        mockServer.server.enqueue(
            jsonResponse(TestFixtures.MockResponses.assemblyAiUploadSuccess("https://cdn.example.com/audio.wav"))
        )
        mockServer.server.enqueue(
            jsonResponse(TestFixtures.MockResponses.assemblyAiTranscriptCreated("tx-1"))
        )
        // Both polls return processing (never completed)
        mockServer.server.enqueue(
            jsonResponse(TestFixtures.MockResponses.assemblyAiTranscriptProcessing("tx-1"))
        )
        mockServer.server.enqueue(
            jsonResponse(TestFixtures.MockResponses.assemblyAiTranscriptProcessing("tx-1"))
        )

        val result = service.transcribe(TestFixtures.createTestPcmAudio(), "en-US")

        assertThat(result).isInstanceOf(SpeechResult.Error::class.java)
    }

    @Test
    fun `transcribe - language code mapping zh-TW to zh`() = runTest {
        val service = createService()
        mockServer.server.enqueue(
            jsonResponse(TestFixtures.MockResponses.assemblyAiUploadSuccess("https://cdn.example.com/audio.wav"))
        )
        mockServer.server.enqueue(
            jsonResponse(TestFixtures.MockResponses.assemblyAiTranscriptCreated("tx-1"))
        )
        mockServer.server.enqueue(
            jsonResponse(TestFixtures.MockResponses.assemblyAiTranscriptCompleted("tx-1", "test"))
        )

        service.transcribe(TestFixtures.createTestPcmAudio(), "zh-TW")

        mockServer.server.takeRequest() // skip upload
        val createRequest = mockServer.server.takeRequest()
        val body = JSONObject(createRequest.body.readUtf8())
        assertThat(body.getString("language_code")).isEqualTo("zh")
    }

    // ==================== ValidateCredentials Tests ====================

    @Test
    fun `validateCredentials - valid key returns Valid`() = runTest {
        val service = createService()
        mockServer.server.enqueue(
            jsonResponse("""{"transcripts":[]}""")
        )

        val result = service.validateCredentials()

        assertThat(result).isInstanceOf(SttValidationResult.Valid::class.java)
    }

    @Test
    fun `validateCredentials - 401 returns Invalid INVALID_CREDENTIALS`() = runTest {
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
    fun `validateCredentials - uses GET on transcript endpoint`() = runTest {
        val service = createService()
        mockServer.server.enqueue(
            jsonResponse("""{"transcripts":[]}""")
        )

        service.validateCredentials()

        val request = mockServer.server.takeRequest()
        assertThat(request.method).isEqualTo("GET")
        assertThat(request.path).contains("/transcript")
    }

    // ==================== Provider Metadata ====================

    @Test
    fun `provider returns ASSEMBLYAI`() {
        val service = createService()
        assertThat(service.provider).isEqualTo(SttProvider.ASSEMBLYAI)
    }

    @Test
    fun `default base URL matches expected AssemblyAI endpoint`() {
        assertThat(AssemblyAiSttService.DEFAULT_BASE_URL)
            .isEqualTo("https://api.assemblyai.com/v2")
    }
}
