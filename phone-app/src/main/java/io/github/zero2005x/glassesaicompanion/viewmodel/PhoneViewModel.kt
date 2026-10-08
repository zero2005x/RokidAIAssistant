package io.github.zero2005x.glassesaicompanion.viewmodel

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.rokidcommon.protocol.ConnectionState
import com.example.rokidcommon.protocol.MessageType
import io.github.zero2005x.glassesaicompanion.ConversationItem
import io.github.zero2005x.glassesaicompanion.data.ForegroundRecordingPolicy
import io.github.zero2005x.glassesaicompanion.data.db.RecordingRepository
import io.github.zero2005x.glassesaicompanion.data.db.RecordingSource
import io.github.zero2005x.glassesaicompanion.data.db.RecordingState
import io.github.zero2005x.glassesaicompanion.service.BluetoothConnectionState
import io.github.zero2005x.glassesaicompanion.service.ServiceBridge
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private const val TAG = "PhoneViewModel"
private const val MAX_CONVERSATION_ITEMS = 200

data class PhoneUiState(
    val connectionState: ConnectionState = ConnectionState.DISCONNECTED,
    val bluetoothState: BluetoothConnectionState = BluetoothConnectionState.DISCONNECTED,
    val connectedGlassesName: String? = null,
    val isServiceRunning: Boolean = false,
    val processingStatus: String? = null,
    val conversations: List<ConversationItem> = emptyList(),
    val showApiKeyWarning: Boolean = false,  // Flag to show API key warning dialog
    val showInitialSetup: Boolean = false,   // Flag to show initial setup dialog (no API key configured)
    val latestPhotoPath: String? = null,     // Path to the latest received photo
    val recordingState: RecordingState = RecordingState.Idle,  // Recording state
    val recordingStoppedInBackground: Boolean = false  // Phone recording was ended because the app was hidden
)

class PhoneViewModel(application: Application) : AndroidViewModel(application) {
    
    private val _uiState = MutableStateFlow(PhoneUiState())
    val uiState: StateFlow<PhoneUiState> = _uiState.asStateFlow()
    
    // Recording repository
    private val recordingRepository = RecordingRepository.getInstance(application)
    
    init {
        // Listen to recording state
        viewModelScope.launch {
            recordingRepository.recordingState.collect { state ->
                _uiState.update { it.copy(recordingState = state) }
            }
        }
        
        // Listen to service state
        viewModelScope.launch {
            ServiceBridge.serviceStateFlow.collect { isRunning ->
                _uiState.update { it.copy(isServiceRunning = isRunning) }
            }
        }
        
        // Listen to Bluetooth connection state
        viewModelScope.launch {
            ServiceBridge.bluetoothStateFlow.collect { state ->
                Log.d(TAG, "Bluetooth state updated: $state")
                val connectionState = when (state) {
                    BluetoothConnectionState.DISCONNECTED -> ConnectionState.DISCONNECTED
                    BluetoothConnectionState.LISTENING -> ConnectionState.DISCONNECTED
                    BluetoothConnectionState.CONNECTING -> ConnectionState.CONNECTING
                    BluetoothConnectionState.CONNECTED -> ConnectionState.CONNECTED
                }
                
                _uiState.update { it.copy(
                    bluetoothState = state,
                    connectionState = connectionState
                ) }
            }
        }
        
        // Listen to connected device name
        viewModelScope.launch {
            ServiceBridge.connectedDeviceNameFlow.collect { name ->
                Log.d(TAG, "Connected device name updated: $name")
                _uiState.update { it.copy(
                    connectedGlassesName = name
                ) }
            }
        }
        
        // Listen to API Key missing notifications
        viewModelScope.launch {
            ServiceBridge.apiKeyMissingFlow.collect {
                _uiState.update { it.copy(showApiKeyWarning = true) }
            }
        }
        
        // Listen to latest photo path for display
        viewModelScope.launch {
            ServiceBridge.latestPhotoPathFlow.collect { path ->
                Log.d(TAG, "Received latest photo path: $path")
                _uiState.update { it.copy(latestPhotoPath = path) }
            }
        }
        
        // Listen to conversation messages (voice input from glasses and AI response)
        viewModelScope.launch {
            ServiceBridge.conversationFlow.collect { message ->
                when (message.type) {
                    MessageType.USER_TRANSCRIPT -> {
                        // User's speech
                        message.payload?.let { text ->
                            addConversation("user", text)
                        }
                    }
                    MessageType.AI_RESPONSE_TEXT -> {
                        // AI's response
                        message.payload?.let { text ->
                            addConversation("assistant", text)
                        }
                    }
                    MessageType.AI_PROCESSING -> {
                        // Update processing status
                        _uiState.update { it.copy(processingStatus = message.payload) }
                    }
                    else -> { /* Other types */ }
                }
            }
        }
    }
    
