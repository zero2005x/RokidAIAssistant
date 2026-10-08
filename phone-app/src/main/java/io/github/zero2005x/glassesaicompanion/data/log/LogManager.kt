package io.github.zero2005x.glassesaicompanion.data.log

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.File
import java.io.FileWriter
import java.io.InputStreamReader
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.atomic.AtomicInteger

/**
 * Centralized log manager for the application
 * Provides functionality to capture, store, export, and delete logs
 * 
 * Features:
 * - In-memory log buffer with configurable max size
 * - Read system logcat logs
 * - Export logs to file
 * - Filter logs by level, tag, time range
 * - Delete logs (all or filtered)
 */
class LogManager private constructor(private val context: Context) {
    
    companion object {
        private const val TAG = "LogManager"
        private const val DEFAULT_MAX_ENTRIES = 5000
        private const val LOG_FILE_PREFIX = "rokid_logs_"
        private const val LOG_DIR_NAME = "logs"
        // Clock-skew tolerance for logcat timestamps, which carry no year
        private const val FUTURE_LOG_TOLERANCE_MS = 60_000L

        // Hoisted out of the per-line parse loop (Regex compilation is expensive)
        private val LOGCAT_LINE_REGEX =
            Regex("""(\d{2}-\d{2}\s+\d{2}:\d{2}:\d{2}\.\d{3})\s+([VDIWEA])/([^(:\s]+)\s*(?:\(\s*\d+\))?\s*:\s*(.*)""")

        // SimpleDateFormat is not thread-safe; keep one instance per thread
        private val YEAR_FORMAT = object : ThreadLocal<SimpleDateFormat>() {
            override fun initialValue(): SimpleDateFormat = SimpleDateFormat("yyyy", Locale.getDefault())
        }
        private val LOG_TIMESTAMP_FORMAT = object : ThreadLocal<SimpleDateFormat>() {
            override fun initialValue(): SimpleDateFormat =
                SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())
        }
        private const val MAX_EXPORTED_LOG_READ_BYTES = 2L * 1024L * 1024L
        private val SAFE_FILE_NAME = Regex("[A-Za-z0-9._-]{1,80}")

        @Volatile
        private var instance: LogManager? = null
        
