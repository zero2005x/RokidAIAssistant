package io.github.zero2005x.glassesaicompanion.service.photo

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
    private val scope: CoroutineScope,
    private val sendPacket: suspend (ByteArray) -> Boolean
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
    private var transferTimeoutJob: Job? = null
    private var chunkTimeoutJob: Job? = null
    
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
                cleanupSession()
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
                receivedBytes = 0,
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
            
            // Store the chunk and update progress without repeatedly summing all prior chunks.
            val previousChunk = session.receivedChunks.put(dataPacket.chunkIndex, dataPacket.payload)
            session.receivedBytes += dataPacket.payload.size - (previousChunk?.size ?: 0)
            _transferState.value = PhotoTransferState.InProgress(
                currentChunk = session.receivedChunks.size,
                totalChunks = session.totalChunks,
                bytesTransferred = session.receivedBytes.toLong(),
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
            chunkTimeoutJob?.cancel()
            
            if (status != PhotoTransferConstants.STATUS_SUCCESS) {
                Log.e(TAG, "Sender reported error: ${PacketUtils.getStatusName(status)}")
                _transferState.value = PhotoTransferState.Error(
                    "Sender reported error",
                    status
                )
                cleanupSession()
                return
            }
            
            // Check if all chunks received
            if (session.receivedChunks.size != session.totalChunks) {
                val missing = (0 until session.totalChunks)
                    .filter { it !in session.receivedChunks.keys }
                Log.e(TAG, "Missing chunks: $missing")
                
                // Request missing chunks
                missing.forEach { sendRetry(it) }
                resetChunkTimeout()
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
                cleanupSession()
                return
            }
            
            // Verify MD5
            if (!PacketUtils.verifyMD5(reassembledData, session.expectedMd5)) {
                Log.e(TAG, "MD5 verification failed")
                _transferState.value = PhotoTransferState.Error(
                    "MD5 verification failed",
                    PhotoTransferConstants.STATUS_MD5_ERROR
                )
                cleanupSession()
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
            
            cleanupSession()
            
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Failed to process END packet", e)
            _transferState.value = PhotoTransferState.Error("Failed to process END: ${e.message}")
            cleanupSession()
        }
    }
    
    /**
     * Sends an ACK packet for a chunk.
     */
    private suspend fun sendAck(chunkIndex: Int, status: Byte) {
        try {
            val ackPacket = PacketUtils.createAckPacket(chunkIndex, status)
            if (sendPacket(ackPacket)) {
                Log.d(TAG, "Sent ACK: chunk=$chunkIndex, status=${PacketUtils.getStatusName(status)}")
            } else {
                failTransfer("Bluetooth connection lost while sending ACK")
            }
        } catch (e: CancellationException) {
            throw e
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
            if (sendPacket(retryPacket)) {
                Log.d(TAG, "Sent RETRY: chunk=$chunkIndex")
            } else {
                failTransfer("Bluetooth connection lost while requesting retry")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send RETRY", e)
        }
    }
    
    /**
     * Starts the overall transfer timeout.
     */
    private fun startTransferTimeout() {
        transferTimeoutJob?.cancel()
        transferTimeoutJob = scope.launch {
            delay(TRANSFER_TIMEOUT_MS)
            failTransfer("Transfer timeout", PhotoTransferConstants.STATUS_TIMEOUT)
        }
        resetChunkTimeout()
    }
    
    /**
     * Resets the chunk timeout (extends overall timeout).
     */
    private fun resetChunkTimeout() {
        chunkTimeoutJob?.cancel()
        chunkTimeoutJob = scope.launch {
            delay(CHUNK_TIMEOUT_MS)
            failTransfer("Timed out waiting for the next photo chunk", PhotoTransferConstants.STATUS_TIMEOUT)
        }
    }
    
    /**
     * Resets the receiver state.
     */
    fun reset() {
        cleanupSession()
        _transferState.value = PhotoTransferState.Idle
        Log.d(TAG, "Receiver reset")
    }

    /** Clears transfer resources without erasing a terminal Success/Error state. */
    private fun cleanupSession() {
        transferTimeoutJob?.cancel()
        transferTimeoutJob = null
        chunkTimeoutJob?.cancel()
        chunkTimeoutJob = null
        currentSession = null
    }

    private fun failTransfer(message: String, status: Byte = PhotoTransferConstants.STATUS_ERROR) {
        Log.e(TAG, message)
        _transferState.value = PhotoTransferState.Error(message, status)
        cleanupSession()
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
    var receivedBytes: Int,
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
