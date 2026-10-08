package io.github.zero2005x.glassesaicompanion.ai.catalog

import io.github.zero2005x.glassesaicompanion.data.AiProvider

/**
 * Static, data-driven description of a provider. [ProviderRegistry] holds the
 * single source of truth; service construction and catalog fetching read from
 * here instead of growing `when` blocks.
 *
 * @property id owning provider enum entry
 * @property displayName human readable provider name
 * @property defaultBaseUrl base URL used when the user supplies none
 * @property protocol wire protocol spoken by the provider
 * @property catalogFormat response format of the models-list endpoint
 *   ([CatalogFormat.NONE] when the provider has no usable models endpoint)
 * @property requiresApiKey true when requests cannot be made without a key
 * @property allowsCustomBaseUrl true when the user may override [defaultBaseUrl]
 * @property supportsRegionalEndpoint true when the base URL may be rewritten
 *   for a region-specific host
 * @property fallbackDocSource name of the official documentation used to verify
 *   the fallback model list (see [FallbackModelCatalog.LAST_VERIFIED_DATE])
 * @property modelsEndpointPath relative path (no leading '/') appended to the
 *   base URL for the models list (null when the provider has no usable models
 *   endpoint)
 */
data class ProviderDescriptor(
    val id: AiProvider,
    val displayName: String,
    val defaultBaseUrl: String,
    val protocol: ApiProtocol,
    val catalogFormat: CatalogFormat,
    val requiresApiKey: Boolean,
    val allowsCustomBaseUrl: Boolean,
    val fallbackDocSource: String,
    val modelsEndpointPath: String? = null,
    val supportsRegionalEndpoint: Boolean = false
) {
    init {
        require(defaultBaseUrl.isNotBlank()) { "defaultBaseUrl must not be blank for $id" }
        require((catalogFormat == CatalogFormat.NONE) == (modelsEndpointPath == null)) {
            "$id: catalogFormat=$catalogFormat is inconsistent with modelsEndpointPath=$modelsEndpointPath"
        }
        modelsEndpointPath?.let {
            require(it.isNotBlank() && !it.startsWith("/")) {
                "$id: modelsEndpointPath must be a non-blank relative path without a leading '/'"
            }
        }
    }

    val hasRemoteCatalog: Boolean
        get() = catalogFormat != CatalogFormat.NONE && modelsEndpointPath != null
}
