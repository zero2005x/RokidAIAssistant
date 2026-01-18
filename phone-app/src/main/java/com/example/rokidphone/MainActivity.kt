package com.example.rokidphone

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.rememberAsyncImagePainter
import com.example.rokidcommon.protocol.ConnectionState
import com.example.rokidphone.data.AvailableModels
import com.example.rokidphone.data.SettingsRepository
import com.example.rokidphone.data.validateForChat
import com.example.rokidphone.data.validateForSpeech
import com.example.rokidphone.service.PhoneAIService
import com.example.rokidphone.ui.SettingsScreen
import com.example.rokidphone.ui.theme.RokidPhoneTheme
import com.example.rokidphone.viewmodel.PhoneViewModel
import java.io.File

class MainActivity : AppCompatActivity() {
    
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.values.all { it }
        if (allGranted) {
            startAIService()
        }
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Auto start service
        checkPermissionsAndStart()
        
        setContent {
            RokidPhoneTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    PhoneMainScreen(
                        onStartService = { checkPermissionsAndStart() },
                        onStopService = { stopAIService() }
                    )
                }
            }
        }
    }
    
    private fun checkPermissionsAndStart() {
        val requiredPermissions = mutableListOf<String>()
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            requiredPermissions.addAll(listOf(
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_ADVERTISE
            ))
        }
        
        requiredPermissions.add(Manifest.permission.RECORD_AUDIO)
        
        val notGranted = requiredPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        
        if (notGranted.isEmpty()) {
            startAIService()
        } else {
            permissionLauncher.launch(notGranted.toTypedArray())
        }
    }
    
    private fun startAIService() {
        val intent = Intent(this, PhoneAIService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }
    
    private fun stopAIService() {
        stopService(Intent(this, PhoneAIService::class.java))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PhoneMainScreen(
    viewModel: PhoneViewModel = viewModel(),
    onStartService: () -> Unit,
    onStopService: () -> Unit
) {
    val context = LocalContext.current
    val settingsRepository = remember { SettingsRepository.getInstance(context) }
    val settings by settingsRepository.settingsFlow.collectAsState()
    
    var showSettings by remember { mutableStateOf(false) }
    
    val uiState by viewModel.uiState.collectAsState()
    
    // Show API key warning dialog when triggered by service
    if (uiState.showApiKeyWarning) {
        ApiKeyMissingDialog(
            settings = settings,
            onGoToSettings = {
                viewModel.dismissApiKeyWarning()
                showSettings = true
            },
            onDismiss = {
                viewModel.dismissApiKeyWarning()
            }
        )
    }
    
    if (showSettings) {
        SettingsScreen(
            settings = settings,
            onSettingsChange = { newSettings ->
                settingsRepository.saveSettings(newSettings)
            },
            onBack = { showSettings = false }
        )
    } else {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(stringResource(R.string.app_title)) },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer
                    ),
                    actions = {
                        IconButton(onClick = { showSettings = true }) {
                            Icon(Icons.Default.Settings, contentDescription = stringResource(R.string.settings))
                        }
                    }
                )
            }
        ) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(16.dp)
            ) {
                // Connection status card
                ConnectionStatusCard(
                    connectionState = uiState.connectionState,
                    glassesName = uiState.connectedGlassesName,
                    onConnect = { viewModel.startScanning() },
                    onDisconnect = { viewModel.disconnect() }
                )
                
                Spacer(modifier = Modifier.height(16.dp))
                
                // Camera capture card - only visible when connected
                if (uiState.connectionState == ConnectionState.CONNECTED) {
                    CameraCaptureCard(
                        onCapturePhoto = { viewModel.requestCapturePhoto() }
                    )
                    
                    Spacer(modifier = Modifier.height(16.dp))
                }
                
                // Show latest captured photo if available
                uiState.latestPhotoPath?.let { photoPath ->
                    LatestPhotoCard(photoPath = photoPath)
                    Spacer(modifier = Modifier.height(16.dp))
                }
                
                // Service control card
                ServiceControlCard(
                    isServiceRunning = uiState.isServiceRunning,
                    onStart = onStartService,
                    onStop = onStopService
                )
                
                Spacer(modifier = Modifier.height(16.dp))
                
                // Conversation history
                ConversationHistory(
                    conversations = uiState.conversations,
                    modifier = Modifier.weight(1f)
                )
                
                // Status bar - show current model
                val currentModel = AvailableModels.findModel(settings.aiModelId)
                StatusBar(
                    processingStatus = uiState.processingStatus,
                    aiModel = currentModel?.displayName ?: settings.aiModelId
                )
            }
        }
    }
}

