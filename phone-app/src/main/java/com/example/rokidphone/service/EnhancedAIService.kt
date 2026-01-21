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
 * 增強的 AI 服務整合層
 * 整合 ProviderManager、ConversationRepository 和 AI 服務
 * 參考 RikkaHub 的 ChatService 設計
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
     * 發送訊息並取得回覆 (自動保存到對話歷史)
     */
    suspend fun sendMessage(
        conversationId: String,
        userMessage: String,
        imageData: ByteArray? = null
    ): Result<String> {
        return try {
            val settings = settingsRepository.getSettings()
            
            // 保存使用者訊息
            conversationRepository.addUserMessage(
                conversationId = conversationId,
                content = userMessage,
                imagePath = null  // TODO: 如果有圖片，需要先保存到檔案
            )
            
            // 取得 AI 服務
            val aiService = providerManager.getActiveService()
                ?: return Result.failure(Exception("AI 服務未設定"))
            
            // 根據是否有圖片選擇不同的處理方式
            val response = if (imageData != null) {
                aiService.analyzeImage(imageData, userMessage)
            } else {
                aiService.chat(userMessage)
            }
            
            // 保存 AI 回覆
            conversationRepository.addAssistantMessage(
                conversationId = conversationId,
                content = response,
                modelId = settings.aiModelId
            )
            
            // 自動生成標題
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
     * 發送訊息並取得串流回覆
     */
    fun sendMessageStream(
        conversationId: String,
        userMessage: String
    ): Flow<StreamResult> = flow {
        try {
            val settings = settingsRepository.getSettings()
            
            // 保存使用者訊息
            conversationRepository.addUserMessage(
                conversationId = conversationId,
                content = userMessage
            )
            
            emit(StreamResult.Started)
            
            // 取得 AI 服務
            val aiService = providerManager.getActiveService()
            if (aiService == null) {
                emit(StreamResult.Error("AI 服務未設定"))
                return@flow
            }
            
            // 目前先使用非串流方式 (TODO: 實現真正的串流)
            val response = aiService.chat(userMessage)
            
            emit(StreamResult.Chunk(response))
            
            // 保存 AI 回覆
            conversationRepository.addAssistantMessage(
                conversationId = conversationId,
                content = response,
                modelId = settings.aiModelId
            )
            
            // 自動生成標題
            val messageCount = conversationRepository.getMessageCount(conversationId)
            if (messageCount <= 2) {
                conversationRepository.autoGenerateTitle(conversationId)
            }
            
            emit(StreamResult.Completed(response))
            
        } catch (e: Exception) {
            Log.e(TAG, "Stream error", e)
            emit(StreamResult.Error(e.message ?: "未知錯誤"))
        }
    }
    
    /**
     * 快速對話 (不保存歷史)
     */
    suspend fun quickChat(message: String): Result<String> {
        return try {
            val aiService = providerManager.getActiveService()
                ?: return Result.failure(Exception("AI 服務未設定"))
            
            val response = aiService.chat(message)
            Result.success(response)
        } catch (e: Exception) {
            Log.e(TAG, "Quick chat failed", e)
            Result.failure(e)
        }
    }
    
    /**
     * 圖片分析 (不保存歷史)
     */
    suspend fun analyzeImage(
        imageData: ByteArray,
        prompt: String = "請描述這張圖片"
    ): Result<String> {
        return try {
            val aiService = providerManager.getActiveService()
                ?: return Result.failure(Exception("AI 服務未設定"))
            
            val response = aiService.analyzeImage(imageData, prompt)
            Result.success(response)
        } catch (e: Exception) {
            Log.e(TAG, "Image analysis failed", e)
            Result.failure(e)
        }
    }
    
    /**
     * 語音轉文字
     */
    suspend fun transcribe(audioData: ByteArray): Result<String> {
        return try {
            val aiService = providerManager.getActiveService()
                ?: return Result.failure(Exception("AI 服務未設定"))
            
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
     * 開始新對話
     */
    suspend fun startNewConversation(
        title: String = "新對話"
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
     * 取得或建立對話
     */
    suspend fun getOrCreateConversation(): String {
        val conversations = conversationRepository.getAllConversations()
        // 這裡簡化處理，實際應該取得最近的對話或建立新對話
        return startNewConversation().getOrThrow()
    }
    
    /**
     * 清除 AI 服務快取
     */
    fun invalidateCache() {
        providerManager.invalidateCache()
    }
}

/**
 * 串流結果
 */
sealed class StreamResult {
    data object Started : StreamResult()
    data class Chunk(val text: String) : StreamResult()
    data class Completed(val fullText: String) : StreamResult()
    data class Error(val message: String) : StreamResult()
}
