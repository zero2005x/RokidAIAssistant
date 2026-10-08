package io.github.zero2005x.glassesaicompanion.report

import io.github.zero2005x.glassesaicompanion.BuildConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Outcome of sending a report. */
sealed interface ReportResult {
    /** The developer's endpoint accepted the report. */
    data object Sent : ReportResult

    /** The report was not delivered. [retryable] is false when retrying cannot help. */
    data class Failed(val retryable: Boolean, val detail: String) : ReportResult
}

/** Delivers a report to the developer. */
fun interface ReportClient {
    suspend fun submit(report: AiContentReport): ReportResult
}

/**
 * Where reports go. The URL is injected at build time (`REPORT_ENDPOINT_URL`), never hardcoded.
 * Only HTTPS endpoints are accepted.
 */
object ReportConfig {
    fun endpointOrNull(raw: String = BuildConfig.REPORT_ENDPOINT_URL): String? =
        raw.trim().takeIf { it.startsWith("https://", ignoreCase = true) && it.length > "https://".length }

    /** A client for the configured endpoint, or null when this build has no endpoint. */
    fun defaultClientOrNull(): ReportClient? = endpointOrNull()?.let { HttpReportClient(it) }
}

/**
 * Posts the report as JSON over HTTPS.
 *
 * It uses its own plain [OkHttpClient]: no logging interceptor, so the content of a report never
 * ends up in the app log.
 */
class HttpReportClient(
    private val endpoint: String,
    private val client: OkHttpClient = defaultClient,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : ReportClient {

    override suspend fun submit(report: AiContentReport): ReportResult = withContext(ioDispatcher) {
        val request = try {
            Request.Builder()
                .url(endpoint)
                .header("User-Agent", "GlassesAiCompanion/${report.appVersion}")
                .post(report.toJson().toRequestBody(JSON_MEDIA_TYPE))
                .build()
        } catch (e: IllegalArgumentException) {
            return@withContext ReportResult.Failed(retryable = false, detail = "Invalid report endpoint")
        }

        try {
            client.newCall(request).execute().use { response ->
                when {
                    response.isSuccessful -> ReportResult.Sent
                    // The server understood the request and refused it; resending the same body
                    // cannot succeed. 429 is the exception: it asks us to try again later.
                    response.code in 400..499 && response.code != 429 ->
                        ReportResult.Failed(retryable = false, detail = "HTTP ${response.code}")
                    else -> ReportResult.Failed(retryable = true, detail = "HTTP ${response.code}")
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            ReportResult.Failed(retryable = true, detail = e.javaClass.simpleName)
        }
    }

    private companion object {
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        private val defaultClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .writeTimeout(15, TimeUnit.SECONDS)
                .build()
        }
    }
}
