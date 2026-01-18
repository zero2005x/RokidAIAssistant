package com.example.rokidphone.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import android.util.Log
import com.example.rokidphone.service.ai.AiServiceFactory
import com.example.rokidphone.service.ai.AiServiceProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

/**
 * 图像分析结果
 */
sealed class ImageAnalysisResult {
    data class Success(val description: String) : ImageAnalysisResult()
    data class Error(val message: String, val exception: Throwable? = null) : ImageAnalysisResult()
}

/**
 * AI Repository - AI 服务封装层
 * 
 * 职责：
 * 1. 封装 AI 服务调用
 * 2. 管理图像预处理
 * 3. 统一错误处理
 * 4. 支持多种分析模式
 */
class AiRepository private constructor(
    private val context: Context,
    private val settingsRepository: SettingsRepository
) {
    companion object {
        private const val TAG = "AiRepository"
        
        // 图像分析提示词
        private const val PROMPT_IMAGE_DESCRIPTION = 
            "请详细描述这张图片中你所看到的内容。包括主要对象、场景、颜色、动作等细节。用中文回答，简洁明了。"
        
        private const val PROMPT_IMAGE_OCR = 
            "请识别并提取这张图片中的所有文字内容。如果有表格，请保持格式。如果没有文字，请说明图片内容。用中文回答。"
        
        private const val PROMPT_IMAGE_TRANSLATE = 
            "请识别图片中的文字，并翻译成中文。如果已经是中文，请翻译成英文。同时提供原文和译文。"
        
        private const val PROMPT_IMAGE_SUMMARY = 
            "请用一句话概括这张图片的主要内容。用中文回答。"
        
        // 图像处理参数
        private const val MAX_IMAGE_SIZE = 1024 // 最大边长
        private const val JPEG_QUALITY = 85
        
        @Volatile
        private var instance: AiRepository? = null
        
        fun getInstance(context: Context): AiRepository {
            return instance ?: synchronized(this) {
                instance ?: AiRepository(
                    context.applicationContext,
                    SettingsRepository.getInstance(context)
                ).also { instance = it }
            }
        }
    }
    
    /**
     * 分析模式
     */
    enum class AnalysisMode {
        DESCRIPTION,    // 详细描述
        OCR,           // 文字识别
        TRANSLATE,     // 翻译
        SUMMARY,       // 简要概括
        CUSTOM         // 自定义提示
    }
    
    /**
     * 创建 AI 服务实例
     */
    private fun createAiService(): AiServiceProvider {
        val settings = settingsRepository.getSettings()
        return AiServiceFactory.createService(settings)
    }
    
    /**
     * 分析图像（使用字节数组）
     * 
     * @param imageData 图像数据 (JPEG/PNG)
     * @param mode 分析模式
     * @param customPrompt 自定义提示词 (仅当 mode 为 CUSTOM 时使用)
     * @return 分析结果
     */
    suspend fun analyzeImage(
        imageData: ByteArray,
        mode: AnalysisMode = AnalysisMode.DESCRIPTION,
        customPrompt: String? = null
    ): ImageAnalysisResult = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Analyzing image: ${imageData.size} bytes, mode: $mode")
            
            // 预处理图像
            val processedData = preprocessImage(imageData)
            
            // 获取提示词
            val prompt = when (mode) {
                AnalysisMode.DESCRIPTION -> PROMPT_IMAGE_DESCRIPTION
                AnalysisMode.OCR -> PROMPT_IMAGE_OCR
                AnalysisMode.TRANSLATE -> PROMPT_IMAGE_TRANSLATE
                AnalysisMode.SUMMARY -> PROMPT_IMAGE_SUMMARY
                AnalysisMode.CUSTOM -> customPrompt ?: PROMPT_IMAGE_DESCRIPTION
            }
            
            // 调用 AI 服务
            val aiService = createAiService()
            val result = aiService.analyzeImage(processedData, prompt)
            
            Log.d(TAG, "Analysis completed: ${result.take(100)}...")
            ImageAnalysisResult.Success(result)
            
        } catch (e: Exception) {
            Log.e(TAG, "Image analysis failed", e)
            ImageAnalysisResult.Error(
                message = e.message ?: "分析失败",
                exception = e
            )
        }
    }
    
    /**
     * 分析图像（使用 Base64 字符串）
     */
    suspend fun analyzeImageBase64(
        base64Image: String,
        mode: AnalysisMode = AnalysisMode.DESCRIPTION,
        customPrompt: String? = null
    ): ImageAnalysisResult {
        return try {
            val imageData = Base64.decode(base64Image, Base64.DEFAULT)
            analyzeImage(imageData, mode, customPrompt)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to decode base64 image", e)
            ImageAnalysisResult.Error("图像解码失败", e)
        }
    }
    
    /**
     * 分析图像（使用 Bitmap）
     */
    suspend fun analyzeImage(
        bitmap: Bitmap,
        mode: AnalysisMode = AnalysisMode.DESCRIPTION,
        customPrompt: String? = null
    ): ImageAnalysisResult {
        return try {
            val imageData = bitmapToBytes(bitmap)
            analyzeImage(imageData, mode, customPrompt)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to convert bitmap", e)
            ImageAnalysisResult.Error("图像转换失败", e)
        }
    }
    
    /**
     * 快速描述图像（简化接口）
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
     * 文字识别（OCR）
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
     * 翻译图片中的文字
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
     * 预处理图像
     * - 调整大小以节省带宽
     * - 压缩质量
     */
    private fun preprocessImage(imageData: ByteArray): ByteArray {
        return try {
            // 解码图像
            val options = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            BitmapFactory.decodeByteArray(imageData, 0, imageData.size, options)
            
            val width = options.outWidth
            val height = options.outHeight
            
            // 如果图像已经足够小，直接返回
            if (width <= MAX_IMAGE_SIZE && height <= MAX_IMAGE_SIZE) {
                return imageData
            }
            
            // 计算缩放比例
            val scale = maxOf(width, height).toFloat() / MAX_IMAGE_SIZE
            
            // 解码并缩放
            val decodeOptions = BitmapFactory.Options().apply {
                inSampleSize = scale.toInt().coerceAtLeast(1)
            }
            val bitmap = BitmapFactory.decodeByteArray(imageData, 0, imageData.size, decodeOptions)
                ?: return imageData
            
            // 压缩为 JPEG
            val result = bitmapToBytes(bitmap, JPEG_QUALITY)
            bitmap.recycle()
            
            Log.d(TAG, "Image preprocessed: ${imageData.size} -> ${result.size} bytes")
            result
            
        } catch (e: Exception) {
            Log.w(TAG, "Image preprocessing failed, using original", e)
            imageData
        }
    }
    
    /**
     * Bitmap 转 ByteArray
     */
    private fun bitmapToBytes(bitmap: Bitmap, quality: Int = JPEG_QUALITY): ByteArray {
        return ByteArrayOutputStream().use { stream ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, quality, stream)
            stream.toByteArray()
        }
    }
    
    /**
     * 检查 AI 服务是否可用
     */
    fun isServiceAvailable(): Boolean {
        val settings = settingsRepository.getSettings()
        return settings.getCurrentApiKey().isNotBlank()
    }
    
    /**
     * 获取当前 AI 提供商名称
     */
    fun getCurrentProviderName(): String {
        // 返回 provider 的枚举名称，因为 displayName 需要 Context 来解析资源
        return settingsRepository.getSettings().aiProvider.name
    }
    
    /**
     * 获取当前模型 ID
     */
    fun getCurrentModelId(): String {
        return settingsRepository.getSettings().getCurrentModelId()
    }
}
