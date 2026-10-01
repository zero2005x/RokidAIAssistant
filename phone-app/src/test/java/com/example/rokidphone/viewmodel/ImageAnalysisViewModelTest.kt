package com.example.rokidphone.viewmodel

import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import com.example.rokidphone.R
import com.example.rokidphone.data.AiRepository
import com.example.rokidphone.data.ImageAnalysisResult
import com.example.rokidphone.service.photo.PhotoData
import com.example.rokidphone.service.photo.PhotoRepository
import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
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
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class ImageAnalysisViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private val scope = TestScope(dispatcher)
    private val aiRepository = mockk<AiRepository>(relaxed = true)
    private val photoRepository = mockk<PhotoRepository>(relaxed = true)
    private val photoFlow = MutableSharedFlow<PhotoData>(replay = 0, extraBufferCapacity = 8)

    private fun photo(id: String) = PhotoData(
        id = id, filePath = "/photos/$id.jpg", timestamp = 0,
        width = 640, height = 480, sizeBytes = 3, transferTimeMs = 0
    )

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        every { photoRepository.photoFlow } returns photoFlow
        every { aiRepository.isServiceAvailable() } returns true
        every { aiRepository.getCurrentProviderName() } returns "OPENAI"
        every { aiRepository.getCurrentModelId() } returns "gpt-4"
        coEvery { photoRepository.getPhotoBytes(any()) } returns byteArrayOf(1, 2, 3)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        unmockkAll()
    }

    private fun viewModel() = ImageAnalysisViewModel(aiRepository, photoRepository)

    @Test
    fun `service information is published on creation and on refresh`() = scope.runTest {
        val model = viewModel()
        assertThat(model.uiState.value.isServiceAvailable).isTrue()
        assertThat(model.uiState.value.providerName).isEqualTo("OPENAI")
        assertThat(model.uiState.value.modelId).isEqualTo("gpt-4")

        every { aiRepository.isServiceAvailable() } returns false
        every { aiRepository.getCurrentProviderName() } returns "GEMINI"
        model.refreshServiceInfo()

        assertThat(model.uiState.value.isServiceAvailable).isFalse()
        assertThat(model.uiState.value.providerName).isEqualTo("GEMINI")
    }

    @Test
    fun `a successful analysis publishes the description and stamps the photo`() = scope.runTest {
        coEvery { aiRepository.analyzeImage(any<ByteArray>(), any(), any()) } returns
            ImageAnalysisResult.Success("a red bicycle")
        val model = viewModel()
        val target = photo("a")

        model.analyzePhoto(target)
        advanceUntilIdle()

        val state = model.uiState.value.analysisState as ImageAnalysisState.Success
        assertThat(state.description).isEqualTo("a red bicycle")
        assertThat(state.photoData).isEqualTo(target)
        assertThat(target.analysisResult).isEqualTo("a red bicycle")
        assertThat(model.uiState.value.selectedPhoto).isEqualTo(target)
        coVerify { aiRepository.analyzeImage(byteArrayOf(1, 2, 3), AiRepository.AnalysisMode.DESCRIPTION, any()) }
    }

    @Test
    fun `a photo that cannot be read is reported before the provider is called`() = scope.runTest {
        coEvery { photoRepository.getPhotoBytes(any()) } returns null
        val model = viewModel()

        model.analyzePhoto(photo("a"))
        advanceUntilIdle()

        val state = model.uiState.value.analysisState as ImageAnalysisState.Error
        assertThat(state.message)
            .isEqualTo(UiText.Resource(R.string.unable_to_read_photo))
        assertThat(state.canRetry).isTrue()
        coVerify(exactly = 0) { aiRepository.analyzeImage(any<ByteArray>(), any(), any()) }
    }

    @Test
    fun `provider errors and unexpected failures both become error states`() = scope.runTest {
        coEvery { aiRepository.analyzeImage(any<ByteArray>(), any(), any()) } returns
            ImageAnalysisResult.Error("quota exceeded")
        val model = viewModel()

        model.analyzePhoto(photo("a"))
        advanceUntilIdle()
        assertThat((model.uiState.value.analysisState as ImageAnalysisState.Error).message)
            .isEqualTo(UiText.Dynamic("quota exceeded"))

        coEvery { photoRepository.getPhotoBytes(any()) } throws IOException("storage gone")
        model.analyzePhoto(photo("b"))
        advanceUntilIdle()
        assertThat((model.uiState.value.analysisState as ImageAnalysisState.Error).message)
            .isEqualTo(UiText.Dynamic("storage gone"))
    }

    @Test
    fun `the chosen analysis mode is passed to the provider`() = scope.runTest {
        coEvery { aiRepository.analyzeImage(any<ByteArray>(), any(), any()) } returns
            ImageAnalysisResult.Success("text")
        val model = viewModel()

        model.setAnalysisMode(AiRepository.AnalysisMode.OCR)
        assertThat(model.uiState.value.analysisMode).isEqualTo(AiRepository.AnalysisMode.OCR)
        model.analyzePhoto(photo("a"))
        advanceUntilIdle()

        coVerify { aiRepository.analyzeImage(any<ByteArray>(), AiRepository.AnalysisMode.OCR, any()) }
    }

    @Test
    fun `a bitmap analysis reports the answer with the bitmap's dimensions`() = scope.runTest {
        val bitmap = mockk<Bitmap>(relaxed = true)
        every { bitmap.width } returns 1920
        every { bitmap.height } returns 1080
        coEvery { aiRepository.analyzeImage(any<Bitmap>(), any(), any()) } returns
            ImageAnalysisResult.Success("a screenshot")
        val model = viewModel()

        model.analyzeBitmap(bitmap)
        advanceUntilIdle()

        val state = model.uiState.value.analysisState as ImageAnalysisState.Success
        assertThat(state.description).isEqualTo("a screenshot")
        assertThat(state.photoData.width).isEqualTo(1920)
        assertThat(state.photoData.height).isEqualTo(1080)
        assertThat(state.photoData.filePath).isEmpty()
        assertThat(model.uiState.value.selectedPhotoBitmap).isSameInstanceAs(bitmap)
    }

    @Test
    fun `bitmap analysis failures are reported without a photo`() = scope.runTest {
        val bitmap = mockk<Bitmap>(relaxed = true)
        coEvery { aiRepository.analyzeImage(any<Bitmap>(), any(), any()) } returns
            ImageAnalysisResult.Error("unsupported format")
        val model = viewModel()

        model.analyzeBitmap(bitmap)
        advanceUntilIdle()
        var state = model.uiState.value.analysisState as ImageAnalysisState.Error
        assertThat(state.message).isEqualTo(UiText.Dynamic("unsupported format"))
        assertThat(state.photoData).isNull()

        coEvery { aiRepository.analyzeImage(any<Bitmap>(), any(), any()) } throws IOException("offline")
        model.analyzeBitmap(bitmap)
        advanceUntilIdle()
        state = model.uiState.value.analysisState as ImageAnalysisState.Error
        assertThat(state.message).isEqualTo(UiText.Dynamic("offline"))
    }

    @Test
    fun `a received photo joins the recent list and is analysed automatically`() = scope.runTest {
        coEvery { aiRepository.analyzeImage(any<ByteArray>(), any(), any()) } returns
            ImageAnalysisResult.Success("auto")
        val model = viewModel()
        advanceUntilIdle()

        photoFlow.emit(photo("a"))
        advanceUntilIdle()

        assertThat(model.uiState.value.recentPhotos.map { it.id }).containsExactly("a")
        assertThat(model.uiState.value.analysisState).isInstanceOf(ImageAnalysisState.Success::class.java)

        // With auto-analysis off the photo is still recorded, but nothing is sent.
        model.setAutoAnalyzeEnabled(false)
        model.cancelAnalysis()
        photoFlow.emit(photo("b"))
        advanceUntilIdle()
        assertThat(model.uiState.value.recentPhotos.map { it.id }).containsExactly("b", "a").inOrder()
        assertThat(model.uiState.value.analysisState).isEqualTo(ImageAnalysisState.Idle)
    }

    @Test
    fun `the recent list keeps only the newest photos`() = scope.runTest {
        val model = viewModel()
        model.setAutoAnalyzeEnabled(false)
        advanceUntilIdle()

        repeat(12) { photoFlow.emit(photo("p$it")) }
        advanceUntilIdle()

        assertThat(model.uiState.value.recentPhotos).hasSize(10)
        assertThat(model.uiState.value.recentPhotos.first().id).isEqualTo("p11")
        assertThat(model.uiState.value.recentPhotos.last().id).isEqualTo("p2")
    }

    @Test
    fun `retry re-analyses the failed photo, or the selected one`() = scope.runTest {
        coEvery { aiRepository.analyzeImage(any<ByteArray>(), any(), any()) } returns
            ImageAnalysisResult.Error("quota exceeded")
        val model = viewModel()

        model.analyzePhoto(photo("a"))
        advanceUntilIdle()
        coEvery { aiRepository.analyzeImage(any<ByteArray>(), any(), any()) } returns
            ImageAnalysisResult.Success("second time lucky")
        model.retryAnalysis()
        advanceUntilIdle()
        assertThat((model.uiState.value.analysisState as ImageAnalysisState.Success).description)
            .isEqualTo("second time lucky")

        // From a non-error state, retry re-runs the selected photo.
        model.selectPhoto(photo("c"))
        model.cancelAnalysis()
        model.retryAnalysis()
        advanceUntilIdle()
        assertThat((model.uiState.value.analysisState as ImageAnalysisState.Success).photoData.id)
            .isEqualTo("c")
    }

    @Test
    fun `retry does nothing when there is no photo to analyse`() = scope.runTest {
        val model = viewModel()

        model.retryAnalysis()
        advanceUntilIdle()

        assertThat(model.uiState.value.analysisState).isEqualTo(ImageAnalysisState.Idle)
        coVerify(exactly = 0) { aiRepository.analyzeImage(any<ByteArray>(), any(), any()) }
    }

    @Test
    fun `cancelling and resetting return the screen to idle`() = scope.runTest {
        coEvery { aiRepository.analyzeImage(any<ByteArray>(), any(), any()) } returns
            ImageAnalysisResult.Success("done")
        val model = viewModel()

        model.analyzePhoto(photo("a"))
        model.cancelAnalysis()
        advanceUntilIdle()
        assertThat(model.uiState.value.analysisState).isEqualTo(ImageAnalysisState.Idle)

        model.analyzePhoto(photo("a"))
        advanceUntilIdle()
        model.resetState()
        advanceUntilIdle()
        assertThat(model.uiState.value.analysisState).isEqualTo(ImageAnalysisState.Idle)
        assertThat(model.uiState.value.selectedPhoto).isNull()
        assertThat(model.uiState.value.selectedPhotoBitmap).isNull()
    }

    @Test
    fun `ui text resolves both resources and dynamic messages`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        assertThat(UiText.Resource(R.string.analyzing_image).asString(context))
            .isEqualTo(context.getString(R.string.analyzing_image))
        assertThat(UiText.Dynamic("raw message").asString(context)).isEqualTo("raw message")
    }

    @Test
    fun `the factory builds only this view model`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val factory = ImageAnalysisViewModel.Factory(context, photoRepository)

        assertThat(factory.create(ImageAnalysisViewModel::class.java)).isNotNull()
        val failure = runCatching { factory.create(PhoneViewModel::class.java) }.exceptionOrNull()
        assertThat(failure).isInstanceOf(IllegalArgumentException::class.java)
    }
}
