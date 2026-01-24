package com.example.rokidglasses.sdk

import android.content.Context
import android.util.Log
import com.example.rokidglasses.service.photo.GlassesCameraManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * Camera Mode - determines which camera implementation to use
 * 
 * Note: Glasses-side application only uses Camera2 API
 * CXR-M SDK camera functionality is for phone-side to remotely control glasses camera
 */
enum class CameraMode {
    /** Automatically select best available (Camera2 for glasses) */
    AUTO,
    /** Force use of Android Camera2 API (recommended for glasses) */
    CAMERA2
}

/**
 * Camera status for UI updates
 */
sealed class UnifiedCameraState {
    data object Idle : UnifiedCameraState()
    data object Initializing : UnifiedCameraState()
    data object Ready : UnifiedCameraState()
    data object Opening : UnifiedCameraState()
    data object Capturing : UnifiedCameraState()
    data class Success(val imageData: ByteArray, val cameraType: String) : UnifiedCameraState() {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false
            other as Success
            return imageData.contentEquals(other.imageData) && cameraType == other.cameraType
        }
        override fun hashCode(): Int = 31 * imageData.contentHashCode() + cameraType.hashCode()
    }
    data class Error(val message: String, val cameraType: String) : UnifiedCameraState()
}

/**
 * Unified Camera Manager (Glasses Side)
 * 
 * Glasses-side camera manager that uses Android Camera2 API to directly access the local camera.
 * 
 * Architecture:
 * - Glasses side: Uses Camera2 API for direct photo capture (this file)
 * - Phone side: Can use CXR-M SDK's takeGlassPhoto() to remotely capture glasses photos
 * 
 * Usage:
 * ```kotlin
 * val cameraManager = UnifiedCameraManager(context)
 * cameraManager.initialize()
 * val imageData = cameraManager.capturePhoto()
 * cameraManager.release()
 * ```
 */
