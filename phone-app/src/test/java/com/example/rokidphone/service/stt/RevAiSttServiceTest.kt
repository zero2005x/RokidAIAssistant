package com.example.rokidphone.service.stt

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Unit tests for RevAiSttService.
 * Tests credential blank check and provider metadata.
 * Rev.ai uses WebSocket streaming with Bearer token auth.
 */
@RunWith(RobolectricTestRunner::class)
class RevAiSttServiceTest {

    private fun createService(
        accessToken: String = "test-rev-token"
    ): RevAiSttService = RevAiSttService(
        accessToken = accessToken
    )

    // ==================== Validate Credentials ====================

    @Test
    fun `validateCredentials - valid token returns Valid`() = runTest {
        val service = createService()

        val result = service.validateCredentials()

        assertThat(result).isInstanceOf(SttValidationResult.Valid::class.java)
    }

    @Test
    fun `validateCredentials - blank token returns Invalid`() = runTest {
        val service = createService(accessToken = "   ")

        val result = service.validateCredentials()

        assertThat(result).isInstanceOf(SttValidationResult.Invalid::class.java)
    }

    @Test
    fun `validateCredentials - empty token returns Invalid`() = runTest {
        val service = createService(accessToken = "")

        val result = service.validateCredentials()

        assertThat(result).isInstanceOf(SttValidationResult.Invalid::class.java)
    }

    // ==================== Provider Metadata ====================

    @Test
    fun `provider returns REV_AI`() {
        val service = createService()
        assertThat(service.provider).isEqualTo(SttProvider.REV_AI)
    }

    @Test
    fun `supportsStreaming returns true`() {
        val service = createService()
        assertThat(service.supportsStreaming()).isTrue()
    }
}
