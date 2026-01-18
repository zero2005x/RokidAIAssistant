package com.example.rokidglasses.service.photo

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.hardware.camera2.*
import android.media.ExifInterface
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Size
import android.view.Surface
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Camera state for UI updates
 */
sealed class CameraState {
    data object Idle : CameraState()
    data object Initializing : CameraState()
    data object Ready : CameraState()
    data object Capturing : CameraState()
    data class Success(val imageData: ByteArray) : CameraState() {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false
            return imageData.contentEquals((other as Success).imageData)
        }
        override fun hashCode(): Int = imageData.contentHashCode()
    }
    data class Error(val message: String) : CameraState()
}

/**
 * Glasses Camera Manager
 * 
 * Manages camera operations for Rokid glasses using Camera2 API.
 * Supports both standard Android Camera2 and Rokid-specific camera access.
 * 
 * Features:
 * - Automatic camera selection (prefer front camera for AR glasses)
 * - Auto-focus and auto-exposure
 * - JPEG compression with quality control
 * - Rotation correction based on device orientation
 * 
 * Usage:
 * ```
 * val cameraManager = GlassesCameraManager(context)
 * cameraManager.initialize()
 * 
 * val imageData = cameraManager.capturePhoto()
 * // imageData is compressed JPEG ready for transfer
 * 
 * cameraManager.release()
 * ```
 */
class GlassesCameraManager(private val context: Context) {
    
    companion object {
        private const val TAG = "GlassesCameraManager"
        
        // Capture settings
        private const val TARGET_WIDTH = 1280
        private const val TARGET_HEIGHT = 720
        private const val JPEG_QUALITY = 85
        
        // Timeouts
        private const val CAMERA_OPEN_TIMEOUT_MS = 5000L
        private const val CAPTURE_TIMEOUT_MS = 10000L
        
        // Retry settings for camera access
        // Increased retries and delays for when Rokid system service holds camera
        private const val MAX_CAMERA_RETRIES = 5
        private const val RETRY_DELAY_MS = 2000L
        private const val INITIAL_DELAY_MS = 500L
        
        // CameraDevice.StateCallback error codes
        private const val ERROR_CAMERA_IN_USE = 1
        private const val ERROR_CAMERA_DISABLED = 2
        private const val ERROR_CAMERA_DEVICE = 3
        private const val ERROR_CAMERA_SERVICE = 4
    }
    
    // Camera state
    private val _cameraState = MutableStateFlow<CameraState>(CameraState.Idle)
    val cameraState: StateFlow<CameraState> = _cameraState.asStateFlow()
    
    // Camera2 components
    private var cameraManager: CameraManager? = null
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var imageReader: ImageReader? = null
    
    // Background thread for camera operations
    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null
    
    // Selected camera ID
    private var cameraId: String? = null
    private var sensorOrientation: Int = 0
    
    /**
     * Initialize camera manager.
     * Must be called before capturePhoto().
     */
    @SuppressLint("MissingPermission")
    suspend fun initialize(): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            _cameraState.value = CameraState.Initializing
            
            // Start background thread
            startBackgroundThread()
            
            // Get camera manager
            cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            
            // Find suitable camera
            cameraId = findBestCamera()
            if (cameraId == null) {
                throw CameraAccessException(CameraAccessException.CAMERA_ERROR, "No suitable camera found")
            }
            
            // Get sensor orientation
            val characteristics = cameraManager?.getCameraCharacteristics(cameraId!!)
            sensorOrientation = characteristics?.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
            
            Log.d(TAG, "Camera initialized: id=$cameraId, orientation=$sensorOrientation")
            
