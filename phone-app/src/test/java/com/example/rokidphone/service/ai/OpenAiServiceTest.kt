package com.example.rokidphone.service.ai

import com.example.rokidphone.data.AiProvider
import com.example.rokidphone.service.SpeechResult
import com.example.rokidphone.testutil.MockWebServerRule
import com.example.rokidphone.testutil.TestFixtures
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import okhttp3.Headers.Companion.headersOf
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class OpenAiServiceTest {

    @get:Rule
    val serverRule = MockWebServerRule()

    private fun createService(apiKey: String = "test-openai-key"): OpenAiCompatibleService =
        OpenAiCompatibleService(
            apiKey = apiKey,
            baseUrl = serverRule.baseUrl,
            modelId = "gpt-4o",
            providerType = AiProvider.OPENAI
        )

    private fun jsonResponse(body: String, code: Int = 200) = MockResponse(
        code = code,
        body = body,
        headers = headersOf("Content-Type", "application/json")
    )

    @Test
    fun `chat - successful response returns text`() = runTest {
        // 測試：OpenAI chat 成功回傳文字
        val service = createService()
        serverRule.server.enqueue(jsonResponse(TestFixtures.MockResponses.openAiChatSuccess("hi from openai")))

        val result = service.chat("hello")

        assertThat(result).isEqualTo("hi from openai")
    }

    @Test
    fun `chat - unauthorized returns meaningful error`() = runTest {
        // 測試：401 應回傳可理解錯誤訊息
        val service = createService()
        serverRule.server.enqueue(jsonResponse(TestFixtures.MockResponses.openAiError(message = "Invalid API key"), 401))

        val result = service.chat("hello")

        assertThat(result).contains("Invalid API key")
    }

    @Test
    fun `transcribe - successful whisper response returns text`() = runTest {
        // 測試：OpenAI Whisper 轉錄成功
        val service = createService()
        serverRule.server.enqueue(jsonResponse(TestFixtures.MockResponses.openAiWhisperSuccess("test speech")))

        val result = service.transcribe(TestFixtures.createTestPcmAudio(), "en-US")

        assertThat(result).isInstanceOf(SpeechResult.Success::class.java)
        assertThat((result as SpeechResult.Success).text).isEqualTo("test speech")
    }

    @Test
    fun `analyzeImage - successful response returns description`() = runTest {
        // 測試：OpenAI vision 成功回傳圖片描述
        val service = createService()
        serverRule.server.enqueue(jsonResponse(TestFixtures.MockResponses.openAiChatSuccess("a white square")))

        val result = service.analyzeImage(TestFixtures.createTestJpeg(), "describe")

        assertThat(result).isEqualTo("a white square")
    }
}
