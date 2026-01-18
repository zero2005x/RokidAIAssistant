package com.example.rokidphone.service

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.ActivityCompat
import com.example.rokidcommon.protocol.Message
import com.example.rokidcommon.protocol.MessageType
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

/**
 * Bluetooth connection state
 */
enum class BluetoothConnectionState {
    DISCONNECTED,
    LISTENING,
    CONNECTING,
    CONNECTED
}

/**
 * Bluetooth SPP Manager
 * Uses Classic Bluetooth Serial Port Profile for communication between glasses and phone
 */
class BluetoothSppManager(
    private val context: Context,
    private val scope: CoroutineScope
) {
    companion object {
        private const val TAG = "BluetoothSppManager"
        private const val SERVICE_NAME = "RokidAIAssistant"
        // SPP UUID
        private val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
        // Custom UUID (to identify our application)
        private val APP_UUID: UUID = UUID.fromString("a1b2c3d4-e5f6-7890-abcd-ef1234567890")
        
        private const val BUFFER_SIZE = 8192
    }
    
    private val bluetoothAdapter: BluetoothAdapter? = BluetoothAdapter.getDefaultAdapter()
    
    private var serverSocket: BluetoothServerSocket? = null
    private var clientSocket: BluetoothSocket? = null
    private var inputStream: InputStream? = null
    private var outputStream: OutputStream? = null
    
    private var acceptJob: Job? = null
    private var readJob: Job? = null
    
    // Connection state
    private val _connectionState = MutableStateFlow(BluetoothConnectionState.DISCONNECTED)
    val connectionState: StateFlow<BluetoothConnectionState> = _connectionState.asStateFlow()
    
    // Received messages
    private val _messageFlow = MutableSharedFlow<Message>(replay = 0, extraBufferCapacity = 100)
    val messageFlow: SharedFlow<Message> = _messageFlow.asSharedFlow()
    
    // Connected device name
    private val _connectedDeviceName = MutableStateFlow<String?>(null)
    val connectedDeviceName: StateFlow<String?> = _connectedDeviceName.asStateFlow()
    
    // Audio buffer - collect fragmented audio data
    private val audioBuffer = mutableListOf<ByteArray>()
    
    // Flag to prevent duplicate disconnect
    @Volatile
    private var isDisconnecting = false
    private val disconnectLock = Any()

    /**
     * Check Bluetooth permission
     */
    fun hasBluetoothPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ActivityCompat.checkSelfPermission(
                context, Manifest.permission.BLUETOOTH_CONNECT
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
     * Start listening for connections (as server)
     * Uses insecure RFCOMM for better compatibility with various devices
     */
    fun startListening() {
        if (!hasBluetoothPermission()) {
            Log.e(TAG, "Missing Bluetooth permission")
            return
        }
        
        if (bluetoothAdapter == null) {
            Log.e(TAG, "Bluetooth not supported")
            return
        }
        
        stopListening()
        
        acceptJob = scope.launch(Dispatchers.IO) {
            try {
                _connectionState.value = BluetoothConnectionState.LISTENING
                Log.d(TAG, "Starting Bluetooth server...")
                
                // Use insecure RFCOMM for better compatibility
                // This works better across different Android versions and devices
                serverSocket = try {
                    bluetoothAdapter.listenUsingInsecureRfcommWithServiceRecord(
                        SERVICE_NAME, APP_UUID
                    ).also {
                        Log.d(TAG, "Server socket created (insecure mode for compatibility)")
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Insecure socket failed, trying secure: ${e.message}")
                    bluetoothAdapter.listenUsingRfcommWithServiceRecord(
                        SERVICE_NAME, APP_UUID
                    ).also {
                        Log.d(TAG, "Server socket created (secure mode)")
                    }
                }
                
                Log.d(TAG, "Waiting for connection...")
                
                // Wait for connection
                val socket = serverSocket?.accept()
                
                if (socket != null) {
                    Log.d(TAG, "Connection accepted from: ${socket.remoteDevice.name}")
                    handleConnection(socket)
                }
                
            } catch (e: SecurityException) {
                Log.e(TAG, "Security exception", e)
                _connectionState.value = BluetoothConnectionState.DISCONNECTED
            } catch (e: IOException) {
                if (_connectionState.value != BluetoothConnectionState.DISCONNECTED) {
                    Log.e(TAG, "Accept failed", e)
                    _connectionState.value = BluetoothConnectionState.DISCONNECTED
                }
            }
        }
    }
    
    /**
     * Connect to specified device (as client)
     */
    fun connectToDevice(device: BluetoothDevice) {
        if (!hasBluetoothPermission()) {
            Log.e(TAG, "Missing Bluetooth permission")
            return
        }
        
        disconnect(restartListening = false)
        
        scope.launch(Dispatchers.IO) {
            try {
                _connectionState.value = BluetoothConnectionState.CONNECTING
                Log.d(TAG, "Connecting to: ${device.name}")
                
                val socket = device.createRfcommSocketToServiceRecord(APP_UUID)
                
                // Cancel discovery to speed up connection
                bluetoothAdapter?.cancelDiscovery()
                
                socket.connect()
                
                Log.d(TAG, "Connected to: ${device.name}")
                handleConnection(socket)
                
            } catch (e: SecurityException) {
                Log.e(TAG, "Security exception", e)
                _connectionState.value = BluetoothConnectionState.DISCONNECTED
            } catch (e: IOException) {
                Log.e(TAG, "Connection failed", e)
                _connectionState.value = BluetoothConnectionState.DISCONNECTED
            }
        }
    }
    
    private suspend fun handleConnection(socket: BluetoothSocket) {
        clientSocket = socket
        inputStream = socket.inputStream
        outputStream = socket.outputStream
        
        try {
            _connectedDeviceName.value = socket.remoteDevice.name
        } catch (e: SecurityException) {
            _connectedDeviceName.value = "Unknown device"
        }
        
        _connectionState.value = BluetoothConnectionState.CONNECTED
        Log.d(TAG, "Connection established")
        
        // Start reading data
        startReading()
    }
    
    private fun startReading() {
        readJob = scope.launch(Dispatchers.IO) {
            val buffer = ByteArray(BUFFER_SIZE)
            val messageBuffer = StringBuilder()
            
            try {
                while (isActive && _connectionState.value == BluetoothConnectionState.CONNECTED) {
                    val bytesRead = inputStream?.read(buffer) ?: -1
                    
                    if (bytesRead == -1) {
                        Log.d(TAG, "Connection closed by remote")
                        break
                    }
                    
                    if (bytesRead > 0) {
                        // Process received data
                        val data = buffer.copyOf(bytesRead)
                        processReceivedData(data, messageBuffer)
                    }
                }
            } catch (e: IOException) {
                // Only log error if not actively disconnecting
                if (_connectionState.value == BluetoothConnectionState.CONNECTED) {
                    Log.e(TAG, "Read error", e)
                }
            } finally {
                // Only call disconnect if still connected (avoid duplicate calls)
                if (_connectionState.value == BluetoothConnectionState.CONNECTED) {
                    handleDisconnection()
                }
            }
        }
    }
    
    private suspend fun processReceivedData(data: ByteArray, messageBuffer: StringBuilder) {
        try {
            // Simplified protocol: JSON + newline separator
            val text = String(data, Charsets.UTF_8)
            messageBuffer.append(text)
            
            // Find complete messages (ending with newline)
            var newlineIndex: Int
            while (messageBuffer.indexOf("\n").also { newlineIndex = it } != -1) {
                val messageJson = messageBuffer.substring(0, newlineIndex)
                messageBuffer.delete(0, newlineIndex + 1)
                
                if (messageJson.isNotEmpty()) {
                    try {
                        val message = Message.fromJson(messageJson)
                        if (message == null) {
                            Log.e(TAG, "Failed to parse message: $messageJson")
                            continue
                        }
                        Log.d(TAG, "Received message: ${message.type}")
                        
                        // Process messages
                        when (message.type) {
                            MessageType.HEARTBEAT -> {
                                // Respond to heartbeat to keep connection alive
                                Log.d(TAG, "Heartbeat received, sending ACK")
                                scope.launch {
                                    sendMessage(Message(type = MessageType.HEARTBEAT_ACK))
                                }
                            }
                            MessageType.VOICE_START -> {
                                audioBuffer.clear()
                                Log.d(TAG, "Voice recording started")
                            }
                            MessageType.VOICE_DATA -> {
                                message.binaryData?.let { audioBuffer.add(it) }
                            }
                            MessageType.VOICE_END -> {
                                // Check if VOICE_END message contains audio data directly
                                val messageBinaryData = message.binaryData
                                val fullAudio = if (messageBinaryData != null && messageBinaryData.isNotEmpty()) {
                                    // Use audio data from VOICE_END message
                                    Log.d(TAG, "Using audio from VOICE_END message: ${messageBinaryData.size} bytes")
                                    messageBinaryData
                                } else {
                                    // Use accumulated audio data
                                    audioBuffer.flatMap { it.toList() }.toByteArray()
                                }
                                audioBuffer.clear()
                                Log.d(TAG, "Voice recording ended, total: ${fullAudio.size} bytes")
                                
                                _messageFlow.emit(Message(
                                    type = MessageType.VOICE_END,
                                    binaryData = fullAudio
                                ))
                            }
                            else -> {
                                _messageFlow.emit(message)
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to parse message: $messageJson", e)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error processing data", e)
        }
    }
    
    /**
     * Send message
     */
    suspend fun sendMessage(message: Message): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                if (_connectionState.value != BluetoothConnectionState.CONNECTED) {
                    Log.w(TAG, "Not connected, cannot send message")
                    return@withContext false
                }
                
                val json = message.toJson() + "\n"
                outputStream?.write(json.toByteArray(Charsets.UTF_8))
                outputStream?.flush()
                
                Log.d(TAG, "Sent message: ${message.type}")
                true
            } catch (e: IOException) {
                Log.e(TAG, "Send failed", e)
                disconnect()
                false
            }
        }
    }
    
    /**
     * Stop listening
     */
    fun stopListening() {
        acceptJob?.cancel()
        acceptJob = null
        
        try {
            serverSocket?.close()
        } catch (e: IOException) {
            Log.e(TAG, "Error closing server socket", e)
        }
        serverSocket = null
    }
    
    /**
     * Handle disconnection from read thread (internal use)
     */
    private fun handleDisconnection() {
        synchronized(disconnectLock) {
            if (isDisconnecting) {
                Log.d(TAG, "Already disconnecting, skipping...")
                return
            }
            isDisconnecting = true
        }
        
        try {
            Log.d(TAG, "Handling disconnection from read thread...")
            
            // Close client connection
            try {
                inputStream?.close()
                outputStream?.close()
                clientSocket?.close()
            } catch (e: IOException) {
                Log.e(TAG, "Error closing connection", e)
            }
            
            inputStream = null
            outputStream = null
            clientSocket = null
            
            _connectionState.value = BluetoothConnectionState.DISCONNECTED
            _connectedDeviceName.value = null
            
            audioBuffer.clear()
            
            // Restart listening
            stopListening()
            Log.d(TAG, "Restarting Bluetooth server...")
            startListening()
        } finally {
            synchronized(disconnectLock) {
                isDisconnecting = false
            }
        }
    }
    
    /**
     * Disconnect
     * @param restartListening Whether to restart listening after disconnect (default true)
     */
    fun disconnect(restartListening: Boolean = true) {
        synchronized(disconnectLock) {
            if (isDisconnecting) {
                Log.d(TAG, "Already disconnecting, skipping...")
                return
            }
            isDisconnecting = true
        }
        
        try {
            Log.d(TAG, "Disconnecting... (restartListening=$restartListening)")
            
            // Set state first to stop read thread
            _connectionState.value = BluetoothConnectionState.DISCONNECTED
            
            readJob?.cancel()
            readJob = null
            
            // Close client connection
            try {
                inputStream?.close()
                outputStream?.close()
                clientSocket?.close()
            } catch (e: IOException) {
                Log.e(TAG, "Error closing connection", e)
            }
            
            inputStream = null
            outputStream = null
            clientSocket = null
            
            _connectedDeviceName.value = null
            
            audioBuffer.clear()
            
            // Stop old server socket and restart listening
            if (restartListening) {
                stopListening()
                Log.d(TAG, "Restarting Bluetooth server after disconnect...")
                startListening()
            }
        } finally {
            synchronized(disconnectLock) {
                isDisconnecting = false
            }
        }
    }
    
    /**
     * Get paired devices list
     */
    fun getPairedDevices(): List<BluetoothDevice> {
        if (!hasBluetoothPermission()) return emptyList()
        
        return try {
            bluetoothAdapter?.bondedDevices?.toList() ?: emptyList()
        } catch (e: SecurityException) {
            Log.e(TAG, "Security exception getting paired devices", e)
            emptyList()
        }
    }
}
