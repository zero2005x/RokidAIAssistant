package com.example.rokidcommon.protocol.photo

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The rejection paths, naming helpers and packet value types of [PacketUtils].
 * The sibling PhotoTransferProtocolIntegrationTest covers a whole transfer.
 */
class PacketUtilsValidationTest {

    private val md5 = PacketUtils.calculateMD5(byteArrayOf(1, 2, 3))
    private val payload = ByteArray(64) { it.toByte() }

    private inline fun rejects(crossinline block: () -> Unit): String {
        val error = runCatching { block() }.exceptionOrNull()
        assertThat(error).isInstanceOf(IllegalArgumentException::class.java)
        return error!!.message!!
    }

    // ==================== Creation guards ====================

    @Test
    fun `a start packet needs a full length MD5`() {
        assertThat(rejects { PacketUtils.createStartPacket(10, 1, ByteArray(15)) })
            .isEqualTo("MD5 must be 16 bytes, got 15")
        assertThat(rejects { PacketUtils.createStartPacket(10, 1, ByteArray(0)) })
            .contains("got 0")
    }

    @Test
    fun `a data packet cannot exceed the chunk size`() {
        val tooBig = ByteArray(PhotoTransferConstants.CHUNK_SIZE + 1)

        assertThat(rejects { PacketUtils.createDataPacket(0, tooBig) })
            .contains("exceeds CHUNK_SIZE")

        // Exactly one chunk is still allowed.
        val exact = PacketUtils.createDataPacket(0, ByteArray(PhotoTransferConstants.CHUNK_SIZE))
        assertThat(exact).hasLength(
            PhotoTransferConstants.DATA_HEADER_SIZE + PhotoTransferConstants.CHUNK_SIZE
        )
    }

    // ==================== Parsing guards ====================

    @Test
    fun `an empty packet has no type`() {
        assertThat(rejects { PacketUtils.parsePacketType(ByteArray(0)) })
            .isEqualTo("Packet cannot be empty")

        assertThat(PacketUtils.parsePacketType(PacketUtils.createRetryPacket(7)))
            .isEqualTo(PhotoTransferConstants.PACKET_TYPE_RETRY)
    }

    @Test
    fun `each parser rejects the wrong length and the wrong type`() {
        val start = PacketUtils.createStartPacket(100, 2, md5)
        val data = PacketUtils.createDataPacket(3, payload)
        val end = PacketUtils.createEndPacket(PhotoTransferConstants.STATUS_SUCCESS)
        val ack = PacketUtils.createAckPacket(3, PhotoTransferConstants.STATUS_SUCCESS)
        val retry = PacketUtils.createRetryPacket(3)

        assertThat(rejects { PacketUtils.parseStartPacket(start.copyOf(24)) })
            .contains("Invalid START packet size")
        assertThat(rejects { PacketUtils.parseDataPacket(ByteArray(10)) })
            .contains("Invalid DATA packet size")
        assertThat(rejects { PacketUtils.parseEndPacket(ByteArray(3)) })
            .contains("Invalid END packet size")
        assertThat(rejects { PacketUtils.parseAckPacket(ByteArray(5)) })
            .contains("Invalid ACK packet size")
        assertThat(rejects { PacketUtils.parseRetryPacket(ByteArray(4)) })
            .contains("Invalid RETRY packet size")

        // Right length, wrong leading type byte.
        assertThat(rejects { PacketUtils.parseStartPacket(start.also { it[0] = 0x09 }) })
            .contains("Invalid packet type for START")
        assertThat(rejects { PacketUtils.parseDataPacket(data.also { it[0] = 0x09 }) })
            .contains("Invalid packet type for DATA")
        assertThat(rejects { PacketUtils.parseEndPacket(end.also { it[0] = 0x09 }) })
            .contains("Invalid packet type for END")
        assertThat(rejects { PacketUtils.parseAckPacket(ack.also { it[0] = 0x09 }) })
            .contains("Invalid packet type for ACK")
        assertThat(rejects { PacketUtils.parseRetryPacket(retry.also { it[0] = 0x09 }) })
            .contains("Invalid packet type for RETRY")
    }

    @Test
    fun `a data packet whose declared length disagrees with its size is rejected`() {
        val packet = PacketUtils.createDataPacket(0, payload)
        // Claim one more byte of payload than the packet actually carries.
        packet[2] = (payload.size + 1).toByte()

        assertThat(rejects { PacketUtils.parseDataPacket(packet) })
            .contains("Packet size mismatch")
    }

    // ==================== Checksums ====================

