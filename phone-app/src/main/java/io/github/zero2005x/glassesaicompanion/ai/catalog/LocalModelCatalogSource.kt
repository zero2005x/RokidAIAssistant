package io.github.zero2005x.glassesaicompanion.ai.catalog

import io.github.zero2005x.glassesaicompanion.data.AiProvider
import java.io.File

/**
 * Discovers on-device model files for the [AiProvider.LOCAL_GEMMA] provider.
 *
 * Unlike the network-backed [RemoteModelCatalogSource], this source has no
 * wire protocol: it simply scans the app-private model directory for supported
 * model files (`.task` for MediaPipe LLM Inference, `.gguf` for llama.cpp) and
 * maps each installed file to a [ModelInfo]. Capabilities and display names are
 * enriched from [FallbackModelCatalog] when the file stem matches a known model
 * ID; unknown files still surface so the user can select whatever they placed
 * in the directory.
 *
 * Keeping this on the plain [File] API (no Android dependency) makes the scan
 * logic unit-testable against a temporary directory.
 */
class LocalModelCatalogSource(
    private val modelDirProvider: () -> File?
) {

    /** File extensions recognised as installable local model files. */
    companion object {
        val SUPPORTED_EXTENSIONS = setOf("task", "gguf", "bin")
    }

    /**
     * Scan the model directory and return one [ModelInfo] per installed file.
     * Returns an empty list when the directory is missing or contains no
     * supported model files.
     */
    fun scanInstalledModels(): List<ModelInfo> {
        val dir = modelDirProvider() ?: return emptyList()
        if (!dir.isDirectory) return emptyList()
        val files = dir.listFiles() ?: return emptyList()
        return files
            .asSequence()
            .filter { it.isFile && it.extension.lowercase() in SUPPORTED_EXTENSIONS && it.length() > 0L }
            .sortedBy { it.name.lowercase() }
            .map { toModelInfo(it) }
            .toList()
    }

    private fun toModelInfo(file: File): ModelInfo {
        val stem = file.nameWithoutExtension
        val known = FallbackModelCatalog.find(AiProvider.LOCAL_GEMMA, stem)
        val capabilities = known?.capabilities
            ?: ModelCapabilityResolver.providerDefault(AiProvider.LOCAL_GEMMA)
        return ModelInfo(
            id = stem,
            displayName = known?.displayName ?: stem,
            provider = AiProvider.LOCAL_GEMMA,
            capabilities = capabilities,
            status = ModelStatus.STABLE,
            // Installed-on-device is the authoritative "currently available"
            // state, mirroring how LIVE means "confirmed available now".
            source = CatalogSource.LIVE,
            description = known?.description ?: "Installed local model file: ${file.name}"
        )
    }
}
