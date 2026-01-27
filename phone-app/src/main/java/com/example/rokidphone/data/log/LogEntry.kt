package com.example.rokidphone.data.log

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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
        fun fromChar(char: Char): LogLevel {
            return when (char) {
                'V' -> VERBOSE
                'D' -> DEBUG
                'I' -> INFO
                'W' -> WARN
                'E' -> ERROR
                'A' -> ASSERT
                else -> DEBUG
            }
        }
    }
}

/**
 * Data class representing a single log entry
 */
data class LogEntry(
    val id: Long = System.nanoTime(),
    val timestamp: Long = System.currentTimeMillis(),
    val level: LogLevel,
    val tag: String,
    val message: String,
    val threadName: String = Thread.currentThread().name,
    val throwable: Throwable? = null
) {
    /**
     * Get formatted timestamp string
     */
    fun getFormattedTimestamp(pattern: String = "yyyy-MM-dd HH:mm:ss.SSS"): String {
        val dateFormat = SimpleDateFormat(pattern, Locale.getDefault())
        return dateFormat.format(Date(timestamp))
    }
    
    /**
     * Get formatted log line for display
     */
    fun toDisplayString(): String {
        val time = getFormattedTimestamp("HH:mm:ss.SSS")
        val stackTrace = throwable?.let { "\n${it.stackTraceToString()}" } ?: ""
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
        
        // Check search query
        if (searchQuery.isNotBlank()) {
            val query = searchQuery.lowercase()
            val matchesMessage = entry.message.lowercase().contains(query)
            val matchesTag = entry.tag.lowercase().contains(query)
            if (!matchesMessage && !matchesTag) return false
        }
        
        // Check time range
        startTime?.let { if (entry.timestamp < it) return false }
        endTime?.let { if (entry.timestamp > it) return false }
        
        return true
    }
}
