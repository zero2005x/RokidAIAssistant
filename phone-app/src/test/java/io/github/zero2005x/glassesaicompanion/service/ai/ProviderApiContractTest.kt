package io.github.zero2005x.glassesaicompanion.service.ai

import io.github.zero2005x.glassesaicompanion.ai.provider.AnythingLLMProvider
import io.github.zero2005x.glassesaicompanion.ai.provider.ChatMessage
import io.github.zero2005x.glassesaicompanion.ai.provider.MessageRole
import io.github.zero2005x.glassesaicompanion.ai.provider.ProviderSetting
import io.github.zero2005x.glassesaicompanion.data.AiProvider
import io.github.zero2005x.glassesaicompanion.testutil.MockWebServerRule
import io.github.zero2005x.glassesaicompanion.testutil.TestFixtures
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import okhttp3.Headers.Companion.headersOf
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Verify actual wire requests against documented provider restrictions. */
@RunWith(RobolectricTestRunner::class)
class ProviderApiContractTest {
    @get:Rule val mockServer = MockWebServerRule()

    private fun service(provider: AiProvider, model: String, responses: Boolean = false) =
        OpenAiCompatibleService(
            apiKey = "test-key", baseUrl = mockServer.baseUrl, modelId = model,
            providerType = provider, useResponsesApi = responses,
            temperature = 1.5f, frequencyPenalty = 0.5f, presencePenalty = 0.5f
        )

    private fun success() = MockResponse(
        body = TestFixtures.MockResponses.openAiChatSuccess("OK"),
        headers = headersOf("Content-Type", "application/json")
    )

    @Test fun `compatible providers use bearer JSON and their own token fields`() = runTest {
        val providers = mapOf(
            AiProvider.OPENAI to "gpt-4o", AiProvider.DEEPSEEK to "deepseek-chat",
            AiProvider.GROQ to "llama-3.3-70b-versatile", AiProvider.XAI to "grok-4",
            AiProvider.ALIBABA to "qwen3-32b", AiProvider.ZHIPU to "glm-5.1",
            AiProvider.BAIDU to "ernie-5.1", AiProvider.PERPLEXITY to "sonar",
            AiProvider.MOONSHOT to "kimi-k2.5", AiProvider.MISTRAL to "mistral-small-latest",
            AiProvider.CUSTOM to "local-model"
        )
        for ((provider, model) in providers) {
            mockServer.server.enqueue(success())
            assertThat(service(provider, model).chat("hello")).isEqualTo("OK")
            val request = mockServer.server.takeRequest()
            val body = JSONObject(request.body.readUtf8())
            assertThat(request.path).isEqualTo("/chat/completions")
            assertThat(request.headers["Authorization"]).isEqualTo("Bearer test-key")
            assertThat(body.getString("model")).isEqualTo(model)
            assertThat(body.has("stream_options")).isFalse()
            val tokens = if (provider == AiProvider.GROQ) "max_completion_tokens" else "max_tokens"
            assertThat(body.has(tokens)).isTrue()
            if (provider == AiProvider.MOONSHOT) {
                assertThat(body.has("temperature")).isFalse()
                assertThat(body.has("top_p")).isFalse()
            }
            if (provider in setOf(AiProvider.GROQ, AiProvider.ZHIPU, AiProvider.MOONSHOT, AiProvider.XAI, AiProvider.PERPLEXITY)) {
                assertThat(body.has("frequency_penalty")).isFalse()
                assertThat(body.has("presence_penalty")).isFalse()
            }
            if (provider == AiProvider.ZHIPU) assertThat(body.getDouble("temperature")).isEqualTo(1.0)
            if (provider == AiProvider.ALIBABA) assertThat(body.getBoolean("enable_thinking")).isFalse()
        }
    }

    @Test fun `streaming usage is requested only from documented compatible providers`() = runTest {
        for (provider in listOf(AiProvider.GROQ, AiProvider.DEEPSEEK, AiProvider.ALIBABA, AiProvider.ZHIPU, AiProvider.MOONSHOT, AiProvider.CUSTOM)) {
            mockServer.server.enqueue(MockResponse(
                body = "data: {\"choices\":[{\"delta\":{\"content\":\"OK\"}}]}\n\ndata: [DONE]\n\n",
                headers = headersOf("Content-Type", "text/event-stream")
            ))
            service(provider, "test-model").streamChat("hi").toList()
            val body = JSONObject(mockServer.server.takeRequest().body.readUtf8())
            val supported = provider in setOf(AiProvider.GROQ, AiProvider.DEEPSEEK, AiProvider.ALIBABA)
            assertThat(body.has("stream_options")).isEqualTo(supported)
            if (supported) assertThat(body.getJSONObject("stream_options").getBoolean("include_usage")).isTrue()
        }
    }

