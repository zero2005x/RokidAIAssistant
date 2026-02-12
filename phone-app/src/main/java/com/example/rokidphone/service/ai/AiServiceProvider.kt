package com.example.rokidphone.service.ai

import com.example.rokidphone.data.AiProvider
import com.example.rokidphone.service.SpeechResult

/**
 * AI Service Unified Interface
 * All AI service providers must implement this interface
 */
interface AiServiceProvider {
    
    /**
     * Provider identifier
     */
    val provider: AiProvider
    
    /**
     * Speech to text
     * @param pcmAudioData PCM format audio data
     * @param languageCode Language code for speech recognition (e.g. "zh-TW", "en-US", "ko-KR")
     * @return Speech recognition result
     */
    suspend fun transcribe(pcmAudioData: ByteArray, languageCode: String = "zh-TW"): SpeechResult
    
    /**
     * Transcribe pre-encoded audio file (e.g. M4A, MP3, OGG)
     * Unlike transcribe() which expects raw PCM data, this accepts encoded audio with its MIME type.
     * Default implementation falls back to transcribe() (treating data as PCM).
     * @param audioData Encoded audio data
     * @param mimeType MIME type of the audio (e.g. "audio/mp4", "audio/aac")
     * @param languageCode Language code for speech recognition
     * @return Speech recognition result
     */
    suspend fun transcribeAudioFile(audioData: ByteArray, mimeType: String, languageCode: String = "zh-TW"): SpeechResult {
        return transcribe(audioData, languageCode)
    }
    
    /**
     * Text chat
     * @param userMessage User message
     * @return AI response
     */
    suspend fun chat(userMessage: String): String
    
    /**
     * Image understanding
     * @param imageData Image data (JPEG/PNG)
     * @param prompt User prompt
     * @return AI response
     */
    suspend fun analyzeImage(imageData: ByteArray, prompt: String = "Please describe this image"): String
    
    /**
     * Clear conversation history
     */
    fun clearHistory()
}
