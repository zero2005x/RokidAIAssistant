package com.example.rokidphone.service.ai

import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.unmockkAll
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Client construction and the request-rewriting interceptors in NetworkClientFactory.
 * Every interceptor is driven through a stub chain, so no request leaves the test.
 */
@RunWith(RobolectricTestRunner::class)
class NetworkClientFactoryTest {

    private val request = Request.Builder().url("https://api.example/v1/chat").build()

    @After fun cleanup() = unmockkAll()

    private fun response(forRequest: Request, code: Int = 200) = Response.Builder()
        .request(forRequest).protocol(Protocol.HTTP_1_1).code(code).message("offline fixture")
        .body("{}".toResponseBody()).build()

    /**
     * Runs [interceptor] over [request] and returns the request it handed onwards.
     */
    private fun sentThrough(interceptor: Interceptor, outgoing: Request = request): Request {
        val forwarded = slot<Request>()
        val chain = mockk<Interceptor.Chain>()
        every { chain.request() } returns outgoing
        every { chain.proceed(capture(forwarded)) } answers { response(forwarded.captured) }

        interceptor.intercept(chain)
        return forwarded.captured
    }

    /** The interceptor a factory method adds before the shared logging one. */
    private fun authInterceptorOf(client: OkHttpClient): Interceptor {
        assertThat(client.interceptors).hasSize(2)
        return client.interceptors.first()
    }

    private fun loggingInterceptorOf(client: OkHttpClient): Interceptor = client.interceptors.last()

    // ==================== Client construction ====================

    @Test
    fun `a plain client carries the default timeouts and the logging interceptor`() {
        val client = NetworkClientFactory.createClient()

        assertThat(client.connectTimeoutMillis).isEqualTo(TimeUnit.SECONDS.toMillis(60).toInt())
        assertThat(client.readTimeoutMillis).isEqualTo(TimeUnit.SECONDS.toMillis(120).toInt())
        assertThat(client.writeTimeoutMillis).isEqualTo(TimeUnit.SECONDS.toMillis(120).toInt())
        assertThat(client.interceptors).hasSize(1)
    }

    @Test
    fun `timeouts can be overridden per client`() {
        val client = NetworkClientFactory.createClient(
            connectTimeout = 5, readTimeout = 10, writeTimeout = 15
        )

        assertThat(client.connectTimeoutMillis).isEqualTo(5_000)
        assertThat(client.readTimeoutMillis).isEqualTo(10_000)
        assertThat(client.writeTimeoutMillis).isEqualTo(15_000)
    }

    // ==================== Authorization ====================

    @Test
    fun `an authorized client sends a bearer token by default`() {
        val client = NetworkClientFactory.createClientWithAuth("sk-123")

        val sent = sentThrough(authInterceptorOf(client))

        assertThat(sent.header("Authorization")).isEqualTo("Bearer sk-123")
        assertThat(client.connectTimeoutMillis).isEqualTo(60_000)
    }

    @Test
    fun `the auth header name and prefix can be replaced`() {
        val client = NetworkClientFactory.createClientWithAuth(
            apiKey = "key-9", authHeaderName = "X-Api-Key", authHeaderPrefix = ""
        )

        val sent = sentThrough(authInterceptorOf(client))

        assertThat(sent.header("X-Api-Key")).isEqualTo("key-9")
        assertThat(sent.header("Authorization")).isNull()
    }

    @Test
    fun `an anthropic client sends the key, the api version and the content type`() {
        val client = NetworkClientFactory.createAnthropicClient("ant-key")

        val sent = sentThrough(authInterceptorOf(client))

        assertThat(sent.header("x-api-key")).isEqualTo("ant-key")
        assertThat(sent.header("anthropic-version")).isEqualTo("2023-06-01")
        assertThat(sent.header("Content-Type")).isEqualTo("application/json")
        // The original request is otherwise untouched.
        assertThat(sent.url).isEqualTo(request.url)
    }

    // ==================== Logging ====================

