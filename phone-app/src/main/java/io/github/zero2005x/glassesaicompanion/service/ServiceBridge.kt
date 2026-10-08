package io.github.zero2005x.glassesaicompanion.service

import android.util.Log
import com.example.rokidcommon.protocol.Message
import kotlinx.coroutines.channels.BufferOverflow
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

    // One-shot command/event flows get a small buffer with an explicit overflow
    // policy so events are not silently dropped when no collector is attached
    // and emit() never suspends indefinitely on a slow collector.
    private const val COMMAND_BUFFER_CAPACITY = 16
    
    private val _conversationFlow = MutableSharedFlow<Message>(
        replay = 0,
        extraBufferCapacity = COMMAND_BUFFER_CAPACITY,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val conversationFlow: SharedFlow<Message> = _conversationFlow.asSharedFlow()
    
    // Connection state is state, not an event: model it as StateFlow
    private val _connectionStateFlow = MutableStateFlow(false)
    val connectionStateFlow: StateFlow<Boolean> = _connectionStateFlow.asStateFlow()
    
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
    private val _apiKeyMissingFlow = MutableSharedFlow<Unit>(
        replay = 0,
        extraBufferCapacity = COMMAND_BUFFER_CAPACITY,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val apiKeyMissingFlow: SharedFlow<Unit> = _apiKeyMissingFlow.asSharedFlow()
    
    // Send message to glasses (from text chat ViewModel)
    private val _sendToGlassesFlow = MutableSharedFlow<Message>(
        replay = 0,
        extraBufferCapacity = COMMAND_BUFFER_CAPACITY,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val sendToGlassesFlow: SharedFlow<Message> = _sendToGlassesFlow.asSharedFlow()

    /**
     * Send a message to glasses via PhoneAIService (called by ViewModel)
     */
    suspend fun sendToGlasses(message: Message) {
        Log.d(TAG, "Sending message to glasses: type=${message.type}")
        _sendToGlassesFlow.emit(message)
    }

    // Pre-compiled patterns for cleanMarkdown (compiled once, not per call).
    // Code blocks are stripped first so emphasis rules cannot mangle their
    // content, and underscore/asterisk rules require word boundaries so
    // identifiers like snake_case_names are left intact.
    private val codeBlockRegex = Regex("```[\\s\\S]*?```")
    private val inlineCodeRegex = Regex("`(.+?)`")
    private val boldAsteriskRegex = Regex("\\*\\*(.+?)\\*\\*")
    private val italicAsteriskRegex = Regex("(?<![\\w*])\\*(.+?)\\*(?![\\w*])")
    private val boldUnderscoreRegex = Regex("(?<![\\w])__(.+?)__(?![\\w])")
    private val italicUnderscoreRegex = Regex("(?<![\\w])_(.+?)_(?![\\w])")
    private val headerRegex = Regex("^#{1,6}\\s*", RegexOption.MULTILINE)
    private val linkRegex = Regex("\\[(.+?)]\\([^)]+\\)")
    private val bulletRegex = Regex("^[\\-*+]\\s+", RegexOption.MULTILINE)
    private val numberedListRegex = Regex("^\\d+\\.\\s+", RegexOption.MULTILINE)
    private val extraNewlinesRegex = Regex("\\n{3,}")

    /**
     * Clean markdown formatting for better display on glasses
     */
    fun cleanMarkdown(text: String): String {
        return text
            // Remove code blocks / inline code first
            .replace(codeBlockRegex, "")
            .replace(inlineCodeRegex, "$1")
            // Remove bold/italic markers
            .replace(boldAsteriskRegex, "$1")      // **bold**
            .replace(italicAsteriskRegex, "$1")    // *italic*
            .replace(boldUnderscoreRegex, "$1")    // __bold__
            .replace(italicUnderscoreRegex, "$1")  // _italic_
            // Remove headers
            .replace(headerRegex, "")
            // Remove links but keep text
            .replace(linkRegex, "$1")
            // Remove bullet points
            .replace(bulletRegex, "• ")
            // Remove numbered lists formatting
            .replace(numberedListRegex, "")
            // Clean up extra whitespace
            .replace(extraNewlinesRegex, "\n\n")
            .trim()
    }

    // Capture photo request from UI
    private val _capturePhotoFlow = MutableSharedFlow<Unit>(
        replay = 0,
        extraBufferCapacity = COMMAND_BUFFER_CAPACITY,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
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
    fun updateConnectionState(isConnected: Boolean) {
        _connectionStateFlow.value = isConnected
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
    
    // Latest received photo path for UI display (state, so a StateFlow)
    private val _latestPhotoPathFlow = MutableStateFlow<String?>(null)
    val latestPhotoPathFlow: StateFlow<String?> = _latestPhotoPathFlow.asStateFlow()
    
    /**
     * Emit latest photo path (called by Service after saving photo)
     */
    fun emitLatestPhotoPath(path: String) {
        Log.d(TAG, "Emitting latest photo path: $path")
        _latestPhotoPathFlow.value = path
    }
    
    // Connection control requests from UI
    private val _startListeningFlow = MutableSharedFlow<Unit>(
        replay = 0,
        extraBufferCapacity = COMMAND_BUFFER_CAPACITY,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val startListeningFlow: SharedFlow<Unit> = _startListeningFlow.asSharedFlow()
    
    private val _disconnectFlow = MutableSharedFlow<Unit>(
        replay = 0,
        extraBufferCapacity = COMMAND_BUFFER_CAPACITY,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
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
    private val _startGlassesRecordingFlow = MutableSharedFlow<String>(
        replay = 0,
        extraBufferCapacity = COMMAND_BUFFER_CAPACITY,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val startGlassesRecordingFlow: SharedFlow<String> = _startGlassesRecordingFlow.asSharedFlow()
    
    // Stop glasses recording request
    private val _stopGlassesRecordingFlow = MutableSharedFlow<Unit>(
        replay = 0,
        extraBufferCapacity = COMMAND_BUFFER_CAPACITY,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val stopGlassesRecordingFlow: SharedFlow<Unit> = _stopGlassesRecordingFlow.asSharedFlow()
    
    // Transcription request - replay = 0 so late collectors never re-run a stale
    // request; extraBufferCapacity prevents loss when no collector is attached yet.
    data class TranscriptionRequest(val recordingId: String, val filePath: String)
    private val _transcribeRecordingFlow = MutableSharedFlow<TranscriptionRequest>(
        replay = 0,
        extraBufferCapacity = 16,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val transcribeRecordingFlow: SharedFlow<TranscriptionRequest> = _transcribeRecordingFlow.asSharedFlow()

    private val _transcriptionCompletedFlow = MutableSharedFlow<String>(extraBufferCapacity = 16)
    val transcriptionCompletedFlow: SharedFlow<String> = _transcriptionCompletedFlow.asSharedFlow()
    
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
    /**
     * Reset all persisted state. As a process-wide object this state would
     * otherwise survive service death and show a stale "connected" status.
     * Called from the service's onDestroy().
     */
    fun reset() {
        _serviceStateFlow.value = false
        _connectionStateFlow.value = false
        _bluetoothStateFlow.value = BluetoothConnectionState.DISCONNECTED
        _connectedDeviceNameFlow.value = null
        _latestPhotoPathFlow.value = null
    }

    fun notifyTranscriptionCompleted(recordingId: String) {
        _transcriptionCompletedFlow.tryEmit(recordingId)
    }
}
