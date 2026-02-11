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
            
            // New providers - Now implemented
            SttProvider.ALIBABA_ASR -> {
                if (sttCredentials.aliyunAccessKeyId.isBlank() || 
                    sttCredentials.aliyunAccessKeySecret.isBlank() ||
                    sttCredentials.aliyunAppKey.isBlank()) {
                    Log.w(TAG, "Aliyun ASR credentials not configured")
                    null
                } else {
                    AliyunSttService(
                        accessKeyId = sttCredentials.aliyunAccessKeyId,
                        accessKeySecret = sttCredentials.aliyunAccessKeySecret,
                        appKey = sttCredentials.aliyunAppKey
                    )
                }
            }
            
            SttProvider.TENCENT_ASR -> {
                if (sttCredentials.tencentSecretId.isBlank() || 
                    sttCredentials.tencentSecretKey.isBlank() ||
                    sttCredentials.tencentAppId.isBlank()) {
                    Log.w(TAG, "Tencent ASR credentials not configured")
                    null
                } else {
                    TencentSttService(
                        secretId = sttCredentials.tencentSecretId,
                        secretKey = sttCredentials.tencentSecretKey,
                        appId = sttCredentials.tencentAppId,
                        engineModelType = sttCredentials.tencentEngineModelType
                    )
                }
            }
            
            SttProvider.BAIDU_ASR -> {
                if (sttCredentials.baiduAsrApiKey.isBlank() || 
                    sttCredentials.baiduAsrSecretKey.isBlank()) {
                    Log.w(TAG, "Baidu ASR credentials not configured")
                    null
                } else {
                    BaiduSttService(
                        apiKey = sttCredentials.baiduAsrApiKey,
                        secretKey = sttCredentials.baiduAsrSecretKey
                    )
                }
            }
            
            // REV_AI, SPEECHMATICS, OTTER_AI are now implemented - see Tier 3 section below
            
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
            
            // Providers now implemented
            SttProvider.GOOGLE_CLOUD_STT -> {
                if (sttCredentials.gcpProjectId.isBlank()) {
                    Log.w(TAG, "Google Cloud STT project ID not configured")
                    null
                } else {
                    GoogleCloudSttService(
                        projectId = sttCredentials.gcpProjectId,
                        apiKey = sttCredentials.gcpApiKey,
                        serviceAccountJson = sttCredentials.gcpServiceAccountJson,
                        useServiceAccount = sttCredentials.gcpUseServiceAccount
                    )
                }
            }
            
            SttProvider.AWS_TRANSCRIBE -> {
                if (sttCredentials.awsAccessKeyId.isBlank() || 
                    sttCredentials.awsSecretAccessKey.isBlank()) {
                    Log.w(TAG, "AWS Transcribe credentials not configured")
                    null
                } else {
                    AwsTranscribeSttService(
                        accessKeyId = sttCredentials.awsAccessKeyId,
                        secretAccessKey = sttCredentials.awsSecretAccessKey,
                        region = sttCredentials.awsRegion
                    )
                }
            }
            
            // Tier 2 providers - Now implemented
            SttProvider.IBM_WATSON -> {
                if (sttCredentials.ibmApiKey.isBlank() || 
                    sttCredentials.ibmServiceUrl.isBlank()) {
                    Log.w(TAG, "IBM Watson credentials not configured")
                    null
                } else {
                    IbmWatsonSttService(
                        apiKey = sttCredentials.ibmApiKey,
                        serviceUrl = sttCredentials.ibmServiceUrl,
                        model = "en-US_BroadbandModel",
                        interimResults = false,
                        smartFormatting = true
                    )
                }
            }
            
            SttProvider.HUAWEI_SIS -> {
                if (sttCredentials.huaweiAk.isBlank() || 
                    sttCredentials.huaweiSk.isBlank() ||
                    sttCredentials.huaweiProjectId.isBlank()) {
                    Log.w(TAG, "Huawei SIS credentials not configured")
                    null
                } else {
                    HuaweiSisSttService(
                        accessKey = sttCredentials.huaweiAk,
                        secretKey = sttCredentials.huaweiSk,
                        region = sttCredentials.huaweiRegion,
                        projectId = sttCredentials.huaweiProjectId,
                        audioFormat = "pcm16k16bit",
                        property = "chinese_16k_common"
                    )
                }
            }
            
            SttProvider.VOLCENGINE -> {
                if (sttCredentials.volcengineAppId.isBlank() || 
                    sttCredentials.volcengineAk.isBlank() ||
                    sttCredentials.volcangineSk.isBlank()) {
                    Log.w(TAG, "Volcengine credentials not configured")
                    null
                } else {
                    VolcengineSttService(
                        appId = sttCredentials.volcengineAppId,
                        accessKeyId = sttCredentials.volcengineAk,
                        accessKeySecret = sttCredentials.volcangineSk,
                        cluster = "volcengine_streaming_common"
                    )
                }
            }
            
            // Tier 3 providers - Now implemented
            SttProvider.REV_AI -> {
                if (sttCredentials.revaiAccessToken.isBlank()) {
                    Log.w(TAG, "Rev.ai access token not configured")
                    null
                } else {
                    RevAiSttService(
                        accessToken = sttCredentials.revaiAccessToken,
                        language = "en",
                        filterProfanity = false
                    )
                }
            }
            
            SttProvider.SPEECHMATICS -> {
                if (sttCredentials.speechmaticsApiKey.isBlank()) {
                    Log.w(TAG, "Speechmatics API key not configured")
                    null
                } else {
                    SpeechmaticsSttService(
                        apiKey = sttCredentials.speechmaticsApiKey,
                        language = "en",
                        enableDiarization = false,
                        operatingPoint = "standard"
                    )
                }
            }
            
            SttProvider.OTTER_AI -> {
                if (sttCredentials.otteraiApiKey.isBlank()) {
                    Log.w(TAG, "Otter.ai API key not configured")
                    null
                } else {
                    OtterAiSttService(
                        apiKey = sttCredentials.otteraiApiKey
                    )
                }
            }
        }
    }
    
    /**
     * Get providers that are fully implemented and available
     */
    fun getImplementedProviders(): List<SttProvider> = listOf(
        // Existing providers
        SttProvider.GEMINI,
        SttProvider.OPENAI_WHISPER,
        SttProvider.GROQ_WHISPER,
        SttProvider.DEEPGRAM,
        SttProvider.ASSEMBLYAI,
        SttProvider.AZURE_SPEECH,
        SttProvider.IFLYTEK,
        // Tier 1 providers
        SttProvider.GOOGLE_CLOUD_STT,
        SttProvider.AWS_TRANSCRIBE,
        SttProvider.ALIBABA_ASR,
        SttProvider.TENCENT_ASR,
        SttProvider.BAIDU_ASR,
        // Tier 2 providers
        SttProvider.IBM_WATSON,
        SttProvider.HUAWEI_SIS,
        // Tier 3 providers
        SttProvider.VOLCENGINE,
        SttProvider.REV_AI,
        SttProvider.SPEECHMATICS,
        SttProvider.OTTER_AI
    )
    
    /**
     * Get providers that are planned but not yet implemented
     */
    fun getPlannedProviders(): List<SttProvider> = listOf(
        // All providers are now implemented!
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
        return geminiService.transcribe(audioData, languageCode)
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
        return openAiService.transcribe(audioData, languageCode)
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
        return groqService.transcribe(audioData, languageCode)
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
