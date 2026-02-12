package com.example.rokidphone.viewmodel

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.rokidphone.data.SettingsRepository
import com.example.rokidphone.data.db.*
import com.example.rokidphone.service.EnhancedAIService
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

private const val TAG = "RecordingViewModel"

/**
 * Filter options for recordings list
 */
enum class RecordingFilter {
    ALL,
    PHONE,
    GLASSES,
    FAVORITES
}

/**
 * Sort options for recordings list
 */
enum class RecordingSort {
    DATE_DESC,
    DATE_ASC,
    DURATION_DESC,
    DURATION_ASC,
    NAME_ASC,
    NAME_DESC
}

/**
 * UI State for Recordings Screen
 */
data class RecordingsUiState(
    val recordings: List<RecordingEntity> = emptyList(),
    val filteredRecordings: List<RecordingEntity> = emptyList(),
    val selectedRecording: RecordingEntity? = null,
    val isLoading: Boolean = true,
    val filter: RecordingFilter = RecordingFilter.ALL,
    val sort: RecordingSort = RecordingSort.DATE_DESC,
    val searchQuery: String = "",
    val isSelectionMode: Boolean = false,
    val selectedIds: Set<String> = emptySet(),
    val recordingState: RecordingState = RecordingState.Idle,
    val statistics: RecordingStatistics = RecordingStatistics(0, 0),
    val showDeleteDialog: Boolean = false,
    val showEditDialog: Boolean = false,
    val editingRecording: RecordingEntity? = null,
    val processingRecordingId: String? = null,
    val error: String? = null
)

/**
 * ViewModel for Recordings management
 */
class RecordingViewModel(application: Application) : AndroidViewModel(application) {
    
    private val repository = RecordingRepository.getInstance(application, viewModelScope)
    
    private val _uiState = MutableStateFlow(RecordingsUiState())
    val uiState: StateFlow<RecordingsUiState> = _uiState.asStateFlow()
    
    init {
        loadRecordings()
        observeRecordingState()
    }
    
    private fun loadRecordings() {
        viewModelScope.launch {
            repository.getAllRecordings().collect { recordings ->
                _uiState.update { state ->
                    state.copy(
                        recordings = recordings,
                        filteredRecordings = applyFilterAndSort(recordings, state.filter, state.sort, state.searchQuery),
                        isLoading = false
                    )
                }
            }
        }
        
        viewModelScope.launch {
            val stats = repository.getStatistics()
            _uiState.update { it.copy(statistics = stats) }
        }
    }
    
    private fun observeRecordingState() {
        viewModelScope.launch {
            repository.recordingState.collect { state ->
                _uiState.update { it.copy(recordingState = state) }
            }
        }
    }
    
    // ==================== Recording Control ====================
    
    /**
     * Start recording from phone microphone
     */
    fun startPhoneRecording() {
        viewModelScope.launch {
            val result = repository.startPhoneRecording()
            result.onFailure { e ->
                _uiState.update { it.copy(error = e.message) }
            }
        }
    }
    
    /**
     * Start recording from glasses microphone
     */
    fun startGlassesRecording() {
        viewModelScope.launch {
            val result = repository.startGlassesRecording()
            result.onFailure { e ->
                _uiState.update { it.copy(error = e.message) }
            }
        }
    }
    
    /**
     * Stop current recording
     */
    fun stopRecording() {
        viewModelScope.launch {
            val result = repository.stopRecording()
            result.onSuccess { recording ->
                recording?.let {
                    Log.d(TAG, "Recording completed: ${it.id}")
                }
            }
            result.onFailure { e ->
                _uiState.update { it.copy(error = e.message) }
            }
        }
    }
    
    // ==================== Filter & Sort ====================
    
    fun setFilter(filter: RecordingFilter) {
        _uiState.update { state ->
            state.copy(
                filter = filter,
                filteredRecordings = applyFilterAndSort(state.recordings, filter, state.sort, state.searchQuery)
            )
        }
    }
    
    fun setSort(sort: RecordingSort) {
        _uiState.update { state ->
            state.copy(
                sort = sort,
                filteredRecordings = applyFilterAndSort(state.recordings, state.filter, sort, state.searchQuery)
            )
        }
    }
    
