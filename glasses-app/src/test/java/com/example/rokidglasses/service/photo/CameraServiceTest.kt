package com.example.rokidglasses.service.photo

import android.app.Application
import android.app.NotificationManager
import android.bluetooth.BluetoothSocket
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.example.rokidglasses.testutil.returnsSuccess
import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.Runs
import io.mockk.unmockkAll
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import java.io.ByteArrayOutputStream

/**
 * Lifecycle, capture guards and notification handling for [CameraService].
 *
 * The Camera2 manager is substituted, so nothing here opens a camera; the transfer
 * itself is covered by PhotoTransferProtocolTest.
 */
@RunWith(RobolectricTestRunner::class)
class CameraServiceTest {

    private val application: Application = ApplicationProvider.getApplicationContext()
    private var controller: ServiceController<CameraService>? = null

    private val photo = ByteArray(256) { it.toByte() }

    @Before
    fun setUp() {
        mockkConstructor(GlassesCameraManager::class)
        coEvery { anyConstructed<GlassesCameraManager>().initialize() } returnsSuccess Unit
        coEvery { anyConstructed<GlassesCameraManager>().capturePhoto() } returns photo
        every { anyConstructed<GlassesCameraManager>().hasCameraPermission() } returns true
        every { anyConstructed<GlassesCameraManager>().release() } just Runs
    }

    @After
    fun tearDown() {
        controller?.destroy()
        controller = null
        unmockkAll()
    }

    private fun start(): CameraService {
        val created = Robolectric.buildService(CameraService::class.java)
        controller = created
        return created.create().get()
    }

    private fun disconnectedSocket() = mockk<BluetoothSocket>(relaxed = true).also {
        every { it.isConnected } returns false
    }

    private fun connectedSocket() = mockk<BluetoothSocket>(relaxed = true).also {
        every { it.isConnected } returns true
        every { it.outputStream } returns ByteArrayOutputStream()
        every { it.inputStream } returns java.io.ByteArrayInputStream(ByteArray(0))
    }

    // ==================== Lifecycle ====================

    @Test
    fun `the service runs in the foreground while it is alive`() {
        val service = start()

        assertThat(CameraService.isRunning).isTrue()
        assertThat(shadowOf(service).lastForegroundNotification).isNotNull()
        assertThat(shadowOf(service).lastForegroundNotificationId).isEqualTo(2001)
    }

    @Test
    fun `a low importance channel is created for the capture notice`() {
        start()

        val channel = application.getSystemService(NotificationManager::class.java)
            .getNotificationChannel("camera_service_channel")

        assertThat(channel).isNotNull()
        assertThat(channel.importance).isEqualTo(NotificationManager.IMPORTANCE_LOW)
    }

    @Test
    fun `destroying the service releases the camera and clears the running flag`() {
        start()

        controller!!.destroy()
        controller = null

        assertThat(CameraService.isRunning).isFalse()
        io.mockk.verify { anyConstructed<GlassesCameraManager>().release() }
    }

    @Test
    fun `the service is bindable and hands back itself`() {
        val service = start()

        val binder = service.onBind(Intent()) as CameraService.CameraServiceBinder

        assertThat(binder.getService()).isSameInstanceAs(service)
    }

    @Test
    fun `the service does not restart itself after being stopped`() {
        val service = start()

        assertThat(service.onStartCommand(null, 0, 1))
            .isEqualTo(android.app.Service.START_NOT_STICKY)
    }

    @Test
    fun `a stop command stops the service`() {
        val service = start()

        service.onStartCommand(Intent(CameraService.ACTION_STOP), 0, 1)

        assertThat(shadowOf(service).isStoppedBySelf).isTrue()
    }

    @Test
    fun `an unknown action is ignored`() {
        val service = start()

        service.onStartCommand(Intent("com.example.unknown"), 0, 1)

        assertThat(shadowOf(service).isStoppedBySelf).isFalse()
        assertThat(service.captureState.value).isEqualTo(PhotoCaptureState.Idle)
    }

    // ==================== Capture guards ====================

    @Test
    fun `capturing without the camera permission fails before opening anything`() = runBlocking {
        val service = start()
        every { anyConstructed<GlassesCameraManager>().hasCameraPermission() } returns false

        val result = service.captureAndSend()

        assertThat(result.exceptionOrNull()).isInstanceOf(SecurityException::class.java)
        assertThat(service.captureState.value).isInstanceOf(PhotoCaptureState.Error::class.java)
    }

    @Test
    fun `capturing without a bluetooth link fails before taking a photo`() = runBlocking {
        val service = start()

        val result = service.captureAndSend()

        assertThat(result.exceptionOrNull()).isInstanceOf(IllegalStateException::class.java)
        val state = service.captureState.value as PhotoCaptureState.Error
        assertThat(state.message).isNotEmpty()
    }

    @Test
    fun `a socket that is not connected is treated as no link`() = runBlocking {
        val service = start()
        service.setBluetoothSocket(disconnectedSocket())

        val result = service.captureAndSend()

        assertThat(result.isFailure).isTrue()
    }

    @Test
    fun `an error during capture is reported to the listener`() = runBlocking {
        val service = start()
        val errors = mutableListOf<String>()
        service.onError = { errors += it }

        service.captureAndSend()

        assertThat(errors).isNotEmpty()
    }

    // ==================== Capture only ====================

    @Test
    fun `a local capture returns the photo and reports success`() = runBlocking {
        val service = start()

        val captured = service.captureOnly()

        assertThat(captured).isEqualTo(photo)
        assertThat(service.captureState.value).isInstanceOf(PhotoCaptureState.Success::class.java)
    }

    @Test
    fun `a local capture that produces nothing is reported as an error`() = runBlocking {
        val service = start()
        coEvery { anyConstructed<GlassesCameraManager>().capturePhoto() } returns null

        assertThat(service.captureOnly()).isNull()

        val state = service.captureState.value as PhotoCaptureState.Error
        assertThat(state.message).isEqualTo("Capture failed")
    }

    @Test
    fun `a local capture that throws is reported rather than propagated`() = runBlocking {
        val service = start()
        coEvery {
            anyConstructed<GlassesCameraManager>().capturePhoto()
        } throws RuntimeException("sensor timeout")

        assertThat(service.captureOnly()).isNull()

        val state = service.captureState.value as PhotoCaptureState.Error
        assertThat(state.message).isEqualTo("sensor timeout")
    }

    // ==================== Bluetooth wiring ====================

    @Test
    fun `attaching a connected socket prepares the transfer protocol`() {
        val service = start()

        service.setBluetoothSocket(connectedSocket())

        // The service stays idle until a capture is asked for.
        assertThat(service.captureState.value).isEqualTo(PhotoCaptureState.Idle)
    }

    @Test
    fun `detaching the socket is safe and can be repeated`() {
        val service = start()
        service.setBluetoothSocket(connectedSocket())

        service.setBluetoothSocket(null)
        service.setBluetoothSocket(null)

        assertThat(service.captureState.value).isEqualTo(PhotoCaptureState.Idle)
    }

    @Test
    fun `replacing the socket drops the previous reader`() {
        val service = start()
        service.setBluetoothSocket(connectedSocket())

        service.setBluetoothSocket(connectedSocket())

        assertThat(service.captureState.value).isEqualTo(PhotoCaptureState.Idle)
    }
}
