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
class GroqServiceTest {

    @get:Rule
    val serverRule = MockWebServerRule()

    private fun createService(): OpenAiCompatibleService = OpenAiCompatibleService(
        apiKey = "test-groq-key",
        baseUrl = serverRule.baseUrl,
        modelId = "llama-4-70b-versatile",
        providerType = AiProvider.GROQ
    )

    private fun jsonResponse(body: String, code: Int = 200) = MockResponse(
        code = code,
        body = body,
        headers = headersOf("Content-Type", "application/json")
    )

    @Test
    fun `chat - successful response returns text`() = runTest {
        // 測試：Groq chat 成功回傳文字
        val service = createService()
        serverRule.server.enqueue(jsonResponse(TestFixtures.MockResponses.openAiChatSuccess("groq reply")))

        val result = service.chat("hello")

        assertThat(result).isEqualTo("groq reply")
    }

    @Test
    fun `transcribe - successful whisper response returns text`() = runTest {
        // 測試：Groq Whisper STT 成功回傳
        val service = createService()
        serverRule.server.enqueue(jsonResponse(TestFixtures.MockResponses.openAiWhisperSuccess("groq speech")))

        val result = service.transcribe(TestFixtures.createTestPcmAudio(), "en-US")

        assertThat(result).isInstanceOf(SpeechResult.Success::class.java)
        assertThat((result as SpeechResult.Success).text).isEqualTo("groq speech")

        val req = serverRule.server.takeRequest()
        assertThat(req.path).isEqualTo("/audio/transcriptions")
        assertThat(req.body.readUtf8()).contains("whisper-large-v3-turbo")
    }

    @Test
    fun `analyzeImage - successful response returns description`() = runTest {
        // 測試：Groq vision 成功回傳描述
        val service = createService()
        serverRule.server.enqueue(jsonResponse(TestFixtures.MockResponses.openAiChatSuccess("an object")))

        val result = service.analyzeImage(TestFixtures.createTestJpeg(), "what is this")

        assertThat(result).isEqualTo("an object")
    }
}
