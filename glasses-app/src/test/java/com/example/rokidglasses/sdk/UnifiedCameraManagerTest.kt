package com.example.rokidglasses.sdk

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.rokidglasses.service.photo.GlassesCameraManager
import com.example.rokidglasses.testutil.returnsFailure
import com.example.rokidglasses.testutil.returnsSuccess
import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.every
import io.mockk.just
import io.mockk.mockkConstructor
import io.mockk.Runs
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * State transitions in [UnifiedCameraManager]. The underlying Camera2 manager is
 * substituted, so nothing here opens a real camera.
 */
@RunWith(RobolectricTestRunner::class)
class UnifiedCameraManagerTest {

    private lateinit var context: Context
    private lateinit var manager: UnifiedCameraManager

    private val photo = ByteArray(128) { it.toByte() }

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        mockkConstructor(GlassesCameraManager::class)
        every { anyConstructed<GlassesCameraManager>().release() } just Runs
        coEvery { anyConstructed<GlassesCameraManager>().initialize() } returnsSuccess Unit
        coEvery { anyConstructed<GlassesCameraManager>().capturePhoto() } returns photo
        manager = UnifiedCameraManager(context)
    }

    @After
    fun tearDown() = unmockkAll()

    private fun initialize() = runBlocking { manager.initialize() }

    // ==================== Before initialization ====================

    @Test
    fun `a new manager is idle and reports the camera2 backend`() {
        assertThat(manager.cameraState.value).isEqualTo(UnifiedCameraState.Idle)
        assertThat(manager.getCurrentMode()).isEqualTo(CameraMode.CAMERA2)
        assertThat(manager.getCameraTypeName()).isEqualTo("Camera2 API")
        assertThat(manager.isUsingCxrSdk()).isFalse()
    }

    @Test
    fun `capturing before initialization fails without opening anything`() = runBlocking {
        assertThat(manager.capturePhoto()).isNull()

        val state = manager.cameraState.value as UnifiedCameraState.Error
        assertThat(state.message).isEqualTo("Camera not ready")
        assertThat(state.cameraType).isEqualTo("Camera2 API")
    }

    // ==================== Initialization ====================

    @Test
    fun `a successful initialization leaves the camera ready`() {
        val result = initialize()

        assertThat(result.isSuccess).isTrue()
        assertThat(manager.cameraState.value).isEqualTo(UnifiedCameraState.Ready)
        assertThat(manager.getCurrentMode()).isEqualTo(CameraMode.CAMERA2)
    }

    @Test
    fun `the auto mode resolves to camera2 on the glasses`() {
        val auto = UnifiedCameraManager(context, preferredMode = CameraMode.AUTO)

        val result = runBlocking { auto.initialize() }

        assertThat(result.isSuccess).isTrue()
        assertThat(auto.getCurrentMode()).isEqualTo(CameraMode.CAMERA2)
    }

    @Test
    fun `a failed initialization is reported and releases the camera it opened`() {
        coEvery {
            anyConstructed<GlassesCameraManager>().initialize()
        } returnsFailure IllegalStateException("no camera on this device")

        val result = initialize()

        assertThat(result.isFailure).isTrue()
        val state = manager.cameraState.value as UnifiedCameraState.Error
        assertThat(state.message).isEqualTo("no camera on this device")
        verify { anyConstructed<GlassesCameraManager>().release() }
    }

    @Test
    fun `an initialization that throws is reported as an error state`() {
        coEvery {
            anyConstructed<GlassesCameraManager>().initialize()
        } throws RuntimeException("camera service died")

        val result = initialize()

        assertThat(result.isFailure).isTrue()
        assertThat((manager.cameraState.value as UnifiedCameraState.Error).message)
            .isEqualTo("camera service died")
    }

    @Test
    fun `initializing twice releases the first camera before opening another`() {
        initialize()

        initialize()

        assertThat(manager.cameraState.value).isEqualTo(UnifiedCameraState.Ready)
        verify(atLeast = 1) { anyConstructed<GlassesCameraManager>().release() }
    }

    // ==================== Capturing ====================

    @Test
    fun `a ready camera returns the captured photo`() = runBlocking {
        initialize()

        val captured = manager.capturePhoto()

        assertThat(captured).isEqualTo(photo)
        val state = manager.cameraState.value as UnifiedCameraState.Success
        assertThat(state.imageData).isEqualTo(photo)
        assertThat(state.cameraType).isEqualTo("Camera2 API")
    }

    @Test
    fun `a second photo can be taken straight after the first`() = runBlocking {
        initialize()
        manager.capturePhoto()

        val second = ByteArray(64) { 7 }
        coEvery { anyConstructed<GlassesCameraManager>().capturePhoto() } returns second

        assertThat(manager.capturePhoto()).isEqualTo(second)
        assertThat((manager.cameraState.value as UnifiedCameraState.Success).imageData)
            .isEqualTo(second)
    }

    @Test
    fun `a capture that produces nothing is reported rather than returned as empty`() =
        runBlocking {
            initialize()
            coEvery { anyConstructed<GlassesCameraManager>().capturePhoto() } returns null

            assertThat(manager.capturePhoto()).isNull()

            assertThat((manager.cameraState.value as UnifiedCameraState.Error).message)
                .isEqualTo("Capture returned null")
        }

    @Test
    fun `a capture that throws is reported as an error state`() = runBlocking {
        initialize()
        coEvery {
            anyConstructed<GlassesCameraManager>().capturePhoto()
        } throws RuntimeException("sensor timeout")

        assertThat(manager.capturePhoto()).isNull()

        assertThat((manager.cameraState.value as UnifiedCameraState.Error).message)
            .isEqualTo("sensor timeout")
    }

    @Test
    fun `capturing after a failed capture is refused until the camera is ready again`() =
        runBlocking {
            initialize()
            coEvery { anyConstructed<GlassesCameraManager>().capturePhoto() } returns null
            manager.capturePhoto()

            // The state is now Error, which is neither Ready nor Success.
            assertThat(manager.capturePhoto()).isNull()
            assertThat((manager.cameraState.value as UnifiedCameraState.Error).message)
                .isEqualTo("Camera not ready")
        }

    // ==================== Release ====================

    @Test
    fun `releasing returns the manager to idle and frees the camera`() {
        initialize()

        manager.release()

        assertThat(manager.cameraState.value).isEqualTo(UnifiedCameraState.Idle)
        verify { anyConstructed<GlassesCameraManager>().release() }
    }

    @Test
    fun `releasing is safe before initialization and can be repeated`() {
        manager.release()
        manager.release()

        assertThat(manager.cameraState.value).isEqualTo(UnifiedCameraState.Idle)
    }

    @Test
    fun `a released camera refuses to capture`() = runBlocking {
        initialize()
        manager.release()

        assertThat(manager.capturePhoto()).isNull()
        assertThat((manager.cameraState.value as UnifiedCameraState.Error).message)
            .isEqualTo("Camera not ready")
    }

    // ==================== State value type ====================

    @Test
    fun `a successful capture state compares by image content`() {
        val one = UnifiedCameraState.Success(byteArrayOf(1, 2, 3), "Camera2 API")
        val same = UnifiedCameraState.Success(byteArrayOf(1, 2, 3), "Camera2 API")

        assertThat(one).isEqualTo(same)
        assertThat(one).isEqualTo(one)
        assertThat(one.hashCode()).isEqualTo(same.hashCode())
        assertThat(one).isNotEqualTo(UnifiedCameraState.Success(byteArrayOf(1, 2), "Camera2 API"))
        assertThat(one).isNotEqualTo(UnifiedCameraState.Success(byteArrayOf(1, 2, 3), "CXR"))
        assertThat(one).isNotEqualTo(null)
        assertThat(one).isNotEqualTo(UnifiedCameraState.Idle)
    }
}
