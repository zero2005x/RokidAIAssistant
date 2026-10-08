package io.github.zero2005x.glassesaicompanion.ai.catalog

import io.github.zero2005x.glassesaicompanion.data.AiProvider
import io.github.zero2005x.glassesaicompanion.testutil.MockWebServerRule
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import okhttp3.Headers.Companion.headersOf
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Four-tier catalog behaviour: live → cache → fallback → manual.
 */
@RunWith(RobolectricTestRunner::class)
class ModelCatalogRepositoryTest {

    @get:Rule
    val mockServer = MockWebServerRule()

    private fun jsonResponse(body: String, code: Int = 200) = MockResponse(
        code = code,
        body = body,
        headers = headersOf("Content-Type", "application/json")
    )

    private fun repository(
        cache: ModelCatalogCache,
        clock: () -> Long = { System.currentTimeMillis() }
    ) = ModelCatalogRepository(
        remote = RemoteModelCatalogSource(baseUrlOverride = mockServer.baseUrl),
        cache = cache,
        clock = clock
    )

    @Test
    fun `live fetch succeeds and is cached`() = runTest {
        val cache = InMemoryModelCatalogCache()
        val repo = repository(cache)
        mockServer.server.enqueue(
            jsonResponse("""{"data": [{"id": "deepseek-v4-flash"}, {"id": "deepseek-v4-pro"}]}""")
        )

        val snapshot = repo.getCatalog(AiProvider.DEEPSEEK, apiKey = "key", forceRefresh = true)

        assertThat(snapshot.source).isEqualTo(CatalogSource.LIVE)
        assertThat(snapshot.models.map { it.id }).containsExactly("deepseek-v4-flash", "deepseek-v4-pro")
        // Second call must be served from cache without any network request.
        val cached = repo.getCatalog(AiProvider.DEEPSEEK, apiKey = "key")
        assertThat(cached.source).isEqualTo(CatalogSource.CACHED)
        assertThat(mockServer.server.requestCount).isEqualTo(1)
    }

    @Test
    fun `remote failure falls back to previous successful cache`() = runTest {
        val cache = InMemoryModelCatalogCache()
        val repo = repository(cache)
        mockServer.server.enqueue(jsonResponse("""{"data": [{"id": "deepseek-v4-flash"}]}"""))
        repo.getCatalog(AiProvider.DEEPSEEK, apiKey = "key", forceRefresh = true)

        mockServer.server.enqueue(jsonResponse("""{"error": {"message": "boom"}}""", code = 500))
        val snapshot = repo.getCatalog(AiProvider.DEEPSEEK, apiKey = "key", forceRefresh = true)

        assertThat(snapshot.source).isEqualTo(CatalogSource.CACHED)
        assertThat(snapshot.models.map { it.id }).containsExactly("deepseek-v4-flash")
        assertThat(snapshot.remoteError).isNotNull()
    }

    @Test
    fun `no cache falls back to verified static catalog`() = runTest {
        val repo = repository(InMemoryModelCatalogCache())
        mockServer.server.enqueue(jsonResponse("""{"error": {"message": "down"}}""", code = 503))

        val snapshot = repo.getCatalog(AiProvider.DEEPSEEK, apiKey = "key", forceRefresh = true)

        assertThat(snapshot.source).isEqualTo(CatalogSource.FALLBACK)
        assertThat(snapshot.models.map { it.id }).contains("deepseek-v4-flash")
        assertThat(snapshot.remoteError).isNotNull()
    }

    @Test
    fun `blank api key skips network and uses cache or fallback`() = runTest {
        val repo = repository(InMemoryModelCatalogCache())

        val snapshot = repo.getCatalog(AiProvider.OPENAI, apiKey = "")

        assertThat(snapshot.source).isEqualTo(CatalogSource.FALLBACK)
        assertThat(mockServer.server.requestCount).isEqualTo(0)
        assertThat(snapshot.models.map { it.id }).contains("gpt-5.6")
    }

    @Test
    fun `expired cache triggers refresh while fresh cache does not`() = runTest {
        val cache = InMemoryModelCatalogCache()
        var now = 1_000_000L
        val repo = repository(cache) { now }
        repo.ttlMs = 1_000L

        // Seed cache as fresh.
        cache.write(
            AiProvider.GROQ,
            CachedCatalog(
                listOf(FallbackModelCatalog.modelsFor(AiProvider.GROQ).first()),
                fetchedAtEpochMs = now
            )
        )
        val fresh = repo.getCatalog(AiProvider.GROQ, apiKey = "key")
        assertThat(fresh.source).isEqualTo(CatalogSource.CACHED)
        assertThat(mockServer.server.requestCount).isEqualTo(0)

        // Expire the cache → a network refresh is attempted.
        now += 10_000L
        mockServer.server.enqueue(jsonResponse("""{"data": [{"id": "llama-3.3-70b-versatile"}]}"""))
        val refreshed = repo.getCatalog(AiProvider.GROQ, apiKey = "key")
        assertThat(refreshed.source).isEqualTo(CatalogSource.LIVE)
        assertThat(mockServer.server.requestCount).isEqualTo(1)
    }

