package com.example.rokidphone.service.stt

import android.util.Log
import com.example.rokidphone.data.ApiSettings

/**
 * STT Service Factory
 * Creates appropriate STT service based on provider selection and credentials
 */
object SttServiceFactory {
    
    private const val TAG = "SttServiceFactory"
    
    /**
     * Create STT service based on selected provider and credentials
     * 
     * @param sttCredentials STT-specific credentials for dedicated providers
     * @param apiSettings Main API settings (for providers that share API keys with chat)
     * @return SttService instance or null if credentials are missing
     */
    fun createService(
        sttCredentials: SttCredentials,
        apiSettings: ApiSettings
    ): SttService? {
        val provider = sttCredentials.getSelectedProvider()
        
        Log.d(TAG, "Creating STT service for provider: $provider")
        
        return when (provider) {
            // Providers that use main API settings keys
            SttProvider.GEMINI -> {
                val apiKey = apiSettings.geminiApiKey
                if (apiKey.isBlank()) {
                    Log.w(TAG, "Gemini API key not configured")
                    null
                } else {
                    GeminiSttAdapter(apiKey, apiSettings.aiModelId)
                }
            }
            
            SttProvider.OPENAI_WHISPER -> {
                val apiKey = apiSettings.openaiApiKey
                if (apiKey.isBlank()) {
                    Log.w(TAG, "OpenAI API key not configured")
                    null
                } else {
                    OpenAiWhisperSttAdapter(apiKey)
                }
            }
            
            SttProvider.GROQ_WHISPER -> {
                val apiKey = apiSettings.groqApiKey
                if (apiKey.isBlank()) {
                    Log.w(TAG, "Groq API key not configured")
                    null
                } else {
                    GroqWhisperSttAdapter(apiKey)
                }
            }
            
            // Dedicated STT providers with their own credentials
            SttProvider.DEEPGRAM -> {
                if (sttCredentials.deepgramApiKey.isBlank()) {
                    Log.w(TAG, "Deepgram API key not configured")
                    null
                } else {
                    DeepgramSttService(sttCredentials.deepgramApiKey)
                }
            }
            
            SttProvider.ASSEMBLYAI -> {
                if (sttCredentials.assemblyaiApiKey.isBlank()) {
                    Log.w(TAG, "AssemblyAI API key not configured")
                    null
                } else {
                    AssemblyAiSttService(sttCredentials.assemblyaiApiKey)
                }
            }
            
            SttProvider.AZURE_SPEECH -> {
                if (sttCredentials.azureSpeechKey.isBlank() || sttCredentials.azureSpeechRegion.isBlank()) {
                    Log.w(TAG, "Azure Speech credentials not configured")
                    null
                } else {
                    AzureSpeechSttService(
                        subscriptionKey = sttCredentials.azureSpeechKey,
                        region = sttCredentials.azureSpeechRegion
                    )
                }
            }
            
            SttProvider.IFLYTEK -> {
                if (sttCredentials.iflytekAppId.isBlank() || 
                    sttCredentials.iflytekApiKey.isBlank() || 
                    sttCredentials.iflytekApiSecret.isBlank()) {
                    Log.w(TAG, "iFLYTEK credentials not configured")
                    null
                } else {
                    IflytekSttService(
                        appId = sttCredentials.iflytekAppId,
                        apiKey = sttCredentials.iflytekApiKey,
                        apiSecret = sttCredentials.iflytekApiSecret
                    )
                }
            }
            
            // Providers not yet implemented - return null with log
            SttProvider.GOOGLE_CLOUD_STT -> {
                Log.w(TAG, "Google Cloud STT not yet implemented - requires SDK integration")
                null
            }
            
            SttProvider.AWS_TRANSCRIBE -> {
                Log.w(TAG, "AWS Transcribe not yet implemented - requires SDK integration")
                null
            }
            
            SttProvider.IBM_WATSON -> {
                Log.w(TAG, "IBM Watson STT not yet implemented")
                null
            }
            
            SttProvider.HUAWEI_SIS -> {
                Log.w(TAG, "Huawei SIS not yet implemented")
                null
            }
            
            SttProvider.VOLCENGINE -> {
                Log.w(TAG, "Volcengine ASR not yet implemented")
                null
            }
        }
    }
    
