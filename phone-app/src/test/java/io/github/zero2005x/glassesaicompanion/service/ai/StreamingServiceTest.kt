package io.github.zero2005x.glassesaicompanion.service.ai

import io.github.zero2005x.glassesaicompanion.ai.catalog.ProviderErrorKind
import io.github.zero2005x.glassesaicompanion.data.AiProvider
import io.github.zero2005x.glassesaicompanion.testutil.MockWebServerRule
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import okhttp3.Headers.Companion.headersOf
import org.json.JSONObject
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Streaming (SSE) tests for the unified [AiStreamEvent] interface across
 * OpenAI-compatible, Anthropic and Gemini services.
 */
@RunWith(RobolectricTestRunner::class)
class StreamingServiceTest {

    @get:Rule
    val mockServer = MockWebServerRule()

    private fun sseResponse(body: String, code: Int = 200) = MockResponse(
        code = code,
        body = body,
        headers = headersOf("Content-Type", "text/event-stream")
    )

    private fun openAiService(
        modelId: String = "llama-3.3-70b-versatile",
        provider: AiProvider = AiProvider.GROQ
    ) = OpenAiCompatibleService(
        apiKey = "test-key",
        baseUrl = mockServer.baseUrl,
        modelId = modelId,
        providerType = provider
    )

    // ==================== OpenAI Chat Completions streaming ====================

    @Test
    fun `stream - multiple text deltas then usage then completed`() = runTest {
        val sse = """
            data: {"choices": [{"index": 0, "delta": {"role": "assistant", "content": "Hello"}}]}

            data: {"choices": [{"index": 0, "delta": {"content": " world"}}]}

            data: {"choices": [], "usage": {"prompt_tokens": 5, "completion_tokens": 2, "total_tokens": 7}}

            data: [DONE]

        """.trimIndent()
        mockServer.server.enqueue(sseResponse(sse))

        val events = openAiService().streamChat("hi").toList()

        val deltas = events.filterIsInstance<AiStreamEvent.TextDelta>()
        assertThat(deltas.map { it.text }).containsExactly("Hello", " world").inOrder()
        val completed = events.last() as AiStreamEvent.Completed
        assertThat(completed.fullText).isEqualTo("Hello world")
        assertThat(completed.usage?.inputTokens).isEqualTo(5)
        assertThat(completed.usage?.outputTokens).isEqualTo(2)
    }

    @Test
    fun `stream - request body enables stream and usage`() = runTest {
        mockServer.server.enqueue(sseResponse("data: [DONE]\n\n"))

        openAiService().streamChat("hi").toList()

        val body = JSONObject(mockServer.server.takeRequest().body.readUtf8())
        assertThat(body.getBoolean("stream")).isTrue()
        assertThat(body.getJSONObject("stream_options").getBoolean("include_usage")).isTrue()
    }

    @Test
    fun `stream - tool call delta is emitted`() = runTest {
        val sse = """
            data: {"choices": [{"index": 0, "delta": {"tool_calls": [{"id": "call_1", "type": "function", "function": {"name": "get_weather", "arguments": "{\"city\":"}}]}}]}

            data: {"choices": [{"index": 0, "delta": {"tool_calls": [{"function": {"arguments": "\"Taipei\"}"}}]}}]}

            data: [DONE]

        """.trimIndent()
        mockServer.server.enqueue(sseResponse(sse))

        val events = openAiService().streamChat("hi").toList()

        val toolCalls = events.filterIsInstance<AiStreamEvent.ToolCallDelta>()
        assertThat(toolCalls).hasSize(2)
        assertThat(toolCalls[0].index).isEqualTo(0)
        assertThat(toolCalls[0].name).isEqualTo("get_weather")
        assertThat(toolCalls[0].argumentsDelta).isEqualTo("{\"city\":")
        assertThat(toolCalls[1].index).isEqualTo(0)
        assertThat(toolCalls[1].argumentsDelta).isEqualTo("\"Taipei\"}")
    }

    @Test
    fun `stream - malformed event is skipped without killing the stream`() = runTest {
        val sse = """
            data: { this is not json

            data: {"choices": [{"index": 0, "delta": {"content": "still alive"}}]}

            data: [DONE]

        """.trimIndent()
        mockServer.server.enqueue(sseResponse(sse))

        val events = openAiService().streamChat("hi").toList()

        assertThat(events.filterIsInstance<AiStreamEvent.Error>()).isEmpty()
        assertThat(events.filterIsInstance<AiStreamEvent.TextDelta>().map { it.text })
            .containsExactly("still alive")
        assertThat(events.last()).isInstanceOf(AiStreamEvent.Completed::class.java)
    }

