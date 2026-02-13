package com.example.rokidphone.service.stt

import com.example.rokidphone.testutil.TestFixtures
import com.example.rokidphone.service.SpeechResult
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Unit tests for AliyunSttService.
 * Tests audio validation, credential blank checks, and provider metadata.
 * Aliyun uses HMAC-SHA1 signed token requests + WebSocket for transcription.
 */
@RunWith(RobolectricTestRunner::class)
class AliyunSttServiceTest {

    private fun createService(
        accessKeyId: String = "test-access-key",
        accessKeySecret: String = "test-secret-key",
        appKey: String = "test-app-key"
    ): AliyunSttService = AliyunSttService(
        accessKeyId = accessKeyId,
        accessKeySecret = accessKeySecret,
        appKey = appKey
    )

    // ==================== Audio Validation ====================

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

    // ==================== Validate Credentials (Blank Checks) ====================

    @Test
    fun `validateCredentials - blank accessKeyId returns Invalid`() = runTest {
        val service = createService(accessKeyId = "   ")

        val result = service.validateCredentials()

        assertThat(result).isInstanceOf(SttValidationResult.Invalid::class.java)
    }

    @Test
    fun `validateCredentials - blank accessKeySecret returns Invalid`() = runTest {
        val service = createService(accessKeySecret = "   ")

        val result = service.validateCredentials()

        assertThat(result).isInstanceOf(SttValidationResult.Invalid::class.java)
    }

    @Test
    fun `validateCredentials - blank appKey returns Invalid`() = runTest {
        val service = createService(appKey = "   ")

        val result = service.validateCredentials()

        assertThat(result).isInstanceOf(SttValidationResult.Invalid::class.java)
    }

    // ==================== Provider Metadata ====================

    @Test
    fun `provider returns ALIBABA_ASR`() {
        val service = createService()
        assertThat(service.provider).isEqualTo(SttProvider.ALIBABA_ASR)
    }
}
