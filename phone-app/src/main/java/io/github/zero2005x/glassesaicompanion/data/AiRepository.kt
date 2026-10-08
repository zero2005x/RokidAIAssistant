package io.github.zero2005x.glassesaicompanion.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import android.util.Log
import io.github.zero2005x.glassesaicompanion.R
import io.github.zero2005x.glassesaicompanion.service.ai.AiServiceFactory
import io.github.zero2005x.glassesaicompanion.service.ai.AiServiceProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

/**
 * Image analysis result
 */
sealed class ImageAnalysisResult {
    data class Success(val description: String) : ImageAnalysisResult()
    data class Error(val message: String, val exception: Throwable? = null) : ImageAnalysisResult()
}

/**
 * AI Repository - AI Service Encapsulation Layer
 * 
 * Responsibilities:
 * 1. Encapsulate AI service calls
 * 2. Manage image preprocessing
 * 3. Unified error handling
 * 4. Support multiple analysis modes
 */
class AiRepository private constructor(
    private val context: Context,
    private val settingsRepository: SettingsRepository
) {
    companion object {
        private const val TAG = "AiRepository"
        
        // Image processing parameters
        private const val MAX_IMAGE_SIZE = 1024 // Maximum side length
        private const val JPEG_QUALITY = 85
        
        @Volatile
        private var instance: AiRepository? = null
        
        fun getInstance(context: Context): AiRepository {
            return instance ?: synchronized(this) {
                instance ?: AiRepository(
                    context.applicationContext,
                    SettingsRepository.getInstance(context.applicationContext)
                ).also { instance = it }
            }
        }
    }
    
    /**
     * Analysis mode
     */
    enum class AnalysisMode {
        DESCRIPTION,    // Detailed description
        OCR,           // Text recognition
        TRANSLATE,     // Translation
        SUMMARY,       // Brief summary
        CUSTOM         // Custom prompt
    }
    
    /**
     * Create AI service instance
     */
    private fun createAiService(): AiServiceProvider {
        val settings = settingsRepository.getSettings()
        return AiServiceFactory.createService(settings)
    }
    
    /**
     * Analyze image (using byte array)
     * 
     * @param imageData Image data (JPEG/PNG)
     * @param mode Analysis mode
     * @param customPrompt Custom prompt (only used when mode is CUSTOM)
     * @return Analysis result
     */
    suspend fun analyzeImage(
        imageData: ByteArray,
        mode: AnalysisMode = AnalysisMode.DESCRIPTION,
        customPrompt: String? = null
    ): ImageAnalysisResult = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Analyzing image: ${imageData.size} bytes, mode: $mode")
            
            // Preprocess image
            val processedData = preprocessImage(imageData)
            
            // Get prompt from localized strings
            val prompt = when (mode) {
                AnalysisMode.DESCRIPTION -> context.getString(R.string.image_analysis_prompt)
                AnalysisMode.OCR -> context.getString(R.string.image_ocr_prompt)
                AnalysisMode.TRANSLATE -> context.getString(R.string.image_translate_prompt)
                AnalysisMode.SUMMARY -> context.getString(R.string.image_summary_prompt)
                AnalysisMode.CUSTOM -> customPrompt ?: context.getString(R.string.image_analysis_prompt)
            }
            
            // Call AI service
            val aiService = createAiService()
            val result = aiService.analyzeImage(processedData, prompt)
            
            // Log length only; the answer may contain OCR'd personal data
            Log.d(TAG, "Analysis completed: ${result.length} chars")
            ImageAnalysisResult.Success(result)

        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Image analysis failed", e)
            ImageAnalysisResult.Error(
                message = e.message ?: context.getString(R.string.analysis_failed),
                exception = e
            )
        }
    }
    
    /**
     * Analyze image (using Base64 string)
     */
    suspend fun analyzeImageBase64(
        base64Image: String,
        mode: AnalysisMode = AnalysisMode.DESCRIPTION,
        customPrompt: String? = null
    ): ImageAnalysisResult {
        return try {
            // Decoding a full image allocates a large byte array; keep it off the main thread
            val imageData = withContext(Dispatchers.IO) { Base64.decode(base64Image, Base64.DEFAULT) }
            analyzeImage(imageData, mode, customPrompt)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Failed to decode base64 image", e)
            ImageAnalysisResult.Error(context.getString(R.string.image_decode_failed), e)
        }
    }
    
    /**
     * Analyze image (using Bitmap)
     */
    suspend fun analyzeImage(
        bitmap: Bitmap,
        mode: AnalysisMode = AnalysisMode.DESCRIPTION,
        customPrompt: String? = null
    ): ImageAnalysisResult {
        return try {
            // JPEG encoding is expensive; keep it off the main thread
            val imageData = withContext(Dispatchers.IO) { bitmapToBytes(bitmap) }
            analyzeImage(imageData, mode, customPrompt)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Failed to convert bitmap", e)
            ImageAnalysisResult.Error(context.getString(R.string.image_convert_failed), e)
        }
    }
    
    /**
     * Quick image description (simplified interface)
     */
    suspend fun describeImage(imageData: ByteArray): Result<String> {
        return when (val result = analyzeImage(imageData, AnalysisMode.DESCRIPTION)) {
            is ImageAnalysisResult.Success -> Result.success(result.description)
            is ImageAnalysisResult.Error -> Result.failure(
                result.exception ?: Exception(result.message)
            )
        }
    }
    
    /**
     * Text recognition (OCR)
     */
    suspend fun recognizeText(imageData: ByteArray): Result<String> {
        return when (val result = analyzeImage(imageData, AnalysisMode.OCR)) {
            is ImageAnalysisResult.Success -> Result.success(result.description)
            is ImageAnalysisResult.Error -> Result.failure(
                result.exception ?: Exception(result.message)
            )
        }
    }
    
    /**
     * Translate text in image
     */
    suspend fun translateImage(imageData: ByteArray): Result<String> {
        return when (val result = analyzeImage(imageData, AnalysisMode.TRANSLATE)) {
            is ImageAnalysisResult.Success -> Result.success(result.description)
            is ImageAnalysisResult.Error -> Result.failure(
                result.exception ?: Exception(result.message)
            )
        }
    }
    
    /**
     * Preprocess image
     * - Resize to save bandwidth
     * - Compress quality
     */
    private fun preprocessImage(imageData: ByteArray): ByteArray {
        return try {
            // Decode image
            val options = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            BitmapFactory.decodeByteArray(imageData, 0, imageData.size, options)
            
            val width = options.outWidth
            val height = options.outHeight

            // Bounds decode failed (corrupt/unsupported data yields -1); bail out
            // instead of silently treating the payload as "small enough"
            if (width <= 0 || height <= 0) {
                Log.w(TAG, "Unable to decode image bounds, using original")
                return imageData
            }

            // If image is already small enough, return directly
            if (width <= MAX_IMAGE_SIZE && height <= MAX_IMAGE_SIZE) {
                return imageData
            }

            // Power-of-two sample size guaranteeing max side <= MAX_IMAGE_SIZE after decode
            // (the decoder rounds inSampleSize down to a power of two anyway)
            var sampleSize = 1
            while (maxOf(width, height) / sampleSize > MAX_IMAGE_SIZE) {
                sampleSize *= 2
            }

            // Decode and scale the image
            val decodeOptions = BitmapFactory.Options().apply {
                inSampleSize = sampleSize
            }
            val bitmap = BitmapFactory.decodeByteArray(imageData, 0, imageData.size, decodeOptions)
                ?: return imageData

            // Compress to JPEG, releasing native bitmap memory even on failure
            val result = try {
                bitmapToBytes(bitmap, JPEG_QUALITY)
            } finally {
                bitmap.recycle()
            }
            
            Log.d(TAG, "Image preprocessed: ${imageData.size} -> ${result.size} bytes")
            result
            
        } catch (e: Exception) {
            Log.w(TAG, "Image preprocessing failed, using original", e)
            imageData
        }
    }
    
    /**
     * Convert Bitmap to ByteArray
     */
    private fun bitmapToBytes(bitmap: Bitmap, quality: Int = JPEG_QUALITY): ByteArray {
        return ByteArrayOutputStream().use { stream ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, quality, stream)
            stream.toByteArray()
        }
    }
    
    /**
     * Check if AI service is available
     */
    fun isServiceAvailable(): Boolean {
        val settings = settingsRepository.getSettings()
        return settings.getCurrentApiKey().isNotBlank()
    }
    
    /**
     * Get current AI provider name
     */
    fun getCurrentProviderName(): String {
        // Return provider enum name since displayName requires Context to resolve resources
        return settingsRepository.getSettings().aiProvider.name
    }
    
    /**
     * Get current model ID
     */
    fun getCurrentModelId(): String {
        return settingsRepository.getSettings().getCurrentModelId()
    }
}
