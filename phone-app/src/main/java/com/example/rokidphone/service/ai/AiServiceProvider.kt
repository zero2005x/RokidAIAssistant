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
