package com.example.rokidphone.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.rokidcommon.Constants
import com.example.rokidcommon.protocol.Message
import com.example.rokidcommon.protocol.MessageType
import com.example.rokidphone.BuildConfig
import com.example.rokidphone.MainActivity
import com.example.rokidphone.R
import com.example.rokidphone.data.AiProvider
import com.example.rokidphone.data.ApiSettings
import com.example.rokidphone.data.AvailableModels
import com.example.rokidphone.data.SettingsRepository
import com.example.rokidphone.service.ai.AiServiceFactory
import com.example.rokidphone.service.ai.AiServiceProvider
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Phone AI Service
 * Receives voice commands from glasses, processes AI computation, returns results
 * 
 * Architecture:
 * 1. Glasses record -> Send audio via Bluetooth to phone
 * 2. Phone performs speech recognition (Gemini API)
 * 3. Phone performs AI conversation (Gemini API)
 * 4. Phone sends results back to glasses via Bluetooth for display
 */
class PhoneAIService : Service() {
    
    companion object {
        private const val TAG = "PhoneAIService"
    }
    
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    // AI service (supports multiple providers)
    private var aiService: AiServiceProvider? = null
    
    // Speech recognition service (may differ from chat service)
    private var speechService: AiServiceProvider? = null
    
    // TTS service
    private var ttsService: TextToSpeechService? = null
    
    // Bluetooth manager
    private var bluetoothManager: BluetoothSppManager? = null
    
    private val _messageFlow = MutableSharedFlow<Message>()
    val messageFlow = _messageFlow.asSharedFlow()
    
    override fun onCreate() {
        super.onCreate()
        initializeServices()
        startForeground(Constants.NOTIFICATION_ID, createNotification())
        
        // Notify UI service has started
        serviceScope.launch {
            ServiceBridge.updateServiceState(true)
        }
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }
    
    override fun onBind(intent: Intent?): IBinder? = null
    
    override fun onDestroy() {
        super.onDestroy()
        
        // Notify UI service has stopped
        kotlinx.coroutines.runBlocking {
            ServiceBridge.updateServiceState(false)
        }
        
        serviceScope.cancel()
        bluetoothManager?.disconnect()
        ttsService?.shutdown()
    }
    
