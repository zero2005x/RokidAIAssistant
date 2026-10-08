package io.github.zero2005x.glassesaicompanion

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Toast
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import io.github.zero2005x.glassesaicompanion.data.ApiSettings
import io.github.zero2005x.glassesaicompanion.data.OnboardingStore
import io.github.zero2005x.glassesaicompanion.data.SettingsRepository
import io.github.zero2005x.glassesaicompanion.data.validateForChat
import io.github.zero2005x.glassesaicompanion.data.validateForSpeech
import io.github.zero2005x.glassesaicompanion.report.AiReportHost
import io.github.zero2005x.glassesaicompanion.service.PhoneAIService
import io.github.zero2005x.glassesaicompanion.ui.components.rememberMicrophoneGate
import io.github.zero2005x.glassesaicompanion.ui.LlmParametersScreen
import io.github.zero2005x.glassesaicompanion.ui.TtsSettingsScreen
import io.github.zero2005x.glassesaicompanion.ui.SettingsScreen
import io.github.zero2005x.glassesaicompanion.ui.conversation.ChatScreen
import io.github.zero2005x.glassesaicompanion.ui.conversation.ConversationHistoryScreen
import io.github.zero2005x.glassesaicompanion.ui.demo.DemoChatScreen
import io.github.zero2005x.glassesaicompanion.ui.gallery.ClearAllConfirmDialog
import io.github.zero2005x.glassesaicompanion.ui.gallery.DeleteConfirmDialog
import io.github.zero2005x.glassesaicompanion.ui.gallery.PhotoDetailScreen
import io.github.zero2005x.glassesaicompanion.ui.gallery.PhotoGalleryScreen
import io.github.zero2005x.glassesaicompanion.ui.home.HomeScreen
import io.github.zero2005x.glassesaicompanion.ui.logs.LogViewerScreen
import io.github.zero2005x.glassesaicompanion.ui.onboarding.OnboardingScreen
import io.github.zero2005x.glassesaicompanion.ui.navigation.BottomNavDestination
import io.github.zero2005x.glassesaicompanion.ui.navigation.NavRoutes
import io.github.zero2005x.glassesaicompanion.ui.recording.RecordingDetailScreen
import io.github.zero2005x.glassesaicompanion.ui.recording.RecordingsScreen
import io.github.zero2005x.glassesaicompanion.ui.theme.RokidPhoneTheme
import io.github.zero2005x.glassesaicompanion.viewmodel.ConversationViewModel
import io.github.zero2005x.glassesaicompanion.viewmodel.PhotoGalleryViewModel
import io.github.zero2005x.glassesaicompanion.viewmodel.PhoneViewModel

class MainActivity : AppCompatActivity() {

