package io.github.zero2005x.glassesaicompanion.ai.catalog

import android.content.Context
import android.util.Log
import io.github.zero2005x.glassesaicompanion.data.AiProvider
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/** A cached catalog page for one provider. */
data class CachedCatalog(
    val models: List<ModelInfo>,
    val fetchedAtEpochMs: Long
)

/** Persistence abstraction so the repository is unit-testable without Android. */
interface ModelCatalogCache {
    fun read(provider: AiProvider): CachedCatalog?
    fun write(provider: AiProvider, catalog: CachedCatalog)
    fun clear(provider: AiProvider)
}

/** Volatile cache, useful for tests and as a last-resort store. */
class InMemoryModelCatalogCache : ModelCatalogCache {
    private val data = mutableMapOf<AiProvider, CachedCatalog>()

    @Synchronized
    override fun read(provider: AiProvider): CachedCatalog? = data[provider]

    @Synchronized
    override fun write(provider: AiProvider, catalog: CachedCatalog) {
        // Defensive copy: the caller may keep and mutate the supplied list.
        data[provider] = catalog.copy(models = catalog.models.toList())
    }

    @Synchronized
    override fun clear(provider: AiProvider) {
        data.remove(provider)
    }
}

/**
 * SharedPreferences-backed cache. Model lists contain no secrets, so they are
 * stored in a dedicated non-encrypted prefs file (API keys never go here).
 */
class SharedPrefsModelCatalogCache(context: Context) : ModelCatalogCache {

    // Normalise to the application context so a retained cache cannot leak an
    // Activity/Fragment context passed by a caller.
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun read(provider: AiProvider): CachedCatalog? {
        val raw = prefs.getString(keyFor(provider), null) ?: return null
        return try {
            val json = JSONObject(raw)
            val models = mutableListOf<ModelInfo>()
            val arr = json.optJSONArray("models") ?: return null
            for (i in 0 until arr.length()) {
                arr.optJSONObject(i)?.let { obj ->
                    deserializeModel(obj, provider)?.let { models += it }
                }
            }
            CachedCatalog(models, json.optLong("fetchedAt", 0L))
        } catch (e: Exception) {
            Log.w(TAG, "Corrupt catalog cache for $provider, dropping entry", e)
            clear(provider)
            null
        }
    }

    override fun write(provider: AiProvider, catalog: CachedCatalog) {
        val arr = JSONArray()
        catalog.models.forEach { arr.put(serializeModel(it)) }
        val json = JSONObject()
            .put("fetchedAt", catalog.fetchedAtEpochMs)
            .put("models", arr)
        prefs.edit().putString(keyFor(provider), json.toString()).apply()
    }

    override fun clear(provider: AiProvider) {
        prefs.edit().remove(keyFor(provider)).apply()
    }

    private fun keyFor(provider: AiProvider) = "catalog_${provider.name.lowercase(Locale.ROOT)}"

    private fun serializeModel(model: ModelInfo): JSONObject {
        val caps = model.capabilities
        return JSONObject()
            .put("id", model.id)
            .put("displayName", model.displayName)
            .put("status", model.status.name)
            .put("description", model.description)
            .put("capabilities", JSONObject().apply {
                put("textInput", caps.textInput)
                put("textOutput", caps.textOutput)
                put("imageInput", caps.imageInput)
                put("audioInput", caps.audioInput)
                put("audioOutput", caps.audioOutput)
                put("streaming", caps.streaming)
                put("toolCalling", caps.toolCalling)
                put("structuredOutput", caps.structuredOutput)
                put("reasoning", caps.reasoning)
                put("realtime", caps.realtime)
                put("transcription", caps.transcription)
                caps.maxContextTokens?.let { put("maxContextTokens", it) }
                caps.maxOutputTokens?.let { put("maxOutputTokens", it) }
            })
    }

    /** Returns null for entries that must be dropped (e.g. missing/blank id). */
    private fun deserializeModel(json: JSONObject, provider: AiProvider): ModelInfo? {
        val id = json.optString("id")
        if (id.isBlank()) return null
        val capsJson = json.optJSONObject("capabilities") ?: JSONObject()
        val caps = ModelCapabilities(
            textInput = capsJson.optBoolean("textInput", true),
            textOutput = capsJson.optBoolean("textOutput", true),
            imageInput = capsJson.optBoolean("imageInput"),
            audioInput = capsJson.optBoolean("audioInput"),
            audioOutput = capsJson.optBoolean("audioOutput"),
            streaming = capsJson.optBoolean("streaming"),
            toolCalling = capsJson.optBoolean("toolCalling"),
            structuredOutput = capsJson.optBoolean("structuredOutput"),
            reasoning = capsJson.optBoolean("reasoning"),
            realtime = capsJson.optBoolean("realtime"),
            transcription = capsJson.optBoolean("transcription"),
            maxContextTokens = if (!capsJson.isNull("maxContextTokens")) capsJson.optLong("maxContextTokens") else null,
            maxOutputTokens = if (!capsJson.isNull("maxOutputTokens")) capsJson.optLong("maxOutputTokens") else null
        )
        val status = try {
            ModelStatus.valueOf(json.optString("status", ModelStatus.UNKNOWN.name))
        } catch (e: Exception) {
            ModelStatus.UNKNOWN
        }
        return ModelInfo(
            id = id,
            displayName = json.optString("displayName").ifBlank { id },
            provider = provider,
            capabilities = caps,
            status = status,
            source = CatalogSource.CACHED,
            description = json.optString("description")
        )
    }

    companion object {
        private const val TAG = "ModelCatalogCache"
        private const val PREFS_NAME = "rokid_model_catalog_cache"
    }
}
