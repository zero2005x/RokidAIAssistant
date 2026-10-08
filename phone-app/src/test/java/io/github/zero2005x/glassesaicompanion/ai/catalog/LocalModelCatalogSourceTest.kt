package io.github.zero2005x.glassesaicompanion.ai.catalog

import io.github.zero2005x.glassesaicompanion.data.AiProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * [LocalModelCatalogSource] scans the app-private model directory for installed
 * model files and maps them to [ModelInfo], enriching known IDs from the
 * verified fallback catalog. Pure [File] logic — no Android dependency.
 */
class LocalModelCatalogSourceTest {

    private fun tempDir(): File = Files.createTempDirectory("localmodels").toFile()

    @Test
    fun `missing directory yields an empty list`() {
        val source = LocalModelCatalogSource(modelDirProvider = { File("/definitely/not/here") })
        assertThat(source.scanInstalledModels()).isEmpty()
    }

    @Test
    fun `null directory provider yields an empty list`() {
        val source = LocalModelCatalogSource(modelDirProvider = { null })
        assertThat(source.scanInstalledModels()).isEmpty()
    }

    @Test
    fun `only supported non-empty model files are surfaced`() {
        val dir = tempDir()
        File(dir, "gemma-3n-E2B-it.task").writeBytes(ByteArray(32))
        File(dir, "my-model.gguf").writeBytes(ByteArray(8))
        File(dir, "notes.txt").writeText("ignore me")   // unsupported extension
        File(dir, "empty.task").writeBytes(ByteArray(0)) // zero-length skipped
        val source = LocalModelCatalogSource(modelDirProvider = { dir })

        val ids = source.scanInstalledModels().map { it.id }
        assertThat(ids).containsExactly("gemma-3n-E2B-it", "my-model")
    }

    @Test
    fun `known model file inherits verified display name and capabilities`() {
        val dir = tempDir()
        File(dir, "gemma-3n-E2B-it.task").writeBytes(ByteArray(32))
        val source = LocalModelCatalogSource(modelDirProvider = { dir })

        val model = source.scanInstalledModels().single()
        assertThat(model.provider).isEqualTo(AiProvider.LOCAL_GEMMA)
        assertThat(model.displayName).isEqualTo("Gemma 3n E2B (on-device)")
        assertThat(model.capabilities.streaming).isTrue()
        assertThat(model.source).isEqualTo(CatalogSource.LIVE)
    }

    @Test
    fun `unknown model file still surfaces with conservative defaults`() {
        val dir = tempDir()
        File(dir, "some-custom-model.gguf").writeBytes(ByteArray(8))
        val source = LocalModelCatalogSource(modelDirProvider = { dir })

        val model = source.scanInstalledModels().single()
        assertThat(model.id).isEqualTo("some-custom-model")
        assertThat(model.displayName).isEqualTo("some-custom-model")
        assertThat(model.capabilities.imageInput).isFalse()
    }
}
