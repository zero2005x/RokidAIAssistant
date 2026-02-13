package com.example.rokidphone.service.stt

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Unit tests for IbmWatsonSttService.
 * Tests provider metadata. IBM Watson uses WebSocket + IAM token auth,
 * so network-dependent tests are not feasible without full mocking.
 */
@RunWith(RobolectricTestRunner::class)
class IbmWatsonSttServiceTest {

    private fun createService(
        apiKey: String = "test-ibm-key",
        serviceUrl: String = "https://api.us-south.speech-to-text.watson.cloud.ibm.com"
    ): IbmWatsonSttService = IbmWatsonSttService(
        apiKey = apiKey,
        serviceUrl = serviceUrl
    )

    // ==================== Provider Metadata ====================

    @Test
    fun `provider returns IBM_WATSON`() {
        val service = createService()
        assertThat(service.provider).isEqualTo(SttProvider.IBM_WATSON)
    }

    @Test
    fun `supportsStreaming returns true`() {
        val service = createService()
        assertThat(service.supportsStreaming()).isTrue()
    }
}
