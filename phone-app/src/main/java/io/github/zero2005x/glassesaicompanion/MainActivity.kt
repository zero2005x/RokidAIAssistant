package io.github.zero2005x.glassesaicompanion

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import io.github.zero2005x.glassesaicompanion.data.SettingsRepository
import io.github.zero2005x.glassesaicompanion.data.validateForChat
import io.github.zero2005x.glassesaicompanion.data.validateForSpeech
import io.github.zero2005x.glassesaicompanion.service.PhoneAIService
import io.github.zero2005x.glassesaicompanion.ui.LlmParametersScreen
import io.github.zero2005x.glassesaicompanion.ui.TtsSettingsScreen
import io.github.zero2005x.glassesaicompanion.ui.SettingsScreen
import io.github.zero2005x.glassesaicompanion.ui.conversation.ChatScreen
import io.github.zero2005x.glassesaicompanion.ui.conversation.ConversationHistoryScreen
import io.github.zero2005x.glassesaicompanion.ui.gallery.ClearAllConfirmDialog
import io.github.zero2005x.glassesaicompanion.ui.gallery.DeleteConfirmDialog
import io.github.zero2005x.glassesaicompanion.ui.gallery.PhotoDetailScreen
import io.github.zero2005x.glassesaicompanion.ui.gallery.PhotoGalleryScreen
import io.github.zero2005x.glassesaicompanion.ui.home.HomeScreen
import io.github.zero2005x.glassesaicompanion.ui.logs.LogViewerScreen
import io.github.zero2005x.glassesaicompanion.ui.navigation.BottomNavDestination
import io.github.zero2005x.glassesaicompanion.ui.navigation.NavRoutes
import io.github.zero2005x.glassesaicompanion.ui.recording.RecordingDetailScreen
import io.github.zero2005x.glassesaicompanion.ui.recording.RecordingsScreen
import io.github.zero2005x.glassesaicompanion.ui.theme.RokidPhoneTheme
import io.github.zero2005x.glassesaicompanion.viewmodel.ConversationViewModel
import io.github.zero2005x.glassesaicompanion.viewmodel.PhotoGalleryViewModel
import io.github.zero2005x.glassesaicompanion.viewmodel.PhoneViewModel

class MainActivity : AppCompatActivity() {
    
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.isNotEmpty() && permissions.values.all { it }
        if (allGranted) {
            startAIService()
        } else {
            val denied = permissions.filterValues { !it }.keys
            android.util.Log.w("MainActivity", "Permissions denied: $denied - AI service not started")
            // TODO: show a rationale/snackbar and direct the user to app settings;
            // distinguish permanently-denied via shouldShowRequestPermissionRationale()
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
        } else {
            requiredPermissions.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        
        requiredPermissions.add(Manifest.permission.RECORD_AUDIO)
        
        // API 33+: foreground service notification requires this runtime permission
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requiredPermissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        
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
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
        } catch (e: Exception) {
            // ForegroundServiceStartNotAllowedException (API 31+), SecurityException /
            // IllegalStateException for missing FGS-type permissions (API 34+)
            android.util.Log.e("MainActivity", "Failed to start AI service", e)
            // TODO: surface the failure to the user
        }
    }
    
    private fun stopAIService() {
        stopService(Intent(this, PhoneAIService::class.java))
    }
}

