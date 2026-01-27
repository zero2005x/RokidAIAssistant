package com.example.rokidphone.ui.logs

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.rokidphone.data.log.LogEntry
import com.example.rokidphone.data.log.LogLevel
import com.example.rokidphone.viewmodel.LogViewerViewModel
import kotlinx.coroutines.launch
import java.io.File

/**
 * Log Viewer Screen
 * Provides full log management functionality including viewing, filtering, exporting, and deleting logs
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogViewerScreen(
    onNavigateBack: () -> Unit,
    viewModel: LogViewerViewModel = viewModel()
) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    
    val filteredLogs by viewModel.filteredLogs.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val stats by viewModel.stats.collectAsState()
    val availableTags by viewModel.availableTags.collectAsState()
    val selectedLevel by viewModel.selectedLevel.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()
    val selectedTags by viewModel.selectedTags.collectAsState()
    val autoScroll by viewModel.autoScroll.collectAsState()
    val message by viewModel.message.collectAsState()
    val exportedFiles by viewModel.exportedFiles.collectAsState()
    
    // Show message as toast
    LaunchedEffect(message) {
        message?.let {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
            viewModel.clearMessage()
        }
    }
    
    // Auto-scroll to bottom
    val listState = rememberLazyListState()
    LaunchedEffect(filteredLogs.size, autoScroll) {
        if (autoScroll && filteredLogs.isNotEmpty()) {
            listState.animateScrollToItem(filteredLogs.size - 1)
        }
    }
    
    var showFilterDialog by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var showExportedFilesDialog by remember { mutableStateOf(false) }
    var showStatsDialog by remember { mutableStateOf(false) }
    var showMoreMenu by remember { mutableStateOf(false) }
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Log Viewer") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    // Refresh button
                    IconButton(onClick = { viewModel.refreshLogs() }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                    }
                    
                    // Filter button
                    IconButton(onClick = { showFilterDialog = true }) {
                        Badge(
                            modifier = Modifier.offset(x = 8.dp, y = (-8).dp),
                            containerColor = if (selectedLevel != LogLevel.VERBOSE || 
                                selectedTags.isNotEmpty() || 
                                searchQuery.isNotBlank()) MaterialTheme.colorScheme.primary 
                            else Color.Transparent
                        ) {
                            Icon(Icons.Default.FilterList, contentDescription = "Filter")
                        }
                    }
                    
                    // More menu
                    Box {
                        IconButton(onClick = { showMoreMenu = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = "More")
                        }
                        
                        DropdownMenu(
                            expanded = showMoreMenu,
                            onDismissRequest = { showMoreMenu = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("Export Logs") },
                                onClick = {
                                    showMoreMenu = false
                                    viewModel.exportLogs()
                                },
                                leadingIcon = { Icon(Icons.Default.Download, null) }
                            )
                            DropdownMenuItem(
                                text = { Text("Copy to Clipboard") },
                                onClick = {
                                    showMoreMenu = false
                                    val logsText = viewModel.getLogsForShare()
                                    clipboardManager.setText(AnnotatedString(logsText))
                                    Toast.makeText(context, "Logs copied to clipboard", Toast.LENGTH_SHORT).show()
                                },
                                leadingIcon = { Icon(Icons.Default.ContentCopy, null) }
                            )
                            DropdownMenuItem(
                                text = { Text("Exported Files") },
                                onClick = {
                                    showMoreMenu = false
                                    viewModel.refreshExportedFiles()
                                    showExportedFilesDialog = true
                                },
                                leadingIcon = { Icon(Icons.Default.Folder, null) }
                            )
                            DropdownMenuItem(
                                text = { Text("Statistics") },
                                onClick = {
                                    showMoreMenu = false
                                    viewModel.refreshStats()
                                    showStatsDialog = true
                                },
                                leadingIcon = { Icon(Icons.Default.Analytics, null) }
                            )
                            HorizontalDivider()
                            DropdownMenuItem(
                                text = { Text("Delete Logs...", color = MaterialTheme.colorScheme.error) },
                                onClick = {
                                    showMoreMenu = false
                                    showDeleteDialog = true
                                },
                                leadingIcon = { 
                                    Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error) 
                                }
                            )
                        }
                    }
                }
            )
        },
        bottomBar = {
            // Status bar
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.surfaceVariant,
                tonalElevation = 4.dp
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "${filteredLogs.size} entries" + 
                            if (stats?.totalCount != filteredLogs.size) " (${stats?.totalCount} total)" else "",
                        style = MaterialTheme.typography.bodySmall
                    )
                    
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Auto-scroll", style = MaterialTheme.typography.bodySmall)
                        Switch(
                            checked = autoScroll,
                            onCheckedChange = { viewModel.toggleAutoScroll() },
                            modifier = Modifier.padding(start = 4.dp)
                        )
                    }
                }
            }
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            if (isLoading && filteredLogs.isEmpty()) {
                CircularProgressIndicator(
                    modifier = Modifier.align(Alignment.Center)
                )
            } else if (filteredLogs.isEmpty()) {
                Column(
                    modifier = Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        Icons.Default.Description,
                        contentDescription = null,
                        modifier = Modifier.size(64.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        "No logs found",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(onClick = { viewModel.refreshLogs() }) {
                        Text("Load Logs")
                    }
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(filteredLogs, key = { it.id }) { entry ->
                        LogEntryItem(
                            entry = entry,
                            onCopy = {
                                clipboardManager.setText(AnnotatedString(entry.toExportString()))
                                Toast.makeText(context, "Log copied", Toast.LENGTH_SHORT).show()
                            }
                        )
                    }
                }
            }
            
            // Loading indicator overlay
            if (isLoading && filteredLogs.isNotEmpty()) {
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.TopCenter)
                )
            }
        }
    }
    
    // Filter Dialog
    if (showFilterDialog) {
        FilterDialog(
            selectedLevel = selectedLevel,
            searchQuery = searchQuery,
            availableTags = availableTags,
            selectedTags = selectedTags,
            onLevelChange = { viewModel.setLogLevel(it) },
            onSearchChange = { viewModel.setSearchQuery(it) },
            onTagToggle = { viewModel.toggleTag(it) },
            onClearFilters = { viewModel.clearAllFilters() },
            onDismiss = { showFilterDialog = false }
        )
    }
    
    // Delete Dialog
    if (showDeleteDialog) {
        DeleteLogsDialog(
            onClearAll = { viewModel.clearAllLogs() },
            onDeleteFiltered = { viewModel.deleteFilteredLogs() },
            onDeleteOld = { hours -> viewModel.deleteOldLogs(hours) },
            onDeleteBelowLevel = { level -> viewModel.deleteLogsBelowLevel(level) },
            onClearSystemLogcat = { viewModel.clearSystemLogcat() },
            onDismiss = { showDeleteDialog = false }
        )
    }
    
    // Exported Files Dialog
    if (showExportedFilesDialog) {
        ExportedFilesDialog(
            files = exportedFiles,
            onViewFile = { file ->
                scope.launch {
                    val content = viewModel.readExportedFile(file)
                    clipboardManager.setText(AnnotatedString(content))
                    Toast.makeText(context, "File content copied to clipboard", Toast.LENGTH_SHORT).show()
                }
            },
            onDeleteFile = { viewModel.deleteExportedFile(it) },
            onDeleteAll = { viewModel.deleteAllExportedFiles() },
            onDismiss = { showExportedFilesDialog = false }
        )
    }
    
    // Stats Dialog
    if (showStatsDialog) {
        StatsDialog(
            stats = stats,
            onDismiss = { showStatsDialog = false }
        )
    }
}

@Composable
private fun LogEntryItem(
    entry: LogEntry,
    onCopy: () -> Unit
) {
    val backgroundColor = when (entry.level) {
        LogLevel.ERROR, LogLevel.ASSERT -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f)
        LogLevel.WARN -> Color(0xFFFFF3E0)
        LogLevel.INFO -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.2f)
        else -> Color.Transparent
    }
    
    val levelColor = when (entry.level) {
        LogLevel.ERROR, LogLevel.ASSERT -> MaterialTheme.colorScheme.error
        LogLevel.WARN -> Color(0xFFFF9800)
        LogLevel.INFO -> MaterialTheme.colorScheme.primary
        LogLevel.DEBUG -> Color(0xFF4CAF50)
        LogLevel.VERBOSE -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(backgroundColor)
            .clickable(onClick = onCopy)
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Timestamp
            Text(
                text = entry.getFormattedTimestamp("HH:mm:ss.SSS"),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontFamily = FontFamily.Monospace
            )
            
            Spacer(modifier = Modifier.width(4.dp))
            
            // Level badge
            Surface(
                shape = RoundedCornerShape(2.dp),
                color = levelColor.copy(alpha = 0.2f)
            ) {
                Text(
                    text = entry.level.tag,
                    modifier = Modifier.padding(horizontal = 4.dp),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = levelColor,
                    fontFamily = FontFamily.Monospace
                )
            }
            
            Spacer(modifier = Modifier.width(4.dp))
            
            // Tag
            Text(
                text = entry.tag,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false)
            )
        }
        
        // Message
        Text(
            text = entry.message,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            lineHeight = 14.sp,
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(top = 2.dp)
        )
        
        // Stacktrace if present
        entry.throwable?.let { throwable ->
            Text(
                text = throwable.stackTraceToString(),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
                lineHeight = 12.sp,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(top = 4.dp)
            )
        }
        
        HorizontalDivider(
            modifier = Modifier.padding(top = 4.dp),
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
        )
    }
}

@Composable
private fun FilterDialog(
    selectedLevel: LogLevel,
    searchQuery: String,
    availableTags: Set<String>,
    selectedTags: Set<String>,
    onLevelChange: (LogLevel) -> Unit,
    onSearchChange: (String) -> Unit,
    onTagToggle: (String) -> Unit,
    onClearFilters: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Filter Logs") },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                // Search
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = onSearchChange,
                    label = { Text("Search") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    leadingIcon = { Icon(Icons.Default.Search, null) },
                    trailingIcon = {
                        if (searchQuery.isNotBlank()) {
                            IconButton(onClick = { onSearchChange("") }) {
                                Icon(Icons.Default.Clear, "Clear")
                            }
                        }
                    }
                )
                
                Spacer(modifier = Modifier.height(16.dp))
                
                // Log level
                Text("Minimum Level", style = MaterialTheme.typography.labelMedium)
                Spacer(modifier = Modifier.height(8.dp))
                
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    LogLevel.entries.forEach { level ->
                        FilterChip(
                            selected = selectedLevel == level,
                            onClick = { onLevelChange(level) },
                            label = { Text(level.name) }
                        )
                    }
                }
                
                if (availableTags.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(16.dp))
                    Text("Tags", style = MaterialTheme.typography.labelMedium)
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    // Show top 20 most common tags
                    val displayTags = availableTags.take(20)
                    
                    Column {
                        displayTags.chunked(3).forEach { row ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                row.forEach { tag ->
                                    FilterChip(
                                        selected = tag in selectedTags,
                                        onClick = { onTagToggle(tag) },
                                        label = { 
                                            Text(
                                                tag, 
                                                maxLines = 1, 
                                                overflow = TextOverflow.Ellipsis
                                            ) 
                                        },
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                                // Fill empty space if row has less than 3 items
                                repeat(3 - row.size) {
                                    Spacer(modifier = Modifier.weight(1f))
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Done")
            }
        },
        dismissButton = {
            TextButton(onClick = onClearFilters) {
                Text("Clear All")
            }
        }
    )
}

@Composable
private fun DeleteLogsDialog(
    onClearAll: () -> Unit,
    onDeleteFiltered: () -> Unit,
    onDeleteOld: (Int) -> Unit,
    onDeleteBelowLevel: (LogLevel) -> Unit,
    onClearSystemLogcat: () -> Unit,
    onDismiss: () -> Unit
) {
    var showConfirmClearAll by remember { mutableStateOf(false) }
    
    if (showConfirmClearAll) {
        AlertDialog(
            onDismissRequest = { showConfirmClearAll = false },
            title = { Text("Clear All Logs?") },
            text = { Text("This will delete all in-memory logs. This action cannot be undone.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        onClearAll()
                        showConfirmClearAll = false
                        onDismiss()
                    }
                ) {
                    Text("Clear All", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showConfirmClearAll = false }) {
                    Text("Cancel")
                }
            }
        )
    } else {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("Delete Logs") },
            text = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        "Choose what to delete:",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    
                    Spacer(modifier = Modifier.height(16.dp))
                    
                    // Delete options
                    ListItem(
                        headlineContent = { Text("Clear All Logs") },
                        supportingContent = { Text("Delete all in-memory logs") },
                        leadingContent = { Icon(Icons.Default.DeleteForever, null) },
                        modifier = Modifier.clickable { showConfirmClearAll = true }
                    )
                    
                    HorizontalDivider()
                    
                    ListItem(
                        headlineContent = { Text("Delete Filtered") },
                        supportingContent = { Text("Delete currently visible logs") },
                        leadingContent = { Icon(Icons.Default.FilterAlt, null) },
                        modifier = Modifier.clickable {
                            onDeleteFiltered()
                            onDismiss()
                        }
                    )
                    
                    HorizontalDivider()
                    
                    ListItem(
                        headlineContent = { Text("Delete Old (1 hour)") },
                        supportingContent = { Text("Delete logs older than 1 hour") },
                        leadingContent = { Icon(Icons.Default.Schedule, null) },
                        modifier = Modifier.clickable {
                            onDeleteOld(1)
                            onDismiss()
                        }
                    )
                    
                    HorizontalDivider()
                    
                    ListItem(
                        headlineContent = { Text("Delete DEBUG & Below") },
                        supportingContent = { Text("Keep only INFO, WARN, ERROR") },
                        leadingContent = { Icon(Icons.Default.BugReport, null) },
                        modifier = Modifier.clickable {
                            onDeleteBelowLevel(LogLevel.INFO)
                            onDismiss()
                        }
                    )
                    
                    HorizontalDivider()
                    
                    ListItem(
                        headlineContent = { Text("Clear System Logcat") },
                        supportingContent = { Text("Clear Android system logs") },
                        leadingContent = { Icon(Icons.Default.CleaningServices, null) },
                        modifier = Modifier.clickable {
                            onClearSystemLogcat()
                            onDismiss()
                        }
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = onDismiss) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
private fun ExportedFilesDialog(
    files: List<File>,
    onViewFile: (File) -> Unit,
    onDeleteFile: (File) -> Unit,
    onDeleteAll: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Exported Log Files") },
        text = {
            if (files.isEmpty()) {
                Text("No exported files found")
            } else {
                LazyColumn(modifier = Modifier.fillMaxWidth()) {
                    items(files) { file ->
                        ListItem(
                            headlineContent = { Text(file.name) },
                            supportingContent = { 
                                Text("${file.length() / 1024} KB") 
                            },
                            trailingContent = {
                                Row {
                                    IconButton(onClick = { onViewFile(file) }) {
                                        Icon(Icons.Default.ContentCopy, "Copy content")
                                    }
                                    IconButton(onClick = { onDeleteFile(file) }) {
                                        Icon(
                                            Icons.Default.Delete, 
                                            "Delete",
                                            tint = MaterialTheme.colorScheme.error
                                        )
                                    }
                                }
                            }
                        )
                        HorizontalDivider()
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Close")
            }
        },
        dismissButton = {
            if (files.isNotEmpty()) {
                TextButton(onClick = {
                    onDeleteAll()
                    onDismiss()
                }) {
                    Text("Delete All", color = MaterialTheme.colorScheme.error)
                }
            }
        }
    )
}

@Composable
private fun StatsDialog(
    stats: com.example.rokidphone.data.log.LogStats?,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Log Statistics") },
        text = {
            if (stats == null) {
                Text("No statistics available")
            } else {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text("Total Entries: ${stats.totalCount}")
                    Text("Time Range: ${stats.getTimeRangeString()}")
                    Text("Exported Files: ${stats.exportedFilesCount}")
                    
                    Spacer(modifier = Modifier.height(16.dp))
                    Text("By Level:", style = MaterialTheme.typography.labelMedium)
                    stats.countByLevel.forEach { (level, count) ->
                        Text("  ${level.name}: $count")
                    }
                    
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("Top Tags:", style = MaterialTheme.typography.labelMedium)
                    stats.countByTag.entries
                        .sortedByDescending { it.value }
                        .take(10)
                        .forEach { (tag, count) ->
                            Text("  $tag: $count")
                        }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Close")
            }
        }
    )
}
