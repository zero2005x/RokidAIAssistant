package io.github.zero2005x.glassesaicompanion.service.cxr

import android.bluetooth.BluetoothDevice
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.rokid.cxr.client.extend.CxrApi
import com.rokid.cxr.client.extend.callbacks.BluetoothStatusCallback
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
 * Starting, restarting, disconnecting and releasing the legacy CXR link. The SDK's [CxrApi]
 * is substituted, so these only observe what the manager asks the SDK to do.
 */
@RunWith(RobolectricTestRunner::class)
class CxrMobileManagerLifecycleTest {

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

    private fun bluetoothCallback(): BluetoothStatusCallback =
        CxrMobileManager::class.java.getDeclaredField("bluetoothCallback")
            .apply { isAccessible = true }
            .get(manager) as BluetoothStatusCallback

    private fun field(name: String) = CxrMobileManager::class.java.getDeclaredField(name)
        .apply { isAccessible = true }

    @Test
    fun `a released manager refuses to start and never schedules a retry`() {
        manager.release()

        assertThat(manager.initBluetooth(device)).isFalse()
        assertThat(manager.bluetoothState.value)
            .isEqualTo(CxrMobileManager.BluetoothState.Disconnected)

        field("isCallbackRegistered").setBoolean(manager, true)
        field("lastConnectedDevice").set(manager, device)
        bluetoothCallback().onFailed(ValueUtil.CxrBluetoothErrorCode.BLE_CONNECT_FAILED)

        assertThat(field("retryCount").getInt(manager)).isEqualTo(0)
    }

    @Test
    fun `starting again replaces the attempt in flight and reaches the sdk`() {
        assertThat(manager.initBluetooth(device)).isTrue()
        assertThat(manager.initBluetooth(device)).isTrue()

        // The SDK is initialised once the short settle delay after the pre-deinit has passed.
        verify(timeout = 5_000, atLeast = 1) { cxrApi.initBluetooth(any(), device, any()) }
        assertThat(field("isCallbackRegistered").getBoolean(manager)).isTrue()
    }

    @Test
    fun `disconnecting stops a start that has not reached the sdk yet`() {
        manager.initBluetooth(device)

        manager.disconnectBluetooth()
        Thread.sleep(700)

        verify(exactly = 0) { cxrApi.initBluetooth(any(), any(), any()) }
        verify(timeout = 5_000, atLeast = 1) { cxrApi.deinitBluetooth() }
        assertThat(manager.bluetoothState.value)
            .isEqualTo(CxrMobileManager.BluetoothState.Disconnected)
    }

    @Test
    fun `an sdk that fails to deinitialise on disconnect is tolerated`() {
        every { cxrApi.deinitBluetooth() } throws IllegalStateException("sdk is gone")
        manager.initBluetooth(device)

        manager.disconnectBluetooth()

        verify(timeout = 5_000, atLeast = 1) { cxrApi.deinitBluetooth() }
        Thread.sleep(200)
        assertThat(manager.bluetoothState.value)
            .isEqualTo(CxrMobileManager.BluetoothState.Disconnected)
    }
}
