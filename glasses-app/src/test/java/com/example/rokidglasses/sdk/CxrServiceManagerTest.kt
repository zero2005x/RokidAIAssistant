package com.example.rokidglasses.sdk

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * [CxrServiceManager] without the CXR-S native layer.
 *
 * The `cxr-service-bridge` artifact is on the test classpath but its JNI library
 * (`cxr-bridge-jni`) is not, so loading `CXRServiceBridge` raises a [LinkageError].
 * Everything here therefore exercises the degradation paths: the manager must stay
 * usable and report failure rather than propagate an error. Anything that needs a
 * live bridge is covered on-device instead.
 */
@RunWith(RobolectricTestRunner::class)
class CxrServiceManagerTest {

    private val manager = CxrServiceManager.getInstance()

    @Test
    fun `the manager is a singleton`() {
        assertThat(CxrServiceManager.getInstance()).isSameInstanceAs(manager)
    }

    @Test
    fun `the channel names are distinct`() {
        val channels = listOf(
            CxrServiceManager.CHANNEL_PHOTO_REQUEST,
            CxrServiceManager.CHANNEL_PHOTO_RESULT,
            CxrServiceManager.CHANNEL_AI_EVENT,
            CxrServiceManager.CHANNEL_STATUS
        )

        assertThat(channels).containsNoDuplicates()
        for (channel in channels) {
            assertThat(channel).isNotEmpty()
        }
    }

    @Test
    fun `a manager starts disconnected with no link health`() {
        manager.release()

        assertThat(manager.connectionState.value)
            .isEqualTo(CxrServiceManager.ConnectionState.Disconnected)
        assertThat(manager.artcHealth.value).isEqualTo(0f)
    }

    @Test
    fun `initializing without the native bridge reports failure instead of throwing`() {
        assertThat(manager.initialize()).isFalse()

        // A failed initialize must leave the manager in a clean, reusable state.
        assertThat(manager.connectionState.value)
            .isEqualTo(CxrServiceManager.ConnectionState.Disconnected)
    }

    @Test
    fun `sending a photo without the native bridge reports failure`() {
        assertThat(manager.sendPhotoToPhone(ByteArray(64))).isFalse()
        assertThat(manager.sendPhotoToPhone(ByteArray(0), fileName = "empty.webp")).isFalse()
    }

    @Test
    fun `sending a status without the native bridge reports failure`() {
        assertThat(manager.sendStatus("ready")).isFalse()
        assertThat(manager.sendStatus("")).isFalse()
    }

    @Test
    fun `releasing is safe to call repeatedly`() {
        manager.release()
        manager.release()

        assertThat(manager.connectionState.value)
            .isEqualTo(CxrServiceManager.ConnectionState.Disconnected)
    }

    @Test
    fun `a connected state carries the device it names`() {
        val connected = CxrServiceManager.ConnectionState.Connected("Pixel 8", deviceType = 2)

        assertThat(connected.deviceName).isEqualTo("Pixel 8")
        assertThat(connected.deviceType).isEqualTo(2)
        assertThat(connected)
            .isEqualTo(CxrServiceManager.ConnectionState.Connected("Pixel 8", 2))
        assertThat(connected)
            .isNotEqualTo(CxrServiceManager.ConnectionState.Connected("Pixel 8", 3))
        assertThat(connected).isNotEqualTo(CxrServiceManager.ConnectionState.Disconnected)
    }
}
