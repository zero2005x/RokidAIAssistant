package com.example.rokidglasses

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.rokidglasses.service.WakeWordService
import com.example.rokidglasses.service.photo.CameraService
import com.example.rokidglasses.ui.theme.RokidGlassesTheme
import com.example.rokidglasses.viewmodel.GlassesViewModel

class MainActivity : ComponentActivity() {
    
    // Hold reference to ViewModel for key events
    private var glassesViewModel: GlassesViewModel? = null
    
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.values.all { it }
        if (allGranted) {
            startWakeWordService()
        }
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Keep screen on
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        
        // Full screen immersive mode
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_FULLSCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
            )
        }
        
        checkPermissions()
        
        // Handle wake up intent
        handleWakeUpIntent(intent)
        
        setContent {
            RokidGlassesTheme {
                val viewModel: GlassesViewModel = viewModel(
                    factory = GlassesViewModel.Factory(this)
                )
                // Store reference for key events
                glassesViewModel = viewModel
                
                GlassesMainScreen(
                    viewModel = viewModel,
                    onScreenTap = { /* Screen tap triggers recording */ }
                )
            }
        }
    }
    
    /**
     * Catch ALL key events at dispatch level for debugging
     */
    override fun dispatchKeyEvent(event: KeyEvent?): Boolean {
        event?.let {
            android.util.Log.d("MainActivity", "dispatchKeyEvent: action=${it.action}, keyCode=${it.keyCode} (${KeyEvent.keyCodeToString(it.keyCode)}), scanCode=${it.scanCode}")
        }
        return super.dispatchKeyEvent(event)
    }
    
    /**
     * Handle physical key events from Rokid touchpad
     * - DPAD_UP / Volume Up: Previous page
     * - DPAD_DOWN / Volume Down: Next page
     * - DPAD_CENTER / Enter: Toggle recording (tap) or capture photo (long press)
     */
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        // Debug: Log all key events to identify Rokid camera button keycode
        android.util.Log.d("MainActivity", "onKeyDown: keyCode=$keyCode (${KeyEvent.keyCodeToString(keyCode)}), scanCode=${event?.scanCode}, repeat=${event?.repeatCount}")
        
        val viewModel = glassesViewModel ?: return super.onKeyDown(keyCode, event)
        val uiState = viewModel.uiState.value
        
        return when (keyCode) {
            // Swipe up on touchpad / Volume up = Previous page
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_VOLUME_UP -> {
                if (uiState.isPaginated) {
                    viewModel.previousPage()
                    true
                } else {
                    super.onKeyDown(keyCode, event)
                }
            }
            // Swipe down on touchpad / Volume down = Next page
            KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_VOLUME_DOWN -> {
                if (uiState.isPaginated) {
                    viewModel.nextPage()
                    true
                } else {
                    super.onKeyDown(keyCode, event)
                }
            }
            // Tap on touchpad / Enter = Toggle recording or exit pagination
            // Long press = Take photo (workaround for camera button)
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                if (event?.repeatCount == 1) {
                    // First repeat = long press started, capture photo
                    android.util.Log.d("MainActivity", "Long press center = capture photo")
                    viewModel.captureAndSendPhoto()
                    true
                } else if (event?.repeatCount == 0) {
                    // Normal tap handling (will only trigger if key released before repeat)
                    true // Consume but wait for key up
                } else {
                    true // Consume subsequent repeats
                }
            }
            // Camera button - take photo and send to phone for AI analysis
            KeyEvent.KEYCODE_CAMERA, 27, 
            KeyEvent.KEYCODE_FOCUS, // Some devices use focus key for camera
            260, 261, 262, 263 -> { // Additional camera-related keycodes
                android.util.Log.d("MainActivity", "Camera/Focus key pressed: $keyCode")
                viewModel.captureAndSendPhoto()
                true
            }
            // Long press back = take photo (alternative trigger)
            KeyEvent.KEYCODE_BACK -> {
                if (event?.repeatCount == 1) {
                    android.util.Log.d("MainActivity", "Long press back = capture photo")
                    viewModel.captureAndSendPhoto()
                    true
                } else {
                    super.onKeyDown(keyCode, event)
                }
            }
            else -> super.onKeyDown(keyCode, event)
        }
    }
    
    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        android.util.Log.d("MainActivity", "onKeyUp: keyCode=$keyCode (${KeyEvent.keyCodeToString(keyCode)})")
        
        val viewModel = glassesViewModel ?: return super.onKeyUp(keyCode, event)
        val uiState = viewModel.uiState.value
        
        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                // Only handle short tap (not long press which was already handled)
                if (event?.eventTime?.minus(event.downTime) ?: 0 < 500) {
                    // Short tap - toggle recording
                    if (uiState.isPaginated && uiState.currentPage == uiState.totalPages - 1) {
                        viewModel.dismissPagination()
                        viewModel.toggleRecording()
                    } else if (!uiState.isPaginated) {
                        viewModel.toggleRecording()
                    }
                }
                true
            }
            else -> super.onKeyUp(keyCode, event)
        }
    }
    
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleWakeUpIntent(intent)
    }
    
    private fun handleWakeUpIntent(intent: Intent?) {
        if (intent?.getBooleanExtra("wake_up", false) == true) {
            // Woke up by voice, can auto start recording
            // TODO: Notify ViewModel to start recording
        }
    }
    
    private fun checkPermissions() {
        val permissions = mutableListOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.CAMERA  // Camera permission for photo capture
        )
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.addAll(listOf(
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_SCAN
            ))
        }
        
        val notGranted = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        
        if (notGranted.isNotEmpty()) {
            permissionLauncher.launch(notGranted.toTypedArray())
        } else {
            startServices()
        }
    }
    
    private fun startServices() {
        startWakeWordService()
        startCameraService()
    }
    
    private fun startCameraService() {
        if (!CameraService.isRunning) {
            val serviceIntent = Intent(this, CameraService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
        }
    }
    
    private fun startWakeWordService() {
        if (!WakeWordService.isRunning) {
            val serviceIntent = Intent(this, WakeWordService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
        }
    }
    
    override fun onResume() {
        super.onResume()
        // Ensure screen stays on
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }
}

@Composable
fun GlassesMainScreen(
    viewModel: GlassesViewModel,
    onScreenTap: () -> Unit = {}
) {
    val uiState by viewModel.uiState.collectAsState()
    var showDeviceSelector by remember { mutableStateOf(false) }
    
    // Track swipe gesture for pagination
    var swipeOffset by remember { mutableFloatStateOf(0f) }
    val swipeThreshold = 50f
    
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(uiState.isPaginated) {
                if (uiState.isPaginated) {
                    detectVerticalDragGestures(
                        onDragEnd = {
                            when {
                                swipeOffset > swipeThreshold -> viewModel.previousPage() // Swipe down = previous
                                swipeOffset < -swipeThreshold -> viewModel.nextPage() // Swipe up = next
                            }
                            swipeOffset = 0f
                        },
                        onDragCancel = { swipeOffset = 0f },
                        onVerticalDrag = { _, dragAmount ->
                            swipeOffset += dragAmount
                        }
                    )
                }
            }
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() }
            ) {
                if (uiState.isPaginated) {
                    // If paginated, tap goes to next page or exits pagination on last page
                    if (uiState.currentPage < uiState.totalPages - 1) {
                        viewModel.nextPage()
                    } else {
                        // On last page, tap to dismiss and allow new recording
                        viewModel.dismissPagination()
                    }
                } else if (uiState.isConnected) {
                    // When connected, tap screen to toggle recording
                    viewModel.toggleRecording()
                } else {
                    // When disconnected, show device selector
                    viewModel.refreshPairedDevices()
                    showDeviceSelector = true
                }
            }
    ) {
        // Status indicator (top right)
        StatusIndicator(
            isConnected = uiState.isConnected,
            isListening = uiState.isListening,
            deviceName = uiState.connectedDeviceName,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(16.dp)
        )
        
        // Page indicator (top left) - only show when paginated
        if (uiState.isPaginated) {
            PageIndicator(
                currentPage = uiState.currentPage + 1,
                totalPages = uiState.totalPages,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(16.dp)
            )
        }
        
        // Main display area (centered)
        MainDisplayArea(
            displayText = uiState.displayText,
            isProcessing = uiState.isProcessing,
            isPaginated = uiState.isPaginated,
            currentPage = uiState.currentPage,
            totalPages = uiState.totalPages,
            modifier = Modifier.align(Alignment.Center)
        )
        
        // Hint text (bottom)
        HintText(
            hint = uiState.hintText,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 24.dp)
        )
        
        // Device selector dialog
        if (showDeviceSelector) {
            DeviceSelectorDialog(
                devices = uiState.availableDevices,
                onDeviceSelected = { device ->
                    viewModel.connectToDevice(device)
                    showDeviceSelector = false
                },
                onDismiss = { showDeviceSelector = false }
            )
        }
    }
}

