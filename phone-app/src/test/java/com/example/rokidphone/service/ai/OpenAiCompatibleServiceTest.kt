package com.example.rokidphone.service.ai

import com.example.rokidphone.data.AiProvider
import com.example.rokidphone.service.SpeechResult
import com.example.rokidphone.testutil.MockWebServerRule
import com.example.rokidphone.testutil.TestFixtures
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
 * Unit tests for OpenAiCompatibleService.
 * Tests chat, whisper STT, vision, and test connection using MockWebServer.
 */
@RunWith(RobolectricTestRunner::class)
class OpenAiCompatibleServiceTest {

    @get:Rule
    val mockServer = MockWebServerRule()

    private fun createService(
        apiKey: String = "test-api-key",
        modelId: String = "gpt-4",
        providerType: AiProvider = AiProvider.OPENAI
    ): OpenAiCompatibleService = OpenAiCompatibleService(
        apiKey = apiKey,
        baseUrl = mockServer.baseUrl,
        modelId = modelId,
        providerType = providerType
    )

    /** Helper to create a JSON MockResponse. */
    private fun jsonResponse(body: String, code: Int = 200) = MockResponse(
        code = code,
        body = body,
        headers = headersOf("Content-Type", "application/json")
    )

    // ==================== Chat Tests ====================

    @Test
    fun `chat - success returns response text`() = runTest {
        val service = createService()
        mockServer.server.enqueue(
            jsonResponse(TestFixtures.MockResponses.openAiChatSuccess("Hello from GPT!"))
        )

        val result = service.chat("Hi")

        assertThat(result).isEqualTo("Hello from GPT!")
    }

    @Test
    fun `chat - request body format is correct`() = runTest {
        val service = OpenAiCompatibleService(
            apiKey = "sk-test",
            baseUrl = mockServer.baseUrl,
            modelId = "gpt-4o",
            systemPrompt = "Be brief",
            temperature = 0.3f,
            maxTokens = 512,
            topP = 0.95f,
            frequencyPenalty = 0.5f,
            presencePenalty = 0.2f
        )
        mockServer.server.enqueue(
            jsonResponse(TestFixtures.MockResponses.openAiChatSuccess("Ok"))
        )

        service.chat("Test")

        val request = mockServer.server.takeRequest()
        assertThat(request.path).isEqualTo("/chat/completions")
        assertThat(request.headers["Authorization"]).isEqualTo("Bearer sk-test")
        val body = JSONObject(request.body.readUtf8())
        assertThat(body.getString("model")).isEqualTo("gpt-4o")
        assertThat(body.getDouble("temperature")).isWithin(0.01).of(0.3)
        assertThat(body.getInt("max_tokens")).isEqualTo(512)
        assertThat(body.getDouble("top_p")).isWithin(0.01).of(0.95)
        assertThat(body.getDouble("frequency_penalty")).isWithin(0.01).of(0.5)
        assertThat(body.getDouble("presence_penalty")).isWithin(0.01).of(0.2)
        assertThat(body.getBoolean("stream")).isFalse()
        // Messages should start with system prompt
        val messages = body.getJSONArray("messages")
        assertThat(messages.getJSONObject(0).getString("role")).isEqualTo("system")
        assertThat(messages.getJSONObject(0).getString("content")).contains("Be brief")
    }

    @Test
    fun `chat - server error returns error message`() = runTest {
        val service = createService()
        mockServer.server.enqueue(
            jsonResponse(TestFixtures.MockResponses.openAiError("invalid_api_key", "Invalid key"), 401)
        )

        val result = service.chat("Hello")

        assertThat(result).contains("Invalid key")
    }

    @Test
    fun `chat - conversation history is maintained`() = runTest {
        val service = createService()
        mockServer.server.enqueue(
            jsonResponse(TestFixtures.MockResponses.openAiChatSuccess("R1"))
        )
        service.chat("M1")

        mockServer.server.enqueue(
            jsonResponse(TestFixtures.MockResponses.openAiChatSuccess("R2"))
        )
        service.chat("M2")

        mockServer.server.takeRequest()
        val second = mockServer.server.takeRequest()
        val body = JSONObject(second.body.readUtf8())
        val messages = body.getJSONArray("messages")
        // system + history (user M1, assistant R1) + user M2 = 4
        assertThat(messages.length()).isEqualTo(4)
    }

