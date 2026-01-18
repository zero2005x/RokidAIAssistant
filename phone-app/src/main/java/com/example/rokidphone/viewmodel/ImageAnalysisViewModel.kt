package com.example.rokidphone.viewmodel

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.rokidphone.data.AiRepository
import com.example.rokidphone.data.ImageAnalysisResult
import com.example.rokidphone.service.photo.PhotoData
import com.example.rokidphone.service.photo.PhotoRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 图像分析状态
 */
sealed class ImageAnalysisState {
    /** 空闲状态 */
    object Idle : ImageAnalysisState()
    
    /** 正在分析 */
    data class Analyzing(
        val photoData: PhotoData? = null,
        val progress: Float = 0f,
        val message: String = "正在分析图像..."
    ) : ImageAnalysisState()
    
    /** 分析成功 */
    data class Success(
        val photoData: PhotoData,
        val description: String,
        val analysisTimeMs: Long = 0
    ) : ImageAnalysisState()
    
    /** 分析失败 */
    data class Error(
        val photoData: PhotoData? = null,
        val message: String,
        val canRetry: Boolean = true
    ) : ImageAnalysisState()
}

/**
 * UI 状态
 */
data class ImageAnalysisUiState(
    val analysisState: ImageAnalysisState = ImageAnalysisState.Idle,
    val recentPhotos: List<PhotoData> = emptyList(),
    val selectedPhoto: PhotoData? = null,
    val selectedPhotoBitmap: Bitmap? = null,
    val analysisMode: AiRepository.AnalysisMode = AiRepository.AnalysisMode.DESCRIPTION,
    val isAutoAnalyzeEnabled: Boolean = true,
    val providerName: String = "",
    val modelId: String = "",
    val isServiceAvailable: Boolean = false
)

/**
 * ImageAnalysisViewModel
 * 
 * 职责：
 * 1. 管理图像分析的 UI 状态
 * 2. 协调 AiRepository 进行图像分析
 * 3. 监听新接收的照片并自动分析
 * 4. 提供重试和取消功能
 */
