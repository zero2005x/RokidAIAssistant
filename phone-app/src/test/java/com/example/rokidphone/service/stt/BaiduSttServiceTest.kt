package com.example.rokidphone.service.stt

import com.example.rokidphone.testutil.MockWebServerRule
import com.example.rokidphone.testutil.TestFixtures
import com.example.rokidphone.service.SpeechResult
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import okhttp3.Headers.Companion.headersOf
import org.json.JSONObject
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Unit tests for BaiduSttService.
 * Tests token exchange and speech recognition via MockWebServer.
 */
@RunWith(RobolectricTestRunner::class)
class BaiduSttServiceTest {

    @get:Rule
    val mockServer = MockWebServerRule()

    private fun createService(
        apiKey: String = "test-api-key",
        secretKey: String = "test-secret-key"
    ): BaiduSttService = BaiduSttService(
        apiKey = apiKey,
        secretKey = secretKey,
        tokenUrl = mockServer.baseUrlNoSlash + "/oauth/2.0/token",
        asrUrl = mockServer.baseUrlNoSlash + "/server_api"
    )

    @Before
    fun setUp() {
        // BaiduSttService may cache tokens statically; create a fresh service
        // to ensure the token endpoint is called during each test
    }

    private fun jsonResponse(body: String, code: Int = 200) = MockResponse(
        code = code,
        body = body,
        headers = headersOf("Content-Type", "application/json")
    )

    private fun tokenSuccessResponse(token: String = "test-access-token") = jsonResponse(
        """{"access_token":"$token","expires_in":2592000}"""
    )

    // ==================== Token Exchange ====================

    @Test
    fun `getAccessToken - sends correct parameters`() = runTest {
        val service = createService(apiKey = "my-api-key", secretKey = "my-secret")
        mockServer.server.enqueue(tokenSuccessResponse())
        // Also need an ASR response since transcribe calls both
        mockServer.server.enqueue(jsonResponse("""{"err_no":0,"result":["test"]}"""))

        service.transcribe(TestFixtures.createTestPcmAudio(), "zh-CN")

        val tokenRequest = mockServer.server.takeRequest()
        val path = tokenRequest.path ?: ""
        assertThat(path).contains("grant_type=client_credentials")
        assertThat(path).contains("client_id=my-api-key")
        assertThat(path).contains("client_secret=my-secret")
    }

    @Test
    fun `getAccessToken - token failure returns error`() = runTest {
        val service = createService()
        mockServer.server.enqueue(jsonResponse("""{"error":"invalid_client"}""", 401))

        val result = service.transcribe(TestFixtures.createTestPcmAudio(), "zh-CN")

        assertThat(result).isInstanceOf(SpeechResult.Error::class.java)
    }

    // ==================== Transcribe Tests ====================

    @Test
    fun `transcribe - success with Mandarin`() = runTest {
        val service = createService()
        mockServer.server.enqueue(tokenSuccessResponse())
        mockServer.server.enqueue(jsonResponse("""{"err_no":0,"result":["你好世界"]}"""))

        val result = service.transcribe(TestFixtures.createTestPcmAudio(), "zh-CN")

        assertThat(result).isInstanceOf(SpeechResult.Success::class.java)
        assertThat((result as SpeechResult.Success).text).isEqualTo("你好世界")
    }

    @Test
    fun `transcribe - request body contains required fields`() = runTest {
        val service = createService()
        mockServer.server.enqueue(tokenSuccessResponse("tok-123"))
        mockServer.server.enqueue(jsonResponse("""{"err_no":0,"result":["test"]}"""))

        service.transcribe(TestFixtures.createTestPcmAudio(), "zh-CN")

        // Skip token request
        mockServer.server.takeRequest()
        // Check ASR request
        val asrRequest = mockServer.server.takeRequest()
        val body = JSONObject(asrRequest.body.readUtf8())
        assertThat(body.getString("format")).isEqualTo("pcm")
        assertThat(body.getInt("rate")).isEqualTo(16000)
        assertThat(body.getInt("channel")).isEqualTo(1)
        assertThat(body.getString("token")).isEqualTo("tok-123")
        assertThat(body.has("speech")).isTrue()
        assertThat(body.has("len")).isTrue()
    }