    @Test
    fun `chat - no API key omits Authorization header`() = runTest {
        val service = createService(apiKey = "")
        mockServer.server.enqueue(
            jsonResponse(TestFixtures.MockResponses.openAiChatSuccess("Ok"))
        )

        service.chat("Hello")

        val request = mockServer.server.takeRequest()
        assertThat(request.headers["Authorization"]).isNull()
    }

    // ==================== Transcribe (Whisper) Tests ====================

    @Test
    fun `transcribe - OpenAI whisper success`() = runTest {
        val service = createService(providerType = AiProvider.OPENAI)
        mockServer.server.enqueue(
            jsonResponse(TestFixtures.MockResponses.openAiWhisperSuccess("Hello world"))
        )

        val result = service.transcribe(TestFixtures.createTestPcmAudio(), "en-US")

        assertThat(result).isInstanceOf(SpeechResult.Success::class.java)
        assertThat((result as SpeechResult.Success).text).isEqualTo("Hello world")
    }

    @Test
    fun `transcribe - Groq uses whisper-large-v3-turbo model`() = runTest {
        val service = createService(providerType = AiProvider.GROQ, modelId = "llama-3")
        mockServer.server.enqueue(
            jsonResponse(TestFixtures.MockResponses.openAiWhisperSuccess("Test"))
        )

        service.transcribe(TestFixtures.createTestPcmAudio(), "en-US")

        val request = mockServer.server.takeRequest()
        assertThat(request.path).isEqualTo("/audio/transcriptions")
        val bodyString = request.body.readUtf8()
        assertThat(bodyString).contains("whisper-large-v3-turbo")
    }

    @Test
    fun `transcribe - DeepSeek returns not supported`() = runTest {
        val service = createService(providerType = AiProvider.DEEPSEEK)

        val result = service.transcribe(TestFixtures.createTestPcmAudio(), "zh-TW")

        assertThat(result).isInstanceOf(SpeechResult.Error::class.java)
        assertThat((result as SpeechResult.Error).message).contains("does not support")
    }

    @Test
    fun `transcribe - CUSTOM returns not supported`() = runTest {
        val service = createService(providerType = AiProvider.CUSTOM)

        val result = service.transcribe(TestFixtures.createTestPcmAudio(), "en-US")

        assertThat(result).isInstanceOf(SpeechResult.Error::class.java)
    }

    @Test
    fun `transcribe - audio too short returns error`() = runTest {
        val service = createService()

        val result = service.transcribe(TestFixtures.createTooShortAudio(), "zh-TW")

        assertThat(result).isInstanceOf(SpeechResult.Error::class.java)
        assertThat((result as SpeechResult.Error).message).contains("too short")
    }

    @Test
    fun `transcribe - empty text returns error`() = runTest {
        val service = createService()
        mockServer.server.enqueue(
            jsonResponse(TestFixtures.MockResponses.openAiWhisperSuccess(""))
        )

        val result = service.transcribe(TestFixtures.createTestPcmAudio(), "en-US")

        assertThat(result).isInstanceOf(SpeechResult.Error::class.java)
    }

    @Test
    fun `transcribe - server error returns error result`() = runTest {
        val service = createService()
        mockServer.server.enqueue(
            jsonResponse(TestFixtures.MockResponses.openAiError("server_error", "Internal"), 500)
        )

        val result = service.transcribe(TestFixtures.createTestPcmAudio(), "en-US")

        assertThat(result).isInstanceOf(SpeechResult.Error::class.java)
    }

    // ==================== AnalyzeImage Tests ====================

    @Test
    fun `analyzeImage - success with vision-capable provider`() = runTest {
        val service = createService(providerType = AiProvider.OPENAI)
        mockServer.server.enqueue(
            jsonResponse(TestFixtures.MockResponses.openAiChatSuccess("A beautiful sunset"))
        )

        val result = service.analyzeImage(TestFixtures.createTestJpeg(), "Describe this")

        assertThat(result).isEqualTo("A beautiful sunset")
    }