    @Test
    fun `stream - http 500 yields classified error event`() = runTest {
        mockServer.server.enqueue(
            MockResponse(code = 500, body = """{"error": {"message": "server exploded"}}""")
        )

        val events = openAiService().streamChat("hi").toList()

        val error = events.filterIsInstance<AiStreamEvent.Error>().single()
        assertThat(error.kind).isEqualTo(ProviderErrorKind.SERVICE_UNAVAILABLE)
        assertThat(error.httpStatus).isEqualTo(500)
        assertThat(events.filterIsInstance<AiStreamEvent.Completed>()).isEmpty()
    }

    @Test
    fun `stream - http 429 yields rate limit error with no retry`() = runTest {
        mockServer.server.enqueue(
            MockResponse(
                code = 429,
                body = """{"error": {"message": "slow down"}}""",
                headers = headersOf("Retry-After", "3")
            )
        )

        val events = openAiService().streamChat("hi").toList()

        val error = events.filterIsInstance<AiStreamEvent.Error>().single()
        assertThat(error.kind).isEqualTo(ProviderErrorKind.RATE_LIMIT)
        assertThat(mockServer.server.requestCount).isEqualTo(1)
    }

    @Test
    fun `stream - collector cancellation stops the stream early`() = runTest {
        val sse = """
            data: {"choices": [{"index": 0, "delta": {"content": "one"}}]}

            data: {"choices": [{"index": 0, "delta": {"content": "two"}}]}

            data: {"choices": [{"index": 0, "delta": {"content": "three"}}]}

            data: [DONE]

        """.trimIndent()
        mockServer.server.enqueue(sseResponse(sse))

        val events = openAiService().streamChat("hi").take(2).toList()

        assertThat(events).hasSize(2)
        assertThat(events.filterIsInstance<AiStreamEvent.Completed>()).isEmpty()
    }

    @Test
    fun `stream - deepseek reasoning_content is never surfaced as text`() = runTest {
        val sse = """
            data: {"choices": [{"index": 0, "delta": {"reasoning_content": "thinking hard"}}]}

            data: {"choices": [{"index": 0, "delta": {"content": "final answer"}}]}

            data: [DONE]

        """.trimIndent()
        mockServer.server.enqueue(sseResponse(sse))

        val events = openAiService(modelId = "deepseek-v4-pro", provider = AiProvider.DEEPSEEK)
            .streamChat("hi").toList()

        assertThat(events.filterIsInstance<AiStreamEvent.Thinking>()).isNotEmpty()
        val completed = events.last() as AiStreamEvent.Completed
        assertThat(completed.fullText).isEqualTo("final answer")
        assertThat(completed.fullText).doesNotContain("thinking hard")
    }

    @Test
    fun `stream - perplexity citations are emitted as typed events`() = runTest {
        val sse = """
            data: {"choices": [{"index": 0, "delta": {"content": "answer"}}]}

            data: {"choices": [{"index": 0, "delta": {}}], "citations": ["https://example.com/a", "https://example.com/b"]}

            data: [DONE]

        """.trimIndent()
        mockServer.server.enqueue(sseResponse(sse))

        val events = openAiService(modelId = "sonar-pro", provider = AiProvider.PERPLEXITY)
            .streamChat("hi").toList()

        val citations = events.filterIsInstance<AiStreamEvent.Citation>()
        assertThat(citations.map { it.url }).containsExactly(
            "https://example.com/a", "https://example.com/b"
        )
    }

    // ==================== Anthropic streaming ====================

    @Test
    fun `anthropic stream - text deltas, thinking marker, usage and completion`() = runTest {
        val sse = """
            event: message_start
            data: {"type": "message_start", "message": {"usage": {"input_tokens": 12}}}

            event: content_block_delta
            data: {"type": "content_block_delta", "index": 0, "delta": {"type": "thinking_delta", "thinking": "secret chain of thought"}}

            event: content_block_delta
            data: {"type": "content_block_delta", "index": 1, "delta": {"type": "text_delta", "text": "Hello"}}

            event: content_block_delta
            data: {"type": "content_block_delta", "index": 1, "delta": {"type": "text_delta", "text": " from Claude"}}

            event: message_delta
            data: {"type": "message_delta", "delta": {"stop_reason": "end_turn"}, "usage": {"output_tokens": 4}}

            event: message_stop
            data: {"type": "message_stop"}

        """.trimIndent()
        mockServer.server.enqueue(sseResponse(sse))

        val service = AnthropicService(
            apiKey = "test-key",
            modelId = "claude-sonnet-5",
            baseUrl = mockServer.baseUrlNoSlash
        )
        val events = service.streamChat("hi").toList()

        val deltas = events.filterIsInstance<AiStreamEvent.TextDelta>()
        assertThat(deltas.map { it.text }).containsExactly("Hello", " from Claude").inOrder()
        // Thinking is surfaced as a state marker only, never as text.
        assertThat(events.filterIsInstance<AiStreamEvent.Thinking>()).isNotEmpty()
        val completed = events.last() as AiStreamEvent.Completed
        assertThat(completed.fullText).isEqualTo("Hello from Claude")
        assertThat(completed.fullText).doesNotContain("secret chain of thought")
        assertThat(completed.usage?.inputTokens).isEqualTo(12)
        assertThat(completed.usage?.outputTokens).isEqualTo(4)
    }