class ImageAnalysisViewModel(
    private val aiRepository: AiRepository,
    private val photoRepository: PhotoRepository
) : ViewModel() {
    
    companion object {
        private const val TAG = "ImageAnalysisVM"
        private const val MAX_RECENT_PHOTOS = 10
    }
    
    private val _uiState = MutableStateFlow(ImageAnalysisUiState())
    val uiState: StateFlow<ImageAnalysisUiState> = _uiState.asStateFlow()
    
    private var currentAnalysisJob: Job? = null
    
    init {
        // 初始化服务信息
        updateServiceInfo()
        
        // 监听新接收的照片
        viewModelScope.launch {
            photoRepository.photoFlow.collect { photoData ->
                Log.d(TAG, "New photo received: ${photoData.filePath}")
                
                // 添加到最近照片列表
                addRecentPhoto(photoData)
                
                // 如果启用了自动分析，开始分析
                if (_uiState.value.isAutoAnalyzeEnabled) {
                    analyzePhoto(photoData)
                }
            }
        }
    }
    
    /**
     * 更新 AI 服务信息
     */
    private fun updateServiceInfo() {
        _uiState.update { 
            it.copy(
                isServiceAvailable = aiRepository.isServiceAvailable(),
                providerName = aiRepository.getCurrentProviderName(),
                modelId = aiRepository.getCurrentModelId()
            )
        }
    }
    
    /**
     * 分析照片
     */
    fun analyzePhoto(photoData: PhotoData) {
        // 取消之前的分析任务
        currentAnalysisJob?.cancel()
        
        currentAnalysisJob = viewModelScope.launch {
            val startTime = System.currentTimeMillis()
            
            // 更新状态：正在分析
            _uiState.update { 
                it.copy(
                    analysisState = ImageAnalysisState.Analyzing(
                        photoData = photoData,
                        message = "正在准备分析..."
                    ),
                    selectedPhoto = photoData
                )
            }
            
            try {
                // 获取照片字节数据
                val photoBytes = photoRepository.getPhotoBytes(photoData)
                
                if (photoBytes == null) {
                    _uiState.update {
                        it.copy(
                            analysisState = ImageAnalysisState.Error(
                                photoData = photoData,
                                message = "无法读取照片数据"
                            )
                        )
                    }
                    return@launch
                }
                
                // 更新状态：正在分析
                _uiState.update {
                    it.copy(
                        analysisState = ImageAnalysisState.Analyzing(
                            photoData = photoData,
                            progress = 0.3f,
                            message = "正在调用 AI 分析..."
                        )
                    )
                }
                
                // 调用 AI 分析
                val result = aiRepository.analyzeImage(
                    imageData = photoBytes,
                    mode = _uiState.value.analysisMode
                )
                
                val analysisTime = System.currentTimeMillis() - startTime
                
                when (result) {
                    is ImageAnalysisResult.Success -> {
                        Log.d(TAG, "Analysis success: ${result.description.take(100)}")
                        
                        // 更新 photoData 的分析结果
                        photoData.analysisResult = result.description
                        
                        _uiState.update {
                            it.copy(
                                analysisState = ImageAnalysisState.Success(
                                    photoData = photoData,
                                    description = result.description,
                                    analysisTimeMs = analysisTime
                                )
                            )
                        }
                    }
                    
                    is ImageAnalysisResult.Error -> {
                        Log.e(TAG, "Analysis error: ${result.message}")
                        
                        _uiState.update {
                            it.copy(
                                analysisState = ImageAnalysisState.Error(
                                    photoData = photoData,
                                    message = result.message
                                )
                            )
                        }
                    }
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "Analysis failed", e)
                
                _uiState.update {
                    it.copy(
                        analysisState = ImageAnalysisState.Error(
                            photoData = photoData,
                            message = e.message ?: "分析失败"
                        )
                    )
                }
            }
        }
    }
    
    /**
     * 使用 Bitmap 分析
     */
    fun analyzeBitmap(bitmap: Bitmap) {
        currentAnalysisJob?.cancel()
        
        currentAnalysisJob = viewModelScope.launch {
            val startTime = System.currentTimeMillis()
            
            _uiState.update {
                it.copy(
                    analysisState = ImageAnalysisState.Analyzing(
                        message = "正在分析图像..."
                    ),
                    selectedPhotoBitmap = bitmap
                )
            }
            
            try {
                val result = aiRepository.analyzeImage(
                    bitmap = bitmap,
                    mode = _uiState.value.analysisMode
                )
                
                val analysisTime = System.currentTimeMillis() - startTime
                
                when (result) {
                    is ImageAnalysisResult.Success -> {
                        _uiState.update {
                            it.copy(
                                analysisState = ImageAnalysisState.Success(
                                    photoData = PhotoData(
                                        id = java.util.UUID.randomUUID().toString(),
                                        filePath = "",
                                        timestamp = System.currentTimeMillis(),
                                        width = bitmap.width,
                                        height = bitmap.height,
                                        sizeBytes = 0,
                                        transferTimeMs = 0,
                                        analysisResult = result.description
                                    ),
                                    description = result.description,
                                    analysisTimeMs = analysisTime
                                )
                            )
                        }
                    }
                    
                    is ImageAnalysisResult.Error -> {
                        _uiState.update {
                            it.copy(
                                analysisState = ImageAnalysisState.Error(
                                    message = result.message
                                )
                            )
                        }
                    }
                }
                
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        analysisState = ImageAnalysisState.Error(
                            message = e.message ?: "分析失败"
                        )
                    )
                }
            }
        }
    }
    
    /**
     * 重试分析
     */
    fun retryAnalysis() {
        val state = _uiState.value.analysisState
        when (state) {
            is ImageAnalysisState.Error -> {
                state.photoData?.let { analyzePhoto(it) }
            }
            else -> {
                _uiState.value.selectedPhoto?.let { analyzePhoto(it) }
            }
        }
    }
    
    /**
     * 取消分析
     */
    fun cancelAnalysis() {
        currentAnalysisJob?.cancel()
        currentAnalysisJob = null
        
        _uiState.update {
            it.copy(analysisState = ImageAnalysisState.Idle)
        }
    }
    
    /**
     * 选择照片
     */
    fun selectPhoto(photoData: PhotoData) {
        _uiState.update {
            it.copy(selectedPhoto = photoData)
        }
    }
    
    /**
     * 设置分析模式
     */
    fun setAnalysisMode(mode: AiRepository.AnalysisMode) {
        _uiState.update {
            it.copy(analysisMode = mode)
        }
    }
    
    /**
     * 设置自动分析
     */
    fun setAutoAnalyzeEnabled(enabled: Boolean) {
        _uiState.update {
            it.copy(isAutoAnalyzeEnabled = enabled)
        }
    }
    
    /**
     * 添加到最近照片列表
     */
    private fun addRecentPhoto(photoData: PhotoData) {
        _uiState.update { state ->
            val newList = listOf(photoData) + state.recentPhotos
            state.copy(
                recentPhotos = newList.take(MAX_RECENT_PHOTOS)
            )
        }
    }
    
    /**
     * 重置状态
     */
    fun resetState() {
        cancelAnalysis()
        _uiState.update {
            it.copy(
                analysisState = ImageAnalysisState.Idle,
                selectedPhoto = null,
                selectedPhotoBitmap = null
            )
        }
    }
    
    /**
     * 刷新服务信息
     */
    fun refreshServiceInfo() {
        updateServiceInfo()
    }
    
    override fun onCleared() {
        super.onCleared()
        currentAnalysisJob?.cancel()
    }
    
    /**
     * ViewModel Factory
     */
    class Factory(
        private val context: Context,
        private val photoRepository: PhotoRepository
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (modelClass.isAssignableFrom(ImageAnalysisViewModel::class.java)) {
                return ImageAnalysisViewModel(
                    aiRepository = AiRepository.getInstance(context),
                    photoRepository = photoRepository
                ) as T
            }
            throw IllegalArgumentException("Unknown ViewModel class")
        }
    }
}
