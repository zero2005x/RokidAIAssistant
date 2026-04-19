package com.example.rokidphone.service.ai

import com.example.rokidphone.data.AiProvider
import com.example.rokidphone.service.SpeechResult
import com.example.rokidphone.testutil.MockWebServerRule
import com.example.rokidphone.testutil.TestFixtures
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import okhttp3.Headers.Companion.headersOf
import org.json.JSONObject
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

    // ==================== v0.12.0 DeepSeekService adapter ====================

    private fun createDeepSeek(
        modelId: String,
        temperature: Float = 0.7f
    ): DeepSeekService = DeepSeekService(
        apiKey = "test-deepseek-key",
        baseUrl = serverRule.baseUrl,
        modelId = modelId,
        temperature = temperature
    )

    /** Build a DeepSeek-style response body including a reasoning_content field. */
    private fun deepSeekReasonerSuccess(content: String, reasoning: String) = """
        {
          "choices": [{
            "index": 0,
            "message": {
              "role": "assistant",
              "content": "$content",
              "reasoning_content": "$reasoning"
            }
          }]
        }
    """.trimIndent()

    @Test
    fun `DeepSeekService reasoner strips temperature from the request body`() = runTest {
        val service = createDeepSeek("deepseek-reasoner", temperature = 0.9f)
        serverRule.server.enqueue(jsonResponse(deepSeekReasonerSuccess("final answer", "step 1")))

        service.chat("hello")

        val body = JSONObject(serverRule.server.takeRequest().body.readUtf8())
        assertThat(body.has("temperature")).isFalse()
        assertThat(body.has("top_p")).isFalse()
        assertThat(body.has("frequency_penalty")).isFalse()
        assertThat(body.has("presence_penalty")).isFalse()
    }

    @Test
    fun `DeepSeekService reasoner captures reasoning_content separately from final answer`() = runTest {
        val service = createDeepSeek("deepseek-reasoner")
        serverRule.server.enqueue(
            jsonResponse(deepSeekReasonerSuccess("42 is the answer", "think: 6 * 7"))
        )

        val text = service.chat("what's 6 times 7?")

        // Final answer must be the polished content, never the reasoning.
        assertThat(text).isEqualTo("42 is the answer")
        assertThat(text).doesNotContain("think: 6 * 7")
        // Reasoning is exposed via the dedicated side channel.
        assertThat(service.lastReasoningContent).isEqualTo("think: 6 * 7")
    }

    @Test
    fun `DeepSeekService speciale strips temperature and captures reasoning_content`() = runTest {
        val service = createDeepSeek("deepseek-v3.2-speciale")
        serverRule.server.enqueue(
            jsonResponse(deepSeekReasonerSuccess("spec answer", "spec thinking"))
        )

        val text = service.chat("hi")

        val body = JSONObject(serverRule.server.takeRequest().body.readUtf8())
        assertThat(body.has("temperature")).isFalse()
        assertThat(text).isEqualTo("spec answer")
        assertThat(service.lastReasoningContent).isEqualTo("spec thinking")
    }

    @Test
    fun `DeepSeekService chat keeps temperature and clears reasoning_content`() = runTest {
        val service = createDeepSeek("deepseek-chat", temperature = 0.3f)
        serverRule.server.enqueue(
            jsonResponse(TestFixtures.MockResponses.openAiChatSuccess("plain chat"))
        )

        val text = service.chat("hi")

        val body = JSONObject(serverRule.server.takeRequest().body.readUtf8())
        assertThat(body.getDouble("temperature")).isWithin(0.01).of(0.3)
        assertThat(text).isEqualTo("plain chat")
        // No reasoning_content in a deepseek-chat response → field must be cleared.
        assertThat(service.lastReasoningContent).isNull()
    }

    @Test
    fun `DeepSeekService isReasonerModel identifies reasoner and speciale only`() {
        assertThat(DeepSeekService.isReasonerModel("deepseek-reasoner")).isTrue()
        assertThat(DeepSeekService.isReasonerModel("deepseek-v3.2-speciale")).isTrue()
        assertThat(DeepSeekService.isReasonerModel("deepseek-chat")).isFalse()
        assertThat(DeepSeekService.isReasonerModel("gpt-4o")).isFalse()
    }
}