/**
 * Main Screen with Bottom Navigation following Material Design 3
 */
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
    
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = navBackStackEntry?.destination
    
    val uiState by viewModel.uiState.collectAsState()
    
    // Check if initial setup is needed when settings are loaded.
    // Key on the derived boolean so unrelated settings changes (TTS rate,
    // model id, ...) don't re-trigger the check and re-show dismissed dialogs.
    val hasApiKey = settings.hasAnyApiKeyConfigured()
    LaunchedEffect(hasApiKey) {
        viewModel.checkInitialSetup(hasApiKey)
    }
    
    // Show initial setup dialog when no API key is configured
    if (uiState.showInitialSetup) {
        InitialSetupDialog(
            onGoToSettings = {
                viewModel.dismissInitialSetup()
                navController.navigate(NavRoutes.SETTINGS)
            },
            onDismiss = {
                viewModel.dismissInitialSetup()
            }
        )
    }
    
    // Show API key warning dialog when triggered by service
    if (uiState.showApiKeyWarning) {
        ApiKeyMissingDialog(
            settings = settings,
            onGoToSettings = {
                viewModel.dismissApiKeyWarning()
                navController.navigate(NavRoutes.SETTINGS)
            },
            onDismiss = {
                viewModel.dismissApiKeyWarning()
            }
        )
    }
    
    Scaffold(
        topBar = {
            // Only show top bar on Home screen
            if (currentDestination?.route == NavRoutes.HOME) {
                TopAppBar(
                    title = { Text(stringResource(R.string.app_title)) },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    )
                )
            }
        },
        bottomBar = {
            NavigationBar(
                containerColor = MaterialTheme.colorScheme.surface,
                tonalElevation = 0.dp
            ) {
                BottomNavDestination.entries.forEach { destination ->
                    val selected = currentDestination?.route == destination.route
                    NavigationBarItem(
                        icon = {
                            Icon(
                                imageVector = if (selected) destination.selectedIcon else destination.unselectedIcon,
                                contentDescription = stringResource(destination.labelResId)
                            )
                        },
                        label = { Text(stringResource(destination.labelResId)) },
                        selected = selected,
                        onClick = {
                            // Navigate to the selected destination
                            if (currentDestination?.route != destination.route) {
                                if (destination.route == NavRoutes.HOME) {
                                    // For HOME: keep the start destination at the
                                    // bottom of the stack and restore its state
                                    navController.navigate(destination.route) {
                                        popUpTo(navController.graph.startDestinationId) {
                                            inclusive = false
                                            saveState = true
                                        }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                } else {
                                    // For other bottom nav items: navigate normally
                                    navController.navigate(destination.route) {
                                        // Pop back to HOME but keep HOME in stack
                                        popUpTo(NavRoutes.HOME) {
                                            saveState = true
                                            inclusive = false
                                        }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                }
                            }
                        }
                    )
                }
            }
        }
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = NavRoutes.HOME,
            modifier = Modifier.padding(padding),
            enterTransition = { fadeIn(animationSpec = tween(300)) },
            exitTransition = { fadeOut(animationSpec = tween(300)) }
        ) {
            composable(NavRoutes.HOME) {
                HomeScreen(
                    connectionState = uiState.connectionState,
                    connectedGlassesName = uiState.connectedGlassesName,
                    isServiceRunning = uiState.isServiceRunning,
                    latestPhotoPath = uiState.latestPhotoPath,
                    processingStatus = uiState.processingStatus,
                    currentModelId = settings.aiModelId,
                    conversations = uiState.conversations,
                    recordingState = uiState.recordingState,
                    onConnect = { viewModel.startScanning() },
                    onDisconnect = { viewModel.disconnect() },
                    onStartService = onStartService,
                    onStopService = onStopService,
                    onCapturePhoto = { viewModel.requestCapturePhoto() },
                    onStartPhoneRecording = { viewModel.startPhoneRecording() },
                    onStartGlassesRecording = { viewModel.startGlassesRecording() },
                    onPauseRecording = { viewModel.pauseRecording() },
                    onStopRecording = { viewModel.stopRecording() },
                    onViewConversationHistory = { navController.navigate(NavRoutes.CHAT) },
                    onViewGallery = { navController.navigate(NavRoutes.GALLERY) },
                    onViewRecordings = { navController.navigate(NavRoutes.RECORDINGS) }
                )
            }
            
            composable(NavRoutes.RECORDINGS) {
                RecordingsScreen(
                    onBack = { navController.popBackStack() },
                    onRecordingDetail = { recordingId ->
                        navController.navigate(NavRoutes.recordingDetail(recordingId))
                    }
                )
            }
            
            composable(
                route = NavRoutes.RECORDING_DETAIL,
                arguments = listOf(
                    navArgument("recordingId") {
                        type = NavType.StringType
                    }
                )
            ) { backStackEntry ->
                val recordingId = backStackEntry.arguments?.getString("recordingId") ?: ""
                RecordingDetailScreen(
                    recordingId = recordingId,
                    onBack = { navController.popBackStack() }
                )
            }
            
            composable(NavRoutes.GALLERY) {
                // Photo Gallery screen
                val galleryViewModel: PhotoGalleryViewModel = viewModel()
                val groupedPhotos by galleryViewModel.groupedPhotos.collectAsState()
                val photos by galleryViewModel.photos.collectAsState()
                val photoCount by galleryViewModel.photoCount.collectAsState()
                val galleryUiState by galleryViewModel.uiState.collectAsState()
                val context = LocalContext.current
                
                // Reset detail view when entering gallery tab
                DisposableEffect(Unit) {
                    onDispose {
                        galleryViewModel.closePhotoDetail()
                        galleryViewModel.clearSelection()
                    }
                }
                
                // Show detail view if a photo is selected
                val currentDetailPhoto = galleryUiState.currentDetailPhoto
                if (currentDetailPhoto != null) {
                    PhotoDetailScreen(
                        photos = photos,
                        initialPhoto = currentDetailPhoto,
                        onBack = { galleryViewModel.closePhotoDetail() },
                        onDelete = { galleryViewModel.deletePhoto(it) },
                        onShare = { photo ->
                            galleryViewModel.sharePhoto(photo) { intent ->
                                context.startActivity(intent)
                            }
                        },
                        loadBitmap = { photoData, maxSize ->
                            galleryViewModel.loadBitmap(photoData, maxSize)
                        }
                    )
                } else {
                    PhotoGalleryScreen(
                        groupedPhotos = groupedPhotos,
                        photoCount = photoCount,
                        uiState = galleryUiState,
                        onPhotoClick = { galleryViewModel.openPhotoDetail(it) },
                        onPhotoLongClick = { galleryViewModel.togglePhotoSelection(it.id) },
                        onToggleSelection = { galleryViewModel.togglePhotoSelection(it) },
                        onSelectAll = { galleryViewModel.selectAll() },
                        onClearSelection = { galleryViewModel.clearSelection() },
                        onDeleteSelected = { galleryViewModel.showDeleteConfirmDialog() },
                        onShareSelected = { 
                            galleryViewModel.shareSelectedPhotos { intent ->
                                context.startActivity(intent)
                            }
                        },
                        onClearAll = { galleryViewModel.showClearAllDialog() },
                        onBack = { navController.popBackStack() },
                        loadBitmap = { photoData, maxSize ->
                            galleryViewModel.loadBitmap(photoData, maxSize)
                        }
                    )
                }
                
                // Delete confirmation dialog
                if (galleryUiState.showDeleteConfirmDialog) {
                    DeleteConfirmDialog(
                        count = galleryUiState.selectedPhotos.size,
                        onConfirm = { galleryViewModel.deleteSelectedPhotos() },
                        onDismiss = { galleryViewModel.hideDeleteConfirmDialog() }
                    )
                }
                
                // Clear all confirmation dialog
                if (galleryUiState.showClearAllDialog) {
                    ClearAllConfirmDialog(
                        onConfirm = { galleryViewModel.clearAllPhotos() },
                        onDismiss = { galleryViewModel.hideClearAllDialog() }
                    )
                }
            }
            
            composable(NavRoutes.CHAT) {
                // Full Chat screen with conversation management
                val conversationViewModel: ConversationViewModel = viewModel()
                val conversations by conversationViewModel.conversations.collectAsState()
                val currentConversationId by conversationViewModel.currentConversationId.collectAsState()
                val currentMessages by conversationViewModel.currentMessages.collectAsState()
                val currentConversation by conversationViewModel.currentConversation.collectAsState()
                val chatUiState by conversationViewModel.uiState.collectAsState()
                val inputText by conversationViewModel.inputText.collectAsState()
                val context = LocalContext.current
                
                // Reset conversation state when leaving chat tab
                DisposableEffect(Unit) {
                    onDispose {
                        conversationViewModel.closeCurrentConversation()
                    }
                }
                
                if (currentConversationId != null) {
                    // Show chat screen
                    ChatScreen(
                        conversationTitle = currentConversation?.title ?: stringResource(R.string.new_conversation),
                        messages = currentMessages,
                        isLoading = chatUiState.isLoading,
                        error = chatUiState.error,
                        inputText = inputText,
                        onInputChange = { conversationViewModel.updateInputText(it) },
                        onSendMessage = { conversationViewModel.sendMessage() },
                        onClearError = { conversationViewModel.clearError() },
                        onBack = { conversationViewModel.closeCurrentConversation() },
                        onClearHistory = { conversationViewModel.clearCurrentConversation() },
                        onExport = {
                            conversationViewModel.exportCurrentConversation { intent ->
                                context.startActivity(intent)
                            }
                        }
                    )
                } else {
                    // Show conversation history
                    ConversationHistoryScreen(
                        conversations = conversations,
                        onConversationClick = { conversationViewModel.selectConversation(it) },
                        onNewConversation = { conversationViewModel.createNewConversation() },
                        onDeleteConversation = { conversationViewModel.deleteConversation(it) },
                        onArchiveConversation = { conversationViewModel.archiveConversation(it) },
                        onPinConversation = { conversationViewModel.pinConversation(it) },
                        onBack = { navController.popBackStack() }
                    )
                }
            }
            
            composable(
                route = NavRoutes.CONVERSATION_DETAIL,
                arguments = listOf(navArgument("conversationId") { type = NavType.StringType })
            ) { backStackEntry ->
                val conversationId = backStackEntry.arguments?.getString("conversationId")
                if (conversationId == null) {
                    // Missing argument: pop back instead of rendering a blank screen
                    LaunchedEffect(Unit) { navController.popBackStack() }
                    return@composable
                }
                val conversationViewModel: ConversationViewModel = viewModel()
                val context = LocalContext.current
                
                // Select the conversation
                LaunchedEffect(conversationId) {
                    conversationViewModel.selectConversation(conversationId)
                }
                
                val currentMessages by conversationViewModel.currentMessages.collectAsState()
                val currentConversation by conversationViewModel.currentConversation.collectAsState()
                val chatUiState by conversationViewModel.uiState.collectAsState()
                val inputText by conversationViewModel.inputText.collectAsState()
                
                ChatScreen(
                    conversationTitle = currentConversation?.title ?: "",
                    messages = currentMessages,
                    isLoading = chatUiState.isLoading,
                    error = chatUiState.error,
                    inputText = inputText,
                    onInputChange = { conversationViewModel.updateInputText(it) },
                    onSendMessage = { conversationViewModel.sendMessage() },
                    onClearError = { conversationViewModel.clearError() },
                    onBack = { navController.popBackStack() },
                    onClearHistory = { conversationViewModel.clearCurrentConversation() },
                    onExport = {
                        conversationViewModel.exportCurrentConversation { intent ->
                            context.startActivity(intent)
                        }
                    }
                )
            }
            
            composable(NavRoutes.SETTINGS) {
                SettingsScreen(
                    settings = settings,
                    onSettingsChange = { newSettings ->
                        settingsRepository.saveSettings(newSettings)
                    },
                    onBack = { navController.popBackStack() },
                    onNavigateToLogViewer = { navController.navigate(NavRoutes.LOG_VIEWER) },
                    onNavigateToLlmParameters = { navController.navigate(NavRoutes.LLM_PARAMETERS) },
                    onNavigateToTtsSettings = { navController.navigate(NavRoutes.TTS_SETTINGS) }
                )
            }
            
            composable(NavRoutes.LLM_PARAMETERS) {
                LlmParametersScreen(
                    settings = settings,
                    onSettingsChange = { newSettings ->
                        settingsRepository.saveSettings(newSettings)
                    },
                    onBack = { navController.popBackStack() }
                )
            }
            
            composable(NavRoutes.TTS_SETTINGS) {
                TtsSettingsScreen(
                    settings = settings,
                    onSettingsChange = { newSettings ->
                        settingsRepository.saveSettings(newSettings)
                    },
                    onBack = { navController.popBackStack() }
                )
            }
            
            composable(NavRoutes.LOG_VIEWER) {
                LogViewerScreen(
                    onNavigateBack = { navController.popBackStack() }
                )
            }
        }
    }
}

