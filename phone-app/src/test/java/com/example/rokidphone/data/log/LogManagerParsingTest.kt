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
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn

/**
 * Logcat line parsing, buffer deletion and export handling for [LogManager]. The
 * sibling LogManagerTest covers the in-memory logging API.
 */
@RunWith(RobolectricTestRunner::class)
class LogManagerParsingTest {

    @get:Rule val temporary = TemporaryFolder()
    private lateinit var manager: LogManager

    private val parseMethod = LogManager::class.java
        .getDeclaredMethod("parseLogLine", String::class.java)
        .apply { isAccessible = true }

    private val loadMethod = LogManager::class.java
        .getDeclaredMethod("loadSystemLogsToBuffer", Int::class.java, Continuation::class.java)
        .apply { isAccessible = true }

    private fun parse(line: String): LogEntry? = parseMethod.invoke(manager, line) as LogEntry?

    private suspend fun loadSystemLogs(lineCount: Int) {
        suspendCoroutineUninterceptedOrReturn<Unit> { continuation ->
            loadMethod.invoke(manager, lineCount, continuation)
        }
    }

    private fun logDir() = File(temporary.root, "logs")

    @Before
    fun setUp() {
        val context = mockk<Context>()
        every { context.filesDir } returns temporary.root
        manager = LogManager::class.java.getDeclaredConstructor(Context::class.java)
            .apply { isAccessible = true }.newInstance(context)
    }

    @Test
    fun `a logcat line is parsed into its level, tag and message`() {
        val entry = parse("01-26 10:30:45.123 D/BluetoothSpp  ( 1234): link established")!!

        assertThat(entry.level).isEqualTo(LogLevel.DEBUG)
        assertThat(entry.tag).isEqualTo("BluetoothSpp")
        assertThat(entry.message).isEqualTo("link established")
    }

    @Test
    fun `every logcat level character is recognised`() {
        val expected = mapOf(
            "V" to LogLevel.VERBOSE, "D" to LogLevel.DEBUG, "I" to LogLevel.INFO,
            "W" to LogLevel.WARN, "E" to LogLevel.ERROR, "A" to LogLevel.ASSERT
        )
        for ((char, level) in expected) {
            assertThat(parse("01-26 10:30:45.123 $char/Tag: message")!!.level).isEqualTo(level)
        }
    }

    @Test
    fun `lines that are not logcat output are skipped`() {
        assertThat(parse("")).isNull()
        assertThat(parse("--------- beginning of main")).isNull()
        assertThat(parse("just a sentence")).isNull()
        // An unknown level character does not match the format.
        assertThat(parse("01-26 10:30:45.123 X/Tag: message")).isNull()
    }