    @Test
    fun `anthropic stream - request sets stream true with anthropic headers`() = runTest {
        mockServer.server.enqueue(sseResponse("data: {\"type\": \"message_stop\"}\n\n"))

        val service = AnthropicService(
            apiKey = "sk-ant-test",
            modelId = "claude-sonnet-5",
            baseUrl = mockServer.baseUrlNoSlash
        )
        service.streamChat("hi").toList()

        val request = mockServer.server.takeRequest()
        assertThat(request.headers["x-api-key"]).isEqualTo("sk-ant-test")
        assertThat(request.headers["anthropic-version"]).isNotNull()
        val body = JSONObject(request.body.readUtf8())
        assertThat(body.getBoolean("stream")).isTrue()
    }

    // ==================== Gemini streaming ====================

    @Test
    fun `gemini stream - text parts, usage metadata and completion`() = runTest {
        val sse = """
            data: {"candidates": [{"content": {"parts": [{"text": "Hello"}], "role": "model"}}]}

            data: {"candidates": [{"content": {"parts": [{"text": " Gemini"}], "role": "model"}}], "usageMetadata": {"promptTokenCount": 8, "candidatesTokenCount": 3}}

        """.trimIndent()
        mockServer.server.enqueue(sseResponse(sse))

        val service = GeminiService(
            apiKey = "test-key",
            modelId = "gemini-3.6-flash",
            baseUrl = mockServer.baseUrlNoSlash
        )
        val events = service.streamChat("hi").toList()

        assertThat(events.filterIsInstance<AiStreamEvent.TextDelta>().map { it.text })
            .containsExactly("Hello", " Gemini").inOrder()
        val completed = events.last() as AiStreamEvent.Completed
        assertThat(completed.fullText).isEqualTo("Hello Gemini")
        assertThat(completed.usage?.inputTokens).isEqualTo(8)

        val request = mockServer.server.takeRequest()
        assertThat(request.path).contains("gemini-3.6-flash:streamGenerateContent")
        assertThat(request.path).contains("alt=sse")
    }

    @Test
    fun `gemini stream - blank api key yields invalid key error without request`() = runTest {
        val service = GeminiService(
            apiKey = "",
            modelId = "gemini-3.6-flash",
            baseUrl = mockServer.baseUrlNoSlash
        )
        val events = service.streamChat("hi").toList()

        val error = events.filterIsInstance<AiStreamEvent.Error>().single()
        assertThat(error.kind).isEqualTo(ProviderErrorKind.INVALID_API_KEY)
        assertThat(mockServer.server.requestCount).isEqualTo(0)
    }

    // ==================== Default wrapper ====================

    @Test
    fun `default streamChat wrapper emits full text then completed`() = runTest {
        // BaiduService (legacy) does not override streamChat → interface default wrapper.
        mockServer.server.enqueue(
            MockResponse(
                code = 200,
                body = """{"access_token": "token", "expires_in": 86400}""",
                headers = headersOf("Content-Type", "application/json")
            )
        )
        mockServer.server.enqueue(
            MockResponse(
                code = 200,
                body = """{"result": "baidu answer"}""",
                headers = headersOf("Content-Type", "application/json")
            )
        )

        val service = BaiduService(
            apiKey = "key",
            secretKey = "secret",
            modelId = "ernie-4.0-8k",
            tokenUrl = mockServer.baseUrlNoSlash + "/oauth",
            baseChatUrl = mockServer.baseUrlNoSlash + "/chat"
        )
        val events = service.streamChat("hi").toList()

        assertThat(events.first()).isEqualTo(AiStreamEvent.TextDelta("baidu answer"))
        assertThat(events.last()).isInstanceOf(AiStreamEvent.Completed::class.java)
    }
}
