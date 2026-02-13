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
class DeepSeekServiceTest {

    @get:Rule
    val serverRule = MockWebServerRule()

    private fun createService(): OpenAiCompatibleService = OpenAiCompatibleService(
        apiKey = "test-deepseek-key",
        baseUrl = serverRule.baseUrl,
        modelId = "deepseek-chat",
        providerType = AiProvider.DEEPSEEK
    )

    private fun jsonResponse(body: String, code: Int = 200) = MockResponse(
        code = code,
        body = body,
        headers = headersOf("Content-Type", "application/json")
    )

    @Test
    fun `chat - successful response returns text`() = runTest {
        // 測試：DeepSeek chat 成功回傳文字
        val service = createService()
        serverRule.server.enqueue(jsonResponse(TestFixtures.MockResponses.openAiChatSuccess("deepseek ok")))

        val result = service.chat("hello")

        assertThat(result).isEqualTo("deepseek ok")
    }

    @Test
    fun `transcribe - provider does not support STT returns error`() = runTest {
        // 測試：DeepSeek 不支援 STT
        val service = createService()

        val result = service.transcribe(TestFixtures.createTestPcmAudio(), "zh-TW")

        assertThat(result).isInstanceOf(SpeechResult.Error::class.java)
        assertThat((result as SpeechResult.Error).message).contains("does not support")
    }

    @Test
    fun `analyzeImage - provider does not support vision returns error`() = runTest {
        // 測試：DeepSeek 不支援視覺分析
        val service = createService()

        val result = service.analyzeImage(TestFixtures.createTestJpeg(), "describe")

        assertThat(result).contains("does not support")
    }
}