    @Test
    fun `transcribe - English uses dev_pid 1737`() = runTest {
        val service = createService()
        mockServer.server.enqueue(tokenSuccessResponse())
        mockServer.server.enqueue(jsonResponse("""{"err_no":0,"result":["hello"]}"""))

        service.transcribe(TestFixtures.createTestPcmAudio(), "en-US")

        mockServer.server.takeRequest() // skip token
        val asrRequest = mockServer.server.takeRequest()
        val body = JSONObject(asrRequest.body.readUtf8())
        assertThat(body.getInt("dev_pid")).isEqualTo(1737)
    }

    @Test
    fun `transcribe - Mandarin uses dev_pid 1537`() = runTest {
        val service = createService()
        mockServer.server.enqueue(tokenSuccessResponse())
        mockServer.server.enqueue(jsonResponse("""{"err_no":0,"result":["你好"]}"""))

        service.transcribe(TestFixtures.createTestPcmAudio(), "zh-CN")

        mockServer.server.takeRequest() // skip token
        val asrRequest = mockServer.server.takeRequest()
        val body = JSONObject(asrRequest.body.readUtf8())
        assertThat(body.getInt("dev_pid")).isEqualTo(1537)
    }

    @Test
    fun `transcribe - error response returns error result`() = runTest {
        val service = createService()
        mockServer.server.enqueue(tokenSuccessResponse())
        mockServer.server.enqueue(jsonResponse("""{"err_no":3301,"err_msg":"Audio quality error"}"""))

        val result = service.transcribe(TestFixtures.createTestPcmAudio(), "zh-CN")

        assertThat(result).isInstanceOf(SpeechResult.Error::class.java)
    }

    @Test
    fun `transcribe - server error returns error result`() = runTest {
        val service = createService()
        mockServer.server.enqueue(tokenSuccessResponse())
        mockServer.server.enqueue(jsonResponse("""{"error":"server error"}""", 500))

        val result = service.transcribe(TestFixtures.createTestPcmAudio(), "zh-CN")

        assertThat(result).isInstanceOf(SpeechResult.Error::class.java)
    }

    @Test
    fun `transcribe - audio too short returns error`() = runTest {
        val service = createService()

        val result = service.transcribe(TestFixtures.createTooShortAudio(), "zh-CN")

        assertThat(result).isInstanceOf(SpeechResult.Error::class.java)
    }

    @Test
    fun `transcribe - empty audio returns error`() = runTest {
        val service = createService()

        val result = service.transcribe(TestFixtures.createEmptyAudio(), "zh-CN")

        assertThat(result).isInstanceOf(SpeechResult.Error::class.java)
    }

    // ==================== Validate Credentials ====================

    @Test
    fun `validateCredentials - valid token returns Valid`() = runTest {
        val service = createService()
        mockServer.server.enqueue(tokenSuccessResponse())

        val result = service.validateCredentials()

        assertThat(result).isInstanceOf(SttValidationResult.Valid::class.java)
    }

    @Test
    fun `validateCredentials - token failure returns Invalid`() = runTest {
        val service = createService()
        mockServer.server.enqueue(jsonResponse("""{"error":"invalid_client"}""", 401))

        val result = service.validateCredentials()

        assertThat(result).isInstanceOf(SttValidationResult.Invalid::class.java)
    }

    // ==================== Provider Metadata ====================

    @Test
    fun `provider returns BAIDU_ASR`() {
        val service = createService()
        assertThat(service.provider).isEqualTo(SttProvider.BAIDU_ASR)
    }

    @Test
    fun `default URLs match expected Baidu endpoints`() {
        assertThat(BaiduSttService.DEFAULT_TOKEN_URL)
            .isEqualTo("https://aip.baidubce.com/oauth/2.0/token")
        assertThat(BaiduSttService.DEFAULT_ASR_URL)
            .isEqualTo("https://vop.baidu.com/server_api")
    }
}
