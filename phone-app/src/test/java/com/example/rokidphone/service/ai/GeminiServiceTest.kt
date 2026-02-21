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
        // The system prompt is sent via the top-level "systemInstruction" field (not in contents).
        // contents should have: history (user + model from turn 1) + current message = 3 entries.
        // i.e. contents.length() > 2 proves that prior history is being included.
        assertThat(contents.length()).isGreaterThan(2)
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
        // The system prompt lives in the top-level "systemInstruction" field, not in contents.
        // After clearHistory() the conversation history is empty, so contents should contain
        // only the single current user message.
        assertThat(contents.length()).isEqualTo(1)
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

    // ==================== Transcribe - isGeminiErrorResponse patterns ====================

    @Test
    fun `transcribe - i am sorry pattern is filtered as error`() = runBlocking {
        val service = createService()
        repeat(3) {
            mockServer.server.enqueue(
                jsonResponse(TestFixtures.MockResponses.geminiTranscribeSuccess("I am sorry, I cannot provide a transcription"))
            )
        }
        val result = service.transcribe(TestFixtures.createTestPcmAudio(), "zh-TW")
        assertThat(result).isInstanceOf(SpeechResult.Error::class.java)
    }

    @Test
    fun `transcribe - silence keyword is filtered as error`() = runBlocking {
        val service = createService()
        repeat(3) {
            mockServer.server.enqueue(
                jsonResponse(TestFixtures.MockResponses.geminiTranscribeSuccess("This audio contains only silence"))
            )
        }
        val result = service.transcribe(TestFixtures.createTestPcmAudio(), "zh-TW")
        assertThat(result).isInstanceOf(SpeechResult.Error::class.java)
    }

    @Test
    fun `transcribe - no speech keyword is filtered as error`() = runBlocking {
        val service = createService()
        repeat(3) {
            mockServer.server.enqueue(
                jsonResponse(TestFixtures.MockResponses.geminiTranscribeSuccess("There is no speech in this recording"))
            )
        }
        val result = service.transcribe(TestFixtures.createTestPcmAudio(), "zh-TW")
        assertThat(result).isInstanceOf(SpeechResult.Error::class.java)
    }

    // ==================== Transcribe - isInvalidTranscription branches ====================

    @Test
    fun `transcribe - single character response is filtered as invalid`() = runBlocking {
        val service = createService()
        repeat(3) {
            mockServer.server.enqueue(
                jsonResponse(TestFixtures.MockResponses.geminiTranscribeSuccess("A"))
            )
        }
        val result = service.transcribe(TestFixtures.createTestPcmAudio(), "zh-TW")
        assertThat(result).isInstanceOf(SpeechResult.Error::class.java)
    }

    @Test
    fun `transcribe - high ratio of zeros and colons is filtered as invalid`() = runBlocking {
        // 22 chars, 20 are '0'/':'/' ' → ratio > 0.7 and length > 10
        val service = createService()
        repeat(3) {
            mockServer.server.enqueue(
                jsonResponse(TestFixtures.MockResponses.geminiTranscribeSuccess("00:00:00:00:00:00:00:0"))
            )
        }
        val result = service.transcribe(TestFixtures.createTestPcmAudio(), "zh-TW")
        assertThat(result).isInstanceOf(SpeechResult.Error::class.java)
    }

    @Test
    fun `transcribe - highly repetitive pattern is filtered as invalid`() = runBlocking {
        // "abcde" repeated 5x = 25 chars; first-5 "abcde" occurs 5 times > 25/8 = 3.125
        val service = createService()
        repeat(3) {
            mockServer.server.enqueue(
                jsonResponse(TestFixtures.MockResponses.geminiTranscribeSuccess("abcdeabcdeabcdeabcdeabcde"))
            )
        }
        val result = service.transcribe(TestFixtures.createTestPcmAudio(), "zh-TW")
        assertThat(result).isInstanceOf(SpeechResult.Error::class.java)
    }

    // ==================== Transcribe - getLanguageDisplayName branches ====================

    @Test
    fun `transcribe - ko-KR sends Korean in transcription prompt`() = runTest {
        val service = createService()
        mockServer.server.enqueue(jsonResponse(TestFixtures.MockResponses.geminiTranscribeSuccess("안녕하세요")))
        val result = service.transcribe(TestFixtures.createTestPcmAudio(), "ko-KR")
        // Also verify a valid Korean result passes through
        assertThat(result).isInstanceOf(SpeechResult.Success::class.java)
        assertThat((result as SpeechResult.Success).text).isEqualTo("안녕하세요")

        val request = mockServer.server.takeRequest()
        val promptText = JSONObject(request.body.readUtf8())
            .getJSONArray("contents").getJSONObject(0)
            .getJSONArray("parts").getJSONObject(1).getString("text")
        assertThat(promptText).contains("Korean")
    }

    @Test
    fun `transcribe - ja-JP sends Japanese in transcription prompt`() = runTest {
        val service = createService()
        mockServer.server.enqueue(jsonResponse(TestFixtures.MockResponses.geminiTranscribeSuccess("テスト")))
        service.transcribe(TestFixtures.createTestPcmAudio(), "ja-JP")
        val request = mockServer.server.takeRequest()
        val promptText = JSONObject(request.body.readUtf8())
            .getJSONArray("contents").getJSONObject(0)
            .getJSONArray("parts").getJSONObject(1).getString("text")
        assertThat(promptText).contains("Japanese")
    }

    @Test
    fun `transcribe - fr sends French in transcription prompt`() = runTest {
        val service = createService()
        mockServer.server.enqueue(jsonResponse(TestFixtures.MockResponses.geminiTranscribeSuccess("Bonjour")))
        service.transcribe(TestFixtures.createTestPcmAudio(), "fr")
        val request = mockServer.server.takeRequest()
        val promptText = JSONObject(request.body.readUtf8())
            .getJSONArray("contents").getJSONObject(0)
            .getJSONArray("parts").getJSONObject(1).getString("text")
        assertThat(promptText).contains("French")
    }

    @Test
    fun `transcribe - zh-CN sends Simplified Chinese in transcription prompt`() = runTest {
        val service = createService()
        mockServer.server.enqueue(jsonResponse(TestFixtures.MockResponses.geminiTranscribeSuccess("你好")))
        service.transcribe(TestFixtures.createTestPcmAudio(), "zh-CN")
        val request = mockServer.server.takeRequest()
        val promptText = JSONObject(request.body.readUtf8())
            .getJSONArray("contents").getJSONObject(0)
            .getJSONArray("parts").getJSONObject(1).getString("text")
        assertThat(promptText).contains("Simplified Chinese")
    }

    @Test
    fun `transcribe - unknown language code sends fallback label in prompt`() = runTest {
        val service = createService()
        mockServer.server.enqueue(jsonResponse(TestFixtures.MockResponses.geminiTranscribeSuccess("hello")))
        service.transcribe(TestFixtures.createTestPcmAudio(), "xx-XX")
        val request = mockServer.server.takeRequest()
        val promptText = JSONObject(request.body.readUtf8())
            .getJSONArray("contents").getJSONObject(0)
            .getJSONArray("parts").getJSONObject(1).getString("text")
        assertThat(promptText).contains("xx-XX")
    }

    @Test
    fun `transcribe - 429 then success retries and returns text`() = runBlocking {
        val service = createService()
        mockServer.server.enqueue(jsonResponse(TestFixtures.MockResponses.geminiError(429, "rate limited"), 429))
        mockServer.server.enqueue(jsonResponse(TestFixtures.MockResponses.geminiTranscribeSuccess("Retry success")))
        val result = service.transcribe(TestFixtures.createTestPcmAudio(), "en-US")
        assertThat(result).isInstanceOf(SpeechResult.Success::class.java)
        assertThat((result as SpeechResult.Success).text).isEqualTo("Retry success")
    }

    // ==================== TranscribeAudioFile - edge cases ====================

    @Test
    fun `transcribeAudioFile - blank API key returns error`() = runTest {
        val service = createService(apiKey = "")
        val result = service.transcribeAudioFile(TestFixtures.createTestPcmAudio(), "audio/m4a", "zh-TW")
        assertThat(result).isInstanceOf(SpeechResult.Error::class.java)
        assertThat((result as SpeechResult.Error).message).contains("API key")
    }

    @Test
    fun `transcribeAudioFile - audio too short returns error`() = runTest {
        val service = createService()
        val result = service.transcribeAudioFile(TestFixtures.createTooShortAudio(), "audio/mp3", "zh-TW")
        assertThat(result).isInstanceOf(SpeechResult.Error::class.java)
        assertThat((result as SpeechResult.Error).message).contains("too short")
    }

    @Test
    fun `transcribeAudioFile - server error returns error`() = runBlocking {
        val service = createService()
        repeat(3) {
            mockServer.server.enqueue(
                jsonResponse(TestFixtures.MockResponses.geminiError(500, "Server Error"), 500)
            )
        }
        val result = service.transcribeAudioFile(TestFixtures.createTestPcmAudio(), "audio/mp3", "zh-TW")
        assertThat(result).isInstanceOf(SpeechResult.Error::class.java)
    }

    @Test
    fun `transcribeAudioFile - unable to recognize returns error`() = runBlocking {
        val service = createService()
        repeat(3) {
            mockServer.server.enqueue(
                jsonResponse(TestFixtures.MockResponses.geminiTranscribeSuccess("Unable to recognize"))
            )
        }
        val result = service.transcribeAudioFile(TestFixtures.createTestPcmAudio(), "audio/m4a", "zh-TW")
        assertThat(result).isInstanceOf(SpeechResult.Error::class.java)
    }

    @Test
    fun `transcribeAudioFile - mp3 mime type is passed through in request`() = runTest {
        val service = createService()
        mockServer.server.enqueue(jsonResponse(TestFixtures.MockResponses.geminiTranscribeSuccess("Hello")))
        service.transcribeAudioFile(TestFixtures.createTestPcmAudio(), "audio/mp3", "en-US")
        val request = mockServer.server.takeRequest()
        val mimeType = JSONObject(request.body.readUtf8())
            .getJSONArray("contents").getJSONObject(0)
            .getJSONArray("parts").getJSONObject(0)
            .getJSONObject("inline_data").getString("mime_type")
        assertThat(mimeType).isEqualTo("audio/mp3")
    }

    @Test
    fun `transcribeAudioFile - 429 then success retries and returns text`() = runBlocking {
        val service = createService()
        mockServer.server.enqueue(jsonResponse(TestFixtures.MockResponses.geminiError(429, "rate limited"), 429))
        mockServer.server.enqueue(jsonResponse(TestFixtures.MockResponses.geminiTranscribeSuccess("File result")))
        val result = service.transcribeAudioFile(TestFixtures.createTestPcmAudio(), "audio/m4a", "en-US")
        assertThat(result).isInstanceOf(SpeechResult.Success::class.java)
        assertThat((result as SpeechResult.Success).text).isEqualTo("File result")
    }

    // ==================== Chat - additional coverage ====================

    @Test
    fun `chat - systemInstruction field is present and contains system prompt text`() = runTest {
        val service = GeminiService(
            apiKey = "key",
            modelId = "gemini-2.5-flash",
            systemPrompt = "Be helpful",
            baseUrl = mockServer.baseUrlNoSlash
        )
        mockServer.server.enqueue(jsonResponse(TestFixtures.MockResponses.geminiChatSuccess("hi")))
        service.chat("Hello")
        val body = JSONObject(mockServer.server.takeRequest().body.readUtf8())
        assertThat(body.has("systemInstruction")).isTrue()
        val sysText = body.getJSONObject("systemInstruction")
            .getJSONArray("parts").getJSONObject(0).getString("text")
        assertThat(sysText).contains("Be helpful")
    }

    @Test
    fun `chat - generationConfig includes topP with correct value`() = runTest {
        val service = GeminiService(
            apiKey = "key",
            modelId = "gemini-2.5-flash",
            topP = 0.9f,
            baseUrl = mockServer.baseUrlNoSlash
        )
        mockServer.server.enqueue(jsonResponse(TestFixtures.MockResponses.geminiChatSuccess("ok")))
        service.chat("Hello")
        val config = JSONObject(mockServer.server.takeRequest().body.readUtf8())
            .getJSONObject("generationConfig")
        assertThat(config.has("topP")).isTrue()
        assertThat(config.getDouble("topP")).isWithin(0.001).of(0.9)
    }

    @Test
    fun `chat - history entries use role model not role assistant`() = runTest {
        val service = createService()
        mockServer.server.enqueue(jsonResponse(TestFixtures.MockResponses.geminiChatSuccess("R1")))
        service.chat("M1")
        mockServer.server.enqueue(jsonResponse(TestFixtures.MockResponses.geminiChatSuccess("R2")))
        service.chat("M2")
        mockServer.server.takeRequest() // discard turn 1
        val contents = JSONObject(mockServer.server.takeRequest().body.readUtf8())
            .getJSONArray("contents")
        // First two items are the turn-1 history pair
        assertThat(contents.getJSONObject(0).getString("role")).isEqualTo("user")
        assertThat(contents.getJSONObject(1).getString("role")).isEqualTo("model")
    }

    @Test
    fun `chat - conversation history is capped at last 6 entries per request`() = runBlocking {
        val service = createService()
        // 4 turns → 8 history entries; takeLast(6) caps at 6
        repeat(4) { i ->
            mockServer.server.enqueue(jsonResponse(TestFixtures.MockResponses.geminiChatSuccess("R${i + 1}")))
            service.chat("M${i + 1}")
        }
        // 5th request: 6 history entries + 1 current = 7
        mockServer.server.enqueue(jsonResponse(TestFixtures.MockResponses.geminiChatSuccess("R5")))
        service.chat("M5")
        repeat(4) { mockServer.server.takeRequest() }
        val contents = JSONObject(mockServer.server.takeRequest().body.readUtf8())
            .getJSONArray("contents")
        assertThat(contents.length()).isEqualTo(7)
    }

    @Test
    fun `chat - 503 service overloaded exhausts retries and returns fallback`() = runBlocking {
        val service = createService()
        repeat(3) {
            mockServer.server.enqueue(
                jsonResponse(TestFixtures.MockResponses.geminiError(503, "Service Unavailable"), 503)
            )
        }
        val result = service.chat("Hello")
        assertThat(result).contains("unavailable")
    }

    @Test
    fun `chat - empty candidates array returns fallback message`() = runBlocking {
        val service = createService()
        repeat(3) {
            mockServer.server.enqueue(jsonResponse("""{"candidates": []}"""))
        }
        val result = service.chat("Hello")
        assertThat(result).contains("unavailable")
    }

    @Test
    fun `chat - request URL contains API key`() = runTest {
        val service = createService(apiKey = "my-secret-key")
        mockServer.server.enqueue(jsonResponse(TestFixtures.MockResponses.geminiChatSuccess("ok")))
        service.chat("Test")
        assertThat(mockServer.server.takeRequest().path).contains("key=my-secret-key")
    }

    @Test
    fun `chat - request URL contains model ID`() = runTest {
        val service = createService(modelId = "gemini-2.5-pro")
        mockServer.server.enqueue(jsonResponse(TestFixtures.MockResponses.geminiChatSuccess("ok")))
        service.chat("Test")
        assertThat(mockServer.server.takeRequest().path).contains("gemini-2.5-pro")
    }

    // ==================== AnalyzeImage - additional coverage ====================

    @Test
    fun `analyzeImage - 429 then success retries and returns text`() = runBlocking {
        val service = createService()
        mockServer.server.enqueue(jsonResponse(TestFixtures.MockResponses.geminiError(429, "rate limited"), 429))
        mockServer.server.enqueue(jsonResponse(TestFixtures.MockResponses.geminiChatSuccess("Image analysis ok")))
        val result = service.analyzeImage(TestFixtures.createTestJpeg(), "Describe")
        assertThat(result).isEqualTo("Image analysis ok")
    }

    @Test
    fun `analyzeImage - 503 exhausts retries and returns fallback`() = runBlocking {
        val service = createService()
        repeat(3) {
            mockServer.server.enqueue(
                jsonResponse(TestFixtures.MockResponses.geminiError(503, "Unavailable"), 503)
            )
        }
        val result = service.analyzeImage(TestFixtures.createTestJpeg(), "Describe")
        assertThat(result).contains("unable to analyze")
    }

    @Test
    fun `analyzeImage - empty candidates returns fallback message`() = runBlocking {
        val service = createService()
        repeat(3) {
            mockServer.server.enqueue(jsonResponse("""{"candidates": []}"""))
        }
        val result = service.analyzeImage(TestFixtures.createTestJpeg(), "Describe")
        assertThat(result).contains("unable to analyze")
    }

    @Test
    fun `analyzeImage - maxOutputTokens is capped at 4096 when service maxTokens exceeds it`() = runTest {
        // GeminiService.analyzeImage uses maxTokens.coerceAtMost(4096)
        val service = GeminiService(
            apiKey = "key",
            modelId = "gemini-2.5-flash",
            maxTokens = 8192,
            baseUrl = mockServer.baseUrlNoSlash
        )
        mockServer.server.enqueue(jsonResponse(TestFixtures.MockResponses.geminiChatSuccess("ok")))
        service.analyzeImage(TestFixtures.createTestJpeg(), "Describe")
        val maxOutputTokens = JSONObject(mockServer.server.takeRequest().body.readUtf8())
            .getJSONObject("generationConfig").getInt("maxOutputTokens")
        assertThat(maxOutputTokens).isEqualTo(4096)
    }

    @Test
    fun `analyzeImage - maxOutputTokens uses configured value when below 4096`() = runTest {
        val service = GeminiService(
            apiKey = "key",
            modelId = "gemini-2.5-flash",
            maxTokens = 1024,
            baseUrl = mockServer.baseUrlNoSlash
        )
        mockServer.server.enqueue(jsonResponse(TestFixtures.MockResponses.geminiChatSuccess("ok")))
        service.analyzeImage(TestFixtures.createTestJpeg(), "Describe")
        val maxOutputTokens = JSONObject(mockServer.server.takeRequest().body.readUtf8())
            .getJSONObject("generationConfig").getInt("maxOutputTokens")
        assertThat(maxOutputTokens).isEqualTo(1024)
    }
}
