package com.example.rokidphone.service.stt

import com.example.rokidphone.service.SpeechResult
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class BaseSttServiceTest {

    private class TestBaseSttService(
        override val provider: SttProvider = SttProvider.GEMINI,
        private val transcribeBlock: suspend (ByteArray, String) -> SpeechResult = { _, _ -> SpeechResult.Success("ok") },
        private val validationBlock: suspend () -> SttValidationResult = { SttValidationResult.Valid }
    ) : BaseSttService() {

        override suspend fun transcribe(audioData: ByteArray, languageCode: String): SpeechResult {
            return transcribeBlock(audioData, languageCode)
        }

        override suspend fun validateCredentials(): SttValidationResult {
            return validationBlock()
        }

        fun convertPcmToWav(pcmData: ByteArray): ByteArray = pcmToWav(pcmData)

        suspend fun <T> callExecuteWithRetry(block: suspend (attempt: Int) -> T?): T? {
            return executeWithRetry("BaseSttServiceTest", block = block)
        }

        fun callBuildMultipartBody(
            boundary: String,
            audioData: ByteArray,
            additionalFields: Map<String, String>
        ): ByteArray = buildMultipartBody(boundary, audioData, additionalFields = additionalFields)

        fun callIsAudioTooShort(audioData: ByteArray, minBytes: Int = 1000): Boolean {
            return isAudioTooShort(audioData, minBytes)
        }
    }

    @Test
    fun `pcmToWav - empty pcm still generates valid wav header`() {
        // 測試：空 PCM 應輸出標準 WAV 標頭
        val service = TestBaseSttService()

        val wav = service.convertPcmToWav(byteArrayOf())

        assertThat(wav.size).isEqualTo(44)
        assertThat(String(wav.copyOfRange(0, 4))).isEqualTo("RIFF")
        assertThat(String(wav.copyOfRange(8, 12))).isEqualTo("WAVE")
        assertThat(String(wav.copyOfRange(36, 40))).isEqualTo("data")
    }

    @Test
    fun `executeWithRetry - retries failed attempts and returns success`() = runTest {
        // 測試：重試機制應在成功後返回結果
        val service = TestBaseSttService()
        var calls = 0

        val result = service.callExecuteWithRetry { attempt ->
            calls = attempt
            if (attempt < 3) throw IllegalStateException("temporary")
            "done"
        }

        assertThat(result).isEqualTo("done")
        assertThat(calls).isEqualTo(3)
    }

    @Test
    fun `executeWithRetry - all attempts fail returns null`() = runTest {
        // 測試：全部重試失敗時應回傳 null
        val service = TestBaseSttService()

        val result = service.callExecuteWithRetry<String> {
            throw IllegalStateException("always fail")
        }

        assertThat(result).isNull()
    }

    @Test
    fun `buildMultipartBody - contains file part and additional fields`() {
        // 測試：multipart 內容應包含音訊欄位與附加參數
        val service = TestBaseSttService()

        val body = service.callBuildMultipartBody(
            boundary = "test-boundary",
            audioData = byteArrayOf(1, 2, 3),
            additionalFields = mapOf("model" to "whisper-1", "language" to "en")
        )
        val content = body.toString(Charsets.UTF_8)

        assertThat(content).contains("--test-boundary")
        assertThat(content).contains("name=\"file\"; filename=\"audio.wav\"")
        assertThat(content).contains("name=\"model\"")
        assertThat(content).contains("whisper-1")
        assertThat(content).contains("name=\"language\"")
        assertThat(content).contains("en")
    }

    @Test
    fun `isAudioTooShort - returns true when below threshold`() {
        // 測試：小於門檻的音訊應判定為過短
        val service = TestBaseSttService()

        val tooShort = service.callIsAudioTooShort(ByteArray(10), minBytes = 100)
        val enough = service.callIsAudioTooShort(ByteArray(100), minBytes = 100)

        assertThat(tooShort).isTrue()
        assertThat(enough).isFalse()
    }

    @Test
    fun `transcribeAudioFile - default implementation delegates to transcribe`() = runTest {
        // 測試：預設 encoded audio 轉錄應委派至 transcribe
        var capturedData: ByteArray? = null
        var capturedLanguage: String? = null
        val service = TestBaseSttService(
            transcribeBlock = { data, language ->
                capturedData = data
                capturedLanguage = language
                SpeechResult.Success("delegated")
            }
        )
        val audio = byteArrayOf(9, 8, 7)

        val result = service.transcribeAudioFile(audio, mimeType = "audio/mp4", languageCode = "en-US")

        assertThat((result as SpeechResult.Success).text).isEqualTo("delegated")
        assertThat(capturedData!!.toList()).isEqualTo(audio.toList())
        assertThat(capturedLanguage).isEqualTo("en-US")
    }

    @Test
    fun `validateCredentials and release - custom validation works and release is no-op`() = runTest {
        // 測試：驗證結果可由子類別定義，release 預設不拋錯
        val service = TestBaseSttService(
            validationBlock = { SttValidationResult.Invalid(SttValidationError.INVALID_CREDENTIALS) }
        )

        val result = service.validateCredentials()
        service.release()

        assertThat(result).isInstanceOf(SttValidationResult.Invalid::class.java)
        val error = (result as SttValidationResult.Invalid).error
        assertThat(error).isEqualTo(SttValidationError.INVALID_CREDENTIALS)
    }
}
