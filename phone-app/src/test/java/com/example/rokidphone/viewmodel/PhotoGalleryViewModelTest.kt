package com.example.rokidphone.viewmodel

import android.app.Application
import android.content.Intent
import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import com.example.rokidphone.service.photo.PhotoData
import com.example.rokidphone.service.photo.PhotoRepository
import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.unmockkAll
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class PhotoGalleryViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private val scope = TestScope(dispatcher)
    private val history = MutableStateFlow(emptyList<PhotoData>())
    private lateinit var application: Application
    private lateinit var photoDir: File

    private fun photo(id: String, timestamp: Long = 0, name: String = "$id.jpg") = PhotoData(
        id = id, filePath = File(photoDir, name).path, timestamp = timestamp,
        width = 640, height = 480, sizeBytes = 3, transferTimeMs = 0
    )

    private fun storedPhoto(id: String, timestamp: Long = 0): PhotoData =
        photo(id, timestamp).also { File(it.filePath).writeBytes(byteArrayOf(1, 2, 3)) }

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        application = ApplicationProvider.getApplicationContext()
        photoDir = File(application.filesDir, "glasses_photos").apply { mkdirs() }
        mockkConstructor(PhotoRepository::class)
        every { anyConstructed<PhotoRepository>().photoHistory } returns history
        coEvery { anyConstructed<PhotoRepository>().deletePhoto(any()) } returns Unit
        coEvery { anyConstructed<PhotoRepository>().clearAll() } returns Unit
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        unmockkAll()
    }

    private fun viewModel() = PhotoGalleryViewModel(application)

    @Test
    fun `photos are grouped by capture date, newest day first`() = scope.runTest {
        val day1 = 1_700_000_000_000 // 2023-11-14 UTC
        val day2 = day1 + 24 * 60 * 60 * 1000
        history.value = listOf(photo("a", day1), photo("b", day2), photo("c", day1))
        val model = viewModel()
        // groupedPhotos and photoCount are WhileSubscribed, so they stay at their
        // initial value until something collects them.
        val collectors = kotlinx.coroutines.CoroutineScope(dispatcher)
        collectors.launch { model.groupedPhotos.collect { } }
        collectors.launch { model.photoCount.collect { } }
        advanceUntilIdle()

        assertThat(model.photos.value).hasSize(3)
        assertThat(model.photoCount.value).isEqualTo(3)
        val grouped = model.groupedPhotos.value
        assertThat(grouped).hasSize(2)
        assertThat(grouped.keys.first()).isGreaterThan(grouped.keys.last())
        assertThat(grouped.values.flatten().map { it.id }).containsExactly("a", "b", "c")
        collectors.cancel()
    }

    @Test
    fun `the detail photo is opened and closed through the ui state`() = scope.runTest {
        val model = viewModel()
        val target = photo("a")

        model.openPhotoDetail(target)
        assertThat(model.uiState.value.currentDetailPhoto).isEqualTo(target)
        model.closePhotoDetail()
        assertThat(model.uiState.value.currentDetailPhoto).isNull()
    }

    @Test
    fun `selection mode follows the selected set`() = scope.runTest {
        history.value = listOf(photo("a"), photo("b"))
        val model = viewModel()
        advanceUntilIdle()

        model.toggleSelectionMode()
        assertThat(model.uiState.value.isSelectionMode).isTrue()
        model.toggleSelectionMode()
        assertThat(model.uiState.value.isSelectionMode).isFalse()

        // Selecting a photo enters selection mode; deselecting the last one leaves it.
        model.togglePhotoSelection("a")
        assertThat(model.uiState.value.selectedPhotos).containsExactly("a")
        assertThat(model.uiState.value.isSelectionMode).isTrue()
        model.togglePhotoSelection("a")
        assertThat(model.uiState.value.selectedPhotos).isEmpty()
        assertThat(model.uiState.value.isSelectionMode).isFalse()

        model.selectAll()
        assertThat(model.uiState.value.selectedPhotos).containsExactly("a", "b")
        assertThat(model.uiState.value.isSelectionMode).isTrue()
        model.clearSelection()
        assertThat(model.uiState.value.selectedPhotos).isEmpty()
        assertThat(model.uiState.value.isSelectionMode).isFalse()
    }

    @Test
    fun `confirmation dialogs are shown and hidden`() = scope.runTest {
        val model = viewModel()

        model.showDeleteConfirmDialog()
        assertThat(model.uiState.value.showDeleteConfirmDialog).isTrue()
        model.hideDeleteConfirmDialog()
        assertThat(model.uiState.value.showDeleteConfirmDialog).isFalse()

        model.showClearAllDialog()
        assertThat(model.uiState.value.showClearAllDialog).isTrue()
        model.hideClearAllDialog()
        assertThat(model.uiState.value.showClearAllDialog).isFalse()
    }

    @Test
    fun `deleting one photo clears it from the detail view and the selection`() = scope.runTest {
        val target = photo("a")
        history.value = listOf(target, photo("b"))
        val model = viewModel()
        advanceUntilIdle()
        model.openPhotoDetail(target)
        model.togglePhotoSelection("a")
        model.togglePhotoSelection("b")

        model.deletePhoto(target)
        advanceUntilIdle()

        coVerify { anyConstructed<PhotoRepository>().deletePhoto(target) }
        assertThat(model.uiState.value.currentDetailPhoto).isNull()
        assertThat(model.uiState.value.selectedPhotos).containsExactly("b")
        assertThat(model.uiState.value.isLoading).isFalse()
    }

    @Test
    fun `deleting the selection removes exactly the selected photos`() = scope.runTest {
        val first = photo("a")
        val second = photo("b")
        history.value = listOf(first, second, photo("c"))
        val model = viewModel()
        advanceUntilIdle()
        model.togglePhotoSelection("a")
        model.togglePhotoSelection("b")
        model.showDeleteConfirmDialog()

        model.deleteSelectedPhotos()
        advanceUntilIdle()

        coVerify { anyConstructed<PhotoRepository>().deletePhoto(first) }
        coVerify { anyConstructed<PhotoRepository>().deletePhoto(second) }
        coVerify(exactly = 2) { anyConstructed<PhotoRepository>().deletePhoto(any()) }
        assertThat(model.uiState.value.selectedPhotos).isEmpty()
        assertThat(model.uiState.value.isSelectionMode).isFalse()
        assertThat(model.uiState.value.showDeleteConfirmDialog).isFalse()
    }

    @Test
    fun `clearing the gallery empties the selection`() = scope.runTest {
        history.value = listOf(photo("a"))
        val model = viewModel()
        advanceUntilIdle()
        model.selectAll()
        model.showClearAllDialog()

        model.clearAllPhotos()
        advanceUntilIdle()

        coVerify { anyConstructed<PhotoRepository>().clearAll() }
        assertThat(model.uiState.value.selectedPhotos).isEmpty()
        assertThat(model.uiState.value.isSelectionMode).isFalse()
        assertThat(model.uiState.value.showClearAllDialog).isFalse()
    }

    @Test
    fun `a delete already in flight is not started again`() = scope.runTest {
        history.value = listOf(photo("a"))
        val model = viewModel()
        advanceUntilIdle()
        // Leave a delete mid-flight by holding the loading flag, the same state the
        // first launch installs before it suspends on the repository.
        PhotoGalleryViewModel::class.java.getDeclaredField("_uiState")
            .apply { isAccessible = true }
            .let {
                @Suppress("UNCHECKED_CAST")
                val state = it.get(model) as MutableStateFlow<PhotoGalleryUiState>
                state.value = state.value.copy(isLoading = true)
            }

        model.deletePhoto(photo("a"))
        model.deleteSelectedPhotos()
        model.clearAllPhotos()
        advanceUntilIdle()

        coVerify(exactly = 0) { anyConstructed<PhotoRepository>().deletePhoto(any()) }
        coVerify(exactly = 0) { anyConstructed<PhotoRepository>().clearAll() }
        assertThat(model.uiState.value.isLoading).isTrue()
    }

    @Test
    fun `bitmap loading is delegated to the repository`() = scope.runTest {
        val bitmap = mockk<Bitmap>()
        val target = photo("a")
        coEvery { anyConstructed<PhotoRepository>().getBitmap(target, 512) } returns bitmap
        coEvery { anyConstructed<PhotoRepository>().getBitmap(target, null) } returns null
        val model = viewModel()

        assertThat(model.loadBitmap(target, 512)).isSameInstanceAs(bitmap)
        assertThat(model.loadBitmap(target)).isNull()
    }

    /**
     * Sharing needs a real FileProvider grant, which Robolectric cannot resolve against
     * its synthetic data directory, so only the guards that run before the grant are
     * asserted here: a photo whose file is gone, and an empty selection, share nothing.
     */
    @Test
    fun `a photo whose file is gone is not shared`() = scope.runTest {
        history.value = listOf(photo("missing"))
        val model = viewModel()
        advanceUntilIdle()
        val intents = mutableListOf<Intent>()

        model.sharePhoto(photo("missing")) { intents += it }
        advanceUntilIdle()

        assertThat(intents).isEmpty()
    }

    @Test
    fun `an empty or unreadable selection is not shared`() = scope.runTest {
        history.value = listOf(photo("gone"))
        val model = viewModel()
        advanceUntilIdle()
        val intents = mutableListOf<Intent>()

        // Nothing selected.
        model.shareSelectedPhotos { intents += it }
        advanceUntilIdle()
        assertThat(intents).isEmpty()

        // Selected, but the file is no longer on disk.
        model.togglePhotoSelection("gone")
        model.shareSelectedPhotos { intents += it }
        advanceUntilIdle()
        assertThat(intents).isEmpty()
    }
}
