package com.example.rokidglasses

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.core.view.WindowCompat
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import com.example.rokidglasses.ui.MeasuredTextPaginator
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.Density
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.material3.LocalTextStyle
import com.example.rokidcommon.protocol.GlassesFont
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.rokidglasses.service.WakeWordService
import com.example.rokidglasses.service.photo.CameraService
import com.example.rokidglasses.ui.theme.RokidGlassesTheme
import com.example.rokidglasses.viewmodel.GlassesViewModel

class MainActivity : ComponentActivity() {
    
    companion object {
        private const val TAG = "MainActivity"
    }
    
    // Hold reference to ViewModel for key events
    private var glassesViewModel: GlassesViewModel? = null
    
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        // Notification permission is optional; core camera/audio/Bluetooth permissions are not.
        if (hasRequiredServicePermissions()) {
            startServices()
        }
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Keep screen on
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        
        // Full screen immersive mode
        WindowCompat.setDecorFitsSystemWindows(window, false)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
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
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        android.util.Log.d("MainActivity", "dispatchKeyEvent: action=${event.action}, keyCode=${event.keyCode} (${KeyEvent.keyCodeToString(event.keyCode)}), scanCode=${event.scanCode}")
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
                if ((event?.eventTime?.minus(event.downTime) ?: 0L) < 500L) {
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        
        val notGranted = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        
        if (notGranted.isNotEmpty()) {
            Log.w(TAG, "Missing permissions: ${notGranted.joinToString(", ")}")
            permissionLauncher.launch(notGranted.toTypedArray())
        } else {
            Log.d(TAG, "All permissions granted")
            startServices()
        }
    }

    private fun hasRequiredServicePermissions(): Boolean {
        val required = mutableListOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            required += Manifest.permission.BLUETOOTH_CONNECT
            required += Manifest.permission.BLUETOOTH_SCAN
        }
        return required.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
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
    
    BoxWithConstraints(
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
        val displayConfig = uiState.displayConfig.normalized()
        val viewportHeight = maxHeight * displayConfig.heightPercent / 100f
        val deviceDensity = LocalDensity.current
        val widthPx = with(deviceDensity) { maxWidth.roundToPx() }
        val heightPx = with(deviceDensity) { maxHeight.roundToPx() }
        LaunchedEffect(widthPx, heightPx, deviceDensity.density, deviceDensity.fontScale) {
            viewModel.updateDisplayMetrics(com.example.rokidcommon.protocol.GlassesDisplayMetrics(
                widthPx, heightPx, deviceDensity.density, deviceDensity.fontScale))
        }
        val scaledDensity = Density(deviceDensity.density,
            deviceDensity.fontScale * displayConfig.fontSizeSp / 22f)
        val family = if (displayConfig.font == GlassesFont.MONOSPACE) FontFamily.Monospace else FontFamily.Default
        CompositionLocalProvider(
            LocalDensity provides scaledDensity,
            LocalTextStyle provides LocalTextStyle.current.copy(fontFamily = family)
        ) {
        Box(
            modifier = Modifier
                .offset(
                    x = maxWidth * displayConfig.leftPercent / 100f,
                    y = maxHeight * displayConfig.topPercent / 100f
                )
                .size(
                    width = maxWidth * displayConfig.widthPercent / 100f,
                    height = maxHeight * displayConfig.heightPercent / 100f
                )
                .clipToBounds()
        ) {
        Column(Modifier.fillMaxSize().padding(6.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth()
                    .heightIn(max = viewportHeight * com.example.rokidcommon.protocol.GlassesDisplayLayout.HEADER_FRACTION)
                    .horizontalScroll(rememberScrollState())
                    .verticalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                if (uiState.isPaginated) {
                    PageIndicator(uiState.currentPage + 1, uiState.totalPages)
                } else Spacer(Modifier.width(1.dp))
                StatusIndicator(
                    isConnected = uiState.isConnected,
                    isListening = uiState.isListening,
                    deviceName = uiState.connectedDeviceName
                )
            }
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                val measurer = rememberTextMeasurer()
                val density = LocalDensity.current
                val textStyle = TextStyle(fontSize = 22.sp, lineHeight = 30.sp,
                    fontWeight = FontWeight.Medium, fontFamily = family, textAlign = TextAlign.Center)
                val widthPx = with(density) { (maxWidth - 12.dp).roundToPx().coerceAtLeast(1) }
                val heightPx = with(density) { maxHeight.roundToPx().coerceAtLeast(1) }
                LaunchedEffect(widthPx, heightPx, textStyle, density, measurer) {
                    viewModel.updateTextLayout { text ->
                        MeasuredTextPaginator.paginate(text, widthPx, heightPx, textStyle, measurer)
                    }
                }
                MainDisplayArea(
                    displayText = uiState.displayText,
                    isProcessing = uiState.isProcessing,
                    textStyle = textStyle
                )
            }
            HintText(hint = uiState.hintText, modifier = Modifier.fillMaxWidth()
                .heightIn(max = viewportHeight * com.example.rokidcommon.protocol.GlassesDisplayLayout.HINT_FRACTION)
                .verticalScroll(rememberScrollState()))
        }
        
        // Device selector dialog
        if (showDeviceSelector) {
            DeviceSelectorDialog(
                devices = uiState.availableDevices,
                cxrConnectedPhoneName = uiState.cxrConnectedPhoneName,
                onDeviceSelected = { device ->
                    viewModel.connectToDevice(device)
                    showDeviceSelector = false
                },
                onDismiss = { showDeviceSelector = false }
            )
        }
        }
        }
    }
}

