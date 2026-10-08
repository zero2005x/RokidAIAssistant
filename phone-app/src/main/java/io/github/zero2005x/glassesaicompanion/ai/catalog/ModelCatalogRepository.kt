package io.github.zero2005x.glassesaicompanion.ai.catalog

import io.github.zero2005x.glassesaicompanion.data.AiProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Result of resolving a provider's model catalog. */
data class ModelCatalogSnapshot(
    val provider: AiProvider,
    val models: List<ModelInfo>,
    val source: CatalogSource,
    val fetchedAtEpochMs: Long?,
    /** Non-null when the remote fetch failed and an older source is being served. */
    val remoteError: String? = null
) {
    val isLive: Boolean get() = source == CatalogSource.LIVE
}

/**
 * Four-tier model catalog:
 *   remote (live) → on-device cache (24h TTL) → verified static fallback → manual ID.
 *
 * The user's currently selected model is never deleted even if it disappears
 * from the remote list; [selectionWarning] reports the mismatch instead.
 */
class ModelCatalogRepository(
    private val remote: RemoteModelCatalogSource,
    private val cache: ModelCatalogCache,
    private val clock: () -> Long = { System.currentTimeMillis() },
    /**
     * Optional discovery source for on-device ([ApiProtocol.LOCAL_INFERENCE])
     * providers. When present, local providers resolve their catalog from
     * installed model files plus the verified fallback list, never the network.
     */
    private val localSource: LocalModelCatalogSource? = null
) {

    companion object {
        const val DEFAULT_TTL_MS = 24L * 60 * 60 * 1000
    }

    @Volatile
    var ttlMs: Long = DEFAULT_TTL_MS

    /**
     * Get the catalog for [provider].
     *
     * @param apiKey credential for the remote fetch; blank skips the network call
     * @param baseUrl optional endpoint override (Custom provider / regional endpoints)
     * @param forceRefresh bypass the TTL and re-fetch from the network
     */
    suspend fun getCatalog(
        provider: AiProvider,
        apiKey: String = "",
        baseUrl: String? = null,
        forceRefresh: Boolean = false
    ): ModelCatalogSnapshot {
        val descriptor = ProviderRegistry.descriptorFor(provider)
        if (descriptor.protocol == ApiProtocol.LOCAL_INFERENCE) {
            return localSnapshot(provider)
        }
        // Capture the clock once per call so the freshness check, the persisted
        // timestamp and the returned snapshot cannot disagree.
        val now = clock()
        val cached = withContext(Dispatchers.IO) { cache.read(provider) }
        val cacheAge = cached?.let { now - it.fetchedAtEpochMs }
        // A negative age means the device clock moved backwards; treat the
        // entry as stale rather than fresh forever.
        val cacheFresh = cacheAge != null && cacheAge >= 0 && cacheAge < ttlMs

        if (descriptor.hasRemoteCatalog && apiKey.isNotBlank() && (forceRefresh || !cacheFresh)) {
            try {
                val live = remote.fetchModels(provider, apiKey, baseUrl)
                    .map { it.withSource(CatalogSource.LIVE) }
                if (live.isNotEmpty()) {
                    withContext(Dispatchers.IO) { cache.write(provider, CachedCatalog(live, now)) }
                    return ModelCatalogSnapshot(provider, live, CatalogSource.LIVE, now)
                }
                // Successful but empty response: surface it explicitly so
                // callers can distinguish "provider reported no models" from
                // "never fetched".
                return serveStaleOrFallback(provider, cached, "Provider returned an empty model list")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                return serveStaleOrFallback(provider, cached, e.message)
            }
        }

        if (cached != null) {
            return cachedSnapshot(provider, cached)
        }

        return fallbackSnapshot(provider)
    }

    /**
     * Synchronous catalog access for UI first-paint (cache/fallback only, no
     * network). Performs blocking cache I/O — call off the main thread or
     * pre-warm the cache.
     */
    fun getCachedOrFallback(provider: AiProvider): ModelCatalogSnapshot {
        if (ProviderRegistry.descriptorFor(provider).protocol == ApiProtocol.LOCAL_INFERENCE) {
            return localSnapshot(provider)
        }
        val cached = cache.read(provider)
        if (cached != null) {
            return cachedSnapshot(provider, cached)
        }
        return fallbackSnapshot(provider)
    }

    fun invalidate(provider: AiProvider) = cache.clear(provider)

    /**
     * Build a manual-entry model (user typed the ID by hand). Manual entries
     * are appended to the list and remain selectable forever.
     */
    fun manualModel(provider: AiProvider, modelId: String): ModelInfo = ModelInfo(
        id = modelId,
        displayName = modelId,
        provider = provider,
        capabilities = ModelCapabilityResolver.providerDefault(provider),
        status = ModelStatus.STABLE,
        source = CatalogSource.MANUAL,
        description = "Manually entered model ID"
    )

    /**
     * True when the persisted selection is not present in the catalog
     * currently being served. The selection itself is always kept; the UI
     * shows a localized warning for this case (R.string.model_not_in_catalog).
     */
    fun isSelectionAbsent(snapshot: ModelCatalogSnapshot, selectedModelId: String): Boolean {
        if (selectedModelId.isBlank()) return false
        return snapshot.models.none { it.id == selectedModelId }
    }

    fun selectionWarning(snapshot: ModelCatalogSnapshot, selectedModelId: String): String? {
        return if (isSelectionAbsent(snapshot, selectedModelId)) {
            "Current selection is not in the latest catalog."
        } else {
            null
        }
    }

    /** Resolve effective capabilities for a (possibly manual) selection. */
    fun capabilitiesFor(snapshot: ModelCatalogSnapshot, modelId: String): ModelCapabilities {
        snapshot.models.find { it.id == modelId }?.let { return it.capabilities }
        return ModelCapabilityResolver.resolve(snapshot.provider, modelId)
    }

    private fun serveStaleOrFallback(
        provider: AiProvider,
        cached: CachedCatalog?,
        error: String?
    ): ModelCatalogSnapshot {
        if (cached != null) {
            return cachedSnapshot(provider, cached, error)
        }
        return fallbackSnapshot(provider).copy(remoteError = error)
    }

    private fun cachedSnapshot(
        provider: AiProvider,
        cached: CachedCatalog,
        error: String? = null
    ): ModelCatalogSnapshot {
        val models = cached.models.map { it.withSource(CatalogSource.CACHED) }
        return ModelCatalogSnapshot(
            provider, models, CatalogSource.CACHED, cached.fetchedAtEpochMs,
            remoteError = error
        )
    }

    private fun fallbackSnapshot(provider: AiProvider) = ModelCatalogSnapshot(
        provider = provider,
        models = FallbackModelCatalog.modelsFor(provider),
        source = CatalogSource.FALLBACK,
        fetchedAtEpochMs = null
    )

    /**
     * Resolve an on-device provider's catalog: installed model files (scanned
     * from the app-private directory) come first, then verified fallback
     * entries that are not yet installed so the user can see what is available
     * to add. Never touches the network.
     */
    private fun localSnapshot(provider: AiProvider): ModelCatalogSnapshot {
        val installed = localSource?.scanInstalledModels().orEmpty()
        val installedIds = installed.map { it.id }.toSet()
        val notInstalled = FallbackModelCatalog.modelsFor(provider)
            .filter { it.id !in installedIds }
        val models = installed + notInstalled
        val source = if (installed.isNotEmpty()) CatalogSource.LIVE else CatalogSource.FALLBACK
        return ModelCatalogSnapshot(
            provider = provider,
            models = models,
            source = source,
            fetchedAtEpochMs = null
        )
    }
}
