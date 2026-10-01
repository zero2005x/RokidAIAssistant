package com.example.rokidglasses.service

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.pm.PackageManager
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The connect path of [BluetoothSppClient]: socket creation fallbacks, the states a
 * connection attempt moves through, and what happens when an attempt fails.
 *
 * The sibling BluetoothSppClientTest covers the enum, the initial state and sending;
 * BluetoothSppFramingTest covers the wire format.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class BluetoothSppConnectTest {

    private lateinit var context: Context
    private lateinit var adapter: BluetoothAdapter
    private lateinit var scope: TestScope
    private lateinit var client: BluetoothSppClient
    private lateinit var device: BluetoothDevice

    /**
     * Keeps the reader coroutine parked so the connection stays up for the length of
     * a test; an immediately-closed stream would tear it down again as we assert.
     */
    private class ParkedInputStream : InputStream() {
        private val gate = CountDownLatch(1)
        override fun read(): Int = readBlocking()
        override fun read(b: ByteArray, off: Int, len: Int): Int = readBlocking()
        private fun readBlocking(): Int {
            gate.await(5, TimeUnit.SECONDS)
            return -1
        }
        fun release() = gate.countDown()
    }

    private val reader = ParkedInputStream()
    private val written = ByteArrayOutputStream()

    @Before
    fun setUp() {
        context = mockk(relaxed = true)
        val manager = mockk<BluetoothManager>(relaxed = true)
        adapter = mockk(relaxed = true)
        every { context.getSystemService(Context.BLUETOOTH_SERVICE) } returns manager
        every { manager.adapter } returns adapter
        every { context.checkSelfPermission(any()) } returns PackageManager.PERMISSION_GRANTED

        device = mockk(relaxed = true)
        every { device.name } returns "Rokid Phone"
        every { device.address } returns "AA:BB:CC:DD:EE:FF"

        scope = TestScope(UnconfinedTestDispatcher())
        client = BluetoothSppClient(context, scope)
    }

    @After
    fun tearDown() {
        reader.release()
        scope.cancel()
    }

    /** A socket that connects cleanly and stays up. */
    private fun workingSocket(): BluetoothSocket = mockk<BluetoothSocket>(relaxed = true).also {
        every { it.isConnected } returns true
        every { it.inputStream } returns reader
        every { it.outputStream } returns written
    }

    private fun createSocket(attempt: Int): BluetoothSocket? =
        BluetoothSppClient::class.java
            .getDeclaredMethod("createSocket", BluetoothDevice::class.java, Int::class.java)
            .apply { isAccessible = true }
            .invoke(client, device, attempt) as BluetoothSocket?

    private fun awaitState(expected: BluetoothClientState, timeoutMs: Long = 8_000) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (client.connectionState.value == expected) return
            Thread.sleep(10)
        }
        throw AssertionError(
            "Timed out waiting for $expected; state was ${client.connectionState.value}"
        )
    }

    /**
     * Waits for a failed attempt to finish.
     *
     * DISCONNECTED is also the starting state, so waiting for it alone would return
     * before the connect coroutine had run at all and assert nothing. Observing
     * CONNECTING first proves the attempt started; the client holds that state for
     * the 200ms discovery-cancellation pause, so it is reliably visible.
     */
    private fun awaitFailedAttempt() {
        awaitState(BluetoothClientState.CONNECTING)
        awaitState(BluetoothClientState.DISCONNECTED)
    }

    // ==================== Socket creation fallbacks ====================

    @Test
    fun `the first attempt uses the insecure uuid lookup`() {
        val socket = workingSocket()
        every { device.createInsecureRfcommSocketToServiceRecord(any()) } returns socket

        assertThat(createSocket(1)).isSameInstanceAs(socket)
        verify { device.createInsecureRfcommSocketToServiceRecord(BluetoothSppClient.SERVICE_UUID) }
    }

    @Test
    fun `the second attempt falls back to the secure uuid lookup`() {
        val socket = workingSocket()
        every { device.createRfcommSocketToServiceRecord(any()) } returns socket

        assertThat(createSocket(2)).isSameInstanceAs(socket)
        verify { device.createRfcommSocketToServiceRecord(BluetoothSppClient.SERVICE_UUID) }
    }

    @Test
    fun `a uuid lookup that throws yields no socket rather than propagating`() {
        every {
            device.createInsecureRfcommSocketToServiceRecord(any())
        } throws IOException("service discovery failed")
        every {
            device.createRfcommSocketToServiceRecord(any())
        } throws IOException("service discovery failed")

        assertThat(createSocket(1)).isNull()
        assertThat(createSocket(2)).isNull()
    }

    @Test
    fun `the reflection based channel attempts never throw`() {
        // Attempts 3, 4 and 5 reach for hidden platform methods by reflection.
        // Whether the method resolves depends on the platform, so the contract
        // under test is only that each attempt returns rather than raising.
        for (attempt in listOf(3, 4, 5, 99)) {
            assertThat(runCatching { createSocket(attempt) }.isSuccess).isTrue()
        }
    }

    // ==================== Connecting ====================

    @Test
    fun `a successful attempt reports the connected device`() {
        every { device.createInsecureRfcommSocketToServiceRecord(any()) } returns workingSocket()

        client.connect(device, maxRetries = 1)

        awaitState(BluetoothClientState.CONNECTED)
        assertThat(client.connectedDeviceName.value).isEqualTo("Rokid Phone")
        assertThat(client.connectedSocket).isNotNull()
    }

    @Test
    fun `discovery is stopped before the socket is opened`() {
        every { device.createInsecureRfcommSocketToServiceRecord(any()) } returns workingSocket()

        client.connect(device, maxRetries = 1)

        awaitState(BluetoothClientState.CONNECTED)
        verify { adapter.cancelDiscovery() }
    }

    @Test
    fun `a socket that cannot be created leaves the client disconnected`() {
        every { device.createInsecureRfcommSocketToServiceRecord(any()) } returns null

        client.connect(device, maxRetries = 1)

        awaitFailedAttempt()
        assertThat(client.connectedSocket).isNull()
        assertThat(client.connectedDeviceName.value).isNull()
    }

    @Test
    fun `a socket that refuses the connection leaves the client disconnected`() {
        val socket = mockk<BluetoothSocket>(relaxed = true)
        every { socket.connect() } throws IOException("read failed, socket might closed")
        every { device.createInsecureRfcommSocketToServiceRecord(any()) } returns socket

        client.connect(device, maxRetries = 1)

        awaitFailedAttempt()
        // The failed socket is closed rather than leaked.
        verify { socket.close() }
    }

    @Test
    fun `a socket that connects but reports itself closed is treated as a failure`() {
        val socket = mockk<BluetoothSocket>(relaxed = true)
        every { socket.isConnected } returns false
        every { device.createInsecureRfcommSocketToServiceRecord(any()) } returns socket

        client.connect(device, maxRetries = 1)

        awaitFailedAttempt()
        assertThat(client.connectedSocket).isNull()
    }

    @Test
    fun `connecting without the bluetooth permission does nothing`() {
        every { context.checkSelfPermission(any()) } returns PackageManager.PERMISSION_DENIED

        client.connect(device, maxRetries = 1)

        assertThat(client.connectionState.value).isEqualTo(BluetoothClientState.DISCONNECTED)
        verify(exactly = 0) { device.createInsecureRfcommSocketToServiceRecord(any()) }
    }

    @Test
    fun `the device name is withheld when the permission is missing`() {
        every { context.checkSelfPermission(any()) } returns PackageManager.PERMISSION_DENIED

        val name = BluetoothSppClient::class.java
            .getDeclaredMethod("getSafeDeviceName", BluetoothDevice::class.java)
            .apply { isAccessible = true }
            .invoke(client, device) as String

        assertThat(name).isEqualTo("unknown (missing permission)")
    }

    // ==================== Connecting by address ====================

    @Test
    fun `connecting by address resolves the device through the adapter`() {
        every { adapter.getRemoteDevice("AA:BB:CC:DD:EE:FF") } returns device
        every { device.createInsecureRfcommSocketToServiceRecord(any()) } returns workingSocket()

        client.connectByAddress("AA:BB:CC:DD:EE:FF")

        awaitState(BluetoothClientState.CONNECTED)
    }

    @Test
    fun `an address the adapter cannot resolve is ignored`() {
        every { adapter.getRemoteDevice(any<String>()) } returns null

        client.connectByAddress("11:22:33:44:55:66")

        assertThat(client.connectionState.value).isEqualTo(BluetoothClientState.DISCONNECTED)
    }

    // ==================== Paired devices ====================

    @Test
    fun `paired devices are listed when the permission is held`() {
        every { adapter.bondedDevices } returns setOf(device)

        assertThat(client.getPairedDevices()).containsExactly(device)
    }

    @Test
    fun `an adapter with no bonded devices lists nothing`() {
        every { adapter.bondedDevices } returns null

        assertThat(client.getPairedDevices()).isEmpty()
    }
}
