package com.example.rokidcommon.protocol.photo

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.zip.CRC32

/**
 * Photo Transfer Packet Utilities
 * 
 * Provides serialization/deserialization methods for the chunked photo transfer protocol.
 * All multi-byte values use BIG_ENDIAN byte order for network transmission.
 * 
 * Packet Formats:
 * - START: [Type:1][TotalSize:4][TotalChunks:4][MD5:16] = 25 bytes
 * - DATA:  [Type:1][DataLength:2][ChunkIndex:4][CRC32:4][Payload:n] = 11 + n bytes
 * - END:   [Type:1][Status:1] = 2 bytes
 * - ACK:   [Type:1][ChunkIndex:4][Status:1] = 6 bytes
 * - RETRY: [Type:1][ChunkIndex:4] = 5 bytes
 */
object PacketUtils {
    
    // ==================== Packet Creation ====================
    
    /**
     * Creates a START packet to initiate photo transfer.
     * 
     * @param totalSize Total size of the photo data in bytes
     * @param totalChunks Total number of chunks to be sent
     * @param md5 MD5 hash of the complete photo data (16 bytes)
     * @return ByteArray containing the START packet
     * @throws IllegalArgumentException if md5 is not 16 bytes
     */
    fun createStartPacket(totalSize: Int, totalChunks: Int, md5: ByteArray): ByteArray {
        require(md5.size == PhotoTransferConstants.MD5_SIZE) {
            "MD5 must be ${PhotoTransferConstants.MD5_SIZE} bytes, got ${md5.size}"
        }
        
        return ByteBuffer.allocate(PhotoTransferConstants.START_PACKET_SIZE)
            .order(ByteOrder.BIG_ENDIAN)
            .put(PhotoTransferConstants.PACKET_TYPE_START)
            .putInt(totalSize)
            .putInt(totalChunks)
            .put(md5)
            .array()
    }
    
    /**
     * Creates a DATA packet containing a chunk of photo data.
     * 
     * @param chunkIndex Zero-based index of this chunk
     * @param data The actual chunk data (should be <= CHUNK_SIZE)
     * @return ByteArray containing the DATA packet with CRC32 checksum
     */
    fun createDataPacket(chunkIndex: Int, data: ByteArray): ByteArray {
        require(data.size <= PhotoTransferConstants.CHUNK_SIZE) {
            "Data size ${data.size} exceeds CHUNK_SIZE ${PhotoTransferConstants.CHUNK_SIZE}"
        }
        
        val crc = calculateCRC32(data)
        
        return ByteBuffer.allocate(PhotoTransferConstants.DATA_HEADER_SIZE + data.size)
            .order(ByteOrder.BIG_ENDIAN)
            .put(PhotoTransferConstants.PACKET_TYPE_DATA)
            .putShort(data.size.toShort())
            .putInt(chunkIndex)
            .putInt(crc)
            .put(data)
            .array()
    }
    
    /**
     * Creates an END packet to signal transfer completion.
     * 
     * @param status Transfer status code (STATUS_SUCCESS, STATUS_ERROR, etc.)
     * @return ByteArray containing the END packet
     */
    fun createEndPacket(status: Byte): ByteArray {
        return byteArrayOf(PhotoTransferConstants.PACKET_TYPE_END, status)
    }
    
    /**
     * Creates an ACK packet to acknowledge receipt of a chunk.
     * 
     * @param chunkIndex Index of the acknowledged chunk
     * @param status Status of the chunk (STATUS_SUCCESS or error code)
     * @return ByteArray containing the ACK packet
     */
    fun createAckPacket(chunkIndex: Int, status: Byte): ByteArray {
        return ByteBuffer.allocate(PhotoTransferConstants.ACK_PACKET_SIZE)
            .order(ByteOrder.BIG_ENDIAN)
            .put(PhotoTransferConstants.PACKET_TYPE_ACK)
            .putInt(chunkIndex)
            .put(status)
            .array()
    }
    
    /**
     * Creates a RETRY packet to request retransmission of a specific chunk.
     * 
     * @param chunkIndex Index of the chunk to be retransmitted
     * @return ByteArray containing the RETRY packet
     */
    fun createRetryPacket(chunkIndex: Int): ByteArray {
        return ByteBuffer.allocate(PhotoTransferConstants.RETRY_PACKET_SIZE)
            .order(ByteOrder.BIG_ENDIAN)
            .put(PhotoTransferConstants.PACKET_TYPE_RETRY)
            .putInt(chunkIndex)
            .array()
    }
    
