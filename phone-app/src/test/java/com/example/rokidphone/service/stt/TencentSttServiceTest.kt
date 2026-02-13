package com.example.rokidphone.service.stt

import com.example.rokidphone.testutil.TestFixtures
import com.example.rokidphone.service.SpeechResult
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Unit tests for TencentSttService.
 * Tests audio validation, credential blank checks, and provider metadata.
 * Tencent uses HMAC-SHA256 signed WebSocket connections.
 */
@RunWith(RobolectricTestRunner::class)
class TencentSttServiceTest {

    private fun createService(
        secretId: String = "test-secret-id",
        secretKey: String = "test-secret-key",
        appId: String = "12345678"
    ): TencentSttService = TencentSttService(
        secretId = secretId,
        secretKey = secretKey,
        appId = appId
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
    fun `validateCredentials - valid credentials returns Valid`() = runTest {
        val service = createService()

        val result = service.validateCredentials()

        assertThat(result).isInstanceOf(SttValidationResult.Valid::class.java)
    }

    @Test
    fun `validateCredentials - blank secretId returns Invalid`() = runTest {
        val service = createService(secretId = "   ")

        val result = service.validateCredentials()

        assertThat(result).isInstanceOf(SttValidationResult.Invalid::class.java)
    }

    @Test
    fun `validateCredentials - blank secretKey returns Invalid`() = runTest {
        val service = createService(secretKey = "   ")

        val result = service.validateCredentials()

        assertThat(result).isInstanceOf(SttValidationResult.Invalid::class.java)
    }

    @Test
    fun `validateCredentials - blank appId returns Invalid`() = runTest {
        val service = createService(appId = "   ")

        val result = service.validateCredentials()

        assertThat(result).isInstanceOf(SttValidationResult.Invalid::class.java)
    }

    // ==================== Provider Metadata ====================

    @Test
    fun `provider returns TENCENT_ASR`() {
        val service = createService()
        assertThat(service.provider).isEqualTo(SttProvider.TENCENT_ASR)
    }
}
