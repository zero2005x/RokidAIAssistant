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
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Unit tests for BaiduService.
 * Tests OAuth token exchange, chat functionality, and error handling.
 */
@RunWith(RobolectricTestRunner::class)
class BaiduServiceTest {

    @get:Rule
    val mockServer = MockWebServerRule()

    @Before
    fun setUp() = runBlocking {
        // Clear static token cache between tests to ensure isolation
        createService().clearTokenCache()
    }

    private fun createService(
        apiKey: String = "test-api-key",
        secretKey: String = "test-secret-key",
        modelId: String = "ernie-4.0-8k"
    ): BaiduService = BaiduService(
        apiKey = apiKey,
        secretKey = secretKey,
        modelId = modelId,
        tokenUrl = mockServer.baseUrlNoSlash + "/oauth/2.0/token",
        baseChatUrl = mockServer.baseUrlNoSlash + "/rpc/2.0/ai_custom/v1/wenxinworkshop/chat"
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

    // ==================== Token Exchange Tests ====================

    @Test
    fun `chat - token exchange happens before chat request`() = runTest {
        val service = createService()
        // First: token exchange
        mockServer.server.enqueue(
            jsonResponse(TestFixtures.MockResponses.baiduTokenSuccess("my-token"))
        )
        // Second: chat request
        mockServer.server.enqueue(
            jsonResponse(TestFixtures.MockResponses.baiduChatSuccess("Hello!"))
        )

        service.chat("Hi")

        // Verify token request
        val tokenRequest = mockServer.server.takeRequest()
        assertThat(tokenRequest.path).contains("grant_type=client_credentials")
        assertThat(tokenRequest.path).contains("client_id=test-api-key")
        assertThat(tokenRequest.path).contains("client_secret=test-secret-key")

        // Verify chat request
        val chatRequest = mockServer.server.takeRequest()
        assertThat(chatRequest.path).contains("access_token=my-token")
    }

    @Test
    fun `chat - token is cached for subsequent requests`() = runTest {
        val service = createService()
        // Token exchange (only once)
        mockServer.server.enqueue(
            jsonResponse(TestFixtures.MockResponses.baiduTokenSuccess("cached-token"))
        )
        mockServer.server.enqueue(
            jsonResponse(TestFixtures.MockResponses.baiduChatSuccess("First"))
        )
        service.chat("First")

        // Second call should not make another token request
        mockServer.server.enqueue(
            jsonResponse(TestFixtures.MockResponses.baiduChatSuccess("Second"))
        )
        service.chat("Second")

        // Total requests: 1 token + 1 chat + 1 chat = 3
        mockServer.server.takeRequest() // token
        mockServer.server.takeRequest() // first chat
        val thirdReq = mockServer.server.takeRequest() // second chat (no token)
        assertThat(thirdReq.path).contains("access_token=cached-token")
    }

    @Test
    fun `chat - token failure returns auth error message`() = runTest {
        val service = createService()
        // Token exchange fails
        mockServer.server.enqueue(
            jsonResponse("{\"error\": \"invalid_client\"}", 401)
        )

        val result = service.chat("Hello")

        assertThat(result).contains("Authentication failed")
    }

    // ==================== Chat Tests ====================

    @Test
    fun `chat - success returns response text`() = runTest {
        val service = createService()
        mockServer.server.enqueue(
            jsonResponse(TestFixtures.MockResponses.baiduTokenSuccess())
        )
        mockServer.server.enqueue(
            jsonResponse(TestFixtures.MockResponses.baiduChatSuccess("欢迎使用百度！"))
        )

        val result = service.chat("你好")

        assertThat(result).isEqualTo("欢迎使用百度！")
    }

    @Test
    fun `chat - request body format is correct`() = runTest {
        val service = BaiduService(
            apiKey = "key",
            secretKey = "secret",
            modelId = "ernie-4.0-8k",
            systemPrompt = "Be helpful",
            temperature = 0.5f,
            topP = 0.9f,
            tokenUrl = mockServer.baseUrlNoSlash + "/oauth/2.0/token",
            baseChatUrl = mockServer.baseUrlNoSlash + "/rpc/2.0/ai_custom/v1/wenxinworkshop/chat"
        )
        mockServer.server.enqueue(
            jsonResponse(TestFixtures.MockResponses.baiduTokenSuccess())
        )
        mockServer.server.enqueue(
            jsonResponse(TestFixtures.MockResponses.baiduChatSuccess("Ok"))
        )

        service.chat("Test")

        mockServer.server.takeRequest() // token
        val chatReq = mockServer.server.takeRequest()
        assertThat(chatReq.path).contains("completions_pro") // ernie-4.0-8k endpoint
        val body = JSONObject(chatReq.body.readUtf8())
        assertThat(body.getDouble("temperature")).isWithin(0.01).of(0.5)
        assertThat(body.getDouble("top_p")).isWithin(0.01).of(0.9)
        assertThat(body.getBoolean("stream")).isFalse()
        assertThat(body.getString("system")).contains("Be helpful")
        val messages = body.getJSONArray("messages")
        val lastMsg = messages.getJSONObject(messages.length() - 1)
        assertThat(lastMsg.getString("role")).isEqualTo("user")
        assertThat(lastMsg.getString("content")).isEqualTo("Test")
    }

    @Test
    fun `chat - model endpoint mapping works for different models`() = runTest {
        val models = mapOf(
            "ernie-4.0-8k" to "completions_pro",
            "ernie-3.5-8k" to "completions",
            "ernie-speed-8k" to "ernie_speed"
        )

        for ((modelId, expectedEndpoint) in models) {
            val service = createService(modelId = modelId)
            // Clear token cache for fresh test
            service.clearTokenCache()
            mockServer.server.enqueue(
                jsonResponse(TestFixtures.MockResponses.baiduTokenSuccess())
            )
            mockServer.server.enqueue(
                jsonResponse(TestFixtures.MockResponses.baiduChatSuccess("Ok"))
            )

            service.chat("Test")

            mockServer.server.takeRequest() // token
            val chatReq = mockServer.server.takeRequest()
            assertThat(chatReq.path).contains(expectedEndpoint)
        }
    }

    @Test
    fun `chat - API error code 110 clears token cache`() = runTest {
        val service = createService()
        // Token
        mockServer.server.enqueue(
            jsonResponse(TestFixtures.MockResponses.baiduTokenSuccess())
        )
        // Chat returns error 110 (token expired)
        mockServer.server.enqueue(
            jsonResponse(TestFixtures.MockResponses.baiduError(110, "Access token expired"))
        )

        val result = service.chat("Hello")

        assertThat(result).contains("Token expired")
    }

    @Test
    fun `chat - API error code 111 clears token cache`() = runTest {
        val service = createService()
        mockServer.server.enqueue(
            jsonResponse(TestFixtures.MockResponses.baiduTokenSuccess())
        )
        mockServer.server.enqueue(
            jsonResponse(TestFixtures.MockResponses.baiduError(111, "Access token invalid"))
        )

        val result = service.chat("Hello")

        assertThat(result).contains("Token expired")
    }

    @Test
    fun `chat - other API error returns error message`() = runTest {
        val service = createService()
        mockServer.server.enqueue(
            jsonResponse(TestFixtures.MockResponses.baiduTokenSuccess())
        )
        mockServer.server.enqueue(
            jsonResponse(TestFixtures.MockResponses.baiduError(17, "Rate limit exceeded"))
        )

        val result = service.chat("Hello")

        assertThat(result).contains("Rate limit exceeded")
    }

    @Test
    fun `chat - server error returns fallback`() = runTest {
        val service = createService()
        mockServer.server.enqueue(
            jsonResponse(TestFixtures.MockResponses.baiduTokenSuccess())
        )
        mockServer.server.enqueue(
            MockResponse(code = 500, body = "Internal Server Error")
        )

        val result = service.chat("Hello")

        assertThat(result).contains("unavailable")
    }

    // ==================== Conversation History ====================

    @Test
    fun `chat - conversation history is maintained`() = runTest {
        val service = createService()
        // Token
        mockServer.server.enqueue(
            jsonResponse(TestFixtures.MockResponses.baiduTokenSuccess())
        )
        mockServer.server.enqueue(
            jsonResponse(TestFixtures.MockResponses.baiduChatSuccess("R1"))
        )
        service.chat("M1")

        mockServer.server.enqueue(
            jsonResponse(TestFixtures.MockResponses.baiduChatSuccess("R2"))
        )
        service.chat("M2")

        mockServer.server.takeRequest() // token
        mockServer.server.takeRequest() // first chat
        val second = mockServer.server.takeRequest()
        val body = JSONObject(second.body.readUtf8())
        val messages = body.getJSONArray("messages")
        // history (M1 user, R1 assistant) + current M2 = 3
        assertThat(messages.length()).isEqualTo(3)
    }

    @Test
    fun `clearHistory - resets conversation`() = runTest {
        val service = createService()
        mockServer.server.enqueue(
            jsonResponse(TestFixtures.MockResponses.baiduTokenSuccess())
        )
        mockServer.server.enqueue(
            jsonResponse(TestFixtures.MockResponses.baiduChatSuccess("R"))
        )
        service.chat("M")
        service.clearHistory()

        mockServer.server.enqueue(
            jsonResponse(TestFixtures.MockResponses.baiduChatSuccess("R2"))
        )
        service.chat("M2")

        mockServer.server.takeRequest() // token
        mockServer.server.takeRequest() // first chat
        val request = mockServer.server.takeRequest()
        val body = JSONObject(request.body.readUtf8())
        val messages = body.getJSONArray("messages")
        // Only current message, no history
        assertThat(messages.length()).isEqualTo(1)
    }

    // ==================== AnalyzeImage Tests ====================

    @Test
    fun `analyzeImage - returns not supported message`() = runTest {
        val service = createService()

        val result = service.analyzeImage(TestFixtures.createTestJpeg())

        assertThat(result).contains("separate API")
    }

    // ==================== TestConnection ====================

    @Test
    fun `testConnection - success when token obtained`() = runTest {
        val service = createService()
        mockServer.server.enqueue(
            jsonResponse(TestFixtures.MockResponses.baiduTokenSuccess())
        )

        val result = service.testConnection()

        assertThat(result.isSuccess).isTrue()
        assertThat(result.getOrNull()).contains("Connected")
    }

    @Test
    fun `testConnection - failure when token cannot be obtained`() = runTest {
        val service = createService()
        mockServer.server.enqueue(
            jsonResponse("{}", 401)
        )

        val result = service.testConnection()

        assertThat(result.isFailure).isTrue()
    }

    // ==================== Provider & Constants ====================

    @Test
    fun `provider returns BAIDU`() {
        val service = createService()
        assertThat(service.provider).isEqualTo(com.example.rokidphone.data.AiProvider.BAIDU)
    }

    @Test
    fun `default URLs match expected Baidu endpoints`() {
        assertThat(BaiduService.DEFAULT_TOKEN_URL)
            .isEqualTo("https://aip.baidubce.com/oauth/2.0/token")
        assertThat(BaiduService.DEFAULT_BASE_CHAT_URL)
            .isEqualTo("https://aip.baidubce.com/rpc/2.0/ai_custom/v1/wenxinworkshop/chat")
    }
}
