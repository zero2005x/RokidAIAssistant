package io.github.zero2005x.glassesaicompanion.service.cxr

import android.bluetooth.BluetoothDevice

/**
 * Google Play flavor: the Rokid CXR-M SDK is not bundled, so the bridge does nothing.
 * Glasses features use the classic Bluetooth (SPP) link only.
 */
fun createCxrGlassesBridge(host: CxrHost): CxrGlassesBridge = NoOpCxrGlassesBridge

private object NoOpCxrGlassesBridge : CxrGlassesBridge {
    override val isSupported: Boolean = false
    override fun start() = Unit
    override fun onSppConnected(device: BluetoothDevice) = Unit
    override fun onSppDisconnected() = Unit
    override fun release() = Unit
}
