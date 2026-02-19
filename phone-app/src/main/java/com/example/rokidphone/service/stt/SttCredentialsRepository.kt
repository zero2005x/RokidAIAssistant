package com.example.rokidphone.service.stt

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Repository for STT provider credentials
 * Uses EncryptedSharedPreferences for secure storage of API keys and secrets
 */
class SttCredentialsRepository(context: Context) {
    
    companion object {
        private const val TAG = "SttCredentialsRepo"
        private const val PREFS_NAME = "rokid_stt_credentials"
        
        // Selected provider
        private const val KEY_SELECTED_PROVIDER = "selected_provider"
        
        // Deepgram
        private const val KEY_DEEPGRAM_API_KEY = "deepgram_api_key"
        
        // AssemblyAI
        private const val KEY_ASSEMBLYAI_API_KEY = "assemblyai_api_key"
        
        // Google Cloud STT
        private const val KEY_GCP_PROJECT_ID = "gcp_project_id"
        private const val KEY_GCP_API_KEY = "gcp_api_key"
        private const val KEY_GCP_SERVICE_ACCOUNT_JSON = "gcp_service_account_json"
        private const val KEY_GCP_USE_SERVICE_ACCOUNT = "gcp_use_service_account"
        
        // Azure Speech
        private const val KEY_AZURE_SPEECH_KEY = "azure_speech_key"
        private const val KEY_AZURE_SPEECH_REGION = "azure_speech_region"
        
        // AWS Transcribe
        private const val KEY_AWS_ACCESS_KEY_ID = "aws_access_key_id"
        private const val KEY_AWS_SECRET_ACCESS_KEY = "aws_secret_access_key"
        private const val KEY_AWS_SESSION_TOKEN = "aws_session_token"
        private const val KEY_AWS_REGION = "aws_region"
        
        // IBM Watson
        private const val KEY_IBM_API_KEY = "ibm_api_key"
        private const val KEY_IBM_SERVICE_URL = "ibm_service_url"
        
        // iFLYTEK
        private const val KEY_IFLYTEK_APP_ID = "iflytek_app_id"
        private const val KEY_IFLYTEK_API_KEY = "iflytek_api_key"
        private const val KEY_IFLYTEK_API_SECRET = "iflytek_api_secret"
        
        // Huawei Cloud SIS
        private const val KEY_HUAWEI_AK = "huawei_ak"
        private const val KEY_HUAWEI_SK = "huawei_sk"
        private const val KEY_HUAWEI_REGION = "huawei_region"
        private const val KEY_HUAWEI_PROJECT_ID = "huawei_project_id"
        
        // Volcengine
        private const val KEY_VOLCENGINE_AK = "volcengine_ak"
        private const val KEY_VOLCENGINE_SK = "volcengine_sk"
        private const val KEY_VOLCENGINE_REGION = "volcengine_region"
        private const val KEY_VOLCENGINE_APP_ID = "volcengine_app_id"
        
        @Volatile
        private var instance: SttCredentialsRepository? = null
        
        fun getInstance(context: Context): SttCredentialsRepository {
            return instance ?: synchronized(this) {
                instance ?: SttCredentialsRepository(context.applicationContext).also { instance = it }
            }
        }
    }
    
    private val masterKey = MasterKey.Builder(context, MasterKey.DEFAULT_MASTER_KEY_ALIAS)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()
    