@Composable
fun ConnectionStatusCard(
    connectionState: ConnectionState,
    glassesName: String?,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = when (connectionState) {
                ConnectionState.CONNECTED -> MaterialTheme.colorScheme.primaryContainer
                ConnectionState.CONNECTING, ConnectionState.RECONNECTING -> 
                    MaterialTheme.colorScheme.tertiaryContainer
                else -> MaterialTheme.colorScheme.surfaceVariant
            }
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = when (connectionState) {
                    ConnectionState.CONNECTED -> Icons.Default.BluetoothConnected
                    ConnectionState.CONNECTING, ConnectionState.RECONNECTING -> 
                        Icons.Default.BluetoothSearching
                    else -> Icons.Default.BluetoothDisabled
                },
                contentDescription = null,
                modifier = Modifier.size(40.dp)
            )
            
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
                    fontWeight = FontWeight.Bold
                )
                
                if (glassesName != null) {
                    Text(
                        text = glassesName,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            
            when (connectionState) {
                ConnectionState.CONNECTED -> {
                    Button(onClick = onDisconnect) {
                        Text(stringResource(R.string.disconnect))
                    }
                }
                ConnectionState.CONNECTING, ConnectionState.RECONNECTING -> {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        strokeWidth = 2.dp
                    )
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
fun CameraCaptureCard(
    onCapturePhoto: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.CameraAlt,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.size(40.dp)
            )
            
            Spacer(modifier = Modifier.width(16.dp))
            
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.camera_capture),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = stringResource(R.string.camera_capture_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            
            Button(
                onClick = onCapturePhoto,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary
                )
            ) {
                Icon(
                    imageVector = Icons.Default.PhotoCamera,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(stringResource(R.string.capture))
            }
        }
    }
}

@Composable
fun LatestPhotoCard(photoPath: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Image,
                    contentDescription = null,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.latest_photo),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }
            
            Spacer(modifier = Modifier.height(12.dp))
            
            // Display the photo
            val file = remember(photoPath) { File(photoPath) }
            if (file.exists()) {
                Image(
                    painter = rememberAsyncImagePainter(file),
                    contentDescription = "Captured photo",
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(200.dp)
                        .clip(RoundedCornerShape(8.dp)),
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
fun ServiceControlCard(
    isServiceRunning: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = if (isServiceRunning) Icons.Default.PlayCircle else Icons.Default.StopCircle,
                contentDescription = null,
                tint = if (isServiceRunning) Color(0xFF4CAF50) else Color.Gray,
                modifier = Modifier.size(40.dp)
            )
            
            Spacer(modifier = Modifier.width(16.dp))
            
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.ai_service),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = if (isServiceRunning) stringResource(R.string.service_running) else stringResource(R.string.service_stopped),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            
            Button(
                onClick = if (isServiceRunning) onStop else onStart,
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isServiceRunning) 
                        MaterialTheme.colorScheme.error 
                    else 
                        MaterialTheme.colorScheme.primary
                )
            ) {
                Text(if (isServiceRunning) stringResource(R.string.stop) else stringResource(R.string.start))
            }
        }
    }
}

@Composable
fun ConversationHistory(
    conversations: List<ConversationItem>,
    modifier: Modifier = Modifier
) {
    Card(modifier = modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = stringResource(R.string.conversation_history),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            
            Spacer(modifier = Modifier.height(8.dp))
            
            if (conversations.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = stringResource(R.string.no_conversation_history),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyColumn(modifier = Modifier.weight(1f)) {
                    items(conversations) { item ->
                        ConversationBubble(item = item)
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                }
            }
        }
    }
}

@Composable
fun ConversationBubble(item: ConversationItem) {
    val isUser = item.role == "user"
    
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
    ) {
        Card(
            colors = CardDefaults.cardColors(
                containerColor = if (isUser) 
                    MaterialTheme.colorScheme.primaryContainer 
                else 
                    MaterialTheme.colorScheme.secondaryContainer
            ),
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
fun StatusBar(
    processingStatus: String?,
    aiModel: String
) {
    val ready = stringResource(R.string.ready)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = processingStatus ?: ready,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        
        Text(
            text = "AI: $aiModel",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

data class ConversationItem(
    val role: String,
    val content: String,
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * Dialog shown when API keys are not configured
 */
@Composable
fun ApiKeyMissingDialog(
    settings: com.example.rokidphone.data.ApiSettings,
    onGoToSettings: () -> Unit,
    onDismiss: () -> Unit
) {
    val chatValidation = settings.validateForChat()
    val speechValidation = settings.validateForSpeech()
    
    val hasChatIssue = chatValidation !is com.example.rokidphone.data.SettingsValidationResult.Valid
    val hasSpeechIssue = speechValidation !is com.example.rokidphone.data.SettingsValidationResult.Valid
    
    // Build the message based on issues
    val message = buildString {
        if (hasChatIssue) {
            when (chatValidation) {
                is com.example.rokidphone.data.SettingsValidationResult.MissingApiKey -> {
                    append("• ")
                    append(stringResource(chatValidation.provider.displayNameResId))
                    append(" API Key ")
                    append(stringResource(R.string.api_key_not_set))
                    append("\n")
                }
                is com.example.rokidphone.data.SettingsValidationResult.InvalidConfiguration -> {
                    append("• ")
                    append(chatValidation.message)
                    append("\n")
                }
                else -> {}
            }
        }
        if (hasSpeechIssue) {
            when (speechValidation) {
                is com.example.rokidphone.data.SettingsValidationResult.MissingSpeechService -> {
                    append("• ")
                    append(stringResource(R.string.speech_service_not_configured))
                }
                else -> {}
            }
        }
    }
    
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                Icons.Default.Warning,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error
            )
        },
        title = {
            Text(stringResource(R.string.api_key_required))
        },
        text = {
            Column {
                Text(message)
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.api_key_setup_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            Button(onClick = onGoToSettings) {
                Text(stringResource(R.string.go_to_settings))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.later))
            }
        }
    )
}
