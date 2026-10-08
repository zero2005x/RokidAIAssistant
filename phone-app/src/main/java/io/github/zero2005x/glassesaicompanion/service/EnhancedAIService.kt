package io.github.zero2005x.glassesaicompanion.service

import android.content.Context
import android.util.Log
import io.github.zero2005x.glassesaicompanion.ai.provider.ProviderManager
import io.github.zero2005x.glassesaicompanion.data.SettingsRepository
import io.github.zero2005x.glassesaicompanion.data.db.ConversationRepository
import io.github.zero2005x.glassesaicompanion.data.db.MessageRole
import io.github.zero2005x.glassesaicompanion.service.ai.AiServiceProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn

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
            
            // Resolve the AI service BEFORE persisting anything, so a
            // misconfiguration does not leave an orphan user message.
            val aiService = providerManager.getActiveService()
                ?: return Result.failure(Exception("AI service not configured"))
            
            // Save user message
            conversationRepository.addUserMessage(
                conversationId = conversationId,
                content = userMessage,
                imagePath = null  // TODO: If there's an image, need to save to file first
            )
            
            // Choose processing based on whether there's an image
            val response = if (imageData != null) {
                aiService.analyzeImage(imageData, userMessage)
            } else {
                aiService.chat(userMessage)
            }
            
            persistAssistantResponse(conversationId, response, settings.aiModelId)
            
            Log.d(TAG, "Message sent and response received for conversation: $conversationId")
            Result.success(response)
            
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send message", e)
            Result.failure(e)
        }
    }
    
    /**
     * Persist the assistant response and auto-generate a title for fresh
     * conversations. Shared by sendMessage and sendMessageStream.
     */
    private suspend fun persistAssistantResponse(
        conversationId: String,
        response: String,
        modelId: String
    ) {
        conversationRepository.addAssistantMessage(
            conversationId = conversationId,
            content = response,
            modelId = modelId
        )
        
        // Auto-generate title
        val messageCount = conversationRepository.getMessageCount(conversationId)
        if (messageCount <= 2) {
            conversationRepository.autoGenerateTitle(conversationId)
        }
    }
    
    /**
     * Send message and get streaming response.
     * Errors are handled by the catch operator to preserve flow exception
     * transparency; DB/network work runs on Dispatchers.IO.
     */
    fun sendMessageStream(
        conversationId: String,
        userMessage: String
    ): Flow<StreamResult> = flow {
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
        
        persistAssistantResponse(conversationId, response, settings.aiModelId)
        
        // Emit only the final result: emitting the full text as a Chunk as well
        // would make collectors render the response twice.
        emit(StreamResult.Completed(response))
    }.catch { e ->
        if (e is CancellationException) throw e
        Log.e(TAG, "Stream error", e)
        emit(StreamResult.Error(e.message ?: "Unknown error"))
    }.flowOn(Dispatchers.IO)
    
    /**
     * Quick chat (without saving history)
     */
    suspend fun quickChat(message: String): Result<String> {
        return try {
            val aiService = providerManager.getActiveService()
                ?: return Result.failure(Exception("AI service not configured"))
            
            val response = aiService.chat(message)
            Result.success(response)
        } catch (e: CancellationException) {
            throw e
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
        } catch (e: CancellationException) {
            throw e
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
        } catch (e: CancellationException) {
            throw e
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
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create conversation", e)
            Result.failure(e)
        }
    }
    
    /**
     * Get or create conversation.
     * Reuses the most recent conversation when one exists instead of leaking
     * a new empty conversation on every call.
     */
    suspend fun getOrCreateConversation(): String {
        val existing = conversationRepository.getAllConversations()
            .firstOrNull()
            ?.firstOrNull()
        return existing?.id ?: startNewConversation().getOrThrow()
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
