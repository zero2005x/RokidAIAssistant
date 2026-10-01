package com.example.rokidphone.service.cxr

import android.bluetooth.BluetoothDevice
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.rokid.cxr.client.extend.CxrApi
import com.rokid.cxr.client.extend.callbacks.BluetoothStatusCallback
import com.rokid.cxr.client.extend.listeners.AiEventListener
import com.rokid.cxr.client.utils.ValueUtil
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * [CxrMobileManager]'s handling of the CXR-M SDK callbacks and its own state.
 *
 * The SDK's CxrApi singleton is substituted, so nothing here touches a radio. The
 * callbacks are invoked directly, which is how the SDK delivers them.
 */
@RunWith(RobolectricTestRunner::class)
class CxrMobileManagerTest {

    private lateinit var context: Context
    private lateinit var cxrApi: CxrApi
    private lateinit var manager: CxrMobileManager
    private lateinit var device: BluetoothDevice

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        cxrApi = mockk(relaxed = true)
        mockkStatic(CxrApi::class)
        every { CxrApi.getInstance() } returns cxrApi

        device = mockk(relaxed = true)
        every { device.name } returns "Rokid Glasses"
        every { device.address } returns "AA:BB:CC:DD:EE:FF"

        manager = CxrMobileManager(context)
    }

    @After
    fun tearDown() {
        manager.release()
        unmockkAll()
    }

    /** The callback object the manager hands to the SDK. */
    private fun bluetoothCallback(): BluetoothStatusCallback =
        CxrMobileManager::class.java.getDeclaredField("bluetoothCallback")
            .apply { isAccessible = true }
            .get(manager) as BluetoothStatusCallback

    private fun aiEventListener(): AiEventListener =
        CxrMobileManager::class.java.getDeclaredField("aiEventListener")
            .apply { isAccessible = true }
            .get(manager) as AiEventListener

    /**
     * The manager drops SDK callbacks until registration completes; flip the flag so
     * a test can exercise what happens once they are being accepted.
     */
    private fun acceptCallbacks(accept: Boolean = true) {
        CxrMobileManager::class.java.getDeclaredField("isCallbackRegistered")
            .apply { isAccessible = true }
            .setBoolean(manager, accept)
    }

    private fun setLastDevice(value: BluetoothDevice?) {
        CxrMobileManager::class.java.getDeclaredField("lastConnectedDevice")
            .apply { isAccessible = true }
            .set(manager, value)
    }

    private fun retryCount(): Int =
        CxrMobileManager::class.java.getDeclaredField("retryCount")
            .apply { isAccessible = true }
            .getInt(manager)

    // ==================== Initial state ====================

    @Test
    fun `a new manager is disconnected with no wifi link`() {
        assertThat(manager.bluetoothState.value)
            .isEqualTo(CxrMobileManager.BluetoothState.Disconnected)
        assertThat(manager.wifiConnected.value).isFalse()
    }

    @Test
    fun `the sdk is reported as available when its classes are on the classpath`() {
        assertThat(CxrMobileManager.isSdkAvailable()).isTrue()
    }

    // ==================== Connection state reporting ====================

    @Test
    fun `starting a connection reports connecting straight away`() {
        assertThat(manager.initBluetooth(device)).isTrue()

        assertThat(manager.bluetoothState.value)
            .isEqualTo(CxrMobileManager.BluetoothState.Connecting)
    }

    @Test
    fun `a connection callback is ignored until registration completes`() {
        acceptCallbacks(false)

        bluetoothCallback().onConnected()
        bluetoothCallback().onDisconnected()
        bluetoothCallback().onFailed(ValueUtil.CxrBluetoothErrorCode.UNKNOWN)

        // The SDK can fire these during its own deinit; none of them may take effect.
        assertThat(manager.bluetoothState.value)
            .isEqualTo(CxrMobileManager.BluetoothState.Disconnected)
    }

    @Test
    fun `a connection reports the glasses it reached`() {
        acceptCallbacks()
        bluetoothCallback().onConnectionInfo("uuid-1", "AA:BB:CC:DD:EE:FF", null, 0)

        bluetoothCallback().onConnected()

        val state = manager.bluetoothState.value as CxrMobileManager.BluetoothState.Connected
        assertThat(state.socketUuid).isEqualTo("uuid-1")
        assertThat(state.macAddress).isEqualTo("AA:BB:CC:DD:EE:FF")
    }

    @Test
    fun `connection info without a uuid or address is a failure`() {
        acceptCallbacks()

        bluetoothCallback().onConnectionInfo(null, "AA:BB:CC:DD:EE:FF", null, 0)

        val state = manager.bluetoothState.value as CxrMobileManager.BluetoothState.Failed
        assertThat(state.error).isEqualTo("Invalid connection info")

        bluetoothCallback().onConnectionInfo("uuid-1", null, null, 0)
        assertThat(manager.bluetoothState.value)
            .isInstanceOf(CxrMobileManager.BluetoothState.Failed::class.java)
    }

    @Test
    fun `a disconnect callback returns the manager to disconnected`() {
        acceptCallbacks()
        bluetoothCallback().onConnectionInfo("uuid-1", "AA:BB:CC:DD:EE:FF", null, 0)
        bluetoothCallback().onConnected()

        bluetoothCallback().onDisconnected()

        assertThat(manager.bluetoothState.value)
            .isEqualTo(CxrMobileManager.BluetoothState.Disconnected)
    }

    @Test
    fun `every bluetooth error code is described`() {
        acceptCallbacks()
        val expected = mapOf(
            ValueUtil.CxrBluetoothErrorCode.PARAM_INVALID to "Parameter invalid",
            ValueUtil.CxrBluetoothErrorCode.BLE_CONNECT_FAILED to "BLE connect failed",
            ValueUtil.CxrBluetoothErrorCode.SOCKET_CONNECT_FAILED to "Socket connect failed",
            ValueUtil.CxrBluetoothErrorCode.UNKNOWN to "Unknown error"
        )

        for ((code, message) in expected) {
            bluetoothCallback().onFailed(code)

            val state = manager.bluetoothState.value as CxrMobileManager.BluetoothState.Failed
            assertThat(state.error).isEqualTo(message)
        }
    }

    @Test
    fun `an unrecognised error code still produces a message`() {
        acceptCallbacks()

        bluetoothCallback().onFailed(null)

        val state = manager.bluetoothState.value as CxrMobileManager.BluetoothState.Failed
        assertThat(state.error).contains("Connection failed")
    }

    // ==================== Retry ====================

    @Test
    fun `a failure with no device to retry does not count as an attempt`() {
        acceptCallbacks()
        setLastDevice(null)

        bluetoothCallback().onFailed(ValueUtil.CxrBluetoothErrorCode.BLE_CONNECT_FAILED)

        assertThat(retryCount()).isEqualTo(0)
    }

    @Test
    fun `retries stop once the limit is reached`() {
        acceptCallbacks()
        setLastDevice(device)

        // Five failures are scheduled, the sixth is refused.
        repeat(6) {
            bluetoothCallback().onFailed(ValueUtil.CxrBluetoothErrorCode.BLE_CONNECT_FAILED)
        }

        assertThat(retryCount()).isEqualTo(5)
    }

    @Test
    fun `a successful connection clears the retry counter`() {
        acceptCallbacks()
        setLastDevice(device)
        bluetoothCallback().onFailed(ValueUtil.CxrBluetoothErrorCode.BLE_CONNECT_FAILED)
        assertThat(retryCount()).isEqualTo(1)

        bluetoothCallback().onConnected()

        assertThat(retryCount()).isEqualTo(0)
    }

    // ==================== AI events ====================

    @Test
    fun `ai key events reach the listeners that were registered`() {
        val seen = mutableListOf<String>()
        manager.setAiEventListener(
            onKeyDown = { seen += "down" },
            onKeyUp = { seen += "up" },
            onExit = { seen += "exit" }
        )

        aiEventListener().onAiKeyDown()
        aiEventListener().onAiKeyUp()

        assertThat(seen).containsExactly("down", "up").inOrder()
        verify { cxrApi.setAiEventListener(any()) }
    }

    @Test
    fun `ai key events are harmless once the listeners are removed`() {
        val seen = mutableListOf<String>()
        manager.setAiEventListener(onKeyDown = { seen += "down" })

        manager.removeAiEventListener()
        aiEventListener().onAiKeyDown()

        assertThat(seen).isEmpty()
        verify { cxrApi.setAiEventListener(null) }
    }

    // ==================== Commands ====================

    @Test
    fun `disconnecting tears down the sdk link and the retry state`() {
        acceptCallbacks()
        setLastDevice(device)
        bluetoothCallback().onFailed(ValueUtil.CxrBluetoothErrorCode.BLE_CONNECT_FAILED)

        manager.disconnectBluetooth()

        assertThat(manager.bluetoothState.value)
            .isEqualTo(CxrMobileManager.BluetoothState.Disconnected)
        assertThat(retryCount()).isEqualTo(0)
        verify { cxrApi.deinitBluetooth() }
    }

    @Test
    fun `an sdk that throws while disconnecting does not propagate`() {
        every { cxrApi.deinitBluetooth() } throws IllegalStateException("sdk is gone")

        manager.disconnectBluetooth()

        // The failure is swallowed, so the caller is not taken down with it.
        assertThat(manager.bluetoothState.value)
            .isEqualTo(CxrMobileManager.BluetoothState.Disconnected)
    }

    @Test
    fun `the connection check reports false when the sdk throws`() {
        every { cxrApi.isBluetoothConnected } throws IllegalStateException("not initialised")

        assertThat(manager.isBluetoothConnected()).isFalse()
    }

    @Test
    fun `the connection check passes the sdk answer through`() {
        every { cxrApi.isBluetoothConnected } returns true

        assertThat(manager.isBluetoothConnected()).isTrue()
    }

    @Test
    fun `camera and display commands return the sdk status`() {
        every { cxrApi.openGlassCamera(any(), any(), any()) } returns ValueUtil.CxrStatus.RESPONSE_SUCCEED
        every { cxrApi.sendTtsContent(any()) } returns ValueUtil.CxrStatus.RESPONSE_SUCCEED

        assertThat(manager.openGlassCamera()).isEqualTo(ValueUtil.CxrStatus.RESPONSE_SUCCEED)
        assertThat(manager.sendTtsContent("hello")).isEqualTo(ValueUtil.CxrStatus.RESPONSE_SUCCEED)
        verify { cxrApi.openGlassCamera(1280, 720, 80) }
    }

    @Test
    fun `a command that throws reports no status rather than failing`() {
        every { cxrApi.openGlassCamera(any(), any(), any()) } throws
            IllegalStateException("glasses not connected")
        every { cxrApi.sendTtsContent(any()) } throws
            IllegalStateException("glasses not connected")

        assertThat(manager.openGlassCamera()).isNull()
        assertThat(manager.sendTtsContent("hello")).isNull()
    }
}