    // ==================== Packet Parsing ====================
    
    /**
     * Extracts the packet type from a packet.
     * 
     * @param packet The packet data
     * @return The packet type byte
     */
    fun parsePacketType(packet: ByteArray): Byte {
        require(packet.isNotEmpty()) { "Packet cannot be empty" }
        return packet[0]
    }
    
    /**
     * Parses a START packet and extracts its fields.
     * 
     * @param packet The START packet data
     * @return StartPacketData containing totalSize, totalChunks, and md5
     * @throws IllegalArgumentException if packet is invalid
     */
    fun parseStartPacket(packet: ByteArray): StartPacketData {
        require(packet.size == PhotoTransferConstants.START_PACKET_SIZE) {
            "Invalid START packet size: ${packet.size}"
        }
        require(packet[0] == PhotoTransferConstants.PACKET_TYPE_START) {
            "Invalid packet type for START: ${packet[0]}"
        }
        
        val buffer = ByteBuffer.wrap(packet).order(ByteOrder.BIG_ENDIAN)
        buffer.get() // Skip type byte
        
        val totalSize = buffer.getInt()
        val totalChunks = buffer.getInt()
        val md5 = ByteArray(PhotoTransferConstants.MD5_SIZE)
        buffer.get(md5)
        
        return StartPacketData(totalSize, totalChunks, md5)
    }
    
    /**
     * Parses a DATA packet and extracts its fields.
     * Also verifies the CRC32 checksum.
     * 
     * @param packet The DATA packet data
     * @return DataPacketData containing chunkIndex, payload, and verification status
     * @throws IllegalArgumentException if packet is invalid
     */
    fun parseDataPacket(packet: ByteArray): DataPacketData {
        require(packet.size >= PhotoTransferConstants.DATA_HEADER_SIZE) {
            "Invalid DATA packet size: ${packet.size}"
        }
        require(packet[0] == PhotoTransferConstants.PACKET_TYPE_DATA) {
            "Invalid packet type for DATA: ${packet[0]}"
        }
        
        val buffer = ByteBuffer.wrap(packet).order(ByteOrder.BIG_ENDIAN)
        buffer.get() // Skip type byte
        
        val dataLength = buffer.getShort().toInt() and 0xFFFF
        val chunkIndex = buffer.getInt()
        val expectedCrc = buffer.getInt()
        
        require(packet.size == PhotoTransferConstants.DATA_HEADER_SIZE + dataLength) {
            "Packet size mismatch: expected ${PhotoTransferConstants.DATA_HEADER_SIZE + dataLength}, got ${packet.size}"
        }
        
        val payload = ByteArray(dataLength)
        buffer.get(payload)
        
        val actualCrc = calculateCRC32(payload)
        val isValid = actualCrc == expectedCrc
        
        return DataPacketData(chunkIndex, payload, isValid, expectedCrc, actualCrc)
    }
    
    /**
     * Parses an END packet and extracts the status.
     * 
     * @param packet The END packet data
     * @return The status byte
     */
    fun parseEndPacket(packet: ByteArray): Byte {
        require(packet.size == PhotoTransferConstants.END_PACKET_SIZE) {
            "Invalid END packet size: ${packet.size}"
        }
        require(packet[0] == PhotoTransferConstants.PACKET_TYPE_END) {
            "Invalid packet type for END: ${packet[0]}"
        }
        return packet[1]
    }
    
    /**
     * Parses an ACK packet and extracts its fields.
     * 
     * @param packet The ACK packet data
     * @return AckPacketData containing chunkIndex and status
     */
    fun parseAckPacket(packet: ByteArray): AckPacketData {
        require(packet.size == PhotoTransferConstants.ACK_PACKET_SIZE) {
            "Invalid ACK packet size: ${packet.size}"
        }
        require(packet[0] == PhotoTransferConstants.PACKET_TYPE_ACK) {
            "Invalid packet type for ACK: ${packet[0]}"
        }
        
        val buffer = ByteBuffer.wrap(packet).order(ByteOrder.BIG_ENDIAN)
        buffer.get() // Skip type byte
        
        val chunkIndex = buffer.getInt()
        val status = buffer.get()
        
        return AckPacketData(chunkIndex, status)
    }
    
