package com.example.rokidphone.viewmodel

import android.app.Application
import android.content.Intent
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.rokidphone.data.log.LogEntry
import com.example.rokidphone.data.log.LogFilter
import com.example.rokidphone.data.log.LogLevel
import com.example.rokidphone.data.log.LogManager
import com.example.rokidphone.data.log.LogStats
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.io.File

/**
 * ViewModel for Log Viewer screen
 * Manages log display, filtering, export, and deletion operations
 */
class LogViewerViewModel(application: Application) : AndroidViewModel(application) {
    
    private val logManager = LogManager.getInstance(application)
    
    // Current filter state
    private val _filter = MutableStateFlow(LogFilter())
    val filter: StateFlow<LogFilter> = _filter.asStateFlow()
    
    // Filtered logs
    private val _filteredLogs = MutableStateFlow<List<LogEntry>>(emptyList())
    val filteredLogs: StateFlow<List<LogEntry>> = _filteredLogs.asStateFlow()
    
    // Log statistics
    private val _stats = MutableStateFlow<LogStats?>(null)
    val stats: StateFlow<LogStats?> = _stats.asStateFlow()
    
    // Exported files
    private val _exportedFiles = MutableStateFlow<List<File>>(emptyList())
    val exportedFiles: StateFlow<List<File>> = _exportedFiles.asStateFlow()
    
    // Loading state
    val isLoading: StateFlow<Boolean> = logManager.isLoading
    
    // Available tags for filtering
    val availableTags: StateFlow<Set<String>> = logManager.availableTags
    
    // Selected log level for filtering
    private val _selectedLevel = MutableStateFlow(LogLevel.VERBOSE)
    val selectedLevel: StateFlow<LogLevel> = _selectedLevel.asStateFlow()
    
    // Search query
    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()
    
    // Selected tags for filtering
    private val _selectedTags = MutableStateFlow<Set<String>>(emptySet())
    val selectedTags: StateFlow<Set<String>> = _selectedTags.asStateFlow()
    
    // Auto-scroll enabled
    private val _autoScroll = MutableStateFlow(true)
    val autoScroll: StateFlow<Boolean> = _autoScroll.asStateFlow()
    
    // Operation result message
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()
    
    init {
        // Observe filter changes and update filtered logs
        viewModelScope.launch {
            combine(
                logManager.logs,
                _selectedLevel,
                _searchQuery,
                _selectedTags
            ) { logs, level, query, tags ->
                val filter = LogFilter(
                    minLevel = level,
                    searchQuery = query,
                    tags = tags
                )
                _filter.value = filter
                logs.filter { filter.matches(it) }
            }.collect { filtered ->
                _filteredLogs.value = filtered
            }
        }
        
        // Initial load
        refreshLogs()
        refreshStats()
        refreshExportedFiles()
    }
    
    // ==================== Read Operations ====================
    
    /**
     * Refresh logs from system logcat
     */
    fun refreshLogs() {
        viewModelScope.launch {
            logManager.loadSystemLogsToBuffer(1000)
            refreshStats()
        }
    }
    
    /**
     * Refresh log statistics
     */
    fun refreshStats() {
        _stats.value = logManager.getLogStats()
    }
    
    /**
     * Refresh list of exported files
     */
    fun refreshExportedFiles() {
        _exportedFiles.value = logManager.getExportedLogFiles()
    }
    
    // ==================== Filter Operations ====================
    
    /**
     * Set minimum log level filter
     */
    fun setLogLevel(level: LogLevel) {
        _selectedLevel.value = level
    }
    
    /**
     * Set search query
     */
    fun setSearchQuery(query: String) {
        _searchQuery.value = query
    }
    
    /**
     * Toggle tag selection
     */
    fun toggleTag(tag: String) {
        val current = _selectedTags.value.toMutableSet()
        if (tag in current) {
            current.remove(tag)
        } else {
            current.add(tag)
        }
        _selectedTags.value = current
    }
    
