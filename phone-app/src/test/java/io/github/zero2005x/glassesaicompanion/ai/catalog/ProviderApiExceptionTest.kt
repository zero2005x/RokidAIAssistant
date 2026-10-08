package io.github.zero2005x.glassesaicompanion.ai.catalog

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Error classification and secret-redaction tests.
 */
@RunWith(RobolectricTestRunner::class)
class ProviderApiExceptionTest {

    @Test
    fun `http status codes map to expected error kinds`() {
        fun kind(status: Int, body: String? = null) =
            ProviderApiException.fromHttpStatus(status, body).kind

        assertThat(kind(401)).isEqualTo(ProviderErrorKind.INVALID_API_KEY)
        assertThat(kind(403)).isEqualTo(ProviderErrorKind.PERMISSION_DENIED)
        assertThat(kind(403, """{"error": {"message": "unsupported country region"}}"""))
            .isEqualTo(ProviderErrorKind.REGION_MISMATCH)
        assertThat(kind(404)).isEqualTo(ProviderErrorKind.MODEL_UNAVAILABLE)
        assertThat(kind(400, """{"error": {"message": "model is deprecated"}}"""))
            .isEqualTo(ProviderErrorKind.MODEL_DEPRECATED)
        assertThat(kind(400, """{"error": {"message": "maximum context length exceeded"}}"""))
            .isEqualTo(ProviderErrorKind.CONTEXT_TOO_LONG)
        assertThat(kind(400, """{"error": {"message": "invalid image format"}}"""))
            .isEqualTo(ProviderErrorKind.UNSUPPORTED_IMAGE)
        assertThat(kind(400, """{"error": {"message": "bad param"}}"""))
            .isEqualTo(ProviderErrorKind.INVALID_REQUEST)
        assertThat(kind(413)).isEqualTo(ProviderErrorKind.UNSUPPORTED_IMAGE)
        assertThat(kind(408)).isEqualTo(ProviderErrorKind.TIMEOUT)
        assertThat(kind(429)).isEqualTo(ProviderErrorKind.RATE_LIMIT)
        assertThat(kind(429, """{"error": {"message": "quota exhausted"}}"""))
            .isEqualTo(ProviderErrorKind.QUOTA_EXCEEDED)
        assertThat(kind(500)).isEqualTo(ProviderErrorKind.SERVICE_UNAVAILABLE)
        assertThat(kind(599)).isEqualTo(ProviderErrorKind.SERVICE_UNAVAILABLE)
    }

    @Test
    fun `retry-after header is clamped and retryability is tracked`() {
        val e = ProviderApiException.fromHttpStatus(429, null, retryAfterHeader = "7200")
        assertThat(e.retryAfterMs).isEqualTo(3_600_000L)
        assertThat(e.isRetryable).isTrue()

        val zero = ProviderApiException.fromHttpStatus(429, null, retryAfterHeader = "-5")
        assertThat(zero.retryAfterMs).isEqualTo(0L)

        val notRetryable = ProviderApiException.fromHttpStatus(401, null)
        assertThat(notRetryable.isRetryable).isFalse()
    }

    @Test
    fun `error message keeps http status and provider code`() {
        val e = ProviderApiException.fromHttpStatus(
            404,
            """{"error": {"message": "model not found", "code": "model_not_found"}}"""
        )
        assertThat(e.message).contains("404")
        assertThat(e.message).contains("model_not_found")
        assertThat(e.providerErrorCode).isEqualTo("model_not_found")
    }

    @Test
    fun `provider code extraction supports nested top-level and non-zero fallback codes`() {
        assertThat(
            ProviderApiException.fromHttpStatus(
                400,
                """{"error":{"message":"bad request","code":40101}}"""
            ).providerErrorCode
        ).isEqualTo("40101")
        assertThat(
            ProviderApiException.fromHttpStatus(
                400,
                """{"message":"bad request","code":"top_level_code"}"""
            ).providerErrorCode
        ).isEqualTo("top_level_code")
        assertThat(
            ProviderApiException.fromHttpStatus(
                400,
                """{"error_code":"provider_code"}"""
            ).providerErrorCode
        ).isEqualTo("provider_code")
        assertThat(
            ProviderApiException.fromHttpStatus(
                400,
                """{"error_code":"0"}"""
            ).providerErrorCode
        ).isNull()
    }

    @Test
    fun `sanitize redacts known credential formats and json fields`() {
        val leaked = listOf(
            "Authorization: " + "Bearer " + "bearer-token-123",
            "Proxy: " + "Basic " + "dXNlcjpzZWNyZXQ=",
            "sk-" + "test-secret",
            "AIza" + "SyFakeKey1234567890",
            "eyJ" + "hbGciOiJIUzI1NiJ9.payload.signature",
            "key=plain-secret",
            "access_token=another-secret",
            """{"api_key":"json-secret","password":"json-password","secret":"hidden"}"""
        ).joinToString("\n")
        val sanitized = ProviderApiException.sanitize(leaked)

        assertThat(sanitized).contains("Authorization: ***")
        assertThat(sanitized).contains("Proxy: ***")
        assertThat(sanitized).contains("key=***")
        assertThat(sanitized).contains("access_token=***")
        assertThat(sanitized).contains("\"api_key\":\"***\"")
        assertThat(sanitized).contains("\"password\":\"***\"")
        assertThat(sanitized).contains("\"secret\":\"***\"")
        assertThat(sanitized).doesNotContain("bearer-token-123")
        assertThat(sanitized).doesNotContain("dXNlcjpzZWNyZXQ=")
        assertThat(sanitized).doesNotContain("sk-test-secret")
        assertThat(sanitized).doesNotContain("AIzaSyFakeKey1234567890")
        assertThat(sanitized).doesNotContain("payload.signature")
        assertThat(sanitized).doesNotContain("another-secret")
        assertThat(sanitized).doesNotContain("json-secret")
        assertThat(sanitized).doesNotContain("json-password")
    }

    @Test
    fun `classified message never embeds credentials from the response body`() {
        val e = ProviderApiException.fromHttpStatus(
            401,
            """{"error": {"message": "invalid key sk-livekey123456 provided"}}"""
        )
        assertThat(e.message).doesNotContain("sk-livekey123456")
    }

    @Test
    fun `sanitize handles null and blank`() {
        assertThat(ProviderApiException.sanitize(null)).isEqualTo("Unknown provider error")
        assertThat(ProviderApiException.sanitize("   ")).isEqualTo("Unknown provider error")
    }

    @Test
    fun `sanitize truncates to max message length`() {
        val sanitized = ProviderApiException.sanitize("x".repeat(700))
        assertThat(sanitized).hasLength(500)
    }

    @Test
    fun `fromHttpStatus extracts safe detail from alternate message shapes`() {
        val topLevel = ProviderApiException.fromHttpStatus(
            403,
            """{"message":"permission denied for sk-secret"}"""
        )
        assertThat(topLevel.kind).isEqualTo(ProviderErrorKind.PERMISSION_DENIED)
        assertThat(topLevel.message).doesNotContain("sk-secret")

        val stringError = ProviderApiException.fromHttpStatus(
            400,
            """{"error":"access_token=secret is invalid"}"""
        )
        assertThat(stringError.kind).isEqualTo(ProviderErrorKind.INVALID_REQUEST)
        assertThat(stringError.message).contains("access_token=***")
    }
}
