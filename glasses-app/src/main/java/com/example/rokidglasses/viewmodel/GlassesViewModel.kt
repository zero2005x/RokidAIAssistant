package com.example.rokidglasses.viewmodel

import android.Manifest
import android.bluetooth.BluetoothDevice
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.rokidcommon.Constants
import com.example.rokidcommon.protocol.ConnectionState
import com.example.rokidcommon.protocol.Message
import com.example.rokidcommon.protocol.MessageType
import com.example.rokidglasses.R
import com.example.rokidglasses.sdk.CameraMode
import com.example.rokidglasses.sdk.CxrServiceManager
import com.example.rokidglasses.sdk.UnifiedCameraManager
import com.example.rokidglasses.service.BluetoothClientState
import com.example.rokidglasses.service.BluetoothSppClient
import com.example.rokidglasses.service.photo.GlassesCameraManager
import com.example.rokidglasses.service.photo.ImageCompressor
import com.example.rokidglasses.service.photo.PhotoTransferProtocol
import com.example.rokidglasses.service.photo.createPhotoTransferProtocol
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.ByteArrayOutputStream

data class GlassesUiState(
    val isConnected: Boolean = false,
    val isListening: Boolean = false,
    val isProcessing: Boolean = false,
    val displayText: String = "",
    val hintText: String = "",
    val connectionState: ConnectionState = ConnectionState.DISCONNECTED,
    val userTranscript: String = "",
    val aiResponse: String = "",
    // Pagination for long text
    val currentPage: Int = 0,
    val totalPages: Int = 1,
    val isPaginated: Boolean = false,
    // Bluetooth related
    val bluetoothState: BluetoothClientState = BluetoothClientState.DISCONNECTED,
    val connectedDeviceName: String? = null,
    val availableDevices: List<BluetoothDevice> = emptyList(),
    // CXR connected phone name (to help identify correct SPP device)
    val cxrConnectedPhoneName: String? = null,
    // Photo capture state
    val isCapturingPhoto: Boolean = false,
    val photoTransferProgress: Float = 0f
)

/**
 * Glasses ViewModel
 * 
 * New Architecture:
 * 1. Glasses record -> Collect PCM data
 * 2. Recording ends -> Send via Bluetooth SPP to phone
 * 3. Phone processes AI -> Returns result
 * 4. Glasses display result
 */