    @Test
    fun `analyzeImage - request contains base64 image URL`() = runTest {
        val service = createService(providerType = AiProvider.OPENAI)
        mockServer.server.enqueue(
            jsonResponse(TestFixtures.MockResponses.openAiChatSuccess("Image"))
        )

        service.analyzeImage(TestFixtures.createTestJpeg(), "What?")

        val request = mockServer.server.takeRequest()
        val body = JSONObject(request.body.readUtf8())
        val messages = body.getJSONArray("messages")
        val content = messages.getJSONObject(0).getJSONArray("content")
        // Should have text and image_url parts
        val textPart = content.getJSONObject(0)
        assertThat(textPart.getString("type")).isEqualTo("text")
        assertThat(textPart.getString("text")).isEqualTo("What?")
        val imagePart = content.getJSONObject(1)
        assertThat(imagePart.getString("type")).isEqualTo("image_url")
        assertThat(imagePart.getJSONObject("image_url").getString("url"))
            .startsWith("data:image/jpeg;base64,")
    }

    @Test
    fun `analyzeImage - non-vision provider returns not supported`() = runTest {
        val service = createService(providerType = AiProvider.DEEPSEEK)

        val result = service.analyzeImage(TestFixtures.createTestJpeg())

        assertThat(result).contains("does not support")
    }

    // ==================== TestConnection Tests ====================

    @Test
    fun `testConnection - success with models endpoint`() = runTest {
        val service = createService()
        val modelsResponse = """
            {
              "data": [
                {"id": "gpt-4", "object": "model"},
                {"id": "gpt-3.5-turbo", "object": "model"}
              ]
            }
        """.trimIndent()
        mockServer.server.enqueue(jsonResponse(modelsResponse))

        val result = service.testConnection()

        assertThat(result.isSuccess).isTrue()
        assertThat(result.getOrNull()).contains("2 models")
    }

    @Test
    fun `testConnection - 401 returns auth failure`() = runTest {
        val service = createService()
        mockServer.server.enqueue(
            jsonResponse(TestFixtures.MockResponses.openAiError("invalid_api_key", "Invalid"), 401)
        )

        val result = service.testConnection()

        assertThat(result.isFailure).isTrue()
        assertThat(result.exceptionOrNull()?.message).contains("Authentication failed")
    }

    @Test
    fun `testConnection - 404 falls back to chat test`() = runTest {
        val service = createService()
        // First: /models returns 404
        mockServer.server.enqueue(MockResponse(code = 404))
        // Fallback: /chat/completions succeeds
        mockServer.server.enqueue(
            jsonResponse(TestFixtures.MockResponses.openAiChatSuccess("Hi"))
        )

        val result = service.testConnection()

        assertThat(result.isSuccess).isTrue()
    }

    // ==================== Provider Tests ====================

    @Test
    fun `provider reflects configured provider type`() {
        val openai = createService(providerType = AiProvider.OPENAI)
        assertThat(openai.provider).isEqualTo(AiProvider.OPENAI)

        val deepseek = createService(providerType = AiProvider.DEEPSEEK)
        assertThat(deepseek.provider).isEqualTo(AiProvider.DEEPSEEK)

        val groq = createService(providerType = AiProvider.GROQ)
        assertThat(groq.provider).isEqualTo(AiProvider.GROQ)
    }

    @Test
    fun `provider matrix supports xAI Alibaba Zhipu and Perplexity configs`() = runTest {
        // 測試：OpenAI 相容服務可覆蓋 xAI/Alibaba/Zhipu/Perplexity
        val providers = listOf(AiProvider.XAI, AiProvider.ALIBABA, AiProvider.ZHIPU, AiProvider.PERPLEXITY)

        for (provider in providers) {
            val service = createService(providerType = provider)
            mockServer.server.enqueue(jsonResponse(TestFixtures.MockResponses.openAiChatSuccess("ok-$provider")))
            val result = service.chat("ping")
            assertThat(result).isEqualTo("ok-$provider")
        }
    }

    @Test
    fun `clearHistory - clears conversation`() = runTest {
        val service = createService()
        mockServer.server.enqueue(
            jsonResponse(TestFixtures.MockResponses.openAiChatSuccess("R"))
        )
        service.chat("M")
        service.clearHistory()

        mockServer.server.enqueue(
            jsonResponse(TestFixtures.MockResponses.openAiChatSuccess("R2"))
        )
        service.chat("M2")

        mockServer.server.takeRequest()
        val req = mockServer.server.takeRequest()
        val body = JSONObject(req.body.readUtf8())
        val messages = body.getJSONArray("messages")
        // system + user = 2 (no history)
        assertThat(messages.length()).isEqualTo(2)
    }
}
