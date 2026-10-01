package com.example.rokidphone.ai.catalog

import com.example.rokidphone.data.AiProvider
import com.example.rokidphone.testutil.MockWebServerRule
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import okhttp3.Headers.Companion.headersOf
import okhttp3.OkHttpClient
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Request shape and failure mapping for [RemoteModelCatalogSource], driven against
 * MockWebServer through its baseUrlOverride hook.
 */
@RunWith(RobolectricTestRunner::class)
class RemoteModelCatalogSourceTest {

    @get:Rule
    val mockServer = MockWebServerRule()

    private val client = OkHttpClient()

    private fun source(baseUrl: String? = mockServer.baseUrl) =
        RemoteModelCatalogSource(client = client, baseUrlOverride = baseUrl)

    private fun enqueue(body: String, code: Int = 200, headers: okhttp3.Headers = headersOf()) =
        mockServer.server.enqueue(MockResponse(code = code, body = body, headers = headers))

    private suspend fun fetchFailure(
        provider: AiProvider = AiProvider.OPENAI,
        apiKey: String = "sk-test",
        baseUrl: String? = mockServer.baseUrl
    ): ProviderApiException {
        val error = runCatching { source(baseUrl).fetchModels(provider, apiKey) }.exceptionOrNull()
        assertThat(error).isInstanceOf(ProviderApiException::class.java)
        return error as ProviderApiException
    }

    // ==================== Request shape ====================

    @Test
    fun `the models path is appended to the base url`() = runTest {
        enqueue("""{"data":[{"id":"gpt-5.1"}]}""")

        val models = source().fetchModels(AiProvider.OPENAI, "sk-test")

        assertThat(models.map { it.id }).containsExactly("gpt-5.1")
        val request = mockServer.server.takeRequest()
        assertThat(request.path).isEqualTo("/models")
        assertThat(request.method).isEqualTo("GET")
    }

    @Test
    fun `a trailing slash on the base url does not double up`() = runTest {
        enqueue("{}")

        source(mockServer.baseUrl.trimEnd('/') + "/").fetchModels(AiProvider.OPENAI, "sk-test")

        assertThat(mockServer.server.takeRequest().path).isEqualTo("/models")
    }

    // ==================== Authentication ====================

    @Test
    fun `an openai style provider authenticates with a bearer token`() = runTest {
        enqueue("{}")

        source().fetchModels(AiProvider.OPENAI, "sk-test")

        val headers = mockServer.server.takeRequest().headers
        assertThat(headers["Authorization"]).isEqualTo("Bearer sk-test")
        assertThat(headers["x-api-key"]).isNull()
    }

    @Test
    fun `gemini authenticates with its own key header`() = runTest {
        enqueue("{}")

        source().fetchModels(AiProvider.GEMINI, "gemini-key")

        val headers = mockServer.server.takeRequest().headers
        assertThat(headers["x-goog-api-key"]).isEqualTo("gemini-key")
        assertThat(headers["Authorization"]).isNull()
    }

    @Test
    fun `anthropic sends its key alongside the api version`() = runTest {
        enqueue("{}")

        source().fetchModels(AiProvider.ANTHROPIC, "ant-key")

        val headers = mockServer.server.takeRequest().headers
        assertThat(headers["x-api-key"]).isEqualTo("ant-key")
        assertThat(headers["anthropic-version"]).isEqualTo("2023-06-01")
        assertThat(headers["Authorization"]).isNull()
    }

    @Test
    fun `a blank key sends no credential at all`() = runTest {
        enqueue("{}")

        source().fetchModels(AiProvider.OPENAI, "   ")

        val headers = mockServer.server.takeRequest().headers
        assertThat(headers["Authorization"]).isNull()
        assertThat(headers["x-goog-api-key"]).isNull()
    }

    // ==================== Failure mapping ====================

    @Test
    fun `a provider with no catalog endpoint never opens a connection`() = runTest {
        val error = fetchFailure(provider = AiProvider.PERPLEXITY)

        assertThat(error.kind).isEqualTo(ProviderErrorKind.INVALID_REQUEST)
        assertThat(error).hasMessageThat().contains("has no remote catalog")
        assertThat(mockServer.server.requestCount).isEqualTo(0)
    }

    @Test
    fun `a rejected key is reported as an invalid key rather than a network fault`() = runTest {
        enqueue("""{"error":{"message":"Invalid API key"}}""", code = 401)

        val error = fetchFailure()

        assertThat(error.kind).isEqualTo(ProviderErrorKind.INVALID_API_KEY)
        assertThat(error.httpStatus).isEqualTo(401)
    }

    @Test
    fun `a missing catalog endpoint is not treated as an auth failure`() = runTest {
        enqueue("not found", code = 404)

        val error = fetchFailure()

        assertThat(error.httpStatus).isEqualTo(404)
        assertThat(error.kind).isNotEqualTo(ProviderErrorKind.INVALID_API_KEY)
    }

    @Test
    fun `a rate limit carries its retry-after hint`() = runTest {
        enqueue(
            """{"error":{"message":"slow down"}}""",
            code = 429,
            headers = headersOf("Retry-After", "30")
        )

        val error = fetchFailure()

        assertThat(error.kind).isEqualTo(ProviderErrorKind.RATE_LIMIT)
        assertThat(error.isRetryable).isTrue()
        assertThat(error.retryAfterMs).isEqualTo(30_000)
    }

    @Test
    fun `an empty success body is reported rather than parsed as no models`() = runTest {
        enqueue("")

        val error = fetchFailure()

        assertThat(error.kind).isEqualTo(ProviderErrorKind.UNKNOWN)
        assertThat(error.httpStatus).isEqualTo(200)
        assertThat(error).hasMessageThat().contains("empty")
    }

    @Test
    fun `an unreachable endpoint is reported as network unavailable`() = runTest {
        // Take the port the rule handed out, then close it so nothing is listening.
        val deadUrl = mockServer.baseUrl
        mockServer.server.close()

        val error = fetchFailure(baseUrl = deadUrl)

        assertThat(error.kind).isEqualTo(ProviderErrorKind.NETWORK_UNAVAILABLE)
        assertThat(error.isRetryable).isTrue()
    }

    // ==================== Parsing ====================

    @Test
    fun `a body the parser cannot read yields no models rather than an error`() = runTest {
        enqueue("this is not json")

        assertThat(source().fetchModels(AiProvider.OPENAI, "sk-test")).isEmpty()
    }

    @Test
    fun `a gemini catalog is read from its own envelope`() = runTest {
        enqueue(
            """
            {"models":[
              {"name":"models/gemini-2.5-flash","supportedGenerationMethods":["generateContent"]},
              {"name":"models/embedding-001","supportedGenerationMethods":["embedContent"]}
            ]}
            """.trimIndent()
        )

        val models = source().fetchModels(AiProvider.GEMINI, "gemini-key")

        // Only the chat-capable model is offered.
        assertThat(models.map { it.id }).containsExactly("gemini-2.5-flash")
    }
}
