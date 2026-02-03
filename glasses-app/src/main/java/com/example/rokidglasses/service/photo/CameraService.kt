package com.example.rokidglasses.service.photo

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothSocket
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.rokidcommon.protocol.photo.PhotoTransferState
import com.example.rokidglasses.MainActivity
import com.example.rokidglasses.R
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Photo capture state for service
 */
sealed class PhotoCaptureState {
    data object Idle : PhotoCaptureState()
    data object Capturing : PhotoCaptureState()
    data object Compressing : PhotoCaptureState()
    data class Transferring(val progress: Float) : PhotoCaptureState()
    data class Success(val transferTimeMs: Long) : PhotoCaptureState()
    data class Error(val message: String) : PhotoCaptureState()
}

/**
 * Camera Service - Foreground Service for Photo Capture & Transfer
 * 
 * This service handles:
 * 1. Listening for camera key events
 * 2. Capturing photos using GlassesCameraManager
 * 3. Compressing photos using ImageCompressor
 * 4. Transferring photos to phone using PhotoTransferProtocol
 * 
 * Usage:
 * ```
 * // Start service
 * val intent = Intent(context, CameraService::class.java)
 * context.startForegroundService(intent)
 * 
 * // Bind to service for control
 * bindService(intent, connection, Context.BIND_AUTO_CREATE)
 * 
 * // Trigger capture
 * cameraService.captureAndSend()
 * ```
 */
class CameraService : Service() {
    
    companion object {
        private const val TAG = "CameraService"
        
        // Notification
        private const val CHANNEL_ID = "camera_service_channel"
        private const val NOTIFICATION_ID = 2001
        
        // Intent actions
        const val ACTION_CAPTURE = "com.example.rokidglasses.action.CAPTURE"
        const val ACTION_STOP = "com.example.rokidglasses.action.STOP"
        
        // Singleton check
        var isRunning = false
            private set
    }
    
    // Binder for activity connection
    private val binder = CameraServiceBinder()
    
    inner class CameraServiceBinder : Binder() {
        fun getService(): CameraService = this@CameraService
    }
    
    // Coroutine scope
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    
    // Camera manager
    private var cameraManager: GlassesCameraManager? = null
    
    // Bluetooth socket (set by ViewModel when connected)
    private var bluetoothSocket: BluetoothSocket? = null
    
    // Transfer protocol
    private var transferProtocol: PhotoTransferProtocol? = null
    
    // State
    private val _captureState = MutableStateFlow<PhotoCaptureState>(PhotoCaptureState.Idle)
    val captureState: StateFlow<PhotoCaptureState> = _captureState.asStateFlow()
    