    /**
     * Clear all tag filters
     */
    fun clearTagFilters() {
        _selectedTags.value = emptySet()
    }
    
    /**
     * Clear all filters
     */
    fun clearAllFilters() {
        _selectedLevel.value = LogLevel.VERBOSE
        _searchQuery.value = ""
        _selectedTags.value = emptySet()
    }
    
    /**
     * Toggle auto-scroll
     */
    fun toggleAutoScroll() {
        _autoScroll.value = !_autoScroll.value
    }
    
    // ==================== Export Operations ====================
    
    /**
     * Export current filtered logs to file
     */
    fun exportLogs() {
        viewModelScope.launch {
            val file = logManager.exportLogs(_filter.value)
            if (file != null) {
                _message.value = "Logs exported to: ${file.name}"
                refreshExportedFiles()
            } else {
                _message.value = "Failed to export logs"
            }
        }
    }
    
    /**
     * Export all logs (unfiltered)
     */
    fun exportAllLogs() {
        viewModelScope.launch {
            val file = logManager.exportLogs()
            if (file != null) {
                _message.value = "All logs exported to: ${file.name}"
                refreshExportedFiles()
            } else {
                _message.value = "Failed to export logs"
            }
        }
    }
    
    /**
     * Get logs as shareable string
     */
    fun getLogsForShare(): String {
        return logManager.exportLogsAsString(_filter.value)
    }
    
    /**
     * Create share intent for log file
     */
    fun createShareIntent(file: File): Intent? {
        return try {
            val context = getApplication<Application>()
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )
            Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        } catch (e: Exception) {
            _message.value = "Failed to create share intent: ${e.message}"
            null
        }
    }
    
    /**
     * Read exported log file content
     */
    suspend fun readExportedFile(file: File): String {
        return logManager.readExportedLogFile(file)
    }
    
    // ==================== Delete Operations ====================
    
    /**
     * Clear all in-memory logs
     */
    fun clearAllLogs() {
        logManager.clearAllLogs()
        refreshStats()
        _message.value = "All logs cleared"
    }
    
    /**
     * Delete logs matching current filter
     */
    fun deleteFilteredLogs() {
        val count = logManager.deleteLogs(_filter.value)
        refreshStats()
        _message.value = "Deleted $count log entries"
    }
    
    /**
     * Delete logs by tag
     */
    fun deleteLogsByTag(tag: String) {
        val count = logManager.deleteLogsByTag(tag)
        refreshStats()
        _message.value = "Deleted $count entries with tag: $tag"
    }
    
    /**
     * Delete logs older than specified hours
     */
    fun deleteOldLogs(hours: Int) {
        val millis = hours * 60L * 60L * 1000L
        val count = logManager.deleteOldLogs(millis)
        refreshStats()
        _message.value = "Deleted $count entries older than $hours hours"
    }
    
    /**
     * Delete logs below specified level
     */
    fun deleteLogsBelowLevel(level: LogLevel) {
        val count = logManager.deleteLogsBelowLevel(level)
        refreshStats()
        _message.value = "Deleted $count entries below ${level.name} level"
    }
    
    /**
     * Delete an exported log file
     */
    fun deleteExportedFile(file: File) {
        if (logManager.deleteExportedLogFile(file)) {
            _message.value = "Deleted: ${file.name}"
            refreshExportedFiles()
        } else {
            _message.value = "Failed to delete: ${file.name}"
        }
    }
    
    /**
     * Delete all exported log files
     */
    fun deleteAllExportedFiles() {
        val count = logManager.deleteAllExportedLogFiles()
        refreshExportedFiles()
        _message.value = "Deleted $count exported files"
    }
    
    /**
     * Clear system logcat
     */
    fun clearSystemLogcat() {
        viewModelScope.launch {
            val success = logManager.clearSystemLogcat()
            _message.value = if (success) {
                "System logcat cleared"
            } else {
                "Failed to clear system logcat (may require root)"
            }
        }
    }
    
    /**
     * Clear message after showing
     */
    fun clearMessage() {
        _message.value = null
    }
}
