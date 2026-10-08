package io.github.zero2005x.glassesaicompanion.data.log

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

/**
 * Log level enumeration for filtering and display
 */
enum class LogLevel(val priority: Int, val tag: String) {
    VERBOSE(2, "V"),
    DEBUG(3, "D"),
    INFO(4, "I"),
    WARN(5, "W"),
    ERROR(6, "E"),
    ASSERT(7, "A");
    
    companion object {
        /** Parse a logcat level character (case-insensitive); null when unrecognised. */
        fun fromCharOrNull(char: Char): LogLevel? = when (char.uppercaseChar()) {
            'V' -> VERBOSE
            'D' -> DEBUG
            'I' -> INFO
            'W' -> WARN
            'E' -> ERROR
            'A' -> ASSERT
            else -> null
        }

        /** Parse a logcat level character, falling back to [DEBUG] for unrecognised input. */
        fun fromChar(char: Char): LogLevel = fromCharOrNull(char) ?: DEBUG
    }
}

/**
 * Data class representing a single log entry
 */
data class LogEntry(
    val id: Long = nextId(),
    val timestamp: Long = System.currentTimeMillis(),
    val level: LogLevel,
    val tag: String,
    val message: String,
    val threadName: String = Thread.currentThread().name,
    val throwable: Throwable? = null
) {
    companion object {
        // Monotonic identity source (System.nanoTime() is a relative timer, not an id).
        private val idCounter = AtomicLong()
        private fun nextId(): Long = idCounter.incrementAndGet()

        // SimpleDateFormat is not thread-safe; cache one instance per thread and pattern
        // instead of allocating a new formatter for every log line rendered/exported.
        private val formatters = object : ThreadLocal<MutableMap<String, SimpleDateFormat>>() {
            override fun initialValue(): MutableMap<String, SimpleDateFormat> = mutableMapOf()
        }

        private const val MAX_DISPLAY_STACK_LINES = 10
    }

    /**
     * Get formatted timestamp string
     */
    fun getFormattedTimestamp(pattern: String = "yyyy-MM-dd HH:mm:ss.SSS"): String {
        val dateFormat = formatters.get()!!.getOrPut(pattern) {
            SimpleDateFormat(pattern, Locale.getDefault())
        }
        return dateFormat.format(Date(timestamp))
    }
    
    /**
     * Get formatted log line for display
     */
    fun toDisplayString(): String {
        val time = getFormattedTimestamp("HH:mm:ss.SSS")
        // Truncate stack traces for display to bound memory/rendering cost;
        // toExportString() keeps the full trace.
        val stackTrace = throwable?.let { t ->
            val lines = t.stackTraceToString().lines()
            val truncated = lines.take(MAX_DISPLAY_STACK_LINES).joinToString("\n")
            val suffix = if (lines.size > MAX_DISPLAY_STACK_LINES) {
                "\n… (${lines.size - MAX_DISPLAY_STACK_LINES} more lines)"
            } else ""
            "\n$truncated$suffix"
        } ?: ""
        return "$time ${level.tag}/$tag: $message$stackTrace"
    }
    
    /**
     * Get formatted log line for file export
     */
    fun toExportString(): String {
        val time = getFormattedTimestamp()
        val stackTrace = throwable?.let { "\n${it.stackTraceToString()}" } ?: ""
        return "$time [$threadName] ${level.tag}/$tag: $message$stackTrace"
    }
}

/**
 * Filter options for log queries
 */
data class LogFilter(
    val minLevel: LogLevel = LogLevel.VERBOSE,
    val tags: Set<String> = emptySet(),
    val searchQuery: String = "",
    val startTime: Long? = null,
    val endTime: Long? = null
) {
    fun matches(entry: LogEntry): Boolean {
        // Check log level
        if (entry.level.priority < minLevel.priority) return false
        
        // Check tag filter
        if (tags.isNotEmpty() && entry.tag !in tags) return false
        
        // Check search query (locale-neutral, case-insensitive; avoids
        // re-normalising the query for every entry)
        if (searchQuery.isNotBlank()) {
            val matchesMessage = entry.message.contains(searchQuery, ignoreCase = true)
            val matchesTag = entry.tag.contains(searchQuery, ignoreCase = true)
            if (!matchesMessage && !matchesTag) return false
        }
        
        // Check time range
        startTime?.let { if (entry.timestamp < it) return false }
        endTime?.let { if (entry.timestamp > it) return false }
        
        return true
    }
}
