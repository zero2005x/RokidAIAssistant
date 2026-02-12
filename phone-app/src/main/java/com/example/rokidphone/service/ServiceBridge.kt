package com.example.rokidphone.service

import android.util.Log
import com.example.rokidcommon.protocol.Message
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

private const val TAG = "ServiceBridge"

/**
 * Bridge between Service and UI
 * Uses singleton pattern so Service and Activity/ViewModel can share message flow
 */
object ServiceBridge {
    
    private val _conversationFlow = MutableSharedFlow<Message>(replay = 0)
    val conversationFlow: SharedFlow<Message> = _conversationFlow.asSharedFlow()
    
    private val _connectionStateFlow = MutableSharedFlow<Boolean>(replay = 1)
    val connectionStateFlow: SharedFlow<Boolean> = _connectionStateFlow.asSharedFlow()
    
    // Service state - use StateFlow for reliable state synchronization
    private val _serviceStateFlow = MutableStateFlow(false)
    val serviceStateFlow: StateFlow<Boolean> = _serviceStateFlow.asStateFlow()
    
    // Bluetooth connection state
    private val _bluetoothStateFlow = MutableStateFlow(BluetoothConnectionState.DISCONNECTED)
    val bluetoothStateFlow: StateFlow<BluetoothConnectionState> = _bluetoothStateFlow.asStateFlow()
    
    // Connected device name
    private val _connectedDeviceNameFlow = MutableStateFlow<String?>(null)
    val connectedDeviceNameFlow: StateFlow<String?> = _connectedDeviceNameFlow.asStateFlow()
    
    // API Key missing notification
    private val _apiKeyMissingFlow = MutableSharedFlow<Unit>(replay = 0)
    val apiKeyMissingFlow: SharedFlow<Unit> = _apiKeyMissingFlow.asSharedFlow()
    
    // Send message to glasses (from text chat ViewModel)
    private val _sendToGlassesFlow = MutableSharedFlow<Message>(replay = 0)
    val sendToGlassesFlow: SharedFlow<Message> = _sendToGlassesFlow.asSharedFlow()

    /**
     * Send a message to glasses via PhoneAIService (called by ViewModel)
     */
    suspend fun sendToGlasses(message: Message) {
        Log.d(TAG, "Sending message to glasses: type=${message.type}")
        _sendToGlassesFlow.emit(message)
    }

    /**
     * Clean markdown formatting for better display on glasses
     */
    fun cleanMarkdown(text: String): String {
        return text
            // Remove bold/italic markers
            .replace(Regex("\\*\\*(.+?)\\*\\*"), "$1")  // **bold**
            .replace(Regex("\\*(.+?)\\*"), "$1")        // *italic*
            .replace(Regex("__(.+?)__"), "$1")          // __bold__
            .replace(Regex("_(.+?)_"), "$1")            // _italic_
            // Remove headers
            .replace(Regex("^#{1,6}\\s*", RegexOption.MULTILINE), "")
            // Remove code blocks
            .replace(Regex("```[\\s\\S]*?```"), "")
            .replace(Regex("`(.+?)`"), "$1")
            // Remove links but keep text
            .replace(Regex("\\[(.+?)]\\(.+?\\)"), "$1")
            // Remove bullet points
            .replace(Regex("^[\\-*+]\\s+", RegexOption.MULTILINE), "• ")
            // Remove numbered lists formatting
            .replace(Regex("^\\d+\\.\\s+", RegexOption.MULTILINE), "")
            // Clean up extra whitespace
            .replace(Regex("\\n{3,}"), "\n\n")
            .trim()
    }

    // Capture photo request from UI
    private val _capturePhotoFlow = MutableSharedFlow<Unit>(replay = 0)
    val capturePhotoFlow: SharedFlow<Unit> = _capturePhotoFlow.asSharedFlow()
    
    /**
     * Request glasses to capture photo (called by UI/ViewModel)
     */
    suspend fun requestCapturePhoto() {
        _capturePhotoFlow.emit(Unit)
    }
    
    /**
     * Emit conversation message (called by Service)
     */
    suspend fun emitConversation(message: Message) {
        _conversationFlow.emit(message)
    }
    
    /**
     * Update connection state (called by Service)
     */
    suspend fun updateConnectionState(isConnected: Boolean) {
        _connectionStateFlow.emit(isConnected)
    }
    
