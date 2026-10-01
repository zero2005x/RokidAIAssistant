package com.example.rokidphone.viewmodel

import android.app.Application
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.example.rokidphone.data.log.LogEntry
import com.example.rokidphone.data.log.LogFilter
import com.example.rokidphone.data.log.LogLevel
import com.example.rokidphone.data.log.LogManager
import com.example.rokidphone.data.log.LogStats
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
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class LogViewerViewModelTest {

    @get:Rule val temporary = TemporaryFolder()

    private val dispatcher = StandardTestDispatcher()
    private val scope = TestScope(dispatcher)
    private val logManager = mockk<LogManager>(relaxed = true)
    private val logs = MutableStateFlow(emptyList<LogEntry>())
    private val loading = MutableStateFlow(false)
    private val tags = MutableStateFlow(emptySet<String>())
    private lateinit var application: Application

    private fun entry(level: LogLevel, tag: String, message: String, timestamp: Long = 0) =
        LogEntry(level = level, tag = tag, message = message, timestamp = timestamp)

    private val stats = LogStats(
        totalCount = 3, countByLevel = mapOf(LogLevel.INFO to 3), countByTag = mapOf("A" to 3),
        oldestTimestamp = 1, newestTimestamp = 9, exportedFilesCount = 0
    )

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        application = ApplicationProvider.getApplicationContext()
        mockkObject(LogManager.Companion)
        every { LogManager.getInstance(any()) } returns logManager
        every { logManager.logs } returns logs
        every { logManager.isLoading } returns loading
        every { logManager.availableTags } returns tags
        every { logManager.getLogStats() } returns stats
        every { logManager.getExportedLogFiles() } returns emptyList()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        unmockkAll()
    }

    private fun viewModel() = LogViewerViewModel(application)

    /**
     * refreshLogs() hops to Dispatchers.IO, so its result lands on a real thread that
     * the test scheduler does not drive. Drain the scheduler repeatedly until the
     * observable effect appears.
     */
    private fun TestScope.awaitEffect(condition: () -> Boolean) {
        repeat(500) {
            advanceUntilIdle()
            if (condition()) return
            Thread.sleep(10)
        }
        advanceUntilIdle()
    }

    @Test
    fun `the initial load publishes logs, statistics and exported files`() = scope.runTest {
        val exported = listOf(temporary.newFile("rokid-logs.txt"))
        every { logManager.getExportedLogFiles() } returns exported
        logs.value = listOf(entry(LogLevel.INFO, "A", "hello"))

        val model = viewModel()
        awaitEffect { runCatching { coVerify { logManager.loadSystemLogsToBuffer(1000) } }.isSuccess }

        assertThat(model.filteredLogs.value).hasSize(1)
        assertThat(model.stats.value).isEqualTo(stats)
        assertThat(model.exportedFiles.value).isEqualTo(exported)
        assertThat(model.isLoading.value).isFalse()
        coVerify { logManager.loadSystemLogsToBuffer(1000) }
    }

    @Test
    fun `a failed refresh is reported as a message`() = scope.runTest {
        coEvery { logManager.loadSystemLogsToBuffer(any()) } throws IOException("logcat unavailable")

        val model = viewModel()
        awaitEffect { model.message.value != null }

        assertThat(model.message.value).isEqualTo("Failed to refresh logs: logcat unavailable")
        model.clearMessage()
        assertThat(model.message.value).isNull()
    }

    @Test
    fun `level, search and tag filters narrow the visible entries`() = scope.runTest {
        logs.value = listOf(
            entry(LogLevel.VERBOSE, "Bluetooth", "scanning"),
            entry(LogLevel.WARN, "Bluetooth", "link dropped"),
            entry(LogLevel.ERROR, "Audio", "record failed")
        )
        val model = viewModel()
        advanceUntilIdle()
        assertThat(model.filteredLogs.value).hasSize(3)

        model.setLogLevel(LogLevel.WARN)
        advanceUntilIdle()
        assertThat(model.filteredLogs.value.map { it.message })
            .containsExactly("link dropped", "record failed")
        assertThat(model.selectedLevel.value).isEqualTo(LogLevel.WARN)

        model.setSearchQuery("dropped")
        advanceUntilIdle()
        assertThat(model.filteredLogs.value.map { it.tag }).containsExactly("Bluetooth")
        assertThat(model.searchQuery.value).isEqualTo("dropped")

        model.setSearchQuery("")
        model.toggleTag("Audio")
        advanceUntilIdle()
        assertThat(model.selectedTags.value).containsExactly("Audio")
        assertThat(model.filteredLogs.value.map { it.tag }).containsExactly("Audio")

        model.toggleTag("Audio")
        advanceUntilIdle()
        assertThat(model.selectedTags.value).isEmpty()

        model.toggleTag("Audio")
        model.clearTagFilters()
        advanceUntilIdle()
        assertThat(model.selectedTags.value).isEmpty()

        model.setLogLevel(LogLevel.ERROR)
        model.setSearchQuery("x")
        model.toggleTag("Audio")
        model.clearAllFilters()
        advanceUntilIdle()
        assertThat(model.filter.value).isEqualTo(LogFilter())
        assertThat(model.filteredLogs.value).hasSize(3)
    }

    @Test
    fun `auto-scroll is a simple toggle`() = scope.runTest {
        val model = viewModel()
        assertThat(model.autoScroll.value).isTrue()
        model.toggleAutoScroll()
        assertThat(model.autoScroll.value).isFalse()
        model.toggleAutoScroll()
        assertThat(model.autoScroll.value).isTrue()
    }

    @Test
    fun `exporting reports the file it wrote and refreshes the list`() = scope.runTest {
        val file = temporary.newFile("rokid-logs.txt")
        coEvery { logManager.exportLogs(any(), any()) } returns file
        every { logManager.getExportedLogFiles() } returns listOf(file)
        val model = viewModel()
        advanceUntilIdle()

        model.exportLogs()
        advanceUntilIdle()
        assertThat(model.message.value).isEqualTo("Logs exported to: rokid-logs.txt")
        assertThat(model.exportedFiles.value).containsExactly(file)

        model.exportAllLogs()
        advanceUntilIdle()
        assertThat(model.message.value).isEqualTo("All logs exported to: rokid-logs.txt")
    }

    @Test
    fun `a failed export is reported for both export modes`() = scope.runTest {
        coEvery { logManager.exportLogs(any(), any()) } returns null
        val model = viewModel()
        advanceUntilIdle()

        model.exportLogs()
        advanceUntilIdle()
        assertThat(model.message.value).isEqualTo("Failed to export logs")

        model.clearMessage()
        model.exportAllLogs()
        advanceUntilIdle()
        assertThat(model.message.value).isEqualTo("Failed to export logs")
    }

    @Test
    fun `sharing reads through the log manager`() = scope.runTest {
        every { logManager.exportLogsAsString(any()) } returns "=== logs ==="
        coEvery { logManager.readExportedLogFile(any()) } returns "file body"
        val model = viewModel()
        advanceUntilIdle()

        assertThat(model.getLogsForShare()).isEqualTo("=== logs ===")
        assertThat(model.readExportedFile(File("any.txt"))).isEqualTo("file body")
    }

    @Test
    fun `a share intent is built for a file inside the provider's paths`() = scope.runTest {
        val model = viewModel()
        advanceUntilIdle()

        // Outside the declared FileProvider paths: reported instead of thrown.
        assertThat(model.createShareIntent(File("/not/shareable.txt"))).isNull()
        assertThat(model.message.value).contains("Failed to create share intent")
    }

    @Test
    fun `delete operations report what they removed and refresh the statistics`() = scope.runTest {
        every { logManager.deleteLogs(any()) } returns 4
        every { logManager.deleteLogsByTag("Audio") } returns 2
        every { logManager.deleteOldLogs(any()) } returns 7
        every { logManager.deleteLogsBelowLevel(LogLevel.WARN) } returns 5
        val model = viewModel()
        advanceUntilIdle()

        model.clearAllLogs()
        assertThat(model.message.value).isEqualTo("All logs cleared")
        io.mockk.verify { logManager.clearAllLogs() }

        model.deleteFilteredLogs()
        assertThat(model.message.value).isEqualTo("Deleted 4 log entries")

        model.deleteLogsByTag("Audio")
        assertThat(model.message.value).isEqualTo("Deleted 2 entries with tag: Audio")

        model.deleteOldLogs(24)
        assertThat(model.message.value).isEqualTo("Deleted 7 entries older than 24 hours")
        io.mockk.verify { logManager.deleteOldLogs(24L * 60 * 60 * 1000) }

        model.deleteLogsBelowLevel(LogLevel.WARN)
        assertThat(model.message.value).isEqualTo("Deleted 5 entries below WARN level")
    }

    @Test
    fun `deleting exported files reports success and failure`() = scope.runTest {
        val file = temporary.newFile("rokid-logs.txt")
        every { logManager.deleteExportedLogFile(file) } returns true
        every { logManager.deleteAllExportedLogFiles() } returns 3
        val model = viewModel()
        advanceUntilIdle()

        model.deleteExportedFile(file)
        assertThat(model.message.value).isEqualTo("Deleted: rokid-logs.txt")

        every { logManager.deleteExportedLogFile(file) } returns false
        model.deleteExportedFile(file)
        assertThat(model.message.value).isEqualTo("Failed to delete: rokid-logs.txt")

        model.deleteAllExportedFiles()
        assertThat(model.message.value).isEqualTo("Deleted 3 exported files")
    }

    @Test
    fun `clearing the system logcat reports whether it worked`() = scope.runTest {
        coEvery { logManager.clearSystemLogcat() } returns true
        val model = viewModel()
        advanceUntilIdle()

        model.clearSystemLogcat()
        advanceUntilIdle()
        assertThat(model.message.value).isEqualTo("System logcat cleared")

        coEvery { logManager.clearSystemLogcat() } returns false
        model.clearSystemLogcat()
        advanceUntilIdle()
        assertThat(model.message.value).contains("may require root")
    }

    @Test
    fun `available tags and loading state are surfaced from the log manager`() = scope.runTest {
        val model = viewModel()
        advanceUntilIdle()

        tags.value = setOf("Bluetooth", "Audio")
        loading.value = true

        assertThat(model.availableTags.value).containsExactly("Bluetooth", "Audio")
        assertThat(model.isLoading.value).isTrue()
    }
}
