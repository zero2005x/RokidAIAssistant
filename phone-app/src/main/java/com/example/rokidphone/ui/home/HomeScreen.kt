package com.example.rokidphone.ui.home

import androidx.compose.animation.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.BluetoothSearching
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil.compose.rememberAsyncImagePainter
import com.example.rokidcommon.protocol.ConnectionState
import com.example.rokidphone.ConversationItem
import com.example.rokidphone.R
import com.example.rokidphone.data.AvailableModels
import com.example.rokidphone.data.db.RecordingSource
import com.example.rokidphone.data.db.RecordingState
import com.example.rokidphone.ui.components.*
import com.example.rokidphone.ui.theme.AppShapeTokens
import com.example.rokidphone.ui.theme.ExtendedTheme
import java.io.File

/**
 * Redesigned Home Screen with Material Design 3 patterns
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    connectionState: ConnectionState,
    connectedGlassesName: String?,
    isServiceRunning: Boolean,
    latestPhotoPath: String?,
    processingStatus: String?,
    currentModelId: String,
    conversations: List<ConversationItem>,
    recordingState: RecordingState = RecordingState.Idle,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onStartService: () -> Unit,
    onStopService: () -> Unit,
    onCapturePhoto: () -> Unit,
    onStartPhoneRecording: () -> Unit = {},
    onStartGlassesRecording: () -> Unit = {},
    onPauseRecording: () -> Unit = {},
    onStopRecording: () -> Unit = {},
    onViewConversationHistory: () -> Unit = {},
    onViewGallery: () -> Unit = {},
    onViewRecordings: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val currentModel = AvailableModels.findModel(currentModelId)
    
    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        contentPadding = PaddingValues(vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Welcome header
        item {
            WelcomeHeader()
        }
        
        // Status overview cards
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Glasses connection status
                InfoCard(
                    icon = when (connectionState) {
                        ConnectionState.CONNECTED -> Icons.Default.BluetoothConnected
                        ConnectionState.CONNECTING, ConnectionState.RECONNECTING -> Icons.AutoMirrored.Filled.BluetoothSearching
                        else -> Icons.Default.BluetoothDisabled
                    },
                    title = stringResource(R.string.glasses_status),
                    value = when (connectionState) {
                        ConnectionState.CONNECTED -> connectedGlassesName ?: stringResource(R.string.connected)
                        ConnectionState.CONNECTING -> stringResource(R.string.connecting)
                        ConnectionState.RECONNECTING -> stringResource(R.string.reconnecting)
                        ConnectionState.ERROR -> stringResource(R.string.connection_error)
                        else -> stringResource(R.string.disconnected)
                    },
                    valueColor = when (connectionState) {
                        ConnectionState.CONNECTED -> ExtendedTheme.colors.success
                        ConnectionState.CONNECTING, ConnectionState.RECONNECTING -> MaterialTheme.colorScheme.tertiary
                        ConnectionState.ERROR -> MaterialTheme.colorScheme.error
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.weight(1f)
                )
                
                // Current AI model
                InfoCard(
                    icon = Icons.Default.Psychology,
                    title = stringResource(R.string.current_model),
                    value = currentModel?.displayName ?: currentModelId,
                    modifier = Modifier.weight(1f)
                )
            }
        }
        
        // Connection control card
        item {
            GlassesConnectionCard(
                connectionState = connectionState,
                glassesName = connectedGlassesName,
                onConnect = onConnect,
                onDisconnect = onDisconnect
            )
        }
        
        // Camera capture card (only when connected)
        item {
            AnimatedSection(visible = connectionState == ConnectionState.CONNECTED) {
                CameraCaptureCard(
                    onCapturePhoto = onCapturePhoto
                )
            }
        }
        
        // Recording control card
        item {
            RecordingControlCard(
                recordingState = recordingState,
                isGlassesConnected = connectionState == ConnectionState.CONNECTED,
                onStartPhoneRecording = onStartPhoneRecording,
                onStartGlassesRecording = onStartGlassesRecording,
                onPauseRecording = onPauseRecording,
                onStopRecording = onStopRecording
            )
        }
        
        // Latest photo (if available)
        latestPhotoPath?.let { photoPath ->
            item {
                LatestPhotoCard(photoPath = photoPath)
            }
        }
        
        // Service control card
        item {
            ServiceCard(
                isRunning = isServiceRunning,
                onStart = onStartService,
                onStop = onStopService
            )
        }
        
        // Quick access cards row
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Chat history quick access
                QuickAccessCard(
                    icon = Icons.Default.History,
                    title = stringResource(R.string.conversation_history),
                    onClick = onViewConversationHistory,
                    modifier = Modifier.weight(1f)
                )
                
                // Gallery quick access
                QuickAccessCard(
                    icon = Icons.Default.PhotoLibrary,
                    title = stringResource(R.string.nav_gallery),
                    onClick = onViewGallery,
                    modifier = Modifier.weight(1f)
                )
            }
        }
        
        // Second row of quick access cards
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Recordings quick access
                QuickAccessCard(
                    icon = Icons.Default.Mic,
                    title = stringResource(R.string.recordings),
                    onClick = onViewRecordings,
                    modifier = Modifier.weight(1f)
                )
                
                // Placeholder for future feature
                Spacer(modifier = Modifier.weight(1f))
            }
        }
        
        // Recent voice conversations section (current session)
        if (conversations.isNotEmpty()) {
            item {
                SectionHeaderWithAction(
                    title = stringResource(R.string.home_current_session),
                    actionLabel = stringResource(R.string.home_view_all),
                    onAction = onViewConversationHistory
                )
            }
            
            item {
                // Display conversations in a card
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.medium,
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        conversations.takeLast(6).forEach { item ->
                            ConversationBubbleCard(item = item)
                        }
                    }
                }
            }
        }
        
        // Status bar
        item {
            StatusFooter(
                processingStatus = processingStatus,
                modelName = currentModel?.displayName ?: currentModelId
            )
        }
    }
}

@Composable
private fun QuickAccessCard(
    icon: ImageVector,
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        onClick = onClick,
        modifier = modifier,
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f)
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(28.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
        }
    }
}

@Composable
private fun SectionHeaderWithAction(
    title: String,
    actionLabel: String,
    onAction: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface
        )
        TextButton(onClick = onAction) {
            Text(
                text = actionLabel,
                style = MaterialTheme.typography.labelMedium
            )
            Spacer(modifier = Modifier.width(4.dp))
            Icon(
                Icons.AutoMirrored.Filled.ArrowForward,
                contentDescription = null,
                modifier = Modifier.size(16.dp)
            )
        }
    }
}

@Composable
private fun WelcomeHeader() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
    ) {
        Text(
            text = stringResource(R.string.home_welcome),
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = stringResource(R.string.home_subtitle),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun GlassesConnectionCard(
    connectionState: ConnectionState,
    glassesName: String?,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit
) {
    val isConnected = connectionState == ConnectionState.CONNECTED
    val isConnecting = connectionState == ConnectionState.CONNECTING || 
                       connectionState == ConnectionState.RECONNECTING
    
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = when (connectionState) {
                ConnectionState.CONNECTED -> ExtendedTheme.colors.successContainer
                ConnectionState.CONNECTING, ConnectionState.RECONNECTING -> 
                    MaterialTheme.colorScheme.tertiaryContainer
                ConnectionState.ERROR -> MaterialTheme.colorScheme.errorContainer
                else -> MaterialTheme.colorScheme.surfaceVariant
            }
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Animated icon container
            Surface(
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.7f),
                modifier = Modifier.size(56.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    if (isConnecting) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(28.dp),
                            strokeWidth = 3.dp
                        )
                    } else {
                        Icon(
                            imageVector = when (connectionState) {
                                ConnectionState.CONNECTED -> Icons.Default.BluetoothConnected
                                ConnectionState.ERROR -> Icons.Default.BluetoothDisabled
                                else -> Icons.AutoMirrored.Filled.BluetoothSearching
                            },
                            contentDescription = null,
                            modifier = Modifier.size(28.dp),
                            tint = when (connectionState) {
                                ConnectionState.CONNECTED -> ExtendedTheme.colors.success
                                ConnectionState.ERROR -> MaterialTheme.colorScheme.error
                                else -> MaterialTheme.colorScheme.onSurfaceVariant
                            }
                        )
                    }
                }
            }
            
            Spacer(modifier = Modifier.width(16.dp))
            
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = when (connectionState) {
                        ConnectionState.CONNECTED -> stringResource(R.string.connected)
                        ConnectionState.CONNECTING -> stringResource(R.string.connecting)
                        ConnectionState.RECONNECTING -> stringResource(R.string.reconnecting)
                        ConnectionState.ERROR -> stringResource(R.string.connection_error)
                        else -> stringResource(R.string.disconnected)
                    },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                
                if (!glassesName.isNullOrBlank()) {
                    Text(
                        text = glassesName,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            
            when {
                isConnected -> {
                    FilledTonalButton(onClick = onDisconnect) {
                        Text(stringResource(R.string.disconnect))
                    }
                }
                isConnecting -> {
                    // Show nothing, circular indicator is shown in icon
                }
                else -> {
                    Button(onClick = onConnect) {
                        Text(stringResource(R.string.connect))
                    }
                }
            }
        }
    }
}

@Composable
private fun CameraCaptureCard(
    onCapturePhoto: () -> Unit
) {
    ActionCard(
        icon = Icons.Default.CameraAlt,
        title = stringResource(R.string.camera_capture),
        subtitle = stringResource(R.string.camera_capture_hint),
        actionLabel = stringResource(R.string.capture),
        onAction = onCapturePhoto,
        iconContainerColor = MaterialTheme.colorScheme.secondaryContainer,
        iconColor = MaterialTheme.colorScheme.onSecondaryContainer
    )
}

@Composable
private fun LatestPhotoCard(photoPath: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Image,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.latest_photo),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
            }
            
            Spacer(modifier = Modifier.height(12.dp))
            
            val file = remember(photoPath) { File(photoPath) }
            if (file.exists()) {
                Image(
                    painter = rememberAsyncImagePainter(file),
                    contentDescription = "Captured photo",
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(180.dp)
                        .clip(AppShapeTokens.ImageContainer),
                    contentScale = ContentScale.Crop
                )
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(100.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = stringResource(R.string.photo_not_found),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun ServiceCard(
    isRunning: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit
) {
    ActionCard(
        icon = if (isRunning) Icons.Default.PlayCircle else Icons.Default.StopCircle,
        title = stringResource(R.string.ai_service),
        subtitle = if (isRunning) stringResource(R.string.service_running) else stringResource(R.string.service_stopped),
        actionLabel = if (isRunning) stringResource(R.string.stop) else stringResource(R.string.start),
        onAction = if (isRunning) onStop else onStart,
        iconContainerColor = if (isRunning) 
            ExtendedTheme.colors.successContainer 
        else 
            MaterialTheme.colorScheme.surfaceVariant,
        iconColor = if (isRunning) 
            ExtendedTheme.colors.success 
        else 
            MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
private fun ConversationBubbleCard(item: ConversationItem) {
    val isUser = item.role == "user"
    
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
    ) {
        Surface(
            shape = if (isUser) AppShapeTokens.MessageBubbleUser else AppShapeTokens.MessageBubbleAssistant,
            color = if (isUser)
                MaterialTheme.colorScheme.primaryContainer
            else
                MaterialTheme.colorScheme.secondaryContainer,
            modifier = Modifier.widthIn(max = 280.dp)
        ) {
            Text(
                text = item.content,
                modifier = Modifier.padding(12.dp),
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}

@Composable
private fun StatusFooter(
    processingStatus: String?,
    modelName: String
) {
    val ready = stringResource(R.string.ready)
    
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Info,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = processingStatus ?: ready,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Psychology,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = modelName,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

/**
 * Recording control card with start/pause/stop functionality
 */
