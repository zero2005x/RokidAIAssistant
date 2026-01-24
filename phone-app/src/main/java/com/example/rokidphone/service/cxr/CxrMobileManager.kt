package com.example.rokidphone.service.cxr

import android.bluetooth.BluetoothDevice
import android.content.Context
import android.util.Log
import com.rokid.cxr.client.extend.CxrApi
import com.rokid.cxr.client.extend.callbacks.*
import com.rokid.cxr.client.extend.listeners.AiEventListener
import com.rokid.cxr.client.extend.infos.GlassInfo
import com.rokid.cxr.client.utils.ValueUtil
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * CXR-M SDK Manager (Phone Side)
 * 
 * Wraps CxrApi for:
 * - Connecting to glasses via Bluetooth
 * - Listening to AI events (long press button)
 * - Capturing photos
 * - Controlling glasses device
 * 
 * SDK Package: com.rokid.cxr:client-m
 */
class CxrMobileManager(private val context: Context) {
    
    companion object {
        private const val TAG = "CxrMobileManager"
        
        // Check if SDK is available
        fun isSdkAvailable(): Boolean {
            return try {
                Class.forName("com.rokid.cxr.client.extend.CxrApi")
                Log.d(TAG, "CXR-M SDK is available")
                true
            } catch (e: ClassNotFoundException) {
                Log.w(TAG, "CXR-M SDK not found in classpath")
                false
            }
        }
    }
    
    // Connection state
    sealed class BluetoothState {
        object Disconnected : BluetoothState()
        object Connecting : BluetoothState()
        data class Connected(val socketUuid: String, val macAddress: String) : BluetoothState()
        data class Failed(val error: String) : BluetoothState()
    }
    
    private val _bluetoothState = MutableStateFlow<BluetoothState>(BluetoothState.Disconnected)
    val bluetoothState: StateFlow<BluetoothState> = _bluetoothState.asStateFlow()
    
    // WiFi P2P connection state
    private val _wifiConnected = MutableStateFlow(false)
    val wifiConnected: StateFlow<Boolean> = _wifiConnected.asStateFlow()
    
    // AI event callback
    private var onAiKeyDown: (() -> Unit)? = null
    private var onAiKeyUp: (() -> Unit)? = null
    private var onAiExit: (() -> Unit)? = null
    
    // Photo result callback
    private var onPhotoResult: ((status: ValueUtil.CxrStatus?, photoData: ByteArray?) -> Unit)? = null
    
    // Glasses information
    private var glassSocketUuid: String? = null
    private var glassMacAddress: String? = null
    
    // CXR API instance
    private val cxrApi: CxrApi by lazy {
        CxrApi.getInstance().also {
            Log.d(TAG, "CxrApi instance created: $it")
            Log.d(TAG, "SDK version: ${it.javaClass.simpleName}")
        }
    }
    
    // Bluetooth status callback
    private val bluetoothCallback = object : BluetoothStatusCallback {
        override fun onConnectionInfo(
            socketUuid: String?,
            macAddress: String?,
            rokidAccount: String?,
            glassesType: Int
        ) {
            Log.d(TAG, "Connection info: uuid=$socketUuid, mac=$macAddress, type=$glassesType")
            
            if (socketUuid != null && macAddress != null) {
                glassSocketUuid = socketUuid
                glassMacAddress = macAddress
                
                // Continue connection
                connectBluetooth(context, socketUuid, macAddress)
            } else {
                Log.e(TAG, "Invalid connection info")
                _bluetoothState.value = BluetoothState.Failed("Invalid connection info")
            }
        }
        
        override fun onConnected() {
            Log.d(TAG, "Bluetooth connected")
            val uuid = glassSocketUuid ?: ""
            val mac = glassMacAddress ?: ""
            _bluetoothState.value = BluetoothState.Connected(uuid, mac)
        }
        
        override fun onDisconnected() {
            Log.d(TAG, "Bluetooth disconnected")
            _bluetoothState.value = BluetoothState.Disconnected
        }
        
        override fun onFailed(errorCode: ValueUtil.CxrBluetoothErrorCode?) {
            val errorMsg = when (errorCode) {
                ValueUtil.CxrBluetoothErrorCode.PARAM_INVALID -> "Parameter invalid"
                ValueUtil.CxrBluetoothErrorCode.BLE_CONNECT_FAILED -> "BLE connect failed"
                ValueUtil.CxrBluetoothErrorCode.SOCKET_CONNECT_FAILED -> "Socket connect failed"
                ValueUtil.CxrBluetoothErrorCode.UNKNOWN -> "Unknown error"
                else -> "Connection failed: $errorCode"
            }
            Log.e(TAG, "Bluetooth connection failed: $errorMsg")
            _bluetoothState.value = BluetoothState.Failed(errorMsg)
        }
    }
    
    // AI event listener
    private val aiEventListener = object : AiEventListener {
        override fun onAiKeyDown() {
            Log.d(TAG, "AI key down (long press)")
            onAiKeyDown?.invoke()
        }
        
        override fun onAiKeyUp() {
            Log.d(TAG, "AI key up")
            onAiKeyUp?.invoke()
        }
        
        override fun onAiExit() {
            Log.d(TAG, "AI scene exit")
            onAiExit?.invoke()
        }
    }
    
    // Photo result callback
    private val photoCallback = object : PhotoResultCallback {
        override fun onPhotoResult(status: ValueUtil.CxrStatus?, photo: ByteArray?) {
            Log.d(TAG, "Photo result: status=$status, size=${photo?.size ?: 0}")
            onPhotoResult?.invoke(status, photo)
        }
    }
    
