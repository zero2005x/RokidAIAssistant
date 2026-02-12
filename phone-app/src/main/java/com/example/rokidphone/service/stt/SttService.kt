package com.example.rokidphone.service.stt

import com.example.rokidphone.service.SpeechResult

/**
 * Speech-to-Text Service Interface
 * All STT providers must implement this interface
 */
interface SttService {
    
    /**
     * Provider identifier
     */
    val provider: SttProvider
    
    /**
     * Transcribe audio to text
     * @param audioData Audio data (PCM 16-bit, 16kHz, mono)
     * @param languageCode Language code (e.g., "zh-CN", "en-US")
     * @return Transcription result
     */
    suspend fun transcribe(audioData: ByteArray, languageCode: String = "zh-CN"): SpeechResult
    
    /**
     * Transcribe pre-encoded audio file (e.g. M4A, MP3, OGG)
     * Unlike transcribe() which expects raw PCM data, this accepts encoded audio with its MIME type.
     * Default implementation falls back to transcribe() (treating data as PCM).
     * @param audioData Encoded audio data
     * @param mimeType MIME type of the audio (e.g. "audio/mp4", "audio/aac")
     * @param languageCode Language code (e.g., "zh-CN", "en-US")
     * @return Transcription result
     */
    suspend fun transcribeAudioFile(audioData: ByteArray, mimeType: String, languageCode: String = "zh-CN"): SpeechResult {
        return transcribe(audioData, languageCode)
    }
    
    /**
     * Validate credentials by making a minimal API call
     * @return ValidationResult indicating success or error details
     */
    suspend fun validateCredentials(): SttValidationResult
    
    /**
     * Check if provider supports real-time streaming
     */
    fun supportsStreaming(): Boolean = provider.supportsStreaming
    
    /**
     * Release resources
     */
    fun release() {}
}

/**
 * Result of credential validation
 */
sealed class SttValidationResult {
    data object Valid : SttValidationResult()
    data class Invalid(val error: SttValidationError) : SttValidationResult()
}

/**
 * Types of validation errors
 */
enum class SttValidationError {
    INVALID_CREDENTIALS,        // 401/403 - Invalid API key or unauthorized
    WRONG_ENDPOINT_OR_REGION,   // 404 - Wrong endpoint or region
    RATE_LIMITED,               // 429 - Rate limited or quota exceeded  
    PROVIDER_UNAVAILABLE,       // 5xx - Provider temporarily unavailable
    NETWORK_ERROR,              // Network connectivity issue
    TIMEOUT,                    // Request timeout
    UNKNOWN                     // Unknown error
}

/**
 * Maps HTTP status codes to validation errors
 */
fun mapHttpStatusToError(statusCode: Int): SttValidationError {
    return when (statusCode) {
        401, 403 -> SttValidationError.INVALID_CREDENTIALS
        404 -> SttValidationError.WRONG_ENDPOINT_OR_REGION
        429 -> SttValidationError.RATE_LIMITED
        in 500..599 -> SttValidationError.PROVIDER_UNAVAILABLE
        else -> SttValidationError.UNKNOWN
    }
}
