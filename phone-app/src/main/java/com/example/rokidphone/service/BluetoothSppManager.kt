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
import com.example.rokidcommon.protocol.photo.PhotoTransferConstants
import com.example.rokidcommon.protocol.photo.PhotoTransferState
import com.example.rokidphone.service.photo.BluetoothPhotoReceiver
import com.example.rokidphone.service.photo.ReceivedPhoto
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.IOException
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
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
        
        // Binary packet header bytes (photo transfer protocol)
        private val PHOTO_PACKET_TYPES = setOf<Byte>(
            PhotoTransferConstants.PACKET_TYPE_START,
            PhotoTransferConstants.PACKET_TYPE_DATA,
            PhotoTransferConstants.PACKET_TYPE_END
        )
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
    
    // Connected BluetoothDevice (for CXR SDK initialization)
    private var _connectedDevice: BluetoothDevice? = null
    val connectedDevice: BluetoothDevice? get() = _connectedDevice

    // Audio buffer - collect fragmented audio data
    private val audioBuffer = mutableListOf<ByteArray>()
    
    // Photo receiver for handling chunked photo transfer
    private val photoReceiver = BluetoothPhotoReceiver(scope)
    
    // Photo transfer state
    val photoTransferState: StateFlow<PhotoTransferState> = photoReceiver.transferState
    
    // Received photos (emitted when a complete photo is received)
    val receivedPhoto: SharedFlow<ReceivedPhoto> = photoReceiver.receivedPhoto
    
    // Binary packet buffer for photo transfer (use ByteArrayOutputStream for efficiency)
    private var binaryBuffer = ByteArrayOutputStream(8192)
    private var expectedPacketLength: Int = 0
    private var parsingBinaryPacket = false
    
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
        
        // Set output stream for photo receiver (for ACK/RETRY responses)
        photoReceiver.setOutputStream(outputStream)
        
        try {
            _connectedDevice = socket.remoteDevice
            _connectedDeviceName.value = socket.remoteDevice.name
        } catch (e: SecurityException) {
            _connectedDevice = socket.remoteDevice
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
            var offset = 0
            while (offset < data.size) {
                // Check if we're continuing to parse a binary photo packet
                if (parsingBinaryPacket) {
                    val remaining = expectedPacketLength - binaryBuffer.size()
                    val bytesToRead = minOf(remaining, data.size - offset)
                    // Write bytes in bulk (much more efficient than byte-by-byte)
                    binaryBuffer.write(data, offset, bytesToRead)
                    offset += bytesToRead
                    
                    if (binaryBuffer.size() >= expectedPacketLength) {
                        // Complete binary packet received
                        val packet = binaryBuffer.toByteArray()
                        binaryBuffer.reset()
                        parsingBinaryPacket = false
                        expectedPacketLength = 0
                        
                        // Process photo packet
                        photoReceiver.processPacket(packet)
                    }
                    continue
                }
                
                // Check first byte to determine if this is a binary photo packet
                val firstByte = data[offset]
                
                if (firstByte in PHOTO_PACKET_TYPES) {
                    // This is a binary photo packet
                    val packetLength = getPacketLength(firstByte, data, offset)
                    
                    if (packetLength > 0) {
                        // Start collecting binary packet
                        binaryBuffer.reset()
                        expectedPacketLength = packetLength
                        parsingBinaryPacket = true
                        
                        val bytesAvailable = data.size - offset
                        val bytesToRead = minOf(packetLength, bytesAvailable)
                        
                        // Write bytes in bulk (much more efficient than byte-by-byte)
                        binaryBuffer.write(data, offset, bytesToRead)
                        offset += bytesToRead
                        
                        if (binaryBuffer.size() >= packetLength) {
                            // Complete packet in this buffer
                            val packet = binaryBuffer.toByteArray()
                            binaryBuffer.reset()
                            parsingBinaryPacket = false
                            expectedPacketLength = 0
                            
                            photoReceiver.processPacket(packet)
                        }
                        continue
                    }
                }
                
                // Regular JSON message processing
                val text = String(data, offset, data.size - offset, Charsets.UTF_8)
                offset = data.size // Consumed all remaining bytes
                messageBuffer.append(text)
            }
            
            // Find complete JSON messages (ending with newline)
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
     * Determines the expected length of a binary photo packet.
     * Returns 0 if length cannot be determined from available data.
     */
    private fun getPacketLength(packetType: Byte, data: ByteArray, offset: Int): Int {
        return when (packetType) {
            PhotoTransferConstants.PACKET_TYPE_START -> {
                // START: [Type:1][TotalSize:4][TotalChunks:4][MD5:16] = 25 bytes
                PhotoTransferConstants.START_PACKET_SIZE
            }
            PhotoTransferConstants.PACKET_TYPE_DATA -> {
                // DATA: [Type:1][DataLength:2][ChunkIndex:4][CRC32:4][Payload:n]
                // We need at least 3 bytes to read DataLength
                if (offset + 3 <= data.size) {
                    val dataLength = ByteBuffer.wrap(data, offset + 1, 2)
                        .order(ByteOrder.BIG_ENDIAN)
                        .short.toInt() and 0xFFFF
                    PhotoTransferConstants.DATA_HEADER_SIZE + dataLength
                } else {
                    // Not enough data to determine length, assume minimum header
                    PhotoTransferConstants.DATA_HEADER_SIZE
                }
            }
            PhotoTransferConstants.PACKET_TYPE_END -> {
                // END: [Type:1][Status:1] = 2 bytes
                PhotoTransferConstants.END_PACKET_SIZE
            }
            else -> 0
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
        
        Log.d(TAG, "Handling disconnection from read thread...")
        
        // Reset photo receiver
        photoReceiver.setOutputStream(null)
        binaryBuffer.reset()
        parsingBinaryPacket = false
        expectedPacketLength = 0
        
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
        _connectedDevice = null
        _connectedDeviceName.value = null
        
        audioBuffer.clear()
        
        // Reset flag before restarting (important!)
        synchronized(disconnectLock) {
            isDisconnecting = false
        }
        
        // Restart listening with delay to ensure socket is fully closed
        scope.launch(Dispatchers.IO) {
            delay(500) // Wait for socket cleanup
            Log.d(TAG, "Restarting Bluetooth server after disconnection...")
            stopListening()
            delay(200) // Small delay between stop and start
            startListening()
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
        
        Log.d(TAG, "Disconnecting... (restartListening=$restartListening)")
        
        // Set state first to stop read thread
        _connectionState.value = BluetoothConnectionState.DISCONNECTED
        
        readJob?.cancel()
        readJob = null
        
        // Reset photo receiver
        photoReceiver.setOutputStream(null)
        binaryBuffer.reset()
        parsingBinaryPacket = false
        expectedPacketLength = 0
        
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
        
        _connectedDevice = null
        _connectedDeviceName.value = null
        
        audioBuffer.clear()
        
        // Reset flag before restarting (important!)
        synchronized(disconnectLock) {
            isDisconnecting = false
        }
        
        // Stop old server socket and restart listening with delay
        if (restartListening) {
            scope.launch(Dispatchers.IO) {
                delay(500) // Wait for socket cleanup
                stopListening()
                delay(200) // Small delay between stop and start
                Log.d(TAG, "Restarting Bluetooth server after disconnect...")
                startListening()
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
