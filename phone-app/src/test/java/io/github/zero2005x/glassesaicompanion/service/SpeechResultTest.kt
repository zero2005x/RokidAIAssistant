package io.github.zero2005x.glassesaicompanion.service

import io.github.zero2005x.glassesaicompanion.R
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class SpeechResultTest {

    @Test
    fun `SpeechResult Success - stores text correctly`() {
        // 測試：成功結果應保留辨識文字
        val result = SpeechResult.Success("hello")

        assertThat(result.text).isEqualTo("hello")
    }

    @Test
    fun `SpeechResult Error - without errorCode returns raw message`() {
        // 測試：未提供錯誤碼時應直接回傳原始 message
        val context = RuntimeEnvironment.getApplication()
        val result = SpeechResult.Error(message = "raw error")

        assertThat(result.getLocalizedMessage(context)).isEqualTo("raw error")
    }

    @Test
    fun `SpeechErrorCode getStringResId - all enum values map to valid resource id`() {
        // 測試：每個錯誤碼都應對應有效字串資源
        for (code in SpeechErrorCode.entries) {
            assertThat(code.getStringResId()).isGreaterThan(0)
        }
    }

    @Test
    fun `SpeechErrorCode requiresDetail - only expected codes require detail`() {
        // 測試：僅特定錯誤碼需要 detail 參數
        // NETWORK_ERROR/UNKNOWN map to the format string stt_error_transcription_error,
        // so they require a detail argument as well.
        val expected = setOf(
            SpeechErrorCode.TRANSCRIPTION_ERROR,
            SpeechErrorCode.PROVIDER_NOT_SUPPORTED,
            SpeechErrorCode.RECOGNITION_FAILED,
            SpeechErrorCode.NETWORK_ERROR,
            SpeechErrorCode.UNKNOWN
        )

        for (code in SpeechErrorCode.entries) {
            if (code in expected) {
                assertThat(code.requiresDetail()).isTrue()
            } else {
                assertThat(code.requiresDetail()).isFalse()
            }
        }
    }

    @Test
    fun `SpeechResult Error getLocalizedMessage - detail format codes inject detail`() {
        // 測試：需要 detail 的錯誤碼應套用字串格式
        val context = RuntimeEnvironment.getApplication()
        val detail = "provider down"
        val detailCodes = listOf(
            SpeechErrorCode.TRANSCRIPTION_ERROR,
            SpeechErrorCode.PROVIDER_NOT_SUPPORTED,
            SpeechErrorCode.RECOGNITION_FAILED
        )

        for (code in detailCodes) {
            val error = SpeechResult.Error(
                message = "ignored",
                errorCode = code,
                errorDetail = detail
            )
            val expected = context.getString(code.getStringResId(), detail)
            assertThat(error.getLocalizedMessage(context)).isEqualTo(expected)
        }
    }

    @Test
    fun `SpeechResult Error getLocalizedMessage - detail codes with null detail fall back to message`() {
        // 測試：detail 缺省時應退回使用 message 作為格式參數（避免結尾殘留冒號）
        val context = RuntimeEnvironment.getApplication()
        val code = SpeechErrorCode.TRANSCRIPTION_ERROR
        val error = SpeechResult.Error(message = "fallback detail", errorCode = code, errorDetail = null)

        assertThat(error.getLocalizedMessage(context))
            .isEqualTo(context.getString(code.getStringResId(), "fallback detail"))
    }

    @Test
    fun `SpeechResult Error getLocalizedMessage - non detail code uses plain resource string`() {
        // 測試：不需 detail 的錯誤碼應回傳一般字串
        val context = RuntimeEnvironment.getApplication()
        val code = SpeechErrorCode.AUDIO_TOO_SHORT
        val error = SpeechResult.Error(message = "ignored", errorCode = code)

        assertThat(error.getLocalizedMessage(context)).isEqualTo(context.getString(code.getStringResId()))
    }

    @Test
    fun `SpeechErrorCode NETWORK_ERROR and UNKNOWN share transcription_error resource`() {
        // 測試：NETWORK_ERROR 與 UNKNOWN 應共用通用轉錄錯誤資源
        assertThat(SpeechErrorCode.NETWORK_ERROR.getStringResId()).isEqualTo(R.string.stt_error_transcription_error)
        assertThat(SpeechErrorCode.UNKNOWN.getStringResId()).isEqualTo(R.string.stt_error_transcription_error)
    }
}
