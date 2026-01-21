package com.example.rokidphone.ai.provider

import kotlinx.coroutines.flow.Flow

/**
 * AI Provider Unified Interface
 * Based on RikkaHub's Provider design, supporting generic setting types
 */
interface Provider<T : ProviderSetting> {
    
    /**
     * Provider ID
     */
    val providerId: String
    
    /**
     * Provider display name
     */
    val displayName: String
    
    /**
     * List available models
     */
    suspend fun listModels(setting: T): List<Model>
    
    /**
     * Generate text response (non-streaming)
     */
    suspend fun generateText(
        setting: T,
        messages: List<ChatMessage>,
        options: GenerationOptions = GenerationOptions()
    ): GenerationResult
    
    /**
     * Stream text response
     */
    fun streamText(
        setting: T,
        messages: List<ChatMessage>,
        options: GenerationOptions = GenerationOptions()
    ): Flow<MessageChunk>
    
    /**
     * Speech to text (if supported)
     */
    suspend fun transcribe(
        setting: T,
        audioData: ByteArray,
        options: TranscriptionOptions = TranscriptionOptions()
    ): TranscriptionResult
    
    /**
     * Image analysis (if supported)
     */
    suspend fun analyzeImage(
        setting: T,
        imageData: ByteArray,
        prompt: String,
        options: GenerationOptions = GenerationOptions()
    ): GenerationResult
    
    /**
     * Validate if setting is valid
     */
    suspend fun validateSetting(setting: T): ValidationResult
}

/**
 * Model Information
 */
data class Model(
    val id: String,
    val name: String,
    val description: String = "",
    val contextLength: Int = 0,
    val supportsVision: Boolean = false,
    val supportsAudio: Boolean = false,
    val supportsFunctionCalling: Boolean = false
)

/**
 * Chat Message
 */
data class ChatMessage(
    val role: MessageRole,
    val content: String,
    val imageData: ByteArray? = null,
    val name: String? = null
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as ChatMessage
        if (role != other.role) return false
        if (content != other.content) return false
        if (imageData != null) {
            if (other.imageData == null) return false
            if (!imageData.contentEquals(other.imageData)) return false
        } else if (other.imageData != null) return false
        if (name != other.name) return false
        return true
    }
    
    override fun hashCode(): Int {
        var result = role.hashCode()
        result = 31 * result + content.hashCode()
        result = 31 * result + (imageData?.contentHashCode() ?: 0)
        result = 31 * result + (name?.hashCode() ?: 0)
        return result
    }
}

/**
 * Message Role
 */
enum class MessageRole {
    SYSTEM,
    USER,
    ASSISTANT,
    TOOL
}

/**
 * Generation Options
 */
data class GenerationOptions(
    val temperature: Float = 0.7f,
    val maxTokens: Int = 4096,
    val topP: Float = 1.0f,
    val frequencyPenalty: Float = 0.0f,
    val presencePenalty: Float = 0.0f,
    val stopSequences: List<String> = emptyList()
)

/**
 * Generation Result
 */
sealed class GenerationResult {
    data class Success(
        val text: String,
        val finishReason: FinishReason = FinishReason.STOP,
        val usage: TokenUsage? = null
    ) : GenerationResult()
    
    data class Error(
        val message: String,
        val code: String? = null,
        val retryable: Boolean = false
    ) : GenerationResult()
}

/**
 * Message Stream Chunk
 */
data class MessageChunk(
    val text: String,
    val isComplete: Boolean = false,
    val finishReason: FinishReason? = null,
    val error: String? = null
)

/**
 * Finish Reason
 */
enum class FinishReason {
    STOP,
    LENGTH,
    CONTENT_FILTER,
    TOOL_CALLS,
    ERROR
}

/**
 * Token Usage
 */
data class TokenUsage(
    val promptTokens: Int,
    val completionTokens: Int,
    val totalTokens: Int
)

/**
 * Speech to Text Options
 */
data class TranscriptionOptions(
    val language: String = "zh-TW",
    val prompt: String = ""
)

/**
 * Speech to Text Result
 */
sealed class TranscriptionResult {
    data class Success(
        val text: String,
        val language: String? = null,
        val duration: Float? = null
    ) : TranscriptionResult()
    
    data class Error(
        val message: String,
        val code: String? = null
    ) : TranscriptionResult()
}

/**
 * Setting Validation Result
 */
sealed class ValidationResult {
    data object Valid : ValidationResult()
    
    data class Invalid(
        val reason: String,
        val field: String? = null
    ) : ValidationResult()
}
