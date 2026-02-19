package com.example.rokidphone.ui.recording

import androidx.compose.animation.*
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.rokidphone.R
import com.example.rokidphone.data.db.*
import com.example.rokidphone.ui.theme.ExtendedTheme
import com.example.rokidphone.viewmodel.*
import java.text.SimpleDateFormat
import java.util.*

/**
 * Recordings Screen - Manage audio recordings
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecordingsScreen(
    viewModel: RecordingViewModel = viewModel(),
    onBack: () -> Unit = {},
    onRecordingDetail: (String) -> Unit = {}
) {
    val uiState by viewModel.uiState.collectAsState()
    
    var showFilterMenu by remember { mutableStateOf(false) }
    var showSortMenu by remember { mutableStateOf(false) }
    var showRecordingOptions by remember { mutableStateOf(false) }
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { 
                    if (uiState.isSelectionMode) {
                        Text(stringResource(R.string.recordings_selected_count, uiState.selectedIds.size))
                    } else {
                        Text(stringResource(R.string.recordings))
                    }
                },
                navigationIcon = {
                    IconButton(onClick = {
                        if (uiState.isSelectionMode) {
                            viewModel.clearSelection()
                        } else {
                            onBack()
                        }
                    }) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back)
                        )
                    }
                },
                actions = {
                    if (uiState.isSelectionMode) {
                        IconButton(onClick = { viewModel.selectAll() }) {
                            Icon(Icons.Default.SelectAll, contentDescription = stringResource(R.string.recordings_select_all))
                        }
                        IconButton(onClick = { viewModel.showDeleteDialog() }) {
                            Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.delete))
                        }
                    } else {
                        // Filter button
                        Box {
                            IconButton(onClick = { showFilterMenu = true }) {
                                Icon(Icons.Default.FilterList, contentDescription = stringResource(R.string.recordings_filter))
                            }
                            FilterMenu(
                                expanded = showFilterMenu,
                                currentFilter = uiState.filter,
                                onDismiss = { showFilterMenu = false },
                                onFilterSelected = { 
                                    viewModel.setFilter(it)
                                    showFilterMenu = false
                                }
                            )
                        }
                        
                        // Sort button
                        Box {
                            IconButton(onClick = { showSortMenu = true }) {
                                Icon(Icons.AutoMirrored.Filled.Sort, contentDescription = stringResource(R.string.recordings_sort))
                            }
                            SortMenu(
                                expanded = showSortMenu,
                                currentSort = uiState.sort,
                                onDismiss = { showSortMenu = false },
                                onSortSelected = {
                                    viewModel.setSort(it)
                                    showSortMenu = false
                                }
                            )
                        }
                        
                        IconButton(onClick = { viewModel.toggleSelectionMode() }) {
                            Icon(Icons.Default.Checklist, contentDescription = stringResource(R.string.recordings_select))
                        }
                    }
                }
            )
        },
        floatingActionButton = {
            // Recording FAB
            Box {
                val recordingState = uiState.recordingState
                
                if (recordingState is RecordingState.Recording) {
                    // Show stop button when recording
                    ExtendedFloatingActionButton(
                        onClick = { viewModel.stopRecording() },
                        containerColor = MaterialTheme.colorScheme.error,
                        icon = { Icon(Icons.Default.Stop, contentDescription = null) },
                        text = { 
                            Text(viewModel.formatDuration(recordingState.durationMs))
                        }
                    )
                } else {
                    // Show recording options
                    FloatingActionButton(
                        onClick = { showRecordingOptions = true }
                    ) {
                        Icon(Icons.Default.Mic, contentDescription = stringResource(R.string.start_recording))
                    }
                }
                
                // Recording options menu
                DropdownMenu(
                    expanded = showRecordingOptions,
                    onDismissRequest = { showRecordingOptions = false }
                ) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.record_from_phone)) },
                        onClick = {
                            viewModel.startPhoneRecording()
                            showRecordingOptions = false
                        },
                        leadingIcon = { Icon(Icons.Default.Phone, null) }
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.record_from_glasses)) },
                        onClick = {
                            viewModel.startGlassesRecording()
                            showRecordingOptions = false
                        },
                        leadingIcon = { Icon(Icons.Default.Visibility, null) }
                    )
                }
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // Search bar
            SearchBar(
                query = uiState.searchQuery,
                onQueryChange = { viewModel.setSearchQuery(it) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            )
            
            // Statistics
            if (!uiState.isSelectionMode && uiState.statistics.totalCount > 0) {
                StatisticsCard(
                    statistics = uiState.statistics,
                    formatDuration = { viewModel.formatDuration(it) },
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }
            
            // Recordings list
            if (uiState.isLoading) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            } else if (uiState.filteredRecordings.isEmpty()) {
                EmptyState(
                    searchQuery = uiState.searchQuery,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(
                        items = uiState.filteredRecordings,
                        key = { it.id }
                    ) { recording ->
                        RecordingItem(
                            recording = recording,
                            isSelected = uiState.selectedIds.contains(recording.id),
                            isSelectionMode = uiState.isSelectionMode,
                            isProcessing = uiState.processingRecordingId == recording.id,
                            formatDuration = { viewModel.formatDuration(it) },
                            onClick = {
                                if (uiState.isSelectionMode) {
                                    viewModel.toggleSelection(recording.id)
                                } else {
                                    onRecordingDetail(recording.id)
                                }
                            },
                            onLongClick = {
                                if (!uiState.isSelectionMode) {
                                    viewModel.toggleSelectionMode()
                                    viewModel.toggleSelection(recording.id)
                                }
                            },
                            onFavoriteClick = { viewModel.toggleFavorite(recording.id) },
                            onTranscribeClick = { viewModel.transcribeRecording(recording.id) },
                            onAnalyzeClick = { viewModel.analyzeWithAi(recording.id) },
                            onEditClick = { viewModel.showEditDialog(recording) },
                            onDeleteClick = { viewModel.deleteRecording(recording.id) }
                        )
                    }
                }
            }
        }
    }
    
    // Delete confirmation dialog
    if (uiState.showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { viewModel.hideDeleteDialog() },
            title = { Text(stringResource(R.string.delete_recordings)) },
            text = { 
                Text(stringResource(R.string.delete_recordings_confirm, uiState.selectedIds.size))
            },
            confirmButton = {
                TextButton(
                    onClick = { viewModel.deleteSelectedRecordings() },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text(stringResource(R.string.delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.hideDeleteDialog() }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }
    
    // Edit dialog
    if (uiState.showEditDialog && uiState.editingRecording != null) {
        EditRecordingDialog(
            recording = uiState.editingRecording!!,
            onDismiss = { viewModel.hideEditDialog() },
            onSave = { id, title -> viewModel.updateTitle(id, title) }
        )
    }
    
    // Error snackbar
    uiState.error?.let { error ->
        LaunchedEffect(error) {
            kotlinx.coroutines.delay(3000)
            viewModel.clearError()
        }
    }
}

@Composable
private fun SearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = modifier,
        placeholder = { Text(stringResource(R.string.search_recordings)) },
        leadingIcon = { Icon(Icons.Default.Search, null) },
        trailingIcon = {
            if (query.isNotBlank()) {
                IconButton(onClick = { onQueryChange("") }) {
                    Icon(Icons.Default.Clear, contentDescription = stringResource(R.string.recordings_clear_search))
                }
            }
        },
        singleLine = true,
        shape = MaterialTheme.shapes.large
    )
}

@Composable
private fun StatisticsCard(
    statistics: RecordingStatistics,
    formatDuration: (Long) -> String,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            StatItem(
                icon = Icons.Default.AudioFile,
                value = statistics.totalCount.toString(),
                label = stringResource(R.string.total_recordings)
            )
            StatItem(
                icon = Icons.Default.Timer,
                value = formatDuration(statistics.totalDurationMs),
                label = stringResource(R.string.total_duration)
            )
        }
    }
}

@Composable
private fun StatItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    value: String,
    label: String
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(
            icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RecordingItem(
    recording: RecordingEntity,
    isSelected: Boolean,
    isSelectionMode: Boolean,
    isProcessing: Boolean,
    formatDuration: (Long) -> String,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onFavoriteClick: () -> Unit,
    onTranscribeClick: () -> Unit,
    onAnalyzeClick: () -> Unit,
    onEditClick: () -> Unit,
    onDeleteClick: () -> Unit
) {
    var showMenu by remember { mutableStateOf(false) }
    
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            ),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected)
                MaterialTheme.colorScheme.primaryContainer
            else
                MaterialTheme.colorScheme.surface
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Selection checkbox or source icon
            if (isSelectionMode) {
                Checkbox(
                    checked = isSelected,
                    onCheckedChange = { onClick() }
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(
                            when (recording.source) {
                                RecordingSource.PHONE -> MaterialTheme.colorScheme.primaryContainer
                                RecordingSource.GLASSES -> MaterialTheme.colorScheme.tertiaryContainer
                            }
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = when (recording.source) {
                            RecordingSource.PHONE -> Icons.Default.Phone
                            RecordingSource.GLASSES -> Icons.Default.Visibility
                        },
                        contentDescription = null,
                        tint = when (recording.source) {
                            RecordingSource.PHONE -> MaterialTheme.colorScheme.primary
                            RecordingSource.GLASSES -> MaterialTheme.colorScheme.tertiary
                        }
                    )
                }
            }
            
            Spacer(modifier = Modifier.width(12.dp))
            
            // Content
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = recording.title,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    
                    if (recording.isFavorite) {
                        Spacer(modifier = Modifier.width(4.dp))
                        Icon(
                            Icons.Default.Star,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
                
                Spacer(modifier = Modifier.height(4.dp))
                
                // Duration and date
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = formatDuration(recording.durationMs),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "•",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = formatDate(recording.createdAt),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                
                // Status chips
                Spacer(modifier = Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    StatusChip(recording.status)
                    
                    if (recording.transcript != null) {
                        AssistChip(
                            onClick = { },
                            label = { Text(stringResource(R.string.recordings_transcribed_chip)) },
                            leadingIcon = {
                                Icon(
                                    Icons.Default.TextFields,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                            },
                            modifier = Modifier.height(24.dp)
                        )
                    }
                    
                    if (recording.aiResponse != null) {
                        AssistChip(
                            onClick = { },
                            label = { Text(stringResource(R.string.recordings_ai_chip)) },
                            leadingIcon = {
                                Icon(
                                    Icons.Default.AutoAwesome,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                            },
                            modifier = Modifier.height(24.dp)
                        )
                    }
                }
                
                // Transcript preview
                recording.transcript?.let { transcript ->
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = transcript,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            
            // Actions
            if (!isSelectionMode) {
                if (isProcessing) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        strokeWidth = 2.dp
                    )
                } else {
                    Box {
                        IconButton(onClick = { showMenu = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.recordings_more))
                        }
                        
                        DropdownMenu(
                            expanded = showMenu,
                            onDismissRequest = { showMenu = false }
                        ) {
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        if (recording.isFavorite) {
                                            stringResource(R.string.recordings_remove_favorite)
                                        } else {
                                            stringResource(R.string.recordings_add_favorite)
                                        }
                                    )
                                },
                                onClick = {
                                    onFavoriteClick()
                                    showMenu = false
                                },
                                leadingIcon = {
                                    Icon(
                                        if (recording.isFavorite) Icons.Default.StarBorder else Icons.Default.Star,
                                        null
                                    )
                                }
                            )
                            
                            if (recording.transcript == null) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.transcribe)) },
                                    onClick = {
                                        onTranscribeClick()
                                        showMenu = false
                                    },
                                    leadingIcon = { Icon(Icons.Default.TextFields, null) }
                                )
                            }
                            
                            if (recording.transcript != null && recording.aiResponse == null) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.analyze_with_ai)) },
                                    onClick = {
                                        onAnalyzeClick()
                                        showMenu = false
                                    },
                                    leadingIcon = { Icon(Icons.Default.AutoAwesome, null) }
                                )
                            }
                            
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.edit)) },
                                onClick = {
                                    onEditClick()
                                    showMenu = false
                                },
                                leadingIcon = { Icon(Icons.Default.Edit, null) }
                            )
                            
                            HorizontalDivider()
                            
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.delete)) },
                                onClick = {
                                    onDeleteClick()
                                    showMenu = false
                                },
                                leadingIcon = { 
                                    Icon(
                                        Icons.Default.Delete, 
                                        null,
                                        tint = MaterialTheme.colorScheme.error
                                    )
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusChip(status: RecordingStatus) {
    val color: Color
    val text: String
    
    when (status) {
        RecordingStatus.RECORDING -> {
            color = MaterialTheme.colorScheme.error
            text = stringResource(R.string.recordings_status_recording)
        }
        RecordingStatus.COMPLETED -> {
            color = ExtendedTheme.colors.success
            text = stringResource(R.string.recordings_status_completed)
        }
        RecordingStatus.TRANSCRIBING -> {
            color = MaterialTheme.colorScheme.tertiary
            text = stringResource(R.string.recordings_status_transcribing)
        }
        RecordingStatus.TRANSCRIBED -> {
            color = MaterialTheme.colorScheme.primary
            text = stringResource(R.string.recordings_status_transcribed)
        }
        RecordingStatus.ANALYZING -> {
            color = MaterialTheme.colorScheme.tertiary
            text = stringResource(R.string.recordings_status_analyzing)
        }
        RecordingStatus.ANALYZED -> {
            color = MaterialTheme.colorScheme.primary
            text = stringResource(R.string.recordings_status_analyzed)
        }
        RecordingStatus.ERROR -> {
            color = MaterialTheme.colorScheme.error
            text = stringResource(R.string.recordings_status_error)
        }
    }
    
    Surface(
        shape = MaterialTheme.shapes.small,
        color = color.copy(alpha = 0.1f)
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelSmall,
            color = color
        )
    }
}

@Composable
private fun FilterMenu(
    expanded: Boolean,
    currentFilter: RecordingFilter,
    onDismiss: () -> Unit,
    onFilterSelected: (RecordingFilter) -> Unit
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        RecordingFilter.entries.forEach { filter ->
            DropdownMenuItem(
                text = {
                    Text(
                        when (filter) {
                            RecordingFilter.ALL -> stringResource(R.string.filter_all)
                            RecordingFilter.PHONE -> stringResource(R.string.filter_phone)
                            RecordingFilter.GLASSES -> stringResource(R.string.filter_glasses)
                            RecordingFilter.FAVORITES -> stringResource(R.string.filter_favorites)
                        }
                    )
                },
                onClick = { onFilterSelected(filter) },
                trailingIcon = {
                    if (filter == currentFilter) {
                        Icon(Icons.Default.Check, null)
                    }
                }
            )
        }
    }
}

@Composable
private fun SortMenu(
    expanded: Boolean,
    currentSort: RecordingSort,
    onDismiss: () -> Unit,
    onSortSelected: (RecordingSort) -> Unit
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        RecordingSort.entries.forEach { sort ->
            DropdownMenuItem(
                text = {
                    Text(
                        when (sort) {
                            RecordingSort.DATE_DESC -> stringResource(R.string.sort_date_desc)
                            RecordingSort.DATE_ASC -> stringResource(R.string.sort_date_asc)
                            RecordingSort.DURATION_DESC -> stringResource(R.string.sort_duration_desc)
                            RecordingSort.DURATION_ASC -> stringResource(R.string.sort_duration_asc)
                            RecordingSort.NAME_ASC -> stringResource(R.string.sort_name_asc)
                            RecordingSort.NAME_DESC -> stringResource(R.string.sort_name_desc)
                        }
                    )
                },
                onClick = { onSortSelected(sort) },
                trailingIcon = {
                    if (sort == currentSort) {
                        Icon(Icons.Default.Check, null)
                    }
                }
            )
        }
    }
}

@Composable
private fun EmptyState(
    searchQuery: String,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = if (searchQuery.isBlank()) Icons.Outlined.Mic else Icons.Outlined.SearchOff,
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = if (searchQuery.isBlank()) 
                stringResource(R.string.no_recordings) 
            else 
                stringResource(R.string.no_recordings_found),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = if (searchQuery.isBlank())
                stringResource(R.string.tap_mic_to_record)
            else
                stringResource(R.string.try_different_search),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun EditRecordingDialog(
    recording: RecordingEntity,
    onDismiss: () -> Unit,
    onSave: (String, String) -> Unit
) {
    var title by remember { mutableStateOf(recording.title) }
    
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.edit_recording)) },
        text = {
            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                label = { Text(stringResource(R.string.title)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(recording.id, title) },
                enabled = title.isNotBlank()
            ) {
                Text(stringResource(R.string.save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}

private fun formatDate(timestamp: Long): String {
    val sdf = SimpleDateFormat("MMM d, HH:mm", Locale.getDefault())
    return sdf.format(Date(timestamp))
}