    /**
     * Request service to start Bluetooth listening
     * This restarts the server socket to accept new connections
     */
    fun startScanning() {
        viewModelScope.launch {
            _uiState.update { it.copy(connectionState = ConnectionState.CONNECTING) }
            ServiceBridge.requestStartListening()
        }
    }
    
    /**
     * Request service to disconnect current Bluetooth connection
     * Note: UI state will be updated automatically through the bluetoothStateFlow
     */
    fun disconnect() {
        viewModelScope.launch {
            Log.d(TAG, "Requesting disconnect")
            ServiceBridge.requestDisconnect()
            // Don't manually override state here - let the flow update it naturally
            // This ensures UI stays in sync with actual Bluetooth state
        }
    }
    
    fun updateServiceStatus(isRunning: Boolean) {
        _uiState.update { it.copy(isServiceRunning = isRunning) }
    }
    
    fun updateProcessingStatus(status: String?) {
        _uiState.update { it.copy(processingStatus = status) }
    }
    
    fun addConversation(role: String, content: String) {
        _uiState.update { state ->
            state.copy(
                conversations = (state.conversations + ConversationItem(role, content))
                    .takeLast(MAX_CONVERSATION_ITEMS)
            )
        }
    }
    
    fun clearConversations() {
        _uiState.update { it.copy(conversations = emptyList()) }
    }
    
    fun dismissApiKeyWarning() {
        _uiState.update { it.copy(showApiKeyWarning = false) }
    }
    
    /**
     * Check if initial setup is needed (no API key configured at all)
     * Called from UI when settings are loaded
     */
    fun checkInitialSetup(hasAnyApiKey: Boolean) {
        if (!hasAnyApiKey) {
            _uiState.update { it.copy(showInitialSetup = true) }
        }
    }
    
    /**
     * Dismiss initial setup dialog
     */
    fun dismissInitialSetup() {
        _uiState.update { it.copy(showInitialSetup = false) }
    }
    
    /**
     * Request glasses to capture and send photo
     */
    fun requestCapturePhoto() {
        viewModelScope.launch {
            ServiceBridge.requestCapturePhoto()
        }
    }
    
    // ==================== Recording Control ====================
    
    /**
     * Start recording from phone microphone
     */
    fun startPhoneRecording() {
        viewModelScope.launch {
            val result = recordingRepository.startPhoneRecording()
            result.onFailure { error ->
                Log.e(TAG, "Failed to start phone recording", error)
            }
        }
    }
    
    /**
     * Start recording from glasses microphone
     */
    fun startGlassesRecording() {
        viewModelScope.launch {
            val result = recordingRepository.startGlassesRecording()
            result.onSuccess { recordingId ->
                // Send command to glasses to start recording
                ServiceBridge.requestStartGlassesRecording(recordingId)
            }
            result.onFailure { error ->
                Log.e(TAG, "Failed to start glasses recording", error)
            }
        }
    }
    
    /**
     * Pause current recording (if supported)
     */
    fun pauseRecording() {
        viewModelScope.launch {
            recordingRepository.pauseRecording()
        }
    }
    
    /**
     * Stop current recording and send to AI for analysis
     */
    fun stopRecording() {
        viewModelScope.launch {
            val result = recordingRepository.stopRecording()
            result.onSuccess { recording ->
                recording?.let {
                    Log.d(TAG, "Recording stopped: ${it.id}, source: ${it.source}, duration: ${it.durationMs}ms")
                    
                    // Only request transcription for phone recordings
                    // Glasses recordings are processed via Bluetooth when the audio data arrives
                    if (it.source == RecordingSource.PHONE && it.filePath.isNotBlank()) {
                        ServiceBridge.requestTranscribeRecording(it.id, it.filePath)
                    } else if (it.source == RecordingSource.GLASSES) {
                        Log.d(TAG, "Glasses recording stopped, sending stop command and waiting for audio via Bluetooth")
                        // Send stop command to glasses - audio data will be received via Bluetooth
                        // The processVoiceData() in PhoneAIService will handle transcription
                        ServiceBridge.requestStopGlassesRecording()
                    }
                }
            }
            result.onFailure { error ->
                Log.e(TAG, "Failed to stop recording", error)
            }
        }
    }
    /**
     * The app left the foreground. Phone-microphone recording is foreground-only, so an
     * active phone recording is stopped (and saved) and the user is told on return.
     */
    fun onAppBackgrounded() {
        if (ForegroundRecordingPolicy.shouldStopWhenBackgrounded(recordingRepository.recordingState.value)) {
            Log.d(TAG, "App backgrounded during a phone recording; stopping it")
            _uiState.update { it.copy(recordingStoppedInBackground = true) }
            stopRecording()
        }
    }

    fun dismissRecordingStoppedNotice() {
        _uiState.update { it.copy(recordingStoppedInBackground = false) }
    }
}
