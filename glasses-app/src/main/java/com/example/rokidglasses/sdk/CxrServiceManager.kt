package com.example.rokidglasses.sdk

import android.util.Log
import com.rokid.cxr.CXRServiceBridge
import com.rokid.cxr.Caps
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * CXR-S SDK Service Manager (眼镜端)
 * 
 * 封装 CXRServiceBridge，用于：
 * - 监听手机端连接状态
 * - 订阅手机端消息
 * - 向手机端发送消息
 * 
 * SDK Package: com.rokid.cxr:cxr-service-bridge
 */
class CxrServiceManager {
    
    companion object {
        private const val TAG = "CxrServiceManager"
        
        // 消息通道名称（需与手机端约定一致）
        const val CHANNEL_PHOTO_REQUEST = "photo_request"
        const val CHANNEL_PHOTO_RESULT = "photo_result"
        const val CHANNEL_AI_EVENT = "ai_event"
        const val CHANNEL_STATUS = "status"
        
        // 检查 SDK 是否可用
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
    
    // 连接状态
    sealed class ConnectionState {
        object Disconnected : ConnectionState()
        data class Connected(val deviceName: String, val deviceType: Int) : ConnectionState()
    }
    
    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()
    
    // ARTC 健康度
    private val _artcHealth = MutableStateFlow(0f)
    val artcHealth: StateFlow<Float> = _artcHealth.asStateFlow()
    
    // 消息回调
    private var onMessageReceived: ((name: String, args: Caps?, data: ByteArray?) -> Unit)? = null
    private var onMessageWithReply: ((name: String, args: Caps?, data: ByteArray?, reply: CXRServiceBridge.Reply?) -> Unit)? = null
    
    // CXR Service Bridge 实例
    private val cxrBridge: CXRServiceBridge by lazy {
        CXRServiceBridge()
    }
    
    // 连接状态监听
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
     * 初始化服务
     */
    fun initialize(): Boolean {
        return try {
            if (!isSdkAvailable()) {
                Log.e(TAG, "CXR-S SDK not available")
                return false
            }
            
            // 设置状态监听
            cxrBridge.setStatusListener(statusListener)
            Log.d(TAG, "CxrServiceManager initialized")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize CxrServiceManager", e)
            false
        }
    }
    
    /**
     * 订阅普通消息
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
     * 订阅可回复消息
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
     * 发送基础消息
     */
    fun sendMessage(channelName: String, args: Caps): Int {
        val result = cxrBridge.sendMessage(channelName, args)
        Log.d(TAG, "Sent message to $channelName, result: $result")
        return result
    }
    
    /**
     * 发送二进制消息
     */
    fun sendMessage(channelName: String, args: Caps, data: ByteArray): Int {
        val result = cxrBridge.sendMessage(channelName, args, data, 0, data.size)
        Log.d(TAG, "Sent binary message to $channelName, size: ${data.size}, result: $result")
        return result
    }
    
    /**
     * 发送照片数据到手机端
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
     * 发送状态消息
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
     * 释放资源
     */
    fun release() {
        try {
            Log.d(TAG, "Releasing CxrServiceManager")
            // SDK 会自动清理
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing CxrServiceManager", e)
        }
    }
}