class UnifiedCameraManager(
    private val context: Context,
    private val preferredMode: CameraMode = CameraMode.CAMERA2
) {
    companion object {
        private const val TAG = "UnifiedCameraManager"
    }
    
    // State
    private val _cameraState = MutableStateFlow<UnifiedCameraState>(UnifiedCameraState.Idle)
    val cameraState: StateFlow<UnifiedCameraState> = _cameraState.asStateFlow()
    
    // Current mode (glasses always uses Camera2)
    private var currentMode: CameraMode = CameraMode.CAMERA2
    private var activeCamera: CxrCameraInterface? = null
    
    // Camera2 implementation (wrapped)
    private var camera2Manager: Camera2Wrapper? = null
    
    /**
     * Initialize the camera manager.
     * Glasses side uses Camera2 API.
     */
    suspend fun initialize(): Result<Unit> = withContext(Dispatchers.IO) {
        _cameraState.value = UnifiedCameraState.Initializing
        
        try {
            currentMode = CameraMode.CAMERA2
            Log.d(TAG, "Using Camera2 mode for glasses")
            
            val result = initializeCamera2()
            
            if (result.isSuccess) {
                _cameraState.value = UnifiedCameraState.Ready
                Log.d(TAG, "Camera initialized: ${getCameraTypeName()}")
            } else {
                _cameraState.value = UnifiedCameraState.Error(
                    result.exceptionOrNull()?.message ?: "Initialization failed",
                    getCameraTypeName()
                )
            }
            
            result
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize camera", e)
            _cameraState.value = UnifiedCameraState.Error(e.message ?: "Unknown error", getCameraTypeName())
            Result.failure(e)
        }
    }
    
    /**
     * Initialize Camera2 camera (using existing GlassesCameraManager).
     */
    private suspend fun initializeCamera2(): Result<Unit> {
        return try {
            Log.d(TAG, "Initializing Camera2 API...")
            camera2Manager = Camera2Wrapper(context)
            val result = camera2Manager!!.initialize()
            
            if (result.isSuccess) {
                activeCamera = camera2Manager
                Log.d(TAG, "Camera2 initialized successfully")
            } else {
                Log.e(TAG, "Camera2 initialization failed: ${result.exceptionOrNull()?.message}")
            }
            
            result
        } catch (e: Exception) {
            Log.e(TAG, "Camera2 initialization failed", e)
            Result.failure(e)
        }
    }
    
    /**
     * Capture a photo using Camera2 API.
     * 
     * @return ByteArray of JPEG compressed photo data, or null if capture failed
     */
    suspend fun capturePhoto(): ByteArray? = withContext(Dispatchers.IO) {
        if (activeCamera == null) {
            Log.e(TAG, "Camera not initialized")
            _cameraState.value = UnifiedCameraState.Error("Camera not initialized", getCameraTypeName())
            return@withContext null
        }
        
        _cameraState.value = UnifiedCameraState.Capturing
        
        try {
            val photoData = camera2Manager?.capturePhotoSimple()
            
            if (photoData != null) {
                _cameraState.value = UnifiedCameraState.Success(photoData, getCameraTypeName())
                Log.d(TAG, "Photo captured: ${photoData.size} bytes using ${getCameraTypeName()}")
            } else {
                _cameraState.value = UnifiedCameraState.Error("Capture returned null", getCameraTypeName())
            }
            
            photoData
            
        } catch (e: Exception) {
            Log.e(TAG, "Photo capture failed", e)
            _cameraState.value = UnifiedCameraState.Error(e.message ?: "Capture failed", getCameraTypeName())
            null
        }
    }
    
    /**
     * Get the current camera type name.
     */
    fun getCameraTypeName(): String {
        return "Camera2 API"
    }
    
    /**
     * Get the current camera mode.
     */
    fun getCurrentMode(): CameraMode = currentMode
    
    /**
     * Check if CXR SDK is being used.
     * Always returns false for glasses-app (CXR-M SDK is for phone-app).
     */
    fun isUsingCxrSdk(): Boolean = false
    
    /**
     * Release all camera resources.
     */
    fun release() {
        camera2Manager?.release()
        activeCamera = null
        _cameraState.value = UnifiedCameraState.Idle
        Log.d(TAG, "Camera resources released")
    }
}

/**
 * Wrapper for Camera2 GlassesCameraManager to implement CxrCameraInterface.
 */
internal class Camera2Wrapper(private val context: Context) : CxrCameraInterface {
    
    private val glassesCameraManager = GlassesCameraManager(context)
    
    override suspend fun initialize(): Result<Unit> {
        return glassesCameraManager.initialize()
    }
    
    override suspend fun openCamera(width: Int, height: Int, quality: Int): Result<Unit> {
        // Camera2 opens camera during capture, not separately
        return Result.success(Unit)
    }
    
    override suspend fun capturePhoto(callback: PhotoResultCallback): Result<Unit> {
        return try {
            val imageData = glassesCameraManager.capturePhoto()
            if (imageData != null) {
                callback.onPhotoResult(CxrStatus.RESPONSE_SUCCEED, imageData)
                Result.success(Unit)
            } else {
                callback.onPhotoResult(CxrStatus.CAMERA_ERROR, null)
                Result.failure(Exception("Camera2 capture failed"))
            }
        } catch (e: Exception) {
            callback.onPhotoResult(CxrStatus.CAMERA_ERROR, null)
            Result.failure(e)
        }
    }
    
    override suspend fun closeCamera() {
        // Camera2 releases after capture
    }
    
    override fun release() {
        glassesCameraManager.release()
    }
    
    override fun isCameraOpen(): Boolean = true
    
    override fun getCameraTypeName(): String = "Camera2 API"
    
    /**
     * Simple capture method.
     */
    suspend fun capturePhotoSimple(): ByteArray? {
        return glassesCameraManager.capturePhoto()
    }
}
