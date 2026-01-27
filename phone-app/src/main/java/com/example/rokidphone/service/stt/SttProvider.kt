package com.example.rokidphone.service.stt

import androidx.annotation.StringRes
import com.example.rokidphone.R

/**
 * Speech-to-Text Service Providers
 * Defines dedicated STT providers separate from AI chat providers
 */
enum class SttProvider(
    @StringRes val displayNameResId: Int,
    val description: String,
    val website: String,
    val authType: SttAuthType,
    val supportsStreaming: Boolean = false,
    val supportsRealtime: Boolean = false
) {
    // Built-in AI providers with STT capability
    GEMINI(
        displayNameResId = R.string.stt_provider_gemini,
        description = "Google Gemini native audio transcription",
        website = "https://ai.google.dev",
        authType = SttAuthType.API_KEY,
        supportsStreaming = false,
        supportsRealtime = false
    ),
    OPENAI_WHISPER(
        displayNameResId = R.string.stt_provider_openai_whisper,
        description = "OpenAI Whisper API, industry standard",
        website = "https://openai.com",
        authType = SttAuthType.API_KEY,
        supportsStreaming = false,
        supportsRealtime = false
    ),
    GROQ_WHISPER(
        displayNameResId = R.string.stt_provider_groq_whisper,
        description = "Groq ultra-fast Whisper inference",
        website = "https://groq.com",
        authType = SttAuthType.API_KEY,
        supportsStreaming = false,
        supportsRealtime = false
    ),
    
    // Dedicated STT providers
    GOOGLE_CLOUD_STT(
        displayNameResId = R.string.stt_provider_google_cloud,
        description = "Google Cloud Speech-to-Text, enterprise grade",
        website = "https://cloud.google.com/speech-to-text",
        authType = SttAuthType.SERVICE_ACCOUNT_OR_API_KEY,
        supportsStreaming = true,
        supportsRealtime = true
    ),
    AZURE_SPEECH(
        displayNameResId = R.string.stt_provider_azure,
        description = "Microsoft Azure AI Speech",
        website = "https://azure.microsoft.com/services/cognitive-services/speech-services/",
        authType = SttAuthType.SUBSCRIPTION_KEY_REGION,
        supportsStreaming = true,
        supportsRealtime = true
    ),
    AWS_TRANSCRIBE(
        displayNameResId = R.string.stt_provider_aws,
        description = "Amazon Transcribe",
        website = "https://aws.amazon.com/transcribe/",
        authType = SttAuthType.AWS_IAM,
        supportsStreaming = true,
        supportsRealtime = true
    ),
    IBM_WATSON(
        displayNameResId = R.string.stt_provider_ibm_watson,
        description = "IBM Watson Speech to Text",
        website = "https://www.ibm.com/cloud/watson-speech-to-text",
        authType = SttAuthType.IBM_IAM,
        supportsStreaming = true,
        supportsRealtime = true
    ),
    DEEPGRAM(
        displayNameResId = R.string.stt_provider_deepgram,
        description = "Deepgram AI-powered transcription",
        website = "https://deepgram.com",
        authType = SttAuthType.API_KEY_HEADER,
        supportsStreaming = true,
        supportsRealtime = true
    ),
    ASSEMBLYAI(
        displayNameResId = R.string.stt_provider_assemblyai,
        description = "AssemblyAI transcription",
        website = "https://www.assemblyai.com",
        authType = SttAuthType.API_KEY_HEADER,
        supportsStreaming = true,
        supportsRealtime = true
    ),
    IFLYTEK(
        displayNameResId = R.string.stt_provider_iflytek,
        description = "科大訊飛 iFLYTEK Speech Recognition",
        website = "https://www.xfyun.cn",
        authType = SttAuthType.SIGNED_REQUEST,
        supportsStreaming = true,
        supportsRealtime = true
    ),
    HUAWEI_SIS(
        displayNameResId = R.string.stt_provider_huawei,
        description = "华为云语音交互服务 SIS",
        website = "https://www.huaweicloud.com/product/sis.html",
        authType = SttAuthType.AK_SK,
        supportsStreaming = true,
        supportsRealtime = true
    ),
    VOLCENGINE(
        displayNameResId = R.string.stt_provider_volcengine,
        description = "火山引擎语音识别 ASR",
        website = "https://www.volcengine.com/product/asr",
        authType = SttAuthType.AK_SK_SIGNED,
        supportsStreaming = true,
        supportsRealtime = true
    );
    
    companion object {
        fun fromName(name: String): SttProvider {
            return entries.find { it.name == name } ?: GEMINI
        }
        
        /**
         * Get providers that use simple API key auth
         */
        fun getSimpleApiKeyProviders(): List<SttProvider> = entries.filter {
            it.authType == SttAuthType.API_KEY || 
            it.authType == SttAuthType.API_KEY_HEADER
        }
        
        /**
         * Get providers available for Chinese language
         */
        fun getChineseProviders(): List<SttProvider> = listOf(
            GEMINI, OPENAI_WHISPER, GROQ_WHISPER,
            GOOGLE_CLOUD_STT, AZURE_SPEECH, 
            IFLYTEK, HUAWEI_SIS, VOLCENGINE
        )
    }
}

/**
 * Authentication types for STT providers
 */
enum class SttAuthType {
    API_KEY,                    // Simple API key (query param or Bearer token)
    API_KEY_HEADER,             // API key in Authorization header (Token scheme)
    SERVICE_ACCOUNT_OR_API_KEY, // Google Cloud: Service Account JSON or API Key
    SUBSCRIPTION_KEY_REGION,    // Azure: Subscription Key + Region
    AWS_IAM,                    // AWS: Access Key ID + Secret Access Key + Region
    IBM_IAM,                    // IBM: API Key + Service URL
    AK_SK,                      // Huawei: Access Key + Secret Key + Region + Project ID
    AK_SK_SIGNED,               // Volcengine: AK/SK with request signing
    SIGNED_REQUEST              // iFLYTEK: APPID + APIKey + APISecret with HMAC signature
}