    /**
     * Update service state (called by Service)
     * Uses StateFlow value assignment for immediate state update
     */
    fun updateServiceState(isRunning: Boolean) {
        _serviceStateFlow.value = isRunning
    }
    
    /**
     * Update Bluetooth connection state (called by Service)
     */
    fun updateBluetoothState(state: BluetoothConnectionState) {
        Log.d(TAG, "Updating Bluetooth state: $state")
        _bluetoothStateFlow.value = state
    }
    
    /**
     * Update connected device name (called by Service)
     */
    fun updateConnectedDeviceName(name: String?) {
        Log.d(TAG, "Updating connected device name: $name")
        _connectedDeviceNameFlow.value = name
    }

    /**
     * Notify UI that API key is missing (called by Service)
     */
    suspend fun notifyApiKeyMissing() {
        _apiKeyMissingFlow.emit(Unit)
    }
    
    // Latest received photo path for UI display
    private val _latestPhotoPathFlow = MutableSharedFlow<String>(replay = 1)
    val latestPhotoPathFlow: SharedFlow<String> = _latestPhotoPathFlow.asSharedFlow()
    
    /**
     * Emit latest photo path (called by Service after saving photo)
     */
    suspend fun emitLatestPhotoPath(path: String) {
        Log.d(TAG, "Emitting latest photo path: $path")
        _latestPhotoPathFlow.emit(path)
    }
    
    // Connection control requests from UI
    private val _startListeningFlow = MutableSharedFlow<Unit>(replay = 0)
    val startListeningFlow: SharedFlow<Unit> = _startListeningFlow.asSharedFlow()
    
    private val _disconnectFlow = MutableSharedFlow<Unit>(replay = 0)
    val disconnectFlow: SharedFlow<Unit> = _disconnectFlow.asSharedFlow()
    
    /**
     * Request service to start Bluetooth listening (called by UI/ViewModel)
     */
    suspend fun requestStartListening() {
        Log.d(TAG, "Requesting start listening")
        _startListeningFlow.emit(Unit)
    }
    
    /**
     * Request service to disconnect Bluetooth (called by UI/ViewModel)
     */
    suspend fun requestDisconnect() {
        Log.d(TAG, "Requesting disconnect")
        _disconnectFlow.emit(Unit)
    }
    
    // ==================== Recording Control ====================
    
    // Glasses recording request
    private val _startGlassesRecordingFlow = MutableSharedFlow<String>(replay = 0)
    val startGlassesRecordingFlow: SharedFlow<String> = _startGlassesRecordingFlow.asSharedFlow()
    
    // Stop glasses recording request
    private val _stopGlassesRecordingFlow = MutableSharedFlow<Unit>(replay = 0)
    val stopGlassesRecordingFlow: SharedFlow<Unit> = _stopGlassesRecordingFlow.asSharedFlow()
    
    // Transcription request - use extraBufferCapacity to prevent loss when no collector
    data class TranscriptionRequest(val recordingId: String, val filePath: String)
    private val _transcribeRecordingFlow = MutableSharedFlow<TranscriptionRequest>(
        replay = 1,  // Keep last request for late collectors
        extraBufferCapacity = 5  // Buffer up to 5 pending requests
    )
    val transcribeRecordingFlow: SharedFlow<TranscriptionRequest> = _transcribeRecordingFlow.asSharedFlow()
    
    /**
     * Request glasses to start recording (called by ViewModel)
     */
    suspend fun requestStartGlassesRecording(recordingId: String) {
        Log.d(TAG, "Requesting glasses recording: $recordingId")
        _startGlassesRecordingFlow.emit(recordingId)
    }
    
    /**
     * Request glasses to stop recording (called by ViewModel)
     */
    suspend fun requestStopGlassesRecording() {
        Log.d(TAG, "Requesting glasses stop recording")
        _stopGlassesRecordingFlow.emit(Unit)
    }
    
    /**
     * Request transcription of recording (called by ViewModel after recording stops)
     */
    suspend fun requestTranscribeRecording(recordingId: String, filePath: String) {
        Log.d(TAG, "Requesting transcription: $recordingId")
        _transcribeRecordingFlow.emit(TranscriptionRequest(recordingId, filePath))
    }
}
