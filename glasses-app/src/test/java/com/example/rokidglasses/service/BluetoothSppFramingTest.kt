package com.example.rokidglasses.service

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.rokidcommon.protocol.Message
import com.example.rokidcommon.protocol.MessageType
import com.example.rokidcommon.protocol.photo.PacketUtils
import com.example.rokidcommon.protocol.photo.PhotoTransferConstants as C
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn

/**
 * Framing and message handling in [BluetoothSppClient]: newline-delimited JSON
 * interleaved with photo ACK/RETRY frames, written to and read from in-memory
 * streams so no Bluetooth socket is involved.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class BluetoothSppFramingTest {

    private lateinit var client: BluetoothSppClient
    private val clientScope = CoroutineScope(UnconfinedTestDispatcher())
    /** Unconfined so a collector registers synchronously before any emission. */
    private val collectors = CoroutineScope(UnconfinedTestDispatcher())
    private val written = ByteArrayOutputStream()

    private val parseMethod = BluetoothSppClient::class.java.getDeclaredMethod(
        "parseAndEmitMessage", String::class.java, Continuation::class.java
    ).apply { isAccessible = true }

    private val startReading = BluetoothSppClient::class.java
        .getDeclaredMethod("startReading").apply { isAccessible = true }

    private fun field(name: String, value: Any?) {
        BluetoothSppClient::class.java.getDeclaredField(name)
            .apply { isAccessible = true }.set(client, value)
    }

    @Suppress("UNCHECKED_CAST")
    private fun connectionState() = BluetoothSppClient::class.java
        .getDeclaredField("_connectionState").apply { isAccessible = true }
        .get(client) as MutableStateFlow<BluetoothClientState>

    private suspend fun parse(json: String) {
        suspendCoroutineUninterceptedOrReturn<Unit> { continuation ->
            parseMethod.invoke(client, json, continuation)
        }
    }

    private fun collectMessages(): List<Message> {
        val received = mutableListOf<Message>()
        collectors.launch { client.messageFlow.collect { received += it } }
        return received
    }

    private fun collectPackets(): List<ByteArray> {
        val received = mutableListOf<ByteArray>()
        collectors.launch { client.photoControlPackets.collect { received += it } }
        return received
    }

    /**
     * Runs the read loop over [data] until the stream is exhausted. The loop reads on
     * Dispatchers.IO, so its job is joined rather than driven by the test scheduler.
     */
    private suspend fun readAll(data: ByteArray) {
        field("inputStream", ByteArrayInputStream(data))
        connectionState().value = BluetoothClientState.CONNECTED
        startReading.invoke(client)
        val readJob = BluetoothSppClient::class.java.getDeclaredField("readJob")
            .apply { isAccessible = true }.get(client) as Job
        readJob.join()
    }

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        client = BluetoothSppClient(context, clientScope)
        field("outputStream", written)
        connectionState().value = BluetoothClientState.CONNECTED
    }

    @After
    fun tearDown() {
        collectors.cancel()
        clientScope.cancel()
    }

    @Test
    fun `a message is written as one newline terminated json frame`() = runTest {
        assertThat(client.sendMessage(Message(type = MessageType.VOICE_START))).isTrue()

        val frame = written.toString(Charsets.UTF_8.name())
        assertThat(frame).endsWith("\n")
        assertThat(Message.fromJson(frame.trim())!!.type).isEqualTo(MessageType.VOICE_START)
    }

    @Test
    fun `nothing is written while the link is down`() = runTest {
        connectionState().value = BluetoothClientState.DISCONNECTED

        assertThat(client.sendMessage(Message(type = MessageType.VOICE_START))).isFalse()
        assertThat(written.size()).isEqualTo(0)
    }

    @Test
    fun `voice helpers send the start signal and the recorded audio`() = runTest {
        assertThat(client.sendVoiceStart()).isTrue()
        assertThat(client.sendVoiceEnd(byteArrayOf(1, 2, 3))).isTrue()

        val frames = written.toString(Charsets.UTF_8.name())
            .split("\n").filter { it.isNotBlank() }.map { Message.fromJson(it)!! }
        assertThat(frames.map { it.type })
            .containsExactly(MessageType.VOICE_START, MessageType.VOICE_END).inOrder()
        assertThat(frames.last().binaryData).isEqualTo(byteArrayOf(1, 2, 3))
    }

    @Test
    fun `a raw packet is written verbatim and a write failure drops the link`() = runTest {
        assertThat(client.sendRawPacket(byteArrayOf(7, 8))).isTrue()
        assertThat(written.toByteArray()).isEqualTo(byteArrayOf(7, 8))

        field("outputStream", object : OutputStream() {
            override fun write(b: Int) = throw IOException("pipe closed")
            override fun write(b: ByteArray) = throw IOException("pipe closed")
        })
        assertThat(client.sendRawPacket(byteArrayOf(1))).isFalse()
        assertThat(client.connectionState.value).isEqualTo(BluetoothClientState.DISCONNECTED)

        // With no stream at all there is nothing to write to.
        connectionState().value = BluetoothClientState.CONNECTED
        field("outputStream", null)
        assertThat(client.sendRawPacket(byteArrayOf(1))).isFalse()
    }

    @Test
    fun `the photo transfer protocol writes through this connection`() = runTest {
        val progress = mutableListOf<Pair<Int, Int>>()
        val protocol = client.createPhotoTransferProtocol { current, total -> progress += current to total }

        assertThat(protocol).isNotNull()
        // The protocol refuses an empty photo before writing anything.
        assertThat(protocol.sendPhoto(byteArrayOf()).isFailure).isTrue()
        assertThat(written.size()).isEqualTo(0)
        assertThat(progress).isEmpty()
    }

    @Test
    fun `known message types are published and a heartbeat ack resets the counter`() = runTest {
        val received = collectMessages()
        field("missedHeartbeatCount", 2)

        parse(Message(type = MessageType.DISPLAY_TEXT, payload = "hello").toJson())
        parse(Message(type = MessageType.HEARTBEAT_ACK).toJson())
        parse(Message(type = MessageType.AI_RESPONSE_TTS, binaryData = byteArrayOf(4, 5)).toJson())

        assertThat(received.map { it.type }).containsExactly(
            MessageType.DISPLAY_TEXT, MessageType.HEARTBEAT_ACK, MessageType.AI_RESPONSE_TTS
        ).inOrder()
        assertThat(received.first().payload).isEqualTo("hello")
        assertThat(received.last().binaryData).isEqualTo(byteArrayOf(4, 5))

        val counter = BluetoothSppClient::class.java.getDeclaredField("missedHeartbeatCount")
            .apply { isAccessible = true }
        assertThat(counter.getInt(client)).isEqualTo(0)
    }

    @Test
    fun `text that is not a usable message is ignored`() = runTest {
        val received = collectMessages()

        parse("not json at all")
        parse("")
        parse("{ broken json")
        parse("{\"type\":999999}")
        parse("{\"type\":${MessageType.DISPLAY_TEXT.code},\"binaryData\":\"!!!not base64!!!\"}")

        // Only the last one is a known type; its unusable payload becomes null.
        assertThat(received).hasSize(1)
        assertThat(received.single().type).isEqualTo(MessageType.DISPLAY_TEXT)
    }

    @Test
    fun `a very long unparsable frame is still ignored`() = runTest {
        val received = collectMessages()

        parse("{" + "x".repeat(600))

        assertThat(received).isEmpty()
    }

    @Test
    fun `the read loop splits json frames and photo control packets`() = runBlocking {
        val received = collectMessages()
        val packets = collectPackets()
        val ack = PacketUtils.createAckPacket(3, C.STATUS_SUCCESS)
        val retry = PacketUtils.createRetryPacket(4)
        val json = (Message(type = MessageType.DISPLAY_TEXT, payload = "hi").toJson() + "\n")
            .toByteArray()

        readAll(json + ack + retry + json + "\n".toByteArray())

        assertThat(received.map { it.payload }).containsExactly("hi", "hi")
        assertThat(packets.map { PacketUtils.parsePacketType(it) })
            .containsExactly(C.PACKET_TYPE_ACK, C.PACKET_TYPE_RETRY).inOrder()
        assertThat(PacketUtils.parseAckPacket(packets.first()).chunkIndex).isEqualTo(3)
        assertThat(PacketUtils.parseRetryPacket(packets.last())).isEqualTo(4)
        // The stream ending closes the connection.
        assertThat(client.connectionState.value).isNotEqualTo(BluetoothClientState.CONNECTED)
    }

    @Test
    fun `an unterminated json frame is held until the rest arrives`() = runBlocking {
        val received = collectMessages()
        val json = Message(type = MessageType.DISPLAY_TEXT, payload = "partial").toJson()

        // No trailing newline: the frame is incomplete and must not be published.
        readAll(json.toByteArray())

        assertThat(received).isEmpty()
    }

    @Test
    fun `paired devices need the bluetooth permission`() {
        // Robolectric grants no runtime permissions by default.
        assertThat(client.getPairedDevices()).isEmpty()
    }
}
