package com.example.rokidphone.service

import android.content.Context
import android.util.Log
import com.example.rokidphone.ai.provider.ProviderManager
import com.example.rokidphone.data.SettingsRepository
import com.example.rokidphone.data.db.ConversationRepository
import com.example.rokidphone.data.db.MessageRole
import com.example.rokidphone.service.ai.AiServiceProvider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Enhanced AI Service Integration Layer
 * Integrates ProviderManager, ConversationRepository and AI services
 * Inspired by RikkaHub's ChatService design
 */
class EnhancedAIService(
    private val context: Context
) {
    companion object {
        private const val TAG = "EnhancedAIService"
        
        @Volatile
        private var instance: EnhancedAIService? = null
        
        fun getInstance(context: Context): EnhancedAIService {
            return instance ?: synchronized(this) {
                instance ?: EnhancedAIService(context.applicationContext).also { instance = it }
            }
        }
    }
    
    private val providerManager = ProviderManager.getInstance(context)
    private val conversationRepository = ConversationRepository.getInstance(context)
    private val settingsRepository = SettingsRepository.getInstance(context)
    
    /**
     * Send message and get response (auto-saves to conversation history)
     */
    suspend fun sendMessage(
        conversationId: String,
        userMessage: String,
        imageData: ByteArray? = null
    ): Result<String> {
        return try {
            val settings = settingsRepository.getSettings()
            
            // Save user message
            conversationRepository.addUserMessage(
                conversationId = conversationId,
                content = userMessage,
                imagePath = null  // TODO: If there's an image, need to save to file first
            )
            
            // Get AI service
            val aiService = providerManager.getActiveService()
                ?: return Result.failure(Exception("AI service not configured"))
            
            // Choose processing based on whether there's an image
            val response = if (imageData != null) {
                aiService.analyzeImage(imageData, userMessage)
            } else {
                aiService.chat(userMessage)
            }
            
            // Save AI response
            conversationRepository.addAssistantMessage(
                conversationId = conversationId,
                content = response,
                modelId = settings.aiModelId
            )
            
            // Auto-generate title
            val messageCount = conversationRepository.getMessageCount(conversationId)
            if (messageCount <= 2) {
                conversationRepository.autoGenerateTitle(conversationId)
            }
            
            Log.d(TAG, "Message sent and response received for conversation: $conversationId")
            Result.success(response)
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send message", e)
            Result.failure(e)
        }
    }
    
    /**
     * Send message and get streaming response
     */
    fun sendMessageStream(
        conversationId: String,
        userMessage: String
    ): Flow<StreamResult> = flow {
        try {
            val settings = settingsRepository.getSettings()
            
            // Save user message
            conversationRepository.addUserMessage(
                conversationId = conversationId,
                content = userMessage
            )
            
            emit(StreamResult.Started)
            
            // Get AI service
            val aiService = providerManager.getActiveService()
            if (aiService == null) {
                emit(StreamResult.Error("AI service not configured"))
                return@flow
            }
            
            // Currently using non-streaming approach (TODO: Implement true streaming)
            val response = aiService.chat(userMessage)
            
            emit(StreamResult.Chunk(response))
            
            // Save AI response
            conversationRepository.addAssistantMessage(
                conversationId = conversationId,
                content = response,
                modelId = settings.aiModelId
            )
            
            // Auto-generate title
            val messageCount = conversationRepository.getMessageCount(conversationId)
            if (messageCount <= 2) {
                conversationRepository.autoGenerateTitle(conversationId)
            }
            
            emit(StreamResult.Completed(response))
            
        } catch (e: Exception) {
            Log.e(TAG, "Stream error", e)
            emit(StreamResult.Error(e.message ?: "Unknown error"))
        }
    }
    
    /**
     * Quick chat (without saving history)
     */
    suspend fun quickChat(message: String): Result<String> {
        return try {
            val aiService = providerManager.getActiveService()
                ?: return Result.failure(Exception("AI service not configured"))
            
            val response = aiService.chat(message)
            Result.success(response)
        } catch (e: Exception) {
            Log.e(TAG, "Quick chat failed", e)
            Result.failure(e)
        }
    }
    
    /**
     * Image analysis (without saving history)
     */
    suspend fun analyzeImage(
        imageData: ByteArray,
        prompt: String = "Please describe this image"
    ): Result<String> {
        return try {
            val aiService = providerManager.getActiveService()
                ?: return Result.failure(Exception("AI service not configured"))
            
            val response = aiService.analyzeImage(imageData, prompt)
            Result.success(response)
        } catch (e: Exception) {
            Log.e(TAG, "Image analysis failed", e)
            Result.failure(e)
        }
    }
    
    /**
     * Speech to text transcription
     */
    suspend fun transcribe(audioData: ByteArray): Result<String> {
        return try {
            val aiService = providerManager.getActiveService()
                ?: return Result.failure(Exception("AI service not configured"))
            
            when (val result = aiService.transcribe(audioData)) {
                is SpeechResult.Success -> Result.success(result.text)
                is SpeechResult.Error -> Result.failure(Exception(result.message))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Transcription failed", e)
            Result.failure(e)
        }
    }
    
    /**
     * Start new conversation
     */
    suspend fun startNewConversation(
        title: String = "New Conversation"
    ): Result<String> {
        return try {
            val settings = settingsRepository.getSettings()
            val conversation = conversationRepository.createConversation(
                providerId = settings.aiProvider.name,
                modelId = settings.aiModelId,
                title = title,
                systemPrompt = settings.systemPrompt
            )
            Result.success(conversation.id)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create conversation", e)
            Result.failure(e)
        }
    }
    
    /**
     * Get or create conversation
     */
    suspend fun getOrCreateConversation(): String {
        val conversations = conversationRepository.getAllConversations()
        // Simplified handling, should actually get recent conversation or create new one
        return startNewConversation().getOrThrow()
    }
    
    /**
     * Clear AI service cache
     */
    fun invalidateCache() {
        providerManager.invalidateCache()
    }
}

/**
 * Stream result
 */
sealed class StreamResult {
    data object Started : StreamResult()
    data class Chunk(val text: String) : StreamResult()
    data class Completed(val fullText: String) : StreamResult()
    data class Error(val message: String) : StreamResult()
}
