package com.example.rokidphone.ai.provider

import com.example.rokidphone.testutil.MockWebServerRule
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
 * Unit tests for AnythingLLMProvider.
 * Covers chat relay, citation mapping, auth failure, connection failure, and isValid().
 */
@RunWith(RobolectricTestRunner::class)
class AnythingLLMProviderTest {

    @get:Rule
    val mockServer = MockWebServerRule()

    private val provider = AnythingLLMProvider()

    private fun validSetting(slug: String = "my-workspace") = ProviderSetting.AnythingLLM(
        serverUrl = mockServer.baseUrlNoSlash,
        apiKey = "test-api-key",
        workspaceSlug = slug
    )

    private fun jsonResponse(body: String, code: Int = 200) = MockResponse(
        code = code,
        body = body,
        headers = headersOf("Content-Type", "application/json")
    )

    private fun chatSuccessResponse(text: String, sources: String = "[]") = """
        {
          "textResponse": "$text",
          "sources": $sources
        }
    """.trimIndent()

    // ==================== Successful Chat Relay ====================

    @Test
    fun `generateText - sends POST to correct workspace path`() = runTest {
        mockServer.server.enqueue(jsonResponse(chatSuccessResponse("Hello!")))

        provider.generateText(
            setting = validSetting(slug = "test-slug"),
            messages = listOf(ChatMessage(MessageRole.USER, "Hi"))
        )

        val request = mockServer.server.takeRequest()
        assertThat(request.method).isEqualTo("POST")
        assertThat(request.path).isEqualTo("/api/v1/workspace/test-slug/chat")
    }

    @Test
    fun `generateText - request body contains mode chat and user message`() = runTest {
        mockServer.server.enqueue(jsonResponse(chatSuccessResponse("Response")))

        provider.generateText(
            setting = validSetting(),
            messages = listOf(ChatMessage(MessageRole.USER, "What is RAG?"))
        )

        val request = mockServer.server.takeRequest()
        val body = JSONObject(request.body.readUtf8())
        assertThat(body.getString("mode")).isEqualTo("chat")
        assertThat(body.getString("message")).isEqualTo("What is RAG?")
    }

    @Test
    fun `generateText - request sends Bearer token`() = runTest {
        mockServer.server.enqueue(jsonResponse(chatSuccessResponse("Hi")))

        provider.generateText(
            setting = validSetting(),
            messages = listOf(ChatMessage(MessageRole.USER, "Hello"))
        )

        val request = mockServer.server.takeRequest()
        assertThat(request.headers["Authorization"]).isEqualTo("Bearer test-api-key")
    }

    @Test
    fun `generateText - returns textResponse from response body`() = runTest {
        mockServer.server.enqueue(jsonResponse(chatSuccessResponse("Workspace answer")))

        val result = provider.generateText(
            setting = validSetting(),
            messages = listOf(ChatMessage(MessageRole.USER, "Tell me something"))
        )

        assertThat(result).isInstanceOf(GenerationResult.Success::class.java)
        val text = (result as GenerationResult.Success).text
        assertThat(text).startsWith("Workspace answer")
    }

    @Test
    fun `generateText - falls back to response field when textResponse is blank`() = runTest {
        val responseBody = """{"textResponse": "", "response": "Fallback text", "sources": []}"""
        mockServer.server.enqueue(jsonResponse(responseBody))

        val result = provider.generateText(
            setting = validSetting(),
            messages = listOf(ChatMessage(MessageRole.USER, "Test"))
        )

        assertThat(result).isInstanceOf(GenerationResult.Success::class.java)
        assertThat((result as GenerationResult.Success).text).startsWith("Fallback text")
    }

    @Test
    fun `generateText - uses last USER message when history present`() = runTest {
        mockServer.server.enqueue(jsonResponse(chatSuccessResponse("OK")))

        provider.generateText(
            setting = validSetting(),
            messages = listOf(
                ChatMessage(MessageRole.USER, "First message"),
                ChatMessage(MessageRole.ASSISTANT, "First reply"),
                ChatMessage(MessageRole.USER, "Second message")
            )
        )

        val body = JSONObject(mockServer.server.takeRequest().body.readUtf8())
        assertThat(body.getString("message")).isEqualTo("Second message")
    }

    // ==================== Citation Mapping ====================

