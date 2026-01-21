package com.example.rokidphone.data.db

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * Conversation Database Repository
 * Provides CRUD operations for conversations and messages
 */
class ConversationRepository(context: Context) {
    
    companion object {
        private const val TAG = "ConversationRepository"
        
        @Volatile
        private var instance: ConversationRepository? = null
        
        fun getInstance(context: Context): ConversationRepository {
            return instance ?: synchronized(this) {
                instance ?: ConversationRepository(context.applicationContext).also { instance = it }
            }
        }
    }
    
    private val database = AppDatabase.getInstance(context)
    private val conversationDao = database.conversationDao()
    private val messageDao = database.messageDao()
    
    // ==================== Conversation Operations ====================
    
    /**
     * Get all active conversations
     */
    fun getAllConversations(): Flow<List<Conversation>> {
        return conversationDao.getAllConversations().map { entities ->
            entities.map { it.toConversation() }
        }
    }
    
    /**
     * Get archived conversations
     */
    fun getArchivedConversations(): Flow<List<Conversation>> {
        return conversationDao.getArchivedConversations().map { entities ->
            entities.map { it.toConversation() }
        }
    }
    
    /**
     * Get conversation by ID
     */
    suspend fun getConversationById(id: String): Conversation? {
        return conversationDao.getConversationById(id)?.toConversation()
    }
    
    /**
     * Observe single conversation changes
     */
    fun getConversationByIdFlow(id: String): Flow<Conversation?> {
        return conversationDao.getConversationByIdFlow(id).map { it?.toConversation() }
    }
    
    /**
     * Search conversations
     */
    fun searchConversations(query: String): Flow<List<Conversation>> {
        return conversationDao.searchConversations(query).map { entities ->
            entities.map { it.toConversation() }
        }
    }
    
    /**
     * Create a new conversation
     */
    suspend fun createConversation(
        providerId: String,
        modelId: String,
        title: String = "New Conversation",
        systemPrompt: String = ""
    ): Conversation = withContext(Dispatchers.IO) {
        val id = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        
        val entity = ConversationEntity(
            id = id,
            title = title,
            providerId = providerId,
            modelId = modelId,
            systemPrompt = systemPrompt,
            createdAt = now,
            updatedAt = now
        )
        
        conversationDao.insertConversation(entity)
        Log.d(TAG, "Created conversation: $id")
        
        entity.toConversation()
    }
    
    /**
     * Update conversation title
     */
    suspend fun updateConversationTitle(id: String, title: String) = withContext(Dispatchers.IO) {
        conversationDao.updateTitle(id, title)
        Log.d(TAG, "Updated conversation title: $id -> $title")
    }
    
    /**
     * Archive conversation
     */
    suspend fun archiveConversation(id: String) = withContext(Dispatchers.IO) {
        conversationDao.setArchived(id, true)
        Log.d(TAG, "Archived conversation: $id")
    }
    
    /**
     * Unarchive conversation
     */
    suspend fun unarchiveConversation(id: String) = withContext(Dispatchers.IO) {
        conversationDao.setArchived(id, false)
        Log.d(TAG, "Unarchived conversation: $id")
    }
    
    /**
     * Pin conversation
     */
    suspend fun pinConversation(id: String) = withContext(Dispatchers.IO) {
        conversationDao.setPinned(id, true)
        Log.d(TAG, "Pinned conversation: $id")
    }
    
    /**
     * Unpin conversation
     */
    suspend fun unpinConversation(id: String) = withContext(Dispatchers.IO) {
        conversationDao.setPinned(id, false)
        Log.d(TAG, "Unpinned conversation: $id")
    }
    
    /**
     * Delete conversation
     */
    suspend fun deleteConversation(id: String) = withContext(Dispatchers.IO) {
        conversationDao.deleteConversationById(id)
        Log.d(TAG, "Deleted conversation: $id")
    }
    
    /**
     * Delete all archived conversations
     */
    suspend fun deleteAllArchivedConversations() = withContext(Dispatchers.IO) {
        conversationDao.deleteAllArchived()
        Log.d(TAG, "Deleted all archived conversations")
    }
    
    // ==================== Message Operations ====================
    
    /**
     * Get all messages for a conversation
     */
    fun getMessagesForConversation(conversationId: String): Flow<List<Message>> {
        return messageDao.getMessagesForConversation(conversationId).map { entities ->
            entities.map { it.toMessage() }
        }
    }
    
    /**
     * Synchronously get all messages for a conversation
     */
    suspend fun getMessagesForConversationSync(conversationId: String): List<Message> {
        return messageDao.getMessagesForConversationSync(conversationId).map { it.toMessage() }
    }
    
    /**
     * Get messages with pagination
     */
    suspend fun getMessagesPaged(
        conversationId: String, 
        page: Int, 
        pageSize: Int = 20
    ): List<Message> = withContext(Dispatchers.IO) {
        val offset = page * pageSize
        messageDao.getMessagesPaged(conversationId, pageSize, offset).map { it.toMessage() }
    }
    