    private fun initializeServices() {
        Log.d(TAG, "Initializing services...")
        
        try {
            // Get settings
            val settingsRepository = SettingsRepository.getInstance(this)
            val settings = settingsRepository.getSettings()
            
            // Validate model and provider compatibility
            val validatedSettings = validateAndCorrectSettings(settings)
            
            // Use factory to create AI service
            aiService = createAiService(validatedSettings)
            Log.d(TAG, "AI service created: ${aiService != null}")
            
            // Set speech recognition service (prefer providers supporting STT)
            speechService = createSpeechService(validatedSettings)
            Log.d(TAG, "Speech service created: ${speechService != null}")
            
            Log.d(TAG, "Using AI provider: ${validatedSettings.aiProvider}, model: ${validatedSettings.aiModelId}")
            
            // Monitor settings changes
            serviceScope.launch {
                settingsRepository.settingsFlow.collect { newSettings ->
                    Log.d(TAG, "Settings changed, updating services...")
                    val validatedNewSettings = validateAndCorrectSettings(newSettings)
                    aiService = createAiService(validatedNewSettings)
                    speechService = createSpeechService(validatedNewSettings)
                    Log.d(TAG, "Services updated: ${validatedNewSettings.aiProvider}, ${validatedNewSettings.aiModelId}")
                }
            }
            
            // Initialize TTS
            ttsService = TextToSpeechService(this)
            Log.d(TAG, "TTS service initialized")
            
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing AI services, but continuing with Bluetooth", e)
        }
        
        // Initialize Bluetooth manager (independent of AI services)
        try {
            bluetoothManager = BluetoothSppManager(this, serviceScope)
            
            // Start listening for Bluetooth connections
            bluetoothManager?.startListening()
            Log.d(TAG, "Bluetooth manager started listening")
            
            // Monitor Bluetooth connection state
            serviceScope.launch {
                bluetoothManager?.connectionState?.collect { state ->
                    Log.d(TAG, "Bluetooth state: $state")
                    ServiceBridge.updateBluetoothState(state)
                    
                    // Update notification
                    updateNotification(state)
                }
            }
            
            // Listen for messages from glasses
            serviceScope.launch {
                bluetoothManager?.messageFlow?.collect { message ->
                    handleGlassesMessage(message)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing Bluetooth manager", e)
        }
    }
    
    private suspend fun handleGlassesMessage(message: Message) {
        Log.d(TAG, "Received message from glasses: ${message.type}")
        
        when (message.type) {
            MessageType.VOICE_END -> {
                // Voice input ended, audio data is in binaryData
                message.binaryData?.let { audioData ->
                    Log.d(TAG, "Processing voice data: ${audioData.size} bytes")
                    processVoiceData(audioData)
                }
            }
            MessageType.VOICE_START -> {
                Log.d(TAG, "Voice recording started on glasses")
                // Notify phone UI
                ServiceBridge.emitConversation(Message(
                    type = MessageType.AI_PROCESSING,
                    payload = getString(R.string.glasses_recording)
                ))
            }
            MessageType.HEARTBEAT -> {
                bluetoothManager?.sendMessage(Message(type = MessageType.HEARTBEAT_ACK))
            }
            else -> { 
                Log.d(TAG, "Unhandled message type: ${message.type}")
            }
        }
    }
    
    /**
     * Process voice data received from glasses
     */
    private suspend fun processVoiceData(audioData: ByteArray) {
        try {
            // Check if speech service is available
            if (speechService == null) {
                Log.e(TAG, "Speech service not available - no API key configured")
                val errorMsg = getString(R.string.service_not_ready)
                bluetoothManager?.sendMessage(Message.aiError(errorMsg))
                ServiceBridge.emitConversation(Message(
                    type = MessageType.AI_ERROR,
                    payload = errorMsg
                ))
                // Notify UI to show settings prompt
                ServiceBridge.notifyApiKeyMissing()
                return
            }
            
            // 1. Notify glasses: recognizing
            bluetoothManager?.sendMessage(Message.aiProcessing(getString(R.string.recognizing_speech)))
            
            // 2. Speech recognition (using dedicated STT service)
            Log.d(TAG, "Starting speech recognition...")
            val transcriptResult = speechService?.transcribe(audioData)
            
            val transcript = when (transcriptResult) {
                is SpeechResult.Success -> {
                    Log.d(TAG, "Transcript: ${transcriptResult.text}")
                    transcriptResult.text
                }
                is SpeechResult.Error -> {
                    Log.e(TAG, "Transcription error: ${transcriptResult.message}")
                    bluetoothManager?.sendMessage(Message.aiError(transcriptResult.message))
                    // Check if error is API key related
                    if (transcriptResult.message.contains("API", ignoreCase = true) ||
                        transcriptResult.message.contains("key", ignoreCase = true) ||
                        transcriptResult.message.contains("401") ||
                        transcriptResult.message.contains("403")) {
                        ServiceBridge.notifyApiKeyMissing()
                    }
                    return
                }
                null -> {
                    val errorMsg = getString(R.string.service_not_ready)
                    bluetoothManager?.sendMessage(Message.aiError(errorMsg))
                    ServiceBridge.notifyApiKeyMissing()
                    return
                }
            }
            
            // 3. Send user voice text to glasses and phone UI
            bluetoothManager?.sendMessage(Message(
                type = MessageType.USER_TRANSCRIPT,
                payload = transcript
            ))
            
            ServiceBridge.emitConversation(Message(
                type = MessageType.USER_TRANSCRIPT,
                payload = transcript
            ))
            
            // 4. Notify thinking
            bluetoothManager?.sendMessage(Message.aiProcessing(getString(R.string.thinking)))
            
            // 5. AI conversation (using main AI service)
            Log.d(TAG, "Getting AI response...")
            val rawAiResponse = aiService?.chat(transcript) ?: "Sorry, an error occurred while processing."
            
            // Clean markdown formatting for better display on glasses
            val aiResponse = cleanMarkdown(rawAiResponse)
            
            Log.d(TAG, "AI response: $aiResponse")
            
            // 6. Send AI response to glasses and phone UI
            bluetoothManager?.sendMessage(Message.aiResponseText(aiResponse))
            
            ServiceBridge.emitConversation(Message(
                type = MessageType.AI_RESPONSE_TEXT,
                payload = aiResponse
            ))
            
            // 7. TTS voice playback (optional)
            ttsService?.speak(aiResponse) { }
            
        } catch (e: Exception) {
            Log.e(TAG, "Error processing voice data", e)
            bluetoothManager?.sendMessage(Message.aiError(getString(R.string.processing_failed, e.message ?: "")))
        }
    }
    
    /**
     * Clean markdown formatting from AI response for better display
     */
    private fun cleanMarkdown(text: String): String {
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
    
    private fun updateNotification(state: BluetoothConnectionState) {
        val statusText = when (state) {
            BluetoothConnectionState.DISCONNECTED -> getString(R.string.disconnected)
            BluetoothConnectionState.LISTENING -> getString(R.string.waiting_glasses)
            BluetoothConnectionState.CONNECTING -> getString(R.string.connecting)
            BluetoothConnectionState.CONNECTED -> getString(R.string.connected_glasses)
        }
        
        val notification = NotificationCompat.Builder(this, Constants.NOTIFICATION_CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(statusText)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(createPendingIntent())
            .setOngoing(true)
            .build()
        
        startForeground(Constants.NOTIFICATION_ID, notification)
    }
    
    private fun createPendingIntent(): PendingIntent {
        return PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
    }
    
    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, Constants.NOTIFICATION_CHANNEL_ID)
            .setContentTitle("Rokid AI Assistant")
            .setContentText("Service running, waiting for glasses connection...")
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(createPendingIntent())
            .setOngoing(true)
            .build()
    }
    
    /**
     * Create AI service
     */
    private fun createAiService(settings: ApiSettings): AiServiceProvider {
        // If no API key, use default Gemini with BuildConfig key
        val effectiveSettings = if (settings.getCurrentApiKey().isBlank()) {
            Log.w(TAG, "No API key for ${settings.aiProvider}, falling back to built-in Gemini")
            
            // Check if BuildConfig has a valid Gemini API key
            if (BuildConfig.GEMINI_API_KEY.isNotBlank()) {
                settings.copy(
                    aiProvider = AiProvider.GEMINI,
                    aiModelId = "gemini-2.5-flash",
                    geminiApiKey = BuildConfig.GEMINI_API_KEY
                )
            } else {
                // No fallback available, create with empty key (will fail gracefully)
                Log.e(TAG, "No fallback API key available!")
                settings
            }
        } else {
            settings
        }
        
        return AiServiceFactory.createService(effectiveSettings)
    }
    
    /**
     * Create speech recognition service
     * Prefer providers supporting STT
     */
    private fun createSpeechService(settings: ApiSettings): AiServiceProvider? {
        // Check if current provider supports speech recognition
        if (settings.aiProvider.supportsSpeech && settings.getCurrentApiKey().isNotBlank()) {
            Log.d(TAG, "Using current provider ${settings.aiProvider} for STT")
            return AiServiceFactory.createService(settings)
        }
        
        // Try other configured providers that support STT
        val sttProviders = listOf(AiProvider.GEMINI, AiProvider.OPENAI, AiProvider.GROQ, AiProvider.XAI)
        for (provider in sttProviders) {
            val apiKey = settings.getApiKeyForProvider(provider)
            if (apiKey.isNotBlank()) {
                val defaultModel = AvailableModels.getModelsForProvider(provider).firstOrNull()
                if (defaultModel != null) {
                    Log.d(TAG, "Using ${provider.name} for speech recognition")
                    return AiServiceFactory.createService(settings.copy(
                        aiProvider = provider,
                        aiModelId = defaultModel.id
                    ))
                }
            }
        }
        
        // Try fallback to BuildConfig Gemini key
        if (BuildConfig.GEMINI_API_KEY.isNotBlank()) {
            Log.d(TAG, "No STT provider configured, using fallback Gemini")
            return AiServiceFactory.createService(settings.copy(
                aiProvider = AiProvider.GEMINI,
                aiModelId = "gemini-2.5-flash",
                geminiApiKey = BuildConfig.GEMINI_API_KEY
            ))
        }
        
        // No speech service available
        Log.e(TAG, "No speech recognition service available!")
        return null
    }
    
    /**
     * Check if speech service is available
     */
    fun isSpeechServiceAvailable(): Boolean = speechService != null
    
    /**
     * Validate and correct settings
     * Ensure selected model is compatible with AI provider
     */
    private fun validateAndCorrectSettings(settings: ApiSettings): ApiSettings {
        val modelInfo = AvailableModels.findModel(settings.aiModelId)
        
        // If model info not found, use provider's default model
        if (modelInfo == null) {
            Log.w(TAG, "Unknown model: ${settings.aiModelId}, using default model for ${settings.aiProvider}")
            val defaultModel = AvailableModels.getModelsForProvider(settings.aiProvider).firstOrNull()
            return if (defaultModel != null) {
                settings.copy(aiModelId = defaultModel.id)
            } else {
                // Fall back to Gemini
                settings.copy(
                    aiProvider = AiProvider.GEMINI,
                    aiModelId = "gemini-2.5-flash"
                )
            }
        }
        
        // If model doesn't match provider, correct the provider
        if (modelInfo.provider != settings.aiProvider) {
            Log.w(TAG, "Model ${settings.aiModelId} belongs to ${modelInfo.provider}, correcting provider")
            return settings.copy(aiProvider = modelInfo.provider)
        }
        
        // Check if provider has API key
        if (settings.getCurrentApiKey().isBlank()) {
            Log.w(TAG, "No API key for ${settings.aiProvider}, checking for fallback")
            // Try to use provider that has API key
            for (provider in AiProvider.entries) {
                val apiKey = settings.getApiKeyForProvider(provider)
                if (apiKey.isNotBlank()) {
                    val defaultModel = AvailableModels.getModelsForProvider(provider).firstOrNull()
                    if (defaultModel != null) {
                        Log.d(TAG, "Falling back to ${provider.name}")
                        return settings.copy(
                            aiProvider = provider,
                            aiModelId = defaultModel.id
                        )
                    }
                }
            }
        }
        
        return settings
    }
}

/**
 * STT Service (Simplified version)
 */
class SpeechToTextService(private val apiKey: String) {
    
    suspend fun transcribe(audioData: ByteArray): String? {
        return withContext(Dispatchers.IO) {
            try {
                // TODO: Implement OpenAI Whisper API call
                // Returning mock result here
                "This is a test voice input"
            } catch (e: Exception) {
                null
            }
        }
    }
}

/**
 * TTS Service (Simplified version)
 */
class TextToSpeechService(private val context: android.content.Context) {
    
    private var tts: android.speech.tts.TextToSpeech? = null
    
    init {
        tts = android.speech.tts.TextToSpeech(context) { status ->
            if (status == android.speech.tts.TextToSpeech.SUCCESS) {
                tts?.language = java.util.Locale.TRADITIONAL_CHINESE
            }
        }
    }
    
    fun speak(text: String, onAudioChunk: (ByteArray) -> Unit) {
        // Simplified version: using system TTS
        tts?.speak(text, android.speech.tts.TextToSpeech.QUEUE_FLUSH, null, null)
    }
    
    fun shutdown() {
        tts?.shutdown()
    }
}

/**
 * Bluetooth Manager (Simplified version)
 */
class BluetoothManager(private val context: android.content.Context) {
    
    private val _messageFlow = MutableSharedFlow<Message>()
    val messageFlow = _messageFlow.asSharedFlow()
    
    suspend fun sendMessage(message: Message) {
        // TODO: Implement Bluetooth send
    }
    
    fun disconnect() {
        // TODO: Implement Bluetooth disconnect
    }
}
