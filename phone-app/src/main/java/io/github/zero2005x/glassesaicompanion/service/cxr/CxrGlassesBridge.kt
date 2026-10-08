package io.github.zero2005x.glassesaicompanion.service.cxr

import android.bluetooth.BluetoothDevice
import android.content.Context
import com.example.rokidcommon.protocol.Message
import kotlinx.coroutines.CoroutineScope

/**
 * Seam between [io.github.zero2005x.glassesaicompanion.service.PhoneAIService] and the
 * optional Rokid CXR-M SDK integration.
 *
 * The Google Play flavor ships without the SDK, so main code must not reference any
 * `com.rokid.cxr` type. Each flavor provides its own [createCxrGlassesBridge]:
 * - `github`: wraps the real SDK (`CxrMobileManager`).
 * - `play`: returns a no-op bridge.
 */
interface CxrGlassesBridge {
    /** True when this build actually talks to the CXR SDK. */
    val isSupported: Boolean

    /** Initialise the SDK and start listening for glasses-side AI key events. */
    fun start()

    /** The classic Bluetooth (SPP) link to [device] is up; the SDK may start its own link. */
    fun onSppConnected(device: BluetoothDevice)

    /**
     * The SPP link dropped, or the glasses app reported that it takes photos over SPP itself.
     * Either way the SDK link is not needed (any more).
     */
    fun onSppDisconnected()

    /** Release SDK resources. */
    fun release()
}

/** Callbacks a [CxrGlassesBridge] needs from the service that owns it. */
interface CxrHost {
    val context: Context
    val scope: CoroutineScope

    /** Send a protocol message to the glasses over the SPP link. */
    suspend fun sendToGlasses(message: Message)

    /** A photo was captured through the SDK and is ready for AI analysis. */
    suspend fun onPhotoCaptured(photoData: ByteArray)

    /** True when the glasses app takes photos over the SPP link, which the SDK must then leave alone. */
    val companionCamera: Boolean

    /** Ask the glasses app to take a photo over the SPP link. */
    suspend fun requestCompanionPhoto()
}
