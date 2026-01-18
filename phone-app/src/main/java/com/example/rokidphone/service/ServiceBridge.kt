package com.example.rokidphone.service

import com.example.rokidcommon.protocol.Message
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Bridge between Service and UI
 * Uses singleton pattern so Service and Activity/ViewModel can share message flow
 */
object ServiceBridge {
    
    private val _conversationFlow = MutableSharedFlow<Message>(replay = 0)
    val conversationFlow: SharedFlow<Message> = _conversationFlow.asSharedFlow()
    
    private val _connectionStateFlow = MutableSharedFlow<Boolean>(replay = 1)
    val connectionStateFlow: SharedFlow<Boolean> = _connectionStateFlow.asSharedFlow()
    
    private val _serviceStateFlow = MutableSharedFlow<Boolean>(replay = 1)
    val serviceStateFlow: SharedFlow<Boolean> = _serviceStateFlow.asSharedFlow()
    
    // Bluetooth connection state
    private val _bluetoothStateFlow = MutableStateFlow(BluetoothConnectionState.DISCONNECTED)
    val bluetoothStateFlow: StateFlow<BluetoothConnectionState> = _bluetoothStateFlow.asStateFlow()
    
    // API Key missing notification
    private val _apiKeyMissingFlow = MutableSharedFlow<Unit>(replay = 0)
    val apiKeyMissingFlow: SharedFlow<Unit> = _apiKeyMissingFlow.asSharedFlow()
    
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
     */
    suspend fun updateServiceState(isRunning: Boolean) {
        _serviceStateFlow.emit(isRunning)
    }
    
    /**
     * Update Bluetooth connection state (called by Service)
     */
    suspend fun updateBluetoothState(state: BluetoothConnectionState) {
        _bluetoothStateFlow.emit(state)
    }
    
    /**
     * Notify UI that API key is missing (called by Service)
     */
    suspend fun notifyApiKeyMissing() {
        _apiKeyMissingFlow.emit(Unit)
    }
}