    // Callbacks
    var onCaptureComplete: ((ByteArray) -> Unit)? = null
    var onTransferComplete: ((TransferStatistics) -> Unit)? = null
    var onError: ((String) -> Unit)? = null
    
    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "CameraService created")
        isRunning = true
        
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, createNotification(getString(R.string.camera_service_starting)))
        
        // Initialize camera manager
        cameraManager = GlassesCameraManager(this)
        
        serviceScope.launch {
            cameraManager?.initialize()
        }
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand: action=${intent?.action}")
        
        when (intent?.action) {
            ACTION_CAPTURE -> {
                serviceScope.launch {
                    captureAndSend()
                }
            }
            ACTION_STOP -> {
                stopSelf()
            }
        }
        
        return START_STICKY
    }
    
    override fun onBind(intent: Intent?): IBinder {
        return binder
    }
    
    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "CameraService destroyed")
        isRunning = false
        
        cameraManager?.release()
        cameraManager = null
        
        serviceScope.cancel()
    }
    
    /**
     * Set Bluetooth socket for photo transfer.
     * Must be called after Bluetooth connection is established.
     */
    fun setBluetoothSocket(socket: BluetoothSocket?) {
        bluetoothSocket = socket
        
        if (socket != null && socket.isConnected) {
            transferProtocol = PhotoTransferProtocol(socket) { current, total ->
                val progress = current.toFloat() / total
                _captureState.value = PhotoCaptureState.Transferring(progress)
                
                Log.d(TAG, "Transfer progress: $current / $total (${(progress * 100).toInt()}%)")
            }
            updateNotification(getString(R.string.bluetooth_connected_ready))
        } else {
            transferProtocol = null
            updateNotification(getString(R.string.waiting_bluetooth_connection))
        }
    }
    
    /**
     * Capture photo and send to phone.
     * Main entry point triggered by key press or voice command.
     */
    suspend fun captureAndSend(): Result<TransferStatistics> = withContext(Dispatchers.IO) {
        try {
            // Check camera permission
            if (cameraManager?.hasCameraPermission() != true) {
                val error = getString(R.string.camera_permission_missing)
                _captureState.value = PhotoCaptureState.Error(error)
                onError?.invoke(error)
                return@withContext Result.failure(SecurityException(error))
            }
            
            // Check Bluetooth connection
            if (bluetoothSocket == null || !bluetoothSocket!!.isConnected) {
                val error = getString(R.string.bluetooth_not_connected)
                _captureState.value = PhotoCaptureState.Error(error)
                onError?.invoke(error)
                return@withContext Result.failure(IllegalStateException(error))
            }
            
            // Step 1: Capture photo
            Log.d(TAG, "Step 1: Capturing photo...")
            _captureState.value = PhotoCaptureState.Capturing
            updateNotification(getString(R.string.capturing_photo))
            
            val rawImageData = cameraManager?.capturePhoto()
            if (rawImageData == null) {
                val error = getString(R.string.capture_failed)
                _captureState.value = PhotoCaptureState.Error(error)
                onError?.invoke(error)
                return@withContext Result.failure(Exception(error))
            }
            
            Log.d(TAG, "Photo captured: ${rawImageData.size} bytes")
            onCaptureComplete?.invoke(rawImageData)
            
            // Step 2: Compress photo
            Log.d(TAG, "Step 2: Compressing photo...")
            _captureState.value = PhotoCaptureState.Compressing
            updateNotification("Compressing...")
            
            val compressedData = ImageCompressor.compressForTransfer(rawImageData)
            Log.d(TAG, "Compressed: ${rawImageData.size} -> ${compressedData.size} bytes")
            
            // Step 3: Transfer to phone
            Log.d(TAG, "Step 3: Transferring to phone...")
            _captureState.value = PhotoCaptureState.Transferring(0f)
            updateNotification("Transferring...")
            
            val transferResult = transferProtocol?.sendPhoto(compressedData)
            
            if (transferResult == null) {
                val error = "Transfer protocol not initialized"
                _captureState.value = PhotoCaptureState.Error(error)
                return@withContext Result.failure(IllegalStateException(error))
            }
            
            transferResult.fold(
                onSuccess = { stats ->
                    Log.d(TAG, "Transfer complete: $stats")
                    _captureState.value = PhotoCaptureState.Success(stats.elapsedTimeMs)
                    updateNotification("Transfer complete (${stats.elapsedTimeMs}ms)")
                    onTransferComplete?.invoke(stats)
                    
                    // Reset to idle after delay
                    serviceScope.launch {
                        delay(3000)
                        if (_captureState.value is PhotoCaptureState.Success) {
                            _captureState.value = PhotoCaptureState.Idle
                            updateNotification("Ready")
                        }
                    }
                    
                    Result.success(stats)
                },
                onFailure = { error ->
                    Log.e(TAG, "Transfer failed", error)
                    _captureState.value = PhotoCaptureState.Error(error.message ?: "Transfer failed")
                    updateNotification("Transfer failed")
                    onError?.invoke(error.message ?: "Transfer failed")
                    Result.failure(error)
                }
            )
            
        } catch (e: Exception) {
            Log.e(TAG, "Capture and send failed", e)
            _captureState.value = PhotoCaptureState.Error(e.message ?: "Unknown error")
            updateNotification("Error: ${e.message}")
            onError?.invoke(e.message ?: "Unknown error")
            Result.failure(e)
        }
    }
    
    /**
     * Capture photo only (without transfer).
     * Useful for testing or local preview.
     */
    suspend fun captureOnly(): ByteArray? = withContext(Dispatchers.IO) {
        try {
            _captureState.value = PhotoCaptureState.Capturing
            updateNotification("Taking photo...")
            
            val imageData = cameraManager?.capturePhoto()
            
            if (imageData != null) {
                _captureState.value = PhotoCaptureState.Success(0)
                updateNotification("Photo captured")
                onCaptureComplete?.invoke(imageData)
            } else {
                _captureState.value = PhotoCaptureState.Error("Capture failed")
            }
            
            imageData
            
        } catch (e: Exception) {
            Log.e(TAG, "Capture failed", e)
            _captureState.value = PhotoCaptureState.Error(e.message ?: "Capture failed")
            null
        }
    }
    
    // ==================== Notification Management ====================
    
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Camera Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Rokid Glasses Camera Service"
                setShowBadge(false)
            }
            
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }
    
    private fun createNotification(text: String): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        
        val stopIntent = PendingIntent.getService(
            this,
            0,
            Intent(this, CameraService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE
        )
        
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Rokid Camera")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(pendingIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopIntent)
            .setOngoing(true)
            .build()
    }
    
    private fun updateNotification(text: String) {
        val notification = createNotification(text)
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.notify(NOTIFICATION_ID, notification)
    }
}
