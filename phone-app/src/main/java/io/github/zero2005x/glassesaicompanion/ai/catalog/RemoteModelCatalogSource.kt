package io.github.zero2005x.glassesaicompanion.ai.catalog

import io.github.zero2005x.glassesaicompanion.data.AiProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Fetches the live model list from a provider's Models endpoint.
 *
 * A 404 from the models endpoint is NOT treated as an authentication failure;
 * it simply means the provider has no usable catalog API, in which case the
 * repository falls back to cache/fallback.
 */
class RemoteModelCatalogSource(
    private val client: OkHttpClient = sharedClient,
    /** Test hook: overrides the descriptor's base URL. */
    private val baseUrlOverride: String? = null
) {

    companion object {
        /** Shared client: one connection pool/dispatcher for all instances. */
        val sharedClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .build()
        }
    }

    /**
     * @return parsed live models (may be empty if the provider lists none)
     * @throws ProviderApiException on HTTP or network failure
     */
    suspend fun fetchModels(
        provider: AiProvider,
        apiKey: String,
        baseUrl: String? = null
    ): List<ModelInfo> = withContext(Dispatchers.IO) {
        val descriptor = ProviderRegistry.descriptorFor(provider)
        val modelsPath = descriptor.modelsEndpointPath
        if (!descriptor.hasRemoteCatalog || modelsPath == null) {
            throw ProviderApiException(
                ProviderErrorKind.INVALID_REQUEST,
                safeMessage = "$provider has no remote catalog"
            )
        }

        val root = (baseUrlOverride ?: baseUrl ?: descriptor.defaultBaseUrl).trimEnd('/')
        val url = "$root/${modelsPath.trimStart('/')}"

        val requestBuilder = Request.Builder().url(url).get()
        applyAuth(requestBuilder, descriptor.protocol, apiKey)

        val call = client.newCall(requestBuilder.build())
        // OkHttp's blocking execute() ignores coroutine cancellation; cancel
        // the in-flight call when the enclosing coroutine is cancelled.
        val cancellationHandle = coroutineContext.job.invokeOnCompletion { cause ->
            if (cause is CancellationException) call.cancel()
        }
        try {
            call.execute().use { response ->
                val body = response.body?.string()
                if (!response.isSuccessful) {
                    throw ProviderApiException.fromHttpStatus(
                        status = response.code,
                        responseBody = body,
                        retryAfterHeader = response.header("Retry-After")
                    )
                }
                if (body.isNullOrBlank()) {
                    throw ProviderApiException(
                        ProviderErrorKind.UNKNOWN,
                        httpStatus = response.code,
                        safeMessage = "Model list response body was empty"
                    )
                }
                ModelCatalogParsers.parse(provider, descriptor.catalogFormat, body)
            }
        } catch (e: ProviderApiException) {
            // Ordering is load-bearing: ProviderApiException is not an
            // IOException, so it must be rethrown before the branches below.
            throw e
        } catch (e: java.net.SocketTimeoutException) {
            coroutineContext.ensureActive()
            throw ProviderApiException(ProviderErrorKind.TIMEOUT, safeMessage = "Model list request timed out")
        } catch (e: java.io.IOException) {
            // call.cancel() surfaces as an IOException; propagate real
            // cancellation instead of misreporting it as a network failure.
            coroutineContext.ensureActive()
            throw ProviderApiException(
                ProviderErrorKind.NETWORK_UNAVAILABLE,
                safeMessage = ProviderApiException.sanitize(e.message)
            )
        } finally {
            cancellationHandle.dispose()
        }
    }

    private fun applyAuth(requestBuilder: Request.Builder, protocol: ApiProtocol, apiKey: String) {
        if (apiKey.isBlank()) return
        when (protocol) {
            ApiProtocol.GEMINI_GENERATE_CONTENT, ApiProtocol.GEMINI_LIVE ->
                requestBuilder.addHeader("x-goog-api-key", apiKey)
            ApiProtocol.ANTHROPIC_MESSAGES -> {
                requestBuilder.addHeader("x-api-key", apiKey)
                requestBuilder.addHeader("anthropic-version", "2023-06-01")
            }
            else -> requestBuilder.addHeader("Authorization", "Bearer $apiKey")
        }
    }
}
