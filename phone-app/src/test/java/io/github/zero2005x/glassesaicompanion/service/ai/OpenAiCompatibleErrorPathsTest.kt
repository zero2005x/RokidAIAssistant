package io.github.zero2005x.glassesaicompanion.service.ai

import io.github.zero2005x.glassesaicompanion.data.AiProvider
import io.github.zero2005x.glassesaicompanion.testutil.MockWebServerRule
import io.github.zero2005x.glassesaicompanion.testutil.TestFixtures
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import okhttp3.Headers.Companion.headersOf
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Failures that must be reported through [OpenAiCompatibleService.lastChatError] and never be
 * mistaken for an answer: empty replies, unreadable bodies and rejected images.
 */
@RunWith(RobolectricTestRunner::class)
class OpenAiCompatibleErrorPathsTest {
    @get:Rule val mockServer = MockWebServerRule()

    private fun json(body: String, code: Int = 200) =
        MockResponse(code = code, body = body, headers = headersOf("Content-Type", "application/json"))

    private fun chatCompletions(model: String = "gpt-5.1") =
        OpenAiCompatibleService("sk-test", mockServer.baseUrl, model, providerType = AiProvider.OPENAI)

    private fun responses(model: String = "gpt-6-luna") = OpenAiCompatibleService(
        "sk-test", mockServer.baseUrl, model, providerType = AiProvider.OPENAI, useResponsesApi = true
    )

    private val emptyResponses = """{"status":"completed","output":[]}"""
    private val jpeg = TestFixtures.createTestJpeg()

    // ==================== Chat ====================

    @Test
    fun anUnreadableChatCompletionIsAnErrorNotAnAnswer() = runBlocking {
        mockServer.server.enqueue(json("this is not json"))
        val service = chatCompletions()

        val answer = service.chat("hello")

        assertThat(answer).startsWith("Sorry, an error occurred")
        assertThat(service.lastChatError).isNotNull()
    }

    @Test
    fun anEmptyResponsesReplyIsReportedAsEmpty() = runBlocking {
        mockServer.server.enqueue(json(emptyResponses))
        val service = responses()

        service.chat("hello")

        assertThat(service.lastChatError).isEqualTo("empty_response")
    }

    @Test
    fun aRejectedResponsesRequestKeepsTheProviderMessage() = runBlocking {
        mockServer.server.enqueue(json("""{"error":{"message":"Model overloaded"}}""", code = 503))
        val service = responses()

        val answer = service.chat("hello")

        assertThat(service.lastChatError).isNotNull()
        assertThat(service.lastChatError).contains("503")
        assertThat(answer).isEqualTo(service.lastChatError)
    }

    @Test
    fun anUnreadableResponsesBodyIsAnErrorNotAnAnswer() = runBlocking {
        mockServer.server.enqueue(json("<html>gateway</html>"))
        val service = responses()

        val answer = service.chat("hello")

        assertThat(answer).startsWith("Sorry, an error occurred")
        assertThat(service.lastChatError).isNotNull()
    }

    // ==================== Image analysis ====================

    @Test
    fun dataThatIsNotAnImageIsRefusedBeforeAnythingIsUploaded() = runBlocking {
        val service = chatCompletions()

        val answer = service.analyzeImage(ByteArray(16), "describe")

        assertThat(answer).startsWith("Image analysis error:")
        assertThat(service.lastChatError).isNotNull()
        assertThat(mockServer.server.requestCount).isEqualTo(0)
    }

    @Test
    fun anUnreadableChatCompletionImageReplyIsAnError() = runBlocking {
        mockServer.server.enqueue(json("garbage"))
        val service = chatCompletions()

        val answer = service.analyzeImage(jpeg, "describe")

        assertThat(answer).startsWith("Image analysis error:")
        assertThat(service.lastChatError).isNotNull()
    }

    @Test
    fun aChatCompletionImageReplyWithoutTextIsReportedAsEmpty() = runBlocking {
        val service = chatCompletions()

        mockServer.server.enqueue(json("""{"choices":[{"message":{"content":""}}]}"""))
        assertThat(service.analyzeImage(jpeg, "describe")).isEqualTo("Unable to analyze image.")
        assertThat(service.lastChatError).isEqualTo("empty_response")

        mockServer.server.enqueue(json("""{"choices":[{"message":{"content":"a red bicycle"}}]}"""))
        assertThat(service.analyzeImage(jpeg, "describe")).isEqualTo("a red bicycle")
        assertThat(service.lastChatError).isNull()
    }

    @Test
    fun aResponsesImageReplyWithoutTextIsReportedAsEmpty() = runBlocking {
        mockServer.server.enqueue(json(emptyResponses))
        val service = responses()

        val answer = service.analyzeImage(jpeg, "describe")

        assertThat(answer).isEqualTo("Unable to analyze image.")
        assertThat(service.lastChatError).isEqualTo("empty_response")
    }

    @Test
    fun anUnreadableResponsesImageReplyIsAnError() = runBlocking {
        mockServer.server.enqueue(json("garbage"))
        val service = responses()

        val answer = service.analyzeImage(jpeg, "describe")

        assertThat(answer).startsWith("Image analysis error:")
        assertThat(service.lastChatError).isNotNull()
    }
}
