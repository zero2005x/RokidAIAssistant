package io.github.zero2005x.glassesaicompanion.service.ai

import io.github.zero2005x.glassesaicompanion.data.AiProvider
import io.github.zero2005x.glassesaicompanion.testutil.MockWebServerRule
import io.github.zero2005x.glassesaicompanion.testutil.TestFixtures
import com.google.common.truth.Truth.assertThat
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
 * OpenAI Responses API tests: request shapes, image input format,
 * SSE events and Chat Completions fallback. The two APIs' content
 * formats must never be mixed.
 */
@RunWith(RobolectricTestRunner::class)
class OpenAiResponsesApiTest {

    @get:Rule
    val mockServer = MockWebServerRule()

    private fun jsonResponse(body: String, code: Int = 200) = MockResponse(
        code = code,
        body = body,
        headers = headersOf("Content-Type", "application/json")
    )

    private fun responsesService(modelId: String = "gpt-5.6") = OpenAiCompatibleService(
        apiKey = "sk-test",
        baseUrl = mockServer.baseUrl,
        modelId = modelId,
        providerType = AiProvider.OPENAI,
        useResponsesApi = true
    )

    private fun responsesSuccess(text: String) = """
        {
          "id": "resp_123",
          "status": "completed",
          "output": [
            {
              "type": "message",
              "role": "assistant",
              "content": [{"type": "output_text", "text": "$text"}]
            }
          ],
          "usage": {"input_tokens": 10, "output_tokens": 4, "total_tokens": 14}
        }
    """.trimIndent()

    @Test
    fun `responses - text request uses input items with typed content parts`() = runTest {
        mockServer.server.enqueue(jsonResponse(responsesSuccess("hi there")))

        val result = responsesService().chat("hello")

        assertThat(result).isEqualTo("hi there")
        val request = mockServer.server.takeRequest()
        assertThat(request.path).isEqualTo("/responses")
        val body = JSONObject(request.body.readUtf8())
        assertThat(body.getString("model")).isEqualTo("gpt-5.6")
        assertThat(body.has("messages")).isFalse() // Responses uses input, not messages
        val input = body.getJSONArray("input")
        val system = input.getJSONObject(0)
        assertThat(system.getString("role")).isEqualTo("system")
        val part = system.getJSONArray("content").getJSONObject(0)
        assertThat(part.getString("type")).isEqualTo("input_text")
        val user = input.getJSONObject(input.length() - 1)
        assertThat(user.getString("role")).isEqualTo("user")
        // Responses API token limit field
        assertThat(body.has("max_output_tokens")).isTrue()
        assertThat(body.has("max_completion_tokens")).isFalse()
        assertThat(body.has("max_tokens")).isFalse()
    }

    @Test
    fun `responses - gpt-5_6 leaves unspecified reasoning effort to server`() = runTest {
        mockServer.server.enqueue(jsonResponse(responsesSuccess("ok")))

        responsesService().chat("hello")

        val body = JSONObject(mockServer.server.takeRequest().body.readUtf8())
        assertThat(body.has("reasoning")).isFalse()
        assertThat(body.has("reasoning_effort")).isFalse()
    }

    @Test
    fun `responses - image request uses input_image part not image_url object`() = runTest {
        mockServer.server.enqueue(jsonResponse(responsesSuccess("a cat")))

        val result = responsesService().analyzeImage(TestFixtures.createTestJpeg(), "what is this?")

        assertThat(result).isEqualTo("a cat")
        val body = JSONObject(mockServer.server.takeRequest().body.readUtf8())
        val content = body.getJSONArray("input").getJSONObject(0).getJSONArray("content")
        assertThat(content.getJSONObject(0).getString("type")).isEqualTo("input_text")
        val imagePart = content.getJSONObject(1)
        assertThat(imagePart.getString("type")).isEqualTo("input_image")
        assertThat(imagePart.getString("image_url")).startsWith("data:image/jpeg;base64,")
        // Responses format: image_url is a plain data-URL string, NOT the
        // Chat Completions nested {"url": ...} object.
        assertThat(imagePart.get("image_url")).isInstanceOf(String::class.java)
    }

    @Test
    fun `responses - 404 falls back to chat completions`() = runTest {
        mockServer.server.enqueue(MockResponse(code = 404, body = """{"error": {"message": "unknown endpoint"}}"""))
        mockServer.server.enqueue(jsonResponse(TestFixtures.MockResponses.openAiChatSuccess("fallback ok")))

        val result = responsesService().chat("hello")

        assertThat(result).isEqualTo("fallback ok")
        mockServer.server.takeRequest() // /responses
        val second = mockServer.server.takeRequest()
        assertThat(second.path).isEqualTo("/chat/completions")
        val body = JSONObject(second.body.readUtf8())
        assertThat(body.has("messages")).isTrue()
    }

    @Test
    fun `responses - streaming emits text deltas and completed`() = runTest {
        val sse = """
            data: {"type": "response.output_text.delta", "delta": "Hello"}

            data: {"type": "response.output_text.delta", "delta": " Responses"}

            data: {"type": "response.completed", "response": {"usage": {"input_tokens": 7, "output_tokens": 3}}}

        """.trimIndent()
        mockServer.server.enqueue(
            MockResponse(code = 200, body = sse, headers = headersOf("Content-Type", "text/event-stream"))
        )

        val events = responsesService().streamChat("hi").toList()

        assertThat(events.filterIsInstance<AiStreamEvent.TextDelta>().map { it.text })
            .containsExactly("Hello", " Responses").inOrder()
        val completed = events.last() as AiStreamEvent.Completed
        assertThat(completed.fullText).isEqualTo("Hello Responses")
        assertThat(completed.usage?.inputTokens).isEqualTo(7)
        assertThat(completed.usage?.outputTokens).isEqualTo(3)
    }

    @Test
    fun `responses - streaming failure event yields classified error`() = runTest {
        val sse = """
            data: {"type": "response.failed", "response": {"error": {"message": "model exploded"}}}

        """.trimIndent()
        mockServer.server.enqueue(
            MockResponse(code = 200, body = sse, headers = headersOf("Content-Type", "text/event-stream"))
        )

        val events = responsesService().streamChat("hi").toList()

        assertThat(events.filterIsInstance<AiStreamEvent.Error>()).isNotEmpty()
    }

    @Test
    fun `factory - gpt-5_6 uses responses while gpt-5_4 stays on chat completions`() = runTest {
        // The request policy marks the verified Responses-first families.
        assertThat(ProviderRequestPolicies.openAiPrefersResponses("gpt-5.6")).isTrue()
        assertThat(ProviderRequestPolicies.openAiPrefersResponses("gpt-5.6-terra")).isTrue()
        assertThat(ProviderRequestPolicies.openAiPrefersResponses("gpt-5.4")).isFalse()
        assertThat(ProviderRequestPolicies.openAiPrefersResponses("gpt-4o")).isFalse()
    }
}
