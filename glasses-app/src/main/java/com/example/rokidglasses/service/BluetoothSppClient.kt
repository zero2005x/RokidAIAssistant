package com.example.rokidglasses.service

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.ActivityCompat
import com.example.rokidcommon.protocol.Message
import com.example.rokidcommon.protocol.MessageType
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.json.JSONObject
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

/**
 * Bluetooth Connection State
 */
enum class BluetoothClientState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED
}

/**
 * Glasses-side Bluetooth SPP Client
 * Responsible for connecting to phone, sending voice data and receiving AI responses
 */
class BluetoothSppClient(
    private val context: Context,
    private val scope: CoroutineScope
) {
    companion object {
        private const val TAG = "BluetoothSppClient"
        
        // Same UUID as phone-side
        val SERVICE_UUID: UUID = UUID.fromString("a1b2c3d4-e5f6-7890-abcd-ef1234567890")
        
        // Message delimiter
        private const val MESSAGE_DELIMITER = "\n"
    }
    
    private val bluetoothAdapter: BluetoothAdapter? by lazy {
        val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothManager.adapter
    }
    
    private var socket: BluetoothSocket? = null
    private var inputStream: InputStream? = null
    private var outputStream: OutputStream? = null
    
    private var connectJob: Job? = null
    private var readJob: Job? = null
    private var heartbeatJob: Job? = null
    
    // Heartbeat interval (10 seconds)
    private val HEARTBEAT_INTERVAL = 10_000L
    
    // Maximum missed heartbeats before triggering reconnection
    private val MAX_MISSED_HEARTBEATS = 3
    
    // Counter for heartbeats sent without receiving ACK
    @Volatile
    private var missedHeartbeatCount = 0
    
    // Last device we were connected to (for auto-reconnect)
    private var lastConnectedDevice: BluetoothDevice? = null

    /**
     * Safely get the device name with BLUETOOTH_CONNECT permission check.
     * Returns "unknown" if the permission is not granted.
     */
    private fun getSafeDeviceName(device: BluetoothDevice): String {
        val hasPermission = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            ActivityCompat.checkSelfPermission(
                context,
                Manifest.permission.BLUETOOTH_CONNECT
            ) == PackageManager.PERMISSION_GRANTED
        return if (hasPermission) device.name ?: "unknown" else "unknown (missing permission)"
    }
    
    // Connection state
    private val _connectionState = MutableStateFlow(BluetoothClientState.DISCONNECTED)
    val connectionState: StateFlow<BluetoothClientState> = _connectionState.asStateFlow()
    
    // Received message flow
    private val _messageFlow = MutableSharedFlow<Message>(extraBufferCapacity = 16)
    val messageFlow: SharedFlow<Message> = _messageFlow.asSharedFlow()
    
    // Connected device name
    private val _connectedDeviceName = MutableStateFlow<String?>(null)
    val connectedDeviceName: StateFlow<String?> = _connectedDeviceName.asStateFlow()
    
    /**
     * Get list of paired devices
     */
    @SuppressLint("MissingPermission")
    fun getPairedDevices(): List<BluetoothDevice> {
        if (!hasBluetoothPermission()) {
            Log.w(TAG, "No Bluetooth permission")
            return emptyList()
        }
        
        return bluetoothAdapter?.bondedDevices?.toList() ?: emptyList()
    }
    
    /**
     * Connect to specified device (with retry mechanism)
     */
    @SuppressLint("MissingPermission")
    fun connect(device: BluetoothDevice, maxRetries: Int = 5) {
        if (!hasBluetoothPermission()) {
            Log.e(TAG, "No Bluetooth permission")
            return
        }
        
        if (_connectionState.value == BluetoothClientState.CONNECTING ||
            _connectionState.value == BluetoothClientState.CONNECTED) {
            Log.w(TAG, "Already connecting or connected")
            return
        }
        
        connectJob?.cancel()
        connectJob = scope.launch(Dispatchers.IO) {
            var lastException: Exception? = null
            
            for (attempt in 1..maxRetries) {
                try {
                    _connectionState.value = BluetoothClientState.CONNECTING
                    Log.d(TAG, "Connecting to ${getSafeDeviceName(device)}... (attempt $attempt/$maxRetries)")
                    
                    // Cancel device discovery to speed up connection
                    bluetoothAdapter?.cancelDiscovery()
                    
                    // Wait a bit for discovery cancellation to take effect
                    delay(200)
                    
                    // Close previous socket
                    closeSocket()
                    
                    // Try multiple socket creation methods
                    socket = createSocket(device, attempt)
                    
                    if (socket == null) {
                        throw IOException("Failed to create socket")
                    }
                    
                    // Connect with timeout handling
                    Log.d(TAG, "Attempting socket connection...")
                    socket?.connect()
                    
                    // Verify connection is established
                    if (socket?.isConnected != true) {
                        throw IOException("Socket not connected after connect() call")
                    }
                    
                    inputStream = socket?.inputStream
                    outputStream = socket?.outputStream
                    
                    _connectionState.value = BluetoothClientState.CONNECTED
                    _connectedDeviceName.value = getSafeDeviceName(device)
                    lastConnectedDevice = device
                    missedHeartbeatCount = 0
                    
                    Log.d(TAG, "Connected to ${getSafeDeviceName(device)}")
                    
                    // Start reading messages
                    startReading()
                    
                    // Start heartbeat to keep connection alive
                    startHeartbeat()
                    return@launch // Connection successful, exit
                    
                } catch (e: Exception) {
                    Log.e(TAG, "Connection attempt $attempt failed: ${e.message}", e)
                    lastException = e
                    
                    // Explicitly close socket and wait for system to release resources
                    closeSocket()
                    
                    if (attempt < maxRetries) {
                        // Progressive backoff: longer delays for more failed attempts
                        // This gives the Bluetooth stack time to clean up resources
                        val delayMs = 1500L + (attempt * 1000L)  // 2.5s, 3.5s, 4.5s...
                        Log.d(TAG, "Connection failed. Waiting ${delayMs}ms before retry $attempt/$maxRetries...")
                        delay(delayMs)
                        
                        // Cancel any pending discovery operations before retry
                        try {
                            bluetoothAdapter?.cancelDiscovery()
                            delay(300)  // Brief pause for cancellation to take effect
                        } catch (e2: Exception) {
                            Log.w(TAG, "Failed to cancel discovery: ${e2.message}")
                        }
                    }
                }
            }
            
            // All retries failed
            Log.e(TAG, "All connection attempts failed")
            _connectionState.value = BluetoothClientState.DISCONNECTED
        }
    }
    
    /**
     * Create socket using different methods based on attempt number
     * Prioritizes UUID-based methods (proper SDP lookup) for reliable channel discovery
     * Falls back to direct channel methods only if UUID methods fail
     */
    @SuppressLint("MissingPermission")
    private fun createSocket(device: BluetoothDevice, attempt: Int): BluetoothSocket? {
        // Prioritize UUID-based methods (uses SDP to find correct channel)
        // Direct channel methods are unreliable after reconnection
        
        return when (attempt) {
            1 -> {
                // Method 1: Insecure RFCOMM with UUID (matches server's insecure mode)
                // This uses SDP to discover the correct channel dynamically
                Log.d(TAG, "Attempt 1: Using createInsecureRfcommSocketToServiceRecord (UUID-based)")
                try {
                    device.createInsecureRfcommSocketToServiceRecord(SERVICE_UUID)
                } catch (e: Exception) {
                    Log.w(TAG, "Insecure UUID method failed: ${e.message}")
                    null
                }
            }
            2 -> {
                // Method 2: Standard secure RFCOMM with UUID
                Log.d(TAG, "Attempt 2: Using createRfcommSocketToServiceRecord (UUID-based)")
                try {
                    device.createRfcommSocketToServiceRecord(SERVICE_UUID)
                } catch (e: Exception) {
                    Log.w(TAG, "Secure UUID method failed: ${e.message}")
                    null
                }
            }
            3 -> {
                // Method 3: Direct channel 4 (fallback if UUID methods fail)
                Log.d(TAG, "Attempt 3: Using reflection with channel 4")
                tryReflectionChannel(device, 4)
            }
            4 -> {
                // Method 4: Direct channel 1 (common fallback)
                Log.d(TAG, "Attempt 4: Using reflection with channel 1")
                tryReflectionChannel(device, 1)
            }
            else -> {
                // Method 5: Try insecure direct channel
                Log.d(TAG, "Attempt 5: Using createInsecureRfcommSocket with channel 4")
                try {
                    val method = device.javaClass.getMethod("createInsecureRfcommSocket", Int::class.java)
                    method.invoke(device, 4) as BluetoothSocket
                } catch (e: Exception) {
                    Log.w(TAG, "Insecure reflection method failed: ${e.message}")
                    // Last resort: try channel 1 insecure
                    try {
                        val method = device.javaClass.getMethod("createInsecureRfcommSocket", Int::class.java)
                        method.invoke(device, 1) as BluetoothSocket
                    } catch (e2: Exception) {
                        Log.w(TAG, "All connection methods exhausted")
                        null
                    }
                }
            }
        }
    }
    
    /**
     * Try to create socket using reflection with specific channel
     */
    private fun tryReflectionChannel(device: BluetoothDevice, channel: Int): BluetoothSocket? {
        return try {
            val method = device.javaClass.getMethod("createRfcommSocket", Int::class.java)
            method.invoke(device, channel) as BluetoothSocket
        } catch (e: Exception) {
            Log.w(TAG, "Reflection method failed for channel $channel: ${e.message}")
            null
        }
    }
    
    /**
     * Connect by device address
     */
    @SuppressLint("MissingPermission")
    fun connectByAddress(address: String) {
        if (!hasBluetoothPermission()) {
            Log.e(TAG, "No Bluetooth permission")
            return
        }
        
        val device = bluetoothAdapter?.getRemoteDevice(address)
        if (device != null) {
            connect(device)
        } else {
            Log.e(TAG, "Device not found: $address")
        }
    }
    
    /**
     * Disconnect
     */
    fun disconnect() {
        Log.d(TAG, "Disconnecting...")
        
        connectJob?.cancel()
        readJob?.cancel()
        heartbeatJob?.cancel()
        
        closeSocket()
        
        _connectionState.value = BluetoothClientState.DISCONNECTED
        _connectedDeviceName.value = null
    }
    
    /**
     * Start heartbeat to keep connection alive
     * Sends HEARTBEAT message every 10 seconds
     * Detects connection loss if too many heartbeats go unanswered
     */
    private fun startHeartbeat() {
        heartbeatJob?.cancel()
        missedHeartbeatCount = 0
        
        heartbeatJob = scope.launch(Dispatchers.IO) {
            while (isActive && _connectionState.value == BluetoothClientState.CONNECTED) {
                delay(HEARTBEAT_INTERVAL)
                
                if (_connectionState.value == BluetoothClientState.CONNECTED) {
                    try {
                        // Increment missed heartbeat count before sending
                        // This will be reset to 0 when we receive HEARTBEAT_ACK
                        missedHeartbeatCount++
                        
                        val heartbeatMsg = Message(type = MessageType.HEARTBEAT)
                        sendMessage(heartbeatMsg)
                        Log.d(TAG, "Heartbeat sent (missed count: $missedHeartbeatCount)")
                        
                        // Check if too many heartbeats were missed
                        if (missedHeartbeatCount >= MAX_MISSED_HEARTBEATS) {
                            Log.w(TAG, "Too many missed heartbeats ($missedHeartbeatCount), connection may be dead")
                            // Trigger reconnection
                            handleConnectionLost()
                            break
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to send heartbeat: ${e.message}")
                    }
                }
            }
        }
    }
    
    /**
     * Called when HEARTBEAT_ACK is received
     * Resets the missed heartbeat counter
     */
    fun onHeartbeatAckReceived() {
        missedHeartbeatCount = 0
    }
    
    /**
     * Handle connection lost (too many missed heartbeats)
     * Attempts to reconnect to the last connected device
     */
    private suspend fun handleConnectionLost() {
        Log.d(TAG, "Handling connection lost...")
        
        val deviceToReconnect = lastConnectedDevice
        
        // Close current connection
        closeSocket()
        _connectionState.value = BluetoothClientState.DISCONNECTED
        
        // Try to reconnect if we have a device
        if (deviceToReconnect != null) {
            Log.d(TAG, "Attempting to reconnect to ${getSafeDeviceName(deviceToReconnect)}...")
            delay(1000) // Wait a bit before reconnecting
            connect(deviceToReconnect)
        }
    }
    
    /**
     * Send message
     */
    suspend fun sendMessage(message: Message): Boolean {
        if (_connectionState.value != BluetoothClientState.CONNECTED) {
            Log.w(TAG, "Not connected, cannot send message")
            return false
        }
        
        return withContext(Dispatchers.IO) {
            try {
                val json = message.toJson()
                val data = (json + MESSAGE_DELIMITER).toByteArray(Charsets.UTF_8)
                
                outputStream?.write(data)
                outputStream?.flush()
                
                Log.d(TAG, "Sent message: ${message.type}")
                true
            } catch (e: IOException) {
                Log.e(TAG, "Failed to send message", e)
                handleDisconnection()
                false
            }
        }
    }
    
    /**
     * Send voice start signal
     */
    suspend fun sendVoiceStart(): Boolean {
        return sendMessage(Message(type = MessageType.VOICE_START))
    }
    
    /**
     * Send voice data (with complete audio)
     */
    suspend fun sendVoiceEnd(audioData: ByteArray): Boolean {
        val message = Message(
            type = MessageType.VOICE_END,
            binaryData = audioData
        )
        return sendMessage(message)
    }
    
    /**
     * Start reading messages
     */
    private fun startReading() {
        readJob?.cancel()
        readJob = scope.launch(Dispatchers.IO) {
            val buffer = StringBuilder()
            val readBuffer = ByteArray(4096)
            
            while (isActive && _connectionState.value == BluetoothClientState.CONNECTED) {
                try {
                    val bytesRead = inputStream?.read(readBuffer) ?: -1
                    
                    if (bytesRead == -1) {
                        Log.d(TAG, "Stream closed")
                        break
                    }
                    
                    val received = String(readBuffer, 0, bytesRead, Charsets.UTF_8)
                    buffer.append(received)
                    
                    // Process complete messages
                    var delimiterIndex: Int
                    while (buffer.indexOf(MESSAGE_DELIMITER).also { delimiterIndex = it } >= 0) {
                        val messageStr = buffer.substring(0, delimiterIndex)
                        buffer.delete(0, delimiterIndex + MESSAGE_DELIMITER.length)
                        
                        if (messageStr.isNotBlank()) {
                            parseAndEmitMessage(messageStr)
                        }
                    }
                    
                } catch (e: IOException) {
                    Log.e(TAG, "Read error", e)
                    break
                }
            }
            
            handleDisconnection()
        }
    }
    
    /**
     * Parse and emit message
     */
    private suspend fun parseAndEmitMessage(jsonStr: String) {
        // Skip non-JSON data (binary photo transfer ACKs, etc.)
        val trimmed = jsonStr.trim()
        
        // Try to find JSON object in the string (may have binary prefix)
        val jsonStart = trimmed.indexOf('{')
        if (jsonStart < 0) {
            // No JSON found, silently skip (likely binary data)
            return
        }
        
        val jsonContent = if (jsonStart > 0) {
            // Extract JSON part, discard binary prefix
            trimmed.substring(jsonStart)
        } else {
            trimmed
        }
        
        try {
            val json = JSONObject(jsonContent)
            val typeValue = json.optInt("type", -1)
            val payload = if (json.has("payload")) json.getString("payload") else null
            
            // Handle binaryData (Base64 encoded)
            val binaryData = if (json.has("binaryData")) {
                try {
                    android.util.Base64.decode(json.getString("binaryData"), android.util.Base64.DEFAULT)
                } catch (e: Exception) {
                    null
                }
            } else null
            
            val type = MessageType.fromCode(typeValue)
            if (type != null) {
                // Handle HEARTBEAT_ACK internally to reset missed heartbeat counter
                if (type == MessageType.HEARTBEAT_ACK) {
                    onHeartbeatAckReceived()
                }
                
                val message = Message(
                    type = type,
                    payload = payload,
                    binaryData = binaryData
                )
                
                Log.d(TAG, "Received message: $type, payload: $payload")
                _messageFlow.emit(message)
            }
            
        } catch (e: Exception) {
            // Only log if it looked like JSON but failed to parse
            if (jsonContent.length < 500) {
                Log.w(TAG, "Failed to parse JSON message: ${jsonContent.take(100)}")
            } else {
                Log.w(TAG, "Failed to parse large message (${jsonContent.length} chars)")
            }
        }
    }
    
    /**
     * Handle disconnection
     */
    private suspend fun handleDisconnection() {
        if (_connectionState.value == BluetoothClientState.DISCONNECTED) return
        
        Log.d(TAG, "Handling disconnection...")
        closeSocket()
        
        _connectionState.value = BluetoothClientState.DISCONNECTED
        _connectedDeviceName.value = null
    }
    
    /**
     * Close socket and release all resources
     * Ensures proper cleanup to avoid "socket might closed" errors
     */
    private fun closeSocket() {
        Log.d(TAG, "Closing socket and releasing resources...")
        
        // Close streams first (order matters)
        try {
            outputStream?.flush()  // Flush any pending data
        } catch (e: Exception) {
            Log.w(TAG, "Failed to flush output stream: ${e.message}")
        }
        
        try {
            inputStream?.close()
        } catch (e: IOException) {
            Log.w(TAG, "Error closing input stream: ${e.message}")
        }
        
        try {
            outputStream?.close()
        } catch (e: IOException) {
            Log.w(TAG, "Error closing output stream: ${e.message}")
        }
        
        // Close socket last
        try {
            socket?.close()
        } catch (e: IOException) {
            Log.w(TAG, "Error closing socket: ${e.message}")
        }
        
        // Clear references
        inputStream = null
        outputStream = null
        socket = null
        
        Log.d(TAG, "Socket closed and resources released")
    }
    
    /**
     * Check Bluetooth permission
     */
    private fun hasBluetoothPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ActivityCompat.checkSelfPermission(
                context,
                Manifest.permission.BLUETOOTH_CONNECT
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true // On Android 11 and below, BLUETOOTH permissions are install-time
        }
    }
    
    /**
     * Check if Bluetooth is enabled
     */
    fun isBluetoothEnabled(): Boolean {
        return bluetoothAdapter?.isEnabled == true
    }
    
    /**
     * Get the underlying Bluetooth socket for photo transfer.
     * Returns null if not connected.
     */
    fun getSocket(): BluetoothSocket? {
        return if (_connectionState.value == BluetoothClientState.CONNECTED) {
            socket
        } else {
            null
        }
    }
}