    @Test
    fun `a corrupted payload is reported rather than thrown`() {
        val packet = PacketUtils.createDataPacket(5, payload)
        assertThat(PacketUtils.verifyCRC32(packet)).isTrue()

        // Flip a payload byte, leaving the stored CRC stale.
        packet[PhotoTransferConstants.DATA_HEADER_SIZE] =
            (packet[PhotoTransferConstants.DATA_HEADER_SIZE] + 1).toByte()

        assertThat(PacketUtils.verifyCRC32(packet)).isFalse()
        val parsed = PacketUtils.parseDataPacket(packet)
        assertThat(parsed.isValid).isFalse()
        assertThat(parsed.actualCrc).isNotEqualTo(parsed.expectedCrc)
        assertThat(parsed.chunkIndex).isEqualTo(5)
    }

    @Test
    fun `only a well formed data packet is worth checksumming`() {
        // Too short to hold a header.
        assertThat(PacketUtils.verifyCRC32(ByteArray(PhotoTransferConstants.DATA_HEADER_SIZE - 1)))
            .isFalse()
        // Long enough, but not a DATA packet.
        assertThat(PacketUtils.verifyCRC32(PacketUtils.createStartPacket(1, 1, md5))).isFalse()
    }

    @Test
    fun `MD5 verification accepts only the matching hash`() {
        val data = byteArrayOf(9, 8, 7)

        assertThat(PacketUtils.verifyMD5(data, PacketUtils.calculateMD5(data))).isTrue()
        assertThat(PacketUtils.verifyMD5(data, md5)).isFalse()
        assertThat(PacketUtils.verifyMD5(data, ByteArray(16))).isFalse()
    }

    @Test
    fun `an MD5 renders as lower case hex`() {
        assertThat(PacketUtils.md5ToHexString(PacketUtils.calculateMD5(ByteArray(0))))
            .isEqualTo("d41d8cd98f00b204e9800998ecf8427e")
        // Bytes above 0x7f must not render as a negative number.
        assertThat(PacketUtils.md5ToHexString(byteArrayOf(0x00, 0x0f, 0xff.toByte())))
            .isEqualTo("000fff")
    }

    // ==================== Chunking ====================

    @Test
    fun `reassembly waits until every chunk has arrived`() {
        val chunks = mapOf(0 to byteArrayOf(1, 2), 2 to byteArrayOf(5, 6))

        assertThat(PacketUtils.reassembleChunks(chunks, totalChunks = 3)).isNull()

        val complete = chunks + (1 to byteArrayOf(3, 4))
        assertThat(PacketUtils.reassembleChunks(complete, totalChunks = 3))
            .isEqualTo(byteArrayOf(1, 2, 3, 4, 5, 6))
    }

    @Test
    fun `splitting and reassembling round trips an exact multiple and a remainder`() {
        for (size in listOf(
            PhotoTransferConstants.CHUNK_SIZE * 2,
            PhotoTransferConstants.CHUNK_SIZE + 1,
            1
        )) {
            val data = ByteArray(size) { (it % 251).toByte() }
            val chunks = PacketUtils.splitIntoChunks(data)

            assertThat(chunks).hasSize(PacketUtils.calculateChunkCount(size))
            val rebuilt = PacketUtils.reassembleChunks(
                chunks.withIndex().associate { (i, c) -> i to c }, chunks.size
            )
            assertThat(rebuilt).isEqualTo(data)
        }

        assertThat(PacketUtils.splitIntoChunks(ByteArray(0))).isEmpty()
        assertThat(PacketUtils.calculateChunkCount(0)).isEqualTo(0)
    }

    // ==================== Naming helpers ====================

    @Test
    fun `every packet type has a name and anything else is flagged unknown`() {
        assertThat(PacketUtils.getPacketTypeName(PhotoTransferConstants.PACKET_TYPE_START))
            .isEqualTo("START")
        assertThat(PacketUtils.getPacketTypeName(PhotoTransferConstants.PACKET_TYPE_DATA))
            .isEqualTo("DATA")
        assertThat(PacketUtils.getPacketTypeName(PhotoTransferConstants.PACKET_TYPE_END))
            .isEqualTo("END")
        assertThat(PacketUtils.getPacketTypeName(PhotoTransferConstants.PACKET_TYPE_ACK))
            .isEqualTo("ACK")
        assertThat(PacketUtils.getPacketTypeName(PhotoTransferConstants.PACKET_TYPE_RETRY))
            .isEqualTo("RETRY")
        assertThat(PacketUtils.getPacketTypeName(0x42)).isEqualTo("UNKNOWN(0x42)")
    }