    /**
     * Permissions without which the glasses link cannot work. Everything else in the app
     * (chat, history, analysing a photo, ...) keeps working when these are denied.
     *
     * The Google Play flavor only talks to bonded devices over classic Bluetooth (SPP), so it
     * needs just BLUETOOTH_CONNECT. The GitHub flavor also drives the Rokid CXR SDK, which
     * scans and advertises.
     */
    private fun essentialPermissions(): List<String> = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (BuildConfig.PLAY_DISTRIBUTION) {
                listOf(Manifest.permission.BLUETOOTH_CONNECT)
            } else {
                listOf(
                    Manifest.permission.BLUETOOTH_CONNECT,
                    Manifest.permission.BLUETOOTH_SCAN,
                    Manifest.permission.BLUETOOTH_ADVERTISE
                )
            }
        BuildConfig.PLAY_DISTRIBUTION -> emptyList()
        else -> listOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }

    /** Nice to have: the foreground-service notification. Never blocks anything. */
    private fun optionalPermissions(): List<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            listOf(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            emptyList()
        }

    private fun isGranted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val essentialDenied = essentialPermissions().filter { permissions[it] == false }
        if (essentialDenied.isEmpty()) {
            startAIService()
        } else {
            Log.w(TAG, "Essential permissions denied: $essentialDenied - AI service not started")
            Toast.makeText(this, R.string.bluetooth_permission_needed, Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Nothing is requested at launch: permissions are asked for in context, when the user
        // starts the feature that needs them. Users who already granted Bluetooth access keep
        // the automatic service start.
        if (essentialPermissions().all(::isGranted)) {
            startAIService()
        }

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

    /** User-initiated: ask for what is missing, then start the glasses service. */
    private fun checkPermissionsAndStart() {
        val missing = (essentialPermissions() + optionalPermissions()).filterNot(::isGranted)
        if (missing.isEmpty()) {
            startAIService()
        } else {
            permissionLauncher.launch(missing.toTypedArray())
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
            Log.e(TAG, "Failed to start AI service", e)
            Toast.makeText(this, R.string.service_start_failed, Toast.LENGTH_LONG).show()
        }
    }

    private fun stopAIService() {
        stopService(Intent(this, PhoneAIService::class.java))
    }

    private companion object {
        private const val TAG = "MainActivity"
    }
}
/**
 * Main Screen with Bottom Navigation following Material Design 3
 */
@Composable
fun PhoneMainScreen(
    viewModel: PhoneViewModel = viewModel(),
    onStartService: () -> Unit,
    onStopService: () -> Unit
) {
    // Every screen that shows AI-generated text can offer "report this response"
    AiReportHost {
        PhoneMainContent(viewModel, onStartService, onStopService)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PhoneMainContent(
    viewModel: PhoneViewModel,
    onStartService: () -> Unit,
    onStopService: () -> Unit
) {
    val context = LocalContext.current

    // First launch: the user has to read and accept the notice before anything else
    val onboardingStore = remember { OnboardingStore(context) }
    var onboardingAccepted by remember { mutableStateOf(onboardingStore.isAccepted()) }
    if (!onboardingAccepted) {
        OnboardingScreen(onAccept = {
            onboardingStore.markAccepted()
            onboardingAccepted = true
        })
        return
    }

    val settingsRepository = remember { SettingsRepository.getInstance(context) }
    val settings by settingsRepository.settingsFlow.collectAsState()
    
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = navBackStackEntry?.destination
    
    val uiState by viewModel.uiState.collectAsState()

    // Phone-microphone recording is foreground-only: stop it when the app is hidden.
    // A configuration change (rotation) also stops the activity, so it is excluded.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP && (context as? Activity)?.isChangingConfigurations != true) {
                viewModel.onAppBackgrounded()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Microphone access is requested only when the user starts a phone recording
    val startPhoneRecordingWithPermission = rememberMicrophoneGate { viewModel.startPhoneRecording() }

    // Check if initial setup is needed when settings are loaded.
    // Key on the derived boolean so unrelated settings changes (TTS rate,
    // model id, ...) don't re-trigger the check and re-show dismissed dialogs.
    val hasApiKey = settings.hasAnyApiKeyConfigured()
    LaunchedEffect(hasApiKey) {
        viewModel.checkInitialSetup(hasApiKey)
    }
    
    PhoneMainDialogs(viewModel, settings, navController)

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
                    onStartPhoneRecording = startPhoneRecordingWithPermission,
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
                    onNavigateToTtsSettings = { navController.navigate(NavRoutes.TTS_SETTINGS) },
                    onNavigateToDemo = { navController.navigate(NavRoutes.DEMO_CHAT) }
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
            
            composable(NavRoutes.DEMO_CHAT) {
                DemoChatScreen(onBack = { navController.popBackStack() })
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
    onTryDemo: () -> Unit,
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
                Spacer(modifier = Modifier.height(8.dp))
                TextButton(onClick = onTryDemo) {
                    Text(stringResource(R.string.demo_try))
                }
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

/** The dialogs that can appear over the main screen. Kept apart so [PhoneMainContent] stays readable. */
@Composable
private fun PhoneMainDialogs(
    viewModel: PhoneViewModel,
    settings: ApiSettings,
    navController: androidx.navigation.NavHostController
) {
    val uiState by viewModel.uiState.collectAsState()

    // Show initial setup dialog when no API key is configured
    if (uiState.showInitialSetup) {
        InitialSetupDialog(
            onGoToSettings = {
                viewModel.dismissInitialSetup()
                navController.navigate(NavRoutes.SETTINGS)
            },
            onTryDemo = {
                viewModel.dismissInitialSetup()
                navController.navigate(NavRoutes.DEMO_CHAT)
            },
            onDismiss = {
                viewModel.dismissInitialSetup()
            }
        )
    }
    
    if (uiState.recordingStoppedInBackground) {
        AlertDialog(
            onDismissRequest = { viewModel.dismissRecordingStoppedNotice() },
            title = { Text(stringResource(R.string.recording_stopped_background_title)) },
            text = { Text(stringResource(R.string.recording_stopped_background_message)) },
            confirmButton = {
                TextButton(onClick = { viewModel.dismissRecordingStoppedNotice() }) {
                    Text(stringResource(R.string.close))
                }
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
    
}
