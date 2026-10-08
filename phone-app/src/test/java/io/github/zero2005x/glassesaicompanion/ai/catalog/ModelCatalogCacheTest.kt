package io.github.zero2005x.glassesaicompanion.ai.catalog

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.github.zero2005x.glassesaicompanion.data.AiProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ModelCatalogCacheTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val prefs = context.getSharedPreferences("rokid_model_catalog_cache", Context.MODE_PRIVATE)

    @Before
    fun clearStorage() {
        prefs.edit().clear().commit()
    }

    @Test
    fun `persisted catalog survives reopening with every capability and timestamp`() {
        val model = ModelInfo(
            id = "test-model", displayName = "Test model", provider = AiProvider.OPENAI,
            capabilities = ModelCapabilities(
                textInput = false, textOutput = false, imageInput = true,
                audioInput = true, audioOutput = true, streaming = true,
                toolCalling = true, structuredOutput = true, reasoning = true,
                realtime = true, transcription = true,
                maxContextTokens = 128_000L, maxOutputTokens = 16_384L
            ),
            status = ModelStatus.PREVIEW, source = CatalogSource.LIVE,
            description = "Persisted model description"
        )
        SharedPrefsModelCatalogCache(context).write(AiProvider.OPENAI, CachedCatalog(listOf(model), 1234L))

        val restored = SharedPrefsModelCatalogCache(context).read(AiProvider.OPENAI)!!
        assertThat(restored.fetchedAtEpochMs).isEqualTo(1234L)
        assertThat(restored.models).containsExactly(model.withSource(CatalogSource.CACHED))
    }

    @Test
    fun `clearing a provider preserves other catalogs and unknown provider returns null`() {
        val cache = SharedPrefsModelCatalogCache(context)
        assertThat(cache.read(AiProvider.OPENAI)).isNull()
        cache.write(AiProvider.OPENAI, CachedCatalog(emptyList(), 10L))
        cache.write(AiProvider.GEMINI, CachedCatalog(emptyList(), 20L))
        cache.clear(AiProvider.OPENAI)
        assertThat(cache.read(AiProvider.OPENAI)).isNull()
        assertThat(cache.read(AiProvider.GEMINI)).isEqualTo(CachedCatalog(emptyList(), 20L))
    }

    @Test
    fun `corrupt JSON is evicted and missing models are treated as a cache miss`() {
        val cache = SharedPrefsModelCatalogCache(context)
        prefs.edit().putString("catalog_openai", "not JSON").commit()
        assertThat(cache.read(AiProvider.OPENAI)).isNull()
        assertThat(prefs.contains("catalog_openai")).isFalse()
        prefs.edit().putString("catalog_openai", "{}").commit()
        assertThat(cache.read(AiProvider.OPENAI)).isNull()
    }

    @Test
    fun `malformed model entries are skipped and optional fields use safe defaults`() {
        prefs.edit().putString("catalog_openai", """{
            "models": [null, 123, {}, {"id":" "},
                {"id":"valid-model", "status":"future-status", "displayName":""}]
        }""").commit()
        val restored = SharedPrefsModelCatalogCache(context).read(AiProvider.OPENAI)!!
        assertThat(restored.fetchedAtEpochMs).isEqualTo(0L)
        assertThat(restored.models).hasSize(1)
        val model = restored.models.single()
        assertThat(model.id).isEqualTo("valid-model")
        assertThat(model.displayName).isEqualTo("valid-model")
        assertThat(model.status).isEqualTo(ModelStatus.UNKNOWN)
        assertThat(model.capabilities.textInput).isTrue()
        assertThat(model.capabilities.textOutput).isTrue()
        assertThat(model.capabilities.imageInput).isFalse()
        assertThat(model.capabilities.maxContextTokens).isNull()
        assertThat(model.capabilities.maxOutputTokens).isNull()
    }

    @Test
    fun `in memory cache copies the input list and clears only the selected provider`() {
        val cache = InMemoryModelCatalogCache()
        val models = mutableListOf(ModelInfo("id", "name", AiProvider.OPENAI))
        cache.write(AiProvider.OPENAI, CachedCatalog(models, 42L))
        cache.write(AiProvider.GEMINI, CachedCatalog(emptyList(), 99L))
        models.clear()
        assertThat(cache.read(AiProvider.OPENAI)!!.models).hasSize(1)
        cache.clear(AiProvider.OPENAI)
        assertThat(cache.read(AiProvider.OPENAI)).isNull()
        assertThat(cache.read(AiProvider.GEMINI)!!.fetchedAtEpochMs).isEqualTo(99L)
    }
}
