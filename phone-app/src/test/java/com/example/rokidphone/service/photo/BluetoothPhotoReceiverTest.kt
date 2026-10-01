package com.example.rokidphone.service.photo

import com.example.rokidcommon.protocol.photo.PacketUtils
import com.example.rokidcommon.protocol.photo.PhotoTransferConstants as C
import com.example.rokidcommon.protocol.photo.PhotoTransferState
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class BluetoothPhotoReceiverTest {

    private val sent = mutableListOf<ByteArray>()
    private var writeSucceeds = true

    private fun receiver(scope: TestScope) = BluetoothPhotoReceiver(scope) { packet ->
        sent += packet
        writeSucceeds
    }

    private fun acks() = sent.filter { PacketUtils.parsePacketType(it) == C.PACKET_TYPE_ACK }
        .map { PacketUtils.parseAckPacket(it) }

    private fun retries() = sent.filter { PacketUtils.parsePacketType(it) == C.PACKET_TYPE_RETRY }
        .map { PacketUtils.parseRetryPacket(it) }

    private fun chunksOf(photo: ByteArray): List<ByteArray> =
        photo.toList().chunked(C.CHUNK_SIZE) { it.toByteArray() }

    @Test
    fun `a complete transfer is reassembled, verified and published`() = runTest {
        val scope = TestScope(testScheduler)
        val receiver = receiver(scope)
        val photo = ByteArray(C.CHUNK_SIZE + 7) { (it % 251).toByte() }
        val chunks = chunksOf(photo)
        // UNDISPATCHED so the subscriber is registered before END is processed:
        // the flow has no replay, so an emission with no subscriber is lost.
        val received = async(start = CoroutineStart.UNDISPATCHED) { receiver.receivedPhoto.first() }

        assertThat(receiver.processPacket(
            PacketUtils.createStartPacket(photo.size, chunks.size, PacketUtils.calculateMD5(photo))
        )).isTrue()
        assertThat(receiver.isReceiving()).isTrue()

        chunks.forEachIndexed { index, chunk ->
            assertThat(receiver.processPacket(PacketUtils.createDataPacket(index, chunk))).isTrue()
        }
        assertThat(receiver.getProgressPercent()).isEqualTo(100f)

        assertThat(receiver.processPacket(PacketUtils.createEndPacket(C.STATUS_SUCCESS))).isTrue()
        // The emission resumes the collector through the test dispatcher.
        advanceUntilIdle()

        val state = receiver.transferState.value
        assertThat(state).isInstanceOf(PhotoTransferState.Success::class.java)
        assertThat((state as PhotoTransferState.Success).data).isEqualTo(photo)
        assertThat(received.await().data).isEqualTo(photo)
        assertThat(receiver.isReceiving()).isFalse()
        assertThat(receiver.getProgressPercent()).isEqualTo(0f)
        // One ACK for START plus one per chunk, all successful.
        assertThat(acks()).hasSize(chunks.size + 1)
        assertThat(acks().map { it.status }.toSet()).containsExactly(C.STATUS_SUCCESS)
    }

    @Test
    fun `packets that are not part of the photo protocol are left to other handlers`() = runTest {
        val receiver = receiver(TestScope(testScheduler))
        assertThat(receiver.processPacket(byteArrayOf())).isFalse()
        assertThat(receiver.processPacket(byteArrayOf(0x7F))).isFalse()
        assertThat(sent).isEmpty()
    }

    @Test
    fun `an implausible header is rejected with an error ack`() = runTest {
        val receiver = receiver(TestScope(testScheduler))

        receiver.processPacket(PacketUtils.createStartPacket(0, 1, ByteArray(16)))
        receiver.processPacket(PacketUtils.createStartPacket(C.MAX_PHOTO_SIZE + 1, 1, ByteArray(16)))
        receiver.processPacket(PacketUtils.createStartPacket(10, 0, ByteArray(16)))
        receiver.processPacket(PacketUtils.createStartPacket(10, C.MAX_CHUNKS + 1, ByteArray(16)))
        // Truncated START packet: parsing fails and is reported the same way.
        receiver.processPacket(byteArrayOf(C.PACKET_TYPE_START))

        assertThat(receiver.isReceiving()).isFalse()
        assertThat(acks()).hasSize(5)
        assertThat(acks().map { it.status }.toSet()).containsExactly(C.STATUS_ERROR)
    }

    @Test
    fun `data and end packets without a session are ignored`() = runTest {
        val receiver = receiver(TestScope(testScheduler))

        assertThat(receiver.processPacket(PacketUtils.createDataPacket(0, byteArrayOf(1)))).isTrue()
        assertThat(receiver.processPacket(PacketUtils.createEndPacket(C.STATUS_SUCCESS))).isTrue()

        assertThat(sent).isEmpty()
        assertThat(receiver.transferState.value).isEqualTo(PhotoTransferState.Idle)
    }

    @Test
    fun `a second start packet abandons the unfinished transfer`() = runTest {
        val receiver = receiver(TestScope(testScheduler))
        val photo = byteArrayOf(1, 2, 3)
        val start = PacketUtils.createStartPacket(photo.size, 1, PacketUtils.calculateMD5(photo))

        receiver.processPacket(start)
        receiver.processPacket(PacketUtils.createDataPacket(0, photo))
        receiver.processPacket(start)
        // The new session starts empty, so END reports the missing chunk instead of succeeding.
        receiver.processPacket(PacketUtils.createEndPacket(C.STATUS_SUCCESS))

        assertThat(retries()).containsExactly(0)
        assertThat(receiver.isReceiving()).isTrue()
    }

    @Test
    fun `out of range chunks are refused and corrupt chunks are re-requested`() = runTest {
        val receiver = receiver(TestScope(testScheduler))
        val photo = byteArrayOf(1, 2, 3)
        receiver.processPacket(
            PacketUtils.createStartPacket(photo.size, 1, PacketUtils.calculateMD5(photo))
        )

        receiver.processPacket(PacketUtils.createDataPacket(5, photo))
        assertThat(acks().last().status).isEqualTo(C.STATUS_ERROR)

        // Flip a payload byte after the CRC was computed.
        val corrupted = PacketUtils.createDataPacket(0, photo).also { it[it.size - 1] = 0x7F }
        receiver.processPacket(corrupted)
        assertThat(retries()).containsExactly(0)

        // A packet that cannot be parsed at all is swallowed, not answered.
        val before = sent.size
        receiver.processPacket(byteArrayOf(C.PACKET_TYPE_DATA))
        assertThat(sent).hasSize(before)
    }

    @Test
    fun `a resent chunk does not double count the transferred bytes`() = runTest {
        val receiver = receiver(TestScope(testScheduler))
        val photo = ByteArray(C.CHUNK_SIZE + 1) { 1 }
        val chunks = chunksOf(photo)
        receiver.processPacket(
            PacketUtils.createStartPacket(photo.size, chunks.size, PacketUtils.calculateMD5(photo))
        )

        receiver.processPacket(PacketUtils.createDataPacket(0, chunks[0]))
        receiver.processPacket(PacketUtils.createDataPacket(0, chunks[0]))

        val state = receiver.transferState.value as PhotoTransferState.InProgress
        assertThat(state.currentChunk).isEqualTo(1)
        assertThat(state.bytesTransferred).isEqualTo(chunks[0].size.toLong())
    }

    @Test
    fun `an end packet reporting sender failure ends the transfer`() = runTest {
        val receiver = receiver(TestScope(testScheduler))
        receiver.processPacket(PacketUtils.createStartPacket(3, 1, ByteArray(16)))

        receiver.processPacket(PacketUtils.createEndPacket(C.STATUS_ERROR))

        val state = receiver.transferState.value
        assertThat(state).isInstanceOf(PhotoTransferState.Error::class.java)
        assertThat((state as PhotoTransferState.Error).message).contains("Sender reported error")
        assertThat(receiver.isReceiving()).isFalse()
    }

    @Test
    fun `a payload that does not match the announced digest is rejected`() = runTest {
        val receiver = receiver(TestScope(testScheduler))
        val photo = byteArrayOf(1, 2, 3)
        receiver.processPacket(PacketUtils.createStartPacket(photo.size, 1, ByteArray(16) { 9 }))
        receiver.processPacket(PacketUtils.createDataPacket(0, photo))

        receiver.processPacket(PacketUtils.createEndPacket(C.STATUS_SUCCESS))

        val state = receiver.transferState.value as PhotoTransferState.Error
        assertThat(state.message).contains("MD5")
        assertThat(state.errorCode).isEqualTo(C.STATUS_MD5_ERROR)
    }

    @Test
    fun `a stalled transfer times out waiting for the next chunk`() = runTest {
        val scope = TestScope(testScheduler)
        val receiver = receiver(scope)
        receiver.processPacket(PacketUtils.createStartPacket(3, 2, ByteArray(16)))

        advanceTimeBy(5_001)
        advanceUntilIdle()

        val state = receiver.transferState.value as PhotoTransferState.Error
        assertThat(state.message).contains("next photo chunk")
        assertThat(state.errorCode).isEqualTo(C.STATUS_TIMEOUT)
        assertThat(receiver.isReceiving()).isFalse()
    }

    @Test
    fun `a lost connection while acknowledging fails the transfer`() = runTest {
        val receiver = receiver(TestScope(testScheduler))
        writeSucceeds = false

        receiver.processPacket(PacketUtils.createStartPacket(3, 1, ByteArray(16)))

        val state = receiver.transferState.value as PhotoTransferState.Error
        assertThat(state.message).contains("connection lost")
        assertThat(receiver.isReceiving()).isFalse()
    }

    @Test
    fun `a lost connection while requesting a retry fails the transfer`() = runTest {
        val receiver = receiver(TestScope(testScheduler))
        val photo = byteArrayOf(1, 2, 3)
        receiver.processPacket(
            PacketUtils.createStartPacket(photo.size, 1, PacketUtils.calculateMD5(photo))
        )
        writeSucceeds = false

        val corrupted = PacketUtils.createDataPacket(0, photo).also { it[it.size - 1] = 0x7F }
        receiver.processPacket(corrupted)

        val state = receiver.transferState.value as PhotoTransferState.Error
        assertThat(state.message).contains("connection lost")
    }

    @Test
    fun `reset returns the receiver to idle`() = runTest {
        val receiver = receiver(TestScope(testScheduler))
        receiver.processPacket(PacketUtils.createStartPacket(3, 1, ByteArray(16)))

        receiver.reset()

        assertThat(receiver.transferState.value).isEqualTo(PhotoTransferState.Idle)
        assertThat(receiver.isReceiving()).isFalse()
    }

    @Test
    fun `a received photo compares by payload and timestamp`() {
        val photo = ReceivedPhoto(byteArrayOf(1, 2), timestamp = 5, transferTimeMs = 9)
        assertThat(photo).isEqualTo(ReceivedPhoto(byteArrayOf(1, 2), 5, 99))
        assertThat(photo.hashCode()).isEqualTo(ReceivedPhoto(byteArrayOf(1, 2), 5, 99).hashCode())
        assertThat(photo).isNotEqualTo(ReceivedPhoto(byteArrayOf(1, 3), 5, 9))
        assertThat(photo).isNotEqualTo(ReceivedPhoto(byteArrayOf(1, 2), 6, 9))
        assertThat(photo).isNotEqualTo("not a photo")
    }
}
