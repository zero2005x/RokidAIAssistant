package io.github.zero2005x.glassesaicompanion.service.ai

import io.github.zero2005x.glassesaicompanion.data.AiProvider
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
 * Baidu Qianfan v2 tests: bearer key auth, OpenAI-compatible chat,
 * models endpoint and vision input. The legacy OAuth flow stays in
 * BaiduService (covered by BaiduServiceTest).
 */
@RunWith(RobolectricTestRunner::class)
class QianfanV2ServiceTest {

    @get:Rule
    val mockServer = MockWebServerRule()

    private fun jsonResponse(body: String, code: Int = 200) = MockResponse(
        code = code,
        body = body,
        headers = headersOf("Content-Type", "application/json")
    )

    private fun createService(modelId: String = "ernie-5.1") = QianfanV2Service(
        apiKey = "bce-v3-test-key",
        baseUrl = mockServer.baseUrl,
        modelId = modelId
    )

    @Test
    fun `chat - v2 request uses bearer key and chat completions`() = runTest {
        mockServer.server.enqueue(jsonResponse(TestFixtures.MockResponses.openAiChatSuccess("ernie reply")))

        val result = createService().chat("hello")

        assertThat(result).isEqualTo("ernie reply")
        val request = mockServer.server.takeRequest()
        assertThat(request.path).isEqualTo("/chat/completions")
        assertThat(request.headers["Authorization"]).isEqualTo("Bearer bce-v3-test-key")
        val body = JSONObject(request.body.readUtf8())
        assertThat(body.getString("model")).isEqualTo("ernie-5.1")
        // No OAuth access_token query param on the v2 path.
        assertThat(request.path).doesNotContain("access_token")
    }

    @Test
    fun `chat - provider identity is BAIDU`() {
        assertThat(createService().provider).isEqualTo(AiProvider.BAIDU)
    }

    @Test
    fun `models endpoint works for connection test`() = runTest {
        mockServer.server.enqueue(jsonResponse("""{"data": [{"id": "ernie-5.1"}, {"id": "ernie-5.0"}]}"""))

        val result = createService().testConnection()

        assertThat(result.isSuccess).isTrue()
        assertThat(result.getOrNull()).contains("2 models")
        assertThat(mockServer.server.takeRequest().path).isEqualTo("/models")
    }

    @Test
    fun `analyzeImage - VL model sends image, text model is rejected`() = runTest {
        mockServer.server.enqueue(jsonResponse(TestFixtures.MockResponses.openAiChatSuccess("a temple")))

        val vl = QianfanV2Service(
            apiKey = "key",
            baseUrl = mockServer.baseUrl,
            modelId = "ernie-4.5-turbo-vl"
        )
        val result = vl.analyzeImage(TestFixtures.createTestJpeg(), "describe")
        assertThat(result).isEqualTo("a temple")

        // Text-only model: no request is made.
        val textOnly = createService("ernie-5.1")
        val rejected = textOnly.analyzeImage(TestFixtures.createTestJpeg(), "describe")
        assertThat(rejected).contains("does not support")
        assertThat(mockServer.server.requestCount).isEqualTo(1)
    }

    @Test
    fun `factory - qianfan mode creates QianfanV2Service, legacy keeps BaiduService`() {
        val qianfanSettings = io.github.zero2005x.glassesaicompanion.data.ApiSettings(
            aiProvider = AiProvider.BAIDU,
            baiduQianfanApiKey = "qianfan-key",
            aiModelId = "ernie-5.1"
        )
        assertThat(AiServiceFactory.createService(qianfanSettings))
            .isInstanceOf(QianfanV2Service::class.java)

        val legacySettings = io.github.zero2005x.glassesaicompanion.data.ApiSettings(
            aiProvider = AiProvider.BAIDU,
            baiduApiKey = "legacy-key",
            baiduSecretKey = "legacy-secret",
            baiduUseLegacyAuth = true,
            aiModelId = "ernie-4.0-8k"
        )
        assertThat(AiServiceFactory.createService(legacySettings))
            .isInstanceOf(BaiduService::class.java)

        // Auto-detect: legacy pair without a Qianfan key implies legacy mode.
        val autoLegacy = legacySettings.copy(baiduUseLegacyAuth = false)
        assertThat(AiServiceFactory.createService(autoLegacy))
            .isInstanceOf(BaiduService::class.java)
    }
}
