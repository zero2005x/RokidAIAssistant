package com.example.rokidphone.service.stt

import com.example.rokidphone.testutil.TestFixtures
import com.example.rokidphone.service.SpeechResult
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Unit tests for AwsTranscribeSttService.
 * Tests non-network logic: audio validation, provider metadata, and
 * credential blank checks. AWS STS validation requires real Sig V4 signing.
 */
@RunWith(RobolectricTestRunner::class)
class AwsTranscribeSttServiceTest {

    private fun createService(
        accessKeyId: String = "AKIAIOSFODNN7EXAMPLE",
        secretAccessKey: String = "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY",
        region: String = "us-east-1"
    ): AwsTranscribeSttService = AwsTranscribeSttService(
        accessKeyId = accessKeyId,
        secretAccessKey = secretAccessKey,
        region = region
    )

    // ==================== Audio Validation ====================

    @Test
    fun `transcribe - audio too short returns error`() = runTest {
        val service = createService()

        val result = service.transcribe(TestFixtures.createTooShortAudio(), "en-US")

        assertThat(result).isInstanceOf(SpeechResult.Error::class.java)
    }

    @Test
    fun `transcribe - empty audio returns error`() = runTest {
        val service = createService()

        val result = service.transcribe(TestFixtures.createEmptyAudio(), "en-US")

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
    fun `validateCredentials - blank secretAccessKey returns Invalid`() = runTest {
        val service = createService(secretAccessKey = "   ")

        val result = service.validateCredentials()

        assertThat(result).isInstanceOf(SttValidationResult.Invalid::class.java)
    }

    // ==================== Provider Metadata ====================

    @Test
    fun `provider returns AWS_TRANSCRIBE`() {
        val service = createService()
        assertThat(service.provider).isEqualTo(SttProvider.AWS_TRANSCRIBE)
    }

    @Test
    fun `supportsStreaming returns true`() {
        val service = createService()
        assertThat(service.supportsStreaming()).isTrue()
    }
}