            _cameraState.value = CameraState.Ready
            Result.success(Unit)
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize camera", e)
            _cameraState.value = CameraState.Error(e.message ?: "Camera initialization failed")
            Result.failure(e)
        }
    }
    
    /**
     * Find the best camera for glasses (prefer external/AR camera, then back camera).
     */
    private fun findBestCamera(): String? {
        val manager = cameraManager ?: return null
        
        try {
            val cameraIds = manager.cameraIdList
            Log.d(TAG, "Available cameras: ${cameraIds.joinToString()}")
            
            // Priority: External camera > Back camera > Front camera
            var backCameraId: String? = null
            var frontCameraId: String? = null
            var externalCameraId: String? = null
            
            for (id in cameraIds) {
                val characteristics = manager.getCameraCharacteristics(id)
                val facing = characteristics.get(CameraCharacteristics.LENS_FACING)
                
                when (facing) {
                    CameraCharacteristics.LENS_FACING_BACK -> backCameraId = id
                    CameraCharacteristics.LENS_FACING_FRONT -> frontCameraId = id
                    CameraCharacteristics.LENS_FACING_EXTERNAL -> externalCameraId = id
                }
                
                // Log camera capabilities
                val capabilities = characteristics.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
                Log.d(TAG, "Camera $id: facing=$facing, capabilities=${capabilities?.joinToString()}")
            }
            
            // For Rokid glasses, external camera is usually the AR camera
            return externalCameraId ?: backCameraId ?: frontCameraId
            
        } catch (e: CameraAccessException) {
            Log.e(TAG, "Error enumerating cameras", e)
            return null
        }
    }
    
    /**
     * Capture a photo and return compressed JPEG data.
     * Includes retry logic for camera access issues.
     * 
     * @return ByteArray of compressed JPEG, or null on failure
     */
    @SuppressLint("MissingPermission")
    suspend fun capturePhoto(): ByteArray? = withContext(Dispatchers.IO) {
        if (cameraId == null) {
            Log.e(TAG, "Camera not initialized")
            _cameraState.value = CameraState.Error("Camera not initialized")
            return@withContext null
        }
        
        var lastError: Exception? = null
        
        // Small initial delay to allow Rokid system service to potentially release camera
        Log.d(TAG, "Initial delay before camera access...")
        delay(INITIAL_DELAY_MS)
        
        for (attempt in 1..MAX_CAMERA_RETRIES) {
            try {
                Log.d(TAG, "Capture attempt $attempt/$MAX_CAMERA_RETRIES")
                _cameraState.value = CameraState.Capturing
                
                // Open camera with retry
                val device = openCameraWithRetry(cameraId!!)
                cameraDevice = device
                
                // Create ImageReader for capture - use YUV format for better compatibility
                val captureSize = chooseCaptureSize()
                Log.d(TAG, "Creating ImageReader with YUV_420_888 format for better compatibility")
                imageReader = ImageReader.newInstance(
                    captureSize.width,
                    captureSize.height,
                    ImageFormat.YUV_420_888,  // Use YUV instead of JPEG for better compatibility
                    3  // Need more buffers for preview + capture
                )
                
                // Create capture session
                val session = createCaptureSession(device, imageReader!!)
                captureSession = session
                
                // Capture photo
                val imageData = captureImage(device, session, imageReader!!)
                
                // Close camera resources
                closeCamera()
                
                if (imageData != null) {
                    _cameraState.value = CameraState.Success(imageData)
                    Log.d(TAG, "Photo captured: ${imageData.size} bytes")
                    return@withContext imageData
                } else {
                    throw Exception("Failed to capture image data")
                }
                
            } catch (e: CameraAccessException) {
                Log.w(TAG, "Camera access error on attempt $attempt: reason=${e.reason}, message=${e.message}")
                lastError = e
                closeCamera()
                
                // Check if camera is in use by another process or temporarily disabled
                // CameraDevice.StateCallback error codes are passed through as reason:
                // 1 = ERROR_CAMERA_IN_USE, 2 = ERROR_CAMERA_DISABLED
                val isRecoverableError = e.reason == CameraAccessException.CAMERA_IN_USE ||
                    e.reason == CameraAccessException.MAX_CAMERAS_IN_USE ||
                    e.reason == ERROR_CAMERA_IN_USE ||
                    e.reason == ERROR_CAMERA_DISABLED ||
                    e.reason == CameraAccessException.CAMERA_DISABLED ||
                    e.reason == CameraAccessException.CAMERA_ERROR
                
                if (isRecoverableError) {
                    Log.w(TAG, "Camera is in use or disabled by another app (possibly Rokid system service)")
                    if (attempt < MAX_CAMERA_RETRIES) {
                        val delayMs = RETRY_DELAY_MS * attempt  // Progressive delay
                        Log.d(TAG, "Waiting ${delayMs}ms before retry...")
                        delay(delayMs)
                    }
                } else {
                    // Non-recoverable error
                    Log.e(TAG, "Non-recoverable camera error, not retrying")
                    break
                }
            } catch (e: Exception) {
                Log.e(TAG, "Capture error on attempt $attempt", e)
                lastError = e
                closeCamera()
                if (attempt < MAX_CAMERA_RETRIES) {
                    val delayMs = RETRY_DELAY_MS * attempt  // Progressive delay
                    Log.d(TAG, "Waiting ${delayMs}ms before retry...")
                    delay(delayMs)
                }
            }
        }
        
        // All retries failed
        Log.e(TAG, "Failed to capture photo after $MAX_CAMERA_RETRIES attempts")
        _cameraState.value = CameraState.Error(
            lastError?.message ?: "Camera capture failed - camera may be in use by system"
        )
        null
    }
    
    /**
     * Open camera with timeout handling.
     */
    @SuppressLint("MissingPermission")
    private suspend fun openCameraWithRetry(cameraId: String): CameraDevice {
        return withTimeout(CAMERA_OPEN_TIMEOUT_MS) {
            openCamera(cameraId)
        }
    }
    
    /**
     * Open camera device.
     */
    @SuppressLint("MissingPermission")
    private suspend fun openCamera(cameraId: String): CameraDevice = suspendCancellableCoroutine { cont ->
        try {
            cameraManager?.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    Log.d(TAG, "Camera opened: $cameraId")
                    if (cont.isActive) {
                        cont.resume(camera)
                    } else {
                        camera.close()
                    }
                }
                
                override fun onDisconnected(camera: CameraDevice) {
                    Log.w(TAG, "Camera disconnected")
                    camera.close()
                    if (cont.isActive) {
                        cont.resumeWithException(CameraAccessException(
                            CameraAccessException.CAMERA_DISCONNECTED,
                            "Camera disconnected"
                        ))
                    }
                }
                
                override fun onError(camera: CameraDevice, error: Int) {
                    Log.e(TAG, "Camera error: $error")
                    camera.close()
                    if (cont.isActive) {
                        cont.resumeWithException(CameraAccessException(
                            error,
                            "Camera error: $error"
                        ))
                    }
                }
            }, backgroundHandler)
        } catch (e: Exception) {
            if (cont.isActive) {
                cont.resumeWithException(e)
            }
        }
    }
    
    /**
     * Create capture session.
     */
    private suspend fun createCaptureSession(
        device: CameraDevice,
        imageReader: ImageReader
    ): CameraCaptureSession = suspendCancellableCoroutine { cont ->
        try {
            val surfaces = listOf(imageReader.surface)
            
            @Suppress("DEPRECATION")
            device.createCaptureSession(surfaces, object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    Log.d(TAG, "Capture session configured")
                    if (cont.isActive) {
                        cont.resume(session)
                    }
                }
                
                override fun onConfigureFailed(session: CameraCaptureSession) {
                    Log.e(TAG, "Capture session configuration failed")
                    if (cont.isActive) {
                        cont.resumeWithException(CameraAccessException(
                            CameraAccessException.CAMERA_ERROR,
                            "Session configuration failed"
                        ))
                    }
                }
            }, backgroundHandler)
        } catch (e: Exception) {
            if (cont.isActive) {
                cont.resumeWithException(e)
            }
        }
    }
    
    /**
     * Capture single image using YUV format and convert to JPEG.
     * This bypasses potential hardware JPEG encoder issues on some devices.
     */
    private suspend fun captureImage(
        device: CameraDevice,
        session: CameraCaptureSession,
        imageReader: ImageReader
    ): ByteArray? = withContext(Dispatchers.IO) {
        try {
            // Use a blocking latch to wait for image
            val latch = java.util.concurrent.CountDownLatch(1)
            var capturedBytes: ByteArray? = null
            
            // Flag to indicate when we're ready to capture (after preview warm-up)
            // This prevents processing preview frames which causes massive CPU load
            // Use AtomicBoolean for thread-safe access from callback
            val readyToCapture = java.util.concurrent.atomic.AtomicBoolean(false)
            
            // Set up image available listener BEFORE starting capture
            imageReader.setOnImageAvailableListener({ reader ->
                try {
                    val image = reader.acquireNextImage()
                    if (image != null) {
                        try {
                            // Only process the image if we're in capture mode (not preview warm-up)
                            if (readyToCapture.get()) {
                                // Check if it's YUV or JPEG format
                                if (image.format == ImageFormat.YUV_420_888) {
                                    // Convert YUV to JPEG
                                    capturedBytes = yuvToJpeg(image)
                                    Log.d(TAG, "Captured YUV image, converted to JPEG: ${capturedBytes?.size ?: 0} bytes")
                                } else {
                                    // Direct JPEG capture
                                    val buffer = image.planes[0].buffer
                                    val bytes = ByteArray(buffer.remaining())
                                    buffer.get(bytes)
                                    capturedBytes = bytes
                                    Log.d(TAG, "Captured JPEG image: ${bytes.size} bytes")
                                }
                                latch.countDown()
                            }
                            // Preview frames are discarded silently to avoid CPU load
                        } finally {
                            image.close()
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error acquiring image", e)
                    if (readyToCapture.get()) {
                        latch.countDown()
                    }
                }
            }, backgroundHandler)
            
            // First, start a preview to warm up the camera pipeline
            Log.d(TAG, "Starting preview warm-up for AE/AF stabilization...")
            val previewBuilder = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            previewBuilder.addTarget(imageReader.surface)
            previewBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
            previewBuilder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
            
            // Run preview for a short time to stabilize exposure
            session.setRepeatingRequest(previewBuilder.build(), null, backgroundHandler)
            delay(1500)  // Wait 1.5 seconds for AE/AF to stabilize
            session.stopRepeating()
            
            // Now we're ready to capture - set the flag
            readyToCapture.set(true)
            Log.d(TAG, "Preview warm-up complete, ready to capture")
            
            // Now do the actual capture
            Log.d(TAG, "Starting still capture...")
            val captureBuilder = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
            captureBuilder.addTarget(imageReader.surface)
            
            // Auto-focus and auto-exposure
            captureBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
            captureBuilder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
            
            // Exposure compensation for brighter photos
            val exposureCompensation = getExposureCompensation()
            if (exposureCompensation != null) {
                captureBuilder.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, exposureCompensation)
                Log.d(TAG, "Applied exposure compensation: $exposureCompensation")
            }
            
            // Auto white balance and scene mode
            captureBuilder.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
            captureBuilder.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
            
            // For JPEG format, set quality and orientation
            if (imageReader.imageFormat == ImageFormat.JPEG) {
                captureBuilder.set(CaptureRequest.JPEG_QUALITY, JPEG_QUALITY.toByte())
                captureBuilder.set(CaptureRequest.JPEG_ORIENTATION, sensorOrientation)
            }
            
            // Execute capture
            session.capture(captureBuilder.build(), object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(
                    session: CameraCaptureSession,
                    request: CaptureRequest,
                    result: TotalCaptureResult
                ) {
                    Log.d(TAG, "Capture completed successfully")
                }
                
                override fun onCaptureFailed(
                    session: CameraCaptureSession,
                    request: CaptureRequest,
                    failure: CaptureFailure
                ) {
                    Log.e(TAG, "Capture failed: reason=${failure.reason}, wasImageCaptured=${failure.wasImageCaptured()}")
                    // If image was NOT captured, release the latch
                    if (!failure.wasImageCaptured()) {
                        latch.countDown()
                    }
                    // If wasImageCaptured=true, the image should still come through ImageReader
                }
            }, backgroundHandler)
            
            // Wait for image with timeout (10 seconds for processing)
            val received = latch.await(10, java.util.concurrent.TimeUnit.SECONDS)
            
            if (received && capturedBytes != null) {
                Log.d(TAG, "Capture successful: ${capturedBytes!!.size} bytes")
                capturedBytes
            } else {
                Log.e(TAG, "Capture timed out or failed to receive image")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error during capture", e)
            null
        }
    }
    
    /**
     * Convert YUV_420_888 image to JPEG byte array.
     */
    private fun yuvToJpeg(image: Image): ByteArray? {
        try {
            val yBuffer = image.planes[0].buffer
            val uBuffer = image.planes[1].buffer
            val vBuffer = image.planes[2].buffer
            
            val ySize = yBuffer.remaining()
            val uSize = uBuffer.remaining()
            val vSize = vBuffer.remaining()
            
            val nv21 = ByteArray(ySize + uSize + vSize)
            
            // Copy Y plane
            yBuffer.get(nv21, 0, ySize)
            
            // Copy VU planes (NV21 format: VU interleaved)
            vBuffer.get(nv21, ySize, vSize)
            uBuffer.get(nv21, ySize + vSize, uSize)
            
            // Create YuvImage and compress to JPEG
            val yuvImage = android.graphics.YuvImage(
                nv21,
                android.graphics.ImageFormat.NV21,
                image.width,
                image.height,
                null
            )
            
            val outputStream = ByteArrayOutputStream()
            yuvImage.compressToJpeg(
                android.graphics.Rect(0, 0, image.width, image.height),
                JPEG_QUALITY,
                outputStream
            )
            
            return outputStream.toByteArray()
        } catch (e: Exception) {
            Log.e(TAG, "Error converting YUV to JPEG", e)
            return null
        }
    }
    
    /**
     * Choose optimal capture size for YUV format.
     */
    private fun chooseCaptureSize(): Size {
        val characteristics = cameraManager?.getCameraCharacteristics(cameraId!!)
        val map = characteristics?.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        
        // Use YUV_420_888 sizes instead of JPEG
        val outputSizes = map?.getOutputSizes(ImageFormat.YUV_420_888) ?: return Size(TARGET_WIDTH, TARGET_HEIGHT)
        
        // Find size closest to target
        val targetPixels = TARGET_WIDTH * TARGET_HEIGHT
        
        var bestSize = outputSizes[0]
        var bestDiff = Int.MAX_VALUE
        
        for (size in outputSizes) {
            val pixels = size.width * size.height
            val diff = kotlin.math.abs(pixels - targetPixels)
            
            if (diff < bestDiff) {
                bestDiff = diff
                bestSize = size
            }
        }
        
        Log.d(TAG, "Selected capture size: ${bestSize.width}x${bestSize.height}")
        return bestSize
    }
    
    /**
     * Close camera and release resources.
     */
    private fun closeCamera() {
        try {
            captureSession?.close()
            captureSession = null
            
            cameraDevice?.close()
            cameraDevice = null
            
            imageReader?.close()
            imageReader = null
        } catch (e: Exception) {
            Log.e(TAG, "Error closing camera", e)
        }
    }
    
    /**
     * Release all resources.
     */
    fun release() {
        closeCamera()
        stopBackgroundThread()
        _cameraState.value = CameraState.Idle
    }
    
    /**
     * Start background handler thread.
     */
    private fun startBackgroundThread() {
        backgroundThread = HandlerThread("CameraBackground").also { it.start() }
        backgroundHandler = Handler(backgroundThread!!.looper)
    }
    
    /**
     * Stop background handler thread.
     */
    private fun stopBackgroundThread() {
        backgroundThread?.quitSafely()
        try {
            backgroundThread?.join()
            backgroundThread = null
            backgroundHandler = null
        } catch (e: InterruptedException) {
            Log.e(TAG, "Error stopping background thread", e)
        }
    }
    
    /**
     * Check if camera permission is granted.
     */
    fun hasCameraPermission(): Boolean {
        return context.checkSelfPermission(android.Manifest.permission.CAMERA) == 
            android.content.pm.PackageManager.PERMISSION_GRANTED
    }
    
    /**
     * Get optimal exposure compensation value to brighten the image.
     * Returns a positive value (typically +1 to +2 stops) if supported.
     */
    private fun getExposureCompensation(): Int? {
        try {
            val characteristics = cameraManager?.getCameraCharacteristics(cameraId ?: return null)
                ?: return null
            
            val range = characteristics.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE)
                ?: return null
            val step = characteristics.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP)
                ?: return null
            
            // Calculate +1.5 stops exposure compensation
            // step is typically 1/3 or 1/2 EV, so +1.5 stops = +3 to +4.5 units
            val targetEV = 1.5f  // Target +1.5 stops brighter
            val compensationUnits = (targetEV / step.toFloat()).toInt()
            
            // Clamp to valid range
            val clampedValue = compensationUnits.coerceIn(range.lower, range.upper)
            
            Log.d(TAG, "Exposure compensation: range=$range, step=$step, target=$clampedValue")
            return clampedValue
            
        } catch (e: Exception) {
            Log.w(TAG, "Failed to get exposure compensation", e)
            return null
        }
    }
}