    private val prefs: SharedPreferences = try {
        EncryptedSharedPreferences.create(
            context,
            PREFS_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    } catch (e: Exception) {
        Log.w(TAG, "Failed to create encrypted prefs, using regular prefs", e)
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }
    
    private val _credentialsFlow = MutableStateFlow(loadCredentials())
    val credentialsFlow: StateFlow<SttCredentials> = _credentialsFlow.asStateFlow()
    
    /**
     * Get current credentials
     */
    fun getCredentials(): SttCredentials = _credentialsFlow.value
    
    /**
     * Load credentials from storage
     */
    private fun loadCredentials(): SttCredentials {
        return SttCredentials(
            selectedProvider = prefs.getString(KEY_SELECTED_PROVIDER, SttProvider.GEMINI.name) ?: SttProvider.GEMINI.name,
            
            // Deepgram
            deepgramApiKey = prefs.getString(KEY_DEEPGRAM_API_KEY, "") ?: "",
            
            // AssemblyAI
            assemblyaiApiKey = prefs.getString(KEY_ASSEMBLYAI_API_KEY, "") ?: "",
            
            // Google Cloud STT
            gcpProjectId = prefs.getString(KEY_GCP_PROJECT_ID, "") ?: "",
            gcpApiKey = prefs.getString(KEY_GCP_API_KEY, "") ?: "",
            gcpServiceAccountJson = prefs.getString(KEY_GCP_SERVICE_ACCOUNT_JSON, "") ?: "",
            gcpUseServiceAccount = prefs.getBoolean(KEY_GCP_USE_SERVICE_ACCOUNT, false),
            
            // Azure Speech
            azureSpeechKey = prefs.getString(KEY_AZURE_SPEECH_KEY, "") ?: "",
            azureSpeechRegion = prefs.getString(KEY_AZURE_SPEECH_REGION, "") ?: "",
            
            // AWS Transcribe
            awsAccessKeyId = prefs.getString(KEY_AWS_ACCESS_KEY_ID, "") ?: "",
            awsSecretAccessKey = prefs.getString(KEY_AWS_SECRET_ACCESS_KEY, "") ?: "",
            awsSessionToken = prefs.getString(KEY_AWS_SESSION_TOKEN, "") ?: "",
            awsRegion = prefs.getString(KEY_AWS_REGION, "us-east-1") ?: "us-east-1",
            
            // IBM Watson
            ibmApiKey = prefs.getString(KEY_IBM_API_KEY, "") ?: "",
            ibmServiceUrl = prefs.getString(KEY_IBM_SERVICE_URL, "") ?: "",
            
            // iFLYTEK
            iflytekAppId = prefs.getString(KEY_IFLYTEK_APP_ID, "") ?: "",
            iflytekApiKey = prefs.getString(KEY_IFLYTEK_API_KEY, "") ?: "",
            iflytekApiSecret = prefs.getString(KEY_IFLYTEK_API_SECRET, "") ?: "",
            
            // Huawei Cloud SIS
            huaweiAk = prefs.getString(KEY_HUAWEI_AK, "") ?: "",
            huaweiSk = prefs.getString(KEY_HUAWEI_SK, "") ?: "",
            huaweiRegion = prefs.getString(KEY_HUAWEI_REGION, "cn-north-4") ?: "cn-north-4",
            huaweiProjectId = prefs.getString(KEY_HUAWEI_PROJECT_ID, "") ?: "",
            
            // Volcengine
            volcengineAk = prefs.getString(KEY_VOLCENGINE_AK, "") ?: "",
            volcangineSk = prefs.getString(KEY_VOLCENGINE_SK, "") ?: "",
            volcengineRegion = prefs.getString(KEY_VOLCENGINE_REGION, "") ?: "",
            volcengineAppId = prefs.getString(KEY_VOLCENGINE_APP_ID, "") ?: ""
        )
    }
    
    /**
     * Save credentials
     */
    fun saveCredentials(credentials: SttCredentials) {
        prefs.edit().apply {
            putString(KEY_SELECTED_PROVIDER, credentials.selectedProvider)
            
            // Deepgram
            putString(KEY_DEEPGRAM_API_KEY, credentials.deepgramApiKey)
            
            // AssemblyAI
            putString(KEY_ASSEMBLYAI_API_KEY, credentials.assemblyaiApiKey)
            
            // Google Cloud STT
            putString(KEY_GCP_PROJECT_ID, credentials.gcpProjectId)
            putString(KEY_GCP_API_KEY, credentials.gcpApiKey)
            putString(KEY_GCP_SERVICE_ACCOUNT_JSON, credentials.gcpServiceAccountJson)
            putBoolean(KEY_GCP_USE_SERVICE_ACCOUNT, credentials.gcpUseServiceAccount)
            
            // Azure Speech
            putString(KEY_AZURE_SPEECH_KEY, credentials.azureSpeechKey)
            putString(KEY_AZURE_SPEECH_REGION, credentials.azureSpeechRegion)
            
            // AWS Transcribe
            putString(KEY_AWS_ACCESS_KEY_ID, credentials.awsAccessKeyId)
            putString(KEY_AWS_SECRET_ACCESS_KEY, credentials.awsSecretAccessKey)
            putString(KEY_AWS_SESSION_TOKEN, credentials.awsSessionToken)
            putString(KEY_AWS_REGION, credentials.awsRegion)
            
            // IBM Watson
            putString(KEY_IBM_API_KEY, credentials.ibmApiKey)
            putString(KEY_IBM_SERVICE_URL, credentials.ibmServiceUrl)
            
            // iFLYTEK
            putString(KEY_IFLYTEK_APP_ID, credentials.iflytekAppId)
            putString(KEY_IFLYTEK_API_KEY, credentials.iflytekApiKey)
            putString(KEY_IFLYTEK_API_SECRET, credentials.iflytekApiSecret)
            
            // Huawei Cloud SIS
            putString(KEY_HUAWEI_AK, credentials.huaweiAk)
            putString(KEY_HUAWEI_SK, credentials.huaweiSk)
            putString(KEY_HUAWEI_REGION, credentials.huaweiRegion)
            putString(KEY_HUAWEI_PROJECT_ID, credentials.huaweiProjectId)
            
            // Volcengine
            putString(KEY_VOLCENGINE_AK, credentials.volcengineAk)
            putString(KEY_VOLCENGINE_SK, credentials.volcangineSk)
            putString(KEY_VOLCENGINE_REGION, credentials.volcengineRegion)
            putString(KEY_VOLCENGINE_APP_ID, credentials.volcengineAppId)
            
            apply()
        }
        
        _credentialsFlow.value = credentials
        Log.d(TAG, "STT credentials saved for provider: ${credentials.selectedProvider}")
    }
    
    /**
     * Update selected provider
     */
    fun setSelectedProvider(provider: SttProvider) {
        val current = _credentialsFlow.value
        saveCredentials(current.copy(selectedProvider = provider.name))
    }
    
    /**
     * Clear all credentials (for logout/reset)
     */
    fun clearAll() {
        prefs.edit().clear().apply()
        _credentialsFlow.value = SttCredentials()
        Log.d(TAG, "All STT credentials cleared")
    }
}
