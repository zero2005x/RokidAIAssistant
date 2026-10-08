package io.github.zero2005x.glassesaicompanion.ai.catalog

import io.github.zero2005x.glassesaicompanion.data.AiProvider

/**
 * A single model entry in a provider's catalog.
 *
 * @param id exact model ID sent to the API
 * @param displayName human readable name
 * @param provider owning provider; must match the catalog this entry came from
 * @param capabilities model-level capability flags (never name-inferred)
 * @param status stable / preview / deprecated / legacy
 * @param source where this entry came from (live API, cache, fallback, manual)
 * @param description short user-facing description
 */
data class ModelInfo(
    val id: String,
    val displayName: String,
    val provider: AiProvider,
    val capabilities: ModelCapabilities = ModelCapabilities.TEXT_ONLY,
    val status: ModelStatus = ModelStatus.STABLE,
    val source: CatalogSource = CatalogSource.FALLBACK,
    val description: String = ""
) {
    init {
        // A blank id would silently propagate into request bodies and only
        // fail at the network layer; fail fast at construction instead.
        require(id.isNotBlank()) { "ModelInfo id must not be blank" }
    }

    /** Stable identity of a model, independent of status/source/description. */
    val identityKey: Pair<AiProvider, String>
        get() = provider to id

    /**
     * True when this entry may be auto-selected as the provider default.
     * This is about *auto-selection* only — it must not be used to drop a
     * user's previously chosen preview/legacy model.
     */
    val isSelectableDefault: Boolean
        get() = status == ModelStatus.STABLE

    fun withSource(newSource: CatalogSource): ModelInfo = copy(source = newSource)
}