    /**
     * Parses a RETRY packet and extracts the chunk index.
     * 
     * @param packet The RETRY packet data
     * @return The chunk index to retransmit
     */
    fun parseRetryPacket(packet: ByteArray): Int {
        require(packet.size == PhotoTransferConstants.RETRY_PACKET_SIZE) {
            "Invalid RETRY packet size: ${packet.size}"
        }
        require(packet[0] == PhotoTransferConstants.PACKET_TYPE_RETRY) {
            "Invalid packet type for RETRY: ${packet[0]}"
        }
        
        val buffer = ByteBuffer.wrap(packet).order(ByteOrder.BIG_ENDIAN)
        buffer.get() // Skip type byte
        
        return buffer.getInt()
    }
    
    // ==================== Checksum Utilities ====================
    
    /**
     * Calculates the MD5 hash of the given data.
     * 
     * @param data The data to hash
     * @return 16-byte MD5 hash
     */
    fun calculateMD5(data: ByteArray): ByteArray {
        val digest = MessageDigest.getInstance("MD5")
        return digest.digest(data)
    }
    
    /**
     * Calculates the CRC32 checksum of the given data.
     * 
     * @param data The data to checksum
     * @return CRC32 value as Int
     */
    fun calculateCRC32(data: ByteArray): Int {
        val crc = CRC32()
        crc.update(data)
        return crc.value.toInt()
    }
    
    /**
     * Verifies the CRC32 checksum of a DATA packet.
     * 
     * @param packet The complete DATA packet
     * @return true if CRC32 matches, false otherwise
     */
    fun verifyCRC32(packet: ByteArray): Boolean {
        if (packet.size < PhotoTransferConstants.DATA_HEADER_SIZE) return false
        if (packet[0] != PhotoTransferConstants.PACKET_TYPE_DATA) return false
        
        val result = parseDataPacket(packet)
        return result.isValid
    }
    
    /**
     * Verifies the MD5 hash of reassembled photo data.
     * 
     * @param data The complete photo data
     * @param expectedMd5 The expected MD5 hash from START packet
     * @return true if MD5 matches, false otherwise
     */
    fun verifyMD5(data: ByteArray, expectedMd5: ByteArray): Boolean {
        val actualMd5 = calculateMD5(data)
        return actualMd5.contentEquals(expectedMd5)
    }
    
    // ==================== Helper Functions ====================
    
    /**
     * Calculates the number of chunks needed for given data size.
     * 
     * @param totalSize Total size in bytes
     * @return Number of chunks required
     */
    fun calculateChunkCount(totalSize: Int): Int {
        return (totalSize + PhotoTransferConstants.CHUNK_SIZE - 1) / PhotoTransferConstants.CHUNK_SIZE
    }
    
    /**
     * Splits data into chunks for transmission.
     * 
     * @param data The complete data to split
     * @return List of byte arrays, each representing a chunk
     */
    fun splitIntoChunks(data: ByteArray): List<ByteArray> {
        val chunks = mutableListOf<ByteArray>()
        var offset = 0
        
        while (offset < data.size) {
            val remaining = data.size - offset
            val chunkSize = minOf(remaining, PhotoTransferConstants.CHUNK_SIZE)
            val chunk = data.copyOfRange(offset, offset + chunkSize)
            chunks.add(chunk)
            offset += chunkSize
        }
        
        return chunks
    }
    
    /**
     * Reassembles chunks into complete data.
     * 
     * @param chunks Map of chunk index to chunk data
     * @param totalChunks Expected total number of chunks
     * @return Complete reassembled data, or null if chunks are missing
     */
    fun reassembleChunks(chunks: Map<Int, ByteArray>, totalChunks: Int): ByteArray? {
        // Verify all chunks are present
        for (i in 0 until totalChunks) {
            if (!chunks.containsKey(i)) {
                return null
            }
        }
        
        // Calculate total size
        val totalSize = chunks.values.sumOf { it.size }
        val result = ByteArray(totalSize)
        
        // Copy chunks in order
        var offset = 0
        for (i in 0 until totalChunks) {
            val chunk = chunks[i]!!
            chunk.copyInto(result, offset)
            offset += chunk.size
        }
        
        return result
    }
    
    /**
     * Converts MD5 byte array to hex string for logging.
     * 
     * @param md5 The 16-byte MD5 hash
     * @return Hex string representation
     */
    fun md5ToHexString(md5: ByteArray): String {
        return md5.joinToString("") { "%02x".format(it) }
    }
    
