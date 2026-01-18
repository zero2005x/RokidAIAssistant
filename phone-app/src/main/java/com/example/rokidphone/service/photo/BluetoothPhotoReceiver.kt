package com.example.rokidphone.service.photo

import android.util.Log
import com.example.rokidcommon.protocol.photo.PacketUtils
import com.example.rokidcommon.protocol.photo.PhotoTransferConstants
import com.example.rokidcommon.protocol.photo.PhotoTransferState
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.OutputStream

/**
 * Bluetooth Photo Receiver
 * 
 * Handles receiving chunked photo data from glasses over Bluetooth SPP.
 * Implements the photo transfer protocol with:
 * - Packet parsing and validation
 * - Chunk reassembly
 * - CRC32/MD5 verification
 * - ACK/RETRY responses
 * 
 * @param scope CoroutineScope for async operations
 */
class BluetoothPhotoReceiver(
    private val scope: CoroutineScope
) {
    companion object {
        private const val TAG = "BluetoothPhotoReceiver"
        
        // Timeout for receiving all chunks (30 seconds)
        private const val TRANSFER_TIMEOUT_MS = 30_000L
        
        // Timeout for individual chunk (5 seconds)
        private const val CHUNK_TIMEOUT_MS = 5_000L
    }
    
    // Current transfer state
    private val _transferState = MutableStateFlow<PhotoTransferState>(PhotoTransferState.Idle)
    val transferState: StateFlow<PhotoTransferState> = _transferState.asStateFlow()
    
    // Emits completed photos for processing
    private val _receivedPhoto = MutableSharedFlow<ReceivedPhoto>(replay = 0, extraBufferCapacity = 1)
    val receivedPhoto: SharedFlow<ReceivedPhoto> = _receivedPhoto.asSharedFlow()
    
    // Transfer session data
    private var currentSession: TransferSession? = null
    private var timeoutJob: Job? = null
    
    // Output stream for sending ACK/RETRY (set by BluetoothSppManager)
    private var outputStream: OutputStream? = null
    
    /**
     * Sets the output stream for sending responses.
     */
    fun setOutputStream(stream: OutputStream?) {
        outputStream = stream
        if (stream == null) {
            // Connection lost, reset state
            reset()
        }
    }
    
    /**
     * Processes a received photo packet.
     * Call this when photo-related data is received from glasses.
     * 
     * @param packet The raw packet data
     * @return true if packet was handled, false if not a photo packet
     */
    suspend fun processPacket(packet: ByteArray): Boolean {
        if (packet.isEmpty()) return false
        
        return when (packet[0]) {
            PhotoTransferConstants.PACKET_TYPE_START -> {
                handleStartPacket(packet)
                true
            }
            PhotoTransferConstants.PACKET_TYPE_DATA -> {
                handleDataPacket(packet)
                true
            }
            PhotoTransferConstants.PACKET_TYPE_END -> {
                handleEndPacket(packet)
                true
            }
            else -> false
        }
    }
    
    /**
     * Handles START packet - initializes a new transfer session.
     */
    private suspend fun handleStartPacket(packet: ByteArray) {
        try {
            val startData = PacketUtils.parseStartPacket(packet)
            
            Log.d(TAG, "Received START: size=${startData.totalSize}, chunks=${startData.totalChunks}, " +
                    "md5=${PacketUtils.md5ToHexString(startData.md5)}")
            
            // Check for existing session
            if (currentSession != null) {
                Log.w(TAG, "Aborting previous incomplete transfer")
                reset()
            }
            
            // Validate parameters
            if (startData.totalSize <= 0 || startData.totalSize > PhotoTransferConstants.MAX_PHOTO_SIZE) {
                Log.e(TAG, "Invalid photo size: ${startData.totalSize}")
                sendAck(0, PhotoTransferConstants.STATUS_ERROR)
                return
            }
            
            if (startData.totalChunks <= 0 || startData.totalChunks > PhotoTransferConstants.MAX_CHUNKS) {
                Log.e(TAG, "Invalid chunk count: ${startData.totalChunks}")
                sendAck(0, PhotoTransferConstants.STATUS_ERROR)
                return
            }
            
            // Create new session
            currentSession = TransferSession(
                totalSize = startData.totalSize,
                totalChunks = startData.totalChunks,
                expectedMd5 = startData.md5,
                receivedChunks = mutableMapOf(),
                startTime = System.currentTimeMillis()
            )
            
            _transferState.value = PhotoTransferState.InProgress(
                currentChunk = 0,
                totalChunks = startData.totalChunks,
                bytesTransferred = 0,
                totalBytes = startData.totalSize.toLong()
            )
            
            // Start transfer timeout
            startTransferTimeout()
            
            // Send ACK for START
            sendAck(0, PhotoTransferConstants.STATUS_SUCCESS)
            
            Log.d(TAG, "Transfer session started")
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse START packet", e)
            sendAck(0, PhotoTransferConstants.STATUS_ERROR)
        }
    }
    
    /**
     * Handles DATA packet - stores chunk and validates CRC.
     */
    private suspend fun handleDataPacket(packet: ByteArray) {
        val session = currentSession
        if (session == null) {
            Log.w(TAG, "Received DATA but no session active")
            return
        }
        
        try {
            val dataPacket = PacketUtils.parseDataPacket(packet)
            
            Log.d(TAG, "Received DATA: chunk=${dataPacket.chunkIndex}/${session.totalChunks}, " +
                    "size=${dataPacket.payload.size}, valid=${dataPacket.isValid}")
            
            // Validate chunk index
            if (dataPacket.chunkIndex < 0 || dataPacket.chunkIndex >= session.totalChunks) {
                Log.e(TAG, "Invalid chunk index: ${dataPacket.chunkIndex}")
                sendAck(dataPacket.chunkIndex, PhotoTransferConstants.STATUS_ERROR)
                return
            }
            
            // Verify CRC32
            if (!dataPacket.isValid) {
                Log.w(TAG, "CRC mismatch for chunk ${dataPacket.chunkIndex}")
                sendRetry(dataPacket.chunkIndex)
                return
            }
            
            // Store chunk
            session.receivedChunks[dataPacket.chunkIndex] = dataPacket.payload
            
            // Update progress
            val bytesReceived = session.receivedChunks.values.sumOf { it.size }.toLong()
            _transferState.value = PhotoTransferState.InProgress(
                currentChunk = session.receivedChunks.size,
                totalChunks = session.totalChunks,
                bytesTransferred = bytesReceived,
                totalBytes = session.totalSize.toLong()
            )
            
            // Reset chunk timeout
            resetChunkTimeout()
            
            // Send ACK
            sendAck(dataPacket.chunkIndex, PhotoTransferConstants.STATUS_SUCCESS)
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to process DATA packet", e)
        }
    }
    
    /**
     * Handles END packet - finalizes transfer and reassembles data.
     */
    private suspend fun handleEndPacket(packet: ByteArray) {
        val session = currentSession
        if (session == null) {
            Log.w(TAG, "Received END but no session active")
            return
        }
        
        try {
            val status = PacketUtils.parseEndPacket(packet)
            Log.d(TAG, "Received END: status=${PacketUtils.getStatusName(status)}")
            
            // Cancel timeout
            timeoutJob?.cancel()
            
            if (status != PhotoTransferConstants.STATUS_SUCCESS) {
                Log.e(TAG, "Sender reported error: ${PacketUtils.getStatusName(status)}")
                _transferState.value = PhotoTransferState.Error(
                    "Sender reported error",
                    status
                )
                reset()
                return
            }
            
            // Check if all chunks received
            if (session.receivedChunks.size != session.totalChunks) {
                val missing = (0 until session.totalChunks)
                    .filter { it !in session.receivedChunks.keys }
                Log.e(TAG, "Missing chunks: $missing")
                
                // Request missing chunks
                missing.forEach { sendRetry(it) }
                return
            }
            
            // Reassemble data
            val reassembledData = PacketUtils.reassembleChunks(
                session.receivedChunks,
                session.totalChunks
            )
            
            if (reassembledData == null) {
                Log.e(TAG, "Failed to reassemble chunks")
                _transferState.value = PhotoTransferState.Error("Reassembly failed")
                reset()
                return
            }
            
            // Verify MD5
            if (!PacketUtils.verifyMD5(reassembledData, session.expectedMd5)) {
                Log.e(TAG, "MD5 verification failed")
                _transferState.value = PhotoTransferState.Error(
                    "MD5 verification failed",
                    PhotoTransferConstants.STATUS_MD5_ERROR
                )
                reset()
                return
            }
            
            val transferTime = System.currentTimeMillis() - session.startTime
            Log.d(TAG, "Transfer complete: ${reassembledData.size} bytes in ${transferTime}ms")
            
            // Success!
            _transferState.value = PhotoTransferState.Success(reassembledData)
            
            // Emit received photo
            _receivedPhoto.emit(ReceivedPhoto(
                data = reassembledData,
                timestamp = System.currentTimeMillis(),
                transferTimeMs = transferTime
            ))
            
            reset()
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to process END packet", e)
            _transferState.value = PhotoTransferState.Error("Failed to process END: ${e.message}")
            reset()
        }
    }
    
    /**
     * Sends an ACK packet for a chunk.
     */
    private suspend fun sendAck(chunkIndex: Int, status: Byte) {
        try {
            val ackPacket = PacketUtils.createAckPacket(chunkIndex, status)
            withContext(Dispatchers.IO) {
                outputStream?.let { stream ->
                    stream.write(ackPacket)
                    stream.flush()
                    Log.d(TAG, "Sent ACK: chunk=$chunkIndex, status=${PacketUtils.getStatusName(status)}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send ACK", e)
        }
    }
    
    /**
     * Sends a RETRY packet for a chunk.
     */
    private suspend fun sendRetry(chunkIndex: Int) {
        try {
            val retryPacket = PacketUtils.createRetryPacket(chunkIndex)
            withContext(Dispatchers.IO) {
                outputStream?.let { stream ->
                    stream.write(retryPacket)
                    stream.flush()
                    Log.d(TAG, "Sent RETRY: chunk=$chunkIndex")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send RETRY", e)
        }
    }
    
    /**
     * Starts the overall transfer timeout.
     */
    private fun startTransferTimeout() {
        timeoutJob?.cancel()
        timeoutJob = scope.launch {
            delay(TRANSFER_TIMEOUT_MS)
            Log.e(TAG, "Transfer timeout")
            _transferState.value = PhotoTransferState.Error(
                "Transfer timeout",
                PhotoTransferConstants.STATUS_TIMEOUT
            )
            reset()
        }
    }
    
    /**
     * Resets the chunk timeout (extends overall timeout).
     */
    private fun resetChunkTimeout() {
        // Simply restart the transfer timeout on each successful chunk
        startTransferTimeout()
    }
    
    /**
     * Resets the receiver state.
     */
    fun reset() {
        timeoutJob?.cancel()
        timeoutJob = null
        currentSession = null
        _transferState.value = PhotoTransferState.Idle
        Log.d(TAG, "Receiver reset")
    }
    
    /**
     * Returns true if currently receiving a photo.
     */
    fun isReceiving(): Boolean {
        return currentSession != null
    }
    
    /**
     * Returns current progress as percentage (0-100).
     */
    fun getProgressPercent(): Float {
        val state = _transferState.value
        return if (state is PhotoTransferState.InProgress) {
            state.progressPercent
        } else 0f
    }
}

/**
 * Internal class to track transfer session state.
 */
private data class TransferSession(
    val totalSize: Int,
    val totalChunks: Int,
    val expectedMd5: ByteArray,
    val receivedChunks: MutableMap<Int, ByteArray>,
    val startTime: Long
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as TransferSession
        return totalSize == other.totalSize && 
               totalChunks == other.totalChunks && 
               expectedMd5.contentEquals(other.expectedMd5)
    }
    
    override fun hashCode(): Int {
        var result = totalSize
        result = 31 * result + totalChunks
        result = 31 * result + expectedMd5.contentHashCode()
        return result
    }
}

/**
 * Data class for a successfully received photo.
 */
data class ReceivedPhoto(
    val data: ByteArray,
    val timestamp: Long,
    val transferTimeMs: Long
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as ReceivedPhoto
        return timestamp == other.timestamp && data.contentEquals(other.data)
    }
    
    override fun hashCode(): Int {
        var result = data.contentHashCode()
        result = 31 * result + timestamp.hashCode()
        return result
    }
}
