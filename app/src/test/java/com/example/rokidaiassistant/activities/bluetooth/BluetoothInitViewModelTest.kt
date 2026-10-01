package com.example.rokidaiassistant.activities.bluetooth

import android.Manifest
import android.app.Application
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import com.example.rokidaiassistant.sdk.BluetoothStatusCallback
import com.example.rokidaiassistant.sdk.CxrApi
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class BluetoothInitViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private val scope = TestScope(dispatcher)
    private val cxrApi = mockk<CxrApi>(relaxed = true)
    private lateinit var application: Application
    private lateinit var model: BluetoothInitViewModel

    /** The private BLE scan callback, so scan results can be delivered directly. */
    private fun scanCallback(): ScanCallback = BluetoothInitViewModel::class.java
        .getDeclaredField("scanCallback").apply { isAccessible = true }
        .get(model) as ScanCallback

    /** The private Rokid connection callback. */
    private fun statusCallback(): BluetoothStatusCallback = BluetoothInitViewModel::class.java
        .getDeclaredField("bluetoothStatusCallback").apply { isAccessible = true }
        .get(model) as BluetoothStatusCallback

    private fun device(address: String, name: String?): BluetoothDevice {
        val device = BluetoothAdapter.getDefaultAdapter().getRemoteDevice(address)
        shadowOf(device).setName(name)
        return device
    }

    private fun scanResult(device: BluetoothDevice): ScanResult = mockk(relaxed = true) {
        every { this@mockk.device } returns device
    }

    private fun grantPermissions() {
        shadowOf(application).grantPermissions(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.ACCESS_FINE_LOCATION
        )
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        application = ApplicationProvider.getApplicationContext()
        mockkObject(CxrApi.Companion)
        every { CxrApi.getInstance() } returns cxrApi
        model = BluetoothInitViewModel()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        unmockkAll()
    }

    @Test
    fun `scanning without the bluetooth permissions is refused`() = scope.runTest {
        model.startScan(application)

        assertThat(model.uiState.value.isScanning).isFalse()
        assertThat(model.uiState.value.error).contains("Bluetooth permission is required")
    }

    @Test
    fun `a device without bluetooth support is reported`() = scope.runTest {
        val context = mockk<Context>(relaxed = true)
        every { context.checkPermission(any(), any(), any()) } returns PackageManager.PERMISSION_GRANTED
        every { context.getSystemService(BluetoothManager::class.java) } returns null

        model.startScan(context)

        assertThat(model.uiState.value.error).isEqualTo("This device does not support Bluetooth")
        assertThat(model.uiState.value.isScanning).isFalse()
    }

    @Test
    fun `a device with no BLE scanner is reported`() = scope.runTest {
        val context = mockk<Context>(relaxed = true)
        every { context.checkPermission(any(), any(), any()) } returns PackageManager.PERMISSION_GRANTED
        every { context.getSystemService(BluetoothManager::class.java) } returns mockk {
            every { adapter } returns mockk { every { bluetoothLeScanner } returns null }
        }

        model.startScan(context)

        assertThat(model.uiState.value.error).isEqualTo("Unable to get BLE scanner")
        assertThat(model.uiState.value.isScanning).isFalse()
    }

    @Test
    fun `a started scan clears the previous results and is not started twice`() = scope.runTest {
        grantPermissions()

        model.startScan(application)
        assertThat(model.uiState.value.isScanning).isTrue()
        assertThat(model.uiState.value.devices).isEmpty()
        assertThat(model.uiState.value.error).isNull()

        // A second start while scanning is a no-op, so the discovered list survives.
        scanCallback().onScanResult(ScanSettings.CALLBACK_TYPE_ALL_MATCHES, scanResult(device("AA:BB:CC:DD:EE:01", "Rokid Glasses")))
        model.startScan(application)
        assertThat(model.uiState.value.devices).hasSize(1)
    }

    @Test
    fun `named devices are collected once each and unnamed ones are skipped`() = scope.runTest {
        grantPermissions()
        model.startScan(application)
        val callback = scanCallback()

        callback.onScanResult(0, scanResult(device("AA:BB:CC:DD:EE:01", "Rokid Glasses")))
        callback.onScanResult(0, scanResult(device("AA:BB:CC:DD:EE:01", "Rokid Glasses")))
        callback.onScanResult(0, scanResult(device("AA:BB:CC:DD:EE:02", null)))
        callback.onBatchScanResults(
            mutableListOf(scanResult(device("AA:BB:CC:DD:EE:03", "Rokid Station")))
        )

        assertThat(model.uiState.value.devices.map { it.address })
            .containsExactly("AA:BB:CC:DD:EE:01", "AA:BB:CC:DD:EE:03").inOrder()
    }

    @Test
    fun `each scan failure code has its own message and stops the scan`() = scope.runTest {
        grantPermissions()
        val expected = mapOf(
            ScanCallback.SCAN_FAILED_ALREADY_STARTED to "Scan already in progress",
            ScanCallback.SCAN_FAILED_APPLICATION_REGISTRATION_FAILED to "Application registration failed",
            ScanCallback.SCAN_FAILED_FEATURE_UNSUPPORTED to "Feature not supported",
            ScanCallback.SCAN_FAILED_INTERNAL_ERROR to "Internal error",
            99 to "Scan failed (99)"
        )
        for ((code, message) in expected) {
            model.startScan(application)
            scanCallback().onScanFailed(code)
            assertThat(model.uiState.value.error).isEqualTo(message)
            assertThat(model.uiState.value.isScanning).isFalse()
        }
    }

    @Test
    fun `stopping a scan clears the scanning flag with or without permission`() = scope.runTest {
        grantPermissions()
        model.startScan(application)
        model.stopScan(application)
        assertThat(model.uiState.value.isScanning).isFalse()

        // Permission revoked between start and stop: still leaves the screen idle.
        model.stopScan(mockk(relaxed = true) {
            every { checkPermission(any(), any(), any()) } returns PackageManager.PERMISSION_DENIED
        })
        assertThat(model.uiState.value.isScanning).isFalse()
    }

    @Test
    fun `connecting without the bluetooth permissions is refused`() = scope.runTest {
        model.connectToDevice(application, device("AA:BB:CC:DD:EE:01", "Rokid Glasses"))

        assertThat(model.uiState.value.isConnecting).isFalse()
        assertThat(model.uiState.value.error).contains("Bluetooth permission is required")
        verify(exactly = 0) { cxrApi.initBluetooth(any(), any(), any()) }
    }

    @Test
    fun `connecting stops the scan and authenticates with the SN file`() = scope.runTest {
        grantPermissions()
        val target = device("AA:BB:CC:DD:EE:01", "Rokid Glasses")
        model.startScan(application)

        model.connectToDevice(application, target)

        assertThat(model.uiState.value.isScanning).isFalse()
        assertThat(model.uiState.value.isConnecting).isTrue()
        assertThat(model.uiState.value.connectedDeviceName).isEqualTo("Rokid Glasses")
        verify { cxrApi.initBluetooth(application, target, any()) }
        verify {
            cxrApi.connectBluetooth(
                application, any(), "AA:BB:CC:DD:EE:01", any(), any(), any()
            )
        }
    }

    @Test
    fun `an unnamed device still connects under a placeholder name`() = scope.runTest {
        grantPermissions()

        model.connectToDevice(application, device("AA:BB:CC:DD:EE:02", null))

        assertThat(model.uiState.value.connectedDeviceName).isEqualTo("Unknown Device")
    }

    @Test
    fun `a failure while initiating the connection is reported`() = scope.runTest {
        grantPermissions()
        every { cxrApi.initBluetooth(any(), any(), any()) } throws IllegalStateException("SDK not ready")

        model.connectToDevice(application, device("AA:BB:CC:DD:EE:01", "Rokid Glasses"))

        assertThat(model.uiState.value.isConnecting).isFalse()
        assertThat(model.uiState.value.error).isEqualTo("Connection failed: SDK not ready")
    }

    @Test
    fun `the sdk connection callbacks drive the screen state`() = scope.runTest {
        grantPermissions()
        model.connectToDevice(application, device("AA:BB:CC:DD:EE:01", "Rokid Glasses"))
        val callback = statusCallback()

        // Informational; nothing on screen changes.
        callback.onConnectionInfo("uuid", "AA:BB:CC:DD:EE:01", 1)
        advanceUntilIdle()
        assertThat(model.uiState.value.isConnected).isFalse()

        callback.onConnected()
        advanceUntilIdle()
        assertThat(model.uiState.value.isConnected).isTrue()
        assertThat(model.uiState.value.isConnecting).isFalse()
        assertThat(model.uiState.value.error).isNull()

        callback.onDisconnected()
        advanceUntilIdle()
        assertThat(model.uiState.value.isConnected).isFalse()
        assertThat(model.uiState.value.connectedDeviceName).isNull()

        callback.onFailed(42, "pairing rejected")
        advanceUntilIdle()
        assertThat(model.uiState.value.isConnecting).isFalse()
        assertThat(model.uiState.value.error).isEqualTo("Connection failed: pairing rejected (42)")

        callback.onFailed(7, null)
        advanceUntilIdle()
        assertThat(model.uiState.value.error).isEqualTo("Connection failed: Unknown error (7)")
    }

    @Test
    fun `disconnecting releases the sdk and resets the screen`() = scope.runTest {
        grantPermissions()
        model.connectToDevice(application, device("AA:BB:CC:DD:EE:01", "Rokid Glasses"))

        model.disconnect()

        verify { cxrApi.deinitBluetooth() }
        assertThat(model.uiState.value).isEqualTo(BluetoothUiState())

        // A failing SDK still resets the screen.
        every { cxrApi.deinitBluetooth() } throws IllegalStateException("already closed")
        model.disconnect()
        assertThat(model.uiState.value).isEqualTo(BluetoothUiState())
    }

    @Test
    fun `a denied permission prompt is reported on the screen`() = scope.runTest {
        model.onPermissionDenied()

        assertThat(model.uiState.value.isScanning).isFalse()
        assertThat(model.uiState.value.isConnecting).isFalse()
        assertThat(model.uiState.value.error).contains("Bluetooth permission is required")
    }
}
