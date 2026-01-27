package com.example.rokidphone.viewmodel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.rokidcommon.protocol.ConnectionState
import com.example.rokidcommon.protocol.MessageType
import com.example.rokidphone.ConversationItem
import com.example.rokidphone.service.BluetoothConnectionState
import com.example.rokidphone.service.ServiceBridge
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private const val TAG = "PhoneViewModel"

data class PhoneUiState(
    val connectionState: ConnectionState = ConnectionState.DISCONNECTED,
    val bluetoothState: BluetoothConnectionState = BluetoothConnectionState.DISCONNECTED,
    val connectedGlassesName: String? = null,
    val isServiceRunning: Boolean = false,
    val processingStatus: String? = null,
    val conversations: List<ConversationItem> = emptyList(),
    val isScanning: Boolean = false,
    val availableDevices: List<String> = emptyList(),
    val showApiKeyWarning: Boolean = false,  // Flag to show API key warning dialog
    val showInitialSetup: Boolean = false,   // Flag to show initial setup dialog (no API key configured)
    val latestPhotoPath: String? = null      // Path to the latest received photo
)

class PhoneViewModel : ViewModel() {
    
    private val _uiState = MutableStateFlow(PhoneUiState())
    val uiState: StateFlow<PhoneUiState> = _uiState.asStateFlow()
    
    init {
        // Listen to service state
        viewModelScope.launch {
            ServiceBridge.serviceStateFlow.collect { isRunning ->
                _uiState.update { it.copy(isServiceRunning = isRunning) }
            }
        }
        
        // Listen to Bluetooth connection state
        viewModelScope.launch {
            ServiceBridge.bluetoothStateFlow.collect { state ->
                val connectionState = when (state) {
                    BluetoothConnectionState.DISCONNECTED -> ConnectionState.DISCONNECTED
                    BluetoothConnectionState.LISTENING -> ConnectionState.DISCONNECTED
                    BluetoothConnectionState.CONNECTING -> ConnectionState.CONNECTING
                    BluetoothConnectionState.CONNECTED -> ConnectionState.CONNECTED
                }
                
                _uiState.update { it.copy(
                    bluetoothState = state,
                    connectionState = connectionState,
                    connectedGlassesName = if (state == BluetoothConnectionState.CONNECTED) 
                        "Rokid Glasses" else null
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
    
    fun startScanning() {
        // Service will automatically start listening for Bluetooth connection
        // No additional operation needed here
    }
    
    fun disconnect() {
        // Server side will handle disconnection
        _uiState.update { it.copy(
            connectionState = ConnectionState.DISCONNECTED,
            connectedGlassesName = null
        ) }
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
                conversations = state.conversations + ConversationItem(role, content)
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
}
