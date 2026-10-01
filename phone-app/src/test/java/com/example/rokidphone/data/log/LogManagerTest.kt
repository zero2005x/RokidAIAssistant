package com.example.rokidphone.data.log

import android.content.Context
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.io.RandomAccessFile

@RunWith(RobolectricTestRunner::class)
class LogManagerTest {
    @get:Rule val temporary = TemporaryFolder()
    private lateinit var manager: LogManager

    @Before
    fun setUp() {
        val context = mockk<Context>()
        every { context.filesDir } returns temporary.root
        manager = LogManager::class.java.getDeclaredConstructor(Context::class.java)
            .apply { isAccessible = true }.newInstance(context)
    }

    @Test
    fun `buffer limits keep newest entries and tags reflect remaining logs`() {
        manager.setMaxEntries(2)
        manager.d("old", "first")
        manager.i("current", "second")
        manager.e("current", "third", IllegalStateException("details"))
        assertThat(manager.logs.value.map { it.message }).containsExactly("second", "third")
        assertThat(manager.availableTags.value).containsExactly("current")
        manager.clearAllLogs()
        assertThat(manager.logs.value).isEmpty()
        assertThat(manager.availableTags.value).isEmpty()
    }

    @Test
    fun `exported logs retain errors and filters exclude other records`() = runTest {
        manager.i("network", "successful request")
        manager.e("network", "request failed", IOExceptionForTest())
        manager.w("other", "unrelated warning")
        val file = manager.exportLogs(LogFilter(minLevel = LogLevel.WARN, tags = setOf("network")), "audit")!!
        assertThat(file.name).isEqualTo("audit.txt")
        val text = manager.readExportedLogFile(file)
        assertThat(text).contains("request failed")
        assertThat(text).contains("test failure")
        assertThat(text).doesNotContain("successful request")
        assertThat(text).doesNotContain("unrelated warning")
        assertThat(manager.getExportedLogFiles()).containsExactly(file)
        assertThat(manager.deleteExportedLogFile(file)).isTrue()
        assertThat(manager.getExportedLogFiles()).isEmpty()
    }

    @Test
    fun `unsafe export names cannot escape the log directory`() = runTest {
        for (name in listOf("../outside", "a/b", "", "a".repeat(81))) {
            val file = manager.exportLogs(fileName = name)!!
            assertThat(file.canonicalFile.parentFile).isEqualTo(File(temporary.root, "logs").canonicalFile)
            assertThat(file.name).startsWith("rokid_logs_")
        }
    }

    @Test
    fun `read and delete reject outside files directories missing files and non text files`() = runTest {
        val exported = manager.exportLogs(fileName = "valid")!!
        val outside = temporary.newFile("outside.txt").apply { writeText("private data") }
        val binary = File(exported.parentFile, "binary.bin").apply { writeText("private data") }
        val nested = File(exported.parentFile, "nested").apply { mkdir() }
        for (file in listOf(outside, binary, nested, File(exported.parentFile, "missing.txt"))) {
            assertThat(manager.readExportedLogFile(file)).startsWith("Error reading file:")
            assertThat(manager.deleteExportedLogFile(file)).isFalse()
        }
        assertThat(outside.readText()).isEqualTo("private data")
        assertThat(binary.exists()).isTrue()
    }

    @Test
    fun `oversized logs are rejected before reading them into memory`() = runTest {
        val file = manager.exportLogs(fileName = "large")!!
        RandomAccessFile(file, "rw").use { it.setLength(2L * 1024 * 1024 + 1) }
        assertThat(manager.readExportedLogFile(file)).contains("exceeds the 2 MiB read limit")
    }

    @Test
    fun `delete all exported logs preserves unrelated files`() = runTest {
        manager.exportLogs(fileName = "one")
        manager.exportLogs(fileName = "two")
        val binary = File(temporary.root, "logs/keep.bin").apply { writeText("keep") }
        assertThat(manager.deleteAllExportedLogFiles()).isEqualTo(2)
        assertThat(binary.exists()).isTrue()
    }

    @Test
    fun `log filtering combines level tag query and time bounds`() {
        val entry = LogEntry(timestamp = 100, level = LogLevel.WARN, tag = "Network", message = "Timeout")
        assertThat(LogFilter(minLevel = LogLevel.WARN, tags = setOf("Network"),
            searchQuery = "TIME", startTime = 100, endTime = 100).matches(entry)).isTrue()
        assertThat(LogFilter(searchQuery = "network").matches(entry)).isTrue()
        for (filter in listOf(LogFilter(minLevel = LogLevel.ERROR), LogFilter(tags = setOf("other")),
            LogFilter(searchQuery = "absent"), LogFilter(startTime = 101), LogFilter(endTime = 99))) {
            assertThat(filter.matches(entry)).isFalse()
        }
    }

    @Test
    fun `display truncates long errors while export keeps the complete trace`() {
        val failure = IllegalStateException("failure").apply {
            stackTrace = Array(20) { StackTraceElement("Class", "method$it", "File.kt", it + 1) }
        }
        val entry = LogEntry(level = LogLevel.ERROR, tag = "test", message = "message", throwable = failure)
        assertThat(entry.toDisplayString()).contains("more lines)")
        assertThat(entry.toDisplayString()).doesNotContain("method19")
        assertThat(entry.toExportString()).contains("method19")
        assertThat(LogEntry(level = LogLevel.INFO, tag = "t", message = "m").toDisplayString()).contains("I/t: m")
        for (level in LogLevel.entries) {
            assertThat(LogLevel.fromChar(level.tag.lowercase().single())).isEqualTo(level)
        }
        assertThat(LogLevel.fromCharOrNull('?')).isNull()
        assertThat(LogLevel.fromChar('?')).isEqualTo(LogLevel.DEBUG)
    }

    private class IOExceptionForTest : java.io.IOException("test failure")
}
