package com.example.rokidphone.viewmodel

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.rokidphone.ai.provider.ProviderManager
import com.example.rokidphone.data.SettingsRepository
import com.example.rokidphone.data.db.Conversation
import com.example.rokidphone.data.db.ConversationRepository
import com.example.rokidphone.data.db.Message
import com.example.rokidphone.data.db.MessageRole
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

/**
 * Conversation ViewModel
 * Manages conversation history and current conversation state
 */
class ConversationViewModel(application: Application) : AndroidViewModel(application) {
    
    companion object {
        private const val TAG = "ConversationViewModel"
    }
    
    private val conversationRepository = ConversationRepository.getInstance(application)
    private val settingsRepository = SettingsRepository.getInstance(application)
    private val providerManager = ProviderManager.getInstance(application)
    
    // All conversations list
    val conversations: StateFlow<List<Conversation>> = conversationRepository
        .getAllConversations()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )
    
    // Current conversation ID
    private val _currentConversationId = MutableStateFlow<String?>(null)
    val currentConversationId: StateFlow<String?> = _currentConversationId.asStateFlow()
    
    // Current conversation
    val currentConversation: StateFlow<Conversation?> = _currentConversationId
        .flatMapLatest { id ->
            if (id != null) {
                conversationRepository.getConversationByIdFlow(id)
            } else {
                flowOf(null)
            }
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = null
        )
    
    // Message list for current conversation
    val currentMessages: StateFlow<List<Message>> = _currentConversationId
        .flatMapLatest { id ->
            if (id != null) {
                conversationRepository.getMessagesForConversation(id)
            } else {
                flowOf(emptyList())
            }
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )
    
    // UI state
    private val _uiState = MutableStateFlow(ConversationUiState())
    val uiState: StateFlow<ConversationUiState> = _uiState.asStateFlow()
    
    // Input text
    private val _inputText = MutableStateFlow("")
    val inputText: StateFlow<String> = _inputText.asStateFlow()
    
    /**
     * Update input text
     */
    fun updateInputText(text: String) {
        _inputText.value = text
    }
    
    /**
     * Create a new conversation
     */
    fun createNewConversation() {
        viewModelScope.launch {
            try {
                val settings = settingsRepository.getSettings()
                val conversation = conversationRepository.createConversation(
                    providerId = settings.aiProvider.name,
                    modelId = settings.aiModelId,
                    title = "New Conversation",
                    systemPrompt = settings.systemPrompt
                )
                
                _currentConversationId.value = conversation.id
                Log.d(TAG, "Created new conversation: ${conversation.id}")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to create conversation", e)
                _uiState.update { it.copy(error = "Failed to create conversation: ${e.message}") }
            }
        }
    }
    
    /**
     * Select a conversation
     */
    fun selectConversation(conversation: Conversation) {
        _currentConversationId.value = conversation.id
        Log.d(TAG, "Selected conversation: ${conversation.id}")
    }
    
    /**
     * Select a conversation (by ID)
     */
    fun selectConversation(conversationId: String) {
        _currentConversationId.value = conversationId
        Log.d(TAG, "Selected conversation: $conversationId")
    }
    
    /**
     * Send a message
     */
    fun sendMessage() {
        val text = _inputText.value.trim()
        if (text.isBlank()) return
        
        val conversationId = _currentConversationId.value
        if (conversationId == null) {
            // If no current conversation, create one first
            viewModelScope.launch {
                createNewConversationAndSendMessage(text)
            }
            return
        }
        
        viewModelScope.launch {
            sendMessageToConversation(conversationId, text)
        }
    }
    
    /**
     * Create a new conversation and send a message
     */
    private suspend fun createNewConversationAndSendMessage(text: String) {
        try {
            val settings = settingsRepository.getSettings()
            val conversation = conversationRepository.createConversation(
                providerId = settings.aiProvider.name,
                modelId = settings.aiModelId,
                title = text.take(50),
                systemPrompt = settings.systemPrompt
            )
            
            _currentConversationId.value = conversation.id
            sendMessageToConversation(conversation.id, text)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create conversation and send message", e)
            _uiState.update { it.copy(error = "Failed to create conversation: ${e.message}") }
        }
    }
    
    /**
     * Send a message to a specific conversation
     */
    private suspend fun sendMessageToConversation(conversationId: String, text: String) {
        try {
            // Clear input
            _inputText.value = ""
            
            // Set loading state
            _uiState.update { it.copy(isLoading = true, error = null) }
            
            // Save user message
            conversationRepository.addUserMessage(conversationId, text)
            
            // Get AI service
            val aiService = providerManager.getActiveService()
            if (aiService == null) {
                _uiState.update { it.copy(isLoading = false, error = "AI service not configured") }
                return
            }
            
            // Get AI response
            val response = aiService.chat(text)
            
            // Save AI response
            val settings = settingsRepository.getSettings()
            conversationRepository.addAssistantMessage(
                conversationId = conversationId,
                content = response,
                modelId = settings.aiModelId
            )
            
            // Auto-generate title (if this is the first message)
            val messageCount = conversationRepository.getMessageCount(conversationId)
            if (messageCount <= 2) {
                conversationRepository.autoGenerateTitle(conversationId)
            }
            
            _uiState.update { it.copy(isLoading = false) }
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send message", e)
            _uiState.update { 
                it.copy(
                    isLoading = false, 
                    error = "Failed to send: ${e.message}"
                ) 
            }
        }
    }
    
    /**
     * Clear messages in the current conversation
     */
    fun clearCurrentConversation() {
        val conversationId = _currentConversationId.value ?: return
        
        viewModelScope.launch {
            try {
                conversationRepository.clearConversationMessages(conversationId)
                Log.d(TAG, "Cleared messages for conversation: $conversationId")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to clear messages", e)
                _uiState.update { it.copy(error = "Failed to clear messages: ${e.message}") }
            }
        }
    }
    
    /**
     * Delete a conversation
     */
    fun deleteConversation(conversation: Conversation) {
        viewModelScope.launch {
            try {
                conversationRepository.deleteConversation(conversation.id)
                
                // If the deleted conversation is the current one, clear selection
                if (_currentConversationId.value == conversation.id) {
                    _currentConversationId.value = null
                }
                
                Log.d(TAG, "Deleted conversation: ${conversation.id}")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to delete conversation", e)
                _uiState.update { it.copy(error = "Failed to delete conversation: ${e.message}") }
            }
        }
    }
    
    /**
     * Archive a conversation
     */
    fun archiveConversation(conversation: Conversation) {
        viewModelScope.launch {
            try {
                if (conversation.isArchived) {
                    conversationRepository.unarchiveConversation(conversation.id)
                } else {
                    conversationRepository.archiveConversation(conversation.id)
                }
                Log.d(TAG, "Toggled archive for conversation: ${conversation.id}")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to archive conversation", e)
                _uiState.update { it.copy(error = "Operation failed: ${e.message}") }
            }
        }
    }
    
    /**
     * Pin a conversation
     */
    fun pinConversation(conversation: Conversation) {
        viewModelScope.launch {
            try {
                if (conversation.isPinned) {
                    conversationRepository.unpinConversation(conversation.id)
                } else {
                    conversationRepository.pinConversation(conversation.id)
                }
                Log.d(TAG, "Toggled pin for conversation: ${conversation.id}")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to pin conversation", e)
                _uiState.update { it.copy(error = "Operation failed: ${e.message}") }
            }
        }
    }
    
    /**
     * Clear error message
     */
    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }
    
    /**
     * Close the current conversation
     */
    fun closeCurrentConversation() {
        _currentConversationId.value = null
    }
}

/**
 * Conversation UI State
 */
data class ConversationUiState(
    val isLoading: Boolean = false,
    val error: String? = null
)