@Composable
fun DeviceSelectorDialog(
    devices: List<android.bluetooth.BluetoothDevice>,
    cxrConnectedPhoneName: String? = null,
    onDeviceSelected: (android.bluetooth.BluetoothDevice) -> Unit,
    onDismiss: () -> Unit
) {
    // Sort devices: CXR-connected phone first, then by name
    val sortedDevices = remember(devices, cxrConnectedPhoneName) {
        if (cxrConnectedPhoneName != null) {
            devices.sortedByDescending { 
                @Suppress("MissingPermission")
                it.name?.equals(cxrConnectedPhoneName, ignoreCase = true) == true 
            }
        } else {
            devices
        }
    }
    
    // Render inside the configured viewport, sharing font scale and family with every other glasses label.
    val selectedFontFamily = LocalTextStyle.current.fontFamily
    Surface(modifier = Modifier.fillMaxSize(), color = Color(0xFF1A1A1A)) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.select_phone), color = Color.White,
                fontSize = 18.sp, fontWeight = FontWeight.Bold)
            if (sortedDevices.isEmpty()) {
                Text(stringResource(R.string.no_paired_devices) + "\n" + stringResource(R.string.pair_device_hint),
                    color = Color.LightGray, fontSize = 14.sp)
            }
            sortedDevices.forEach { device ->
                @Suppress("MissingPermission")
                val deviceName = device.name ?: stringResource(R.string.unknown_device)
                val recommended = deviceName.equals(cxrConnectedPhoneName, ignoreCase = true)
                Surface(onClick = { onDeviceSelected(device) }, modifier = Modifier.fillMaxWidth(),
                    color = if (recommended) Color(0xFF1E3A5F) else Color(0xFF2A2A2A)) {
                    Column(Modifier.padding(8.dp)) {
                        Text(deviceName, color = Color.White, fontSize = 16.sp)
                        if (recommended) Text("★ " + stringResource(R.string.recommended),
                            color = Color(0xFF64B5F6), fontSize = 12.sp)
                    }
                }
            }
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel), color = Color(0xFF64B5F6), fontFamily = selectedFontFamily)
            }
        }
    }
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
    modifier: Modifier = Modifier,
    textStyle: TextStyle = TextStyle(fontSize = 22.sp, lineHeight = 30.sp,
        fontWeight = FontWeight.Medium, textAlign = TextAlign.Center)
) {
    Box(modifier.fillMaxSize().padding(horizontal = 6.dp), contentAlignment = Alignment.Center) {
        if (isProcessing) {
            CircularProgressIndicator(Modifier.size(32.dp), color = Color(0xFF64B5F6), strokeWidth = 3.dp)
        } else {
            Text(text = displayText, color = Color.White, style = textStyle,
                modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()))
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