    @Test
    fun `every status has a name and anything else is flagged unknown`() {
        assertThat(PacketUtils.getStatusName(PhotoTransferConstants.STATUS_SUCCESS))
            .isEqualTo("SUCCESS")
        assertThat(PacketUtils.getStatusName(PhotoTransferConstants.STATUS_CRC_ERROR))
            .isEqualTo("CRC_ERROR")
        assertThat(PacketUtils.getStatusName(PhotoTransferConstants.STATUS_MD5_ERROR))
            .isEqualTo("MD5_ERROR")
        assertThat(PacketUtils.getStatusName(PhotoTransferConstants.STATUS_TIMEOUT))
            .isEqualTo("TIMEOUT")
        assertThat(PacketUtils.getStatusName(PhotoTransferConstants.STATUS_OUT_OF_MEMORY))
            .isEqualTo("OUT_OF_MEMORY")
        assertThat(PacketUtils.getStatusName(PhotoTransferConstants.STATUS_ERROR))
            .isEqualTo("ERROR")
        assertThat(PacketUtils.getStatusName(0x42)).isEqualTo("UNKNOWN(0x42)")
    }

    // ==================== Packet value types ====================

    @Test
    fun `start packet data compares by MD5 content rather than array identity`() {
        val one = StartPacketData(100, 2, md5.copyOf())
        val same = StartPacketData(100, 2, md5.copyOf())

        assertThat(one).isEqualTo(same)
        assertThat(one).isEqualTo(one)
        assertThat(one.hashCode()).isEqualTo(same.hashCode())
        assertThat(one).isNotEqualTo(StartPacketData(101, 2, md5.copyOf()))
        assertThat(one).isNotEqualTo(StartPacketData(100, 3, md5.copyOf()))
        assertThat(one).isNotEqualTo(StartPacketData(100, 2, ByteArray(16)))
        assertThat(one).isNotEqualTo(null)
        assertThat(one).isNotEqualTo("not a packet")
        assertThat(one.toString()).contains(PacketUtils.md5ToHexString(md5))
    }

    @Test
    fun `data packet data compares by payload content and validity`() {
        val one = DataPacketData(1, payload.copyOf(), isValid = true, expectedCrc = 7, actualCrc = 7)
        val same = DataPacketData(1, payload.copyOf(), isValid = true, expectedCrc = 7, actualCrc = 7)

        assertThat(one).isEqualTo(same)
        assertThat(one).isEqualTo(one)
        assertThat(one.hashCode()).isEqualTo(same.hashCode())
        assertThat(one).isNotEqualTo(one.copy(chunkIndex = 2))
        assertThat(one).isNotEqualTo(one.copy(payload = ByteArray(64)))
        assertThat(one).isNotEqualTo(one.copy(isValid = false))
        assertThat(one).isNotEqualTo(null)
        assertThat(one).isNotEqualTo("not a packet")
        assertThat(one.toString()).contains("payloadSize=${payload.size}")
    }

    @Test
    fun `an ack reports success only for the success status`() {
        val ok = AckPacketData(4, PhotoTransferConstants.STATUS_SUCCESS)
        val bad = AckPacketData(4, PhotoTransferConstants.STATUS_CRC_ERROR)

        assertThat(ok.isSuccess).isTrue()
        assertThat(bad.isSuccess).isFalse()
        assertThat(ok.toString()).contains("SUCCESS")
        assertThat(bad.toString()).contains("CRC_ERROR")
    }

    @Test
    fun `transfer progress is a percentage that tolerates an empty transfer`() {
        assertThat(PhotoTransferState.InProgress(1, 4, 10, 40).progressPercent).isEqualTo(25f)
        assertThat(PhotoTransferState.InProgress(0, 0, 0, 0).progressPercent).isEqualTo(0f)
        assertThat(PhotoTransferState.Idle).isEqualTo(PhotoTransferState.Idle)
    }

    @Test
    fun `a successful transfer compares by its bytes`() {
        val one = PhotoTransferState.Success(byteArrayOf(1, 2, 3))
        val same = PhotoTransferState.Success(byteArrayOf(1, 2, 3))

        assertThat(one).isEqualTo(same)
        assertThat(one).isEqualTo(one)
        assertThat(one.hashCode()).isEqualTo(same.hashCode())
        assertThat(one).isNotEqualTo(PhotoTransferState.Success(byteArrayOf(1, 2)))
        assertThat(one).isNotEqualTo(null)
        assertThat(one).isNotEqualTo(PhotoTransferState.Idle)

        val error = PhotoTransferState.Error("timed out", PhotoTransferConstants.STATUS_TIMEOUT)
        assertThat(error.message).isEqualTo("timed out")
        assertThat(error.errorCode).isEqualTo(PhotoTransferConstants.STATUS_TIMEOUT)
        assertThat(PhotoTransferState.Error("no code").errorCode).isNull()
    }
}