    @Test
    fun `a timestamp from the future is read as last year`() {
        val tomorrow = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, 1) }.time
        val stamp = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.getDefault()).format(tomorrow)

        val entry = parse("$stamp D/Tag: rolled over")!!

        // Without a year in the format, tomorrow would otherwise land in the future.
        assertThat(entry.timestamp).isLessThan(System.currentTimeMillis())
        val year = Calendar.getInstance().apply { time = Date(entry.timestamp) }.get(Calendar.YEAR)
        assertThat(year).isEqualTo(Calendar.getInstance().get(Calendar.YEAR) - 1)
    }

    @Test
    fun `reading the system log leaves the loading flag down`() = runTest {
        // logcat is not available to a JVM unit test, so the read yields nothing.
        assertThat(manager.readSystemLogs(10)).isEmpty()
        assertThat(manager.isLoading.value).isFalse()

        loadSystemLogs(10)
        assertThat(manager.isLoading.value).isFalse()
    }

    @Test
    fun `deleting by time range removes only the entries inside it`() {
        manager.i("Tag", "first")
        manager.i("Tag", "second")
        val cutoff = manager.logs.value.first().timestamp

        val removed = manager.deleteLogsByTimeRange(0, cutoff)

        assertThat(removed).isAtLeast(1)
        assertThat(manager.logs.value.size).isEqualTo(2 - removed)
    }

    @Test
    fun `deleting old entries uses an age rather than an instant`() {
        manager.i("Tag", "recent")

        // Nothing is older than an hour yet.
        assertThat(manager.deleteOldLogs(60 * 60 * 1000L)).isEqualTo(0)
        // Everything is older than "zero milliseconds ago".
        assertThat(manager.deleteOldLogs(0)).isEqualTo(1)
        assertThat(manager.logs.value).isEmpty()
    }

    @Test
    fun `deleting below a level keeps the more serious entries`() {
        manager.v("Tag", "verbose")
        manager.d("Tag", "debug")
        manager.w("Tag", "warn")
        manager.e("Tag", "error")

        val removed = manager.deleteLogsBelowLevel(LogLevel.WARN)

        assertThat(removed).isEqualTo(2)
        assertThat(manager.logs.value.map { it.message }).containsExactly("warn", "error").inOrder()
    }

    @Test
    fun `statistics describe the buffer and the exported files`() = runTest {
        manager.i("network", "one")
        manager.e("audio", "two")
        manager.exportLogs()

        val stats = manager.getLogStats()

        assertThat(stats.totalCount).isEqualTo(2)
        assertThat(stats.countByLevel[LogLevel.INFO]).isEqualTo(1)
        assertThat(stats.countByTag["audio"]).isEqualTo(1)
        assertThat(stats.oldestTimestamp).isNotNull()
        assertThat(stats.newestTimestamp).isAtLeast(stats.oldestTimestamp!!)
        assertThat(stats.exportedFilesCount).isEqualTo(1)
        assertThat(stats.getTimeRangeString()).isNotEmpty()

        manager.clearAllLogs()
        assertThat(manager.getLogStats().getTimeRangeString()).isEqualTo("No logs")
    }

    @Test
    fun `exported files are listed newest first and ignore other files`() = runTest {
        manager.i("Tag", "entry")
        // The manager appends the .txt extension itself.
        val first = manager.exportLogs(fileName = "rokid_logs_old")!!
        val second = manager.exportLogs(fileName = "rokid_logs_new")!!
        first.setLastModified(1_000)
        second.setLastModified(9_000)
        File(logDir(), "notes.md").writeText("not a log")

        assertThat(manager.getExportedLogFiles().map { it.name })
            .containsExactly("rokid_logs_new.txt", "rokid_logs_old.txt").inOrder()
    }

    @Test
    fun `a file outside the export directory is refused`() = runTest {
        val outside = temporary.newFile("elsewhere.txt").apply { writeText("secret") }

        val content = manager.readExportedLogFile(outside)

        assertThat(content).startsWith("Error reading file:")
        assertThat(content).doesNotContain("secret")
        assertThat(manager.deleteExportedLogFile(outside)).isFalse()
        assertThat(outside.exists()).isTrue()
    }

    @Test
    fun `exported files can be read back and deleted`() = runTest {
        manager.i("Tag", "entry")
        val file = manager.exportLogs()!!

        assertThat(manager.readExportedLogFile(file)).contains("entry")
        assertThat(manager.deleteExportedLogFile(file)).isTrue()
        assertThat(file.exists()).isFalse()

        manager.exportLogs()
        manager.exportLogs(fileName = "rokid_logs_second")
        assertThat(manager.deleteAllExportedLogFiles()).isEqualTo(2)
        assertThat(manager.getExportedLogFiles()).isEmpty()
    }

    @Test
    fun `the string export carries a header and every entry`() {
        manager.i("network", "a request")
        manager.e("audio", "a failure")

        val text = manager.exportLogsAsString()

        assertThat(text).contains("=== Rokid AI Assistant Logs ===")
        assertThat(text).contains("Total entries: 2")
        assertThat(text).contains("a request")
        assertThat(text).contains("a failure")

        // A filter narrows what is written.
        val errorsOnly = manager.exportLogsAsString(LogFilter(minLevel = LogLevel.ERROR))
        assertThat(errorsOnly).contains("a failure")
        assertThat(errorsOnly).doesNotContain("a request")
    }

    @Test
    fun `clearing the system logcat reports whether the command worked`() = runTest {
        // logcat is unavailable to a JVM unit test, so the command cannot succeed.
        assertThat(manager.clearSystemLogcat()).isFalse()
    }
}
