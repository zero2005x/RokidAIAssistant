package com.example.rokidphone.service

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothServerSocket
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
 * The server side of [BluetoothSppManager]: the accept loop, its insecure-to-secure
 * fallback and the failures it has to survive.
 *
 * The sibling BluetoothSppManagerTest covers the enum, sending and disconnection;
 * BluetoothSppProtocolTest covers the wire format.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class BluetoothSppListenTest {

    private companion object {
        // Mirrors the manager's own private constants; the SDP record it publishes
        // is part of the contract the glasses client connects against.
        const val SERVICE_NAME = "RokidAIAssistant"
        val APP_UUID: java.util.UUID =
            java.util.UUID.fromString("a1b2c3d4-e5f6-7890-abcd-ef1234567890")
    }

    private lateinit var context: Context
    private lateinit var adapter: BluetoothAdapter
    private lateinit var scope: TestScope
    private lateinit var manager: BluetoothSppManager

    /** Keeps an accepted connection's reader parked so the state stays settled. */
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

    @Before
    fun setUp() {
        context = mockk(relaxed = true)
        val manager0 = mockk<BluetoothManager>(relaxed = true)
        adapter = mockk(relaxed = true)
        every { context.getSystemService(Context.BLUETOOTH_SERVICE) } returns manager0
        every { manager0.adapter } returns adapter
        grantBluetooth(PackageManager.PERMISSION_GRANTED)

        scope = TestScope(UnconfinedTestDispatcher())
        manager = BluetoothSppManager(context, scope)
    }

    @After
    fun tearDown() {
        reader.release()
        manager.stopListening()
        scope.cancel()
    }

    /**
     * The manager checks through ActivityCompat, which resolves to
     * Context.checkPermission rather than Context.checkSelfPermission; stub both so
     * the result does not depend on which androidx path is taken.
     */
    private fun grantBluetooth(result: Int) {
        every { context.checkSelfPermission(any()) } returns result
        every { context.checkPermission(any(), any(), any()) } returns result
    }

    private fun connectedSocket(name: String = "Glasses_2855"): BluetoothSocket {
        val device = mockk<BluetoothDevice>(relaxed = true)
        every { device.name } returns name
        return mockk<BluetoothSocket>(relaxed = true).also {
            every { it.isConnected } returns true
            every { it.remoteDevice } returns device
            every { it.inputStream } returns reader
            every { it.outputStream } returns ByteArrayOutputStream()
        }
    }

    /** A server socket whose accept() blocks until the test finishes. */
    private fun parkedServerSocket(): BluetoothServerSocket =
        mockk<BluetoothServerSocket>(relaxed = true).also {
            every { it.accept() } answers {
                Thread.sleep(3_000)
                throw IOException("test ended")
            }
        }

    private fun awaitState(expected: BluetoothConnectionState, timeoutMs: Long = 8_000) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (manager.connectionState.value == expected) return
            Thread.sleep(10)
        }
        throw AssertionError(
            "Timed out waiting for $expected; state was ${manager.connectionState.value}"
        )
    }

    private fun safeRemoteDeviceName(socket: BluetoothSocket): String =
        BluetoothSppManager::class.java
            .getDeclaredMethod("safeRemoteDeviceName", BluetoothSocket::class.java)
            .apply { isAccessible = true }
            .invoke(manager, socket) as String

    // ==================== Guards ====================

    @Test
    fun `listening without the bluetooth permission opens no server socket`() {
        grantBluetooth(PackageManager.PERMISSION_DENIED)

        manager.startListening()

        assertThat(manager.connectionState.value)
            .isEqualTo(BluetoothConnectionState.DISCONNECTED)
        verify(exactly = 0) {
            adapter.listenUsingInsecureRfcommWithServiceRecord(any(), any())
        }
    }

    // ==================== Opening the server socket ====================

    @Test
    fun `listening announces the service over insecure rfcomm`() {
        every {
            adapter.listenUsingInsecureRfcommWithServiceRecord(any(), any())
        } returns parkedServerSocket()

        manager.startListening()

        awaitState(BluetoothConnectionState.LISTENING)
        verify { adapter.listenUsingInsecureRfcommWithServiceRecord(SERVICE_NAME, APP_UUID) }
        verify(exactly = 0) { adapter.listenUsingRfcommWithServiceRecord(any(), any()) }
    }

    @Test
    fun `a refused insecure socket falls back to the secure one`() {
        every {
            adapter.listenUsingInsecureRfcommWithServiceRecord(any(), any())
        } throws IOException("insecure rfcomm unavailable")
        every {
            adapter.listenUsingRfcommWithServiceRecord(any(), any())
        } returns parkedServerSocket()

        manager.startListening()

        awaitState(BluetoothConnectionState.LISTENING)
        verify { adapter.listenUsingRfcommWithServiceRecord(SERVICE_NAME, APP_UUID) }
    }

    // ==================== Accepting ====================

    @Test
    fun `an accepted connection is reported with the device that made it`() {
        val socket = connectedSocket("Glasses_2855")
        val server = mockk<BluetoothServerSocket>(relaxed = true)
        var first = true
        every { server.accept() } answers {
            if (first) { first = false; socket } else { Thread.sleep(3_000); throw IOException("done") }
        }
        every {
            adapter.listenUsingInsecureRfcommWithServiceRecord(any(), any())
        } returns server

        manager.startListening()

        awaitState(BluetoothConnectionState.CONNECTED)
        assertThat(manager.connectedDeviceName.value).isEqualTo("Glasses_2855")
    }

    @Test
    fun `a security failure ends the accept loop instead of spinning`() {
        every {
            adapter.listenUsingInsecureRfcommWithServiceRecord(any(), any())
        } throws SecurityException("BLUETOOTH_CONNECT revoked")
        every {
            adapter.listenUsingRfcommWithServiceRecord(any(), any())
        } throws SecurityException("BLUETOOTH_CONNECT revoked")

        manager.startListening()

        // DISCONNECTED is also the starting state, so assert the loop actually ran
        // rather than just reading the state back: both socket modes are tried and
        // the security failure then ends the loop instead of retrying forever.
        verify(timeout = 5_000) { adapter.listenUsingRfcommWithServiceRecord(any(), any()) }
        awaitState(BluetoothConnectionState.DISCONNECTED)
    }

    @Test
    fun `the server socket of a finished round is always closed`() {
        val server = mockk<BluetoothServerSocket>(relaxed = true)
        every { server.accept() } throws SecurityException("revoked")
        every {
            adapter.listenUsingInsecureRfcommWithServiceRecord(any(), any())
        } returns server

        manager.startListening()

        // This attempt fails instantly, so LISTENING is not reliably observable by
        // polling. The close in the loop's finally block is the durable evidence
        // that the round ran and cleaned up after itself.
        verify(timeout = 5_000) { server.accept() }
        verify(timeout = 5_000) { server.close() }
    }

    // ==================== Reading the peer name ====================

    @Test
    fun `the remote name is read from the socket`() {
        assertThat(safeRemoteDeviceName(connectedSocket("Glasses_2855")))
            .isEqualTo("Glasses_2855")
    }

    @Test
    fun `a nameless or unreadable peer is described rather than crashing`() {
        val nameless = mockk<BluetoothSocket>(relaxed = true).also {
            every { it.remoteDevice } returns mockk(relaxed = true) {
                every { name } returns null
            }
        }
        assertThat(safeRemoteDeviceName(nameless)).isEqualTo("Unknown device")

        // Reading the name needs BLUETOOTH_CONNECT and may be refused; that must
        // never propagate out of the accept loop.
        val refused = mockk<BluetoothSocket>(relaxed = true).also {
            every { it.remoteDevice } throws SecurityException("needs BLUETOOTH_CONNECT")
        }
        assertThat(safeRemoteDeviceName(refused)).isEqualTo("Unknown device")
    }

    // ==================== Stopping ====================

    @Test
    fun `stopping closes the listening socket`() {
        val server = parkedServerSocket()
        every {
            adapter.listenUsingInsecureRfcommWithServiceRecord(any(), any())
        } returns server
        manager.startListening()
        awaitState(BluetoothConnectionState.LISTENING)

        manager.stopListening()

        verify(timeout = 5_000) { server.close() }
    }
}
