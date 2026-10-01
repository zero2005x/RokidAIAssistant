package com.example.rokidglasses.service.photo

import android.Manifest
import android.app.Application
import android.content.Context
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.util.ReflectionHelpers

/**
 * The parts of [GlassesCameraManager] that do not need a camera: the device check,
 * EXIF orientation normalisation, permission handling and resource teardown.
 *
 * Opening a Camera2 device is not possible in a JVM test, so the capture path is
 * covered only through its guards; the rest is verified on-device.
 */
@RunWith(RobolectricTestRunner::class)
class GlassesCameraManagerTest {

    private lateinit var context: Context
    private lateinit var manager: GlassesCameraManager
    private lateinit var originalModel: String

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        originalModel = Build.MODEL
        manager = GlassesCameraManager(context)
    }

    @After
    fun tearDown() {
        setModel(originalModel)
        manager.release()
    }

    private fun setModel(model: String) =
        ReflectionHelpers.setStaticField(Build::class.java, "MODEL", model)

    private fun grantCamera() =
        shadowOf(ApplicationProvider.getApplicationContext<Application>())
            .grantPermissions(Manifest.permission.CAMERA)

    private fun invokePrivate(name: String) =
        GlassesCameraManager::class.java.getDeclaredMethod(name)
            .apply { isAccessible = true }
            .invoke(manager)

    // ==================== Device detection ====================

    @Test
    fun `rokid hardware is recognised regardless of case`() {
        for (model in listOf("Rokid Glasses", "ROKID-X2", "rokid air")) {
            setModel(model)
            assertThat(manager.isRokidGlassesDevice()).isTrue()
        }
    }

    @Test
    fun `other hardware is not treated as rokid glasses`() {
        for (model in listOf("Pixel 8", "SM-G998B", "")) {
            setModel(model)
            assertThat(manager.isRokidGlassesDevice()).isFalse()
        }
    }

    // ==================== Orientation ====================

    @Test
    fun `an image with no orientation tag is returned untouched`() = runBlocking {
        val jpeg = ByteArray(512) { it.toByte() }

        // Same instance, not just equal: nothing should be re-encoded needlessly.
        assertThat(manager.normalizeOrientation(jpeg)).isSameInstanceAs(jpeg)
    }

    @Test
    fun `bytes that are not an image are returned rather than raising`() = runBlocking {
        val notAnImage = "this is not a jpeg".toByteArray()

        assertThat(manager.normalizeOrientation(notAnImage)).isSameInstanceAs(notAnImage)
        assertThat(manager.normalizeOrientation(ByteArray(0)))
            .isEqualTo(ByteArray(0))
    }

    // ==================== Permission ====================

    @Test
    fun `the camera permission is reported as the system sees it`() {
        assertThat(manager.hasCameraPermission()).isFalse()

        grantCamera()

        assertThat(manager.hasCameraPermission()).isTrue()
    }

    @Test
    fun `capturing without the camera permission yields nothing`() = runBlocking {
        assertThat(manager.capturePhoto()).isNull()
    }

    @Test
    fun `initializing without a usable camera reports failure`() = runBlocking {
        // Robolectric exposes no camera devices, so there is nothing to open.
        val result = manager.initialize()

        assertThat(result.isFailure).isTrue()
    }

    // ==================== Teardown ====================

    @Test
    fun `releasing is safe before anything was opened and can be repeated`() {
        manager.release()
        manager.release()

        // Still usable afterwards: release must not leave a broken object behind.
        assertThat(manager.hasCameraPermission()).isFalse()
    }

    @Test
    fun `closing the camera with nothing open does not raise`() {
        invokePrivate("closeCamera")
        invokePrivate("closeCamera")
    }

    @Test
    fun `the background thread can be started and stopped repeatedly`() {
        invokePrivate("startBackgroundThread")
        invokePrivate("stopBackgroundThread")
        invokePrivate("startBackgroundThread")
        invokePrivate("stopBackgroundThread")

        // Stopping one that was never started must also be safe.
        invokePrivate("stopBackgroundThread")
    }
}