@Composable
fun DeviceSelectorDialog(
    devices: List<android.bluetooth.BluetoothDevice>,
    onDeviceSelected: (android.bluetooth.BluetoothDevice) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF1A1A1A),
        title = {
            Text(
                text = stringResource(R.string.select_phone),
                color = Color.White,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            if (devices.isEmpty()) {
                Text(
                    text = stringResource(R.string.no_paired_devices) + "\n" + stringResource(R.string.pair_device_hint),
                    color = Color.White.copy(alpha = 0.7f),
                    fontSize = 14.sp
                )
            } else {
                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    devices.forEach { device ->
                        @Suppress("MissingPermission")
                        val deviceName = device.name ?: stringResource(R.string.unknown_device)
                        
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onDeviceSelected(device) },
                            color = Color(0xFF2A2A2A),
                            shape = MaterialTheme.shapes.small
                        ) {
                            Text(
                                text = deviceName,
                                color = Color.White,
                                fontSize = 16.sp,
                                modifier = Modifier.padding(16.dp)
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel), color = Color(0xFF64B5F6))
            }
        }
    )
}

@Composable
fun StatusIndicator(
    isConnected: Boolean,
    isListening: Boolean,
    deviceName: String? = null,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.End,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Connection status
            StatusDot(
                color = if (isConnected) Color(0xFF64B5F6) else Color(0xFFFF5722),
                label = if (isConnected) stringResource(R.string.connected) else stringResource(R.string.tap_to_connect)
            )
            
            // Recording status
            AnimatedVisibility(
                visible = isListening,
                enter = fadeIn() + scaleIn(),
                exit = fadeOut() + scaleOut()
            ) {
                StatusDot(
                    color = Color(0xFFF44336),
                    label = stringResource(R.string.recording),
                    pulsing = true
                )
            }
        }
        
        // Display connected device name
        if (isConnected && deviceName != null) {
            Text(
                text = deviceName,
                color = Color.White.copy(alpha = 0.5f),
                fontSize = 10.sp
            )
        }
    }
}

