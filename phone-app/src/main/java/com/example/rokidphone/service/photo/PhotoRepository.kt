package com.example.rokidphone.service.photo

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*

/**
 * Photo Repository
 * 
 * Manages received photos from glasses:
 * - Decodes and validates JPEG data
 * - Stores photos locally
 * - Provides access for AI analysis
 * - Handles cleanup of old photos
 */
class PhotoRepository(
    private val context: Context,
    private val scope: CoroutineScope
) {
    companion object {
        private const val TAG = "PhotoRepository"
        
        // Directory for storing received photos
        private const val PHOTO_DIR = "glasses_photos"
        
        // Maximum photos to keep
        private const val MAX_STORED_PHOTOS = 50
        
        // Photo file name format
        private const val PHOTO_NAME_FORMAT = "photo_%s.jpg"
        private const val DATE_FORMAT = "yyyyMMdd_HHmmss"
    }
    
    // Current photo being processed
    private val _currentPhoto = MutableStateFlow<PhotoData?>(null)
    val currentPhoto: StateFlow<PhotoData?> = _currentPhoto.asStateFlow()
    
    // Photo history
    private val _photoHistory = MutableStateFlow<List<PhotoData>>(emptyList())
    val photoHistory: StateFlow<List<PhotoData>> = _photoHistory.asStateFlow()
    
    // SharedFlow for new photo events - used by ImageAnalysisViewModel
    private val _photoFlow = MutableSharedFlow<PhotoData>(replay = 0, extraBufferCapacity = 1)
    val photoFlow: SharedFlow<PhotoData> = _photoFlow.asSharedFlow()
    
    // Photo storage directory
    private val photoDir: File by lazy {
        File(context.filesDir, PHOTO_DIR).apply {
            if (!exists()) mkdirs()
        }
    }
    
    init {
        // Load photo history on init
        scope.launch(Dispatchers.IO) {
            loadPhotoHistory()
        }
    }
    
    /**
     * Processes received photo data from glasses.
     * 
     * @param receivedPhoto The received photo data
     * @return PhotoData if successful, null if decoding failed
     */
    suspend fun processReceivedPhoto(receivedPhoto: ReceivedPhoto): PhotoData? {
        return withContext(Dispatchers.IO) {
            try {
                Log.d(TAG, "Processing received photo: ${receivedPhoto.data.size} bytes")
                
                // Decode JPEG to verify it's valid
                val bitmap = BitmapFactory.decodeByteArray(
                    receivedPhoto.data, 
                    0, 
                    receivedPhoto.data.size
                )
                
                if (bitmap == null) {
                    Log.e(TAG, "Failed to decode JPEG")
                    return@withContext null
                }
                
                Log.d(TAG, "Decoded photo: ${bitmap.width}x${bitmap.height}")
                
                // Generate file name
                val dateFormat = SimpleDateFormat(DATE_FORMAT, Locale.getDefault())
                val timestamp = dateFormat.format(Date(receivedPhoto.timestamp))
                val fileName = String.format(PHOTO_NAME_FORMAT, timestamp)
                val photoFile = File(photoDir, fileName)
                
                // Save to file
                FileOutputStream(photoFile).use { fos ->
                    fos.write(receivedPhoto.data)
                    fos.flush()
                }
                
                Log.d(TAG, "Saved photo to: ${photoFile.absolutePath}")
                
                val photoData = PhotoData(
                    id = UUID.randomUUID().toString(),
                    filePath = photoFile.absolutePath,
                    timestamp = receivedPhoto.timestamp,
                    width = bitmap.width,
                    height = bitmap.height,
                    sizeBytes = receivedPhoto.data.size,
                    transferTimeMs = receivedPhoto.transferTimeMs
                )
                
                // Update current photo
                _currentPhoto.value = photoData
                
                // Emit to photoFlow for ImageAnalysisViewModel
                _photoFlow.tryEmit(photoData)
                
                // Add to history
                updateHistory(photoData)
                
                // Cleanup old photos
                cleanupOldPhotos()
                
                // Recycle bitmap
                bitmap.recycle()
                
                photoData
                
            } catch (e: Exception) {
                Log.e(TAG, "Failed to process photo", e)
                null
            }
        }
    }
    
    /**
     * Gets the bitmap for a photo.
     * 
     * @param photoData The photo data
     * @param maxSize Maximum dimension (width or height)
     * @return Bitmap or null if loading failed
     */
    suspend fun getBitmap(photoData: PhotoData, maxSize: Int? = null): Bitmap? {
        return withContext(Dispatchers.IO) {
            try {
                val file = File(photoData.filePath)
                if (!file.exists()) {
                    Log.e(TAG, "Photo file not found: ${photoData.filePath}")
                    return@withContext null
                }
                
                val options = BitmapFactory.Options()
                
                if (maxSize != null) {
                    // First decode bounds only
                    options.inJustDecodeBounds = true
                    BitmapFactory.decodeFile(file.absolutePath, options)
                    
                    // Calculate sample size
                    val maxDim = maxOf(options.outWidth, options.outHeight)
                    options.inSampleSize = (maxDim / maxSize).coerceAtLeast(1)
                    options.inJustDecodeBounds = false
                }
                
                BitmapFactory.decodeFile(file.absolutePath, options)
                
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load bitmap", e)
                null
            }
        }
    }
    
    /**
     * Gets the raw JPEG bytes for a photo.
     * 
     * @param photoData The photo data
     * @return ByteArray or null if loading failed
     */
    suspend fun getPhotoBytes(photoData: PhotoData): ByteArray? {
        return withContext(Dispatchers.IO) {
            try {
                val file = File(photoData.filePath)
                if (!file.exists()) {
                    Log.e(TAG, "Photo file not found: ${photoData.filePath}")
                    return@withContext null
                }
                file.readBytes()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to read photo bytes", e)
                null
            }
        }
    }
    
    /**
     * Deletes a specific photo.
     */
    suspend fun deletePhoto(photoData: PhotoData) {
        withContext(Dispatchers.IO) {
            try {
                File(photoData.filePath).delete()
                
                val currentList = _photoHistory.value.toMutableList()
                currentList.removeAll { it.id == photoData.id }
                _photoHistory.value = currentList
                
                if (_currentPhoto.value?.id == photoData.id) {
                    _currentPhoto.value = null
                }
                
                Log.d(TAG, "Deleted photo: ${photoData.id}")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to delete photo", e)
            }
        }
    }
    
    /**
     * Clears all stored photos.
     */
    suspend fun clearAll() {
        withContext(Dispatchers.IO) {
            try {
                photoDir.listFiles()?.forEach { it.delete() }
                _photoHistory.value = emptyList()
                _currentPhoto.value = null
                Log.d(TAG, "Cleared all photos")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to clear photos", e)
            }
        }
    }
    
    /**
     * Loads photo history from storage.
     */
    private fun loadPhotoHistory() {
        try {
            val photos = photoDir.listFiles()
                ?.filter { it.isFile && it.extension == "jpg" }
                ?.sortedByDescending { it.lastModified() }
                ?.mapNotNull { file ->
                    try {
                        val options = BitmapFactory.Options().apply {
                            inJustDecodeBounds = true
                        }
                        BitmapFactory.decodeFile(file.absolutePath, options)
                        
                        PhotoData(
                            id = file.nameWithoutExtension,
                            filePath = file.absolutePath,
                            timestamp = file.lastModified(),
                            width = options.outWidth,
                            height = options.outHeight,
                            sizeBytes = file.length().toInt(),
                            transferTimeMs = 0
                        )
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to load photo metadata: ${file.name}")
                        null
                    }
                }
                ?: emptyList()
            
            _photoHistory.value = photos
            Log.d(TAG, "Loaded ${photos.size} photos from history")
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load photo history", e)
        }
    }
    
    /**
     * Updates photo history with new photo.
     */
    private fun updateHistory(photoData: PhotoData) {
        val currentList = _photoHistory.value.toMutableList()
        currentList.add(0, photoData) // Add at beginning
        _photoHistory.value = currentList
    }
    
    /**
     * Removes old photos to stay within limit.
     */
    private fun cleanupOldPhotos() {
        val currentList = _photoHistory.value
        if (currentList.size > MAX_STORED_PHOTOS) {
            val toRemove = currentList.drop(MAX_STORED_PHOTOS)
            toRemove.forEach { photo ->
                try {
                    File(photo.filePath).delete()
                    Log.d(TAG, "Cleaned up old photo: ${photo.id}")
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to cleanup photo: ${photo.id}")
                }
            }
            _photoHistory.value = currentList.take(MAX_STORED_PHOTOS)
        }
    }
}

/**
 * Data class representing a stored photo.
 */
data class PhotoData(
    val id: String,
    val filePath: String,
    val timestamp: Long,
    val width: Int,
    val height: Int,
    val sizeBytes: Int,
    val transferTimeMs: Long,
    var analysisResult: String? = null
) {
    val formattedSize: String
        get() = when {
            sizeBytes < 1024 -> "$sizeBytes B"
            sizeBytes < 1024 * 1024 -> "${sizeBytes / 1024} KB"
            else -> "%.1f MB".format(sizeBytes / (1024.0 * 1024.0))
        }
    
    val formattedDimensions: String
        get() = "${width}x${height}"
    
    val formattedTimestamp: String
        get() {
            val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
            return sdf.format(Date(timestamp))
        }
}
