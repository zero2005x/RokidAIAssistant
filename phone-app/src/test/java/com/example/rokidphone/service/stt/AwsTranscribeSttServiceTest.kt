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
 * Tests non-network logic: audio validation, provider metadata, credential blank checks,
 * and language code mapping (via reflection on the private mapLanguageCode method).
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

    /** Invoke the private mapLanguageCode method via reflection. */
    private fun mapLanguageCode(service: AwsTranscribeSttService, code: String): String {
        val method = AwsTranscribeSttService::class.java.getDeclaredMethod("mapLanguageCode", String::class.java)
        method.isAccessible = true
        return method.invoke(service, code) as String
    }

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

    // ==================== mapLanguageCode (via reflection) ====================

    @Test
    fun `mapLanguageCode - zh-TW maps to zh-TW`() {
        val service = createService()
        assertThat(mapLanguageCode(service, "zh-TW")).isEqualTo("zh-TW")
    }

    @Test
    fun `mapLanguageCode - zh-Hant maps to zh-TW`() {
        val service = createService()
        assertThat(mapLanguageCode(service, "zh-Hant")).isEqualTo("zh-TW")
    }

    @Test
    fun `mapLanguageCode - zh maps to zh-CN`() {
        val service = createService()
        assertThat(mapLanguageCode(service, "zh")).isEqualTo("zh-CN")
    }

    @Test
    fun `mapLanguageCode - zh-CN maps to zh-CN`() {
        val service = createService()
        assertThat(mapLanguageCode(service, "zh-CN")).isEqualTo("zh-CN")
    }

    @Test
    fun `mapLanguageCode - en maps to en-US`() {
        val service = createService()
        assertThat(mapLanguageCode(service, "en")).isEqualTo("en-US")
    }

    @Test
    fun `mapLanguageCode - en-GB maps to en-US`() {
        val service = createService()
        assertThat(mapLanguageCode(service, "en-GB")).isEqualTo("en-US")
    }

    @Test
    fun `mapLanguageCode - ja maps to ja-JP`() {
        val service = createService()
        assertThat(mapLanguageCode(service, "ja")).isEqualTo("ja-JP")
    }

    @Test
    fun `mapLanguageCode - ko maps to ko-KR`() {
        val service = createService()
        assertThat(mapLanguageCode(service, "ko")).isEqualTo("ko-KR")
    }

    @Test
    fun `mapLanguageCode - fr maps to fr-FR`() {
        val service = createService()
        assertThat(mapLanguageCode(service, "fr")).isEqualTo("fr-FR")
    }

    @Test
    fun `mapLanguageCode - de maps to de-DE`() {
        val service = createService()
        assertThat(mapLanguageCode(service, "de")).isEqualTo("de-DE")
    }

    @Test
    fun `mapLanguageCode - es maps to es-US`() {
        val service = createService()
        assertThat(mapLanguageCode(service, "es")).isEqualTo("es-US")
    }

    @Test
    fun `mapLanguageCode - pt maps to pt-BR`() {
        val service = createService()
        assertThat(mapLanguageCode(service, "pt")).isEqualTo("pt-BR")
    }

    @Test
    fun `mapLanguageCode - unknown code passes through unchanged`() {
        val service = createService()
        assertThat(mapLanguageCode(service, "xx-XX")).isEqualTo("xx-XX")
    }

    @Test
    fun `mapLanguageCode - it passes through unchanged (not in mapping)`() {
        val service = createService()
        assertThat(mapLanguageCode(service, "it")).isEqualTo("it")
    }
}

