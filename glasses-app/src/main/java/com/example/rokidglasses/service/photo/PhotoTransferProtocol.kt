// SPDX-License-Identifier: Apache-2.0
package com.example.rokidglasses.service.photo

import android.util.Log
import com.example.rokidcommon.protocol.photo.AckPacketData
import com.example.rokidcommon.protocol.photo.PacketUtils
import com.example.rokidcommon.protocol.photo.PhotoTransferConstants
import com.example.rokidcommon.protocol.photo.PhotoTransferState
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException

/**
 * Photo Transfer Protocol - Glasses Side (Sender)
 * 
 * Responsible for chunked photo transfer from Rokid Glasses to Android Phone.
 * Implements reliable transfer with CRC32 verification and retry mechanism.
 * 
 * Usage:
 * ```
 * val protocol = PhotoTransferProtocol(socket) { current, total ->
 *     Log.d("Progress", "$current / $total")
 * }
 * 
 * val result = protocol.sendPhoto(imageData)
 * result.onSuccess { Log.d("Transfer", "Success") }
 * result.onFailure { Log.e("Transfer", "Failed", it) }
 * ```
 */
class PhotoTransferProtocol(
    private val sendPacket: suspend (ByteArray) -> Boolean,
    private val controlPackets: SharedFlow<ByteArray>,
    private val onProgress: (current: Int, total: Int) -> Unit = { _, _ -> }
) {
    companion object {
        private const val TAG = "PhotoTransferProtocol"
        
    }
    
    // Transfer state flow
    private val _transferState = MutableStateFlow<PhotoTransferState>(PhotoTransferState.Idle)
    val transferState: StateFlow<PhotoTransferState> = _transferState.asStateFlow()
    
    // Statistics
    private var transferStartTime: Long = 0
    private var totalBytesSent: Long = 0
    private var retryCount: Int = 0
    
    private val transferMutex = Mutex()
    @Volatile private var currentTransferJob: Job? = null
    
    /**
     * Send photo data to the connected phone.
     * 
     * This method:
     * 1. Calculates MD5 hash of the entire photo
     * 2. Splits the photo into chunks
     * 3. Sends START packet with metadata
     * 4. Sends each DATA packet with CRC32 verification
     * 5. Handles ACK/RETRY responses from receiver
     * 6. Sends END packet to mark completion
     * 
     * @param imageData The compressed JPEG image data
     * @return Result indicating success or failure with error details
     */
    suspend fun sendPhoto(imageData: ByteArray): Result<TransferStatistics> = withContext(Dispatchers.IO) {
        transferMutex.withLock {
            val transferJob = currentCoroutineContext()[Job]
            currentTransferJob = transferJob
            try {
                if (imageData.isEmpty() || imageData.size > PhotoTransferConstants.MAX_PHOTO_SIZE) {
                    return@withLock Result.failure(
                        IllegalArgumentException("Photo size must be between 1 and ${PhotoTransferConstants.MAX_PHOTO_SIZE} bytes")
                    )
                }
            
                // Reset statistics
                transferStartTime = System.currentTimeMillis()
                totalBytesSent = 0
                retryCount = 0

                // Calculate metadata
                val md5 = PacketUtils.calculateMD5(imageData)
                val chunks = PacketUtils.splitIntoChunks(imageData)
                val totalChunks = chunks.size

                Log.d(TAG, "Starting photo transfer: ${imageData.size} bytes, $totalChunks chunks, MD5=${PacketUtils.md5ToHexString(md5)}")

                // Update state
                _transferState.value = PhotoTransferState.InProgress(0, totalChunks, 0, imageData.size.toLong())

                // Step 1: Send START packet
                val startResult = sendStartPacket(imageData.size, totalChunks, md5)
                if (startResult.isFailure) {
                    return@withLock Result.failure(startResult.exceptionOrNull()!!)
                }

                // Step 2: Send DATA packets
                for ((index, chunk) in chunks.withIndex()) {
                    val dataResult = sendDataPacketWithRetry(index, chunk, totalChunks)
                    if (dataResult.isFailure) {
                        // Send failure END packet
                        sendEndPacket(PhotoTransferConstants.STATUS_ERROR)
                        return@withLock Result.failure(dataResult.exceptionOrNull()!!)
                    }

                    // Update progress
                    totalBytesSent += chunk.size
                    _transferState.value = PhotoTransferState.InProgress(
                        index + 1,
                        totalChunks,
                        totalBytesSent,
                        imageData.size.toLong()
                    )
                    onProgress(index + 1, totalChunks)

                    // Small delay to prevent buffer overflow
                    delay(PhotoTransferConstants.CHUNK_DELAY_MS)
                }

                // Step 3: Send END packet
                val endResult = sendEndPacket(PhotoTransferConstants.STATUS_SUCCESS)
                if (endResult.isFailure) {
                    return@withLock Result.failure(endResult.exceptionOrNull()!!)
                }

                // Calculate statistics
                val elapsedMs = System.currentTimeMillis() - transferStartTime
                val transferRate = if (elapsedMs > 0) {
                    (imageData.size.toFloat() / elapsedMs) * 1000 / 1024 // KB/s
                } else 0f

                val stats = TransferStatistics(
                    totalBytes = imageData.size,
                    totalChunks = totalChunks,
                    elapsedTimeMs = elapsedMs,
                    transferRateKBps = transferRate,
                    retryCount = retryCount
                )

                Log.d(TAG, "Transfer completed: $stats")
                _transferState.value = PhotoTransferState.Success(imageData)

                Result.success(stats)
            } catch (e: CancellationException) {
                _transferState.value = PhotoTransferState.Error("Transfer cancelled")
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Transfer failed", e)
                _transferState.value = PhotoTransferState.Error(e.message ?: "Unknown error")
                Result.failure(e)
            } finally {
                if (currentTransferJob === transferJob) currentTransferJob = null
            }
        }
    }
    
    /**
     * Send START packet to initiate transfer.
     */
    private suspend fun sendStartPacket(totalSize: Int, totalChunks: Int, md5: ByteArray): Result<Unit> {
        return try {
            val packet = PacketUtils.createStartPacket(totalSize, totalChunks, md5)
            val result = sendAndAwaitAck(packet, expectedChunkIndex = 0)
            if (result.isSuccess) {
                Log.d(TAG, "Sent START packet: size=$totalSize, chunks=$totalChunks")
            }
            result
        } catch (e: IOException) {
            Log.e(TAG, "Failed to send START packet", e)
            Result.failure(e)
        }
    }
    
    /**
     * Send DATA packet with retry mechanism.
     * Will retry up to MAX_RETRY_COUNT times if CRC verification fails on receiver side.
     */
    private suspend fun sendDataPacketWithRetry(
        chunkIndex: Int, 
        data: ByteArray,
        totalChunks: Int
    ): Result<Unit> {
        var attempts = 0
        
        while (attempts < PhotoTransferConstants.MAX_RETRY_COUNT) {
            attempts++
            
            val packet = PacketUtils.createDataPacket(chunkIndex, data)
            val result = sendAndAwaitAck(packet, chunkIndex)
            if (result.isFailure) {
                Log.w(TAG, "Failed to send chunk $chunkIndex, attempt $attempts")
                retryCount++
                delay(100) // Brief delay before retry
                continue
            }
            
            return Result.success(Unit)
        }
        
        return Result.failure(IOException("Failed to send chunk $chunkIndex after ${PhotoTransferConstants.MAX_RETRY_COUNT} attempts"))
    }
    
    /**
     * Send a single DATA packet.
     */
    private suspend fun sendEndPacket(status: Byte): Result<Unit> {
        return try {
            val packet = PacketUtils.createEndPacket(status)
            if (!sendPacket(packet)) return Result.failure(IOException("Bluetooth write failed"))
            Log.d(TAG, "Sent END packet: status=${PacketUtils.getStatusName(status)}")
            Result.success(Unit)
        } catch (e: IOException) {
            Log.e(TAG, "Failed to send END packet", e)
            Result.failure(e)
        }
    }
    
    /**
     * Wait for ACK response from receiver.
     * Used in reliable transfer mode.
     * 
     * @param expectedChunkIndex The chunk index we're expecting ACK for
     * @return AckPacketData if received, null if timeout or error
     */
    private suspend fun sendAndAwaitAck(packet: ByteArray, expectedChunkIndex: Int): Result<Unit> = coroutineScope {
        val response = async(start = CoroutineStart.UNDISPATCHED) {
            waitForAck(expectedChunkIndex)
        }
        if (!sendPacket(packet)) {
            response.cancel()
            return@coroutineScope Result.failure(IOException("Bluetooth write failed"))
        }

        val ack = response.await()
        if (ack?.isSuccess == true) {
            Result.success(Unit)
        } else {
            Result.failure(IOException("ACK failed or timed out for chunk $expectedChunkIndex"))
        }
    }

    private suspend fun waitForAck(expectedChunkIndex: Int): AckPacketData? {
        try {
            return withTimeout(PhotoTransferConstants.ACK_TIMEOUT_MS) {
                controlPackets.mapNotNull { packet ->
                    when (PacketUtils.parsePacketType(packet)) {
                        PhotoTransferConstants.PACKET_TYPE_ACK -> {
                            PacketUtils.parseAckPacket(packet).takeIf { it.chunkIndex == expectedChunkIndex }
                        }
                        PhotoTransferConstants.PACKET_TYPE_RETRY -> {
                            val retryIndex = PacketUtils.parseRetryPacket(packet)
                            if (retryIndex == expectedChunkIndex) {
                                AckPacketData(retryIndex, PhotoTransferConstants.STATUS_CRC_ERROR)
                            } else null
                        }
                        else -> null
                    }
                }.first()
            }
        } catch (e: TimeoutCancellationException) {
            Log.w(TAG, "ACK timeout for chunk $expectedChunkIndex")
            return null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Error waiting for ACK", e)
            return null
        }
    }
    
    /**
     * Cancel ongoing transfer.
     */
    fun cancelTransfer() {
        Log.d(TAG, "Transfer cancelled")
        _transferState.value = PhotoTransferState.Error("Transfer cancelled by user")
        currentTransferJob?.cancel(CancellationException("Photo transfer cancelled"))
    }
    
    /**
     * Reset transfer state to Idle.
     */
    fun reset() {
        _transferState.value = PhotoTransferState.Idle
        totalBytesSent = 0
        retryCount = 0
    }
}
/**
 * Statistics for a completed transfer.
 */
data class TransferStatistics(
    val totalBytes: Int,
    val totalChunks: Int,
    val elapsedTimeMs: Long,
    val transferRateKBps: Float,
    val retryCount: Int
) {
    override fun toString(): String {
        return "TransferStatistics(bytes=$totalBytes, chunks=$totalChunks, " +
               "time=${elapsedTimeMs}ms, rate=${"%.2f".format(transferRateKBps)} KB/s, " +
               "retries=$retryCount)"
    }
}
