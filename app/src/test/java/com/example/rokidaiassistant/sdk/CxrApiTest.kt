package com.example.rokidaiassistant.sdk

import android.Manifest
import android.app.Application
import android.bluetooth.BluetoothDevice
import android.os.Looper
import android.os.ParcelUuid
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowBluetoothDevice
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * The mock Rokid CXR SDK surface: permission handling, the delayed connect
 * callback and listener fan-out.
 */
// Runs on S or later so the BLUETOOTH_CONNECT check is actually reached.
@Config(sdk = [34])
@RunWith(RobolectricTestRunner::class)
class CxrApiTest {

    private val application: Application = ApplicationProvider.getApplicationContext()
    private val api = CxrApi.getInstance()
    private val callback = RecordingCallback()

    /** Captures what the SDK reported back, in order. */
    private class RecordingCallback : BluetoothStatusCallback {
        val events = mutableListOf<String>()
        var lastUuid: String? = null
        var lastMac: String? = null
        var lastGlassType: Int = -1
        var lastFailure: Pair<Int, String?>? = null

        override fun onConnectionInfo(uuid: String?, mac: String?, glassType: Int) {
            lastUuid = uuid
            lastMac = mac
            lastGlassType = glassType
            events += "info"
        }

        override fun onConnected() { events += "connected" }
        override fun onDisconnected() { events += "disconnected" }
        override fun onFailed(code: Int, msg: String?) {
            lastFailure = code to msg
            events += "failed"
        }
    }

    private class RecordingAiListener : AiEventListener {
        val events = mutableListOf<String>()
        override fun onAiKeyDown() { events += "down" }
        override fun onAiKeyUp() { events += "up" }
        override fun onAiExit() { events += "exit" }
    }

    private class RecordingAudioListener : AudioStreamListener {
        var data: ByteArray? = null
        var length: Int = -1
        override fun onAudioData(data: ByteArray?, length: Int) {
            this.data = data
            this.length = length
        }
    }

    private fun device(address: String = "AA:BB:CC:DD:EE:FF"): BluetoothDevice =
        ShadowBluetoothDevice.newInstance(address)

    private fun grantBluetooth() =
        shadowOf(application).grantPermissions(Manifest.permission.BLUETOOTH_CONNECT)

    private fun settleConnectDelay() =
        shadowOf(Looper.getMainLooper()).idleFor(600, TimeUnit.MILLISECONDS)

    @Before
    fun setUp() {
        // The SDK is a process-wide singleton, so clear anything a prior test left.
        api.setAiEventListener(null)
        api.setAudioStreamListener(null)
        api.deinitBluetooth()
        settleConnectDelay()
    }

    @After
    fun tearDown() {
        api.setAiEventListener(null)
        api.setAudioStreamListener(null)
        api.deinitBluetooth()
    }

    @Test
    fun `the api is a singleton`() {
        assertThat(CxrApi.getInstance()).isSameInstanceAs(api)
    }

    @Test
    fun `initializing without the connect permission fails instead of reporting info`() {
        api.initBluetooth(application, device(), callback)

        assertThat(callback.events).containsExactly("failed")
        assertThat(callback.lastFailure).isEqualTo(1 to "Missing BLUETOOTH_CONNECT permission")
    }

    @Test
    fun `initializing reports the address and the first advertised uuid`() {
        grantBluetooth()
        val uuid = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
        val target = device().also {
            shadowOf(it).setUuids(arrayOf(ParcelUuid(uuid)))
            shadowOf(it).setName("Rokid Glasses")
        }

        api.initBluetooth(application, target, callback)

        assertThat(callback.events).containsExactly("info")
        assertThat(callback.lastUuid).isEqualTo(uuid.toString())
        assertThat(callback.lastMac).isEqualTo("AA:BB:CC:DD:EE:FF")
        assertThat(callback.lastGlassType).isEqualTo(0)
    }

    @Test
    fun `a device advertising no uuid still reports its address`() {
        grantBluetooth()

        api.initBluetooth(application, device("11:22:33:44:55:66"), callback)

        assertThat(callback.lastUuid).isNull()
        assertThat(callback.lastMac).isEqualTo("11:22:33:44:55:66")
    }

    @Test
    fun `connecting reports success only once the delay has elapsed`() {
        api.connectBluetooth(
            application, uuid = "uuid-1", address = "AA:BB:CC:DD:EE:FF",
            callback = callback, snBytes = ByteArray(8), clientSecret = "secret"
        )

        assertThat(callback.events).isEmpty()

        settleConnectDelay()

        assertThat(callback.events).containsExactly("connected")
    }

    @Test
    fun `a second connect replaces the pending one rather than queueing it`() {
        val first = RecordingCallback()
        api.connectBluetooth(
            application, "uuid-1", "AA:BB:CC:DD:EE:FF", first, ByteArray(4), "secret"
        )
        api.connectBluetooth(
            application, "uuid-2", "AA:BB:CC:DD:EE:FF", callback, ByteArray(4), "secret"
        )

        settleConnectDelay()

        // Only one callback fires, and it is the one registered last.
        assertThat(first.events).isEmpty()
        assertThat(callback.events).containsExactly("connected")
    }

    @Test
    fun `deinitializing cancels a pending connect and reports the disconnect`() {
        api.connectBluetooth(
            application, "uuid-1", "AA:BB:CC:DD:EE:FF", callback, ByteArray(4), "secret"
        )

        api.deinitBluetooth()
        settleConnectDelay()

        assertThat(callback.events).containsExactly("disconnected")
    }

    @Test
    fun `deinitializing twice reports nothing the second time`() {
        api.initBluetooth(application, device(), callback)
        callback.events.clear()

        api.deinitBluetooth()
        api.deinitBluetooth()

        assertThat(callback.events).containsExactly("disconnected")
    }

    @Test
    fun `ai key events reach the registered listener`() {
        val listener = RecordingAiListener()
        api.setAiEventListener(listener)

        api.simulateAiKeyDown()
        api.simulateAiKeyUp()

        assertThat(listener.events).containsExactly("down", "up").inOrder()
    }

    @Test
    fun `audio data reaches the registered listener with its length`() {
        val listener = RecordingAudioListener()
        api.setAudioStreamListener(listener)
        val pcm = ByteArray(320) { it.toByte() }

        api.simulateAudioData(pcm)

        assertThat(listener.data).isEqualTo(pcm)
        assertThat(listener.length).isEqualTo(320)
    }

    @Test
    fun `simulated events are harmless once the listeners are cleared`() {
        val ai = RecordingAiListener()
        val audio = RecordingAudioListener()
        api.setAiEventListener(ai)
        api.setAudioStreamListener(audio)

        api.setAiEventListener(null)
        api.setAudioStreamListener(null)

        api.simulateAiKeyDown()
        api.simulateAiKeyUp()
        api.simulateAudioData(ByteArray(16))

        assertThat(ai.events).isEmpty()
        assertThat(audio.data).isNull()
    }

    @Test
    fun `the display and heartbeat calls are accepted in any connection state`() {
        // These are pure no-ops in the mock SDK; they must never throw.
        api.sendAsrContent("hello")
        api.notifyAsrEnd()
        api.sendTtsContent("hi there")
        api.notifyTtsAudioFinished()
        api.sendAi_Heartbeat()
    }
}
