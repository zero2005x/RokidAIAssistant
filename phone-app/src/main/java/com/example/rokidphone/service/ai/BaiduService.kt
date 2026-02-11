package com.example.rokidphone.service.ai

import android.util.Log
import com.example.rokidphone.data.AiProvider
import com.example.rokidphone.service.SpeechResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Baidu Ernie Bot / Wenxin Service Implementation
 * 
 * Baidu uses a proprietary API that requires OAuth 2.0 authentication:
 * 1. Exchange API Key + Secret Key for an access_token
 * 2. Use the access_token in subsequent API calls
 * 
 * API Docs: https://cloud.baidu.com/doc/WENXINWORKSHOP/s/flfmc9do2
 */
class BaiduService(
    private val apiKey: String,
    private val secretKey: String,
    private val modelId: String = "ernie-4.0-8k",
    private val systemPrompt: String = ""
) : AiServiceProvider {
    
    companion object {
        private const val TAG = "BaiduService"
        private const val TOKEN_URL = "https://aip.baidubce.com/oauth/2.0/token"
        private const val BASE_CHAT_URL = "https://aip.baidubce.com/rpc/2.0/ai_custom/v1/wenxinworkshop/chat"
        
        // Token cache with thread-safe access
        private var cachedToken: String? = null
        private var tokenExpiry: Long = 0L
        private val tokenMutex = Mutex()
        
        // Model endpoint mapping
        private val modelEndpoints = mapOf(
            "ernie-4.0-8k" to "completions_pro",
            "ernie-4.0-turbo-8k" to "ernie-4.0-turbo-8k",
            "ernie-3.5-8k" to "completions",
            "ernie-speed-8k" to "ernie_speed",
            "ernie-speed-128k" to "ernie-speed-128k",
            "ernie-lite-8k" to "ernie-lite-8k",
            "ernie-tiny-8k" to "ernie-tiny-8k"
        )
    }
    
    override val provider = AiProvider.BAIDU
    
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .build()
    
    // Conversation history
    private val conversationHistory = mutableListOf<Pair<String, String>>()
    
    /**
     * Get access token with caching
     */
    private suspend fun getAccessToken(): String? {
        return tokenMutex.withLock {
            val now = System.currentTimeMillis()
            
            // Return cached token if still valid (with 5 minute buffer)
            if (cachedToken != null && tokenExpiry > now + 300_000) {
                Log.d(TAG, "Using cached access token")
                return@withLock cachedToken
            }
            
            // Fetch new token
            Log.d(TAG, "Fetching new access token")
            withContext(Dispatchers.IO) {
                try {
                    val tokenRequest = Request.Builder()
                        .url("$TOKEN_URL?grant_type=client_credentials&client_id=$apiKey&client_secret=$secretKey")
                        .post("".toRequestBody("application/json".toMediaType()))
                        .build()
                    
                    client.newCall(tokenRequest).execute().use { response ->
                        val responseBody = response.body?.string()
                        
                        if (response.isSuccessful && responseBody != null) {
                            val json = JSONObject(responseBody)
                            val token = json.optString("access_token")
                            val expiresIn = json.optLong("expires_in", 86400) // Default 24 hours
                            
                            if (token.isNotBlank()) {
                                cachedToken = token
                                tokenExpiry = now + (expiresIn * 1000)
                                Log.d(TAG, "Access token obtained, expires in ${expiresIn}s")
                                token
                            } else {
                                Log.e(TAG, "Empty access token in response")
                                null
                            }
                        } else {
                            Log.e(TAG, "Token request failed: ${response.code}, body: $responseBody")
                            null
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to get access token", e)
                    null
                }
            }
        }
    }
    
    /**
     * Get the chat endpoint for the specified model
     */
    private fun getChatEndpoint(modelId: String): String {
        val endpoint = modelEndpoints[modelId] ?: "completions"
        return "$BASE_CHAT_URL/$endpoint"
    }
    
    /**
     * Speech Recognition - Baidu does not support STT through this API
     */
    override suspend fun transcribe(pcmAudioData: ByteArray, languageCode: String): SpeechResult {
        return SpeechResult.Error("Baidu Ernie does not support speech recognition through this API")
    }
    
    /**
     * Text Chat - Using Baidu Chat API
     */
    override suspend fun chat(userMessage: String): String {
        return withContext(Dispatchers.IO) {
            Log.d(TAG, "Chat request: $userMessage")
            
            // Get access token
            val accessToken = getAccessToken()
            if (accessToken == null) {
                return@withContext "Authentication failed. Please check your Baidu API Key and Secret Key."
            }
            
            // Build messages array (Baidu format is slightly different)
            val messages = JSONArray().apply {
                // Add conversation history
                for ((role, content) in conversationHistory.takeLast(6)) {
                    put(JSONObject().apply {
                        put("role", role)
                        put("content", content)
                    })
                }
                
                // Add current user message
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", userMessage)
                })
            }
            
            // Build request body (Baidu format)
            val requestJson = JSONObject().apply {
                put("messages", messages)
                put("system", getFullSystemPrompt())
                put("temperature", 0.7)
                put("stream", false)
            }
            
            val chatUrl = "${getChatEndpoint(modelId)}?access_token=$accessToken"
            
            try {
                Log.d(TAG, "Sending chat request to Baidu")
                
                val request = Request.Builder()
                    .url(chatUrl)
                    .addHeader("Content-Type", "application/json")
                    .post(requestJson.toString().toRequestBody("application/json".toMediaType()))
                    .build()
                
                client.newCall(request).execute().use { response ->
                    val responseBody = response.body?.string()
                    
                    if (response.isSuccessful && responseBody != null) {
                        val json = JSONObject(responseBody)
                        
                        // Check for error in response
                        val errorCode = json.optInt("error_code", 0)
                        if (errorCode != 0) {
                            val errorMsg = json.optString("error_msg", "Unknown error")
                            Log.e(TAG, "Baidu API error: $errorCode - $errorMsg")
                            
                            // If token error, clear cache and retry
                            if (errorCode == 110 || errorCode == 111) {
                                tokenMutex.withLock {
                                    cachedToken = null
                                    tokenExpiry = 0L
                                }
                                return@withContext "Token expired. Please try again."
                            }
                            
                            return@withContext "Baidu API error: $errorMsg"
                        }
                        
                        // Extract response
                        val result = json.optString("result", "").trim()
                        
                        if (result.isNotEmpty()) {
                            addToHistory(userMessage, result)
                            Log.d(TAG, "Baidu response: $result")
                            result
                        } else {
                            "Sorry, I couldn't generate a response."
                        }
                    } else {
                        Log.e(TAG, "API error: ${response.code}, body: $responseBody")
                        "Sorry, Baidu service is temporarily unavailable."
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Chat error", e)
                "Sorry, an error occurred: ${e.message}"
            }
        }
    }
    
    /**
     * Image Analysis - Baidu has separate vision APIs
     */
    override suspend fun analyzeImage(imageData: ByteArray, prompt: String): String {
        return "Baidu Ernie image analysis requires a separate API configuration."
    }
    
    /**
     * Test Connection - Verify credentials
     */
    suspend fun testConnection(): Result<String> {
        return withContext(Dispatchers.IO) {
            try {
                Log.d(TAG, "Testing Baidu connection")
                
                val accessToken = getAccessToken()
                if (accessToken != null) {
                    Result.success("Connected successfully! Access token obtained.")
                } else {
                    Result.failure(Exception("Failed to obtain access token. Please check your API Key and Secret Key."))
                }
            } catch (e: Exception) {
                Log.e(TAG, "Connection test failed", e)
                Result.failure(e)
            }
        }
    }
    
    /**
     * Get current date time string
     */
    private fun getCurrentDateTime(): String {
        val dateFormat = SimpleDateFormat("yyyy-MM-dd EEEE HH:mm", Locale.getDefault())
        return dateFormat.format(Date())
    }
    
    /**
     * Get full system prompt with date
     */
    private fun getFullSystemPrompt(): String {
        return "$systemPrompt\n\nCurrent date/time: ${getCurrentDateTime()}"
    }
    
    /**
     * Add to conversation history
     */
    private fun addToHistory(userMessage: String, assistantMessage: String) {
        conversationHistory.add("user" to userMessage)
        conversationHistory.add("assistant" to assistantMessage)
        
        // Limit history length
        while (conversationHistory.size > 10) {
            conversationHistory.removeAt(0)
        }
    }
    
    /**
     * Clear conversation history
     */
    override fun clearHistory() {
        conversationHistory.clear()
    }
    
    /**
     * Clear cached token (useful when credentials change)
     */
    suspend fun clearTokenCache() {
        tokenMutex.withLock {
            cachedToken = null
            tokenExpiry = 0L
        }
    }
}