    @Test
    fun `manual model remains selectable and keeps conservative capabilities`() = runTest {
        val repo = repository(InMemoryModelCatalogCache())
        val manual = repo.manualModel(AiProvider.MOONSHOT, "kimi-custom-ft-001")

        assertThat(manual.source).isEqualTo(CatalogSource.MANUAL)
        assertThat(manual.capabilities.textInput).isTrue()

        // A manual selection missing from the catalog is flagged (UI shows a
        // localized warning) but the selection itself is kept.
        val snapshot = repo.getCatalog(AiProvider.MOONSHOT, apiKey = "")
        assertThat(repo.isSelectionAbsent(snapshot, manual.id)).isTrue()
        assertThat(repo.selectionWarning(snapshot, manual.id))
            .isEqualTo("Current selection is not in the latest catalog.")
        // Existing selections are not flagged.
        val listed = snapshot.models.first().id
        assertThat(repo.isSelectionAbsent(snapshot, listed)).isFalse()
        assertThat(repo.selectionWarning(snapshot, listed)).isNull()
    }

    @Test
    fun `provider catalogs are cached independently`() = runTest {
        val cache = InMemoryModelCatalogCache()
        val repo = repository(cache)
        mockServer.server.enqueue(jsonResponse("""{"data": [{"id": "deepseek-v4-flash"}]}"""))
        repo.getCatalog(AiProvider.DEEPSEEK, apiKey = "key", forceRefresh = true)

        // A different provider has no cache → fallback, and DeepSeek cache is intact.
        val other = repo.getCatalog(AiProvider.GROQ, apiKey = "")
        assertThat(other.source).isEqualTo(CatalogSource.FALLBACK)
        val deepseek = repo.getCatalog(AiProvider.DEEPSEEK, apiKey = "")
        assertThat(deepseek.source).isEqualTo(CatalogSource.CACHED)
    }

    @Test
    fun `capabilitiesFor prefers catalog entry then resolver`() = runTest {
        val repo = repository(InMemoryModelCatalogCache())
        val snapshot = repo.getCatalog(AiProvider.XAI, apiKey = "")

        assertThat(repo.capabilitiesFor(snapshot, "grok-4.5").imageInput).isTrue()
        assertThat(repo.capabilitiesFor(snapshot, "grok-4.1-fast").imageInput).isFalse()
        // Unknown ID: provider default (xAI is vision-capable by default).
        assertThat(repo.capabilitiesFor(snapshot, "grok-unknown").imageInput).isTrue()
    }

    // ==================== On-device (LOCAL_GEMMA) provider ====================

    @Test
    fun `local provider without source serves fallback and never hits the network`() = runTest {
        val repo = repository(InMemoryModelCatalogCache())

        val snapshot = repo.getCatalog(AiProvider.LOCAL_GEMMA, apiKey = "ignored", forceRefresh = true)

        assertThat(snapshot.source).isEqualTo(CatalogSource.FALLBACK)
        assertThat(snapshot.models.map { it.id }).contains("gemma-3n-E2B-it")
        assertThat(mockServer.server.requestCount).isEqualTo(0)
    }

    @Test
    fun `local provider surfaces installed model files before fallback entries`() = runTest {
        val dir = java.nio.file.Files.createTempDirectory("localmodels").toFile()
        java.io.File(dir, "gemma-3n-E2B-it.task").writeBytes(ByteArray(16))
        val local = LocalModelCatalogSource(modelDirProvider = { dir })
        val repo = ModelCatalogRepository(
            remote = RemoteModelCatalogSource(baseUrlOverride = mockServer.baseUrl),
            cache = InMemoryModelCatalogCache(),
            localSource = local
        )

        val snapshot = repo.getCatalog(AiProvider.LOCAL_GEMMA)

        assertThat(snapshot.source).isEqualTo(CatalogSource.LIVE)
        val installed = snapshot.models.first()
        assertThat(installed.id).isEqualTo("gemma-3n-E2B-it")
        assertThat(installed.source).isEqualTo(CatalogSource.LIVE)
        // The other verified model is still offered (not yet installed).
        assertThat(snapshot.models.map { it.id }).contains("gemma-3n-E4B-it")
        assertThat(mockServer.server.requestCount).isEqualTo(0)
    }
}
