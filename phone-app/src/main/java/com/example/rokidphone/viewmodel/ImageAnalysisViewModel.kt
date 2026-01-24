package com.example.rokidphone.viewmodel

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.rokidphone.R
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
 * UI text representation that supports both string resources and dynamic strings.
 * Use this to avoid hardcoding strings in ViewModels while maintaining flexibility
 * for dynamic error messages from APIs.
 */
sealed class UiText {
    /** String resource (for localized messages) */
    data class Resource(@StringRes val resId: Int) : UiText()
    
    /** Dynamic string (for API error messages) */
    data class Dynamic(val text: String) : UiText()
    
    /** Resolve to actual string */
    fun asString(context: Context): String = when (this) {
        is Resource -> context.getString(resId)
        is Dynamic -> text
    }
}

/**
 * Image analysis state
 */
sealed class ImageAnalysisState {
    /** Idle state */
    object Idle : ImageAnalysisState()
    
    /** Analyzing */
    data class Analyzing(
        val photoData: PhotoData? = null,
        val progress: Float = 0f,
        val message: UiText = UiText.Resource(R.string.analyzing_image)
    ) : ImageAnalysisState()
    
    /** Analysis successful */
    data class Success(
        val photoData: PhotoData,
        val description: String,
        val analysisTimeMs: Long = 0
    ) : ImageAnalysisState()
    
    /** Analysis failed */
    data class Error(
        val photoData: PhotoData? = null,
        val message: UiText,
        val canRetry: Boolean = true
    ) : ImageAnalysisState()
}

/**
 * UI state
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
 * Responsibilities:
 * 1. Manage image analysis UI state
 * 2. Coordinate with AiRepository for image analysis
 * 3. Listen for newly received photos and auto-analyze
 * 4. Provide retry and cancel functionality
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
        // Initialize service information
        updateServiceInfo()
        
        // Listen for newly received photos
        viewModelScope.launch {
            photoRepository.photoFlow.collect { photoData ->
                Log.d(TAG, "New photo received: ${photoData.filePath}")
                
                // Add to recent photos list
                addRecentPhoto(photoData)
                
                // If auto-analysis is enabled, start analyzing
                if (_uiState.value.isAutoAnalyzeEnabled) {
                    analyzePhoto(photoData)
                }
            }
        }
    }
    
    /**
     * Update AI service information
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
     * Analyze photo
     */
    fun analyzePhoto(photoData: PhotoData) {
        // Cancel previous analysis task
        currentAnalysisJob?.cancel()
        
        currentAnalysisJob = viewModelScope.launch {
            val startTime = System.currentTimeMillis()
            
            // Update state: analyzing
            _uiState.update { 
                it.copy(
                    analysisState = ImageAnalysisState.Analyzing(
                        photoData = photoData,
                        message = UiText.Resource(R.string.preparing_analysis)
                    ),
                    selectedPhoto = photoData
                )
            }
            
            try {
                // Get photo byte data
                val photoBytes = photoRepository.getPhotoBytes(photoData)
                
                if (photoBytes == null) {
                    _uiState.update {
                        it.copy(
                            analysisState = ImageAnalysisState.Error(
                                photoData = photoData,
                                message = UiText.Resource(R.string.unable_to_read_photo)
                            )
                        )
                    }
                    return@launch
                }
                
                // Update state: analyzing
                _uiState.update {
                    it.copy(
                        analysisState = ImageAnalysisState.Analyzing(
                            photoData = photoData,
                            progress = 0.3f,
                            message = UiText.Resource(R.string.calling_ai_analysis)
                        )
                    )
                }
                
                // Call AI for analysis
                val result = aiRepository.analyzeImage(
                    imageData = photoBytes,
                    mode = _uiState.value.analysisMode
                )
                
                val analysisTime = System.currentTimeMillis() - startTime
                
                when (result) {
                    is ImageAnalysisResult.Success -> {
                        Log.d(TAG, "Analysis success: ${result.description.take(100)}")
                        
                        // Update photoData analysis result
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
                                    message = UiText.Dynamic(result.message)
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
                            message = UiText.Dynamic(e.message ?: "Analysis failed")
                        )
                    )
                }
            }
        }
    }
    
    /**
     * Analyze using Bitmap
     */
    fun analyzeBitmap(bitmap: Bitmap) {
        currentAnalysisJob?.cancel()
        
        currentAnalysisJob = viewModelScope.launch {
            val startTime = System.currentTimeMillis()
            
            _uiState.update {
                it.copy(
                    analysisState = ImageAnalysisState.Analyzing(
                        message = UiText.Resource(R.string.analyzing_image)
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
                                    message = UiText.Dynamic(result.message)
                                )
                            )
                        }
                    }
                }
                
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        analysisState = ImageAnalysisState.Error(
                            message = UiText.Dynamic(e.message ?: "Analysis failed")
                        )
                    )
                }
            }
        }
    }
    
    /**
     * Retry analysis
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
     * Cancel analysis
     */
    fun cancelAnalysis() {
        currentAnalysisJob?.cancel()
        currentAnalysisJob = null
        
        _uiState.update {
            it.copy(analysisState = ImageAnalysisState.Idle)
        }
    }
    
    /**
     * Select photo
     */
    fun selectPhoto(photoData: PhotoData) {
        _uiState.update {
            it.copy(selectedPhoto = photoData)
        }
    }
    
    /**
     * Set analysis mode
     */
    fun setAnalysisMode(mode: AiRepository.AnalysisMode) {
        _uiState.update {
            it.copy(analysisMode = mode)
        }
    }
    
    /**
     * Set auto analysis
     */
    fun setAutoAnalyzeEnabled(enabled: Boolean) {
        _uiState.update {
            it.copy(isAutoAnalyzeEnabled = enabled)
        }
    }
    
    /**
     * Add to recent photos list
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
     * Reset state
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
     * Refresh service info
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