    @Test fun `Claude sampling is omitted on newer models and bounded on supported models`() = runTest {
        for (model in listOf("claude-opus-4-7", "claude-opus-4-8", "claude-opus-5", "claude-sonnet-4-6", "claude-sonnet-4-20250514")) {
            mockServer.server.enqueue(MockResponse(body = """{"content":[{"type":"text","text":"OK"}]}"""))
            AnthropicService(apiKey = "test-key", modelId = model, temperature = 1.5f, baseUrl = mockServer.baseUrlNoSlash).chat("hi")
            val request = mockServer.server.takeRequest()
            val body = JSONObject(request.body.readUtf8())
            assertThat(request.headers["x-api-key"]).isEqualTo("test-key")
            assertThat(request.headers["anthropic-version"]).isEqualTo("2023-06-01")
            val legacy = model.startsWith("claude-sonnet")
            assertThat(body.has("temperature")).isEqualTo(legacy)
            if (legacy) assertThat(body.getDouble("temperature")).isEqualTo(1.0)
        }
    }

    @Test fun `incomplete Responses stream reports error without completion or history`() = runTest {
        val adapter = service(AiProvider.OPENAI, "gpt-5.6", responses = true)
        mockServer.server.enqueue(MockResponse(
            body = "data: {\"type\":\"response.output_text.delta\",\"delta\":\"partial\"}\n\n" +
                "data: {\"type\":\"response.incomplete\",\"response\":{\"incomplete_details\":{\"reason\":\"max_output_tokens\"}}}\n\n",
            headers = headersOf("Content-Type", "text/event-stream")
        ))
        val events = adapter.streamChat("failed question").toList()
        assertThat(events.filterIsInstance<AiStreamEvent.Error>()).hasSize(1)
        assertThat(events.filterIsInstance<AiStreamEvent.Completed>()).isEmpty()
        mockServer.server.takeRequest()
        mockServer.server.enqueue(MockResponse(body = """{"output":[{"type":"message","content":[{"type":"output_text","text":"OK"}]}]}"""))
        adapter.chat("next question")
        val body = mockServer.server.takeRequest().body.readUtf8()
        assertThat(body).doesNotContain("failed question")
        assertThat(body).doesNotContain("partial")
    }

    @Test fun `connection probe uses Responses protocol when models is unavailable`() = runTest {
        mockServer.server.enqueue(MockResponse(code = 404))
        mockServer.server.enqueue(MockResponse(body = "{}"))
        assertThat(service(AiProvider.OPENAI, "gpt-5.6", responses = true).testConnection().isSuccess).isTrue()
        mockServer.server.takeRequest()
        val request = mockServer.server.takeRequest()
        assertThat(request.path).isEqualTo("/responses")
        val body = JSONObject(request.body.readUtf8())
        assertThat(body.has("input")).isTrue()
        assertThat(body.has("messages")).isFalse()
        assertThat(body.has("reasoning")).isFalse()
    }

    @Test fun `AnythingLLM workspace slug is one encoded URL path segment`() = runTest {
        mockServer.server.enqueue(MockResponse(body = """{"textResponse":"OK"}"""))
        AnythingLLMProvider().generateText(
            setting = ProviderSetting.AnythingLLM(
                serverUrl = mockServer.baseUrlNoSlash, apiKey = "test-key", workspaceSlug = "team docs/a+b"
            ), messages = listOf(ChatMessage(MessageRole.USER, "hi"))
        )
        assertThat(mockServer.server.takeRequest().path)
            .isEqualTo("/api/v1/workspace/team%20docs%2Fa+b/chat")
    }

    @Test fun `JSON null text must not become spoken null`() {
        val blocks = JSONArray("""[{"type":"text","text":null},{"type":"text","text":"answer"}]""")
        assertThat(ChatContentParser.extractText(blocks)).isEqualTo("answer")
    }

    @Test fun `reasoning effort respects model generations`() {
        assertThat(ProviderRequestPolicies.openAiReasoningEffort("o3", "minimal")).isNull()
        assertThat(ProviderRequestPolicies.openAiReasoningEffort("gpt-5.2", "minimal")).isNull()
        assertThat(ProviderRequestPolicies.openAiReasoningEffort("gpt-5.1", "xhigh")).isNull()
        assertThat(ProviderRequestPolicies.openAiReasoningEffort("gpt-5", "none")).isNull()
        assertThat(ProviderRequestPolicies.openAiReasoningEffort("gpt-5", "minimal")).isEqualTo("minimal")
        assertThat(ProviderRequestPolicies.openAiReasoningEffort("gpt-5.2", "none")).isEqualTo("none")
        assertThat(ProviderRequestPolicies.openAiReasoningEffort("gpt-5.2-pro", "none")).isNull()
    }
}