    /**
     * Returns human-readable packet type name.
     * 
     * @param type The packet type byte
     * @return String name of the packet type
     */
    fun getPacketTypeName(type: Byte): String {
        return when (type) {
            PhotoTransferConstants.PACKET_TYPE_START -> "START"
            PhotoTransferConstants.PACKET_TYPE_DATA -> "DATA"
            PhotoTransferConstants.PACKET_TYPE_END -> "END"
            PhotoTransferConstants.PACKET_TYPE_ACK -> "ACK"
            PhotoTransferConstants.PACKET_TYPE_RETRY -> "RETRY"
            else -> "UNKNOWN(0x${type.toString(16)})"
        }
    }
    
    /**
     * Returns human-readable status name.
     * 
     * @param status The status byte
     * @return String name of the status
     */
    fun getStatusName(status: Byte): String {
        return when (status) {
            PhotoTransferConstants.STATUS_SUCCESS -> "SUCCESS"
            PhotoTransferConstants.STATUS_CRC_ERROR -> "CRC_ERROR"
            PhotoTransferConstants.STATUS_MD5_ERROR -> "MD5_ERROR"
            PhotoTransferConstants.STATUS_TIMEOUT -> "TIMEOUT"
            PhotoTransferConstants.STATUS_OUT_OF_MEMORY -> "OUT_OF_MEMORY"
            PhotoTransferConstants.STATUS_ERROR -> "ERROR"
            else -> "UNKNOWN(0x${status.toString(16)})"
        }
    }
}

// ==================== Data Classes ====================

/**
 * Data extracted from a START packet.
 */
data class StartPacketData(
    val totalSize: Int,
    val totalChunks: Int,
    val md5: ByteArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as StartPacketData
        return totalSize == other.totalSize && 
               totalChunks == other.totalChunks && 
               md5.contentEquals(other.md5)
    }
    
    override fun hashCode(): Int {
        var result = totalSize
        result = 31 * result + totalChunks
        result = 31 * result + md5.contentHashCode()
        return result
    }
    
    override fun toString(): String {
        return "StartPacketData(totalSize=$totalSize, totalChunks=$totalChunks, md5=${PacketUtils.md5ToHexString(md5)})"
    }
}

/**
 * Data extracted from a DATA packet.
 */
data class DataPacketData(
    val chunkIndex: Int,
    val payload: ByteArray,
    val isValid: Boolean,
    val expectedCrc: Int,
    val actualCrc: Int
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as DataPacketData
        return chunkIndex == other.chunkIndex && 
               payload.contentEquals(other.payload) && 
               isValid == other.isValid
    }
    
    override fun hashCode(): Int {
        var result = chunkIndex
        result = 31 * result + payload.contentHashCode()
        result = 31 * result + isValid.hashCode()
        return result
    }
    
    override fun toString(): String {
        return "DataPacketData(chunkIndex=$chunkIndex, payloadSize=${payload.size}, isValid=$isValid)"
    }
}

/**
 * Data extracted from an ACK packet.
 */
data class AckPacketData(
    val chunkIndex: Int,
    val status: Byte
) {
    val isSuccess: Boolean get() = status == PhotoTransferConstants.STATUS_SUCCESS
    
    override fun toString(): String {
        return "AckPacketData(chunkIndex=$chunkIndex, status=${PacketUtils.getStatusName(status)})"
    }
}

/**
 * Represents the state of a photo transfer.
 */
sealed class PhotoTransferState {
    /** Transfer not started */
    data object Idle : PhotoTransferState()
    
    /** Receiving/sending in progress */
    data class InProgress(
        val currentChunk: Int,
        val totalChunks: Int,
        val bytesTransferred: Long,
        val totalBytes: Long
    ) : PhotoTransferState() {
        val progressPercent: Float get() = if (totalChunks > 0) currentChunk.toFloat() / totalChunks * 100f else 0f
    }
    
    /** Transfer completed successfully */
    data class Success(val data: ByteArray) : PhotoTransferState() {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false
            return data.contentEquals((other as Success).data)
        }
        
        override fun hashCode(): Int = data.contentHashCode()
    }
    
    /** Transfer failed */
    data class Error(val message: String, val errorCode: Byte? = null) : PhotoTransferState()
}
