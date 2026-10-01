package com.example.rokidphone.viewmodel

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.example.rokidphone.data.ApiSettings
import com.example.rokidphone.data.SettingsRepository
import com.example.rokidphone.data.db.RecordingEntity
import com.example.rokidphone.data.db.RecordingRepository
import com.example.rokidphone.data.db.RecordingSource
import com.example.rokidphone.data.db.RecordingState
import com.example.rokidphone.data.db.RecordingStatistics
import com.example.rokidphone.service.EnhancedAIService
import com.example.rokidphone.service.ServiceBridge
import com.example.rokidphone.testutil.returnsFailure
import com.example.rokidphone.testutil.returnsSuccess
import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkAll
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class RecordingViewModelTest {

    @get:Rule val temporary = TemporaryFolder()

    private val dispatcher = StandardTestDispatcher()
    private val scope = TestScope(dispatcher)
    private val repository = mockk<RecordingRepository>(relaxed = true)
    private val aiService = mockk<EnhancedAIService>()
    private val recordings = MutableStateFlow(emptyList<RecordingEntity>())
    private val recordingState = MutableStateFlow<RecordingState>(RecordingState.Idle)
    private lateinit var application: Application

    private fun recording(
        id: String,
        title: String = id,
        source: RecordingSource = RecordingSource.PHONE,
        durationMs: Long = 0,
        createdAt: Long = 0,
        isFavorite: Boolean = false,
        transcript: String? = null,
        aiResponse: String? = null,
        notes: String? = null,
        filePath: String = "/does/not/matter"
    ) = RecordingEntity(
        id = id, title = title, filePath = filePath, source = source, durationMs = durationMs,
        createdAt = createdAt, isFavorite = isFavorite, transcript = transcript,
        aiResponse = aiResponse, notes = notes
    )

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        application = ApplicationProvider.getApplicationContext()
        mockkObject(RecordingRepository.Companion, EnhancedAIService.Companion, SettingsRepository.Companion)
        every { RecordingRepository.getInstance(any()) } returns repository
        every { EnhancedAIService.getInstance(any()) } returns aiService
        every { repository.getAllRecordings() } returns recordings
        every { repository.recordingState } returns recordingState
        coEvery { repository.getStatistics() } returns RecordingStatistics(0, 0)
    }

    @After
    fun tearDown() {
        ServiceBridge.reset()
        Dispatchers.resetMain()
        unmockkAll()
    }

    private fun viewModel(): RecordingViewModel =
        RecordingViewModel(application).also { scope.runCurrent() }

    @Test
    fun `initial load publishes recordings, statistics and recording state`() = scope.runTest {
        recordings.value = listOf(recording("a"))
        coEvery { repository.getStatistics() } returns RecordingStatistics(3, 9_000)
        val model = viewModel()
        advanceUntilIdle()

        assertThat(model.uiState.value.isLoading).isFalse()
        assertThat(model.uiState.value.recordings.map { it.id }).containsExactly("a")
        assertThat(model.uiState.value.statistics).isEqualTo(RecordingStatistics(3, 9_000))

        val active = RecordingState.Recording(RecordingSource.PHONE, startTime = 1_000, durationMs = 250)
        recordingState.value = active
        advanceUntilIdle()
        assertThat(model.uiState.value.recordingState).isEqualTo(active)
    }

    @Test
    fun `load and statistics failures surface as errors instead of crashing`() = scope.runTest {
        every { repository.getAllRecordings() } returns flow { throw IOException("db gone") }
        coEvery { repository.getStatistics() } throws IllegalStateException("stats gone")
        val model = viewModel()
        advanceUntilIdle()

        assertThat(model.uiState.value.isLoading).isFalse()
        assertThat(model.uiState.value.error).isAnyOf("db gone", "stats gone")
        model.clearError()
        assertThat(model.uiState.value.error).isNull()
    }

    @Test
    fun `filter, search and sort narrow and order the visible recordings`() = scope.runTest {
        recordings.value = listOf(
            recording("p1", title = "Beta", durationMs = 30, createdAt = 1, transcript = "hello world"),
            recording("g1", title = "alpha", source = RecordingSource.GLASSES, durationMs = 10, createdAt = 3),
            recording("f1", title = "Gamma", durationMs = 20, createdAt = 2, isFavorite = true, notes = "WORLD tour")
        )
        val model = viewModel()
        advanceUntilIdle()

        assertThat(model.uiState.value.filteredRecordings.map { it.id })
            .containsExactly("g1", "f1", "p1").inOrder() // DATE_DESC by default

        model.setFilter(RecordingFilter.PHONE)
        assertThat(model.uiState.value.filteredRecordings.map { it.id }).containsExactly("f1", "p1").inOrder()
        model.setFilter(RecordingFilter.GLASSES)
        assertThat(model.uiState.value.filteredRecordings.map { it.id }).containsExactly("g1")
        model.setFilter(RecordingFilter.FAVORITES)
        assertThat(model.uiState.value.filteredRecordings.map { it.id }).containsExactly("f1")

        model.setFilter(RecordingFilter.ALL)
        model.setSearchQuery("world") // matches transcript and notes, case-insensitively
        assertThat(model.uiState.value.filteredRecordings.map { it.id }).containsExactly("f1", "p1").inOrder()
        model.setSearchQuery("alp") // matches title
        assertThat(model.uiState.value.filteredRecordings.map { it.id }).containsExactly("g1")
        model.setSearchQuery("   ")
        assertThat(model.uiState.value.filteredRecordings).hasSize(3)

        model.setSort(RecordingSort.DATE_ASC)
        assertThat(model.uiState.value.filteredRecordings.map { it.id }).containsExactly("p1", "f1", "g1").inOrder()
        model.setSort(RecordingSort.DURATION_DESC)
        assertThat(model.uiState.value.filteredRecordings.map { it.id }).containsExactly("p1", "f1", "g1").inOrder()
        model.setSort(RecordingSort.DURATION_ASC)
        assertThat(model.uiState.value.filteredRecordings.map { it.id }).containsExactly("g1", "f1", "p1").inOrder()
        model.setSort(RecordingSort.NAME_ASC)
        assertThat(model.uiState.value.filteredRecordings.map { it.id }).containsExactly("g1", "p1", "f1").inOrder()
        model.setSort(RecordingSort.NAME_DESC)
        assertThat(model.uiState.value.filteredRecordings.map { it.id }).containsExactly("f1", "p1", "g1").inOrder()
    }

    @Test
    fun `selection mode tracks ids and is cleared when it is left`() = scope.runTest {
        recordings.value = listOf(recording("a"), recording("b"))
        val model = viewModel()
        advanceUntilIdle()

        model.toggleSelectionMode()
        assertThat(model.uiState.value.isSelectionMode).isTrue()
        model.toggleSelection("a")
        model.toggleSelection("b")
        model.toggleSelection("a")
        assertThat(model.uiState.value.selectedIds).containsExactly("b")
        model.selectAll()
        assertThat(model.uiState.value.selectedIds).containsExactly("a", "b")
        model.toggleSelectionMode()
        assertThat(model.uiState.value.isSelectionMode).isFalse()
        assertThat(model.uiState.value.selectedIds).isEmpty()

        model.toggleSelectionMode()
        model.toggleSelection("a")
        model.clearSelection()
        assertThat(model.uiState.value.isSelectionMode).isFalse()
        assertThat(model.uiState.value.selectedIds).isEmpty()
    }

    @Test
    fun `detail and dialog visibility is driven from the ui state`() = scope.runTest {
        val entity = recording("a")
        val model = viewModel()
        advanceUntilIdle()

        model.selectRecording(entity)
        assertThat(model.uiState.value.selectedRecording).isEqualTo(entity)
        model.clearSelectedRecording()
        assertThat(model.uiState.value.selectedRecording).isNull()

        model.showEditDialog(entity)
        assertThat(model.uiState.value.showEditDialog).isTrue()
        assertThat(model.uiState.value.editingRecording).isEqualTo(entity)
        model.hideEditDialog()
        assertThat(model.uiState.value.showEditDialog).isFalse()
        assertThat(model.uiState.value.editingRecording).isNull()

        model.showDeleteDialog()
        assertThat(model.uiState.value.showDeleteDialog).isTrue()
        model.hideDeleteDialog()
        assertThat(model.uiState.value.showDeleteDialog).isFalse()
    }

    @Test
    fun `recording controls report repository failures`() = scope.runTest {
        coEvery { repository.startPhoneRecording() } returnsFailure IOException("mic busy")
        coEvery { repository.startGlassesRecording() } returnsFailure IOException("not connected")
        coEvery { repository.stopRecording() } returnsFailure IOException("save failed")
        val model = viewModel()

        model.startPhoneRecording(); advanceUntilIdle()
        assertThat(model.uiState.value.error).isEqualTo("mic busy")
        model.startGlassesRecording(); advanceUntilIdle()
        assertThat(model.uiState.value.error).isEqualTo("not connected")
        model.stopRecording(); advanceUntilIdle()
        assertThat(model.uiState.value.error).isEqualTo("save failed")

        model.clearError()
        coEvery { repository.startPhoneRecording() } returnsSuccess "id"
        coEvery { repository.stopRecording() } returnsSuccess recording("a")
        model.startPhoneRecording(); model.stopRecording(); advanceUntilIdle()
        assertThat(model.uiState.value.error).isNull()
    }

    @Test
    fun `edit writes close the dialog and failures are reported`() = scope.runTest {
        val model = viewModel()
        advanceUntilIdle()

        model.showEditDialog(recording("a"))
        model.updateTitle("a", "Renamed"); advanceUntilIdle()
        coVerify { repository.updateTitle("a", "Renamed") }
        assertThat(model.uiState.value.showEditDialog).isFalse()

        model.updateNotes("a", "note"); advanceUntilIdle()
        coVerify { repository.updateNotes("a", "note") }
        model.toggleFavorite("a"); advanceUntilIdle()
        coVerify { repository.toggleFavorite("a") }
        assertThat(model.uiState.value.error).isNull()

        coEvery { repository.toggleFavorite(any()) } throws IOException("disk full")
        model.toggleFavorite("a"); advanceUntilIdle()
        assertThat(model.uiState.value.error).isEqualTo("disk full")
    }

    @Test
    fun `deleting drops the row from selection and detail state`() = scope.runTest {
        recordings.value = listOf(recording("a"), recording("b"))
        val model = viewModel()
        advanceUntilIdle()

        model.selectRecording(recording("a"))
        model.toggleSelectionMode()
        model.toggleSelection("a")
        model.toggleSelection("b")
        model.showDeleteDialog()

        model.deleteRecording("a"); advanceUntilIdle()
        coVerify { repository.deleteRecording("a") }
        assertThat(model.uiState.value.selectedRecording).isNull()
        assertThat(model.uiState.value.selectedIds).containsExactly("b")
        assertThat(model.uiState.value.showDeleteDialog).isFalse()

        model.showDeleteDialog()
        model.deleteSelectedRecordings(); advanceUntilIdle()
        coVerify { repository.deleteRecordings(listOf("b")) }
        assertThat(model.uiState.value.selectedIds).isEmpty()
        assertThat(model.uiState.value.isSelectionMode).isFalse()
        assertThat(model.uiState.value.showDeleteDialog).isFalse()
    }

    @Test
    fun `transcription is dispatched once per pending recording with a real file`() = scope.runTest {
        val file = temporary.newFile("recording.wav")
        coEvery { repository.getRecordingById("missing") } returns null
        coEvery { repository.getRecordingById("nopath") } returns recording("nopath", filePath = "")
        coEvery { repository.getRecordingById("gone") } returns recording("gone", filePath = "/absent.wav")
        coEvery { repository.getRecordingById("done") } returns
            recording("done", filePath = file.path, transcript = "t", aiResponse = "a")
        coEvery { repository.getRecordingById("ready") } returns recording("ready", filePath = file.path)
        val model = viewModel()
        advanceUntilIdle()

        for (id in listOf("missing", "nopath", "gone", "done")) {
            model.transcribeRecording(id)
            advanceUntilIdle()
            // Nothing was dispatched, so the spinner must not stay on.
            assertThat(model.uiState.value.processingRecordingIds).isEmpty()
        }
        assertThat(model.uiState.value.error).isEqualTo("Recording file not found")

        // A pending recording with a real file is handed to the service. The request
        // itself travels over ServiceBridge (covered by ServiceBridgeTest); here the
        // observable effect is that the spinner stays on until the service answers.
        model.transcribeRecording("ready")
        advanceUntilIdle()
        assertThat(model.uiState.value.processingRecordingIds).containsExactly("ready")

        ServiceBridge.notifyTranscriptionCompleted("ready")
        advanceUntilIdle()
        assertThat(model.uiState.value.processingRecordingIds).isEmpty()
    }

    @Test
    fun `transcription errors are recorded against the recording`() = scope.runTest {
        coEvery { repository.getRecordingById("boom") } throws IOException("db offline")
        val model = viewModel()
        advanceUntilIdle()

        model.transcribeRecording("boom")
        advanceUntilIdle()
        coVerify { repository.markError("boom", "db offline") }
        assertThat(model.uiState.value.error).isEqualTo("db offline")
        assertThat(model.uiState.value.processingRecordingIds).isEmpty()
    }

    @Test
    fun `ai analysis stores the answer and reports every failure mode`() = scope.runTest {
        every { SettingsRepository.getInstance(any()) } returns mockk {
            every { getSettings() } returns ApiSettings(aiModelId = "gpt-4")
        }
        coEvery { repository.getRecordingById("missing") } returns null
        coEvery { repository.getRecordingById("blank") } returns recording("blank", transcript = " ")
        coEvery { repository.getRecordingById("ok") } returns recording("ok", transcript = "please summarise")
        coEvery { aiService.quickChat("please summarise") } returnsSuccess "a summary"
        val model = viewModel()
        advanceUntilIdle()

        model.analyzeWithAi("missing"); advanceUntilIdle()
        assertThat(model.uiState.value.error).isEqualTo("Recording not found")
        model.analyzeWithAi("blank"); advanceUntilIdle()
        assertThat(model.uiState.value.error).isEqualTo("Please transcribe the recording first")

        model.analyzeWithAi("ok"); advanceUntilIdle()
        coVerify {
            repository.updateAiResponse("ok", "a summary", any(), "gpt-4")
        }
        assertThat(model.uiState.value.processingRecordingIds).isEmpty()

        coEvery { aiService.quickChat(any()) } returnsFailure IOException("rate limited")
        model.analyzeWithAi("ok"); advanceUntilIdle()
        coVerify { repository.markError("ok", "rate limited") }
        assertThat(model.uiState.value.error).isEqualTo("rate limited")

        coEvery { aiService.quickChat(any()) } throws IOException("socket closed")
        model.analyzeWithAi("ok"); advanceUntilIdle()
        coVerify { repository.markError("ok", "socket closed") }
        assertThat(model.uiState.value.processingRecordingIds).isEmpty()
    }

    @Test
    fun `durations are formatted with minutes only when there are any`() {
        val model = RecordingViewModel(application)
        assertThat(model.formatDuration(0)).isEqualTo("0s")
        assertThat(model.formatDuration(9_400)).isEqualTo("9s")
        assertThat(model.formatDuration(60_000)).isEqualTo("1m 0s")
        assertThat(model.formatDuration(125_000)).isEqualTo("2m 5s")
    }
}