    /**
     * Initialize Bluetooth connection
     */
    fun initBluetooth(device: BluetoothDevice): Boolean {
        return try {
            if (!isSdkAvailable()) {
                Log.e(TAG, "CXR-M SDK not available")
                return false
            }
            
            _bluetoothState.value = BluetoothState.Connecting
            Log.d(TAG, "Initializing Bluetooth with device: ${device.name}")
            
            cxrApi.initBluetooth(context, device, bluetoothCallback)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to init Bluetooth", e)
            _bluetoothState.value = BluetoothState.Failed(e.message ?: "Unknown error")
            false
        }
    }
    
    /**
     * Connect Bluetooth
     */
    private fun connectBluetooth(context: Context, socketUuid: String, macAddress: String) {
        try {
            Log.d(TAG, "Connecting Bluetooth: uuid=$socketUuid, mac=$macAddress")
            // connectBluetooth parameters: context, socketUuid, macAddress, callback, secretKey, identifier
            cxrApi.connectBluetooth(context, socketUuid, macAddress, bluetoothCallback, null, null)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to connect Bluetooth", e)
            _bluetoothState.value = BluetoothState.Failed(e.message ?: "Connection error")
        }
    }
    
    /**
     * Check if Bluetooth is connected
     */
    fun isBluetoothConnected(): Boolean {
        return try {
            cxrApi.isBluetoothConnected
        } catch (e: Exception) {
            false
        }
    }
    
    /**
     * Disconnect Bluetooth
     */
    fun disconnectBluetooth() {
        try {
            cxrApi.deinitBluetooth()
            _bluetoothState.value = BluetoothState.Disconnected
            Log.d(TAG, "Bluetooth disconnected")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to disconnect Bluetooth", e)
        }
    }
    
    /**
     * Set AI event listener
     */
    fun setAiEventListener(
        onKeyDown: (() -> Unit)? = null,
        onKeyUp: (() -> Unit)? = null,
        onExit: (() -> Unit)? = null
    ) {
        this.onAiKeyDown = onKeyDown
        this.onAiKeyUp = onKeyUp
        this.onAiExit = onExit
        
        cxrApi.setAiEventListener(aiEventListener)
        Log.d(TAG, "AI event listener set")
    }
    
    /**
     * Remove AI event listener
     */
    fun removeAiEventListener() {
        onAiKeyDown = null
        onAiKeyUp = null
        onAiExit = null
        cxrApi.setAiEventListener(null)
    }
    
    /**
     * Open glasses camera
     */
    fun openGlassCamera(
        width: Int = 1280, 
        height: Int = 720, 
        quality: Int = 80
    ): ValueUtil.CxrStatus? {
        return try {
            val status = cxrApi.openGlassCamera(width, height, quality)
            Log.d(TAG, "Open glass camera: $status")
            status
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open glass camera", e)
            null
        }
    }
    
    /**
     * Take photo
     */
    fun takePhoto(
        width: Int = 1280,
        height: Int = 720,
        quality: Int = 80,
        callback: (status: ValueUtil.CxrStatus?, photoData: ByteArray?) -> Unit
    ): ValueUtil.CxrStatus? {
        this.onPhotoResult = callback
        
        return try {
            val status = cxrApi.takeGlassPhoto(width, height, quality, photoCallback)
            Log.d(TAG, "Take photo request: $status")
            status
        } catch (e: Exception) {
            Log.e(TAG, "Failed to take photo", e)
            null
        }
    }
    
    /**
     * Send exit AI scene event
     */
    fun sendExitEvent(): ValueUtil.CxrStatus? {
        return try {
            cxrApi.sendExitEvent()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send exit event", e)
            null
        }
    }
    
    /**
     * Send TTS content to glasses
     */
    fun sendTtsContent(content: String): ValueUtil.CxrStatus? {
        return try {
            cxrApi.sendTtsContent(content)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send TTS content", e)
            null
        }
    }
    
    /**
     * Notify TTS playback completed
     */
    fun notifyTtsFinished(): ValueUtil.CxrStatus? {
        return try {
            cxrApi.notifyTtsAudioFinished()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to notify TTS finished", e)
            null
        }
    }
    
    /**
     * Notify AI error
     */
    fun notifyAiError(): ValueUtil.CxrStatus? {
        return try {
            cxrApi.notifyAiError()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to notify AI error", e)
            null
        }
    }
    
    /**
     * Notify no network
     */
    fun notifyNoNetwork(): ValueUtil.CxrStatus? {
        return try {
            cxrApi.notifyNoNetwork()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to notify no network", e)
            null
        }
    }
    
    /**
     * Get glasses info
     */
    fun getGlassInfo(callback: (status: ValueUtil.CxrStatus?, info: GlassInfo?) -> Unit) {
        try {
            cxrApi.getGlassInfo(object : GlassInfoResultCallback {
                override fun onGlassInfoResult(status: ValueUtil.CxrStatus?, glassInfo: GlassInfo?) {
                    callback(status, glassInfo)
                }
            })
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get glass info", e)
            callback(null, null)
        }
    }
    
    /**
     * Release resources
     */
    fun release() {
        try {
            removeAiEventListener()
            disconnectBluetooth()
            Log.d(TAG, "CxrMobileManager released")
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing CxrMobileManager", e)
        }
    }
}
