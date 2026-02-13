package com.example.rokidphone.service.stt

import kotlinx.serialization.Serializable

/**
 * STT Provider Credentials
 * Stores authentication credentials for all supported STT providers
 */
@Serializable
data class SttCredentials(
    // Currently selected STT provider
    val selectedProvider: String = SttProvider.GEMINI.name,
    
    // === Simple API Key Providers ===
    
    // OpenAI Whisper (reuses main OpenAI API key from ApiSettings)
    // Groq Whisper (reuses main Groq API key from ApiSettings)
    // Gemini (reuses main Gemini API key from ApiSettings)
    
    // === Deepgram ===
    val deepgramApiKey: String = "",
    
    // === AssemblyAI ===
    val assemblyaiApiKey: String = "",
    
    // === Google Cloud Speech-to-Text ===
    val gcpProjectId: String = "",
    val gcpApiKey: String = "",  // Simple API key mode
    val gcpServiceAccountJson: String = "",  // Service account JSON mode
    val gcpUseServiceAccount: Boolean = false,  // Which mode to use
    
    // === Microsoft Azure AI Speech ===
    val azureSpeechKey: String = "",
    val azureSpeechRegion: String = "",  // e.g., "eastus", "westus2"
    
    // === Amazon Transcribe ===
    val awsAccessKeyId: String = "",
    val awsSecretAccessKey: String = "",
    val awsSessionToken: String = "",  // Optional, for temporary credentials
    val awsRegion: String = "us-east-1",
    
    // === IBM Watson Speech to Text ===
    val ibmApiKey: String = "",
    val ibmServiceUrl: String = "",  // e.g., "https://api.us-south.speech-to-text.watson.cloud.ibm.com"
    
    // === iFLYTEK (Xunfei) ===
    val iflytekAppId: String = "",
    val iflytekApiKey: String = "",
    val iflytekApiSecret: String = "",
    
    // === Huawei Cloud SIS ===
    val huaweiAk: String = "",  // Access Key
    val huaweiSk: String = "",  // Secret Key
    val huaweiRegion: String = "cn-north-4",
    val huaweiProjectId: String = "",
    
    // === Volcengine (ByteDance) ===
    val volcengineAk: String = "",  // Access Key
    val volcangineSk: String = "",  // Secret Key
    val volcengineRegion: String = "",  // Optional
    val volcengineAppId: String = "",  // Application ID
    
    // === Alibaba Cloud ASR ===
    val aliyunAccessKeyId: String = "",
    val aliyunAccessKeySecret: String = "",
    val aliyunAppKey: String = "",  // NLS AppKey
    
    // === Tencent Cloud ASR ===
    val tencentSecretId: String = "",
    val tencentSecretKey: String = "",
    val tencentAppId: String = "",
    val tencentEngineModelType: String = "16k_zh",  // Engine model type
    
    // === Baidu Cloud ASR ===
    val baiduAsrApiKey: String = "",
    val baiduAsrSecretKey: String = "",
    
    // === Rev.ai ===
    val revaiAccessToken: String = "",
    
    // === Speechmatics ===
    val speechmaticsApiKey: String = "",
    
    // === Otter.ai ===
    val otteraiApiKey: String = ""
) {
    /**
     * Get the currently selected provider
     */
    fun getSelectedProvider(): SttProvider {
        return SttProvider.fromName(selectedProvider) ?: SttProvider.GEMINI
    }
    
    /**
     * Check if credentials are configured for the selected provider
     */
    fun hasCredentialsForSelectedProvider(): Boolean {
        return hasCredentialsForProvider(getSelectedProvider())
    }
    
    /**
     * Check if credentials are configured for a specific provider
     */
    fun hasCredentialsForProvider(provider: SttProvider): Boolean {
        return when (provider) {
            SttProvider.GEMINI -> true  // Uses main API key from ApiSettings
            SttProvider.OPENAI_WHISPER -> true  // Uses main API key from ApiSettings
            SttProvider.GROQ_WHISPER -> true  // Uses main API key from ApiSettings
            SttProvider.GOOGLE_CLOUD_STT -> {
                gcpProjectId.isNotBlank() && 
                (gcpApiKey.isNotBlank() || (gcpUseServiceAccount && gcpServiceAccountJson.isNotBlank()))
            }
            SttProvider.AZURE_SPEECH -> azureSpeechKey.isNotBlank() && azureSpeechRegion.isNotBlank()
            SttProvider.AWS_TRANSCRIBE -> awsAccessKeyId.isNotBlank() && awsSecretAccessKey.isNotBlank() && awsRegion.isNotBlank()
            SttProvider.IBM_WATSON -> ibmApiKey.isNotBlank() && ibmServiceUrl.isNotBlank()
            SttProvider.DEEPGRAM -> deepgramApiKey.isNotBlank()
            SttProvider.ASSEMBLYAI -> assemblyaiApiKey.isNotBlank()
            SttProvider.IFLYTEK -> iflytekAppId.isNotBlank() && iflytekApiKey.isNotBlank() && iflytekApiSecret.isNotBlank()
            SttProvider.HUAWEI_SIS -> huaweiAk.isNotBlank() && huaweiSk.isNotBlank() && huaweiProjectId.isNotBlank()
            SttProvider.VOLCENGINE -> volcengineAk.isNotBlank() && volcangineSk.isNotBlank()
            SttProvider.REV_AI -> revaiAccessToken.isNotBlank()
            SttProvider.SPEECHMATICS -> speechmaticsApiKey.isNotBlank()
            SttProvider.ALIBABA_ASR -> aliyunAccessKeyId.isNotBlank() && aliyunAccessKeySecret.isNotBlank() && aliyunAppKey.isNotBlank()
            SttProvider.TENCENT_ASR -> tencentSecretId.isNotBlank() && tencentSecretKey.isNotBlank()
            SttProvider.BAIDU_ASR -> baiduAsrApiKey.isNotBlank() && baiduAsrSecretKey.isNotBlank()
            SttProvider.OTTER_AI -> otteraiApiKey.isNotBlank()
        }
    }
    
    /**
     * Get list of configured providers
     */
    fun getConfiguredProviders(): List<SttProvider> {
        return SttProvider.entries.filter { hasCredentialsForProvider(it) }
    }
    
    /**
     * Create a copy with updated provider selection
     */
    fun withSelectedProvider(provider: SttProvider): SttCredentials {
        return copy(selectedProvider = provider.name)
    }
}