/**
 * Conversation item data class
 */
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
    settings: io.github.zero2005x.glassesaicompanion.data.ApiSettings,
    onGoToSettings: () -> Unit,
    onDismiss: () -> Unit
) {
    val chatValidation = settings.validateForChat()
    val speechValidation = settings.validateForSpeech()
    
    val hasChatIssue = chatValidation !is io.github.zero2005x.glassesaicompanion.data.SettingsValidationResult.Valid
    val hasSpeechIssue = speechValidation !is io.github.zero2005x.glassesaicompanion.data.SettingsValidationResult.Valid
    
    // Build the message based on issues
    val message = buildString {
        if (hasChatIssue) {
            when (chatValidation) {
                is io.github.zero2005x.glassesaicompanion.data.SettingsValidationResult.MissingApiKey -> {
                    append("• ")
                    append(stringResource(chatValidation.provider.displayNameResId))
                    append(" API Key ")
                    append(stringResource(R.string.api_key_not_set))
                    append("\n")
                }
                is io.github.zero2005x.glassesaicompanion.data.SettingsValidationResult.InvalidConfiguration -> {
                    append("• ")
                    append(chatValidation.message)
                    append("\n")
                }
                else -> {}
            }
        }
        if (hasSpeechIssue) {
            when (speechValidation) {
                is io.github.zero2005x.glassesaicompanion.data.SettingsValidationResult.MissingSpeechService -> {
                    append("• ")
                    append(stringResource(R.string.speech_service_not_configured))
                }
                else -> {}
            }
        }
    }
    
    if (message.isBlank()) {
        // Both validations passed or produced no detail: nothing actionable to
        // show, so skip the dialog instead of rendering a misleading empty body.
        return
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

/**
 * Initial setup dialog shown when no API key is configured at all
 */
@Composable
fun InitialSetupDialog(
    onGoToSettings: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                Icons.Default.Settings,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
        },
        title = {
            Text(stringResource(R.string.initial_setup_title))
        },
        text = {
            Column {
                Text(stringResource(R.string.initial_setup_message))
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.initial_setup_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            Button(onClick = onGoToSettings) {
                Text(stringResource(R.string.setup_now))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.later))
            }
        }
    )
}
