package io.github.zero2005x.glassesaicompanion.service.cxr

import android.bluetooth.BluetoothDevice
import android.util.Log
import com.example.rokidcommon.protocol.Message
import com.rokid.cxr.client.utils.ValueUtil
import io.github.zero2005x.glassesaicompanion.R
import io.github.zero2005x.glassesaicompanion.data.SettingsRepository
import kotlinx.coroutines.launch

/** GitHub flavor: the real Rokid CXR-M SDK integration. */
fun createCxrGlassesBridge(host: CxrHost): CxrGlassesBridge = SdkCxrGlassesBridge(host)

private class SdkCxrGlassesBridge(private val host: CxrHost) : CxrGlassesBridge {

    private companion object {
        private const val TAG = "CxrGlassesBridge"
    }

    private var manager: CxrMobileManager? = null

    override val isSupported: Boolean = true

    /**
     * Initialize CXR-M SDK for Rokid glasses connection. This enables:
     * - AI key event listening (long press on glasses)
     * - Remote photo capture from glasses
     */
    override fun start() {
        if (!CxrMobileManager.isSdkAvailable()) {
            Log.w(TAG, "CXR-M SDK not available")
            return
        }

        try {
            val cxr = CxrMobileManager(host.context)
            manager = cxr

            // Set AI event listener for glasses key press
            cxr.setAiEventListener(
                onKeyDown = {
                    Log.d(TAG, "CXR: AI key pressed on glasses")
                    // Trigger photo capture when AI key is pressed
                    host.scope.launch {
                        if (host.companionCamera) host.requestCompanionPhoto() else capturePhotoFromGlasses()
                    }
                },
                onKeyUp = {
                    Log.d(TAG, "CXR: AI key released")
                },
                onExit = {
                    Log.d(TAG, "CXR: AI scene exited")
                }
            )

            // Monitor CXR Bluetooth connection state
            host.scope.launch {
                cxr.bluetoothState.collect { state ->
                    Log.d(TAG, "CXR Bluetooth state: $state")
                    when (state) {
                        is CxrMobileManager.BluetoothState.Connected -> {
                            Log.d(TAG, "CXR connected: ${state.macAddress}")
                        }
                        is CxrMobileManager.BluetoothState.Disconnected -> {
                            Log.d(TAG, "CXR disconnected")
                        }
                        is CxrMobileManager.BluetoothState.Failed -> {
                            Log.e(TAG, "CXR connection failed: ${state.error}")
                        }
                        else -> {}
                    }
                }
            }

            Log.d(TAG, "CXR-M SDK initialized")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize CXR-M SDK", e)
        }
    }

    override fun onSppConnected(device: BluetoothDevice) {
        manager?.initBluetooth(device)
    }

    override fun onSppDisconnected() {
        manager?.disconnectBluetooth()
    }

    override fun release() {
        manager?.release()
        manager = null
    }

    /** Capture a photo from the glasses using the CXR-M SDK. */
    private suspend fun capturePhotoFromGlasses() {
        val context = host.context
        val cxr = manager ?: run {
            Log.w(TAG, "CXR manager not available, using legacy photo transfer")
            return
        }

        if (!cxr.isBluetoothConnected()) {
            Log.w(TAG, "CXR not connected to glasses")
            host.sendToGlasses(Message.aiError(context.getString(R.string.glasses_not_connected_cxr)))
            return
        }

        // Check API key before triggering photo capture (provider-agnostic)
        val apiKey = SettingsRepository.getInstance(context).getSettings().getCurrentApiKey()
        if (apiKey.isBlank()) {
            Log.e(TAG, "API key is not configured, aborting photo capture")
            host.sendToGlasses(Message.aiError(context.getString(R.string.api_key_not_configured)))
            return
        }

        Log.d(TAG, "Capturing photo from glasses via CXR SDK...")

        // Notify glasses: taking photo
        cxr.sendTtsContent(context.getString(R.string.taking_photo))

        val status = cxr.takePhoto(
            width = 1280,
            height = 720,
            quality = 80
        ) { resultStatus, photoData ->
            host.scope.launch {
                when (resultStatus) {
                    ValueUtil.CxrStatus.RESPONSE_SUCCEED -> {
                        if (photoData?.isNotEmpty() == true) {
                            Log.d(TAG, "CXR photo received: ${photoData.size} bytes")
                            host.onPhotoCaptured(photoData)
                        } else {
                            Log.e(TAG, "CXR photo is empty")
                            host.sendToGlasses(Message.aiError(context.getString(R.string.photo_empty)))
                        }
                    }
                    ValueUtil.CxrStatus.RESPONSE_TIMEOUT -> {
                        Log.e(TAG, "CXR photo timeout")
                        host.sendToGlasses(Message.aiError(context.getString(R.string.photo_timeout)))
                    }
                    else -> {
                        Log.e(TAG, "CXR photo failed: $resultStatus")
                        host.sendToGlasses(
                            Message.aiError(context.getString(R.string.photo_capture_failed, resultStatus))
                        )
                    }
                }
            }
        }

        Log.d(TAG, "CXR takePhoto request status: $status")
    }
}