    fun setSearchQuery(query: String) {
        _uiState.update { state ->
            state.copy(
                searchQuery = query,
                filteredRecordings = applyFilterAndSort(state.recordings, state.filter, state.sort, query)
            )
        }
    }
    
    private fun applyFilterAndSort(
        recordings: List<RecordingEntity>,
        filter: RecordingFilter,
        sort: RecordingSort,
        searchQuery: String
    ): List<RecordingEntity> {
        var result = recordings
        
        // Apply filter
        result = when (filter) {
            RecordingFilter.ALL -> result
            RecordingFilter.PHONE -> result.filter { it.source == RecordingSource.PHONE }
            RecordingFilter.GLASSES -> result.filter { it.source == RecordingSource.GLASSES }
            RecordingFilter.FAVORITES -> result.filter { it.isFavorite }
        }
        
        // Apply search
        if (searchQuery.isNotBlank()) {
            val query = searchQuery.lowercase()
            result = result.filter { recording ->
                recording.title.lowercase().contains(query) ||
                recording.transcript?.lowercase()?.contains(query) == true ||
                recording.notes?.lowercase()?.contains(query) == true
            }
        }
        
        // Apply sort
        result = when (sort) {
            RecordingSort.DATE_DESC -> result.sortedByDescending { it.createdAt }
            RecordingSort.DATE_ASC -> result.sortedBy { it.createdAt }
            RecordingSort.DURATION_DESC -> result.sortedByDescending { it.durationMs }
            RecordingSort.DURATION_ASC -> result.sortedBy { it.durationMs }
            RecordingSort.NAME_ASC -> result.sortedBy { it.title.lowercase() }
            RecordingSort.NAME_DESC -> result.sortedByDescending { it.title.lowercase() }
        }
        
        return result
    }
    
    // ==================== Selection ====================
    
    fun toggleSelectionMode() {
        _uiState.update { state ->
            if (state.isSelectionMode) {
                state.copy(isSelectionMode = false, selectedIds = emptySet())
            } else {
                state.copy(isSelectionMode = true)
            }
        }
    }
    
    fun toggleSelection(id: String) {
        _uiState.update { state ->
            val newSelected = if (state.selectedIds.contains(id)) {
                state.selectedIds - id
            } else {
                state.selectedIds + id
            }
            state.copy(selectedIds = newSelected)
        }
    }
    
    fun selectAll() {
        _uiState.update { state ->
            state.copy(selectedIds = state.filteredRecordings.map { it.id }.toSet())
        }
    }
    
    fun clearSelection() {
        _uiState.update { it.copy(selectedIds = emptySet(), isSelectionMode = false) }
    }
    
    // ==================== Recording Detail ====================
    
    fun selectRecording(recording: RecordingEntity) {
        _uiState.update { it.copy(selectedRecording = recording) }
    }
    
    fun clearSelectedRecording() {
        _uiState.update { it.copy(selectedRecording = null) }
    }
    
    // ==================== Edit Operations ====================
    
    fun showEditDialog(recording: RecordingEntity) {
        _uiState.update { it.copy(showEditDialog = true, editingRecording = recording) }
    }
    
    fun hideEditDialog() {
        _uiState.update { it.copy(showEditDialog = false, editingRecording = null) }
    }
    
    fun updateTitle(id: String, title: String) {
        viewModelScope.launch {
            repository.updateTitle(id, title)
            hideEditDialog()
        }
    }
    
    fun updateNotes(id: String, notes: String?) {
        viewModelScope.launch {
            repository.updateNotes(id, notes)
        }
    }
    
    fun toggleFavorite(id: String) {
        viewModelScope.launch {
            repository.toggleFavorite(id)
        }
    }
    
    // ==================== Delete Operations ====================
    
    fun showDeleteDialog() {
        _uiState.update { it.copy(showDeleteDialog = true) }
    }
    
    fun hideDeleteDialog() {
        _uiState.update { it.copy(showDeleteDialog = false) }
    }
    