@Composable
private fun RecordingControlCard(
    recordingState: RecordingState,
    isGlassesConnected: Boolean,
    onStartPhoneRecording: () -> Unit,
    onStartGlassesRecording: () -> Unit,
    onPauseRecording: () -> Unit,
    onStopRecording: () -> Unit
) {
    val isRecording = recordingState is RecordingState.Recording
    val isStopping = recordingState is RecordingState.Stopping
    
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = when {
                isRecording -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.7f)
                else -> MaterialTheme.colorScheme.surfaceVariant
            }
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp)
        ) {
            // Header
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.7f),
                    modifier = Modifier.size(48.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        if (isRecording) {
                            // Pulsing recording indicator
                            Icon(
                                imageVector = Icons.Default.FiberManualRecord,
                                contentDescription = null,
                                modifier = Modifier.size(24.dp),
                                tint = MaterialTheme.colorScheme.error
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Default.Mic,
                                contentDescription = null,
                                modifier = Modifier.size(24.dp),
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
                
                Spacer(modifier = Modifier.width(16.dp))
                
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.recording_control),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    
                    if (isRecording && recordingState is RecordingState.Recording) {
                        val durationText = formatRecordingDuration(recordingState.durationMs)
                        val sourceText = when (recordingState.source) {
                            RecordingSource.PHONE -> stringResource(R.string.recording_source_phone)
                            RecordingSource.GLASSES -> stringResource(R.string.recording_source_glasses)
                        }
                        Text(
                            text = "$sourceText • $durationText",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    } else {
                        Text(
                            text = stringResource(R.string.recording_ready),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            
            // Control buttons
            if (isRecording || isStopping) {
                // Recording controls: Pause & Stop
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // Pause button (disabled for now - MediaRecorder pause requires API 24+)
                    // OutlinedButton(
                    //     onClick = onPauseRecording,
                    //     modifier = Modifier.weight(1f),
                    //     enabled = !isStopping
                    // ) {
                    //     Icon(Icons.Default.Pause, contentDescription = null)
                    //     Spacer(modifier = Modifier.width(8.dp))
                    //     Text(stringResource(R.string.pause_recording))
                    // }
                    
                    // Stop button
                    Button(
                        onClick = onStopRecording,
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !isStopping,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error
                        )
                    ) {
                        if (isStopping) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onError
                            )
                        } else {
                            Icon(Icons.Default.Stop, contentDescription = null)
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (isStopping) 
                                stringResource(R.string.stopping) 
                            else 
                                stringResource(R.string.stop_and_send)
                        )
                    }
                }
            } else {
                // Start recording options
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // Phone recording button
                    Button(
                        onClick = onStartPhoneRecording,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary
                        )
                    ) {
                        Icon(Icons.Default.Smartphone, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(R.string.record_phone))
                    }
                    
                    // Glasses recording button (only when connected)
                    Button(
                        onClick = onStartGlassesRecording,
                        modifier = Modifier.weight(1f),
                        enabled = isGlassesConnected,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.secondary
                        )
                    ) {
                        Icon(Icons.Default.Visibility, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(R.string.record_glasses))
                    }
                }
            }
            
            // Error message
            if (recordingState is RecordingState.Error) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = recordingState.message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

/**
 * Format recording duration in MM:SS format
 */
private fun formatRecordingDuration(durationMs: Long): String {
    val seconds = (durationMs / 1000) % 60
    val minutes = (durationMs / 1000) / 60
    return "%02d:%02d".format(minutes, seconds)
}