        fun getInstance(context: Context): LogManager {
            return instance ?: synchronized(this) {
                instance ?: LogManager(context.applicationContext).also { instance = it }
            }
        }
    }
    
    // In-memory log storage using thread-safe deque
    private val logBuffer = ConcurrentLinkedDeque<LogEntry>()

    // Read/written from arbitrary threads (log() vs setMaxEntries())
    @Volatile
    private var maxEntries = DEFAULT_MAX_ENTRIES
    
    // Observable log state
    private val _logs = MutableStateFlow<List<LogEntry>>(emptyList())
    val logs: StateFlow<List<LogEntry>> = _logs.asStateFlow()
    
    // Available tags collected from logs
    private val _availableTags = MutableStateFlow<Set<String>>(emptySet())
    val availableTags: StateFlow<Set<String>> = _availableTags.asStateFlow()
    
    // Loading state
    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    // In-flight read counter so overlapping reads don't reset the loading state early
    private val loadingCounter = AtomicInteger()

    // Log directory (created once; mkdirs failure is logged instead of ignored)
    private val logDir: File by lazy {
        File(context.filesDir, LOG_DIR_NAME).also {
            if (!it.exists() && !it.mkdirs()) {
                Log.w(TAG, "Failed to create log dir: ${it.absolutePath}")
            }
        }
    }

    /**
     * Configure maximum number of log entries to keep in memory
     */
    fun setMaxEntries(max: Int) {
        require(max > 0) { "maxEntries must be positive, was $max" }
        maxEntries = max
        trimBuffer()
    }
    
    /**
     * Add a log entry to the buffer
     */
    fun log(level: LogLevel, tag: String, rawMessage: String, throwable: Throwable? = null) {
        // Secrets never reach the buffer, the log viewer, logcat or an export
        val message = LogRedactor.redact(rawMessage)
        val entry = LogEntry(
            level = level,
            tag = tag,
            message = message,
            throwable = throwable
        )
        addEntry(entry)
        
        // Also log to Android's logcat
        when (level) {
            LogLevel.VERBOSE -> Log.v(tag, message, throwable)
            LogLevel.DEBUG -> Log.d(tag, message, throwable)
            LogLevel.INFO -> Log.i(tag, message, throwable)
            LogLevel.WARN -> Log.w(tag, message, throwable)
            LogLevel.ERROR -> Log.e(tag, message, throwable)
            LogLevel.ASSERT -> Log.wtf(tag, message, throwable)
        }
    }
    
    /**
     * Convenience methods for different log levels
     */
    fun v(tag: String, message: String) = log(LogLevel.VERBOSE, tag, message)
    fun d(tag: String, message: String) = log(LogLevel.DEBUG, tag, message)
    fun i(tag: String, message: String) = log(LogLevel.INFO, tag, message)
    fun w(tag: String, message: String, throwable: Throwable? = null) = log(LogLevel.WARN, tag, message, throwable)
    fun e(tag: String, message: String, throwable: Throwable? = null) = log(LogLevel.ERROR, tag, message, throwable)
    
    private fun addEntry(entry: LogEntry) {
        logBuffer.addLast(entry)
        trimBuffer()
        updateState()
    }
    
    private fun trimBuffer() {
        while (logBuffer.size > maxEntries) {
            logBuffer.pollFirst()
        }
    }
    
    // NOTE: O(n) per call (full buffer copy + tag-set rebuild) and invoked on every
    // log() call. Accepted for now given the bounded buffer; if log bursts become a
    // problem, throttle StateFlow updates or maintain the tag set incrementally.
    private fun updateState() {
        _logs.value = logBuffer.toList()
        _availableTags.value = logBuffer.map { it.tag }.toSet()
    }
    
    // ==================== Read Logs ====================
    
    /**
     * Read system logcat logs for this app
     * @param lineCount Maximum number of lines to read
     * @param filter Optional filter to apply
     */
    suspend fun readSystemLogs(
        lineCount: Int = 1000,
        filter: LogFilter = LogFilter()
    ): List<LogEntry> = withContext(Dispatchers.IO) {
        _isLoading.value = loadingCounter.incrementAndGet() > 0
        try {
            val entries = mutableListOf<LogEntry>()

            // Use logcat command to read logs
            val process = Runtime.getRuntime().exec(arrayOf(
                "logcat", "-d", "-v", "time", "-t", lineCount.toString()
            ))

            try {
                BufferedReader(InputStreamReader(process.inputStream)).use { reader ->
                    reader.forEachLine { logLine ->
                        parseLogLine(logLine)?.let { entry ->
                            if (filter.matches(entry)) {
                                entries.add(entry)
                            }
                        }
                    }
                }
                process.waitFor()
            } finally {
                // Always reap the child process, even on parse/IO failure
                process.destroy()
            }

            entries
        } catch (e: Exception) {
            Log.e(TAG, "Error reading system logs", e)
            emptyList()
        } finally {
            _isLoading.value = loadingCounter.decrementAndGet() > 0
        }
    }
    
    /**
     * Load logcat logs and add to buffer
     */
    suspend fun loadSystemLogsToBuffer(lineCount: Int = 500) {
        val systemLogs = readSystemLogs(lineCount)
        // Dedup on a stable key: freshly parsed entries always get new ids, so an
        // id comparison never matches and repeated loads duplicated every entry.
        val existingKeys = logBuffer.mapTo(HashSet()) { Triple(it.timestamp, it.tag, it.message) }
        systemLogs.forEach { entry ->
            if (existingKeys.add(Triple(entry.timestamp, entry.tag, entry.message))) {
                logBuffer.addLast(entry)
            }
        }
        trimBuffer()
        updateState()
    }
    
    /**
     * Parse a logcat line into LogEntry
     * Format: "MM-DD HH:MM:SS.mmm D/TAG: message"
     */
    private fun parseLogLine(line: String): LogEntry? {
        return try {
            // Basic logcat format: "01-26 10:30:45.123 D/MyTag  ( 1234): message"
            val match = LOGCAT_LINE_REGEX.find(line) ?: return null

            val (timeStr, levelChar, tag, message) = match.destructured

            // Logcat timestamps carry no year; assume the current year, but if that
            // lands in the future (e.g. a Dec-31 log read on Jan-1) use last year.
            val now = System.currentTimeMillis()
            val timestamp = try {
                val year = YEAR_FORMAT.get()!!.format(Date(now))
                val parsed = LOG_TIMESTAMP_FORMAT.get()!!.parse("$year-$timeStr")?.time
                when {
                    parsed == null -> now
                    parsed - now > FUTURE_LOG_TOLERANCE_MS ->
                        java.util.Calendar.getInstance().apply {
                            timeInMillis = parsed
                            add(java.util.Calendar.YEAR, -1)
                        }.timeInMillis
                    else -> parsed
                }
            } catch (e: Exception) {
                now
            }

            LogEntry(
                timestamp = timestamp,
                level = LogLevel.fromChar(levelChar.first()),
                tag = tag.trim(),
                message = LogRedactor.redact(message)
            )
        } catch (e: Exception) {
            null
        }
    }
    
    /**
     * Get filtered logs from buffer
     */
    fun getFilteredLogs(filter: LogFilter): List<LogEntry> {
        return logBuffer.filter { filter.matches(it) }
    }
    
    // ==================== Export Logs ====================
    
    /**
     * Export logs to a file
     * @param filter Optional filter to apply before export
     * @param fileName Custom file name (without extension)
     * @return File path of exported logs, or null if failed
     */
    suspend fun exportLogs(
        filter: LogFilter = LogFilter(),
        fileName: String? = null
    ): File? = withContext(Dispatchers.IO) {
        try {
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val name = fileName
                ?.trim()
                ?.takeIf { SAFE_FILE_NAME.matches(it) }
                ?: "${LOG_FILE_PREFIX}$timestamp"
            val file = File(logDir, "$name.txt")
            
            val logsToExport = getFilteredLogs(filter)
            
            FileWriter(file).use { writer ->
                writer.write("=== ${context.getString(io.github.zero2005x.glassesaicompanion.R.string.app_name)} Logs ===\n")
                writer.write("Exported: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())}\n")
                writer.write("Total entries: ${logsToExport.size}\n")
                writer.write("Filter: Level >= ${filter.minLevel}, Tags: ${filter.tags.ifEmpty { "All" }}\n")
                writer.write("=====================================\n\n")
                
                logsToExport.forEach { entry ->
                    writer.write(LogRedactor.redact(entry.toExportString()))
                    writer.write("\n")
                }
            }
            
            Log.i(TAG, "Logs exported to: ${file.absolutePath}")
            file
        } catch (e: Exception) {
            Log.e(TAG, "Error exporting logs", e)
            null
        }
    }
    
    /**
     * Export logs as a string (for sharing)
     */
    fun exportLogsAsString(filter: LogFilter = LogFilter()): String {
        val logsToExport = getFilteredLogs(filter)
        val sb = StringBuilder()
        
        sb.appendLine("=== ${context.getString(io.github.zero2005x.glassesaicompanion.R.string.app_name)} Logs ===")
        sb.appendLine("Total entries: ${logsToExport.size}")
        sb.appendLine("=====================================\n")
        
        logsToExport.forEach { entry ->
            sb.appendLine(LogRedactor.redact(entry.toExportString()))
        }
        
        return sb.toString()
    }
    
    /**
     * Get list of exported log files
     */
    fun getExportedLogFiles(): List<File> {
        return logDir.listFiles()?.filter { 
            it.isFile && it.name.endsWith(".txt") 
        }?.sortedByDescending { it.lastModified() } ?: emptyList()
    }
    
    /**
     * Read content of an exported log file
     */
    suspend fun readExportedLogFile(file: File): String = withContext(Dispatchers.IO) {
        try {
            val safeFile = resolveExportedLogFile(file)
                ?: throw SecurityException("File is outside the exported log directory")
            require(safeFile.length() <= MAX_EXPORTED_LOG_READ_BYTES) {
                "Log file exceeds the ${MAX_EXPORTED_LOG_READ_BYTES / 1024 / 1024} MiB read limit"
            }
            safeFile.readText()
        } catch (e: Exception) {
            Log.e(TAG, "Error reading log file: ${file.name}", e)
            "Error reading file: ${e.message}"
        }
    }
    
    // ==================== Delete Logs ====================
    
    /**
     * Clear all logs from buffer
     */
    fun clearAllLogs() {
        logBuffer.clear()
        updateState()
        Log.i(TAG, "All in-memory logs cleared")
    }
    
    /**
     * Delete logs matching filter criteria
     * @param filter Filter to determine which logs to delete
     * @return Number of deleted entries
     */
    fun deleteLogs(filter: LogFilter): Int {
        // Single-pass removal; remove-by-equals could delete an equal duplicate
        // and the count no longer depends on a non-atomic filter-then-remove
        val before = logBuffer.size
        logBuffer.removeAll { filter.matches(it) }
        val removed = before - logBuffer.size
        updateState()
        Log.i(TAG, "Deleted $removed log entries")
        return removed
    }
    
    /**
     * Delete logs by tag
     * @param tag Tag to filter logs for deletion
     * @return Number of deleted entries
     */
    fun deleteLogsByTag(tag: String): Int {
        val filter = LogFilter(tags = setOf(tag))
        return deleteLogs(filter)
    }
    
    /**
     * Delete logs by time range
     * @param startTime Start timestamp (inclusive)
     * @param endTime End timestamp (inclusive)
     * @return Number of deleted entries
     */
    fun deleteLogsByTimeRange(startTime: Long, endTime: Long): Int {
        val filter = LogFilter(startTime = startTime, endTime = endTime)
        return deleteLogs(filter)
    }
    
    /**
     * Delete logs older than specified duration
     * @param olderThanMillis Delete logs older than this duration from now
     * @return Number of deleted entries
     */
    fun deleteOldLogs(olderThanMillis: Long): Int {
        val cutoffTime = System.currentTimeMillis() - olderThanMillis
        val filter = LogFilter(endTime = cutoffTime)
        return deleteLogs(filter)
    }
    
    /**
     * Delete logs by level (delete all logs with lower priority)
     * @param belowLevel Delete all logs with priority below this level
     * @return Number of deleted entries
     */
    fun deleteLogsBelowLevel(belowLevel: LogLevel): Int {
        val before = logBuffer.size
        logBuffer.removeAll { it.level.priority < belowLevel.priority }
        val removed = before - logBuffer.size
        updateState()
        Log.i(TAG, "Deleted $removed log entries below level ${belowLevel.tag}")
        return removed
    }
    
    /**
     * Delete a single exported log file
     */
    fun deleteExportedLogFile(file: File): Boolean {
        return try {
            val safeFile = resolveExportedLogFile(file) ?: return false
            val deleted = safeFile.delete()
            if (deleted) {
                Log.i(TAG, "Deleted log file: ${file.name}")
            }
            deleted
        } catch (e: Exception) {
            Log.e(TAG, "Error deleting log file: ${file.name}", e)
            false
        }
    }

    private fun resolveExportedLogFile(file: File): File? {
        val root = logDir.canonicalFile
        val candidate = file.canonicalFile
        return candidate.takeIf {
            it.parentFile == root && it.isFile && it.name.endsWith(".txt", ignoreCase = true)
        }
    }
    
    /**
     * Delete all exported log files
     * @return Number of files deleted
     */
    fun deleteAllExportedLogFiles(): Int {
        var count = 0
        getExportedLogFiles().forEach { file ->
            if (deleteExportedLogFile(file)) count++
        }
        Log.i(TAG, "Deleted $count exported log files")
        return count
    }
    
    /**
     * Clear system logcat (requires root or debugging permissions)
     */
    suspend fun clearSystemLogcat(): Boolean = withContext(Dispatchers.IO) {
        try {
            val process = Runtime.getRuntime().exec(arrayOf("logcat", "-c"))
            val exitCode = try {
                process.waitFor()
            } finally {
                process.destroy()
            }
            exitCode == 0
        } catch (e: Exception) {
            Log.e(TAG, "Error clearing system logcat", e)
            false
        }
    }
    
    // ==================== Statistics ====================
    
    /**
     * Get log statistics
     */
    fun getLogStats(): LogStats {
        val logs = logBuffer.toList()
        return LogStats(
            totalCount = logs.size,
            countByLevel = logs.groupBy { it.level }.mapValues { it.value.size },
            countByTag = logs.groupBy { it.tag }.mapValues { it.value.size },
            oldestTimestamp = logs.minOfOrNull { it.timestamp },
            newestTimestamp = logs.maxOfOrNull { it.timestamp },
            exportedFilesCount = getExportedLogFiles().size
        )
    }
}

/**
 * Log statistics data class
 */
data class LogStats(
    val totalCount: Int,
    val countByLevel: Map<LogLevel, Int>,
    val countByTag: Map<String, Int>,
    val oldestTimestamp: Long?,
    val newestTimestamp: Long?,
    val exportedFilesCount: Int
) {
    fun getTimeRangeString(): String {
        if (oldestTimestamp == null || newestTimestamp == null) return "No logs"
        val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
        return "${dateFormat.format(Date(oldestTimestamp))} - ${dateFormat.format(Date(newestTimestamp))}"
    }
}
