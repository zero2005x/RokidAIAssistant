package com.example.rokidphone.ui.recording

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.rokidphone.R
import com.example.rokidphone.data.db.*
import com.example.rokidphone.ui.theme.ExtendedTheme
import com.example.rokidphone.viewmodel.RecordingViewModel
import java.text.SimpleDateFormat
import java.util.*

/**
 * Recording Detail Screen
 * Shows full details of a recording with transcript and AI response
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecordingDetailScreen(
    recordingId: String,
    viewModel: RecordingViewModel = viewModel(),
    onBack: () -> Unit = {}
) {
    val uiState by viewModel.uiState.collectAsState()
    var recording by remember { mutableStateOf<RecordingEntity?>(null) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var showEditDialog by remember { mutableStateOf(false) }
    var isPlaying by remember { mutableStateOf(false) }
    
    // Load recording
    LaunchedEffect(recordingId) {
        viewModel.getRecordingById(recordingId)?.let {
            recording = it
        }
    }
    
    // Also observe from flow for real-time updates
    LaunchedEffect(uiState.recordings) {
        uiState.recordings.find { it.id == recordingId }?.let {
            recording = it
        }
    }
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(recording?.title ?: stringResource(R.string.recording_detail)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back)
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { 
                        recording?.let { viewModel.toggleFavorite(it.id) }
                    }) {
                        Icon(
                            if (recording?.isFavorite == true) Icons.Filled.Star else Icons.Filled.StarBorder,
                            contentDescription = "Favorite",
                            tint = if (recording?.isFavorite == true) 
                                MaterialTheme.colorScheme.primary 
                            else 
                                MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    IconButton(onClick = { showEditDialog = true }) {
                        Icon(Icons.Default.Edit, contentDescription = stringResource(R.string.edit))
                    }
                    IconButton(onClick = { showDeleteDialog = true }) {
                        Icon(
                            Icons.Default.Delete, 
                            contentDescription = stringResource(R.string.delete),
                            tint = MaterialTheme.colorScheme.error
                        )
                    }
                }
            )
        }
    ) { padding ->
        recording?.let { rec ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
            ) {
                // Player card
                PlayerCard(
                    recording = rec,
                    isPlaying = isPlaying,
                    formatDuration = { viewModel.formatDuration(it) },
                    onPlayPause = { isPlaying = !isPlaying },
                    modifier = Modifier.padding(16.dp)
                )
                
                // Info section
                InfoSection(
                    recording = rec,
                    formatDuration = { viewModel.formatDuration(it) },
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
                
                Spacer(modifier = Modifier.height(16.dp))
                
                // Transcript section
                TranscriptSection(
                    recording = rec,
                    isProcessing = uiState.processingRecordingId == rec.id,
                    onTranscribe = { viewModel.transcribeRecording(rec.id) },
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
                
                Spacer(modifier = Modifier.height(16.dp))
                
                // AI Response section
                AiResponseSection(
                    recording = rec,
                    isProcessing = uiState.processingRecordingId == rec.id,
                    onAnalyze = { viewModel.analyzeWithAi(rec.id) },
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
                
                // Notes section
                rec.notes?.let { notes ->
                    Spacer(modifier = Modifier.height(16.dp))
                    NotesSection(
                        notes = notes,
                        onUpdateNotes = { viewModel.updateNotes(rec.id, it) },
                        modifier = Modifier.padding(horizontal = 16.dp)
                    )
                }
                
                Spacer(modifier = Modifier.height(32.dp))
            }
        } ?: run {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
        }
    }
    
    // Delete dialog
    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text(stringResource(R.string.delete_recording)) },
            text = { Text(stringResource(R.string.delete_recording_confirm)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        recording?.let { viewModel.deleteRecording(it.id) }
                        showDeleteDialog = false
                        onBack()
                    },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text(stringResource(R.string.delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }
    
    // Edit dialog
    if (showEditDialog && recording != null) {
        EditRecordingDetailDialog(
            recording = recording!!,
            onDismiss = { showEditDialog = false },
            onSave = { title, notes ->
                viewModel.updateTitle(recording!!.id, title)
                viewModel.updateNotes(recording!!.id, notes)
                showEditDialog = false
            }
        )
    }
}

@Composable
private fun PlayerCard(
    recording: RecordingEntity,
    isPlaying: Boolean,
    formatDuration: (Long) -> String,
    onPlayPause: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Source icon
            Icon(
                imageVector = when (recording.source) {
                    RecordingSource.PHONE -> Icons.Default.Phone
                    RecordingSource.GLASSES -> Icons.Default.Visibility
                },
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = MaterialTheme.colorScheme.onPrimaryContainer
            )
            
            Spacer(modifier = Modifier.height(8.dp))
            
            Text(
                text = when (recording.source) {
                    RecordingSource.PHONE -> stringResource(R.string.recording_source_phone)
                    RecordingSource.GLASSES -> stringResource(R.string.recording_source_glasses)
                },
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
            
            Spacer(modifier = Modifier.height(16.dp))
            
            // Duration
            Text(
                text = formatDuration(recording.durationMs),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
            
            Spacer(modifier = Modifier.height(16.dp))
            
            // Play button
            FilledIconButton(
                onClick = onPlayPause,
                modifier = Modifier.size(64.dp),
                enabled = recording.filePath.isNotBlank()
            ) {
                Icon(
                    imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = if (isPlaying) "Pause" else "Play",
                    modifier = Modifier.size(32.dp)
                )
            }
            
            if (recording.filePath.isBlank()) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.audio_file_not_available),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                )
            }
        }
    }
}

@Composable
private fun InfoSection(
    recording: RecordingEntity,
    formatDuration: (Long) -> String,
    modifier: Modifier = Modifier
) {
    Card(modifier = modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = stringResource(R.string.recording_info),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            
            Spacer(modifier = Modifier.height(12.dp))
            
            InfoRow(
                label = stringResource(R.string.created),
                value = formatDateTime(recording.createdAt)
            )
            
            InfoRow(
                label = stringResource(R.string.duration),
                value = formatDuration(recording.durationMs)
            )
            
            if (recording.fileSizeBytes > 0) {
                InfoRow(
                    label = stringResource(R.string.file_size),
                    value = formatFileSize(recording.fileSizeBytes)
                )
            }
            
            InfoRow(
                label = stringResource(R.string.sample_rate),
                value = "${recording.sampleRate} Hz"
            )
            
            InfoRow(
                label = stringResource(R.string.status),
                value = recording.status.name
            )
        }
    }
}

@Composable
private fun InfoRow(
    label: String,
    value: String
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
private fun TranscriptSection(
    recording: RecordingEntity,
    isProcessing: Boolean,
    onTranscribe: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(modifier = modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.TextFields,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.transcript),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
                
                if (recording.transcript == null && !isProcessing) {
                    FilledTonalButton(onClick = onTranscribe) {
                        Icon(Icons.Default.RecordVoiceOver, null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(stringResource(R.string.transcribe))
                    }
                }
            }
            
            Spacer(modifier = Modifier.height(12.dp))
            
            if (isProcessing && recording.transcript == null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.transcribing))
                }
            } else if (recording.transcript != null) {
                SelectionContainer {
                    Text(
                        text = recording.transcript!!,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                
                if (recording.transcribedAt != null) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.transcribed_at, formatDateTime(recording.transcribedAt!!)),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                Text(
                    text = stringResource(R.string.no_transcript),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun AiResponseSection(
    recording: RecordingEntity,
    isProcessing: Boolean,
    onAnalyze: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(modifier = modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.AutoAwesome,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.tertiary
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.ai_response),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
                
                if (recording.transcript != null && recording.aiResponse == null && !isProcessing) {
                    FilledTonalButton(onClick = onAnalyze) {
                        Icon(Icons.Default.Psychology, null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(stringResource(R.string.analyze))
                    }
                }
            }
            
            Spacer(modifier = Modifier.height(12.dp))
            
            if (isProcessing && recording.aiResponse == null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.analyzing))
                }
            } else if (recording.aiResponse != null) {
                SelectionContainer {
                    Text(
                        text = recording.aiResponse!!,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                
                if (recording.modelId != null) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.analyzed_by, recording.modelId!!),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else if (recording.transcript == null) {
                Text(
                    text = stringResource(R.string.transcribe_first),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Text(
                    text = stringResource(R.string.no_ai_response),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun NotesSection(
    notes: String,
    onUpdateNotes: (String?) -> Unit,
    modifier: Modifier = Modifier
) {
    var isEditing by remember { mutableStateOf(false) }
    var editedNotes by remember { mutableStateOf(notes) }
    
    Card(modifier = modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.notes),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                
                IconButton(onClick = { isEditing = !isEditing }) {
                    Icon(
                        if (isEditing) Icons.Default.Check else Icons.Default.Edit,
                        contentDescription = null
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(8.dp))
            
            if (isEditing) {
                OutlinedTextField(
                    value = editedNotes,
                    onValueChange = { editedNotes = it },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3
                )
                
                Spacer(modifier = Modifier.height(8.dp))
                
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = {
                        editedNotes = notes
                        isEditing = false
                    }) {
                        Text(stringResource(R.string.cancel))
                    }
                    Button(onClick = {
                        onUpdateNotes(editedNotes.ifBlank { null })
                        isEditing = false
                    }) {
                        Text(stringResource(R.string.save))
                    }
                }
            } else {
                Text(
                    text = notes,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
    }
}

@Composable
private fun EditRecordingDetailDialog(
    recording: RecordingEntity,
    onDismiss: () -> Unit,
    onSave: (String, String?) -> Unit
) {
    var title by remember { mutableStateOf(recording.title) }
    var notes by remember { mutableStateOf(recording.notes ?: "") }
    
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.edit_recording)) },
        text = {
            Column {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text(stringResource(R.string.title)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                
                Spacer(modifier = Modifier.height(16.dp))
                
                OutlinedTextField(
                    value = notes,
                    onValueChange = { notes = it },
                    label = { Text(stringResource(R.string.notes)) },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(title, notes.ifBlank { null }) },
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

// Extension function for ViewModel
suspend fun RecordingViewModel.getRecordingById(id: String): RecordingEntity? {
    return uiState.value.recordings.find { it.id == id }
}

private fun formatDateTime(timestamp: Long): String {
    val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
    return sdf.format(Date(timestamp))
}

private fun formatFileSize(bytes: Long): String {
    return when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${bytes / 1024} KB"
        else -> String.format("%.1f MB", bytes / (1024.0 * 1024.0))
    }
}
