package com.example.rokidphone.service.ai

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.net.UnknownHostException

@RunWith(RobolectricTestRunner::class)
class BaseAiServiceTest {

    private class TestBaseAiService(
        apiKey: String = "key",
        modelId: String = "model",
        systemPrompt: String = "system prompt"
    ) : BaseAiService(apiKey, modelId, systemPrompt) {

        fun convertPcmToWav(pcmData: ByteArray): ByteArray = pcmToWav(pcmData)

        suspend fun <T> callExecuteWithRetry(action: suspend (attempt: Int) -> T?): T? {
            return executeWithRetry("BaseAiServiceTest", action)
        }

        fun addHistory(userMessage: String, assistantMessage: String) = addToHistory(userMessage, assistantMessage)

        fun historySnapshot(): List<Pair<String, String>> = conversationHistory.toList()

        fun fullSystemPrompt(): String = getFullSystemPrompt()
    }

    @Test
    fun `pcmToWav - empty pcm still generates valid wav header`() {
        // 測試：空 PCM 仍應輸出有效 WAV 結構
        val service = TestBaseAiService()

        val wav = service.convertPcmToWav(byteArrayOf())

        assertThat(wav.size).isEqualTo(44)
        assertThat(String(wav.copyOfRange(0, 4))).isEqualTo("RIFF")
        assertThat(String(wav.copyOfRange(8, 12))).isEqualTo("WAVE")
        assertThat(String(wav.copyOfRange(12, 16))).isEqualTo("fmt ")
        assertThat(String(wav.copyOfRange(36, 40))).isEqualTo("data")
    }

    @Test
    fun `pcmToWav - non empty pcm appends payload after header`() {
        // 測試：PCM 資料應附加於 WAV 標頭後
        val service = TestBaseAiService()
        val pcm = byteArrayOf(1, 2, 3, 4)

        val wav = service.convertPcmToWav(pcm)

        assertThat(wav.size).isEqualTo(44 + pcm.size)
        assertThat(wav.copyOfRange(44, wav.size).toList()).isEqualTo(pcm.toList())
    }

    @Test
    fun `executeWithRetry - retries network exception then returns success`() = runTest {
        // 測試：網路錯誤應重試並在成功時返回
        val service = TestBaseAiService()
        var calls = 0

        val result = service.callExecuteWithRetry { attempt ->
            calls = attempt
            if (attempt < 3) throw UnknownHostException("offline")
            "ok"
        }

        assertThat(result).isEqualTo("ok")
        assertThat(calls).isEqualTo(3)
    }

    @Test
    fun `executeWithRetry - non network exception stops immediately`() = runTest {
        // 測試：非網路錯誤不應重試
        val service = TestBaseAiService()
        var calls = 0

        val result = service.callExecuteWithRetry<String> { attempt ->
            calls = attempt
            throw IllegalStateException("boom")
        }

        assertThat(result).isNull()
        assertThat(calls).isEqualTo(1)
    }

    @Test
    fun `addToHistory - keeps only latest ten entries`() {
        // 測試：歷史紀錄上限為 10 筆
        val service = TestBaseAiService()

        repeat(6) { index ->
            service.addHistory("user-$index", "assistant-$index")
        }

        val history = service.historySnapshot()
        assertThat(history.size).isEqualTo(10)
        assertThat(history.first()).isEqualTo("user" to "user-1")
        assertThat(history.last()).isEqualTo("assistant" to "assistant-5")
    }

    @Test
    fun `getFullSystemPrompt - includes base prompt and current date time label`() {
        // 測試：完整 system prompt 應包含原始提示與時間標籤
        val service = TestBaseAiService(systemPrompt = "You are helpful")

        val prompt = service.fullSystemPrompt()

        assertThat(prompt).contains("You are helpful")
        assertThat(prompt).contains("Current date/time:")
    }
}
