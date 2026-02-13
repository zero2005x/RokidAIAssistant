package com.example.rokidcommon.protocol.photo

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Integration tests for chunked photo transfer protocol flow.
 */
class PhotoTransferProtocolIntegrationTest {

    @Test
    fun `full photo transfer flow start data ack end can reassemble and verify md5`() {
        // Test: Complete photo transfer flow can reassemble data and pass MD5 verification
        val photoData = ByteArray(PhotoTransferConstants.CHUNK_SIZE * 2 + 321) { (it % 251).toByte() }
        val chunks = PacketUtils.splitIntoChunks(photoData)
        val expectedChunkCount = PacketUtils.calculateChunkCount(photoData.size)
        val md5 = PacketUtils.calculateMD5(photoData)

        val startPacket = PacketUtils.createStartPacket(photoData.size, expectedChunkCount, md5)
        val startData = PacketUtils.parseStartPacket(startPacket)

        assertThat(startData.totalSize).isEqualTo(photoData.size)
        assertThat(startData.totalChunks).isEqualTo(expectedChunkCount)
        assertThat(startData.md5.contentEquals(md5)).isTrue()

        val receivedChunks = mutableMapOf<Int, ByteArray>()

        chunks.forEachIndexed { index, chunk ->
            val dataPacket = PacketUtils.createDataPacket(index, chunk)
            val parsed = PacketUtils.parseDataPacket(dataPacket)

            assertThat(parsed.chunkIndex).isEqualTo(index)
            assertThat(parsed.isValid).isTrue()
            assertThat(parsed.payload).isEqualTo(chunk)

            // Simulate receiver ACK
            val ack = PacketUtils.createAckPacket(index, PhotoTransferConstants.STATUS_SUCCESS)
            val ackParsed = PacketUtils.parseAckPacket(ack)
            assertThat(ackParsed.chunkIndex).isEqualTo(index)
            assertThat(ackParsed.status).isEqualTo(PhotoTransferConstants.STATUS_SUCCESS)

            receivedChunks[index] = parsed.payload
        }

        val reassembled = PacketUtils.reassembleChunks(receivedChunks, expectedChunkCount)
        assertThat(reassembled).isNotNull()
        assertThat(reassembled).isEqualTo(photoData)
        assertThat(PacketUtils.verifyMD5(reassembled!!, md5)).isTrue()

        val endPacket = PacketUtils.createEndPacket(PhotoTransferConstants.STATUS_SUCCESS)
        val endStatus = PacketUtils.parseEndPacket(endPacket)
        assertThat(endStatus).isEqualTo(PhotoTransferConstants.STATUS_SUCCESS)
    }

    @Test
    fun `crc failure flow triggers retry for failed chunk`() {
        // Test: CRC verification failure correctly requests retransmission of specific chunk
        val chunkIndex = 5
        val originalPayload = ByteArray(256) { (it * 3 % 255).toByte() }
        val packet = PacketUtils.createDataPacket(chunkIndex, originalPayload)

        // Corrupt payload byte after header to force CRC mismatch
        val corrupted = packet.copyOf()
        val payloadOffset = PhotoTransferConstants.DATA_HEADER_SIZE
        corrupted[payloadOffset] = (corrupted[payloadOffset].toInt() xor 0x7F).toByte()

        val parsedCorrupted = PacketUtils.parseDataPacket(corrupted)
        assertThat(parsedCorrupted.chunkIndex).isEqualTo(chunkIndex)
        assertThat(parsedCorrupted.isValid).isFalse()

        val retryPacket = PacketUtils.createRetryPacket(chunkIndex)
        val retryIndex = PacketUtils.parseRetryPacket(retryPacket)
        assertThat(retryIndex).isEqualTo(chunkIndex)
    }

    @Test
    fun `reassembleChunks returns null when any chunk is missing`() {
        // Test: Cannot reassemble complete photo when any chunk is missing
        val chunks = mapOf(
            0 to byteArrayOf(1, 2, 3),
            2 to byteArrayOf(7, 8, 9)
        )

        val result = PacketUtils.reassembleChunks(chunks, totalChunks = 3)

        assertThat(result).isNull()
    }
}