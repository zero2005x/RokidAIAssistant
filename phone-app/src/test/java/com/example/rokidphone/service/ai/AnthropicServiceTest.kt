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
 * Unit tests for AnthropicService.
 * Tests Claude chat and vision endpoints using MockWebServer.
 */
@RunWith(RobolectricTestRunner::class)
class AnthropicServiceTest {

    @get:Rule
    val mockServer = MockWebServerRule()

    private fun createService(
        apiKey: String = "test-api-key",
        modelId: String = "claude-sonnet-4-5"
    ): AnthropicService = AnthropicService(
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
    fun `transcribe - returns not supported error`() = runTest {
        val service = createService()

        val result = service.transcribe(TestFixtures.createTestPcmAudio(), "zh-TW")

        assertThat(result).isInstanceOf(SpeechResult.Error::class.java)
        assertThat((result as SpeechResult.Error).message).contains("does not support")
    }

    // ==================== Chat Tests ====================

    @Test
    fun `chat - success returns response text`() = runTest {
        val service = createService()
        mockServer.server.enqueue(
            jsonResponse(TestFixtures.MockResponses.anthropicChatSuccess("Hello from Claude!"))
        )

        val result = service.chat("Hi")

        assertThat(result).isEqualTo("Hello from Claude!")
    }

    @Test
    fun `chat - request uses correct headers`() = runTest {
        val service = createService(apiKey = "sk-ant-test-key")
        mockServer.server.enqueue(
            jsonResponse(TestFixtures.MockResponses.anthropicChatSuccess("Hi"))
        )

        service.chat("Hello")

        val request = mockServer.server.takeRequest()
        assertThat(request.headers["x-api-key"]).isEqualTo("sk-ant-test-key")
        assertThat(request.headers["anthropic-version"]).isEqualTo(AnthropicService.API_VERSION)
        assertThat(request.headers["Content-Type"]).contains("application/json")
    }

    @Test
    fun `chat - request body format is correct`() = runTest {
        val service = AnthropicService(
            apiKey = "test-key",
            modelId = "claude-sonnet-4-5",
            systemPrompt = "Be helpful",
            temperature = 0.5f,
            maxTokens = 1024,
            topP = 0.9f,
            baseUrl = mockServer.baseUrlNoSlash
        )
        mockServer.server.enqueue(
            jsonResponse(TestFixtures.MockResponses.anthropicChatSuccess("Ok"))
        )

        service.chat("Test message")

        val request = mockServer.server.takeRequest()
        assertThat(request.path).isEqualTo("/messages")
        val body = JSONObject(request.body.readUtf8())
        assertThat(body.getString("model")).isEqualTo("claude-sonnet-4-5")
        assertThat(body.getInt("max_tokens")).isEqualTo(1024)
        assertThat(body.getDouble("temperature")).isWithin(0.01).of(0.5)
        assertThat(body.getDouble("top_p")).isWithin(0.01).of(0.9)
        assertThat(body.getString("system")).contains("Be helpful")
        // Last message should be the user message
        val messages = body.getJSONArray("messages")
        val lastMsg = messages.getJSONObject(messages.length() - 1)
        assertThat(lastMsg.getString("role")).isEqualTo("user")
        assertThat(lastMsg.getString("content")).isEqualTo("Test message")
    }

    @Test
    fun `chat - server error returns fallback message`() = runBlocking {
        val service = createService()
        repeat(3) {
            mockServer.server.enqueue(
                jsonResponse(TestFixtures.MockResponses.anthropicError("server_error", "Internal"), 500)
            )
        }

        val result = service.chat("Hello")

        assertThat(result).contains("unavailable")
    }

    @Test
    fun `chat - empty response text returns fallback`() = runBlocking {
        val service = createService()
        // Enqueue enough for retries
        repeat(3) {
            mockServer.server.enqueue(
                jsonResponse(TestFixtures.MockResponses.anthropicChatSuccess(""))
            )
        }

        val result = service.chat("Hello")

        assertThat(result).contains("unavailable")
    }

    @Test
    fun `chat - conversation history is maintained across calls`() = runTest {
        val service = createService()
        // First call
        mockServer.server.enqueue(
            jsonResponse(TestFixtures.MockResponses.anthropicChatSuccess("Response 1"))
        )
        service.chat("Message 1")

        // Second call
        mockServer.server.enqueue(
            jsonResponse(TestFixtures.MockResponses.anthropicChatSuccess("Response 2"))
        )
        service.chat("Message 2")

        mockServer.server.takeRequest() // skip first
        val secondRequest = mockServer.server.takeRequest()
        val body = JSONObject(secondRequest.body.readUtf8())
        val messages = body.getJSONArray("messages")
        // Should have history (user+assistant from first) + current = 3
        assertThat(messages.length()).isEqualTo(3)
        assertThat(messages.getJSONObject(0).getString("role")).isEqualTo("user")
        assertThat(messages.getJSONObject(0).getString("content")).isEqualTo("Message 1")
        assertThat(messages.getJSONObject(1).getString("role")).isEqualTo("assistant")
    }

    @Test
    fun `clearHistory - resets conversation`() = runTest {
        val service = createService()
        mockServer.server.enqueue(
            jsonResponse(TestFixtures.MockResponses.anthropicChatSuccess("First"))
        )
        service.chat("First message")
        service.clearHistory()

        mockServer.server.enqueue(
            jsonResponse(TestFixtures.MockResponses.anthropicChatSuccess("After clear"))
        )
        service.chat("After clear")

        mockServer.server.takeRequest()
        val request = mockServer.server.takeRequest()
        val body = JSONObject(request.body.readUtf8())
        val messages = body.getJSONArray("messages")
        // Only the current user message, no history
        assertThat(messages.length()).isEqualTo(1)
    }

    // ==================== AnalyzeImage Tests ====================

    @Test
    fun `analyzeImage - success returns analysis text`() = runTest {
        val service = createService()
        mockServer.server.enqueue(
            jsonResponse(TestFixtures.MockResponses.anthropicChatSuccess("A landscape photo"))
        )

        val result = service.analyzeImage(TestFixtures.createTestJpeg(), "Describe this")

        assertThat(result).isEqualTo("A landscape photo")
    }

    @Test
    fun `analyzeImage - request body contains image and prompt`() = runTest {
        val service = createService()
        mockServer.server.enqueue(
            jsonResponse(TestFixtures.MockResponses.anthropicChatSuccess("Image analysis"))
        )

        service.analyzeImage(TestFixtures.createTestJpeg(), "What is this?")

        val request = mockServer.server.takeRequest()
        val body = JSONObject(request.body.readUtf8())
        val messages = body.getJSONArray("messages")
        val content = messages.getJSONObject(0).getJSONArray("content")
        // First element: image
        assertThat(content.getJSONObject(0).getString("type")).isEqualTo("image")
        val source = content.getJSONObject(0).getJSONObject("source")
        assertThat(source.getString("type")).isEqualTo("base64")
        assertThat(source.getString("media_type")).isEqualTo("image/jpeg")
        // Second element: text prompt
        assertThat(content.getJSONObject(1).getString("type")).isEqualTo("text")
        assertThat(content.getJSONObject(1).getString("text")).isEqualTo("What is this?")
    }

    @Test
    fun `analyzeImage - server error returns fallback`() = runBlocking {
        val service = createService()
        repeat(3) {
            mockServer.server.enqueue(
                jsonResponse(TestFixtures.MockResponses.anthropicError("server_error", "Error"), 500)
            )
        }

        val result = service.analyzeImage(TestFixtures.createTestJpeg())

        assertThat(result).contains("unable to analyze")
    }

    // ==================== Provider & Default URL ====================

    @Test
    fun `provider returns ANTHROPIC`() {
        val service = createService()
        assertThat(service.provider).isEqualTo(com.example.rokidphone.data.AiProvider.ANTHROPIC)
    }

    @Test
    fun `default base URL matches expected Anthropic endpoint`() {
        assertThat(AnthropicService.DEFAULT_BASE_URL).isEqualTo("https://api.anthropic.com/v1")
    }

    @Test
    fun `API_VERSION constant is 2023-06-01`() {
        assertThat(AnthropicService.API_VERSION).isEqualTo("2023-06-01")
    }
}
