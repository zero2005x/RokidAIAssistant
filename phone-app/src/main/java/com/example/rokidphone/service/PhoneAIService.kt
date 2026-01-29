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
import com.example.rokidphone.service.ai.AiServiceFactory
import com.example.rokidphone.service.ai.AiServiceProvider
import com.example.rokidphone.service.cxr.CxrMobileManager
import com.example.rokidphone.service.stt.SttProvider
import com.example.rokidphone.service.stt.SttService
import com.example.rokidphone.service.stt.SttServiceFactory
import com.example.rokidphone.data.toSttCredentials
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
    
    // Photo repository for managing received photos
    private var photoRepository: PhotoRepository? = null
    
    // Conversation repository for persisting voice conversations
    private var conversationRepository: ConversationRepository? = null
    
    // Current voice conversation ID (for grouping voice interactions)
    private var currentVoiceConversationId: String? = null
    
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
            }
            
            // Listen for messages from glasses
            serviceScope.launch {
                bluetoothManager?.messageFlow?.collect { message ->
                    handleGlassesMessage(message)
                }
            }
            
            // Initialize photo repository
            photoRepository = PhotoRepository(this, serviceScope)
            
            // Listen for received photos from glasses
            serviceScope.launch {
                bluetoothManager?.receivedPhoto?.collect { receivedPhoto ->
                    handleReceivedPhoto(receivedPhoto)
                }
            }
            
            // Monitor photo transfer state
            serviceScope.launch {
                bluetoothManager?.photoTransferState?.collect { state ->
                    handlePhotoTransferState(state)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing Bluetooth manager", e)
        }
        
        // Listen for capture photo requests from UI
        serviceScope.launch {
            ServiceBridge.capturePhotoFlow.collect {
                Log.d(TAG, "Capture photo request from UI")
                requestGlassesToCapturePhoto()
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
            ServiceBridge.notifyApiKeyMissing()
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
            bluetoothManager?.sendMessage(Message.aiError("眼镜未通过 CXR 连接"))
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
        cxr.sendTtsContent("正在拍照...")
        
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
                            bluetoothManager?.sendMessage(Message.aiError("拍照失败：照片为空"))
                        }
                    }
                    ValueUtil.CxrStatus.RESPONSE_TIMEOUT -> {
                        Log.e(TAG, "CXR photo timeout")
                        bluetoothManager?.sendMessage(Message.aiError("拍照超时，请重试"))
                    }
                    else -> {
                        Log.e(TAG, "CXR photo failed: $resultStatus")
                        bluetoothManager?.sendMessage(Message.aiError("拍照失败: $resultStatus"))
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
            bluetoothManager?.sendMessage(Message.aiError("处理照片失败: ${e.message}"))
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
                
                // Check STT service availability before allowing recording
                if (sttService == null && speechService == null) {
                    Log.e(TAG, "STT service not available - API key not configured")
                    val errorMsg = getString(R.string.service_not_ready)
                    bluetoothManager?.sendMessage(Message.aiError(errorMsg))
                    ServiceBridge.emitConversation(Message(
                        type = MessageType.AI_ERROR,
                        payload = errorMsg
                    ))
                    ServiceBridge.notifyApiKeyMissing()
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
            
            // Use AI service to analyze the image (Chinese prompt)
            val analysisResult = aiService?.analyzeImage(
                photoBytes, 
                "请详细描述这张图片中你所看到的内容。包括主要对象、场景、颜色等细节。用中文回答，简洁明了。"
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
                ServiceBridge.notifyApiKeyMissing()
                return
            }
            
            // 1. Notify glasses: recognizing
            bluetoothManager?.sendMessage(Message.aiProcessing(getString(R.string.recognizing_speech)))
            
            // 2. Speech recognition - prefer dedicated STT service if available
            Log.d(TAG, "Starting speech recognition...")
            val transcriptResult = if (sttService != null) {
                Log.d(TAG, "Using dedicated STT service: ${sttService?.provider?.name}")
                sttService?.transcribe(audioData, "zh-CN")
            } else {
                Log.d(TAG, "Using AI-based speech service")
                speechService?.transcribe(audioData)
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
            val settings = SettingsRepository.getInstance(this).getSettings()
            saveAssistantMessage(aiResponse, settings.aiModelId)
            
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
