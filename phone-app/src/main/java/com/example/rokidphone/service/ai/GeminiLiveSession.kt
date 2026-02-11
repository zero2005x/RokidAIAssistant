package com.example.rokidphone.service.ai

import android.content.Context
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

/**
 * Gemini Live Session Coordinator
 *
 * Functionality:
 * - Coordinates GeminiLiveService (WebSocket) and LiveAudioManager (Audio).
 * - Manages the full lifecycle of a Live session (start/stop).
 * - Handles bidirectional audio data transmission.
 * - Integrates ToolCallRouter for tool call routing and execution.
 * - Provides unified session state and events to the upper layer (PhoneAIService / ViewModel).
 *
 * Integration with existing architecture:
 * - Created and managed within PhoneAIService.
 * - Communicates with the glasses via MessageType.
 * - Does not affect the existing REST API mode (GeminiService).
 *
 * Android specific handling:
 * - Ensures Context is available to initialize LiveAudioManager.
 * - Managed within the Service lifecycle to avoid memory leaks.
 * - Handles Activity/Service state changes.
 */
class GeminiLiveSession(
    private val context: Context,
    private val apiKey: String,
    private val modelId: String = "gemini-2.5-flash-preview-native-audio-dialog",
    private val systemPrompt: String = ""
) {
    companion object {
        private const val TAG = "GeminiLiveSession"
    }

    // ========== Session State ==========

    /**
     * Session State
     */
    enum class SessionState {
        IDLE,           // Idle, not started
        CONNECTING,     // Connecting to WebSocket
        ACTIVE,         // Session active (can send/receive audio)
        PAUSED,         // Session paused
        DISCONNECTING,  // Disconnecting
        ERROR           // Error
    }

    private val _sessionState = MutableStateFlow(SessionState.IDLE)
    val sessionState = _sessionState.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage = _errorMessage.asStateFlow()

    // ========== Session Components ==========

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private var liveService: GeminiLiveService? = null
    private var audioManager: LiveAudioManager? = null
    private var toolCallRouter: ToolCallRouter? = null

    // ========== Event Streams ==========

    /**
     * User speech transcription event
     */
    private val _inputTranscription = MutableSharedFlow<String>()
    val inputTranscription = _inputTranscription.asSharedFlow()

    /**
     * AI response transcription event
     */
    private val _outputTranscription = MutableSharedFlow<String>()
    val outputTranscription = _outputTranscription.asSharedFlow()

    /**
     * AI turn complete event
     */
    private val _turnComplete = MutableSharedFlow<Unit>()
    val turnComplete = _turnComplete.asSharedFlow()

    /**
     * Session interrupted event (User interrupted AI)
     */
    private val _interrupted = MutableSharedFlow<Unit>()
    val interrupted = _interrupted.asSharedFlow()

    /**
     * Tool call event
     */
    private val _toolCalls = MutableSharedFlow<List<GeminiLiveService.ToolCall>>()
    val toolCalls = _toolCalls.asSharedFlow()

    // ========== Configuration ==========

    /**
     * VAD Configuration
     */
    data class VadConfig(
        val startSensitivity: GeminiLiveService.StartOfSpeechSensitivity =
            GeminiLiveService.StartOfSpeechSensitivity.START_SENSITIVITY_HIGH,
        val endSensitivity: GeminiLiveService.EndOfSpeechSensitivity =
            GeminiLiveService.EndOfSpeechSensitivity.END_SENSITIVITY_LOW,
        val silenceDurationMs: Int = 500,
        val activityHandling: GeminiLiveService.ActivityHandling =
            GeminiLiveService.ActivityHandling.START_OF_ACTIVITY_INTERRUPTS
    )

    private var vadConfig = VadConfig()

    // ========== Tool Declarations ==========

    /**
     * Tool declaration list
     * Uses ToolDeclarations.allDeclarations() to provide default declarations
     */
    private var toolDeclarations: List<JSONObject>? = null

    // ========== Session Control ==========

    /**
     * Start Live Session
     *
     * @param vadConfig VAD configuration (optional)
     * @param tools Tool declarations (optional)
     * @return Whether the startup was successful
     */
    fun start(
        vadConfig: VadConfig = VadConfig(),
        tools: List<JSONObject>? = null
    ): Boolean {
        if (_sessionState.value != SessionState.IDLE && _sessionState.value != SessionState.ERROR) {
            Log.w(TAG, "Session is already in progress, state: ${_sessionState.value}")
            return false
        }

        Log.d(TAG, "Starting Live session")

        this.vadConfig = vadConfig
        this.toolDeclarations = tools

        _errorMessage.value = null
        _sessionState.value = SessionState.CONNECTING

        try {
            // Initialize ToolCallRouter
            toolCallRouter = ToolCallRouter(scope).also { router ->
                // Collect tool execution results and return to WebSocket
                scope.launch {
                    router.toolResults.collect { result ->
                        Log.d(TAG, "Tool result received: ${result.toolCallId}, success=${result.success}")
                        sendToolResponse(result.toolCallId, result.toResponseJson())
                    }
                }
            }

            // Initialize Audio Manager
            audioManager = LiveAudioManager(context, scope).apply {
                // Set audio chunk callback
                onAudioChunk = { chunk ->
                    // Send recording data to Gemini
                    liveService?.sendAudio(chunk)
                }

                onPlaybackComplete = {
                    Log.d(TAG, "AI response playback complete")
                }

                onError = { error ->
                    Log.e(TAG, "Audio error: $error")
                    _errorMessage.value = error
                }
            }

            // Initialize WebSocket Service
            liveService = GeminiLiveService(
                apiKey = apiKey,
                modelId = modelId,
                systemPrompt = systemPrompt,
                scope = scope
            ).apply {
                // Update VAD settings
                updateVadSettings(
                    startSensitivity = vadConfig.startSensitivity,
                    endSensitivity = vadConfig.endSensitivity,
                    silenceDurationMs = vadConfig.silenceDurationMs,
                    activityHandling = vadConfig.activityHandling
                )

                // Set callbacks
                onConnectionStateChanged = { state ->
                    handleConnectionStateChange(state)
                }

                onAudioReceived = { audioData ->
                    handleAudioReceived(audioData)
                }

                onTurnComplete = {
                    handleTurnComplete()
                }

                onInterrupted = {
                    handleInterrupted()
                }

                onToolCall = { calls ->
                    handleToolCalls(calls)
                }

                onInputTranscription = { text ->
                    handleInputTranscription(text)
                }

                onOutputTranscription = { text ->
                    handleOutputTranscription(text)
                }
            }

            // Request audio focus
            audioManager?.requestAudioFocus()

            // Start connection
            liveService?.connect(tools)

            return true

        } catch (e: Exception) {
            Log.e(TAG, "Failed to start session", e)
            _errorMessage.value = e.message ?: "Startup failed"
            _sessionState.value = SessionState.ERROR
            return false
        }
    }

    /**
     * Stop Live Session
     */
    fun stop() {
        if (_sessionState.value == SessionState.IDLE) {
            Log.w(TAG, "Session not started")
            return
        }

        Log.d(TAG, "Stopping Live session")
        _sessionState.value = SessionState.DISCONNECTING

        try {
            // Stop recording and playback
            audioManager?.release()
            audioManager = null

            // Disconnect WebSocket
            liveService?.disconnect()
            liveService = null

            _sessionState.value = SessionState.IDLE
            Log.d(TAG, "Session ended")

        } catch (e: Exception) {
            Log.e(TAG, "Error occurred while stopping session", e)
            _sessionState.value = SessionState.IDLE
        }
    }

    /**
     * Pause session (stop recording but keep connection)
     */
    fun pause() {
        if (_sessionState.value != SessionState.ACTIVE) {
            Log.w(TAG, "Session not active, cannot pause")
            return
        }

        Log.d(TAG, "Pausing session")
        audioManager?.stopRecording()
        _sessionState.value = SessionState.PAUSED
    }

    /**
     * Resume session
     */
    fun resume() {
        if (_sessionState.value != SessionState.PAUSED) {
            Log.w(TAG, "Session is not paused")
            return
        }

        Log.d(TAG, "Resuming session")
        audioManager?.startRecording()
        _sessionState.value = SessionState.ACTIVE
    }

    // ========== Data Transmission ==========

    /**
     * Send video frame
     *
     * @param jpegData Image data in JPEG format
     */
    fun sendVideoFrame(jpegData: ByteArray) {
        if (_sessionState.value != SessionState.ACTIVE) {
            Log.w(TAG, "Session not active, cannot send video")
            return
        }

        liveService?.sendVideoFrame(jpegData)
    }

    /**
     * Send tool call response
     *
     * @param toolCallId Tool Call ID
     * @param result Execution result
     */
    fun sendToolResponse(toolCallId: String, result: JSONObject) {
        liveService?.sendToolResponse(toolCallId, result)
    }

    /**
     * Manually end input turn
     * Used when not using automatic VAD
     */
    fun endOfTurn() {
        liveService?.endOfTurn()
    }

    // ========== Event Handling ==========

    /**
     * Handle connection state changes
     */
    private fun handleConnectionStateChange(state: GeminiLiveService.ConnectionState) {
        Log.d(TAG, "Connection state changed: $state")

        when (state) {
            GeminiLiveService.ConnectionState.DISCONNECTED -> {
                if (_sessionState.value != SessionState.DISCONNECTING) {
                    // Unexpected disconnection
                    _sessionState.value = SessionState.ERROR
                    _errorMessage.value = "Connection lost"
                }
            }

            GeminiLiveService.ConnectionState.CONNECTING,
            GeminiLiveService.ConnectionState.SETTING_UP -> {
                _sessionState.value = SessionState.CONNECTING
            }

            GeminiLiveService.ConnectionState.READY -> {
                _sessionState.value = SessionState.ACTIVE

                // Connection ready, start recording
                scope.launch {
                    delay(100)  // Short delay to ensure WebSocket stability
                    audioManager?.startRecording()
                }

                Log.d(TAG, "Session ready, starting recording")
            }

            GeminiLiveService.ConnectionState.ERROR -> {
                _sessionState.value = SessionState.ERROR
                _errorMessage.value = liveService?.errorMessage?.value ?: "Connection error"
            }
        }
    }

    /**
     * Handle received AI audio
     */
    private fun handleAudioReceived(audioData: ByteArray) {
        // Add audio to playback queue
        audioManager?.playAudio(audioData)
    }

    /**
     * Handle turn completion
     */
    private fun handleTurnComplete() {
        Log.d(TAG, "AI turn complete")

        // Finish playback
        audioManager?.finishPlayback()

        // Emit event
        scope.launch {
            _turnComplete.emit(Unit)
        }
    }

    /**
     * Handle interruption
     */
    private fun handleInterrupted() {
        Log.d(TAG, "AI interrupted by user")

        // Stop playback immediately
        audioManager?.stopPlayback()

        // Emit event
        scope.launch {
            _interrupted.emit(Unit)
        }
    }

    /**
     * Handle tool calls - route to ToolCallRouter for execution
     */
    private fun handleToolCalls(calls: List<GeminiLiveService.ToolCall>) {
        Log.d(TAG, "Received ${calls.size} tool calls")

        // Send event to upper layer (PhoneAIService can listen)
        scope.launch {
            _toolCalls.emit(calls)
        }

        // Route execution through ToolCallRouter
        // Router will automatically return results via toolResults flow
        val router = toolCallRouter
        if (router != null) {
            router.handleToolCalls(calls)
        } else {
            // Fallback: If router is not initialized, send default responses directly
            Log.w(TAG, "ToolCallRouter not initialized, sending default responses")
            for (call in calls) {
                val result = JSONObject().apply {
                    put("status", "success")
                    put("message", "Tool ${call.name} acknowledged (router not ready)")
                }
                sendToolResponse(call.id, result)
            }
        }
    }

    /**
     * Handle user speech transcription
     */
    private fun handleInputTranscription(text: String) {
        Log.d(TAG, "User said: $text")
        scope.launch {
            _inputTranscription.emit(text)
        }
    }

    /**
     * Handle AI response transcription
     */
    private fun handleOutputTranscription(text: String) {
        Log.d(TAG, "AI said: $text")
        scope.launch {
            _outputTranscription.emit(text)
        }
    }

    // ========== Resource Release ==========

    /**
     * Release all resources
     */
    fun release() {
        Log.d(TAG, "Releasing GeminiLiveSession resources")

        // Cancel all in-flight tool calls
        toolCallRouter?.cancelAll()
        toolCallRouter?.release()
        toolCallRouter = null

        stop()

        audioManager?.abandonAudioFocus()

        scope.cancel()
    }

    /**
     * Get ToolCallRouter (for external custom handler registration)
     */
    fun getToolCallRouter(): ToolCallRouter? = toolCallRouter

    // ========== Tool Declarations ==========

    /**
     * Get default tool declarations (delegated to ToolDeclarations)
     */
    fun getDefaultToolDeclarations(): List<JSONObject> {
        return ToolDeclarations.allDeclarations()
    }
}