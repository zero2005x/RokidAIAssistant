package io.github.zero2005x.glassesaicompanion.service.ai

import io.github.zero2005x.glassesaicompanion.ai.catalog.ModelCapabilities
import io.github.zero2005x.glassesaicompanion.data.AiProvider
import io.github.zero2005x.glassesaicompanion.data.ApiSettings
import io.github.zero2005x.glassesaicompanion.testutil.MockWebServerRule
import io.github.zero2005x.glassesaicompanion.testutil.TestFixtures
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
 * Issue #9: Custom endpoint (LM Studio) with a vision-capable local model
 * (e.g. Gemma 4 e4B) must be able to analyze photos when the user enables
 * the manual vision override — and must stay blocked without it.
 */
@RunWith(RobolectricTestRunner::class)
class CustomEndpointVisionTest {

    @get:Rule
    val mockServer = MockWebServerRule()

    private fun jsonResponse(body: String, code: Int = 200) = MockResponse(
        code = code,
        body = body,
        headers = headersOf("Content-Type", "application/json")
    )

    private fun customService(overrides: ModelCapabilities? = null) = OpenAiCompatibleService(
        apiKey = "",
        baseUrl = mockServer.baseUrl,
        modelId = "gemma-4-e4b",
        providerType = AiProvider.CUSTOM,
        capabilityOverrides = overrides
    )

    @Test
    fun `analyzeImage - custom endpoint without override is rejected before any request`() = runTest {
        val result = customService().analyzeImage(TestFixtures.createTestJpeg(), "describe")

        assertThat(result).contains("does not support")
        assertThat(mockServer.server.requestCount).isEqualTo(0)
    }

    @Test
    fun `analyzeImage - custom endpoint with vision override sends image request`() = runTest {
        mockServer.server.enqueue(
            jsonResponse(TestFixtures.MockResponses.openAiChatSuccess("a street scene"))
        )

        val service = customService(ModelCapabilities(imageInput = true, streaming = true))
        val result = service.analyzeImage(TestFixtures.createTestJpeg(), "what is this?")

        assertThat(result).isEqualTo("a street scene")
        val request = mockServer.server.takeRequest()
        assertThat(request.path).isEqualTo("/chat/completions")
        val body = JSONObject(request.body.readUtf8())
        assertThat(body.getString("model")).isEqualTo("gemma-4-e4b")
        val content = body.getJSONArray("messages").getJSONObject(0).getJSONArray("content")
        assertThat(content.getJSONObject(1).getString("type")).isEqualTo("image_url")
        assertThat(
            content.getJSONObject(1).getJSONObject("image_url").getString("url")
        ).startsWith("data:image/jpeg;base64,")
    }

    @Test
    fun `policy - custom with vision override uses image_url format, without uses NONE`() {
        val withVision = ProviderRequestPolicies.resolve(
            AiProvider.CUSTOM, "gemma-4-e4b", ModelCapabilities(imageInput = true)
        )
        assertThat(withVision.imageContentFormat).isEqualTo(ImageContentFormat.OPENAI_IMAGE_URL)

        val without = ProviderRequestPolicies.resolve(
            AiProvider.CUSTOM, "gemma-4-e4b", ModelCapabilities()
        )
        assertThat(without.imageContentFormat).isEqualTo(ImageContentFormat.NONE)
    }

    @Test
    fun `factory - customCapabilityOverrides setting enables image analysis end to end`() = runTest {
        val settings = ApiSettings(
            aiProvider = AiProvider.CUSTOM,
            customBaseUrl = mockServer.baseUrl,
            customModelName = "gemma-4-e4b",
            customCapabilityOverrides = setOf("vision")
        )
        mockServer.server.enqueue(
            jsonResponse(TestFixtures.MockResponses.openAiChatSuccess("factory vision works"))
        )

        val service = AiServiceFactory.createService(settings)
        val result = service.analyzeImage(TestFixtures.createTestJpeg(), "describe")

        assertThat(result).isEqualTo("factory vision works")
        assertThat(mockServer.server.requestCount).isEqualTo(1)
    }

    @Test
    fun `factory - default custom settings keep image analysis blocked`() = runTest {
        val settings = ApiSettings(
            aiProvider = AiProvider.CUSTOM,
            customBaseUrl = mockServer.baseUrl,
            customModelName = "gemma-4-e4b"
        )

        val service = AiServiceFactory.createService(settings)
        val result = service.analyzeImage(TestFixtures.createTestJpeg(), "describe")

        assertThat(result).contains("does not support")
        assertThat(mockServer.server.requestCount).isEqualTo(0)
    }
}