class GlassesViewModel(
    private val context: Context
) : ViewModel() {
    
    companion object {
        private const val TAG = "GlassesViewModel"
        // Max characters per page for glasses display
        private const val MAX_CHARS_PER_PAGE = 120
        private const val MAX_LINES_PER_PAGE = 4
    }
    
    private val _uiState = MutableStateFlow(GlassesUiState(
        displayText = context.getString(R.string.say_hey_rokid),
        hintText = context.getString(R.string.tap_touchpad_record)
    ))
    val uiState: StateFlow<GlassesUiState> = _uiState.asStateFlow()
    
    // Store full AI response for pagination
    private var fullAiResponse: String = ""
    private var responsePages: List<String> = emptyList()
    
    private var audioRecord: AudioRecord? = null
    private var recordingJob: Job? = null
    
    // Bluetooth SPP client - connects to phone
    private val bluetoothClient = BluetoothSppClient(context, viewModelScope)
    
    // Camera manager for photo capture
    // Glasses use Camera2 API to directly access local camera (no longer using CXR-M SDK)
    // CXR-M SDK is for phone side to remotely control glasses camera
    private var cameraManager: UnifiedCameraManager? = null
    
    // CXR-S SDK service manager (for communication with phone)
    private var cxrServiceManager: CxrServiceManager? = null
    
    // Photo transfer protocol
    private var photoTransferProtocol: PhotoTransferProtocol? = null
    
    // Audio buffer - collects recording data
    private val audioBuffer = ByteArrayOutputStream()
    
    init {
        initializeBluetooth()
        initializeCamera()
        initializeCxrService()
    }
    
    /**
     * Initialize CXR-S SDK service (Glasses Side)
     * Used to receive messages and commands from phone side
     */
    private fun initializeCxrService() {
        if (CxrServiceManager.isSdkAvailable()) {
            cxrServiceManager = CxrServiceManager.getInstance()
            val initialized = cxrServiceManager?.initialize() == true
            Log.d(TAG, "CXR-S Service initialized: $initialized")
            
            if (initialized) {
                // Listen for connection state
                viewModelScope.launch {
                    cxrServiceManager?.connectionState?.collect { state ->
                        when (state) {
                            is CxrServiceManager.ConnectionState.Connected -> {
                                Log.d(TAG, "CXR connected to: ${state.deviceName}")
                                // Store CXR-connected phone name for SPP device selection
                                _uiState.update { it.copy(cxrConnectedPhoneName = state.deviceName) }
                            }
                            is CxrServiceManager.ConnectionState.Disconnected -> {
                                Log.d(TAG, "CXR disconnected")
                                _uiState.update { it.copy(cxrConnectedPhoneName = null) }
                            }
                        }
                    }
                }
            }
        } else {
            Log.w(TAG, "CXR-S SDK not available")
        }
    }
    
    private fun initializeCamera() {
        viewModelScope.launch {
            // Glasses directly use Camera2 API (no longer depends on CXR-M SDK)
            // CXR-M SDK camera functionality is for phone side to remotely control glasses camera
            cameraManager = UnifiedCameraManager(
                context = context,
                preferredMode = CameraMode.CAMERA2  // Use local Camera2 API directly
            )
            
            val result = cameraManager?.initialize()
            
            if (result?.isSuccess == true) {
                val cameraType = cameraManager?.getCameraTypeName() ?: "Unknown"
                Log.d(TAG, "Camera manager initialized: $cameraType")
            } else {
                Log.w(TAG, "Camera manager initialization failed: ${result?.exceptionOrNull()?.message}")
            }
        }
    }
    
    private fun initializeBluetooth() {
        // Listen to Bluetooth connection state
        viewModelScope.launch {
            bluetoothClient.connectionState.collect { state ->
                Log.d(TAG, "Bluetooth state changed: $state")
                val connectionState = when (state) {
                    BluetoothClientState.DISCONNECTED -> ConnectionState.DISCONNECTED
                    BluetoothClientState.CONNECTING -> ConnectionState.CONNECTING
                    BluetoothClientState.CONNECTED -> ConnectionState.CONNECTED
                }
                
                _uiState.update { it.copy(
                    bluetoothState = state,
                    connectionState = connectionState,
                    isConnected = state == BluetoothClientState.CONNECTED,
                    displayText = when (state) {
                        BluetoothClientState.DISCONNECTED -> context.getString(R.string.not_connected)
                        BluetoothClientState.CONNECTING -> context.getString(R.string.connecting_status)
                        BluetoothClientState.CONNECTED -> context.getString(R.string.connected_ready)
                    },
                    hintText = when (state) {
                        BluetoothClientState.DISCONNECTED -> context.getString(R.string.please_connect_phone)
                        BluetoothClientState.CONNECTING -> context.getString(R.string.please_wait)
                        BluetoothClientState.CONNECTED -> context.getString(R.string.tap_touchpad_start)
                    }
                ) }
            }
        }
        
        // Listen to connected device name
        viewModelScope.launch {
            bluetoothClient.connectedDeviceName.collect { name ->
                _uiState.update { it.copy(connectedDeviceName = name) }
            }
        }
        
        // Listen to messages from phone
        viewModelScope.launch {
            bluetoothClient.messageFlow.collect { message ->
                handlePhoneMessage(message)
            }
        }
        
        // Get paired devices
        refreshPairedDevices()
    }
    
    /**
     * Refresh paired devices list
     */
    fun refreshPairedDevices() {
        val devices = bluetoothClient.getPairedDevices()
        _uiState.update { it.copy(availableDevices = devices) }
        Log.d(TAG, "Found ${devices.size} paired devices")
    }
    
    /**
     * Connect to specified device
     */
    fun connectToDevice(device: BluetoothDevice) {
        Log.d(TAG, "Connecting to device: ${device.name}")
        bluetoothClient.connect(device)
    }
    
    /**
     * Disconnect Bluetooth connection
     */
    fun disconnectBluetooth() {
        bluetoothClient.disconnect()
    }
    
    fun startRecording() {
        // Check if connected
        if (_uiState.value.bluetoothState != BluetoothClientState.CONNECTED) {
            _uiState.update { it.copy(
                displayText = context.getString(R.string.please_connect_phone),
                hintText = context.getString(R.string.select_paired_device)
            ) }
            return
        }
        
        if (_uiState.value.isListening) return
        
        // Clear audio buffer and reset pagination
        audioBuffer.reset()
        resetPagination()
        
        _uiState.update { it.copy(
            isListening = true,
            displayText = context.getString(R.string.listening),
            hintText = context.getString(R.string.tap_stop_recording),
            userTranscript = "",
            aiResponse = ""
        ) }
        
        Log.d(TAG, "Start recording")
        
        // Check RECORD_AUDIO permission before proceeding
        if (ActivityCompat.checkSelfPermission(
                context,
                Manifest.permission.RECORD_AUDIO
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            Log.e(TAG, "RECORD_AUDIO permission not granted")
            _uiState.update { it.copy(
                displayText = context.getString(R.string.mic_permission_required),
                isListening = false
            ) }
            return
        }
        
        // Notify phone that recording started
        viewModelScope.launch {
            bluetoothClient.sendVoiceStart()
        }
        
        // Start recording
        recordingJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                val bufferSize = AudioRecord.getMinBufferSize(
                    Constants.AUDIO_SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT
                )
                
                // Verify permission again before AudioRecord initialization
                if (ActivityCompat.checkSelfPermission(
                        context,
                        Manifest.permission.RECORD_AUDIO
                    ) != PackageManager.PERMISSION_GRANTED
                ) {
                    Log.e(TAG, "RECORD_AUDIO permission lost during initialization")
                    withContext(Dispatchers.Main) {
                        _uiState.update { it.copy(
                            displayText = context.getString(R.string.mic_permission_required),
                            isListening = false
                        ) }
                    }
                    return@launch
                }
                
                audioRecord = AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    Constants.AUDIO_SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufferSize
                )
                
                // Verify AudioRecord was initialized successfully
                if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                    Log.e(TAG, "AudioRecord initialization failed")
                    withContext(Dispatchers.Main) {
                        _uiState.update { it.copy(
                            displayText = "Failed to initialize microphone",
                            isListening = false
                        ) }
                    }
                    audioRecord?.release()
                    audioRecord = null
                    return@launch
                }
                
                audioRecord?.startRecording()
                Log.d(TAG, "AudioRecord started recording")
                
                val buffer = ByteArray(Constants.AUDIO_BUFFER_SIZE)
                
                while (isActive && _uiState.value.isListening) {
                    val readSize = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                    if (readSize > 0) {
                        // Collect audio data to buffer
                        synchronized(audioBuffer) {
                            audioBuffer.write(buffer, 0, readSize)
                        }
                    }
                }
                
                Log.d(TAG, "Recording ended, collected ${audioBuffer.size()} bytes")
                
            } catch (e: SecurityException) {
                Log.e(TAG, "Microphone permission error", e)
                withContext(Dispatchers.Main) {
                    _uiState.update { it.copy(
                        displayText = context.getString(R.string.mic_permission_required),
                        isListening = false
                    ) }
                }
            } finally {
                // Safely stop and release AudioRecord
                try {
                    if (audioRecord?.recordingState == android.media.AudioRecord.RECORDSTATE_RECORDING) {
                        audioRecord?.stop()
                    }
                } catch (e: IllegalStateException) {
                    Log.w(TAG, "AudioRecord stop failed (already stopped or invalid state)", e)
                }
                try {
                    audioRecord?.release()
                } catch (e: Exception) {
                    Log.w(TAG, "AudioRecord release failed", e)
                }
                audioRecord = null
            }
        }
    }
    
    fun stopRecording() {
        _uiState.update { it.copy(
            isListening = false,
            isProcessing = true,
            displayText = context.getString(R.string.sending_audio),
            hintText = context.getString(R.string.please_wait)
        ) }
        
        recordingJob?.cancel()
        recordingJob = null
        
        Log.d(TAG, "Stop recording, sending audio to phone")
        
        // Send audio via Bluetooth to phone for processing
        viewModelScope.launch(Dispatchers.IO) {
            try {
                // Get recording data
                val audioData: ByteArray
                synchronized(audioBuffer) {
                    audioData = audioBuffer.toByteArray()
                }
                
                Log.d(TAG, "Audio data size: ${audioData.size} bytes")
                
                if (audioData.isEmpty()) {
                    withContext(Dispatchers.Main) {
                        _uiState.update { it.copy(
                            isProcessing = false,
                            displayText = context.getString(R.string.no_voice_detected),
                            hintText = context.getString(R.string.please_try_again)
                        ) }
                    }
                    return@launch
                }
                
                // Send audio data to phone
                withContext(Dispatchers.Main) {
                    _uiState.update { it.copy(displayText = context.getString(R.string.sending)) }
                }
                
                val success = bluetoothClient.sendVoiceEnd(audioData)
                
                if (!success) {
                    withContext(Dispatchers.Main) {
                        _uiState.update { it.copy(
                            isProcessing = false,
                            displayText = context.getString(R.string.send_failed),
                            hintText = context.getString(R.string.reconnect_try_again)
                        ) }
                    }
                    return@launch
                }
                
                Log.d(TAG, "Audio sent to phone, waiting for processing result")
                
                // Update UI state, waiting for phone response
                withContext(Dispatchers.Main) {
                    _uiState.update { it.copy(
                        displayText = context.getString(R.string.waiting_phone),
                        hintText = context.getString(R.string.ai_thinking)
                    ) }
                }
                
                // Phone will update UI via handlePhoneMessage callback when done
                
            } catch (e: Exception) {
                Log.e(TAG, "Error sending audio", e)
                withContext(Dispatchers.Main) {
                    _uiState.update { it.copy(
                        isProcessing = false,
                        displayText = context.getString(R.string.error_prefix, e.message ?: ""),
                        hintText = context.getString(R.string.please_try_again)
                    ) }
                }
            }
        }
    }
    
    fun toggleRecording() {
        if (_uiState.value.isListening) {
            stopRecording()
        } else {
            startRecording()
        }
    }
    
    /**
     * Handle message from phone
     */
    private fun handlePhoneMessage(message: Message) {
        Log.d(TAG, "Received from phone: ${message.type}, payload: ${message.payload}")
        
        when (message.type) {
            MessageType.AI_PROCESSING -> {
                _uiState.update { it.copy(
                    isProcessing = true,
                    displayText = message.payload ?: context.getString(R.string.processing)
                ) }
            }
            
            MessageType.USER_TRANSCRIPT -> {
                // User's speech recognized by phone
                _uiState.update { it.copy(
                    userTranscript = message.payload ?: "",
                    displayText = context.getString(R.string.you_said, message.payload ?: "")
                ) }
            }
            
            MessageType.AI_RESPONSE_TEXT -> {
                // AI response text - handle pagination for long responses
                val responseText = message.payload ?: ""
                fullAiResponse = responseText
                responsePages = paginateText(responseText)
                
                val isPaginated = responsePages.size > 1
                val displayText = if (responsePages.isNotEmpty()) responsePages[0] else responseText
                val hintText = if (isPaginated) {
                    context.getString(R.string.swipe_for_more)
                } else {
                    context.getString(R.string.tap_continue)
                }
                
                _uiState.update { it.copy(
                    isProcessing = false,
                    aiResponse = responseText,
                    displayText = displayText,
                    hintText = hintText,
                    currentPage = 0,
                    totalPages = responsePages.size,
                    isPaginated = isPaginated
                ) }
            }
            
            MessageType.AI_RESPONSE_TTS -> {
                // Play AI voice
                message.binaryData?.let { audioData ->
                    playAudio(audioData)
                }
            }
            
            MessageType.AI_ERROR -> {
                _uiState.update { it.copy(
                    isProcessing = false,
                    displayText = context.getString(R.string.error_prefix, message.payload ?: ""),
                    hintText = context.getString(R.string.please_try_again)
                ) }
            }
            
            MessageType.DISPLAY_TEXT -> {
                _uiState.update { it.copy(
                    displayText = message.payload ?: ""
                ) }
            }
            
            MessageType.DISPLAY_CLEAR -> {
                _uiState.update { it.copy(
                    displayText = "",
                    hintText = context.getString(R.string.tap_touchpad_start)
                ) }
            }
            
            MessageType.HEARTBEAT -> {
                viewModelScope.launch {
                    bluetoothClient.sendMessage(
                        Message(type = MessageType.HEARTBEAT_ACK)
                    )
                }
            }
            
            MessageType.CAPTURE_PHOTO -> {
                // Phone requested to capture photo
                Log.d(TAG, "Phone requested photo capture")
                captureAndSendPhoto()
            }
            
            MessageType.PHOTO_ANALYSIS_RESULT -> {
                // Received photo analysis result from phone
                Log.d(TAG, "Photo analysis result: ${message.payload}")
                val analysisText = message.payload ?: context.getString(R.string.photo_analysis_no_result)
                
                // Clear capturing state and show result
                _uiState.update { it.copy(
                    isCapturingPhoto = false,
                    photoTransferProgress = 0f,
                    isProcessing = false
                ) }
                
                // Display the analysis result (with pagination)
                fullAiResponse = analysisText
                responsePages = paginateText(analysisText)
                val isPaginated = responsePages.size > 1
                val pageIndicator = if (isPaginated) " (1/${responsePages.size})" else ""
                val hintText = if (isPaginated) 
                    context.getString(R.string.swipe_left_right_pages)
                else 
                    context.getString(R.string.tap_touchpad_start)
                
                _uiState.update { it.copy(
                    displayText = responsePages[0] + pageIndicator,
                    hintText = hintText,
                    currentPage = 0,
                    totalPages = responsePages.size,
                    isPaginated = isPaginated
                ) }
            }
            
            else -> { 
                Log.d(TAG, "Unhandled message type: ${message.type}")
            }
        }
    }
    
    private fun playAudio(audioData: ByteArray) {
        // TODO: Use AudioTrack to play audio
        Log.d(TAG, "Playing audio: ${audioData.size} bytes")
    }
    
    /**
     * Paginate long text for glasses display
     * Splits text into pages based on character limit and line count
     */
    private fun paginateText(text: String): List<String> {
        if (text.length <= MAX_CHARS_PER_PAGE) {
            return listOf(text)
        }
        
        val pages = mutableListOf<String>()
        val words = text.split(" ", "，", "。", "、", "！", "？")
        var currentPage = StringBuilder()
        var lineCount = 0
        var charCount = 0
        
        for (word in words) {
            val wordWithSpace = if (currentPage.isEmpty()) word else " $word"
            val newCharCount = charCount + wordWithSpace.length
            
            // Check if adding this word would exceed limits
            if (newCharCount > MAX_CHARS_PER_PAGE || lineCount >= MAX_LINES_PER_PAGE) {
                if (currentPage.isNotEmpty()) {
                    pages.add(currentPage.toString().trim())
                    currentPage = StringBuilder()
                    charCount = 0
                    lineCount = 0
                }
            }
            
            currentPage.append(wordWithSpace)
            charCount = currentPage.length
            
            // Count newlines for line tracking
            if (word.contains("\n")) {
                lineCount += word.count { it == '\n' }
            }
        }
        
        // Add remaining text
        if (currentPage.isNotEmpty()) {
            pages.add(currentPage.toString().trim())
        }
        
        // If simple word splitting didn't work well, use character-based splitting
        if (pages.isEmpty() || (pages.size == 1 && text.length > MAX_CHARS_PER_PAGE)) {
            pages.clear()
            var i = 0
            while (i < text.length) {
                val end = minOf(i + MAX_CHARS_PER_PAGE, text.length)
                // Try to break at natural boundaries
                var breakPoint = end
                if (end < text.length) {
                    val lastSpace = text.lastIndexOf(' ', end)
                    val lastPunctuation = maxOf(
                        text.lastIndexOf('。', end),
                        text.lastIndexOf('，', end),
                        text.lastIndexOf('.', end),
                        text.lastIndexOf(',', end)
                    )
                    val naturalBreak = maxOf(lastSpace, lastPunctuation)
                    if (naturalBreak > i) {
                        breakPoint = naturalBreak + 1
                    }
                }
                pages.add(text.substring(i, breakPoint).trim())
                i = breakPoint
            }
        }
        
        return pages
    }
    
    /**
     * Navigate to next page (swipe down)
     */
    fun nextPage() {
        val currentState = _uiState.value
        if (currentState.isPaginated && currentState.currentPage < currentState.totalPages - 1) {
            val newPage = currentState.currentPage + 1
            val isLastPage = newPage == currentState.totalPages - 1
            _uiState.update { it.copy(
                currentPage = newPage,
                displayText = responsePages.getOrElse(newPage) { "" },
                hintText = if (isLastPage) context.getString(R.string.tap_continue) 
                          else context.getString(R.string.swipe_for_more)
            ) }
        }
    }
    
    /**
     * Navigate to previous page (swipe up)
     */
    fun previousPage() {
        val currentState = _uiState.value
        if (currentState.isPaginated && currentState.currentPage > 0) {
            val newPage = currentState.currentPage - 1
            _uiState.update { it.copy(
                currentPage = newPage,
                displayText = responsePages.getOrElse(newPage) { "" },
                hintText = context.getString(R.string.swipe_for_more)
            ) }
        }
    }
    
    /**
     * Dismiss pagination and return to normal state
     */
    fun dismissPagination() {
        resetPagination()
        _uiState.update { it.copy(
            displayText = context.getString(R.string.tap_touchpad_start),
            hintText = context.getString(R.string.tap_touchpad_record)
        ) }
    }
    
    /**
     * Reset pagination state (when starting new conversation)
     */
    private fun resetPagination() {
        fullAiResponse = ""
        responsePages = emptyList()
        _uiState.update { it.copy(
            currentPage = 0,
            totalPages = 1,
            isPaginated = false
        ) }
    }

    override fun onCleared() {
        super.onCleared()
        recordingJob?.cancel()
        audioRecord?.release()
        bluetoothClient.disconnect()
        cameraManager?.release()
        cxrServiceManager?.release()
    }
    
    // ==================== Photo Capture ====================
    
    /**
     * Capture photo and send to phone for AI analysis.
     * Triggered by camera key press or voice command.
     */
    fun captureAndSendPhoto() {
        if (_uiState.value.isCapturingPhoto) {
            Log.w(TAG, "Photo capture already in progress")
            return
        }
        
        if (_uiState.value.bluetoothState != BluetoothClientState.CONNECTED) {
            Log.w(TAG, "Not connected to phone")
            _uiState.update { it.copy(
                displayText = context.getString(R.string.bluetooth_not_connected),
                hintText = context.getString(R.string.connect_phone_first)
            ) }
            return
        }
        
        viewModelScope.launch {
            try {
                _uiState.update { it.copy(
                    isCapturingPhoto = true,
                    isProcessing = true,
                    displayText = "正在拍照...",
                    hintText = "請稍候"
                ) }
                
                // Step 1: Capture photo
                val cameraType = cameraManager?.getCameraTypeName() ?: "Unknown"
                Log.d(TAG, "Capturing photo using: $cameraType")
                val rawImageData = cameraManager?.capturePhoto()
                
                if (rawImageData == null) {
                    val cameraState = cameraManager?.cameraState?.value
                    Log.e(TAG, "Failed to capture photo. Camera state: $cameraState")
                    _uiState.update { it.copy(
                        isCapturingPhoto = false,
                        isProcessing = false,
                        displayText = "拍照失敗",
                        hintText = "相機可能被系統佔用，請稍後重試"
                    ) }
                    return@launch
                }
                
                Log.d(TAG, "Photo captured: ${rawImageData.size} bytes using $cameraType")
                
                // Step 2: Compress photo
                _uiState.update { it.copy(displayText = "正在壓縮...") }
                val compressedData = withContext(Dispatchers.Default) {
                    ImageCompressor.compressForTransfer(rawImageData)
                }
                Log.d(TAG, "Compressed: ${rawImageData.size} -> ${compressedData.size} bytes")
                
                // Step 3: Send to phone
                _uiState.update { it.copy(
                    displayText = "正在傳輸...",
                    photoTransferProgress = 0f
                ) }
                
                val socket = bluetoothClient.getSocket()
                if (socket == null || !socket.isConnected) {
                    throw IllegalStateException("Bluetooth socket not connected")
                }
                
                photoTransferProtocol = socket.createPhotoTransferProtocol { current, total ->
                    val progress = current.toFloat() / total
                    _uiState.update { it.copy(photoTransferProgress = progress) }
                }
                
                val result = photoTransferProtocol?.sendPhoto(compressedData)
                
                result?.fold(
                    onSuccess = { stats ->
                        Log.d(TAG, "Photo transfer complete: $stats")
                        _uiState.update { it.copy(
                            isCapturingPhoto = false,
                            displayText = "已發送，等待 AI 分析...",
                            hintText = "請稍候"
                        ) }
                        // Phone will send AI response via Bluetooth
                    },
                    onFailure = { error ->
                        Log.e(TAG, "Photo transfer failed", error)
                        _uiState.update { it.copy(
                            isCapturingPhoto = false,
                            isProcessing = false,
                            displayText = "傳輸失敗: ${error.message}",
                            hintText = "請重試"
                        ) }
                    }
                )
                
            } catch (e: Exception) {
                Log.e(TAG, "Photo capture error", e)
                _uiState.update { it.copy(
                    isCapturingPhoto = false,
                    isProcessing = false,
                    displayText = "錯誤: ${e.message}",
                    hintText = "請重試"
                ) }
            }
        }
    }
    
    /**
     * ViewModel Factory
     */
    class Factory(private val context: Context) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (modelClass.isAssignableFrom(GlassesViewModel::class.java)) {
                return GlassesViewModel(context.applicationContext) as T
            }
            throw IllegalArgumentException("Unknown ViewModel class")
        }
    }
}
