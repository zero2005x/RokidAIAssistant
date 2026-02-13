package com.example.rokidphone.service.stt

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Unit tests for VolcengineSttService.
 * Tests credential blank checks and provider metadata.
 * Volcengine uses AK/SK signed WebSocket connections.
 */
@RunWith(RobolectricTestRunner::class)
class VolcengineSttServiceTest {

    private fun createService(
        appId: String = "test-app-id",
        accessKeyId: String = "test-access-key",
        accessKeySecret: String = "test-secret-key"
    ): VolcengineSttService = VolcengineSttService(
        appId = appId,
        accessKeyId = accessKeyId,
        accessKeySecret = accessKeySecret
    )

    // ==================== Validate Credentials (Blank Checks) ====================

    @Test
    fun `validateCredentials - valid credentials returns Valid`() = runTest {
        val service = createService()

        val result = service.validateCredentials()

        assertThat(result).isInstanceOf(SttValidationResult.Valid::class.java)
    }

    @Test
    fun `validateCredentials - blank appId returns Invalid`() = runTest {
        val service = createService(appId = "   ")

        val result = service.validateCredentials()

        assertThat(result).isInstanceOf(SttValidationResult.Invalid::class.java)
    }

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

    // ==================== Provider Metadata ====================

    @Test
    fun `provider returns VOLCENGINE`() {
        val service = createService()
        assertThat(service.provider).isEqualTo(SttProvider.VOLCENGINE)
    }

    @Test
    fun `supportsStreaming returns true`() {
        val service = createService()
        assertThat(service.supportsStreaming()).isTrue()
    }
}
