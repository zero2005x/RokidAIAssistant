package com.example.rokidglasses.viewmodel

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.rokidcommon.protocol.Message
import com.example.rokidglasses.sdk.CxrServiceManager
import com.example.rokidglasses.sdk.UnifiedCameraManager
import com.example.rokidglasses.service.BluetoothClientState
import com.example.rokidglasses.service.BluetoothSppClient
import com.example.rokidglasses.testutil.returnsSuccess
import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockkConstructor
import io.mockk.mockkObject
import io.mockk.unmockkAll
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * [GlassesViewModel] must survive a broken CXR-S SDK.
 *
 * `isSdkAvailable()` calls Class.forName, which runs the class initializer and loads
 * the cxr-bridge-jni native library. Off the glasses that raises UnsatisfiedLinkError
 * — an Error, not an Exception — so the view model needs to catch LinkageError as
 * well, or it dies during construction and the whole screen is gone. The app still
 * works over Bluetooth SPP without CXR, so degrading is the correct behaviour.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class GlassesCxrGuardTest {

    private val fromPhone = MutableSharedFlow<Message>(extraBufferCapacity = 8)
    private val bluetoothState = MutableStateFlow(BluetoothClientState.DISCONNECTED)
    private val deviceName = MutableStateFlow<String?>(null)
    private lateinit var context: Context

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        context = ApplicationProvider.getApplicationContext()
        mockkObject(CxrServiceManager.Companion)
        mockkConstructor(BluetoothSppClient::class, UnifiedCameraManager::class)
        every { anyConstructed<BluetoothSppClient>().messageFlow } returns fromPhone
        every { anyConstructed<BluetoothSppClient>().connectionState } returns bluetoothState
        every { anyConstructed<BluetoothSppClient>().connectedDeviceName } returns deviceName
        every { anyConstructed<BluetoothSppClient>().getPairedDevices() } returns emptyList()
        coEvery { anyConstructed<BluetoothSppClient>().sendMessage(any()) } returns true
        coEvery { anyConstructed<UnifiedCameraManager>().initialize() } returnsSuccess Unit
        every { anyConstructed<UnifiedCameraManager>().getCameraTypeName() } returns "Camera2 API"
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        unmockkAll()
    }

    /** The view model is usable: its initial on-glass prompt is present. */
    private fun assertUsable(model: GlassesViewModel) {
        assertThat(model.uiState.value.displayText).isNotEmpty()
        assertThat(model.uiState.value.cxrConnectedPhoneName).isNull()
    }

    @Test
    fun `a missing native library does not take the screen down`() {
        every {
            CxrServiceManager.isSdkAvailable()
        } throws UnsatisfiedLinkError("no cxr-bridge-jni in java.library.path")

        assertUsable(GlassesViewModel(context))
    }

    @Test
    fun `any other linkage failure is also survivable`() {
        every {
            CxrServiceManager.isSdkAvailable()
        } throws NoClassDefFoundError("com/rokid/cxr/CXRServiceBridge")

        assertUsable(GlassesViewModel(context))
    }

    @Test
    fun `an sdk that throws an ordinary exception does not take the screen down`() {
        every {
            CxrServiceManager.isSdkAvailable()
        } throws IllegalStateException("CXR service unavailable")

        assertUsable(GlassesViewModel(context))
    }

    @Test
    fun `a failure while initializing the service is survivable`() {
        every { CxrServiceManager.isSdkAvailable() } returns true
        every { CxrServiceManager.getInstance() } throws RuntimeException("bridge refused")

        assertUsable(GlassesViewModel(context))
    }

    @Test
    fun `an absent sdk is simply skipped`() {
        every { CxrServiceManager.isSdkAvailable() } returns false

        assertUsable(GlassesViewModel(context))
    }
}
