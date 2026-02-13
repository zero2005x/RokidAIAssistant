package com.example.rokidphone.service.ai

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
 * Unit tests for GeminiService.
 * Uses MockWebServer to intercept HTTP requests and verify request/response handling.
 */
@RunWith(RobolectricTestRunner::class)
class GeminiServiceTest {

    @get:Rule
    val mockServer = MockWebServerRule()

    private fun createService(
        apiKey: String = "test-api-key",
        modelId: String = "gemini-2.5-flash"
    ): GeminiService = GeminiService(
        apiKey = apiKey,
        modelId = modelId,
        baseUrl = mockServer.baseUrlNoSlash
    )

    /** Helper to create a JSON MockResponse. */
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
            jsonResponse(TestFixtures.MockResponses.geminiTranscribeSuccess("你好世界"))
        )

        val result = service.transcribe(TestFixtures.createTestPcmAudio(), "zh-TW")

        assertThat(result).isInstanceOf(SpeechResult.Success::class.java)
        assertThat((result as SpeechResult.Success).text).isEqualTo("你好世界")
    }

    @Test
    fun `transcribe - request format contains inline_data with audio`() = runTest {
        val service = createService()
        mockServer.server.enqueue(
            jsonResponse(TestFixtures.MockResponses.geminiTranscribeSuccess())
        )

        service.transcribe(TestFixtures.createTestPcmAudio(), "en-US")

        val request = mockServer.server.takeRequest()
        assertThat(request.path).contains("key=test-api-key")
        val body = JSONObject(request.body.readUtf8())
        val parts = body.getJSONArray("contents")
            .getJSONObject(0)
            .getJSONArray("parts")
        // First part should be inline_data with audio
        assertThat(parts.getJSONObject(0).has("inline_data")).isTrue()
        assertThat(
            parts.getJSONObject(0).getJSONObject("inline_data").getString("mime_type")
        ).isEqualTo("audio/wav")
        // Second part should be the transcription prompt text
        assertThat(parts.getJSONObject(1).has("text")).isTrue()
    }

    @Test
    fun `transcribe - audio too short returns error`() = runTest {
        val service = createService()

        val result = service.transcribe(TestFixtures.createTooShortAudio(), "zh-TW")

        assertThat(result).isInstanceOf(SpeechResult.Error::class.java)
        assertThat((result as SpeechResult.Error).message).contains("too short")
    }

    @Test
    fun `transcribe - blank API key returns error`() = runTest {
        val service = createService(apiKey = "")

        val result = service.transcribe(TestFixtures.createTestPcmAudio(), "zh-TW")

        assertThat(result).isInstanceOf(SpeechResult.Error::class.java)
        assertThat((result as SpeechResult.Error).message).contains("API key")
    }

    @Test
    fun `transcribe - unable to recognize response returns error`() = runBlocking {
        val service = createService()
        // Enqueue for all retry attempts (MAX_RETRIES = 3)
        repeat(3) {
            mockServer.server.enqueue(
                jsonResponse(TestFixtures.MockResponses.geminiTranscribeSuccess("Unable to recognize"))
            )
        }

        val result = service.transcribe(TestFixtures.createTestPcmAudio(), "zh-TW")

        assertThat(result).isInstanceOf(SpeechResult.Error::class.java)
    }

    @Test
    fun `transcribe - server error returns error result`() = runBlocking {
        val service = createService()
        // Enqueue retries (MAX_RETRIES = 3)
        repeat(3) {
            mockServer.server.enqueue(
                jsonResponse(TestFixtures.MockResponses.geminiError(500, "Internal Server Error"), 500)
            )
        }

        val result = service.transcribe(TestFixtures.createTestPcmAudio(), "zh-TW")

        assertThat(result).isInstanceOf(SpeechResult.Error::class.java)
    }

    @Test
    fun `transcribe - empty response text returns error`() = runBlocking {
        val service = createService()
        // Enqueue for all retry attempts (MAX_RETRIES = 3)
        repeat(3) {
            mockServer.server.enqueue(
                jsonResponse(TestFixtures.MockResponses.geminiTranscribeSuccess(""))
            )
        }

        val result = service.transcribe(TestFixtures.createTestPcmAudio(), "zh-TW")

        assertThat(result).isInstanceOf(SpeechResult.Error::class.java)
    }

    @Test
    fun `transcribe - gemini error response pattern is filtered`() = runBlocking {
        val service = createService()
        // Enqueue for all retry attempts (MAX_RETRIES = 3)
        repeat(3) {
            mockServer.server.enqueue(
                jsonResponse(TestFixtures.MockResponses.geminiTranscribeSuccess("I'm sorry, I cannot transcribe"))
            )
        }

        val result = service.transcribe(TestFixtures.createTestPcmAudio(), "zh-TW")

        assertThat(result).isInstanceOf(SpeechResult.Error::class.java)
    }

    @Test
    fun `transcribe - timestamp-only response is filtered as invalid`() = runBlocking {
        val service = createService()
        // Enqueue for all retry attempts (MAX_RETRIES = 3)
        repeat(3) {
            mockServer.server.enqueue(
                jsonResponse(TestFixtures.MockResponses.geminiTranscribeSuccess("00:00 00:01 00:02"))
            )
        }

        val result = service.transcribe(TestFixtures.createTestPcmAudio(), "zh-TW")

        assertThat(result).isInstanceOf(SpeechResult.Error::class.java)
    }

    // ==================== TranscribeAudioFile Tests ====================

    @Test
    fun `transcribeAudioFile - success with m4a mime type`() = runTest {
        val service = createService()
        mockServer.server.enqueue(
            jsonResponse(TestFixtures.MockResponses.geminiTranscribeSuccess("Hello world"))
        )

        val result = service.transcribeAudioFile(
            TestFixtures.createTestPcmAudio(),
            "audio/m4a",
            "en-US"
        )

        assertThat(result).isInstanceOf(SpeechResult.Success::class.java)
        assertThat((result as SpeechResult.Success).text).isEqualTo("Hello world")

        val request = mockServer.server.takeRequest()
        val body = JSONObject(request.body.readUtf8())
        val mimeType = body.getJSONArray("contents")
            .getJSONObject(0)
            .getJSONArray("parts")
            .getJSONObject(0)
            .getJSONObject("inline_data")
            .getString("mime_type")
        assertThat(mimeType).isEqualTo("audio/m4a")
    }

    // ==================== Chat Tests ====================

    @Test
    fun `chat - success returns response text`() = runTest {
        val service = createService()
        mockServer.server.enqueue(
            jsonResponse(TestFixtures.MockResponses.geminiChatSuccess("Hello from Gemini!"))
        )

        val result = service.chat("Hi")

        assertThat(result).isEqualTo("Hello from Gemini!")
    }

    @Test
    fun `chat - request format includes system prompt and user message`() = runTest {
        val service = GeminiService(
            apiKey = "test-key",
            modelId = "gemini-2.5-flash",
            systemPrompt = "You are a helpful assistant",
            baseUrl = mockServer.baseUrlNoSlash
        )
        mockServer.server.enqueue(
            jsonResponse(TestFixtures.MockResponses.geminiChatSuccess("Hi!"))
        )

        service.chat("Hello")

        val request = mockServer.server.takeRequest()
        val body = JSONObject(request.body.readUtf8())
        val contents = body.getJSONArray("contents")
        // First content should be system prompt (as user role)
        val systemContent = contents.getJSONObject(0)
        assertThat(systemContent.getString("role")).isEqualTo("user")
        // Verify generationConfig
        val config = body.getJSONObject("generationConfig")
        assertThat(config.has("temperature")).isTrue()
        assertThat(config.has("maxOutputTokens")).isTrue()
    }

    @Test
    fun `chat - blank API key returns error message`() = runTest {
        val service = createService(apiKey = "")

        val result = service.chat("Hello")

        assertThat(result).contains("API key")
    }

    @Test
    fun `chat - server error returns fallback message`() = runBlocking {
        val service = createService()
        repeat(3) {
            mockServer.server.enqueue(
                jsonResponse(TestFixtures.MockResponses.geminiError(500, "Internal"), 500)
            )
        }

        val result = service.chat("Hello")

        assertThat(result).contains("unavailable")
    }

    @Test
    fun `chat - API returns 429 then success retries and returns text`() = runBlocking {
        // 測試：429 後重試成功
        val service = createService()
        mockServer.server.enqueue(jsonResponse("{\"error\":{\"message\":\"rate limited\"}}", 429))
        mockServer.server.enqueue(jsonResponse(TestFixtures.MockResponses.geminiChatSuccess("retry-ok")))

        val result = service.chat("retry test")

        assertThat(result).isEqualTo("retry-ok")
    }

    @Test
    fun `chat - conversation history is maintained`() = runTest {
        val service = createService()
        // First message
        mockServer.server.enqueue(
            jsonResponse(TestFixtures.MockResponses.geminiChatSuccess("Response 1"))
        )
        service.chat("Message 1")

        // Second message
        mockServer.server.enqueue(
            jsonResponse(TestFixtures.MockResponses.geminiChatSuccess("Response 2"))
        )
        service.chat("Message 2")

        // Take first request (discard), take second
        mockServer.server.takeRequest()
        val secondRequest = mockServer.server.takeRequest()
        val body = JSONObject(secondRequest.body.readUtf8())
        val contents = body.getJSONArray("contents")
        // Should have: system prompt (user), system ack (model), history (user+model), current message
        assertThat(contents.length()).isGreaterThan(3)
    }

    @Test
    fun `clearHistory - resets conversation context`() = runTest {
        val service = createService()
        // Build history
        mockServer.server.enqueue(
            jsonResponse(TestFixtures.MockResponses.geminiChatSuccess("First"))
        )
        service.chat("First message")
        service.clearHistory()

        // Next chat should not have history
        mockServer.server.enqueue(
            jsonResponse(TestFixtures.MockResponses.geminiChatSuccess("After clear"))
        )
        service.chat("After clear")

        mockServer.server.takeRequest() // discard first
        val request = mockServer.server.takeRequest()
        val body = JSONObject(request.body.readUtf8())
        val contents = body.getJSONArray("contents")
        // Should only have system prompt (user+model) + current message = 3
        assertThat(contents.length()).isEqualTo(3)
    }

    // ==================== AnalyzeImage Tests ====================

    @Test
    fun `analyzeImage - success returns analysis text`() = runTest {
        val service = createService()
        mockServer.server.enqueue(
            jsonResponse(TestFixtures.MockResponses.geminiChatSuccess("A cat sitting on a table"))
        )

        val result = service.analyzeImage(TestFixtures.createTestJpeg(), "Describe this image")

        assertThat(result).isEqualTo("A cat sitting on a table")
    }

    @Test
    fun `analyzeImage - request contains base64 image and prompt`() = runTest {
        val service = createService()
        mockServer.server.enqueue(
            jsonResponse(TestFixtures.MockResponses.geminiChatSuccess("Description"))
        )

        service.analyzeImage(TestFixtures.createTestJpeg(), "What is this?")

        val request = mockServer.server.takeRequest()
        val body = JSONObject(request.body.readUtf8())
        val parts = body.getJSONArray("contents")
            .getJSONObject(0)
            .getJSONArray("parts")
        // First part: inline_data with image/jpeg
        val inlineData = parts.getJSONObject(0).getJSONObject("inline_data")
        assertThat(inlineData.getString("mime_type")).isEqualTo("image/jpeg")
        assertThat(inlineData.getString("data")).isNotEmpty()
        // Second part: text prompt
        assertThat(parts.getJSONObject(1).getString("text")).isEqualTo("What is this?")
    }

    @Test
    fun `analyzeImage - blank API key returns error message`() = runTest {
        val service = createService(apiKey = "")

        val result = service.analyzeImage(TestFixtures.createTestJpeg())

        assertThat(result).contains("API key")
    }

    @Test
    fun `analyzeImage - server error returns fallback message`() = runBlocking {
        val service = createService()
        repeat(3) {
            mockServer.server.enqueue(
                jsonResponse(TestFixtures.MockResponses.geminiError(500, "Error"), 500)
            )
        }

        val result = service.analyzeImage(TestFixtures.createTestJpeg(), "Describe")

        assertThat(result).contains("unable to analyze")
    }

    // ==================== Provider & Default URL Tests ====================

    @Test
    fun `provider returns GEMINI`() {
        val service = createService()
        assertThat(service.provider).isEqualTo(com.example.rokidphone.data.AiProvider.GEMINI)
    }

    @Test
    fun `default base URL matches expected Gemini API endpoint`() {
        assertThat(GeminiService.DEFAULT_BASE_URL).isEqualTo(
            "https://generativelanguage.googleapis.com/v1beta/models"
        )
    }

    @Test
    fun `custom baseUrl is used in API requests`() = runTest {
        val service = createService()
        mockServer.server.enqueue(
            jsonResponse(TestFixtures.MockResponses.geminiChatSuccess("ok"))
        )

        service.chat("test")

        val request = mockServer.server.takeRequest()
        assertThat(request.requestUrl.toString()).startsWith(mockServer.baseUrl)
    }
}