@Composable
fun StatusDot(
    color: Color,
    label: String,
    pulsing: Boolean = false
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .background(color, shape = androidx.compose.foundation.shape.CircleShape)
        )
        Text(
            text = label,
            color = Color.White.copy(alpha = 0.8f),
            fontSize = 12.sp
        )
    }
}

@Composable
fun MainDisplayArea(
    displayText: String,
    isProcessing: Boolean,
    isPaginated: Boolean = false,
    currentPage: Int = 0,
    totalPages: Int = 1,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        AnimatedVisibility(
            visible = isProcessing,
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(32.dp),
                color = Color(0xFF64B5F6),
                strokeWidth = 3.dp
            )
        }
        
        Spacer(modifier = Modifier.height(16.dp))
        
        AnimatedContent(
            targetState = displayText,
            transitionSpec = {
                if (isPaginated) {
                    // Slide animation for pagination
                    slideInVertically { height -> height } + fadeIn() togetherWith 
                    slideOutVertically { height -> -height } + fadeOut()
                } else {
                    fadeIn() togetherWith fadeOut()
                }
            },
            label = "display_text"
        ) { text ->
            Text(
                text = text,
                color = Color.White,
                fontSize = if (isPaginated) 20.sp else 24.sp,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center,
                lineHeight = if (isPaginated) 28.sp else 32.sp,
                modifier = Modifier.fillMaxWidth()
            )
        }
        
        // Navigation hints for paginated content
        if (isPaginated) {
            Spacer(modifier = Modifier.height(16.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (currentPage > 0) {
                    Text(
                        text = "▲",
                        color = Color(0xFF64B5F6),
                        fontSize = 16.sp
                    )
                }
                if (currentPage < totalPages - 1) {
                    Text(
                        text = "▼",
                        color = Color(0xFF64B5F6),
                        fontSize = 16.sp
                    )
                }
            }
        }
    }
}

@Composable
fun PageIndicator(
    currentPage: Int,
    totalPages: Int,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        color = Color(0xFF2A2A2A),
        shape = MaterialTheme.shapes.small
    ) {
        Text(
            text = stringResource(R.string.page_indicator, currentPage, totalPages),
            color = Color.White.copy(alpha = 0.8f),
            fontSize = 12.sp,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
        )
    }
}

@Composable
fun HintText(
    hint: String,
    modifier: Modifier = Modifier
) {
    Text(
        text = hint,
        color = Color.White.copy(alpha = 0.5f),
        fontSize = 14.sp,
        textAlign = TextAlign.Center,
        modifier = modifier
    )
}
