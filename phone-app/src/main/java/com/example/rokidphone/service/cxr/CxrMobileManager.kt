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
 * CXR-M SDK Manager (手机端)
 * 
 * 封装 CxrApi，用于：
 * - 连接眼镜蓝牙
 * - 监听 AI 事件（长按按键）
 * - 拍摄照片
 * - 控制眼镜设备
 * 
 * SDK Package: com.rokid.cxr:client-m
 */
class CxrMobileManager(private val context: Context) {
    
    companion object {
        private const val TAG = "CxrMobileManager"
        
        // 检查 SDK 是否可用
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
    
    // 连接状态
    sealed class BluetoothState {
        object Disconnected : BluetoothState()
        object Connecting : BluetoothState()
        data class Connected(val socketUuid: String, val macAddress: String) : BluetoothState()
        data class Failed(val error: String) : BluetoothState()
    }
    
    private val _bluetoothState = MutableStateFlow<BluetoothState>(BluetoothState.Disconnected)
    val bluetoothState: StateFlow<BluetoothState> = _bluetoothState.asStateFlow()
    
    // WiFi P2P 连接状态
    private val _wifiConnected = MutableStateFlow(false)
    val wifiConnected: StateFlow<Boolean> = _wifiConnected.asStateFlow()
    
    // AI 事件回调
    private var onAiKeyDown: (() -> Unit)? = null
    private var onAiKeyUp: (() -> Unit)? = null
    private var onAiExit: (() -> Unit)? = null
    
    // 照片结果回调
    private var onPhotoResult: ((status: ValueUtil.CxrStatus?, photoData: ByteArray?) -> Unit)? = null
    
    // 眼镜信息
    private var glassSocketUuid: String? = null
    private var glassMacAddress: String? = null
    
    // CXR API 实例
    private val cxrApi: CxrApi by lazy {
        CxrApi.getInstance().also {
            Log.d(TAG, "CxrApi instance created: $it")
            Log.d(TAG, "SDK version: ${it.javaClass.simpleName}")
        }
    }
    
    // 蓝牙状态回调
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
                
                // 继续连接
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
    
    // AI 事件监听
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
    
    // 照片结果回调
    private val photoCallback = object : PhotoResultCallback {
        override fun onPhotoResult(status: ValueUtil.CxrStatus?, photo: ByteArray?) {
            Log.d(TAG, "Photo result: status=$status, size=${photo?.size ?: 0}")
            onPhotoResult?.invoke(status, photo)
        }
    }
    
    /**
     * 初始化蓝牙连接
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
     * 连接蓝牙
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
     * 检查蓝牙是否已连接
     */
    fun isBluetoothConnected(): Boolean {
        return try {
            cxrApi.isBluetoothConnected
        } catch (e: Exception) {
            false
        }
    }
    
    /**
     * 断开蓝牙
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
     * 设置 AI 事件监听器
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
     * 移除 AI 事件监听器
     */
    fun removeAiEventListener() {
        onAiKeyDown = null
        onAiKeyUp = null
        onAiExit = null
        cxrApi.setAiEventListener(null)
    }
    
    /**
     * 打开眼镜相机
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
     * 拍照
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
     * 发送退出 AI 场景事件
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
     * 发送 TTS 内容到眼镜
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
     * 通知 TTS 播放完成
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
     * 通知 AI 错误
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
     * 通知无网络
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
     * 获取眼镜信息
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
     * 释放资源
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
