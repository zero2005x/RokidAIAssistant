package com.example.rokidphone.viewmodel

import android.app.Application
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.rokidphone.service.photo.PhotoData
import com.example.rokidphone.service.photo.PhotoRepository
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

/**
 * Data class representing the UI state for photo gallery
 */
data class PhotoGalleryUiState(
    val selectedPhotos: Set<String> = emptySet(),
    val isSelectionMode: Boolean = false,
    val isLoading: Boolean = false,
    val currentDetailPhoto: PhotoData? = null,
    val showDeleteConfirmDialog: Boolean = false,
    val showClearAllDialog: Boolean = false
)

/**
 * ViewModel for photo gallery management
 * Wraps PhotoRepository to provide UI-friendly state and actions
 */
class PhotoGalleryViewModel(application: Application) : AndroidViewModel(application) {
    
    private val context: Context = application.applicationContext
    private val photoRepository = PhotoRepository(context, viewModelScope)
    
    /**
     * All photos in the gallery, grouped by date
     */
    val photos: StateFlow<List<PhotoData>> = photoRepository.photoHistory
    
    /**
     * UI state for the gallery
     */
    private val _uiState = MutableStateFlow(PhotoGalleryUiState())
    val uiState: StateFlow<PhotoGalleryUiState> = _uiState.asStateFlow()
    
    /**
     * Photos grouped by date for display
     */
    val groupedPhotos: StateFlow<Map<String, List<PhotoData>>> = photos
        .map { photoList ->
            photoList.groupBy { photo ->
                val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
                sdf.format(Date(photo.timestamp))
            }.toSortedMap(compareByDescending { it })
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyMap()
        )
    
    /**
     * Load a bitmap for display
     */
    suspend fun loadBitmap(photoData: PhotoData, maxSize: Int? = null): Bitmap? {
        return photoRepository.getBitmap(photoData, maxSize)
    }
    
    /**
     * Open photo detail view
     */
    fun openPhotoDetail(photoData: PhotoData) {
        _uiState.update { it.copy(currentDetailPhoto = photoData) }
    }
    
    /**
     * Close photo detail view
     */
    fun closePhotoDetail() {
        _uiState.update { it.copy(currentDetailPhoto = null) }
    }
    
    /**
     * Toggle selection mode
     */
    fun toggleSelectionMode() {
        _uiState.update { 
            if (it.isSelectionMode) {
                it.copy(isSelectionMode = false, selectedPhotos = emptySet())
            } else {
                it.copy(isSelectionMode = true)
            }
        }
    }
    
    /**
     * Toggle photo selection
     */
    fun togglePhotoSelection(photoId: String) {
        _uiState.update { state ->
            val newSelected = if (state.selectedPhotos.contains(photoId)) {
                state.selectedPhotos - photoId
            } else {
                state.selectedPhotos + photoId
            }
            state.copy(
                selectedPhotos = newSelected,
                isSelectionMode = newSelected.isNotEmpty()
            )
        }
    }
    
    /**
     * Select all photos
     */
    fun selectAll() {
        val allIds = photos.value.map { it.id }.toSet()
        _uiState.update { it.copy(selectedPhotos = allIds, isSelectionMode = true) }
    }
    
    /**
     * Clear selection
     */
    fun clearSelection() {
        _uiState.update { it.copy(selectedPhotos = emptySet(), isSelectionMode = false) }
    }
    
    /**
     * Show delete confirmation dialog
     */
    fun showDeleteConfirmDialog() {
        _uiState.update { it.copy(showDeleteConfirmDialog = true) }
    }
    
    /**
     * Hide delete confirmation dialog
     */
    fun hideDeleteConfirmDialog() {
        _uiState.update { it.copy(showDeleteConfirmDialog = false) }
    }
    
    /**
     * Show clear all confirmation dialog
     */
    fun showClearAllDialog() {
        _uiState.update { it.copy(showClearAllDialog = true) }
    }
    
    /**
     * Hide clear all confirmation dialog
     */
    fun hideClearAllDialog() {
        _uiState.update { it.copy(showClearAllDialog = false) }
    }
    
    /**
     * Delete single photo
     */
    fun deletePhoto(photoData: PhotoData) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            photoRepository.deletePhoto(photoData)
            _uiState.update { 
                it.copy(
                    isLoading = false,
                    currentDetailPhoto = null
                )
            }
        }
    }
    
    /**
     * Delete selected photos
     */
    fun deleteSelectedPhotos() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, showDeleteConfirmDialog = false) }
            val selectedIds = _uiState.value.selectedPhotos
            val photosToDelete = photos.value.filter { selectedIds.contains(it.id) }
            
            photosToDelete.forEach { photo ->
                photoRepository.deletePhoto(photo)
            }
            
            _uiState.update { 
                it.copy(
                    isLoading = false,
                    selectedPhotos = emptySet(),
                    isSelectionMode = false
                )
            }
        }
    }
    
    /**
     * Clear all photos
     */
    fun clearAllPhotos() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, showClearAllDialog = false) }
            photoRepository.clearAll()
            _uiState.update { 
                it.copy(
                    isLoading = false,
                    selectedPhotos = emptySet(),
                    isSelectionMode = false
                )
            }
        }
    }
    
    /**
     * Share a photo using Intent
     */
    fun sharePhoto(photoData: PhotoData, onIntent: (Intent) -> Unit) {
        viewModelScope.launch {
            try {
                val file = File(photoData.filePath)
                if (file.exists()) {
                    val uri: Uri = FileProvider.getUriForFile(
                        context,
                        "${context.packageName}.fileprovider",
                        file
                    )
                    
                    val shareIntent = Intent(Intent.ACTION_SEND).apply {
                        type = "image/jpeg"
                        putExtra(Intent.EXTRA_STREAM, uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    
                    onIntent(Intent.createChooser(shareIntent, null))
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
    
    /**
     * Share multiple selected photos
     */
    fun shareSelectedPhotos(onIntent: (Intent) -> Unit) {
        viewModelScope.launch {
            try {
                val selectedIds = _uiState.value.selectedPhotos
                val photosToShare = photos.value.filter { selectedIds.contains(it.id) }
                
                if (photosToShare.isEmpty()) return@launch
                
                val uris = ArrayList<Uri>()
                photosToShare.forEach { photo ->
                    val file = File(photo.filePath)
                    if (file.exists()) {
                        val uri = FileProvider.getUriForFile(
                            context,
                            "${context.packageName}.fileprovider",
                            file
                        )
                        uris.add(uri)
                    }
                }
                
                if (uris.isNotEmpty()) {
                    val shareIntent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                        type = "image/jpeg"
                        putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    
                    onIntent(Intent.createChooser(shareIntent, null))
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
    
    /**
     * Get photo count
     */
    val photoCount: StateFlow<Int> = photos
        .map { it.size }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = 0
        )
}
