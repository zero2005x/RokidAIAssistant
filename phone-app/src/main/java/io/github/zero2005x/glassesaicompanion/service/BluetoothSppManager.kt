package io.github.zero2005x.glassesaicompanion.service

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
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
import io.github.zero2005x.glassesaicompanion.service.photo.BluetoothPhotoReceiver
import io.github.zero2005x.glassesaicompanion.service.photo.ReceivedPhoto
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
        // Custom UUID (to identify our application)
        private val APP_UUID: UUID = UUID.fromString("a1b2c3d4-e5f6-7890-abcd-ef1234567890")
        
        private const val BUFFER_SIZE = 8192
        private const val MAX_VOICE_BYTES = 16 * 1024 * 1024
        
        // JSON message terminator on the text channel
        private const val NEWLINE_BYTE: Byte = 0x0A // '\n'
        
        // Bytes needed to read a DATA packet's payload length: [Type:1][DataLength:2]
        private const val DATA_LENGTH_PREFIX_SIZE = 3
        
        // Binary packet header bytes (photo transfer protocol)
        private val PHOTO_PACKET_TYPES = setOf<Byte>(
            PhotoTransferConstants.PACKET_TYPE_START,
            PhotoTransferConstants.PACKET_TYPE_DATA,
            PhotoTransferConstants.PACKET_TYPE_END
        )
    }
    
    private val bluetoothAdapter: BluetoothAdapter? =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
    
    // Mutated from the accept job, read job, sendMessage coroutines and
    // disconnect() on arbitrary threads: @Volatile gives visibility guarantees.
    @Volatile
    private var serverSocket: BluetoothServerSocket? = null
    @Volatile
    private var clientSocket: BluetoothSocket? = null
    @Volatile
    private var inputStream: InputStream? = null
    @Volatile
    private var outputStream: OutputStream? = null
    
    private var acceptJob: Job? = null
    private var readJob: Job? = null
    private val writeMutex = Mutex()
    
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
    private var audioBufferBytes = 0
    private var audioOverflowed = false
    
    // Photo receiver for handling chunked photo transfer
    private val photoReceiver = BluetoothPhotoReceiver(scope) { packet ->
        writeBytes(packet)
    }
    
    // Photo transfer state
    val photoTransferState: StateFlow<PhotoTransferState> = photoReceiver.transferState
    
    // Received photos (emitted when a complete photo is received)
    val receivedPhoto: SharedFlow<ReceivedPhoto> = photoReceiver.receivedPhoto
    
    // Binary packet buffer for photo transfer (use ByteArrayOutputStream for efficiency)
    private val binaryBuffer = ByteArrayOutputStream(BUFFER_SIZE)
    @Volatile
    private var expectedPacketLength: Int = 0
    @Volatile
    private var parsingBinaryPacket = false
    
    // Flag to prevent duplicate disconnect
    @Volatile
    private var isDisconnecting = false
    private val disconnectLock = Any()
    
    // Serialises writes to outputStream; the photo receiver writes ACK/RETRY
    // frames to the same stream and must not interleave with JSON messages.
    private val writeLock = Any()

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
     * Continuously accepts reconnections when the current connection is lost
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
            while (isActive) {
                // Local reference: the finally block below must only ever close
                // the socket created by THIS iteration. Closing the field could
                // destroy a NEWER server socket created after this job was
                // cancelled by stopListening().
                var localServerSocket: BluetoothServerSocket? = null
                try {
                    _connectionState.value = BluetoothConnectionState.LISTENING
                    Log.d(TAG, "Starting Bluetooth server...")
                    
                    // Use insecure RFCOMM for better compatibility
                    // This works better across different Android versions and devices
                    localServerSocket = try {
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
                    serverSocket = localServerSocket
                    
                    Log.d(TAG, "Waiting for connection...")
                    
                    // Wait for connection
                    val socket = localServerSocket.accept()
                    
                    if (socket != null) {
                        Log.d(TAG, "Connection accepted from: ${safeRemoteDeviceName(socket)}")
                        try {
                            handleConnection(socket)
                        } catch (e: Exception) {
                            // Never leak an accepted socket on setup failure
                            Log.e(TAG, "Failed to set up accepted connection", e)
                            try {
                                socket.close()
                            } catch (closeError: IOException) {
                                Log.w(TAG, "Error closing failed socket: ${closeError.message}")
                            }
                            throw e
                        }
                        
                        // Wait for this connection to be disconnected before accepting new ones
                        // The read job will handle disconnection detection
                        readJob?.join()
                        
                        Log.d(TAG, "Connection ended, preparing to accept new connections...")
                        
                        // Wait for BT stack to fully release the socket resources
                        // before re-creating the server socket.
                        // The glasses client waits 2s before reconnecting, so 1s here is safe.
                        delay(1000)
                    }
                    
                } catch (e: SecurityException) {
                    Log.e(TAG, "Security exception", e)
                    _connectionState.value = BluetoothConnectionState.DISCONNECTED
                    break // Exit loop on security exception
                } catch (e: IOException) {
                    if (_connectionState.value != BluetoothConnectionState.DISCONNECTED) {
                        Log.e(TAG, "Accept failed: ${e.message}")
                        // Don't break, try to restart the server socket
                        delay(500)
                    } else {
                        // Intentional disconnect, exit the loop
                        break
                    }
                } catch (e: CancellationException) {
                    Log.d(TAG, "Accept job cancelled")
                    throw e
                } finally {
                    // Close only THIS iteration's server socket to free up the port
                    try {
                        localServerSocket?.close()
                    } catch (e: IOException) {
                        Log.w(TAG, "Error closing server socket: ${e.message}")
                    }
                    if (serverSocket === localServerSocket) {
                        serverSocket = null
                    }
                }
            }
            
            Log.d(TAG, "Accept loop exited")
        }
    }
    
    /**
     * Read the remote device name safely: it requires BLUETOOTH_CONNECT and can
     * throw SecurityException, which must not kill the accept loop.
     */
    private fun safeRemoteDeviceName(socket: BluetoothSocket): String {
        return try {
            socket.remoteDevice?.name ?: "Unknown device"
        } catch (e: SecurityException) {
            Log.w(TAG, "Cannot read remote device name (missing permission?)", e)
            "Unknown device"
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
        
        // A server accept and a client connect must never race to install their sockets.
        stopListening()
        disconnect(restartListening = false)
        
        scope.launch(Dispatchers.IO) {
            var socket: BluetoothSocket? = null
            var connectionEstablished = false
            try {
                _connectionState.value = BluetoothConnectionState.CONNECTING
                Log.d(TAG, "Connecting to: ${device.name}")
                
                val newSocket = device.createRfcommSocketToServiceRecord(APP_UUID)
                socket = newSocket
                
                // Cancel discovery to speed up connection
                bluetoothAdapter?.cancelDiscovery()
                
                newSocket.connect()
                
                Log.d(TAG, "Connected to: ${device.name}")
                handleConnection(newSocket)
                connectionEstablished = true
                
            } catch (e: CancellationException) {
                throw e
            } catch (e: SecurityException) {
                Log.e(TAG, "Security exception", e)
                _connectionState.value = BluetoothConnectionState.DISCONNECTED
            } catch (e: IOException) {
                Log.e(TAG, "Connection failed", e)
                _connectionState.value = BluetoothConnectionState.DISCONNECTED
            } finally {
                if (!connectionEstablished) {
                    try {
                        socket?.close()
                    } catch (e: IOException) {
                        Log.w(TAG, "Failed to close unsuccessful client socket", e)
                    }
                    if (isActive) {
                        startListening()
                    }
                }
            }
        }
    }
    
    private suspend fun handleConnection(socket: BluetoothSocket) {
        clientSocket = socket
        inputStream = socket.inputStream
        outputStream = socket.outputStream
        
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
                    // If the packet length is still unknown (a DATA header was
                    // split across reads), first buffer enough bytes to read it.
                    if (expectedPacketLength <= 0) {
                        val need = DATA_LENGTH_PREFIX_SIZE - binaryBuffer.size()
                        if (need > 0) {
                            val take = minOf(need, data.size - offset)
                            binaryBuffer.write(data, offset, take)
                            offset += take
                        }
                        if (binaryBuffer.size() < DATA_LENGTH_PREFIX_SIZE) {
                            continue // Still not enough bytes; wait for the next read
                        }
                        val header = binaryBuffer.toByteArray()
                        expectedPacketLength = getPacketLength(header[0], header, 0)
                        if (expectedPacketLength <= 0) {
                            Log.e(TAG, "Could not resolve binary packet length, dropping buffered bytes")
                            binaryBuffer.reset()
                            parsingBinaryPacket = false
                            continue
                        }
                    }
                    
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
                    
                    if (packetLength <= 0) {
                        // The DATA header is split across reads: park the
                        // available header bytes and wait for the rest instead
                        // of guessing a length and desynchronising the stream.
                        binaryBuffer.reset()
                        expectedPacketLength = 0
                        parsingBinaryPacket = true
                        val take = minOf(DATA_LENGTH_PREFIX_SIZE, data.size - offset)
                        binaryBuffer.write(data, offset, take)
                        offset += take
                        continue
                    }
                    
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
                
                // Regular JSON message processing: consume text only up to the
                // next newline (inclusive) or the start of a binary packet —
                // never blindly consume the rest of the buffer, because a binary
                // photo packet may follow a JSON message within a single read().
                var end = offset
                while (end < data.size &&
                    data[end] != NEWLINE_BYTE &&
                    !(end > offset && data[end] in PHOTO_PACKET_TYPES)
                ) {
                    end++
                }
                if (end < data.size && data[end] == NEWLINE_BYTE) {
                    end++ // Include the newline terminator
                }
                val text = String(data, offset, end - offset, Charsets.UTF_8)
                offset = end
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
                                audioBufferBytes = 0
                                audioOverflowed = false
                                Log.d(TAG, "Voice recording started")
                                // Emit to flow so service/UI can be notified
                                _messageFlow.emit(message)
                            }
                            MessageType.VOICE_DATA -> {
                                message.binaryData?.let { chunk ->
                                    if (!audioOverflowed && audioBufferBytes <= MAX_VOICE_BYTES - chunk.size) {
                                        audioBuffer.add(chunk)
                                        audioBufferBytes += chunk.size
                                    } else if (!audioOverflowed) {
                                        audioBuffer.clear()
                                        audioBufferBytes = 0
                                        audioOverflowed = true
                                        val error = Message(
                                            type = MessageType.SYSTEM_ERROR,
                                            payload = "Voice recording exceeded the size limit"
                                        )
                                        _messageFlow.emit(error)
                                        sendMessage(error)
                                    }
                                }
                            }
                            MessageType.VOICE_END -> {
                                if (audioOverflowed) {
                                    audioBuffer.clear()
                                    audioBufferBytes = 0
                                    audioOverflowed = false
                                    continue
                                }
                                // Check if VOICE_END message contains audio data directly
                                val messageBinaryData = message.binaryData
                                val fullAudio = if (messageBinaryData != null && messageBinaryData.isNotEmpty()) {
                                    // Use audio data from VOICE_END message
                                    Log.d(TAG, "Using audio from VOICE_END message: ${messageBinaryData.size} bytes")
                                    messageBinaryData
                                } else {
                                    // Use accumulated audio data (bulk copy, no per-byte boxing)
                                    ByteArrayOutputStream(audioBuffer.sumOf { it.size })
                                        .apply { audioBuffer.forEach { write(it) } }
                                        .toByteArray()
                                }
                                audioBuffer.clear()
                                audioBufferBytes = 0
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
     * Returns 0 if the length cannot be determined from the available data
     * (e.g. a DATA header split across reads) — the caller must keep the
     * partial bytes pending instead of assuming a length.
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
                if (offset + DATA_LENGTH_PREFIX_SIZE <= data.size) {
                    val dataLength = ByteBuffer.wrap(data, offset + 1, 2)
                        .order(ByteOrder.BIG_ENDIAN)
                        .short.toInt() and 0xFFFF
                    PhotoTransferConstants.DATA_HEADER_SIZE + dataLength
                } else {
                    // Not enough data to determine the length: report "unknown"
                    // so the caller keeps the partial header bytes pending.
                    0
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
        val json = message.toJson() + "\n"
        val sent = writeBytes(json.toByteArray(Charsets.UTF_8))
        if (sent) {
            Log.d(TAG, "Sent message: ${message.type}")
        }
        return sent
    }

    /** Serializes every write, including photo ACK/RETRY packets, on the RFCOMM stream. */
    private suspend fun writeBytes(bytes: ByteArray): Boolean = withContext(Dispatchers.IO) {
        writeMutex.withLock {
            if (_connectionState.value != BluetoothConnectionState.CONNECTED) {
                Log.w(TAG, "Not connected, cannot send data")
                return@withLock false
            }

            try {
                val stream = outputStream ?: return@withLock false
                stream.write(bytes)
                stream.flush()
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
     * The acceptJob loop will automatically accept new connections after readJob completes
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
        photoReceiver.reset()
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
        audioBufferBytes = 0
        audioOverflowed = false
        
        // Reset the flag - the acceptJob loop will automatically accept new connections
        // after readJob completes (readJob?.join() in startListening)
        synchronized(disconnectLock) {
            isDisconnecting = false
        }
        
        Log.d(TAG, "Disconnection handled, ready for reconnection")
    }
    
    /**
     * Disconnect
     * @param restartListening Whether to restart listening after disconnect (default true)
     */
    fun disconnect(restartListening: Boolean = true) {
        var ownsFlag = false
        val scheduleRestart = synchronized(disconnectLock) {
            if (isDisconnecting) {
                // A teardown is already in flight (e.g. from the read thread).
                // Do NOT drop this request: run our own (idempotent) teardown,
                // but leave the flag and the listener restart to the in-flight
                // operation so we don't schedule duplicate restarts.
                Log.d(TAG, "Disconnect already in progress; running teardown anyway")
                false
            } else {
                isDisconnecting = true
                ownsFlag = true
                restartListening
            }
        }
        
        Log.d(TAG, "Disconnecting... (restartListening=$restartListening)")
        
        // Set state first to stop read thread
        _connectionState.value = BluetoothConnectionState.DISCONNECTED
        
        readJob?.cancel()
        readJob = null
        
        // Reset photo receiver
        photoReceiver.reset()
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
        audioBufferBytes = 0
        audioOverflowed = false
        
        // Stop old server socket and restart listening with delay
        // Reset flag only AFTER restart completes to prevent race conditions
        if (scheduleRestart) {
            scope.launch(Dispatchers.IO) {
                try {
                    delay(500) // Wait for socket cleanup
                    Log.d(TAG, "Restarting Bluetooth server after disconnect...")
                    // startListening() stops the previous listener first
                    startListening()
                } finally {
                    // Reset flag after restart completes
                    synchronized(disconnectLock) {
                        isDisconnecting = false
                    }
                }
            }
        } else if (ownsFlag) {
            // Not restarting: reset the flag immediately (only the operation
            // that set the flag may clear it)
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
