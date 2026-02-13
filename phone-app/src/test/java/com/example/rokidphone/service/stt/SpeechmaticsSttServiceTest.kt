package com.example.rokidphone.service.stt

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Unit tests for SpeechmaticsSttService.
 * Tests credential blank check and provider metadata.
 * Speechmatics uses WebSocket streaming with API key auth.
 */
@RunWith(RobolectricTestRunner::class)
class SpeechmaticsSttServiceTest {

    private fun createService(
        apiKey: String = "test-sm-key"
    ): SpeechmaticsSttService = SpeechmaticsSttService(
        apiKey = apiKey
    )

    // ==================== Validate Credentials ====================

    @Test
    fun `validateCredentials - valid key returns Valid`() = runTest {
        val service = createService()

        val result = service.validateCredentials()

        assertThat(result).isInstanceOf(SttValidationResult.Valid::class.java)
    }

    @Test
    fun `validateCredentials - blank key returns Invalid`() = runTest {
        val service = createService(apiKey = "   ")

        val result = service.validateCredentials()

        assertThat(result).isInstanceOf(SttValidationResult.Invalid::class.java)
    }

    @Test
    fun `validateCredentials - empty key returns Invalid`() = runTest {
        val service = createService(apiKey = "")

        val result = service.validateCredentials()

        assertThat(result).isInstanceOf(SttValidationResult.Invalid::class.java)
    }

    // ==================== Provider Metadata ====================

    @Test
    fun `provider returns SPEECHMATICS`() {
        val service = createService()
        assertThat(service.provider).isEqualTo(SttProvider.SPEECHMATICS)
    }

    @Test
    fun `supportsStreaming returns true`() {
        val service = createService()
        assertThat(service.supportsStreaming()).isTrue()
    }
}
