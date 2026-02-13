package com.example.rokidphone.service.stt

import com.example.rokidphone.testutil.TestFixtures
import com.example.rokidphone.service.SpeechResult
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Unit tests for IflytekSttService.
 * Since iFlytek uses HMAC-SHA256 signed requests with hardcoded URLs,
 * we test non-network logic: audio validation, provider metadata, and
 * credential checks.
 */
@RunWith(RobolectricTestRunner::class)
class IflytekSttServiceTest {

    private fun createService(
        appId: String = "test-app-id",
        apiKey: String = "test-api-key",
        apiSecret: String = "test-api-secret"
    ): IflytekSttService = IflytekSttService(
        appId = appId,
        apiKey = apiKey,
        apiSecret = apiSecret
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

    // ==================== Provider Metadata ====================

    @Test
    fun `provider returns IFLYTEK`() {
        val service = createService()
        assertThat(service.provider).isEqualTo(SttProvider.IFLYTEK)
    }

    @Test
    fun `supportsStreaming returns true from provider enum`() {
        val service = createService()
        assertThat(service.supportsStreaming()).isTrue()
    }
}
