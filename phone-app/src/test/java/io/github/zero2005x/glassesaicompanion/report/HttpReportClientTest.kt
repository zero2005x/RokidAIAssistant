package io.github.zero2005x.glassesaicompanion.report

import com.google.common.truth.Truth.assertThat
import io.github.zero2005x.glassesaicompanion.testutil.MockWebServerRule
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import org.json.JSONObject
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class HttpReportClientTest {

    @get:Rule
    val serverRule = MockWebServerRule()

    private val sample = AiContentReport(
        reason = ReportReason.MISLEADING,
        note = "wrong date",
        assistantContent = "It happened in 1850.",
        userContent = null,
        provider = "OPENAI",
        model = "gpt-5",
        appVersion = "1.2.0 (1)",
        distribution = "play",
        locale = "en-US",
        androidSdk = 35,
        createdAtMillis = 1L
    )

    private fun client(url: String = serverRule.baseUrlNoSlash + "/report") = HttpReportClient(url)

    @Test
    fun `a 2xx answer means the report was delivered`() = runTest {
        serverRule.server.enqueue(MockResponse(code = 204))

        assertThat(client().submit(sample)).isEqualTo(ReportResult.Sent)
    }

    @Test
    fun `the report is posted as json with no credentials`() = runTest {
        serverRule.server.enqueue(MockResponse(code = 200))

        client().submit(sample)

        val request = serverRule.server.takeRequest()
        assertThat(request.method).isEqualTo("POST")
        assertThat(request.path).isEqualTo("/report")
        assertThat(request.headers["Content-Type"]).startsWith("application/json")
        assertThat(request.headers["Authorization"]).isNull()
        assertThat(request.headers["Cookie"]).isNull()
        val body = JSONObject(request.body.readUtf8())
        assertThat(body.getString("assistantContent")).isEqualTo("It happened in 1850.")
        assertThat(body.getString("reason")).isEqualTo("inaccurate_or_misleading")
    }

    @Test
    fun `a client error that retrying cannot fix is not retryable`() = runTest {
        serverRule.server.enqueue(MockResponse(code = 400))

        val result = client().submit(sample)

        assertThat(result).isEqualTo(ReportResult.Failed(retryable = false, detail = "HTTP 400"))
    }

    @Test
    fun `too many requests asks the user to try again later`() = runTest {
        serverRule.server.enqueue(MockResponse(code = 429))

        val result = client().submit(sample) as ReportResult.Failed

        assertThat(result.retryable).isTrue()
    }

    @Test
    fun `a server error is retryable`() = runTest {
        serverRule.server.enqueue(MockResponse(code = 503))

        val result = client().submit(sample) as ReportResult.Failed

        assertThat(result.retryable).isTrue()
        assertThat(result.detail).isEqualTo("HTTP 503")
    }

    @Test
    fun `no connection is a retryable failure`() = runTest {
        // Nothing listens on port 1
        val result = client("http://127.0.0.1:1/report").submit(sample) as ReportResult.Failed

        assertThat(result.retryable).isTrue()
    }

    @Test
    fun `a malformed endpoint fails without retry`() = runTest {
        val result = client("not a url").submit(sample)

        assertThat(result).isEqualTo(ReportResult.Failed(retryable = false, detail = "Invalid report endpoint"))
    }

    // ==================== ReportConfig ====================

    @Test
    fun `only an https endpoint counts as configured`() {
        assertThat(ReportConfig.endpointOrNull("https://reports.example.com/v1/report"))
            .isEqualTo("https://reports.example.com/v1/report")
        assertThat(ReportConfig.endpointOrNull("  HTTPS://reports.example.com  "))
            .isEqualTo("HTTPS://reports.example.com")

        assertThat(ReportConfig.endpointOrNull("http://reports.example.com")).isNull()
        assertThat(ReportConfig.endpointOrNull("")).isNull()
        assertThat(ReportConfig.endpointOrNull("https://")).isNull()
        assertThat(ReportConfig.endpointOrNull("reports.example.com")).isNull()
    }
}