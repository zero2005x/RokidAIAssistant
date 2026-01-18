package com.example.rokidaiassistant.sdk

import android.bluetooth.BluetoothDevice
import android.content.Context
import android.util.Log

/**
 * Rokid CXR SDK Mock Interface
 * 
 * This class mocks the Rokid CXR SDK API for development testing.
 * For actual deployment, replace with the real SDK: com.rokid.cxr:client-m:1.0.4
 * 
 * Real SDK imports:
 * - com.rokid.cxr.api.CxrApi
 * - com.rokid.cxr.api.callback.AiEventListener
 * - com.rokid.cxr.api.callback.AudioStreamListener
 * - com.rokid.cxr.api.callback.BluetoothStatusCallback
 */
class CxrApi private constructor() {
    
    companion object {
        private const val TAG = "CxrApi"
        
        @Volatile
        private var instance: CxrApi? = null
        
        fun getInstance(): CxrApi {
            return instance ?: synchronized(this) {
                instance ?: CxrApi().also { instance = it }
            }
        }
    }
    
    private var aiEventListener: AiEventListener? = null
    private var audioStreamListener: AudioStreamListener? = null
    private var bluetoothCallback: BluetoothStatusCallback? = null
    
    /**
     * Initialize Bluetooth connection
     */
    fun initBluetooth(
        context: Context,
        device: BluetoothDevice,
        callback: BluetoothStatusCallback
    ) {
        Log.d(TAG, "[MOCK] initBluetooth called for device: ${device.name}")
        bluetoothCallback = callback
        // Mock initialization success
        callback.onConnectionInfo(
            device.uuids?.firstOrNull()?.uuid?.toString(),
            device.address,
            0
        )
    }
    
    /**
     * Connect Bluetooth device (with SN verification)
     */
    fun connectBluetooth(
        context: Context,
        uuid: String,
        address: String,
        callback: BluetoothStatusCallback,
        snBytes: ByteArray,
        clientSecret: String
    ) {
        Log.d(TAG, "[MOCK] connectBluetooth: $address, uuid=$uuid, snBytes=${snBytes.size} bytes")
        bluetoothCallback = callback
        // Mock connection success (500ms delay to simulate real connection process)
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            callback.onConnected()
        }, 500)
    }
    
    /**
     * Disconnect and release Bluetooth connection
     */
    fun deinitBluetooth() {
        Log.d(TAG, "[MOCK] deinitBluetooth")
        bluetoothCallback?.onDisconnected()
        bluetoothCallback = null
    }
    
    /**
     * Set AI event listener
     */
    fun setAiEventListener(listener: AiEventListener?) {
        Log.d(TAG, "[MOCK] setAiEventListener: ${listener != null}")
        aiEventListener = listener
    }
    
    /**
     * Set audio stream listener
     */
    fun setAudioStreamListener(listener: AudioStreamListener?) {
        Log.d(TAG, "[MOCK] setAudioStreamListener: ${listener != null}")
        audioStreamListener = listener
    }
    
    /**
     * Send ASR recognition content to glasses display
     */
    fun sendAsrContent(content: String) {
        Log.d(TAG, "[MOCK] sendAsrContent: $content")
    }
    
    /**
     * Notify ASR recognition end
     */
    fun notifyAsrEnd() {
        Log.d(TAG, "[MOCK] notifyAsrEnd")
    }
    
    /**
     * Send TTS content to glasses display
     */
    fun sendTtsContent(content: String) {
        Log.d(TAG, "[MOCK] sendTtsContent: $content")
    }
    
    /**
     * Notify TTS audio playback finished
     */
    fun notifyTtsAudioFinished() {
        Log.d(TAG, "[MOCK] notifyTtsAudioFinished")
    }
    
    /**
     * Send AI scene heartbeat
     */
    fun sendAi_Heartbeat() {
        Log.v(TAG, "[MOCK] sendAi_Heartbeat")
    }
    
    // ========================================
    // Test methods - Simulate glasses events
    // ========================================
    
    /**
     * [Test] Simulate AI key down
     */
    fun simulateAiKeyDown() {
        Log.d(TAG, "[TEST] Simulating AI Key Down")
        aiEventListener?.onAiKeyDown()
    }
    
    /**
     * [Test] Simulate AI key up
     */
    fun simulateAiKeyUp() {
        Log.d(TAG, "[TEST] Simulating AI Key Up")
        aiEventListener?.onAiKeyUp()
    }
    
    /**
     * [Test] Simulate audio data
     */
    fun simulateAudioData(data: ByteArray) {
        Log.d(TAG, "[TEST] Simulating Audio Data: ${data.size} bytes")
        audioStreamListener?.onAudioData(data, data.size)
    }
}

/**
 * AI event listener interface
 */
interface AiEventListener {
    /** AI key pressed (user starts speaking) */
    fun onAiKeyDown()
    
    /** AI key released (user stops speaking) */
    fun onAiKeyUp()
    
    /** Exit AI scene */
    fun onAiExit()
}

/**
 * Audio stream listener interface
 */
interface AudioStreamListener {
    /** Received audio data */
    fun onAudioData(data: ByteArray?, length: Int)
}

/**
 * Bluetooth status callback interface
 */
interface BluetoothStatusCallback {
    /** Connection info callback */
    fun onConnectionInfo(uuid: String?, mac: String?, glassType: Int)
    
    /** Connection successful */
    fun onConnected()
    
    /** Connection disconnected */
    fun onDisconnected()
    
    /** Connection failed */
    fun onFailed(code: Int, msg: String?)
}
