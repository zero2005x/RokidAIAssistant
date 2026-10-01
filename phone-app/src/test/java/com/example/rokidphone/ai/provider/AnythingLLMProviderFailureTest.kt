package com.example.rokidphone.ai.provider

import com.example.rokidphone.testutil.MockWebServerRule
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import okhttp3.Headers.Companion.headersOf
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * What [AnythingLLMProvider] does when the workspace does not answer usefully, and
 * how streamText relays either outcome. The sibling AnythingLLMProviderTest covers
 * the successful relay and source citations.
 */
@RunWith(RobolectricTestRunner::class)
class AnythingLLMProviderFailureTest {

    @get:Rule
    val mockServer = MockWebServerRule()

    private val provider = AnythingLLMProvider()

    private fun setting() = ProviderSetting.AnythingLLM(
        serverUrl = mockServer.baseUrlNoSlash,
        apiKey = "test-api-key",
        workspaceSlug = "my-workspace"
    )

    private fun response(body: String, code: Int = 200) = MockResponse(
        code = code,
        body = body,
        headers = headersOf("Content-Type", "application/json")
    )

    private val question = listOf(ChatMessage(MessageRole.USER, "Hi"))

    private suspend fun generate(): GenerationResult =
        provider.generateText(setting = setting(), messages = question)

    private suspend fun generateError(): GenerationResult.Error {
        val result = generate()
        assertThat(result).isInstanceOf(GenerationResult.Error::class.java)
        return result as GenerationResult.Error
    }

    // ==================== Unusable answers ====================

    @Test
    fun `a workspace that answers with nothing is reported as empty`() = runTest {
        mockServer.server.enqueue(response("""{"textResponse":"","response":""}"""))

        val error = generateError()

        assertThat(error.code).isEqualTo("empty_response")
        assertThat(error.retryable).isFalse()
        assertThat(error.message).contains("empty response")
    }

    @Test
    fun `a reply with neither text field is reported as empty`() = runTest {
        mockServer.server.enqueue(response("{}"))

        assertThat(generateError().code).isEqualTo("empty_response")
    }

    @Test
    fun `a reply that is not json is reported as malformed`() = runTest {
        mockServer.server.enqueue(response("this is not json"))

        val error = generateError()

        assertThat(error.code).isEqualTo("invalid_response")
        assertThat(error.retryable).isFalse()
    }

    // ==================== HTTP failures ====================

    @Test
    fun `a client error is not worth retrying`() = runTest {
        mockServer.server.enqueue(response("""{"error":"bad workspace"}""", code = 404))

        val error = generateError()

        assertThat(error.code).isEqualTo("404")
        assertThat(error.retryable).isFalse()
        assertThat(error.message).contains("HTTP 404")
    }

    @Test
    fun `a server error is worth retrying`() = runTest {
        mockServer.server.enqueue(response("upstream is down", code = 503))

        val error = generateError()

        assertThat(error.code).isEqualTo("503")
        assertThat(error.retryable).isTrue()
    }

    @Test
    fun `an unreachable server is a retryable connection error`() = runTest {
        mockServer.server.close()

        val error = generateError()

        assertThat(error.code).isEqualTo("connection_error")
        assertThat(error.retryable).isTrue()
    }

    // ==================== Streaming relay ====================

    @Test
    fun `streaming emits one complete chunk for a successful answer`() = runTest {
        mockServer.server.enqueue(
            response("""{"textResponse":"A grounded answer.","sources":[]}""")
        )

        val chunks = provider.streamText(setting(), question, GenerationOptions()).toList()

        assertThat(chunks).hasSize(1)
        assertThat(chunks[0].text).isEqualTo("A grounded answer.")
        assertThat(chunks[0].isComplete).isTrue()
        assertThat(chunks[0].finishReason).isEqualTo(FinishReason.STOP)
        assertThat(chunks[0].error).isNull()
    }

    @Test
    fun `streaming reports a failure as a terminal error chunk`() = runTest {
        mockServer.server.enqueue(response("nope", code = 500))

        val chunks = provider.streamText(setting(), question, GenerationOptions()).toList()

        assertThat(chunks).hasSize(1)
        assertThat(chunks[0].text).isEmpty()
        assertThat(chunks[0].isComplete).isTrue()
        assertThat(chunks[0].finishReason).isEqualTo(FinishReason.ERROR)
        assertThat(chunks[0].error).contains("HTTP 500")
    }
}