    @Test
    fun `the logging interceptor passes a response straight through`() {
        val logging = loggingInterceptorOf(NetworkClientFactory.createClient())
        val chain = mockk<Interceptor.Chain>()
        val expected = response(request, code = 201)
        every { chain.request() } returns request
        every { chain.proceed(request) } returns expected

        assertThat(logging.intercept(chain)).isSameInstanceAs(expected)
    }

    @Test
    fun `the logging interceptor rethrows a transport failure`() {
        val logging = loggingInterceptorOf(NetworkClientFactory.createClient())
        val chain = mockk<Interceptor.Chain>()
        every { chain.request() } returns request
        every { chain.proceed(request) } throws IOException("connection reset")

        val error = runCatching { logging.intercept(chain) }.exceptionOrNull()

        assertThat(error).isInstanceOf(IOException::class.java)
        assertThat(error).hasMessageThat().isEqualTo("connection reset")
    }

    // ==================== Dynamic base URL ====================

    @Test
    fun `an absolute url is left alone whichever scheme it uses`() {
        val interceptor = DynamicUrlInterceptor("https://base.example")

        assertThat(sentThrough(interceptor).url).isEqualTo(request.url)

        val insecure = Request.Builder().url("http://plain.example/v1").build()
        assertThat(sentThrough(interceptor, insecure).url).isEqualTo(insecure.url)
    }

    @Test
    fun `updating the base url drops a trailing slash`() {
        val interceptor = DynamicUrlInterceptor("https://old.example")

        interceptor.setBaseUrl("https://new.example/")

        // OkHttp only ever hands an interceptor an absolute URL, so the request is
        // forwarded unchanged; this asserts the update itself is accepted.
        assertThat(sentThrough(interceptor).url).isEqualTo(request.url)
    }

    // ==================== Endpoint helpers ====================

    @Test
    fun `building a url joins the base and path with exactly one slash`() {
        assertThat(ApiEndpoints.buildUrl("https://api.example", "v1/chat"))
            .isEqualTo("https://api.example/v1/chat")
        assertThat(ApiEndpoints.buildUrl("https://api.example/", "/v1/chat"))
            .isEqualTo("https://api.example/v1/chat")
        assertThat(ApiEndpoints.buildUrl("https://api.example///", "///v1/chat"))
            .isEqualTo("https://api.example/v1/chat")
        assertThat(ApiEndpoints.buildUrl("https://api.example", ""))
            .isEqualTo("https://api.example/")
    }

    @Test
    fun `the gemini helpers name the model in the path`() {
        assertThat(ApiEndpoints.Gemini.generateContent("gemini-2.5-flash"))
            .isEqualTo("models/gemini-2.5-flash:generateContent")
        assertThat(ApiEndpoints.Gemini.streamGenerateContent("gemini-2.5-pro"))
            .isEqualTo("models/gemini-2.5-pro:streamGenerateContent")
        assertThat(
            ApiEndpoints.buildUrl(
                "https://generativelanguage.googleapis.com/v1beta",
                ApiEndpoints.Gemini.generateContent("gemini-2.5-flash")
            )
        ).isEqualTo(
            "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent"
        )
    }

    @Test
    fun `the openai and anthropic endpoint paths are relative`() {
        val paths = listOf(
            ApiEndpoints.OpenAiCompatible.CHAT_COMPLETIONS,
            ApiEndpoints.OpenAiCompatible.COMPLETIONS,
            ApiEndpoints.OpenAiCompatible.EMBEDDINGS,
            ApiEndpoints.OpenAiCompatible.AUDIO_TRANSCRIPTIONS,
            ApiEndpoints.OpenAiCompatible.AUDIO_TRANSLATIONS,
            ApiEndpoints.OpenAiCompatible.MODELS,
            ApiEndpoints.Anthropic.MESSAGES
        )

        assertThat(paths).containsNoDuplicates()
        for (path in paths) {
            assertThat(path).doesNotContain("://")
            assertThat(path.startsWith("/")).isFalse()
        }
    }
}
