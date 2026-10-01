package com.example.rokidphone.service

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.rokidcommon.protocol.Message
import com.example.rokidcommon.protocol.MessageType
import com.example.rokidcommon.protocol.photo.PacketUtils
import com.example.rokidcommon.protocol.photo.PhotoTransferConstants as C
import com.example.rokidcommon.protocol.photo.PhotoTransferState
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn

/**
 * Exercises the RFCOMM framing that [BluetoothSppManager] performs on the bytes
 * arriving from the glasses: newline-delimited JSON interleaved with binary photo
 * packets, plus the voice-buffer accounting. The stream side is a plain
 * [ByteArrayOutputStream], so no Bluetooth hardware or socket is involved.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class BluetoothSppProtocolTest {

    private lateinit var manager: BluetoothSppManager
    private lateinit var scope: TestScope
    /** Unconfined so a collector registers synchronously before any emission. */
    private val collectors = CoroutineScope(UnconfinedTestDispatcher())
    private val written = ByteArrayOutputStream()
    private val messageBuffer = StringBuilder()
    private var stream: OutputStream = written

    private val processMethod = BluetoothSppManager::class.java.getDeclaredMethod(
        "processReceivedData", ByteArray::class.java, StringBuilder::class.java, Continuation::class.java
    ).apply { isAccessible = true }

    private fun field(name: String, value: Any?) {
        BluetoothSppManager::class.java.getDeclaredField(name)
            .apply { isAccessible = true }.set(manager, value)
    }

    @Suppress("UNCHECKED_CAST")
    private fun connectionState() = BluetoothSppManager::class.java.getDeclaredField("_connectionState")
        .apply { isAccessible = true }.get(manager) as MutableStateFlow<BluetoothConnectionState>

    /** Feeds one RFCOMM read into the manager's framing. */
    private suspend fun feed(vararg data: ByteArray) {
        for (chunk in data) {
            suspendCoroutineUninterceptedOrReturn<Unit> { continuation ->
                processMethod.invoke(manager, chunk, messageBuffer, continuation)
            }
        }
    }

    private fun collectMessages(): List<Message> {
        val received = mutableListOf<Message>()
        collectors.launch { manager.messageFlow.collect { received += it } }
        return received
    }

    private fun line(message: Message) = (message.toJson() + "\n").toByteArray()

    private fun sentMessages(): List<Message> = written.toString(Charsets.UTF_8.name())
        .split("\n").filter { it.isNotBlank() }.mapNotNull { Message.fromJson(it) }

    private fun sentPackets(): List<ByteArray> {
        val bytes = written.toByteArray()
        val packets = mutableListOf<ByteArray>()
        var offset = 0
        while (offset + C.ACK_PACKET_SIZE <= bytes.size) {
            packets += bytes.copyOfRange(offset, offset + C.ACK_PACKET_SIZE)
            offset += C.ACK_PACKET_SIZE
        }
        return packets
    }

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        scope = TestScope(UnconfinedTestDispatcher())
        manager = BluetoothSppManager(context, scope)
        field("outputStream", stream)
        connectionState().value = BluetoothConnectionState.CONNECTED
    }

    @After
    fun tearDown() {
        collectors.cancel()
        scope.cancel()
    }

    @Test
    fun `complete json lines are published and partial lines wait for the rest`() = runTest {
        val received = collectMessages()

        val display = Message(type = MessageType.DISPLAY_TEXT, payload = "hello")
        val json = display.toJson()
        feed(json.substring(0, 5).toByteArray())
        assertThat(received).isEmpty()
        feed((json.substring(5) + "\n").toByteArray())

        assertThat(received.map { it.type }).containsExactly(MessageType.DISPLAY_TEXT)
        assertThat(received.single().payload).isEqualTo("hello")
    }

    /**
     * The heartbeat reply is launched on the manager's scope and written from
     * Dispatchers.IO, so it lands on a real thread rather than the test scheduler.
     */
    private fun awaitWrite(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline && !condition()) Thread.sleep(5)
    }

    @Test
    fun `a heartbeat is answered on the same stream`() = runTest {
        feed(line(Message(type = MessageType.HEARTBEAT)))
        awaitWrite { sentMessages().isNotEmpty() }

        assertThat(sentMessages().map { it.type }).containsExactly(MessageType.HEARTBEAT_ACK)
    }

    @Test
    fun `unparsable lines are skipped without dropping the rest of the stream`() = runTest {
        val received = collectMessages()

        feed("not json\n".toByteArray())
        feed("{\"type\":999999}\n".toByteArray())
        feed("\n".toByteArray())
        feed(line(Message(type = MessageType.DISPLAY_TEXT, payload = "after")))

        assertThat(received.map { it.payload }).containsExactly("after")
    }

    @Test
    fun `voice chunks are concatenated and published when recording ends`() = runTest {
        val received = collectMessages()

        feed(line(Message(type = MessageType.VOICE_START)))
        feed(line(Message(type = MessageType.VOICE_DATA, binaryData = byteArrayOf(1, 2))))
        feed(line(Message(type = MessageType.VOICE_DATA, binaryData = byteArrayOf(3))))
        feed(line(Message(type = MessageType.VOICE_END)))

        assertThat(received.map { it.type })
            .containsExactly(MessageType.VOICE_START, MessageType.VOICE_END).inOrder()
        assertThat(received.last().binaryData).isEqualTo(byteArrayOf(1, 2, 3))
    }

    @Test
    fun `audio carried on the end message wins over the accumulated buffer`() = runTest {
        val received = collectMessages()

        feed(line(Message(type = MessageType.VOICE_START)))
        feed(line(Message(type = MessageType.VOICE_DATA, binaryData = byteArrayOf(1, 2))))
        feed(line(Message(type = MessageType.VOICE_END, binaryData = byteArrayOf(7, 8, 9))))

        assertThat(received.last().binaryData).isEqualTo(byteArrayOf(7, 8, 9))
    }

    @Test
    fun `an oversized recording is abandoned and reported once`() = runTest {
        val received = collectMessages()
        val megabyte = ByteArray(1024 * 1024)

        feed(line(Message(type = MessageType.VOICE_START)))
        repeat(17) { feed(line(Message(type = MessageType.VOICE_DATA, binaryData = megabyte))) }

        val errors = received.filter { it.type == MessageType.SYSTEM_ERROR }
        assertThat(errors).hasSize(1)
        assertThat(errors.single().payload).contains("size limit")
        // The peer is told too, exactly once.
        assertThat(sentMessages().count { it.type == MessageType.SYSTEM_ERROR }).isEqualTo(1)

        // The aborted recording produces no VOICE_END payload, and the next one works.
        feed(line(Message(type = MessageType.VOICE_END)))
        assertThat(received.none { it.type == MessageType.VOICE_END }).isTrue()
        feed(line(Message(type = MessageType.VOICE_DATA, binaryData = byteArrayOf(4))))
        feed(line(Message(type = MessageType.VOICE_END)))
        assertThat(received.last().binaryData).isEqualTo(byteArrayOf(4))
    }

    @Test
    fun `a binary photo packet arriving whole is handed to the receiver`() = runTest {
        val photo = byteArrayOf(1, 2, 3)
        feed(PacketUtils.createStartPacket(photo.size, 1, PacketUtils.calculateMD5(photo)))

        val state = manager.photoTransferState.value
        assertThat(state).isInstanceOf(PhotoTransferState.InProgress::class.java)
        assertThat((state as PhotoTransferState.InProgress).totalChunks).isEqualTo(1)
        assertThat(PacketUtils.parsePacketType(sentPackets().single())).isEqualTo(C.PACKET_TYPE_ACK)
    }

    @Test
    fun `a photo packet split across reads is reassembled`() = runTest {
        val photo = byteArrayOf(1, 2, 3)
        val start = PacketUtils.createStartPacket(photo.size, 1, PacketUtils.calculateMD5(photo))
        val data = PacketUtils.createDataPacket(0, photo)

        feed(start.copyOfRange(0, 4), start.copyOfRange(4, start.size))
        // A DATA header split before its length field is known.
        feed(data.copyOfRange(0, 2), data.copyOfRange(2, 5), data.copyOfRange(5, data.size))
        feed(PacketUtils.createEndPacket(C.STATUS_SUCCESS))

        val state = manager.photoTransferState.value
        assertThat(state).isInstanceOf(PhotoTransferState.Success::class.java)
        assertThat((state as PhotoTransferState.Success).data).isEqualTo(photo)
    }

    @Test
    fun `a json line followed by a photo packet in one read is split correctly`() = runTest {
        val received = collectMessages()
        val photo = byteArrayOf(1, 2, 3)
        val start = PacketUtils.createStartPacket(photo.size, 1, PacketUtils.calculateMD5(photo))

        feed(line(Message(type = MessageType.DISPLAY_TEXT, payload = "before")) + start)

        assertThat(received.map { it.payload }).containsExactly("before")
        assertThat(manager.photoTransferState.value)
            .isInstanceOf(PhotoTransferState.InProgress::class.java)
    }

    @Test
    fun `messages are only written while the link is up`() = runTest {
        assertThat(manager.sendMessage(Message(type = MessageType.DISPLAY_TEXT, payload = "x"))).isTrue()
        assertThat(sentMessages().single().payload).isEqualTo("x")

        connectionState().value = BluetoothConnectionState.DISCONNECTED
        assertThat(manager.sendMessage(Message(type = MessageType.DISPLAY_TEXT))).isFalse()
    }

    @Test
    fun `a write failure drops the connection`() = runTest {
        field("outputStream", object : OutputStream() {
            override fun write(b: Int) = throw IOException("pipe closed")
            override fun write(b: ByteArray) = throw IOException("pipe closed")
        })

        assertThat(manager.sendMessage(Message(type = MessageType.DISPLAY_TEXT))).isFalse()
        assertThat(manager.connectionState.value).isEqualTo(BluetoothConnectionState.DISCONNECTED)
    }

    @Test
    fun `bluetooth availability is reported from the platform adapter`() {
        // From Android 12 BLUETOOTH_CONNECT is a runtime permission.
        assertThat(manager.hasBluetoothPermission()).isFalse()
        org.robolectric.Shadows.shadowOf(
            ApplicationProvider.getApplicationContext<android.app.Application>()
        ).grantPermissions(android.Manifest.permission.BLUETOOTH_CONNECT)
        assertThat(manager.hasBluetoothPermission()).isTrue()

        val adapter = ApplicationProvider.getApplicationContext<Context>()
            .getSystemService(android.bluetooth.BluetoothManager::class.java).adapter
        org.robolectric.Shadows.shadowOf(adapter).setEnabled(true)
        assertThat(manager.isBluetoothEnabled()).isTrue()
        org.robolectric.Shadows.shadowOf(adapter).setEnabled(false)
        assertThat(manager.isBluetoothEnabled()).isFalse()

        assertThat(manager.connectedDevice).isNull()
        assertThat(manager.connectedDeviceName.value).isNull()
    }
}
