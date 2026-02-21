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
import com.example.rokidcommon.protocol.photo.PhotoTransferState
import com.example.rokidphone.BuildConfig
import com.example.rokidphone.MainActivity
import com.example.rokidphone.R
import com.example.rokidphone.data.AiProvider
import com.example.rokidphone.data.ApiSettings
import com.example.rokidphone.data.AvailableModels
import com.example.rokidphone.data.SettingsRepository
import com.example.rokidphone.data.db.ConversationRepository
import com.example.rokidphone.data.db.RecordingRepository
import com.example.rokidphone.service.ai.AiServiceFactory
import com.example.rokidphone.service.ai.AiServiceProvider
import com.example.rokidphone.service.ai.GeminiLiveSession
import com.example.rokidphone.service.cxr.CxrMobileManager
import com.example.rokidphone.service.stt.SttProvider
import com.example.rokidphone.service.stt.SttService
import com.example.rokidphone.service.stt.SttServiceFactory
import com.example.rokidphone.data.toSttCredentials
import com.example.rokidphone.service.ServiceBridge.notifyApiKeyMissing
import com.example.rokidphone.service.photo.PhotoData
import com.example.rokidphone.service.photo.PhotoRepository
import com.example.rokidphone.service.photo.ReceivedPhoto
import com.rokid.cxr.client.utils.ValueUtil
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
    
    // Dedicated STT service (for specialized STT providers like Deepgram, Azure, etc.)
    private var sttService: SttService? = null
    
    // TTS service
    private var ttsService: TextToSpeechService? = null
    
    // Bluetooth manager (legacy SPP connection)
    private var bluetoothManager: BluetoothSppManager? = null
    
    // CXR-M SDK Manager (for Rokid glasses connection and photo capture)
    private var cxrManager: CxrMobileManager? = null
    
    // Gemini Live session (real-time bidirectional voice)
    private var liveSession: GeminiLiveSession? = null
    
    // Photo repository for managing received photos
    private var photoRepository: PhotoRepository? = null
    
    // Conversation repository for persisting voice conversations
    private var conversationRepository: ConversationRepository? = null
    
    // Recording repository for saving glasses recordings
    private var recordingRepository: RecordingRepository? = null
    
    // Current voice conversation ID (for grouping voice interactions)
    private var currentVoiceConversationId: String? = null
    
    // Track recording IDs currently being processed to prevent duplicate transcription
    private val processingRecordingIds = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    
    private val _messageFlow = MutableSharedFlow<Message>()
    val messageFlow = _messageFlow.asSharedFlow()
    
    override fun onCreate() {
        super.onCreate()
        initializeServices()
        startForeground(Constants.NOTIFICATION_ID, createNotification())
        
        // Notify UI service has started (immediate state update)
        ServiceBridge.updateServiceState(true)
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }
    
    override fun onBind(intent: Intent?): IBinder? = null
    
    override fun onDestroy() {
        super.onDestroy()
        
        // Notify UI service has stopped (immediate state update)
        ServiceBridge.updateServiceState(false)
        
        serviceScope.cancel()
        liveSession?.release()
        liveSession = null
        bluetoothManager?.disconnect()
        cxrManager?.release()
        ttsService?.shutdown()
        sttService?.release()
    }
    
    private fun initializeServices() {
        Log.d(TAG, "Initializing services...")
        
        try {
            // Get settings
            val settingsRepository = SettingsRepository.getInstance(this)
            val settings = settingsRepository.getSettings()
            
            // Initialize conversation repository for persisting voice conversations
            conversationRepository = ConversationRepository.getInstance(this)
            
            // Create or get the current voice conversation session
            serviceScope.launch {
                ensureVoiceConversationSession(settings)
            }
            
            // Validate model and provider compatibility
            val validatedSettings = validateAndCorrectSettings(settings)
            
            // Use factory to create AI service
            aiService = createAiService(validatedSettings)
            Log.d(TAG, "AI service created: ${aiService != null}")
            
            // Set speech recognition service (prefer providers supporting STT)
            speechService = createSpeechService(validatedSettings)
            Log.d(TAG, "Speech service created: ${speechService != null}")
            
            // Create dedicated STT service if a specialized provider is selected
            sttService = createSttService(validatedSettings)
            Log.d(TAG, "Dedicated STT service created: ${sttService != null}, provider: ${validatedSettings.sttProvider}")
            
            Log.d(TAG, "Using AI provider: ${validatedSettings.aiProvider}, model: ${validatedSettings.aiModelId}")
            
            // Monitor settings changes
            serviceScope.launch {
                settingsRepository.settingsFlow.collect { newSettings ->
                    Log.d(TAG, "Settings changed, updating services...")
                    val validatedNewSettings = validateAndCorrectSettings(newSettings)
                    aiService = createAiService(validatedNewSettings)
                    speechService = createSpeechService(validatedNewSettings)
                    sttService = createSttService(validatedNewSettings)
                    
                    // Handle Live mode transitions
                    handleLiveModeTransition(validatedNewSettings)
                    
                    Log.d(TAG, "Services updated: ${validatedNewSettings.aiProvider}, STT: ${validatedNewSettings.sttProvider}")
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
            
            // Start listening for Bluetooth connections (only if permission granted)
            if (bluetoothManager?.hasBluetoothPermission() == true) {
                bluetoothManager?.startListening()
                Log.d(TAG, "Bluetooth manager started listening")
            } else {
                Log.w(TAG, "Bluetooth permission not granted, waiting for permission")
            }
            
            // Monitor Bluetooth connection state
            serviceScope.launch {
                try {
                    bluetoothManager?.connectionState?.collect { state ->
                        Log.d(TAG, "Bluetooth state: $state")
                        ServiceBridge.updateBluetoothState(state)
                        
                        // Update notification
                        updateNotification(state)
                        
                        // Initialize CXR Bluetooth when SPP connected
                        if (state == BluetoothConnectionState.CONNECTED) {
                            bluetoothManager?.connectedDevice?.let { device ->
                                Log.d(TAG, "Initializing CXR Bluetooth with device: ${device.name}")
                                cxrManager?.initBluetooth(device)
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error in Bluetooth state collector", e)
                }
            }
            
            // Monitor connected device name
            serviceScope.launch {
                try {
                    bluetoothManager?.connectedDeviceName?.collect { name ->
                        Log.d(TAG, "Connected device name: $name")
                        ServiceBridge.updateConnectedDeviceName(name)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error in device name collector", e)
                }
            }
            
            // Listen for messages from glasses
            serviceScope.launch {
                try {
                    bluetoothManager?.messageFlow?.collect { message ->
                        handleGlassesMessage(message)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error in message flow collector", e)
                }
            }
            
            // Initialize photo repository
            photoRepository = PhotoRepository(this, serviceScope)
            
            // Initialize recording repository for saving glasses recordings
            recordingRepository = RecordingRepository.getInstance(this, serviceScope)
            
            // Listen for received photos from glasses
            serviceScope.launch {
                try {
                    bluetoothManager?.receivedPhoto?.collect { receivedPhoto ->
                        handleReceivedPhoto(receivedPhoto)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error in received photo collector", e)
                }
            }
            
            // Monitor photo transfer state
            serviceScope.launch {
                try {
                    bluetoothManager?.photoTransferState?.collect { state ->
                        handlePhotoTransferState(state)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error in photo transfer state collector", e)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing Bluetooth manager", e)
        }
        
        // Listen for send-to-glasses requests from text chat
        serviceScope.launch {
            ServiceBridge.sendToGlassesFlow.collect { message ->
                Log.d(TAG, "Forwarding message to glasses: type=${message.type}")
                bluetoothManager?.sendMessage(message)
            }
        }

        // Listen for capture photo requests from UI
        serviceScope.launch {
            ServiceBridge.capturePhotoFlow.collect {
                Log.d(TAG, "Capture photo request from UI")
                requestGlassesToCapturePhoto()
            }
        }
        
        // Listen for connection control requests from UI
        serviceScope.launch {
            ServiceBridge.startListeningFlow.collect {
                Log.d(TAG, "Start listening request from UI")
                bluetoothManager?.let { manager ->
                    // Restart Bluetooth listening
                    manager.stopListening()
                    kotlinx.coroutines.delay(300) // Wait for socket cleanup
                    if (manager.hasBluetoothPermission()) {
                        manager.startListening()
                        Log.d(TAG, "Bluetooth listening restarted")
                    }
                }
            }
        }
        
        serviceScope.launch {
            ServiceBridge.disconnectFlow.collect {
                Log.d(TAG, "Disconnect request from UI")
                bluetoothManager?.disconnect(restartListening = true)
            }
        }
        
        // Listen for transcription requests from UI (phone recordings)
        serviceScope.launch {
            try {
                ServiceBridge.transcribeRecordingFlow.collect { request ->
                    Log.d(TAG, "Transcription request received: ${request.recordingId}")
                    processPhoneRecording(request.recordingId, request.filePath)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error in transcription request collector", e)
            }
        }
        
        // Listen for glasses recording start requests from UI
        serviceScope.launch {
            try {
                ServiceBridge.startGlassesRecordingFlow.collect { recordingId ->
                    Log.d(TAG, "Glasses recording start command: $recordingId")
                    bluetoothManager?.sendMessage(
                        Message(type = MessageType.REMOTE_RECORD_START, payload = recordingId)
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error in glasses recording start collector", e)
            }
        }
        
        // Listen for glasses recording stop requests from UI
        serviceScope.launch {
            try {
                ServiceBridge.stopGlassesRecordingFlow.collect {
                    Log.d(TAG, "Glasses recording stop command")
                    bluetoothManager?.sendMessage(
                        Message(type = MessageType.REMOTE_RECORD_STOP)
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error in glasses recording stop collector", e)
            }
        }
        
        // Initialize CXR-M SDK (for Rokid glasses photo capture)
        initializeCxrSdk()
    }
    
    /**
     * Request glasses to capture and send photo via SPP
     */
    private suspend fun requestGlassesToCapturePhoto() {
        if (bluetoothManager?.connectionState?.value != BluetoothConnectionState.CONNECTED) {
            Log.w(TAG, "Cannot capture photo: not connected to glasses")
            return
        }
        
        // Check API key before sending capture command
        val settingsRepository = SettingsRepository.getInstance(this)
        val apiKey = settingsRepository.getSettings().geminiApiKey
        if (apiKey.isBlank()) {
            Log.e(TAG, "API key is not configured, aborting photo capture")
            // Notify UI to show API key warning dialog
            notifyApiKeyMissing()
            // Also send error message to glasses
            bluetoothManager?.sendMessage(Message.aiError("API key not configured. Please set up an API key in Settings."))
            return
        }
        
        Log.d(TAG, "Sending CAPTURE_PHOTO command to glasses")
        bluetoothManager?.sendMessage(Message(type = MessageType.CAPTURE_PHOTO))
    }
    
    /**
     * Initialize CXR-M SDK for Rokid glasses connection
     * This enables:
     * - AI key event listening (long press on glasses)
     * - Remote photo capture from glasses
     */
    private fun initializeCxrSdk() {
        if (!CxrMobileManager.isSdkAvailable()) {
            Log.w(TAG, "CXR-M SDK not available")
            return
        }
        
        try {
            cxrManager = CxrMobileManager(this)
            
            // Set AI event listener for glasses key press
            cxrManager?.setAiEventListener(
                onKeyDown = {
                    Log.d(TAG, "CXR: AI key pressed on glasses")
                    // Trigger photo capture when AI key is pressed
                    serviceScope.launch {
                        capturePhotoFromGlasses()
                    }
                },
                onKeyUp = {
                    Log.d(TAG, "CXR: AI key released")
                },
                onExit = {
                    Log.d(TAG, "CXR: AI scene exited")
                }
            )
            
            // Monitor CXR Bluetooth connection state
            serviceScope.launch {
                cxrManager?.bluetoothState?.collect { state ->
                    Log.d(TAG, "CXR Bluetooth state: $state")
                    when (state) {
                        is CxrMobileManager.BluetoothState.Connected -> {
                            Log.d(TAG, "CXR connected: ${state.macAddress}")
                        }
                        is CxrMobileManager.BluetoothState.Disconnected -> {
                            Log.d(TAG, "CXR disconnected")
                        }
                        is CxrMobileManager.BluetoothState.Failed -> {
                            Log.e(TAG, "CXR connection failed: ${state.error}")
                        }
                        else -> {}
                    }
                }
            }
            
            Log.d(TAG, "CXR-M SDK initialized")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize CXR-M SDK", e)
        }
    }
    
    /**
     * Capture photo from glasses using CXR-M SDK
     */
    private suspend fun capturePhotoFromGlasses() {
        val cxr = cxrManager ?: run {
            Log.w(TAG, "CXR manager not available, using legacy photo transfer")
            return
        }
        
        if (!cxr.isBluetoothConnected()) {
            Log.w(TAG, "CXR not connected to glasses")
            bluetoothManager?.sendMessage(Message.aiError(getString(R.string.glasses_not_connected_cxr)))
            return
        }
        
        // Check API key before triggering photo capture
        val settingsRepository = SettingsRepository.getInstance(this)
        val apiKey = settingsRepository.getSettings().geminiApiKey
        if (apiKey.isBlank()) {
            Log.e(TAG, "API key is not configured, aborting photo capture")
            bluetoothManager?.sendMessage(Message.aiError("API key not configured. Please set up an API key in Settings."))
            return
        }
        
        Log.d(TAG, "Capturing photo from glasses via CXR SDK...")
        
        // Notify glasses: taking photo
        cxr.sendTtsContent(getString(R.string.taking_photo))
        
        // Take photo using CXR SDK
        val status = cxr.takePhoto(
            width = 1280,
            height = 720,
            quality = 80
        ) { resultStatus, photoData ->
            serviceScope.launch {
                when (resultStatus) {
                    ValueUtil.CxrStatus.RESPONSE_SUCCEED -> {
                        if (photoData != null && photoData.isNotEmpty()) {
                            Log.d(TAG, "CXR photo received: ${photoData.size} bytes")
                            handleCxrPhotoResult(photoData)
                        } else {
                            Log.e(TAG, "CXR photo is empty")
                            bluetoothManager?.sendMessage(Message.aiError(getString(R.string.photo_empty)))
                        }
                    }
                    ValueUtil.CxrStatus.RESPONSE_TIMEOUT -> {
                        Log.e(TAG, "CXR photo timeout")
                        bluetoothManager?.sendMessage(Message.aiError(getString(R.string.photo_timeout)))
                    }
                    else -> {
                        Log.e(TAG, "CXR photo failed: $resultStatus")
                        bluetoothManager?.sendMessage(Message.aiError(getString(R.string.photo_capture_failed, resultStatus)))
                    }
                }
            }
        }
        
        Log.d(TAG, "CXR takePhoto request status: $status")
    }
    
    /**
     * Handle photo captured via CXR SDK
     */
    private suspend fun handleCxrPhotoResult(photoData: ByteArray) {
        try {
            Log.d(TAG, "Processing CXR photo: ${photoData.size} bytes")
            
            // Create a ReceivedPhoto object
            val receivedPhoto = ReceivedPhoto(
                data = photoData,
                timestamp = System.currentTimeMillis(),
                transferTimeMs = 0  // Direct capture, no transfer time
            )
            
            // Process the photo
            handleReceivedPhoto(receivedPhoto)
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to process CXR photo", e)
            bluetoothManager?.sendMessage(Message.aiError(getString(R.string.photo_process_failed, e.message ?: "")))
        }
    }
    
    private suspend fun handleGlassesMessage(message: Message) {
        Log.d(TAG, "Received message from glasses: ${message.type}")
        
        when (message.type) {
            MessageType.VOICE_END -> {
                // Voice input ended, audio data is in binaryData
                message.binaryData?.let { audioData ->
                    Log.d(TAG, "Processing voice data: ${audioData.size} bytes")
                    
                    // If Live mode is active, audio is already streamed in real-time.
                    // VOICE_END only signals end-of-turn for the live session.
                    if (liveSession != null && liveSession?.sessionState?.value == GeminiLiveSession.SessionState.ACTIVE) {
                        Log.d(TAG, "Live mode active, signaling end of turn")
                        liveSession?.endOfTurn()
                    } else {
                        processVoiceData(audioData)
                    }
                }
            }
            MessageType.VOICE_START -> {
                Log.d(TAG, "Voice recording started on glasses")
                
                // In Live mode, audio is streamed in real-time — no STT step needed
                if (liveSession != null && liveSession?.sessionState?.value == GeminiLiveSession.SessionState.ACTIVE) {
                    Log.d(TAG, "Live mode active, audio streams directly to Gemini")
                    return
                }
                
                // Check STT service availability before allowing recording
                if (sttService == null && speechService == null) {
                    Log.e(TAG, "STT service not available - API key not configured")
                    val errorMsg = getString(R.string.service_not_ready)
                    bluetoothManager?.sendMessage(Message.aiError(errorMsg))
                    ServiceBridge.emitConversation(Message(
                        type = MessageType.AI_ERROR,
                        payload = errorMsg
                    ))
                    notifyApiKeyMissing()
                    return
                }
                
                // Notify phone UI
                ServiceBridge.emitConversation(Message(
                    type = MessageType.AI_PROCESSING,
                    payload = getString(R.string.glasses_recording)
                ))
            }
            MessageType.HEARTBEAT -> {
                bluetoothManager?.sendMessage(Message(type = MessageType.HEARTBEAT_ACK))
            }
            // Receive real-time video frames from glasses and forward to Gemini Live Session
            MessageType.VIDEO_FRAME -> {
                message.binaryData?.let { frameData ->
                    if (liveSession != null && liveSession?.sessionState?.value == GeminiLiveSession.SessionState.ACTIVE) {
                        Log.d(TAG, "Forwarding video frame to Live session: ${frameData.size} bytes")
                        liveSession?.sendVideoFrame(frameData)
                    } else {
                        Log.w(TAG, "Received VIDEO_FRAME but Live session is not active")
                    }
                }
            }
            // Receive real-time transcription text from glasses and forward to phone UI
            MessageType.LIVE_TRANSCRIPTION -> {
                message.payload?.let { text ->
                    Log.d(TAG, "Received live transcription from glasses: $text")
                    ServiceBridge.emitConversation(Message(
                        type = MessageType.LIVE_TRANSCRIPTION,
                        payload = text
                    ))
                }
            }
            else -> { 
                Log.d(TAG, "Unhandled message type: ${message.type}")
            }
        }
    }
    
    /**
     * Handle received photo from glasses
     */
    private suspend fun handleReceivedPhoto(receivedPhoto: ReceivedPhoto) {
        Log.d(TAG, "Received photo: ${receivedPhoto.data.size} bytes, transfer time: ${receivedPhoto.transferTimeMs}ms")
        
        // Process and store the photo
        val photoData = photoRepository?.processReceivedPhoto(receivedPhoto)
        
        if (photoData != null) {
            Log.d(TAG, "Photo saved: ${photoData.filePath}")
            
            // Notify UI about the photo path for display
            ServiceBridge.emitLatestPhotoPath(photoData.filePath)
            
            // Notify UI that a photo was received
            ServiceBridge.emitConversation(Message(
                type = MessageType.AI_PROCESSING,
                payload = getString(R.string.photo_received)
            ))
            
            // Analyze the photo with AI
            analyzePhotoWithAI(photoData)
        } else {
            Log.e(TAG, "Failed to process received photo")
            bluetoothManager?.sendMessage(Message.aiError(getString(R.string.photo_processing_failed)))
        }
    }
    
    /**
     * Handle photo transfer state changes
     */
    private fun handlePhotoTransferState(state: PhotoTransferState) {
        when (state) {
            is PhotoTransferState.Idle -> {
                Log.d(TAG, "Photo transfer: Idle")
            }
            is PhotoTransferState.InProgress -> {
                Log.d(TAG, "Photo transfer: ${state.currentChunk}/${state.totalChunks} " +
                        "(${state.progressPercent.toInt()}%)")
            }
            is PhotoTransferState.Success -> {
                Log.d(TAG, "Photo transfer: Success (${state.data.size} bytes)")
            }
            is PhotoTransferState.Error -> {
                Log.e(TAG, "Photo transfer error: ${state.message}")
                serviceScope.launch {
                    bluetoothManager?.sendMessage(Message.aiError(
                        getString(R.string.photo_transfer_failed, state.message)
                    ))
                }
            }
        }
    }
    
    /**
     * Analyze photo with AI and send results back to glasses
     */
    private suspend fun analyzePhotoWithAI(photoData: PhotoData) {
        try {
            // Get photo bytes for AI analysis
            val photoBytes = photoRepository?.getPhotoBytes(photoData) ?: return
            
            Log.d(TAG, "Analyzing photo with AI: ${photoBytes.size} bytes")
            
            // Notify glasses: analyzing photo
            bluetoothManager?.sendMessage(Message.aiProcessing(getString(R.string.analyzing_photo)))
            
            ServiceBridge.emitConversation(Message(
                type = MessageType.AI_PROCESSING,
                payload = getString(R.string.analyzing_photo)
            ))
            
            // Use AI service to analyze the image with localized prompt
            val analysisResult = aiService?.analyzeImage(
                photoBytes, 
                getString(R.string.image_analysis_prompt)
            ) ?: getString(R.string.ai_analysis_unavailable)
            
            // Clean markdown for glasses display
            val cleanedResult = cleanMarkdown(analysisResult)
            
            Log.d(TAG, "Photo analysis result: $cleanedResult")
            
            // Update photo data with analysis result
            photoData.analysisResult = cleanedResult
            
            // Send result to glasses
            bluetoothManager?.sendMessage(Message(
                type = MessageType.PHOTO_ANALYSIS_RESULT,
                payload = cleanedResult
            ))
            
            // Notify phone UI
            ServiceBridge.emitConversation(Message(
                type = MessageType.AI_RESPONSE_TEXT,
                payload = cleanedResult
            ))
            
            // TTS voice playback
            ttsService?.speak(cleanedResult) { }
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to analyze photo", e)
            bluetoothManager?.sendMessage(Message.aiError(
                getString(R.string.photo_analysis_failed, e.message ?: "")
            ))
        }
    }
    
    /**
     * Process voice data received from glasses
     */
    private suspend fun processVoiceData(audioData: ByteArray) {
        try {
            // Check if any speech service is available
            if (sttService == null && speechService == null) {
                Log.e(TAG, "Speech service not available - no API key configured")
                val errorMsg = getString(R.string.service_not_ready)
                bluetoothManager?.sendMessage(Message.aiError(errorMsg))
                ServiceBridge.emitConversation(Message(
                    type = MessageType.AI_ERROR,
                    payload = errorMsg
                ))
                // Notify UI to show settings prompt
                notifyApiKeyMissing()
                return
            }
            
            // 1. Notify glasses: recognizing
            bluetoothManager?.sendMessage(Message.aiProcessing(getString(R.string.recognizing_speech)))
            
            // 2. Speech recognition - prefer dedicated STT service if available
            Log.d(TAG, "Starting speech recognition...")
            val settings = SettingsRepository.getInstance(this).getSettings()
            val transcriptResult = if (sttService != null) {
                Log.d(TAG, "Using dedicated STT service: ${sttService?.provider?.name}")
                sttService?.transcribe(audioData, settings.speechLanguage)
            } else {
                Log.d(TAG, "Using AI-based speech service, language: ${settings.speechLanguage}")
                speechService?.transcribe(audioData, settings.speechLanguage)
            }
            
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
                        notifyApiKeyMissing()
                    }
                    return
                }
                null -> {
                    val errorMsg = getString(R.string.service_not_ready)
                    bluetoothManager?.sendMessage(Message.aiError(errorMsg))
                    notifyApiKeyMissing()
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
            
            // 3.1 Save user message to database for history
            saveUserMessage(transcript)
            
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
            
            // 6.1 Save AI response to database for history
            saveAssistantMessage(aiResponse, settings.aiModelId)
            
            // 6.2 Save glasses recording to database (with transcript and AI response)
            try {
                recordingRepository?.saveGlassesRecording(
                    audioData = audioData,
                    transcript = transcript,
                    aiResponse = aiResponse,
                    providerId = settings.aiProvider.name,
                    modelId = settings.aiModelId
                )
                Log.d(TAG, "Glasses recording saved to database")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to save glasses recording", e)
            }
            
            // 7. TTS voice playback (optional)
            ttsService?.speak(aiResponse) { }
            
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            // Service is being stopped, don't treat this as an error
            Log.d(TAG, "Voice processing cancelled (service stopping)")
            throw e  // Re-throw to properly propagate cancellation
        } catch (e: Exception) {
            Log.e(TAG, "Error processing voice data", e)
            bluetoothManager?.sendMessage(Message.aiError(getString(R.string.processing_failed, e.message ?: "")))
        }
    }
    
    // ========== Gemini Live Mode ==========
    
    /**
     * Handle provider transitions to/from Live mode.
     * Called whenever settings change.
     */
    private fun handleLiveModeTransition(settings: ApiSettings) {
        if (settings.aiProvider == AiProvider.GEMINI_LIVE) {
            // Start Live session if not already active
            if (liveSession == null || liveSession?.sessionState?.value == GeminiLiveSession.SessionState.IDLE
                || liveSession?.sessionState?.value == GeminiLiveSession.SessionState.ERROR) {
                startLiveSession(settings)
            }
        } else {
            // Stop Live session if switching away from Live mode
            if (liveSession != null) {
                Log.d(TAG, "Switching away from Live mode, stopping session")
                liveSession?.release()
                liveSession = null
                
                // Notify glasses that live session ended
                serviceScope.launch {
                    bluetoothManager?.sendMessage(Message(type = MessageType.LIVE_SESSION_END))
                }
            }
        }
    }
    
    /**
     * Start a Gemini Live session for real-time bidirectional voice.
     */
    private fun startLiveSession(settings: ApiSettings) {
        val apiKey = settings.geminiApiKey
        if (apiKey.isBlank()) {
            Log.e(TAG, "Cannot start Live session: Gemini API key not configured")
            serviceScope.launch { notifyApiKeyMissing() }
            return
        }
        
        Log.d(TAG, "Starting Gemini Live session")
        
        liveSession = GeminiLiveSession(
            context = this,
            apiKey = apiKey,
            modelId = settings.aiModelId.ifBlank { "gemini-2.5-flash-preview-native-audio-dialog" },
            systemPrompt = buildSystemPromptWithLanguage(settings.systemPrompt, settings.responseLanguage)
        )
        
        // Collect Live session events
        collectLiveSessionEvents()
        
        // Start the session
        val started = liveSession?.start() ?: false
        if (started) {
            Log.d(TAG, "Live session started successfully")
            serviceScope.launch {
                bluetoothManager?.sendMessage(Message(type = MessageType.LIVE_SESSION_START))
            }
        } else {
            Log.e(TAG, "Failed to start Live session")
        }
    }
    
    /**
     * Collect event flows from the Live session and forward to glasses/UI.
     */
    private fun collectLiveSessionEvents() {
        val session = liveSession ?: return
        
        // User speech transcription
        serviceScope.launch {
            session.inputTranscription.collect { text ->
                Log.d(TAG, "Live input transcription: $text")
                bluetoothManager?.sendMessage(Message(
                    type = MessageType.LIVE_TRANSCRIPTION,
                    payload = text
                ))
                ServiceBridge.emitConversation(Message(
                    type = MessageType.USER_TRANSCRIPT,
                    payload = text
                ))
                saveUserMessage(text)
            }
        }
        
        // AI response transcription
        serviceScope.launch {
            session.outputTranscription.collect { text ->
                Log.d(TAG, "Live output transcription: $text")
                bluetoothManager?.sendMessage(Message(
                    type = MessageType.AI_RESPONSE_TEXT,
                    payload = text
                ))
                ServiceBridge.emitConversation(Message(
                    type = MessageType.AI_RESPONSE_TEXT,
                    payload = text
                ))
                val settings = SettingsRepository.getInstance(this@PhoneAIService).getSettings()
                saveAssistantMessage(text, settings.aiModelId)
            }
        }
        
        // Turn complete
        serviceScope.launch {
            session.turnComplete.collect {
                Log.d(TAG, "Live turn complete")
            }
        }
        
        // Interrupted
        serviceScope.launch {
            session.interrupted.collect {
                Log.d(TAG, "Live session interrupted by user")
            }
        }
        
        // Session state changes
        serviceScope.launch {
            session.sessionState.collect { state ->
                Log.d(TAG, "Live session state: $state")
                when (state) {
                    GeminiLiveSession.SessionState.ERROR -> {
                        val error = session.errorMessage.value ?: "Live session error"
                        Log.e(TAG, "Live session error: $error")
                        serviceScope.launch {
                            bluetoothManager?.sendMessage(Message.aiError(error))
                            bluetoothManager?.sendMessage(Message(type = MessageType.LIVE_SESSION_END))
                        }
                    }
                    GeminiLiveSession.SessionState.IDLE -> {
                        // Session stopped
                    }
                    else -> { /* CONNECTING, ACTIVE, PAUSED, DISCONNECTING */ }
                }
            }
        }
    }
    
    /**
     * Process phone recording - transcribe and analyze with AI
     * Called when user stops recording from phone microphone
     * @param recordingId The ID of the recording in database
     * @param filePath The path to the WAV file
     */
    private suspend fun processPhoneRecording(recordingId: String, filePath: String) {
        // Deduplicate: skip if this recording is already being processed
        if (!processingRecordingIds.add(recordingId)) {
            Log.w(TAG, "Recording $recordingId is already being processed, skipping duplicate request")
            return
        }
        
        try {
            // Early check for empty file path
            if (filePath.isBlank()) {
                Log.w(TAG, "Recording $recordingId has empty file path, skipping")
                return
            }
            
            // Check if already processed in database (prevents duplicate processing)
            val existingRecording = recordingRepository?.getRecordingById(recordingId)
            if (existingRecording != null && 
                !existingRecording.transcript.isNullOrBlank() && 
                !existingRecording.aiResponse.isNullOrBlank()) {
                Log.d(TAG, "Recording $recordingId already has transcript and AI response, skipping duplicate")
                return
            }
            
            // Check settings - should we auto-analyze?
            val settings = SettingsRepository.getInstance(this).getSettings()
            if (!settings.autoAnalyzeRecordings) {
                Log.d(TAG, "Auto-analyze disabled, skipping recording: $recordingId")
                return
            }
            
            Log.d(TAG, "Processing phone recording: $recordingId, path: $filePath")
            
            // Check if any speech service is available
            if (sttService == null && speechService == null) {
                Log.e(TAG, "Speech service not available - no API key configured")
                recordingRepository?.markError(recordingId, getString(R.string.service_not_ready))
                notifyApiKeyMissing()
                return
            }
            
            // Read audio file
            val audioFile = java.io.File(filePath)
            if (!audioFile.exists()) {
                Log.e(TAG, "Recording file not found: $filePath")
                recordingRepository?.markError(recordingId, "File not found")
                return
            }
            
            val audioData = audioFile.readBytes()
            Log.d(TAG, "Read audio file: ${audioData.size} bytes")
            
            // Notify UI and glasses: transcribing
            if (settings.pushRecordingToGlasses) {
                bluetoothManager?.sendMessage(Message.aiProcessing(getString(R.string.recognizing_speech)))
            }
            ServiceBridge.emitConversation(Message(
                type = MessageType.AI_PROCESSING,
                payload = getString(R.string.recognizing_speech)
            ))
            
            // 1. Speech recognition
            val transcriptResult = performSpeechRecognition(audioData, filePath, settings.speechLanguage)
            val transcript = extractTranscript(transcriptResult, recordingId, settings) ?: return
            
            // 2. Update recording with transcript
            recordingRepository?.updateTranscript(recordingId, transcript)
            
            // 3. Notify UI and glasses: analyzing
            if (settings.pushRecordingToGlasses) {
                bluetoothManager?.sendMessage(Message.aiProcessing(getString(R.string.thinking)))
            }
            ServiceBridge.emitConversation(Message(
                type = MessageType.AI_PROCESSING,
                payload = getString(R.string.thinking)
            ))
            
            // 4. AI conversation
            Log.d(TAG, "Getting AI response for phone recording...")
            val rawAiResponse = aiService?.chat(transcript) ?: getString(R.string.ai_analysis_unavailable)
            val aiResponse = cleanMarkdown(rawAiResponse)
            
            Log.d(TAG, "Phone recording AI response: $aiResponse")
            
            // 5. Update recording with AI response
            recordingRepository?.updateAiResponse(
                id = recordingId,
                response = aiResponse,
                providerId = settings.aiProvider.name,
                modelId = settings.aiModelId
            )
            
            // 6. Send result to glasses (if enabled) and phone UI
            if (settings.pushRecordingToGlasses) {
                bluetoothManager?.sendMessage(Message(type = MessageType.USER_TRANSCRIPT, payload = transcript))
                bluetoothManager?.sendMessage(Message.aiResponseText(aiResponse))
            }
            
            ServiceBridge.emitConversation(Message(
                type = MessageType.USER_TRANSCRIPT,
                payload = transcript
            ))
            ServiceBridge.emitConversation(Message(
                type = MessageType.AI_RESPONSE_TEXT,
                payload = aiResponse
            ))
            
            // 7. Save to conversation history
            saveUserMessage(transcript)
            saveAssistantMessage(aiResponse, settings.aiModelId)
            
            // 8. TTS playback (optional)
            ttsService?.speak(aiResponse) { }
            
            Log.d(TAG, "Phone recording processed successfully: $recordingId")
            
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            Log.d(TAG, "Phone recording processing cancelled (service stopping)")
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Error processing phone recording", e)
            notifyProcessingError(recordingId, e.message ?: "Unknown error")
        } finally {
            processingRecordingIds.remove(recordingId)
        }
    }
    
    /**
     * Determine audio MIME type from file extension
     */
    private fun getAudioMimeType(filePath: String): String = when {
        filePath.endsWith(".m4a", ignoreCase = true) || filePath.endsWith(".mp4", ignoreCase = true) -> "audio/mp4"
        filePath.endsWith(".aac", ignoreCase = true) -> "audio/aac"
        filePath.endsWith(".mp3", ignoreCase = true) -> "audio/mpeg"
        filePath.endsWith(".ogg", ignoreCase = true) -> "audio/ogg"
        else -> "audio/wav"
    }
    
    /**
     * Perform speech recognition using the best available STT service
     */
    private suspend fun performSpeechRecognition(
        audioData: ByteArray,
        filePath: String,
        languageCode: String
    ): SpeechResult? {
        val isEncodedAudio = filePath.endsWith(".m4a", ignoreCase = true) ||
                filePath.endsWith(".mp4", ignoreCase = true) ||
                filePath.endsWith(".aac", ignoreCase = true) ||
                filePath.endsWith(".mp3", ignoreCase = true) ||
                filePath.endsWith(".ogg", ignoreCase = true)
        val audioMimeType = getAudioMimeType(filePath)
        
        Log.d(TAG, "Starting speech recognition for phone recording... (encoded=$isEncodedAudio, mimeType=$audioMimeType)")
        
        val stt = sttService
        val speech = speechService
        val serviceName = if (stt != null) "dedicated STT (${stt.provider.name})" else "AI-based speech"
        Log.d(TAG, "Using $serviceName service, language: $languageCode")
        
        return when {
            stt != null && isEncodedAudio -> stt.transcribeAudioFile(audioData, audioMimeType, languageCode)
            stt != null -> stt.transcribe(audioData, languageCode)
            speech != null && isEncodedAudio -> speech.transcribeAudioFile(audioData, audioMimeType, languageCode)
            speech != null -> speech.transcribe(audioData, languageCode)
            else -> null
        }
    }
    
    /**
     * Extract transcript text from SpeechResult, notifying errors as needed.
     * Returns null if transcription failed (caller should return early).
     */
    private suspend fun extractTranscript(
        result: SpeechResult?,
        recordingId: String,
        settings: ApiSettings
    ): String? {
        return when (result) {
            is SpeechResult.Success -> {
                Log.d(TAG, "Phone recording transcript: ${result.text}")
                result.text
            }
            is SpeechResult.Error -> {
                Log.e(TAG, "Phone recording transcription error: ${result.message}")
                notifyRecordingError(recordingId, result.message, settings)
                null
            }
            null -> {
                notifyRecordingError(recordingId, getString(R.string.service_not_ready), settings)
                notifyApiKeyMissing()
                null
            }
        }
    }
    
    /**
     * Notify recording error to database, glasses, and phone UI
     */
    private suspend fun notifyRecordingError(recordingId: String, message: String, settings: ApiSettings) {
        recordingRepository?.markError(recordingId, message)
        if (settings.pushRecordingToGlasses) {
            bluetoothManager?.sendMessage(Message.aiError(message))
        }
        ServiceBridge.emitConversation(Message(type = MessageType.AI_ERROR, payload = message))
    }
    
    /**
     * Notify processing error with best-effort error propagation
     */
    private suspend fun notifyProcessingError(recordingId: String, errorMsg: String) {
        recordingRepository?.markError(recordingId, errorMsg)
        try {
            val settings = SettingsRepository.getInstance(this).getSettings()
            if (settings.pushRecordingToGlasses) {
                bluetoothManager?.sendMessage(Message.aiError(errorMsg))
            }
            ServiceBridge.emitConversation(Message(type = MessageType.AI_ERROR, payload = errorMsg))
        } catch (e: Exception) {
            Log.w(TAG, "Failed to notify error state", e)
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
    
    /**
     * Ensure a voice conversation session exists for persisting voice interactions
     * Creates a new conversation if needed, or continues using existing one from today
     */
    private suspend fun ensureVoiceConversationSession(settings: ApiSettings) {
        try {
            if (currentVoiceConversationId == null) {
                // First, try to find an existing voice session from today
                val existingSession = conversationRepository?.findTodayVoiceSession()
                
                if (existingSession != null) {
                    // Reuse existing session from today
                    currentVoiceConversationId = existingSession.id
                    Log.d(TAG, "Reusing existing voice conversation session: $currentVoiceConversationId")
                } else {
                    // Create a new conversation for voice interactions
                    val dateFormat = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault())
                    val title = getString(R.string.voice_session_title, dateFormat.format(java.util.Date()))
                    
                    val conversation = conversationRepository?.createConversation(
                        providerId = settings.aiProvider.name,
                        modelId = settings.aiModelId,
                        title = title,
                        systemPrompt = settings.systemPrompt
                    )
                    
                    currentVoiceConversationId = conversation?.id
                    Log.d(TAG, "Created voice conversation session: $currentVoiceConversationId")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error creating voice conversation session", e)
        }
    }
    
    /**
     * Save user message to database
     */
    private suspend fun saveUserMessage(content: String) {
        currentVoiceConversationId?.let { conversationId ->
            try {
                conversationRepository?.addUserMessage(conversationId, content)
                Log.d(TAG, "Saved user message to conversation: $conversationId")
            } catch (e: Exception) {
                Log.e(TAG, "Error saving user message", e)
            }
        }
    }
    
    /**
     * Save AI response to database
     */
    private suspend fun saveAssistantMessage(content: String, modelId: String?) {
        currentVoiceConversationId?.let { conversationId ->
            try {
                conversationRepository?.addAssistantMessage(
                    conversationId = conversationId,
                    content = content,
                    modelId = modelId
                )
                Log.d(TAG, "Saved assistant message to conversation: $conversationId")
            } catch (e: Exception) {
                Log.e(TAG, "Error saving assistant message", e)
            }
        }
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
     * Prepend an explicit language-enforcement instruction to [basePrompt].
     *
     * Older / smaller models (e.g. Gemini 2.0 Flash) may ignore a general
     * "respond in the user's language" instruction unless it is prominently placed at
     * the very beginning of the system prompt.  This approach keeps the original
     * user-written base prompt intact while ensuring model compliance.
     *
     * If [responseLanguage] is blank the base prompt is returned unchanged — the model
     * will rely on the conversation language as usual.
     *
     * TODO: Verify via manual testing that the prepended instruction does not conflict
     *       with any user-customised system prompt.
     */
    private fun buildSystemPromptWithLanguage(basePrompt: String, responseLanguage: String): String {
        if (responseLanguage.isBlank()) return basePrompt

        val locale = java.util.Locale.forLanguageTag(responseLanguage)

        // Short English language name used in the closing phrase, e.g. "Korean", "French".
        // Falls back to the raw tag if the JVM returns an empty string for an unknown code.
        val englishLanguageName = locale.getDisplayLanguage(java.util.Locale.ENGLISH)
            .takeIf { it.isNotBlank() } ?: responseLanguage

        // Full English label including region qualifier for disambiguation when needed,
        // e.g. "Chinese (Taiwan)" vs "Chinese (China)", "French (France)".
        val fullEnglishLabel = locale.getDisplayName(java.util.Locale.ENGLISH)
            .takeIf { it.isNotBlank() } ?: englishLanguageName

        // Native self-name, e.g. "한국어", "日本語", "français".
        // Omitted when it is identical to the English name (e.g. for "English").
        val nativeName = locale.getDisplayLanguage(locale)
            .takeIf { it.isNotBlank() && !it.equals(englishLanguageName, ignoreCase = true) }

        val languageLabel = if (nativeName != null) "$fullEnglishLabel ($nativeName)" else fullEnglishLabel

        val langInstruction =
            "CRITICAL INSTRUCTION: You MUST respond ONLY in $languageLabel. " +
            "Never mix in other languages. All responses must be in pure $englishLanguageName.\n\n"

        return langInstruction + basePrompt
    }

    /**
     * Create AI service
     */
    private fun createAiService(settings: ApiSettings): AiServiceProvider {
        // If no API key configured, notify user
        val effectiveSettings = if (settings.getCurrentApiKey().isBlank()) {
            // Check if BuildConfig has a valid Gemini API key as fallback
            if (BuildConfig.GEMINI_API_KEY.isNotBlank()) {
                Log.d(TAG, "No API key for ${settings.aiProvider}, using development fallback")
                settings.copy(
                    aiProvider = AiProvider.GEMINI,
                    aiModelId = "gemini-2.5-flash",
                    geminiApiKey = BuildConfig.GEMINI_API_KEY
                )
            } else {
                // No fallback available - user must configure API key
                Log.w(TAG, "No API key configured. Please set up an API key in Settings.")
                settings
            }
        } else {
            settings
        }
        
        val promptEnhancedSettings = effectiveSettings.copy(
            systemPrompt = buildSystemPromptWithLanguage(
                effectiveSettings.systemPrompt,
                effectiveSettings.responseLanguage
            )
        )
        return AiServiceFactory.createService(promptEnhancedSettings)
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
                val sttModel = getSttFallbackModelId(provider)
                if (sttModel != null) {
                    Log.d(TAG, "Using ${provider.name} for speech recognition, model: $sttModel")
                    return AiServiceFactory.createService(settings.copy(
                        aiProvider = provider,
                        aiModelId = sttModel
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
     * Get known-working STT model for a provider.
     * Uses stable model IDs that are confirmed to exist in each provider's API,
     * rather than picking the first model from the display list (which may include
     * unreleased/preview models like gemini-3-pro).
     */
    private fun getSttFallbackModelId(provider: AiProvider): String? {
        return when (provider) {
            AiProvider.GEMINI -> "gemini-2.5-flash"
            AiProvider.OPENAI -> "gpt-4o-mini"
            AiProvider.GROQ -> "whisper-large-v3"
            AiProvider.XAI -> "grok-2-latest"
            else -> AvailableModels.getModelsForProvider(provider).firstOrNull()?.id
        }
    }
    
    /**
     * Create dedicated STT service for specialized providers
     * Uses SttServiceFactory for providers like Deepgram, Azure, Aliyun, etc.
     */
    private fun createSttService(settings: ApiSettings): SttService? {
        val sttProvider = settings.sttProvider
        
        // Check if this is a provider that uses main AI API keys (handled by speechService)
        val aiBasedProviders = listOf(
            SttProvider.GEMINI,
            SttProvider.OPENAI_WHISPER,
            SttProvider.GROQ_WHISPER
        )
        
        if (sttProvider in aiBasedProviders) {
            Log.d(TAG, "STT provider ${sttProvider.name} uses main AI service, no dedicated STT service needed")
            return null
        }
        
        // Create dedicated STT service using SttServiceFactory
        val sttCredentials = settings.toSttCredentials()
        val service = SttServiceFactory.createService(sttCredentials, settings)
        
        if (service != null) {
            Log.d(TAG, "Created dedicated STT service for provider: ${sttProvider.name}")
        } else {
            Log.w(TAG, "Failed to create STT service for ${sttProvider.name} - credentials may be missing")
        }
        
        return service
    }
    
    /**
     * Check if speech service is available
     */
    fun isSpeechServiceAvailable(): Boolean = speechService != null || sttService != null
    
    /**
     * Validate and correct settings
     * Ensure selected model is compatible with AI provider
     */
    private fun validateAndCorrectSettings(settings: ApiSettings): ApiSettings {
        // Migrate deprecated model IDs to their replacements
        val deprecatedModelMigrations = mapOf(
            "sonar-reasoning" to "sonar-reasoning-pro"
        )
        val migratedSettings = deprecatedModelMigrations[settings.aiModelId]?.let { replacement ->
            Log.w(TAG, "Model '${settings.aiModelId}' is deprecated, migrating to '$replacement'")
            settings.copy(aiModelId = replacement)
        } ?: settings
        
        val modelInfo = AvailableModels.findModel(migratedSettings.aiModelId)
        
        // If model info not found, use provider's default model
        if (modelInfo == null) {
            Log.w(TAG, "Unknown model: ${migratedSettings.aiModelId}, using default model for ${migratedSettings.aiProvider}")
            val defaultModel = AvailableModels.getModelsForProvider(migratedSettings.aiProvider).firstOrNull()
            return if (defaultModel != null) {
                migratedSettings.copy(aiModelId = defaultModel.id)
            } else {
                // Fall back to Gemini
                migratedSettings.copy(
                    aiProvider = AiProvider.GEMINI,
                    aiModelId = "gemini-2.5-flash"
                )
            }
        }
        
        // If model doesn't match provider, correct the provider
        if (modelInfo.provider != migratedSettings.aiProvider) {
            Log.w(TAG, "Model ${migratedSettings.aiModelId} belongs to ${modelInfo.provider}, correcting provider")
            return migratedSettings.copy(aiProvider = modelInfo.provider)
        }
        
        // Check if provider has API key
        if (migratedSettings.getCurrentApiKey().isBlank()) {
            Log.w(TAG, "No API key for ${migratedSettings.aiProvider}, checking for fallback")
            // Try to use provider that has API key
            for (provider in AiProvider.entries) {
                val apiKey = migratedSettings.getApiKeyForProvider(provider)
                if (apiKey.isNotBlank()) {
                    val defaultModel = AvailableModels.getModelsForProvider(provider).firstOrNull()
                    if (defaultModel != null) {
                        Log.d(TAG, "Falling back to ${provider.name}")
                        return migratedSettings.copy(
                            aiProvider = provider,
                            aiModelId = defaultModel.id
                        )
                    }
                }
            }
        }
        
        return migratedSettings
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
                val defaultLocale = java.util.Locale.getDefault()
                val langResult = tts?.setLanguage(defaultLocale)
                if (langResult == android.speech.tts.TextToSpeech.LANG_MISSING_DATA ||
                    langResult == android.speech.tts.TextToSpeech.LANG_NOT_SUPPORTED) {
                    android.util.Log.w(
                        "TextToSpeechService",
                        "System TTS does not support device locale '$defaultLocale'. " +
                        "Consider installing the '${defaultLocale.displayLanguage}' TTS pack."
                    )
                }
            } else {
                android.util.Log.e("TextToSpeechService", "System TTS initialization failed (status=$status)")
            }
        }
    }
    
    fun speak(text: String, onAudioChunk: (ByteArray) -> Unit) {
        // Simplified version: using system TTS
        // TODO: For consistent multi-language voice quality (especially Korean), consider
        //       routing through EdgeTtsClient (Microsoft Edge TTS) instead of system TTS.
        val locale = detectLocaleForText(text)
        val langResult = tts?.setLanguage(locale)
        if (langResult == android.speech.tts.TextToSpeech.LANG_MISSING_DATA ||
            langResult == android.speech.tts.TextToSpeech.LANG_NOT_SUPPORTED) {
            android.util.Log.w(
                "TextToSpeechService",
                "System TTS language pack not available for locale '$locale'. " +
                "The voice may sound incorrect (e.g. Korean text read by a Chinese voice). " +
                "Install the '${locale.displayLanguage}' TTS pack in device Settings > " +
                "Accessibility > Text-to-speech. Falling back to device default locale."
            )
            // Fall back to device default rather than leaving an unsupported locale set
            tts?.setLanguage(java.util.Locale.getDefault())
        }
        tts?.speak(text, android.speech.tts.TextToSpeech.QUEUE_FLUSH, null, null)
    }
    
    fun shutdown() {
        tts?.shutdown()
    }

    private fun detectLocaleForText(text: String): java.util.Locale {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return java.util.Locale.getDefault()

        return when {
            trimmed.any { it in '\uAC00'..'\uD7AF' } -> java.util.Locale.KOREAN
            trimmed.any { it in '\u4E00'..'\u9FFF' } -> java.util.Locale.TRADITIONAL_CHINESE
            trimmed.any { it in '\u3040'..'\u30FF' } -> java.util.Locale.JAPANESE
            else -> java.util.Locale.getDefault()
        }
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