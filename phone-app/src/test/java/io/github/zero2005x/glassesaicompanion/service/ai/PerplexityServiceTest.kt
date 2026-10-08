package io.github.zero2005x.glassesaicompanion.service.ai

import io.github.zero2005x.glassesaicompanion.data.AiProvider
import io.github.zero2005x.glassesaicompanion.testutil.MockWebServerRule
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
 * Perplexity Sonar tests: citations / search results are preserved as typed
 * metadata and surfaced as a readable source list until the UI renders them.
 */
@RunWith(RobolectricTestRunner::class)
class PerplexityServiceTest {

    @get:Rule
    val mockServer = MockWebServerRule()

    private fun jsonResponse(body: String, code: Int = 200) = MockResponse(
        code = code,
        body = body,
        headers = headersOf("Content-Type", "application/json")
    )

    private fun createService(modelId: String = "sonar-pro") = PerplexityService(
        apiKey = "pplx-test",
        baseUrl = mockServer.baseUrl,
        modelId = modelId
    )

    @Test
    fun `chat - citations are appended as readable sources`() = runTest {
        val body = """
            {
              "choices": [{"index": 0, "message": {"role": "assistant", "content": "The sky is blue."}}],
              "citations": ["https://example.com/sky", "https://example.com/blue"]
            }
        """.trimIndent()
        mockServer.server.enqueue(jsonResponse(body))

        val result = createService().chat("why is the sky blue?")

        assertThat(result).startsWith("The sky is blue.")
        assertThat(result).contains("Sources:")
        assertThat(result).contains("https://example.com/sky")
        assertThat(result).contains("https://example.com/blue")
    }

    @Test
    fun `chat - search results with titles are preferred over plain citations`() = runTest {
        val body = """
            {
              "choices": [{"index": 0, "message": {"role": "assistant", "content": "Answer."}}],
              "citations": ["https://example.com/a"],
              "search_results": [
                {"title": "Great Article", "url": "https://example.com/a"},
                {"title": "Another One", "url": "https://example.com/b"}
              ]
            }
        """.trimIndent()
        mockServer.server.enqueue(jsonResponse(body))

        val result = createService().chat("query")

        assertThat(result).contains("Great Article — https://example.com/a")
        assertThat(result).contains("Another One — https://example.com/b")
    }

    @Test
    fun `chat - response without citations stays untouched`() = runTest {
        val body = """
            {"choices": [{"index": 0, "message": {"role": "assistant", "content": "Plain."}}]}
        """.trimIndent()
        mockServer.server.enqueue(jsonResponse(body))

        assertThat(createService().chat("query")).isEqualTo("Plain.")
    }

    @Test
    fun `chat - sonar request never includes penalty params`() = runTest {
        val service = PerplexityService(
            apiKey = "pplx-test",
            baseUrl = mockServer.baseUrl,
            modelId = "sonar",
            temperature = 0.5f
        )
        mockServer.server.enqueue(jsonResponse("""{"choices": [{"message": {"content": "ok"}}]}"""))

        service.chat("query")

        val body = JSONObject(mockServer.server.takeRequest().body.readUtf8())
        assertThat(body.has("frequency_penalty")).isFalse()
        assertThat(body.has("presence_penalty")).isFalse()
        assertThat(body.has("reasoning_effort")).isFalse()
        assertThat(body.getDouble("temperature")).isWithin(0.01).of(0.5)
    }
}