    @Test
    fun `generateText - appends sources with title and url`() = runTest {
        val sources = """[{"title":"Doc1","url":"https://example.com/doc1","score":0.92}]"""
        mockServer.server.enqueue(jsonResponse(chatSuccessResponse("Answer text", sources)))

        val result = provider.generateText(
            setting = validSetting(),
            messages = listOf(ChatMessage(MessageRole.USER, "Query"))
        ) as GenerationResult.Success

        assertThat(result.text).contains("**Sources:**")
        assertThat(result.text).contains("Doc1")
        assertThat(result.text).contains("https://example.com/doc1")
        assertThat(result.text).contains("[score: 0.92]")
    }

    @Test
    fun `generateText - appends sources with chunkSource when url is missing`() = runTest {
        val sources = """[{"title":"Doc2","chunkSource":"file://docs/notes.txt","score":0.75}]"""
        mockServer.server.enqueue(jsonResponse(chatSuccessResponse("Answer", sources)))

        val result = provider.generateText(
            setting = validSetting(),
            messages = listOf(ChatMessage(MessageRole.USER, "Query"))
        ) as GenerationResult.Success

        assertThat(result.text).contains("Doc2")
        assertThat(result.text).contains("file://docs/notes.txt")
        assertThat(result.text).contains("[score: 0.75]")
    }

    @Test
    fun `generateText - multiple sources all appear in output`() = runTest {
        val sources = """[
            {"title":"DocA","url":"https://a.com","score":0.9},
            {"title":"DocB","url":"https://b.com","score":0.8}
        ]"""
        mockServer.server.enqueue(jsonResponse(chatSuccessResponse("Multi-source answer", sources)))

        val result = provider.generateText(
            setting = validSetting(),
            messages = listOf(ChatMessage(MessageRole.USER, "Query"))
        ) as GenerationResult.Success

        assertThat(result.text).contains("DocA")
        assertThat(result.text).contains("DocB")
        assertThat(result.text).contains("https://a.com")
        assertThat(result.text).contains("https://b.com")
    }

    @Test
    fun `generateText - empty sources array does not append sources section`() = runTest {
        mockServer.server.enqueue(jsonResponse(chatSuccessResponse("Clean answer", "[]")))

        val result = provider.generateText(
            setting = validSetting(),
            messages = listOf(ChatMessage(MessageRole.USER, "Query"))
        ) as GenerationResult.Success

        assertThat(result.text).doesNotContain("**Sources:**")
    }

    // ==================== Auth Failure (validateSetting) ====================

    @Test
    fun `validateSetting - returns Valid on 200 OK`() = runTest {
        mockServer.server.enqueue(jsonResponse("""{"authenticated": true}"""))

        val result = provider.validateSetting(validSetting())

        assertThat(result).isEqualTo(ValidationResult.Valid)
    }

    @Test
    fun `validateSetting - sends GET to auth endpoint with Bearer token`() = runTest {
        mockServer.server.enqueue(jsonResponse("""{"authenticated": true}"""))

        provider.validateSetting(validSetting())

        val request = mockServer.server.takeRequest()
        assertThat(request.method).isEqualTo("GET")
        assertThat(request.path).isEqualTo("/api/v1/auth")
        assertThat(request.headers["Authorization"]).isEqualTo("Bearer test-api-key")
    }

    @Test
    fun `validateSetting - returns Invalid with apiKey field on 401`() = runTest {
        mockServer.server.enqueue(MockResponse(code = 401))

        val result = provider.validateSetting(validSetting())

        assertThat(result).isInstanceOf(ValidationResult.Invalid::class.java)
        val invalid = result as ValidationResult.Invalid
        assertThat(invalid.field).isEqualTo("apiKey")
        assertThat(invalid.reason).contains("authentication failed")
    }

    @Test
    fun `validateSetting - returns Invalid with HTTP code message on non-401 error`() = runTest {
        mockServer.server.enqueue(MockResponse(code = 503))

        val result = provider.validateSetting(validSetting())

        assertThat(result).isInstanceOf(ValidationResult.Invalid::class.java)
        assertThat((result as ValidationResult.Invalid).reason).contains("503")
    }

