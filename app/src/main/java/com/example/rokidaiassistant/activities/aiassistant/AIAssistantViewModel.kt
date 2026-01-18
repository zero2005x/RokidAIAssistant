package com.example.rokidaiassistant.activities.aiassistant

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.rokidaiassistant.data.Constants
import com.example.rokidaiassistant.services.GeminiService
import com.example.rokidaiassistant.services.SpeechToTextService
import com.example.rokidaiassistant.services.TextToSpeechService
import com.example.rokidaiassistant.sdk.CxrApi
import com.example.rokidaiassistant.sdk.AiEventListener
import com.example.rokidaiassistant.sdk.AudioStreamListener
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream

/**
 * AI Assistant UI State
 */
data class AIAssistantUiState(
    val isListening: Boolean = false,
    val isProcessing: Boolean = false,
    val currentTranscript: String = "",
    val lastResponse: String = "",
    val conversationHistory: List<ConversationItem> = emptyList(),
    val error: String? = null
)

/**
 * Conversation Item
 */
data class ConversationItem(
    val isUser: Boolean,
    val text: String,
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * AI Assistant ViewModel
 * 
 * Core responsibilities:
 * 1. Listen for glasses AI events (key press/release/exit)
 * 2. Receive microphone audio stream from glasses
 * 3. Call STT service for speech recognition
 * 4. Call Gemini API to get response
 * 5. Call TTS service to play voice response
 * 6. Send results back to glasses for display
 */
class AIAssistantViewModel(application: Application) : AndroidViewModel(application) {
    
    companion object {
        private const val TAG = "AIAssistantVM"
    }
    
    private val _uiState = MutableStateFlow(AIAssistantUiState())
    val uiState: StateFlow<AIAssistantUiState> = _uiState.asStateFlow()
    
    // Service instances
    private val geminiService = GeminiService()
    private val sttService = SpeechToTextService()
    private val ttsService = TextToSpeechService(application.applicationContext)
    
    private val audioBuffer = ByteArrayOutputStream()
    private var heartbeatJob: Job? = null
    
    // ========================================
    // AI Event Listener
    // ========================================
    private val aiEventListener = object : AiEventListener {
        override fun onAiKeyDown() {
            Log.d(TAG, "AI Key Down - User started speaking")
            startListening()
        }
        
        override fun onAiKeyUp() {
            Log.d(TAG, "AI Key Up - User stopped speaking")
            stopListeningAndProcess()
        }
        
        override fun onAiExit() {
            Log.d(TAG, "AI Exit - Exiting AI scene")
            cleanup()
        }
    }
    
    // ========================================
    // Audio Stream Listener
    // ========================================
    private val audioStreamListener = object : AudioStreamListener {
        override fun onAudioData(data: ByteArray?, length: Int) {
            if (data != null && _uiState.value.isListening) {
                audioBuffer.write(data, 0, length)
                Log.v(TAG, "Received audio data: $length bytes, total: ${audioBuffer.size()} bytes")
            }
        }
    }
    
    /**
     * Initialize AI Assistant
     */
    fun initialize() {
        Log.d(TAG, "Initializing AI Assistant")
        
        try {
            // Initialize TTS service
            ttsService.initSystemTts()
            Log.d(TAG, "TTS service initialized")
            
            // Register AI event listener
            CxrApi.getInstance().setAiEventListener(aiEventListener)
            Log.d(TAG, "AI event listener registered")
            
            // Register audio stream listener
            CxrApi.getInstance().setAudioStreamListener(audioStreamListener)
            Log.d(TAG, "Audio stream listener registered")
            
            // Start heartbeat
            startHeartbeat()
            
        } catch (e: Exception) {
            Log.e(TAG, "Initialization failed", e)
            _uiState.value = _uiState.value.copy(
                error = "Initialization failed: ${e.message}"
            )
        }
    }
    
    /**
     * Start listening
     */
    private fun startListening() {
        audioBuffer.reset()
        _uiState.value = _uiState.value.copy(
            isListening = true,
            isProcessing = false,
            currentTranscript = "Listening...",
            error = null
        )
    }
    
    /**
     * Stop listening and process
     */
    private fun stopListeningAndProcess() {
        _uiState.value = _uiState.value.copy(
            isListening = false,
            isProcessing = true,
            currentTranscript = "Processing..."
        )
        
        viewModelScope.launch {
            processAudioAndRespond()
        }
    }
    
    /**
     * Process audio and get AI response
     */
    private suspend fun processAudioAndRespond() {
        try {
            val audioData = audioBuffer.toByteArray()
            Log.d(TAG, "Processing audio data: ${audioData.size} bytes")
            
            // Use Whisper STT for speech recognition
            _uiState.value = _uiState.value.copy(currentTranscript = "Speech recognition...")
            
            val sttResult = sttService.transcribe(audioData)
            val transcript = sttResult.getOrElse { error ->
                Log.e(TAG, "STT recognition failed", error)
                "(Speech recognition failed)"
            }
            Log.d(TAG, "Recognition result: $transcript")
            
            // Update UI state
            _uiState.value = _uiState.value.copy(currentTranscript = transcript)
            
            // Add user message to conversation history
            addToConversation(isUser = true, text = transcript)
            
            // Send ASR content to glasses display
            try {
                CxrApi.getInstance().sendAsrContent(transcript)
                CxrApi.getInstance().notifyAsrEnd()
                Log.d(TAG, "ASR content sent to glasses")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send ASR content", e)
            }
            
            // Call Gemini API
            _uiState.value = _uiState.value.copy(currentTranscript = "AI thinking...")
            Log.d(TAG, "Calling Gemini API...")
            val result = geminiService.sendMessage(transcript)
            
            result.onSuccess { response ->
                Log.d(TAG, "Gemini response: $response")
                
                // Add AI response to conversation history
                addToConversation(isUser = false, text = response)
                
                // Send TTS content to glasses display
                try {
                    CxrApi.getInstance().sendTtsContent(response)
                    Log.d(TAG, "TTS content sent to glasses")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to send TTS content", e)
                }
                
                // Update UI state
                _uiState.value = _uiState.value.copy(
                    isProcessing = false,
                    lastResponse = response,
                    currentTranscript = ""
                )
                
                // Use TTS to play voice response
                ttsService.speak(response) {
                    // Notify glasses after playback completes
                    try {
                        CxrApi.getInstance().notifyTtsAudioFinished()
                        Log.d(TAG, "TTS playback completed, glasses notified")
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to notify TTS completion", e)
                    }
                }
            }
            
            result.onFailure { error ->
                Log.e(TAG, "Gemini API error", error)
                _uiState.value = _uiState.value.copy(
                    isProcessing = false,
                    error = "AI response failed: ${error.message}"
                )
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Processing failed", e)
            _uiState.value = _uiState.value.copy(
                isProcessing = false,
                error = "Processing failed: ${e.message}"
            )
        }
    }
    
    /**
     * For testing: Send text message directly
     */
    fun sendTestMessage(message: String) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                isProcessing = true,
                error = null
            )
            
            // Add user message
            addToConversation(isUser = true, text = message)
            
            // Call Gemini
            val result = geminiService.sendMessage(message)
            
            result.onSuccess { response ->
                addToConversation(isUser = false, text = response)
                _uiState.value = _uiState.value.copy(
                    isProcessing = false,
                    lastResponse = response
                )
            }
            
            result.onFailure { error ->
                _uiState.value = _uiState.value.copy(
                    isProcessing = false,
                    error = "AI response failed: ${error.message}"
                )
            }
        }
    }
    
    /**
     * Add message to conversation history
     */
    private fun addToConversation(isUser: Boolean, text: String) {
        val newHistory = _uiState.value.conversationHistory + ConversationItem(isUser, text)
        _uiState.value = _uiState.value.copy(conversationHistory = newHistory)
    }
    
    /**
     * Start heartbeat
     */
    private fun startHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = viewModelScope.launch {
            while (true) {
                try {
                    CxrApi.getInstance().sendAi_Heartbeat()
                    Log.v(TAG, "Heartbeat sent")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to send heartbeat", e)
                }
                delay(Constants.HEARTBEAT_INTERVAL_MS)
            }
        }
    }
    
    /**
     * Clean up resources
     */
    private fun cleanup() {
        heartbeatJob?.cancel()
        ttsService.stop()
        _uiState.value = AIAssistantUiState()
    }
    
    /**
     * Stop TTS playback
     */
    fun stopSpeaking() {
        ttsService.stop()
    }
    
    override fun onCleared() {
        super.onCleared()
        Log.d(TAG, "ViewModel cleanup")
        
        // Clear listeners
        try {
            CxrApi.getInstance().setAiEventListener(null)
            CxrApi.getInstance().setAudioStreamListener(null)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to clear listeners", e)
        }
        
        // Release TTS resources
        ttsService.release()
        
        heartbeatJob?.cancel()
    }
}
