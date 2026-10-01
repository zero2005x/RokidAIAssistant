package com.example.rokidglasses.service.photo

import com.example.rokidcommon.protocol.photo.PacketUtils
import com.example.rokidcommon.protocol.photo.PhotoTransferConstants as C
import com.example.rokidcommon.protocol.photo.PhotoTransferState
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
class PhotoTransferProtocolTest {
    @Test
    fun `acknowledged chunks preserve payload order and report completion`() = runTest {
        val control = MutableSharedFlow<ByteArray>()
        val packets = mutableListOf<ByteArray>()
        val progress = mutableListOf<Pair<Int, Int>>()
        val protocol = PhotoTransferProtocol({ packet ->
            packets += packet
            when (PacketUtils.parsePacketType(packet)) {
                C.PACKET_TYPE_START -> control.emit(PacketUtils.createAckPacket(0, C.STATUS_SUCCESS))
                C.PACKET_TYPE_DATA -> {
                    val index = PacketUtils.parseDataPacket(packet).chunkIndex
                    control.emit(PacketUtils.createAckPacket(index + 100, C.STATUS_SUCCESS))
                    control.emit(PacketUtils.createRetryPacket(index + 100))
                    control.emit(PacketUtils.createEndPacket(C.STATUS_SUCCESS))
                    control.emit(PacketUtils.createAckPacket(index, C.STATUS_SUCCESS))
                }
            }
            true
        }, control) { current, total -> progress += current to total }
        val photo = ByteArray(C.CHUNK_SIZE + 3) { (it % 127).toByte() }
        val statistics = protocol.sendPhoto(photo).getOrThrow()
        assertThat(statistics.totalBytes).isEqualTo(photo.size)
        assertThat(statistics.totalChunks).isEqualTo(2)
        assertThat(statistics.retryCount).isEqualTo(0)
        assertThat(progress).containsExactly(1 to 2, 2 to 2).inOrder()
        assertThat(packets.map { PacketUtils.parsePacketType(it) })
            .containsExactly(C.PACKET_TYPE_START, C.PACKET_TYPE_DATA, C.PACKET_TYPE_DATA, C.PACKET_TYPE_END).inOrder()
        assertThat(protocol.transferState.value).isInstanceOf(PhotoTransferState.Success::class.java)
        protocol.reset()
        assertThat(protocol.transferState.value).isEqualTo(PhotoTransferState.Idle)
    }

    @Test
    fun `receiver retry resends the chunk and increments retry statistics`() = runTest {
        val control = MutableSharedFlow<ByteArray>()
        var attempts = 0
        val protocol = PhotoTransferProtocol({ packet ->
            when (PacketUtils.parsePacketType(packet)) {
                C.PACKET_TYPE_START -> control.emit(PacketUtils.createAckPacket(0, C.STATUS_SUCCESS))
                C.PACKET_TYPE_DATA -> {
                    attempts++
                    control.emit(if (attempts == 1) PacketUtils.createRetryPacket(0)
                        else PacketUtils.createAckPacket(0, C.STATUS_SUCCESS))
                }
            }
            true
        }, control)
        assertThat(protocol.sendPhoto(byteArrayOf(1)).getOrThrow().retryCount).isEqualTo(1)
        assertThat(attempts).isEqualTo(2)
    }

    @Test
    fun `repeated failed writes stop retrying and send an error end packet`() = runTest {
        val control = MutableSharedFlow<ByteArray>()
        var attempts = 0
        var ended = false
        val protocol = PhotoTransferProtocol({ packet ->
            when (PacketUtils.parsePacketType(packet)) {
                C.PACKET_TYPE_START -> { control.emit(PacketUtils.createAckPacket(0, C.STATUS_SUCCESS)); true }
                C.PACKET_TYPE_DATA -> { attempts++; false }
                else -> { ended = true; true }
            }
        }, control)
        assertThat(protocol.sendPhoto(byteArrayOf(1)).isFailure).isTrue()
        assertThat(attempts).isEqualTo(C.MAX_RETRY_COUNT)
        assertThat(ended).isTrue()
    }

    @Test
    fun `invalid photo sizes are rejected without writing packets`() = runTest {
        val protocol = PhotoTransferProtocol({ error("No packet should be sent") }, MutableSharedFlow())
        assertThat(protocol.sendPhoto(byteArrayOf()).exceptionOrNull()).isInstanceOf(IllegalArgumentException::class.java)
        assertThat(protocol.sendPhoto(ByteArray(C.MAX_PHOTO_SIZE + 1)).isFailure).isTrue()
    }

    @Test
    fun `failed start writes and IO errors are returned to the caller`() = runTest {
        assertThat(PhotoTransferProtocol({ false }, MutableSharedFlow()).sendPhoto(byteArrayOf(1)).isFailure).isTrue()
        val failure = PhotoTransferProtocol({ throw IOException("disconnected") }, MutableSharedFlow())
            .sendPhoto(byteArrayOf(1)).exceptionOrNull()
        assertThat(failure!!.message).isEqualTo("disconnected")
    }

    @Test
    fun `cancel stops an active acknowledgement wait and resets state`() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val control = MutableSharedFlow<ByteArray>()
        val protocol = PhotoTransferProtocol({ started.complete(Unit); true }, control)
        val transfer = async { protocol.sendPhoto(byteArrayOf(1)) }
        withTimeout(3000) { started.await() }
        protocol.cancelTransfer()
        transfer.join()
        assertThat(transfer.isCancelled).isTrue()
        assertThat(protocol.transferState.value).isInstanceOf(PhotoTransferState.Error::class.java)
        protocol.reset()
        assertThat(protocol.transferState.value).isEqualTo(PhotoTransferState.Idle)
    }
}