    /**
     * Get list of fully implemented providers
     */
    fun getImplementedProviders(): List<SttProvider> = listOf(
        SttProvider.GEMINI,
        SttProvider.OPENAI_WHISPER,
        SttProvider.GROQ_WHISPER,
        SttProvider.DEEPGRAM,
        SttProvider.ASSEMBLYAI,
        SttProvider.AZURE_SPEECH,
        SttProvider.IFLYTEK
    )
    
    /**
     * Get providers that are planned but not yet implemented
     */
    fun getPlannedProviders(): List<SttProvider> = listOf(
        SttProvider.GOOGLE_CLOUD_STT,
        SttProvider.AWS_TRANSCRIBE,
        SttProvider.IBM_WATSON,
        SttProvider.HUAWEI_SIS,
        SttProvider.VOLCENGINE
    )
}

/**
 * Adapter to use existing GeminiService as SttService
 */
private class GeminiSttAdapter(
    private val apiKey: String,
    private val modelId: String
) : BaseSttService() {
    
    private val geminiService by lazy {
        com.example.rokidphone.service.ai.GeminiService(apiKey, modelId)
    }
    
    override val provider = SttProvider.GEMINI
    
    override suspend fun transcribe(audioData: ByteArray, languageCode: String): com.example.rokidphone.service.SpeechResult {
        return geminiService.transcribe(audioData)
    }
    
    override suspend fun validateCredentials(): SttValidationResult {
        // Try a simple API call to validate
        return try {
            val result = geminiService.chat("Hello")
            if (result.isNotEmpty()) {
                SttValidationResult.Valid
            } else {
                SttValidationResult.Invalid(SttValidationError.UNKNOWN)
            }
        } catch (e: Exception) {
            SttValidationResult.Invalid(SttValidationError.INVALID_CREDENTIALS)
        }
    }
}

/**
 * Adapter to use existing OpenAiService Whisper as SttService
 */
private class OpenAiWhisperSttAdapter(
    private val apiKey: String
) : BaseSttService() {
    
    private val openAiService by lazy {
        com.example.rokidphone.service.ai.OpenAiService(apiKey)
    }
    
    override val provider = SttProvider.OPENAI_WHISPER
    
    override suspend fun transcribe(audioData: ByteArray, languageCode: String): com.example.rokidphone.service.SpeechResult {
        return openAiService.transcribe(audioData)
    }
    
    override suspend fun validateCredentials(): SttValidationResult {
        return try {
            val result = openAiService.chat("Hello")
            if (result.isNotEmpty()) {
                SttValidationResult.Valid
            } else {
                SttValidationResult.Invalid(SttValidationError.UNKNOWN)
            }
        } catch (e: Exception) {
            SttValidationResult.Invalid(SttValidationError.INVALID_CREDENTIALS)
        }
    }
}

/**
 * Adapter to use GroqService Whisper as SttService
 */
private class GroqWhisperSttAdapter(
    private val apiKey: String
) : BaseSttService() {
    
    private val groqService by lazy {
        com.example.rokidphone.service.ai.GroqService(apiKey)
    }
    
    override val provider = SttProvider.GROQ_WHISPER
    
    override suspend fun transcribe(audioData: ByteArray, languageCode: String): com.example.rokidphone.service.SpeechResult {
        return groqService.transcribe(audioData)
    }
    
    override suspend fun validateCredentials(): SttValidationResult {
        return try {
            val result = groqService.chat("Hello")
            if (result.isNotEmpty()) {
                SttValidationResult.Valid
            } else {
                SttValidationResult.Invalid(SttValidationError.UNKNOWN)
            }
        } catch (e: Exception) {
            SttValidationResult.Invalid(SttValidationError.INVALID_CREDENTIALS)
        }
    }
}