    @Test
    fun `validateSetting - returns Invalid early when setting is invalid`() = runTest {
        val incompleteSetting = ProviderSetting.AnythingLLM(
            serverUrl = mockServer.baseUrlNoSlash,
            apiKey = "",
            workspaceSlug = "slug"
        )

        val result = provider.validateSetting(incompleteSetting)

        assertThat(result).isInstanceOf(ValidationResult.Invalid::class.java)
        assertThat((result as ValidationResult.Invalid).field).isEqualTo("apiKey")
        // No HTTP request should have been made
        assertThat(mockServer.server.requestCount).isEqualTo(0)
    }

    // ==================== Connection Failure ====================

    @Test
    fun `generateText - returns Error result when server is unreachable`() = runTest {
        val result = provider.generateText(
            setting = ProviderSetting.AnythingLLM(
                serverUrl = "http://127.0.0.1:1",
                apiKey = "key",
                workspaceSlug = "slug"
            ),
            messages = listOf(ChatMessage(MessageRole.USER, "Hello"))
        )

        assertThat(result).isInstanceOf(GenerationResult.Error::class.java)
        val error = result as GenerationResult.Error
        assertThat(error.retryable).isTrue()
    }

    @Test
    fun `validateSetting - returns Invalid when server is unreachable`() = runTest {
        val result = provider.validateSetting(
            ProviderSetting.AnythingLLM(
                serverUrl = "http://127.0.0.1:1",
                apiKey = "key",
                workspaceSlug = "slug"
            )
        )

        assertThat(result).isInstanceOf(ValidationResult.Invalid::class.java)
        assertThat((result as ValidationResult.Invalid).reason).contains("Connection failed")
    }

    @Test
    fun `generateText - returns Error on HTTP 500`() = runTest {
        mockServer.server.enqueue(MockResponse(code = 500))

        val result = provider.generateText(
            setting = validSetting(),
            messages = listOf(ChatMessage(MessageRole.USER, "Query"))
        )

        assertThat(result).isInstanceOf(GenerationResult.Error::class.java)
        val error = result as GenerationResult.Error
        assertThat(error.message).contains("500")
        assertThat(error.retryable).isTrue()
    }

    // ==================== isValid() ====================

    @Test
    fun `isValid returns true when all three fields are non-blank`() {
        val setting = ProviderSetting.AnythingLLM(
            serverUrl = "http://localhost:3001",
            apiKey = "my-key",
            workspaceSlug = "workspace"
        )
        assertThat(setting.isValid()).isTrue()
    }

    @Test
    fun `isValid returns false when serverUrl is blank`() {
        val setting = ProviderSetting.AnythingLLM(serverUrl = "", apiKey = "key", workspaceSlug = "slug")
        assertThat(setting.isValid()).isFalse()
    }

    @Test
    fun `isValid returns false when apiKey is blank`() {
        val setting = ProviderSetting.AnythingLLM(serverUrl = "http://host", apiKey = "", workspaceSlug = "slug")
        assertThat(setting.isValid()).isFalse()
    }

    @Test
    fun `isValid returns false when workspaceSlug is blank`() {
        val setting = ProviderSetting.AnythingLLM(serverUrl = "http://host", apiKey = "key", workspaceSlug = "")
        assertThat(setting.isValid()).isFalse()
    }

    @Test
    fun `isValid returns false when all fields are blank`() {
        val setting = ProviderSetting.AnythingLLM()
        assertThat(setting.isValid()).isFalse()
    }

    // ==================== Unsupported operations ====================

    @Test
    fun `analyzeImage returns error indicating no image support`() = runTest {
        val result = provider.analyzeImage(validSetting(), byteArrayOf(), "prompt")

        assertThat(result).isInstanceOf(GenerationResult.Error::class.java)
        assertThat((result as GenerationResult.Error).message).contains("does not support image analysis")
    }

    @Test
    fun `transcribe returns error indicating no speech support`() = runTest {
        val result = provider.transcribe(validSetting(), byteArrayOf())

        assertThat(result).isInstanceOf(TranscriptionResult.Error::class.java)
        assertThat((result as TranscriptionResult.Error).message).contains("does not support speech recognition")
    }

    @Test
    fun `listModels returns empty list`() = runTest {
        val models = provider.listModels(validSetting())
        assertThat(models).isEmpty()
    }

    // ==================== Provider metadata ====================

    @Test
    fun `providerId is anythingllm`() {
        assertThat(provider.providerId).isEqualTo("anythingllm")
    }
}
