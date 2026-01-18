package com.example.rokidglasses.sdk

/**
 * CXR Camera Interface
 * 
 * Defines the contract for camera operations that can be implemented
 * using either Rokid CXR SDK or standard Camera2 API.
 * 
 * This abstraction allows switching between:
 * 1. CxrApiCamera - Uses Rokid CXR SDK (preferred for Rokid glasses)
 * 2. Camera2Camera - Uses Android Camera2 API (fallback)
 */
interface CxrCameraInterface {
    
    /**
     * Initialize the camera system.
     * @return Result indicating success or failure with error details
     */
    suspend fun initialize(): Result<Unit>
    
    /**
     * Open the camera with specified configuration.
     * @param width Desired width in pixels
     * @param height Desired height in pixels
     * @param quality JPEG quality (1-100)
     * @return Result indicating success or failure
     */
    suspend fun openCamera(width: Int, height: Int, quality: Int): Result<Unit>
    
    /**
     * Capture a photo.
     * @param callback Callback to receive the photo data
     * @return Result indicating success or failure
     */
    suspend fun capturePhoto(callback: PhotoResultCallback): Result<Unit>
    
    /**
     * Close the camera and release resources.
     */
    suspend fun closeCamera()
    
    /**
     * Release all camera resources.
     */
    fun release()
    
    /**
     * Check if camera is currently open.
     */
    fun isCameraOpen(): Boolean
    
    /**
     * Get the camera type name for logging.
     */
    fun getCameraTypeName(): String
}

/**
 * Photo result callback interface.
 */
interface PhotoResultCallback {
    /**
     * Called when photo capture succeeds.
     * @param photoData JPEG compressed photo data
     */
    fun onPhotoResult(status: CxrStatus, photoData: ByteArray?)
}

/**
 * Photo path callback interface (for when photo is saved to storage).
 */
interface PhotoPathCallback {
    /**
     * Called when photo is saved.
     * @param path Path to the saved photo file on device
     */
    fun onPhotoPath(status: CxrStatus, path: String?)
}

/**
 * CXR API Status codes.
 * Mirrors the status codes from Rokid CXR SDK.
 */
enum class CxrStatus {
    /** Operation completed successfully */
    RESPONSE_SUCCEED,
    /** Operation timed out */
    RESPONSE_TIMEOUT,
    /** Invalid parameters or operation failed */
    RESPONSE_INVALID,
    /** Camera is in use by another process */
    CAMERA_IN_USE,
    /** Camera access error */
    CAMERA_ERROR,
    /** Unknown error */
    UNKNOWN
}

/**
 * Camera configuration.
 */
data class CameraConfig(
    val width: Int,
    val height: Int,
    val quality: Int
) {
    companion object {
        /** Default configuration for Rokid glasses */
        val DEFAULT = CameraConfig(1280, 720, 80)
        
        /** High quality configuration */
        val HIGH_QUALITY = CameraConfig(1920, 1080, 90)
        
        /** Low quality for faster transfer */
        val LOW_QUALITY = CameraConfig(640, 480, 60)
        
        /** Alternative configs for compatibility */
        val ALTERNATIVES = listOf(
            CameraConfig(1280, 720, 60),
            CameraConfig(1024, 768, 60),
            CameraConfig(960, 540, 60),
            CameraConfig(640, 480, 60),
            CameraConfig(1920, 1080, 30)
        )
    }
}
