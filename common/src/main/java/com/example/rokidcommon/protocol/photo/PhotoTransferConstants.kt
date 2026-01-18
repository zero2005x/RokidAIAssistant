package com.example.rokidcommon.protocol.photo

import java.util.UUID

/**
 * Photo Transfer Protocol Constants
 * 
 * Defines all constants for the chunked photo transfer protocol between
 * Rokid Glasses (Server/Sender) and Android Phone (Client/Receiver).
 * 
 * Protocol Flow:
 * 1. PHOTO_START - Initialize transfer with metadata (size, chunks, MD5)
 * 2. PHOTO_DATA  - Transfer photo data in chunks with CRC32 verification
 * 3. PHOTO_END   - Mark transfer completion with status
 */
object PhotoTransferConstants {
    
    // ==================== Bluetooth Configuration ====================
    
    /**
     * Standard SPP (Serial Port Profile) UUID
     * This is the universally recognized UUID for Bluetooth SPP connections.
     */
    val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    
    /**
     * Custom service name for photo transfer
     */
    const val SERVICE_NAME = "GlassesPhotoTransfer"
    
    // ==================== Packet Types ====================
    
    /**
     * START packet (0x01) - Initiates a photo transfer
     * Format: [Type:1][TotalSize:4][TotalChunks:4][MD5:16]
     * Total: 25 bytes
     */
    const val PACKET_TYPE_START: Byte = 0x01
    
    /**
     * DATA packet (0x02) - Contains actual photo data
     * Format: [Type:1][DataLength:2][ChunkIndex:4][CRC32:4][Payload:n]
     * Header: 11 bytes + Payload (up to CHUNK_SIZE bytes)
     */
    const val PACKET_TYPE_DATA: Byte = 0x02
    
    /**
     * END packet (0x03) - Marks transfer completion
     * Format: [Type:1][Status:1]
     * Total: 2 bytes
     */
    const val PACKET_TYPE_END: Byte = 0x03
    
    /**
     * ACK packet (0x04) - Acknowledgement from receiver
     * Format: [Type:1][ChunkIndex:4][Status:1]
     * Total: 6 bytes
     */
    const val PACKET_TYPE_ACK: Byte = 0x04
    
    /**
     * RETRY packet (0x05) - Request retransmission of specific chunk
     * Format: [Type:1][ChunkIndex:4]
     * Total: 5 bytes
     */
    const val PACKET_TYPE_RETRY: Byte = 0x05
    
    // ==================== Transfer Parameters ====================
    
    /**
     * Maximum photo size (5MB)
     * This is a reasonable limit for compressed JPEG photos
     */
    const val MAX_PHOTO_SIZE = 5 * 1024 * 1024
    
    /**
     * Maximum number of chunks per transfer
     * At 4KB per chunk, this allows for ~8MB photos
     */
    const val MAX_CHUNKS = 2048
    
    /**
     * Chunk size for data packets (4KB)
     * This is optimized for Bluetooth SPP throughput while maintaining
     * reasonable memory usage and retry granularity.
     */
    const val CHUNK_SIZE = 4096
    
    /**
     * Maximum retry attempts per chunk before failing the entire transfer
     */
    const val MAX_RETRY_COUNT = 3
    
    /**
     * Timeout for waiting ACK from receiver (milliseconds)
     */
    const val ACK_TIMEOUT_MS = 5000L
    
    /**
     * Timeout for entire transfer operation (milliseconds)
     * Default: 60 seconds for large photos
     */
    const val TRANSFER_TIMEOUT_MS = 60000L
    
    /**
     * Delay between chunks to prevent buffer overflow (milliseconds)
     */
    const val CHUNK_DELAY_MS = 10L
    
    // ==================== Packet Size Constants ====================
    
    /**
     * Size of START packet header (excluding payload)
     * Type(1) + TotalSize(4) + TotalChunks(4) + MD5(16) = 25 bytes
     */
    const val START_PACKET_SIZE = 25
    
    /**
     * Size of DATA packet header (excluding payload)
     * Type(1) + DataLength(2) + ChunkIndex(4) + CRC32(4) = 11 bytes
     */
    const val DATA_HEADER_SIZE = 11
    
    /**
     * Size of END packet
     * Type(1) + Status(1) = 2 bytes
     */
    const val END_PACKET_SIZE = 2
    
    /**
     * Size of ACK packet
     * Type(1) + ChunkIndex(4) + Status(1) = 6 bytes
     */
    const val ACK_PACKET_SIZE = 6
    
    /**
     * Size of RETRY packet
     * Type(1) + ChunkIndex(4) = 5 bytes
     */
    const val RETRY_PACKET_SIZE = 5
    
    /**
     * MD5 hash size in bytes
     */
    const val MD5_SIZE = 16
    
    // ==================== Status Codes ====================
    
    /**
     * Transfer completed successfully
     */
    const val STATUS_SUCCESS: Byte = 0x00
    
    /**
     * Transfer failed - CRC32 verification error
     */
    const val STATUS_CRC_ERROR: Byte = 0x01
    
    /**
     * Transfer failed - MD5 verification error
     */
    const val STATUS_MD5_ERROR: Byte = 0x02
    
    /**
     * Transfer failed - Timeout
     */
    const val STATUS_TIMEOUT: Byte = 0x03
    
    /**
     * Transfer failed - Out of memory
     */
    const val STATUS_OUT_OF_MEMORY: Byte = 0x04
    
    /**
     * Transfer failed - General error
     */
    const val STATUS_ERROR: Byte = 0xFF.toByte()
    
    // ==================== Image Compression Settings ====================
    
    /**
     * Target width for compressed images (720p)
     */
    const val TARGET_IMAGE_WIDTH = 1280
    
    /**
     * Target height for compressed images (720p)
     */
    const val TARGET_IMAGE_HEIGHT = 720
    
    /**
     * JPEG compression quality (0-100)
     * 70 provides good balance between quality and size
     */
    const val JPEG_QUALITY = 70
    
    /**
     * Maximum file size target after compression (bytes)
     * 200KB should transfer in ~2-3 seconds over Bluetooth SPP
     */
    const val MAX_COMPRESSED_SIZE = 200 * 1024
}
