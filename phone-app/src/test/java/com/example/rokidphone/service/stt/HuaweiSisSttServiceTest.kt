package com.example.rokidphone.service.stt

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Unit tests for HuaweiSisSttService.
 * Tests credential blank checks and provider metadata.
 * Huawei SIS uses AK/SK signed WebSocket connections.
 */
@RunWith(RobolectricTestRunner::class)
class HuaweiSisSttServiceTest {

    private fun createService(
        accessKey: String = "test-access-key",
        secretKey: String = "test-secret-key",
        projectId: String = "test-project-id",
        region: String = "cn-north-4"
    ): HuaweiSisSttService = HuaweiSisSttService(
        accessKey = accessKey,
        secretKey = secretKey,
        projectId = projectId,
        region = region
    )

    // ==================== Validate Credentials (Blank Checks) ====================

    @Test
    fun `validateCredentials - valid credentials returns Valid`() = runTest {
        val service = createService()

        val result = service.validateCredentials()

        assertThat(result).isInstanceOf(SttValidationResult.Valid::class.java)
    }

    @Test
    fun `validateCredentials - blank accessKey returns Invalid`() = runTest {
        val service = createService(accessKey = "   ")

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
    fun `validateCredentials - blank projectId returns Invalid`() = runTest {
        val service = createService(projectId = "   ")

        val result = service.validateCredentials()

        assertThat(result).isInstanceOf(SttValidationResult.Invalid::class.java)
    }

    // ==================== Provider Metadata ====================

    @Test
    fun `provider returns HUAWEI_SIS`() {
        val service = createService()
        assertThat(service.provider).isEqualTo(SttProvider.HUAWEI_SIS)
    }

    @Test
    fun `supportsStreaming returns true`() {
        val service = createService()
        assertThat(service.supportsStreaming()).isTrue()
    }
}