    fun deleteRecording(id: String) {
        viewModelScope.launch {
            repository.deleteRecording(id)
            _uiState.update { state ->
                if (state.selectedRecording?.id == id) {
                    state.copy(selectedRecording = null)
                } else {
                    state
                }
            }
        }
    }
    
    fun deleteSelectedRecordings() {
        viewModelScope.launch {
            val ids = _uiState.value.selectedIds.toList()
            repository.deleteRecordings(ids)
            clearSelection()
            hideDeleteDialog()
        }
    }
    
    // ==================== Processing ====================
    
    /**
     * Transcribe recording using STT service
     */
    fun transcribeRecording(id: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(processingRecordingId = id) }
            
            try {
                // Get recording details
                val recording = repository.getRecordingById(id)
                if (recording == null) {
                    _uiState.update { it.copy(error = "Recording not found", processingRecordingId = null) }
                    return@launch
                }
                
                // Check if file path is valid
                if (recording.filePath.isBlank()) {
                    Log.w(TAG, "Recording $id has empty file path, cannot transcribe")
                    _uiState.update { it.copy(error = "Recording file not found", processingRecordingId = null) }
                    return@launch
                }
                
                // Skip if already transcribed and has AI response (prevent duplicate processing)
                if (!recording.transcript.isNullOrBlank() && !recording.aiResponse.isNullOrBlank()) {
                    Log.d(TAG, "Recording $id already transcribed, skipping")
                    _uiState.update { it.copy(processingRecordingId = null) }
                    return@launch
                }
                
                // Check if file exists
                val file = java.io.File(recording.filePath)
                if (!file.exists()) {
                    Log.e(TAG, "Recording file does not exist: ${recording.filePath}")
                    _uiState.update { it.copy(error = "Recording file not found", processingRecordingId = null) }
                    return@launch
                }
                
                Log.d(TAG, "Transcribing recording: $id, path: ${recording.filePath}")
                
                // Request transcription via ServiceBridge
                com.example.rokidphone.service.ServiceBridge.requestTranscribeRecording(id, recording.filePath)
                
            } catch (e: Exception) {
                Log.e(TAG, "Transcription failed", e)
                repository.markError(id, e.message ?: "Transcription failed")
                _uiState.update { it.copy(error = e.message) }
            } finally {
                _uiState.update { it.copy(processingRecordingId = null) }
            }
        }
    }
    
    /**
     * Analyze transcript with AI
     */
    fun analyzeWithAi(id: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(processingRecordingId = id) }
            
            try {
                val recording = repository.getRecordingById(id)
                if (recording == null) {
                    _uiState.update { it.copy(error = "Recording not found", processingRecordingId = null) }
                    return@launch
                }
                
                if (recording.transcript.isNullOrBlank()) {
                    _uiState.update { it.copy(error = "Please transcribe the recording first", processingRecordingId = null) }
                    return@launch
                }
                
                Log.d(TAG, "Analyzing recording with AI: $id")
                
                val enhancedAIService = EnhancedAIService.getInstance(getApplication())
                val result = enhancedAIService.quickChat(recording.transcript)
                
                result.onSuccess { response ->
                    val settings = SettingsRepository.getInstance(getApplication()).getSettings()
                    repository.updateAiResponse(
                        id = id,
                        response = response,
                        providerId = settings.aiProvider.name,
                        modelId = settings.aiModelId
                    )
                    Log.d(TAG, "AI analysis completed for recording: $id")
                }.onFailure { e ->
                    Log.e(TAG, "AI analysis failed", e)
                    repository.markError(id, e.message ?: "AI analysis failed")
                    _uiState.update { it.copy(error = e.message) }
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "AI analysis failed", e)
                repository.markError(id, e.message ?: "AI analysis failed")
                _uiState.update { it.copy(error = e.message) }
            } finally {
                _uiState.update { it.copy(processingRecordingId = null) }
            }
        }
    }
    
    // ==================== Utility ====================
    
    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }
    
    fun formatDuration(durationMs: Long): String {
        val totalSeconds = durationMs / 1000
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return if (minutes > 0) {
            "${minutes}m ${seconds}s"
        } else {
            "${seconds}s"
        }
    }
    
    override fun onCleared() {
        super.onCleared()
        repository.release()
    }
}
