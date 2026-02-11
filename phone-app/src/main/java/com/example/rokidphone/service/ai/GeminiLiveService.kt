package com.example.rokidphone.service.ai

import android.util.Base64
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.*
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Gemini Live API WebSocket real-time streaming service.
 *
 * Features:
 * - Connects to Gemini Live API via WebSocket for bidirectional real-time voice conversation.
 * - Sends PCM audio (16kHz) for real-time speech recognition.
 * - Sends video frames (JPEG) for real-time vision understanding.
 * - Receives AI response audio (PCM 24kHz) for immediate playback.
 * - Supports VAD (Voice Activity Detection) for automatic speech start/end detection.
 * - Supports Function Calling (tool calls).
 * - Supports real-time input/output audio transcription.
 *
 * Integration with existing architecture:
 * - Coordinated by [GeminiLiveSession] alongside [LiveAudioManager].
 * - Used as the GEMINI_LIVE mode implementation in PhoneAIService.
 *
 * Android-specific notes:
 * - Uses OkHttp WebSocket client (already in project dependencies).
 * - Manages WebSocket lifecycle with CoroutineScope.
 * - Uses Android-native Base64 encoding.
 */
class GeminiLiveService(
    private val apiKey: String,
    private val modelId: String = "gemini-2.5-flash-preview-native-audio-dialog",
    private val systemPrompt: String = "",
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
) {
    companion object {
        private const val TAG = "GeminiLiveService"

        // Gemini Live API WebSocket endpoint
        private const val WEBSOCKET_BASE_URL =
            "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent"

        // Audio format constants
        const val INPUT_SAMPLE_RATE = 16000   // Input audio sample rate (16kHz)
        const val OUTPUT_SAMPLE_RATE = 24000  // Output audio sample rate (24kHz)
        const val AUDIO_CHANNELS = 1          // Mono
        const val BITS_PER_SAMPLE = 16        // 16-bit PCM

        // VAD configuration constant
        const val DEFAULT_SILENCE_DURATION_MS = 500
    }

    // ========== Connection State ==========

    /**
     * WebSocket connection state machine.
     */
    enum class ConnectionState {
        DISCONNECTED,  // Not connected
        CONNECTING,    // Connecting to server
        SETTING_UP,    // Connected, waiting for setup completion
        READY,         // Ready to send/receive audio & video
        ERROR          // Error state
    }

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState = _connectionState.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage = _errorMessage.asStateFlow()

    // ========== WebSocket Instance ==========

    private var webSocket: WebSocket? = null
    private val isConnected = AtomicBoolean(false)

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)      // No read timeout for WebSocket
        .writeTimeout(30, TimeUnit.SECONDS)
        .pingInterval(20, TimeUnit.SECONDS)    // Keep-alive ping interval
        .build()

    // ========== Callback Interfaces ==========

    /**
     * Called when AI response audio data is received.
     * @param pcmData PCM audio (24kHz, 16-bit, mono)
     */
    var onAudioReceived: ((ByteArray) -> Unit)? = null

    /**
     * Called when the AI response turn is complete (one full response finished).
     */
    var onTurnComplete: (() -> Unit)? = null

    /**
     * Called when the AI response is interrupted (user started speaking).
     */
    var onInterrupted: (() -> Unit)? = null

    /**
     * Called when a tool call request is received from the model.
     * @param toolCalls List of tool call requests
     */
    var onToolCall: ((List<ToolCall>) -> Unit)? = null

    /**
     * Called when user speech transcription text is received.
     */
    var onInputTranscription: ((String) -> Unit)? = null

    /**
     * Called when AI response transcription text is received.
     */
    var onOutputTranscription: ((String) -> Unit)? = null

    /**
     * Called when connection state changes.
     */
    var onConnectionStateChanged: ((ConnectionState) -> Unit)? = null

    // ========== Tool Call Data Structure ==========

    /**
     * Represents a single tool (function) call from the model.
     */
    data class ToolCall(
        val id: String,
        val name: String,
        val args: JSONObject
    )

    // ========== VAD Configuration ==========

    /**
     * Voice Activity Detection — start-of-speech sensitivity level.
     */
    enum class StartOfSpeechSensitivity {
        START_SENSITIVITY_UNSPECIFIED,
        START_SENSITIVITY_LOW,
        START_SENSITIVITY_MEDIUM,
        START_SENSITIVITY_HIGH
    }

    /**
     * Voice Activity Detection — end-of-speech sensitivity level.
     */
    enum class EndOfSpeechSensitivity {
        END_SENSITIVITY_UNSPECIFIED,
        END_SENSITIVITY_LOW,
        END_SENSITIVITY_MEDIUM,
        END_SENSITIVITY_HIGH
    }

    /**
     * Activity handling policy — whether user speech interrupts the AI.
     */
    enum class ActivityHandling {
        ACTIVITY_HANDLING_UNSPECIFIED,
        START_OF_ACTIVITY_INTERRUPTS,
        NO_INTERRUPT
    }

    // Current VAD settings
    private var startSensitivity = StartOfSpeechSensitivity.START_SENSITIVITY_HIGH
    private var endSensitivity = EndOfSpeechSensitivity.END_SENSITIVITY_LOW
    private var silenceDurationMs = DEFAULT_SILENCE_DURATION_MS
    private var activityHandling = ActivityHandling.START_OF_ACTIVITY_INTERRUPTS

    // ========== Connection Management ==========

    /**
     * Connect to the Gemini Live API.
     *
     * @param tools Optional list of tool (function) declarations
     */
    fun connect(tools: List<JSONObject>? = null) {
        if (isConnected.get()) {
            Log.w(TAG, "Already connected, skipping duplicate connection")
            return
        }

        if (apiKey.isBlank()) {
            Log.e(TAG, "API Key is not configured")
            _errorMessage.value = "API Key is not configured"
            _connectionState.value = ConnectionState.ERROR
            onConnectionStateChanged?.invoke(ConnectionState.ERROR)
            return
        }

        Log.d(TAG, "Connecting to Gemini Live API...")
        _connectionState.value = ConnectionState.CONNECTING
        onConnectionStateChanged?.invoke(ConnectionState.CONNECTING)

        val url = "$WEBSOCKET_BASE_URL?key=$apiKey"
        val request = Request.Builder()
            .url(url)
            .build()

        webSocket = client.newWebSocket(request, createWebSocketListener(tools))
    }

    /**
     * Disconnect from the WebSocket.
     */
    fun disconnect() {
        Log.d(TAG, "Disconnecting WebSocket")
        isConnected.set(false)
        webSocket?.close(1000, "Client disconnect")
        webSocket = null
        _connectionState.value = ConnectionState.DISCONNECTED
        onConnectionStateChanged?.invoke(ConnectionState.DISCONNECTED)
    }

    /**
     * Update VAD (Voice Activity Detection) settings.
     * Must be called before [connect] to take effect.
     */
    fun updateVadSettings(
        startSensitivity: StartOfSpeechSensitivity = this.startSensitivity,
        endSensitivity: EndOfSpeechSensitivity = this.endSensitivity,
        silenceDurationMs: Int = this.silenceDurationMs,
        activityHandling: ActivityHandling = this.activityHandling
    ) {
        this.startSensitivity = startSensitivity
        this.endSensitivity = endSensitivity
        this.silenceDurationMs = silenceDurationMs
        this.activityHandling = activityHandling
    }

    // ========== Data Transmission ==========

    /**
     * Send audio data to Gemini.
     *
     * @param pcmData PCM audio data (16kHz, 16-bit, mono)
     */
    fun sendAudio(pcmData: ByteArray) {
        if (!isConnected.get() || _connectionState.value != ConnectionState.READY) {
            return  // Silently drop — not ready
        }

        val base64Audio = Base64.encodeToString(pcmData, Base64.NO_WRAP)

        val message = JSONObject().apply {
            put("realtime_input", JSONObject().apply {
                put("media_chunks", JSONArray().apply {
                    put(JSONObject().apply {
                        put("mime_type", "audio/pcm;rate=$INPUT_SAMPLE_RATE")
                        put("data", base64Audio)
                    })
                })
            })
        }

        sendMessage(message)
    }

    /**
     * Send a video frame to Gemini.
     *
     * @param jpegData JPEG-encoded image data
     */
    fun sendVideoFrame(jpegData: ByteArray) {
        if (!isConnected.get() || _connectionState.value != ConnectionState.READY) {
            Log.w(TAG, "Not ready, cannot send video frame")
            return
        }

        val base64Image = Base64.encodeToString(jpegData, Base64.NO_WRAP)

        val message = JSONObject().apply {
            put("realtime_input", JSONObject().apply {
                put("media_chunks", JSONArray().apply {
                    put(JSONObject().apply {
                        put("mime_type", "image/jpeg")
                        put("data", base64Image)
                    })
                })
            })
        }

        sendMessage(message)
    }

    /**
     * Send a tool call response back to the model.
     *
     * @param toolCallId The ID of the tool call being responded to
     * @param result     The execution result as a JSON object
     */
    fun sendToolResponse(toolCallId: String, result: JSONObject) {
        if (!isConnected.get()) {
            Log.w(TAG, "Not connected, cannot send tool response")
            return
        }

        val message = JSONObject().apply {
            put("tool_response", JSONObject().apply {
                put("function_responses", JSONArray().apply {
                    put(JSONObject().apply {
                        put("id", toolCallId)
                        put("response", result)
                    })
                })
            })
        }

        sendMessage(message)
    }

    /**
     * Manually signal end of user input turn.
     * Use this when automatic VAD is disabled.
     */
    fun endOfTurn() {
        if (!isConnected.get()) {
            Log.w(TAG, "Not connected, cannot end turn")
            return
        }

        val message = JSONObject().apply {
            put("client_content", JSONObject().apply {
                put("turn_complete", true)
            })
        }

        sendMessage(message)
    }

    // ========== Internal Methods ==========

    /**
     * Send a JSON message over the WebSocket.
     */
    private fun sendMessage(json: JSONObject) {
        val text = json.toString()
        val success = webSocket?.send(text) ?: false

        if (!success) {
            Log.e(TAG, "Failed to send message")
        }
    }

    /**
     * Create the OkHttp WebSocket listener.
     */
    private fun createWebSocketListener(tools: List<JSONObject>?): WebSocketListener {
        return object : WebSocketListener() {

            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d(TAG, "WebSocket connected")
                isConnected.set(true)
                _connectionState.value = ConnectionState.SETTING_UP
                onConnectionStateChanged?.invoke(ConnectionState.SETTING_UP)

                // Send the setup message immediately after connection
                sendSetupMessage(tools)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleServerMessage(text)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "WebSocket closing: $code - $reason")
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "WebSocket closed: $code - $reason")
                isConnected.set(false)
                _connectionState.value = ConnectionState.DISCONNECTED
                onConnectionStateChanged?.invoke(ConnectionState.DISCONNECTED)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "WebSocket connection failed", t)
                isConnected.set(false)
                _errorMessage.value = t.message ?: "Connection failed"
                _connectionState.value = ConnectionState.ERROR
                onConnectionStateChanged?.invoke(ConnectionState.ERROR)
            }
        }
    }

    /**
     * Send the initial setup message (first message after WebSocket connection).
     * Configures model, generation parameters, VAD, transcription, and tools.
     */
    private fun sendSetupMessage(tools: List<JSONObject>?) {
        val setupMessage = JSONObject().apply {
            put("setup", JSONObject().apply {
                // Model configuration
                put("model", "models/$modelId")

                // Generation configuration
                put("generation_config", JSONObject().apply {
                    // Response modality: audio
                    put("response_modalities", JSONArray().apply {
                        put("AUDIO")
                    })

                    // Voice configuration
                    put("speech_config", JSONObject().apply {
                        put("voice_config", JSONObject().apply {
                            put("prebuilt_voice_config", JSONObject().apply {
                                put("voice_name", "Aoede")  // Default voice
                            })
                        })
                    })
                })

                // System instruction
                if (systemPrompt.isNotBlank()) {
                    put("system_instruction", JSONObject().apply {
                        put("parts", JSONArray().apply {
                            put(JSONObject().apply {
                                put("text", systemPrompt)
                            })
                        })
                    })
                }

                // Real-time input configuration (VAD settings)
                put("realtime_input_config", JSONObject().apply {
                    // Automatic voice activity detection
                    put("automatic_activity_detection", JSONObject().apply {
                        put("disabled", false)
                        put("start_of_speech_sensitivity", startSensitivity.name)
                        put("end_of_speech_sensitivity", endSensitivity.name)
                        put("prefix_padding_ms", 300)
                        put("silence_duration_ms", silenceDurationMs)
                    })

                    // Activity handling (interrupt policy)
                    put("activity_handling", activityHandling.name)

                    // Enable input/output audio transcription
                    put("input_audio_transcription", JSONObject())
                    put("output_audio_transcription", JSONObject())
                })

                // Tool declarations
                if (!tools.isNullOrEmpty()) {
                    put("tools", JSONArray().apply {
                        for (tool in tools) {
                            put(tool)
                        }
                    })
                }
            })
        }

        Log.d(TAG, "Sending setup message")
        sendMessage(setupMessage)
    }

    /**
     * Handle an incoming server message by dispatching to the appropriate handler.
     */
    private fun handleServerMessage(text: String) {
        try {
            val json = JSONObject(text)

            // Setup complete acknowledgement
            if (json.has("setupComplete")) {
                Log.d(TAG, "Setup complete, connection ready")
                _connectionState.value = ConnectionState.READY
                onConnectionStateChanged?.invoke(ConnectionState.READY)
                return
            }

            // Server content (audio, transcription, turn signals)
            if (json.has("serverContent")) {
                handleServerContent(json.getJSONObject("serverContent"))
                return
            }

            // Tool call request
            if (json.has("toolCall")) {
                handleToolCallMessage(json.getJSONObject("toolCall"))
                return
            }

            // Tool call cancellation
            if (json.has("toolCallCancellation")) {
                handleToolCallCancellation(json.getJSONObject("toolCallCancellation"))
                return
            }

            Log.d(TAG, "Received unknown message type: ${json.keys().asSequence().toList()}")

        } catch (e: Exception) {
            Log.e(TAG, "Failed to handle server message", e)
        }
    }

    /**
     * Handle server content: audio data, transcriptions, turn events.
     */
    private fun handleServerContent(content: JSONObject) {
        // Check if AI response was interrupted by user speech
        if (content.optBoolean("interrupted", false)) {
            Log.d(TAG, "AI response interrupted")
            onInterrupted?.invoke()
            return
        }

        // Check if the model turn is complete
        if (content.optBoolean("turn_complete", false)) {
            Log.d(TAG, "Turn complete")
            onTurnComplete?.invoke()
        }

        // Process model turn content (audio & text parts)
        val modelTurn = content.optJSONObject("model_turn")
        if (modelTurn != null) {
            val parts = modelTurn.optJSONArray("parts")
            if (parts != null) {
                for (i in 0 until parts.length()) {
                    val part = parts.getJSONObject(i)

                    // Handle audio data
                    val inlineData = part.optJSONObject("inline_data")
                    if (inlineData != null) {
                        val mimeType = inlineData.optString("mime_type", "")
                        val data = inlineData.optString("data", "")

                        if (mimeType.startsWith("audio/") && data.isNotEmpty()) {
                            val audioBytes = Base64.decode(data, Base64.DEFAULT)
                            Log.d(TAG, "Received audio data: ${audioBytes.size} bytes")
                            onAudioReceived?.invoke(audioBytes)
                        }
                    }

                    // Handle text content (inline output transcription)
                    val partText = part.optString("text", "")
                    if (partText.isNotEmpty()) {
                        Log.d(TAG, "Received AI text: $partText")
                    }
                }
            }
        }

        // Handle input (user speech) transcription
        val inputTranscription = content.optJSONObject("input_transcription")
        if (inputTranscription != null) {
            val text = inputTranscription.optString("text", "")
            if (text.isNotEmpty()) {
                Log.d(TAG, "User speech transcription: $text")
                onInputTranscription?.invoke(text)
            }
        }

        // Handle output (AI speech) transcription
        val outputTranscription = content.optJSONObject("output_transcription")
        if (outputTranscription != null) {
            val text = outputTranscription.optString("text", "")
            if (text.isNotEmpty()) {
                Log.d(TAG, "AI speech transcription: $text")
                onOutputTranscription?.invoke(text)
            }
        }
    }

    /**
     * Handle a tool call message from the model.
     */
    private fun handleToolCallMessage(toolCallJson: JSONObject) {
        val functionCalls = toolCallJson.optJSONArray("function_calls") ?: return

        val toolCalls = mutableListOf<ToolCall>()
        for (i in 0 until functionCalls.length()) {
            val fc = functionCalls.getJSONObject(i)
            val id = fc.optString("id", "")
            val name = fc.optString("name", "")
            val args = fc.optJSONObject("args") ?: JSONObject()

            if (id.isNotEmpty() && name.isNotEmpty()) {
                toolCalls.add(ToolCall(id, name, args))
            }
        }

        if (toolCalls.isNotEmpty()) {
            Log.d(TAG, "Received ${toolCalls.size} tool call(s)")
            onToolCall?.invoke(toolCalls)
        }
    }

    /**
     * Handle tool call cancellation from the model.
     */
    private fun handleToolCallCancellation(cancellation: JSONObject) {
        val ids = cancellation.optJSONArray("ids")
        if (ids != null) {
            val cancelledIds = mutableListOf<String>()
            for (i in 0 until ids.length()) {
                cancelledIds.add(ids.getString(i))
            }
            Log.d(TAG, "Tool call(s) cancelled: $cancelledIds")
            // TODO: Notify ToolCallRouter to cancel in-flight calls (P2)
        }
    }

    /**
     * Release all resources (disconnect + cancel coroutine scope).
     */
    fun release() {
        disconnect()
        scope.cancel()
    }
}
