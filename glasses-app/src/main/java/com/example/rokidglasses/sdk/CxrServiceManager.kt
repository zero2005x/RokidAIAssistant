package com.example.rokidglasses.sdk

import android.util.Log
import com.rokid.cxr.CXRServiceBridge
import com.rokid.cxr.Caps
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * CXR-S SDK Service Manager (Glasses Side)
 * 
 * Wraps CXRServiceBridge for:
 * - Listening to phone side connection status
 * - Subscribing to phone side messages
 * - Sending messages to phone side
 * 
 * SDK Package: com.rokid.cxr:cxr-service-bridge
 */
class CxrServiceManager {
    
    companion object {
        private const val TAG = "CxrServiceManager"
        
        // Message channel name (must match phone side)
        const val CHANNEL_PHOTO_REQUEST = "photo_request"
        const val CHANNEL_PHOTO_RESULT = "photo_result"
        const val CHANNEL_AI_EVENT = "ai_event"
        const val CHANNEL_STATUS = "status"
        
        // Check if SDK is available
        fun isSdkAvailable(): Boolean {
            return try {
                Class.forName("com.rokid.cxr.CXRServiceBridge")
                Log.d(TAG, "CXR-S SDK is available")
                true
            } catch (e: ClassNotFoundException) {
                Log.w(TAG, "CXR-S SDK not found in classpath")
                false
            }
        }
        
        @Volatile
        private var instance: CxrServiceManager? = null
        
        fun getInstance(): CxrServiceManager {
            return instance ?: synchronized(this) {
                instance ?: CxrServiceManager().also { instance = it }
            }
        }
    }
    
    // Connection state
    sealed class ConnectionState {
        object Disconnected : ConnectionState()
        data class Connected(val deviceName: String, val deviceType: Int) : ConnectionState()
    }
    
    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()
    
    // ARTC health
    private val _artcHealth = MutableStateFlow(0f)
    val artcHealth: StateFlow<Float> = _artcHealth.asStateFlow()
    
    // Message callback
    private var onMessageReceived: ((name: String, args: Caps?, data: ByteArray?) -> Unit)? = null
    private var onMessageWithReply: ((name: String, args: Caps?, data: ByteArray?, reply: CXRServiceBridge.Reply?) -> Unit)? = null
    
    // CXR Service Bridge instance
    private val cxrBridge: CXRServiceBridge by lazy {
        CXRServiceBridge()
    }
    
    // Connection state listener
    private val statusListener = object : CXRServiceBridge.StatusListener {
        override fun onConnected(name: String, type: Int) {
            Log.d(TAG, "Connected to device: $name, type: $type")
            _connectionState.value = ConnectionState.Connected(name, type)
        }
        
        override fun onDisconnected() {
            Log.d(TAG, "Disconnected from device")
            _connectionState.value = ConnectionState.Disconnected
        }
        
        override fun onARTCStatus(health: Float, reset: Boolean) {
            Log.d(TAG, "ARTC Status: Health=${(health * 100).toInt()}%, reset=$reset")
            _artcHealth.value = health
        }
    }
    
    /**
     * Initialize service
     */
    fun initialize(): Boolean {
        return try {
            if (!isSdkAvailable()) {
                Log.e(TAG, "CXR-S SDK not available")
                return false
            }
            
            // Set state listener
            cxrBridge.setStatusListener(statusListener)
            Log.d(TAG, "CxrServiceManager initialized")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize CxrServiceManager", e)
            false
        }
    }
    
    /**
     * Subscribe to regular messages
     */
    fun subscribe(channelName: String, callback: (name: String, args: Caps?, data: ByteArray?) -> Unit): Int {
        onMessageReceived = callback
        
        val msgCallback = object : CXRServiceBridge.MsgCallback {
            override fun onReceive(name: String, args: Caps?, value: ByteArray?) {
                Log.d(TAG, "Received message: $name, args size: ${args?.size() ?: 0}")
                callback(name, args, value)
            }
        }
        
        val result = cxrBridge.subscribe(channelName, msgCallback)
        Log.d(TAG, "Subscribed to $channelName, result: $result")
        return result
    }
    
    /**
     * Subscribe to messages with reply
     */
    fun subscribeWithReply(
        channelName: String, 
        callback: (name: String, args: Caps?, data: ByteArray?, reply: CXRServiceBridge.Reply?) -> Unit
    ): Int {
        onMessageWithReply = callback
        
        val replyCallback = object : CXRServiceBridge.MsgReplyCallback {
            override fun onReceive(name: String, args: Caps?, value: ByteArray?, reply: CXRServiceBridge.Reply?) {
                Log.d(TAG, "Received message with reply: $name")
                callback(name, args, value, reply)
            }
        }
        
        val result = cxrBridge.subscribe(channelName, replyCallback)
        Log.d(TAG, "Subscribed to $channelName with reply, result: $result")
        return result
    }
    
    /**
     * Send basic message
     */
    fun sendMessage(channelName: String, args: Caps): Int {
        val result = cxrBridge.sendMessage(channelName, args)
        Log.d(TAG, "Sent message to $channelName, result: $result")
        return result
    }
    
    /**
     * Send binary message
     */
    fun sendMessage(channelName: String, args: Caps, data: ByteArray): Int {
        val result = cxrBridge.sendMessage(channelName, args, data, 0, data.size)
        Log.d(TAG, "Sent binary message to $channelName, size: ${data.size}, result: $result")
        return result
    }
    
    /**
     * Send photo data to phone side
     */
    fun sendPhotoToPhone(photoData: ByteArray, fileName: String = "photo.webp"): Boolean {
        return try {
            val args = Caps()
            args.write(fileName)
            args.write(photoData.size.toString())
            
            val result = sendMessage(CHANNEL_PHOTO_RESULT, args, photoData)
            result == 0
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send photo", e)
            false
        }
    }
    
    /**
     * Send status message
     */
    fun sendStatus(status: String): Boolean {
        return try {
            val args = Caps()
            args.write(status)
            
            val result = sendMessage(CHANNEL_STATUS, args)
            result == 0
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send status", e)
            false
        }
    }
    
    /**
     * Release resources
     */
    fun release() {
        try {
            Log.d(TAG, "Releasing CxrServiceManager")
            // SDK will clean up automatically
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing CxrServiceManager", e)
        }
    }
}
