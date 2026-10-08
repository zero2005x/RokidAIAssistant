package io.github.zero2005x.glassesaicompanion.data.log

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The secrets below are synthetic. They are built from pieces so that secret scanners do not
 * mistake the test source for a leaked credential.
 */
class LogRedactorTest {

    private val openAiKey = "sk-" + "proj-abc123DEF456"
    private val anthropicKey = "sk-" + "ant-api03-xyz987"
    private val googleKey = "AIza" + "SyFAKE-test-key-0123456789abcdef"

    @Test
    fun `provider keys are masked wherever they appear`() {
        for (key in listOf(openAiKey, anthropicKey, googleKey)) {
            val redacted = LogRedactor.redact("calling provider with $key and more text")

            assertThat(redacted).doesNotContain(key)
            assertThat(redacted).contains("***")
            assertThat(redacted).contains("calling provider with")
        }
    }

    @Test
    fun `authorization headers keep their name and lose their value`() {
        val redacted = LogRedactor.redact("Authorization: Bearer abc.def.ghi-123")

        assertThat(redacted).isEqualTo("Authorization: ***")
    }

    @Test
    fun `basic credentials with base64 padding are masked completely`() {
        val redacted = LogRedactor.redact("Proxy: Basic dXNlcjpzZWNyZXQ=")

        assertThat(redacted).isEqualTo("Proxy: ***")
    }

    @Test
    fun `api key headers are masked`() {
        val google = LogRedactor.redact("x-goog-api-key: $googleKey")
        val anthropic = LogRedactor.redact("x-api-key: $anthropicKey")
        val generic = LogRedactor.redact("x-api-key: plain-value-without-known-prefix")

        assertThat(google).isEqualTo("x-goog-api-key: ***")
        assertThat(anthropic).isEqualTo("x-api-key: ***")
        assertThat(generic).isEqualTo("x-api-key: ***")
    }

    @Test
    fun `credentials in a query string are masked`() {
        val redacted = LogRedactor.redact(
            "GET https://example.test/v1/models?key=plain-secret&alt=sse&access_token=tok-123&api_key=k-9"
        )

        assertThat(redacted).contains("key=***")
        assertThat(redacted).contains("access_token=***")
        assertThat(redacted).contains("api_key=***")
        assertThat(redacted).contains("alt=sse")
        assertThat(redacted).doesNotContain("plain-secret")
        assertThat(redacted).doesNotContain("tok-123")
        assertThat(redacted).doesNotContain("k-9")
    }

    @Test
    fun `json credential fields keep the field name and lose the value`() {
        val redacted = LogRedactor.redact("""{"api_key":"abc","token":"def","password":"ghi","model":"gpt"}""")

        assertThat(redacted).contains("\"api_key\":\"***\"")
        assertThat(redacted).contains("\"token\":\"***\"")
        assertThat(redacted).contains("\"password\":\"***\"")
        assertThat(redacted).contains("\"model\":\"gpt\"")
    }

    @Test
    fun `an empty json credential field is left alone`() {
        assertThat(LogRedactor.redact("""{"api_key":""}""")).isEqualTo("""{"api_key":""}""")
    }

    @Test
    fun `ordinary text and numbers are not touched`() {
        val text = "Transcript segment (42 characters), maxTokens=2048, monkey=banana, tokenCount=7"

        assertThat(LogRedactor.redact(text)).isEqualTo(text)
    }

    @Test
    fun `redaction is idempotent`() {
        val once = LogRedactor.redact("Authorization: Bearer abc key=secret $openAiKey")

        assertThat(LogRedactor.redact(once)).isEqualTo(once)
    }

    @Test
    fun `snippet is a short single line without secrets`() {
        val body = "{\n  \"error\": \"bad key $openAiKey\",\n  \"detail\": \"" + "x".repeat(500) + "\"\n}"

        val snippet = LogRedactor.snippet(body, maxChars = 80)

        assertThat(snippet).doesNotContain(openAiKey)
        assertThat(snippet).doesNotContain("\n")
        assertThat(snippet.length).isAtMost(81)
        assertThat(snippet).endsWith("…")
    }

    @Test
    fun `snippet of nothing says so`() {
        assertThat(LogRedactor.snippet(null)).isEqualTo("(empty)")
        assertThat(LogRedactor.snippet("   ")).isEqualTo("(empty)")
    }
}