    /**
     * Add a message
     */
    suspend fun addMessage(
        conversationId: String,
        role: MessageRole,
        content: String,
        modelId: String? = null,
        hasImage: Boolean = false,
        imagePath: String? = null,
        tokenCount: Int? = null,
        finishReason: String? = null
    ): Message = withContext(Dispatchers.IO) {
        val id = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        
        val entity = MessageEntity(
            id = id,
            conversationId = conversationId,
            role = role.name.lowercase(),
            content = content,
            createdAt = now,
            modelId = modelId,
            hasImage = hasImage,
            imagePath = imagePath,
            tokenCount = tokenCount,
            finishReason = finishReason
        )
        
        messageDao.insertMessage(entity)
        conversationDao.incrementMessageCount(conversationId)
        
        Log.d(TAG, "Added message to conversation $conversationId: ${content.take(50)}...")
        
        entity.toMessage()
    }
    
    /**
     * Add a user message
     */
    suspend fun addUserMessage(
        conversationId: String,
        content: String,
        imagePath: String? = null
    ): Message {
        return addMessage(
            conversationId = conversationId,
            role = MessageRole.USER,
            content = content,
            hasImage = imagePath != null,
            imagePath = imagePath
        )
    }
    
    /**
     * Add an assistant reply
     */
    suspend fun addAssistantMessage(
        conversationId: String,
        content: String,
        modelId: String? = null,
        tokenCount: Int? = null,
        finishReason: String? = null
    ): Message {
        return addMessage(
            conversationId = conversationId,
            role = MessageRole.ASSISTANT,
            content = content,
            modelId = modelId,
            tokenCount = tokenCount,
            finishReason = finishReason
        )
    }
    
    /**
     * Update message content (for streaming updates)
     */
    suspend fun updateMessageContent(
        messageId: String,
        content: String,
        tokenCount: Int? = null,
        finishReason: String? = null
    ) = withContext(Dispatchers.IO) {
        val existing = messageDao.getMessageById(messageId) ?: return@withContext
        
        val updated = existing.copy(
            content = content,
            tokenCount = tokenCount ?: existing.tokenCount,
            finishReason = finishReason ?: existing.finishReason
        )
        
        messageDao.updateMessage(updated)
    }
    
    /**
     * Delete a message
     */
    suspend fun deleteMessage(id: String) = withContext(Dispatchers.IO) {
        messageDao.deleteMessageById(id)
        Log.d(TAG, "Deleted message: $id")
    }
    
    /**
     * Clear all messages in a conversation
     */
    suspend fun clearConversationMessages(conversationId: String) = withContext(Dispatchers.IO) {
        messageDao.deleteMessagesForConversation(conversationId)
        Log.d(TAG, "Cleared messages for conversation: $conversationId")
    }
    
    /**
     * Get message count for a conversation
     */
    suspend fun getMessageCount(conversationId: String): Int = withContext(Dispatchers.IO) {
        messageDao.getMessageCount(conversationId)
    }
    
    /**
     * Get total token count for a conversation
     */
    suspend fun getTotalTokenCount(conversationId: String): Int = withContext(Dispatchers.IO) {
        messageDao.getTotalTokenCount(conversationId) ?: 0
    }
    
    /**
     * Auto-generate conversation title
     * Generated from the first user message
     */
    suspend fun autoGenerateTitle(conversationId: String) = withContext(Dispatchers.IO) {
        val messages = messageDao.getMessagesForConversationSync(conversationId)
        val firstUserMessage = messages.firstOrNull { it.role == "user" }
        
        if (firstUserMessage != null) {
            val title = firstUserMessage.content
                .take(50)
                .replace("\n", " ")
                .trim()
                .let { if (it.length == 50) "$it..." else it }
            
            conversationDao.updateTitle(conversationId, title)
            Log.d(TAG, "Auto-generated title for $conversationId: $title")
        }
    }
}

// ==================== Data Classes ====================

/**
 * Conversation Model
 */
data class Conversation(
    val id: String,
    val title: String,
    val providerId: String,
    val modelId: String,
    val systemPrompt: String,
    val createdAt: Long,
    val updatedAt: Long,
    val messageCount: Int,
    val isArchived: Boolean,
    val isPinned: Boolean
)

/**
 * Message Model
 */
data class Message(
    val id: String,
    val conversationId: String,
    val role: MessageRole,
    val content: String,
    val createdAt: Long,
    val tokenCount: Int?,
    val modelId: String?,
    val hasImage: Boolean,
    val imagePath: String?,
    val finishReason: String?,
    val errorMessage: String?
)

/**
 * Message Role
 */
enum class MessageRole {
    USER,
    ASSISTANT,
    SYSTEM
}

// ==================== Extension Functions ====================

private fun ConversationEntity.toConversation(): Conversation {
    return Conversation(
        id = id,
        title = title,
        providerId = providerId,
        modelId = modelId,
        systemPrompt = systemPrompt,
        createdAt = createdAt,
        updatedAt = updatedAt,
        messageCount = messageCount,
        isArchived = isArchived,
        isPinned = isPinned
    )
}

private fun MessageEntity.toMessage(): Message {
    return Message(
        id = id,
        conversationId = conversationId,
        role = MessageRole.valueOf(role.uppercase()),
        content = content,
        createdAt = createdAt,
        tokenCount = tokenCount,
        modelId = modelId,
        hasImage = hasImage,
        imagePath = imagePath,
        finishReason = finishReason,
        errorMessage = errorMessage
    )
}
