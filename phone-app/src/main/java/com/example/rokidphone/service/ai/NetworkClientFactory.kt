package com.example.rokidphone.service.ai

import android.util.Log
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Network Client Factory
 * Creates OkHttpClient instances with dynamic configuration
 * Supports runtime base URL changes and custom headers
 */
object NetworkClientFactory {
    
    private const val TAG = "NetworkClientFactory"
    
    /**
     * Create a new OkHttpClient with default timeout settings
     */
    fun createClient(
        connectTimeout: Long = 60,
        readTimeout: Long = 120,
        writeTimeout: Long = 120
    ): OkHttpClient {
        return OkHttpClient.Builder()
            .connectTimeout(connectTimeout, TimeUnit.SECONDS)
            .readTimeout(readTimeout, TimeUnit.SECONDS)
            .writeTimeout(writeTimeout, TimeUnit.SECONDS)
            .addInterceptor(LoggingInterceptor())
            .build()
    }
    
    /**
     * Create client with authorization header
     */
    fun createClientWithAuth(
        apiKey: String,
        authHeaderName: String = "Authorization",
        authHeaderPrefix: String = "Bearer "
    ): OkHttpClient {
        return OkHttpClient.Builder()
            .connectTimeout(60, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(120, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                val request = chain.request().newBuilder()
                    .addHeader(authHeaderName, "$authHeaderPrefix$apiKey")
                    .build()
                chain.proceed(request)
            }
            .addInterceptor(LoggingInterceptor())
            .build()
    }
    
    /**
     * Create client for Anthropic (uses x-api-key header)
     */
    fun createAnthropicClient(apiKey: String): OkHttpClient {
        return OkHttpClient.Builder()
            .connectTimeout(60, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(120, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                val request = chain.request().newBuilder()
                    .addHeader("x-api-key", apiKey)
                    .addHeader("anthropic-version", "2023-06-01")
                    .addHeader("Content-Type", "application/json")
                    .build()
                chain.proceed(request)
            }
            .addInterceptor(LoggingInterceptor())
            .build()
    }
    
    /**
     * Logging interceptor for debugging
     */
    private class LoggingInterceptor : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request()
            val startTime = System.nanoTime()
            
            Log.d(TAG, "Sending request: ${request.method} ${request.url}")
            
            return try {
                val response = chain.proceed(request)
                val duration = (System.nanoTime() - startTime) / 1_000_000
                
                Log.d(TAG, "Received response: ${response.code} in ${duration}ms")
                response
            } catch (e: IOException) {
                Log.e(TAG, "Request failed: ${e.message}")
                throw e
            }
        }
    }
}

/**
 * Dynamic URL Interceptor
 * Allows changing the base URL at runtime
 */
class DynamicUrlInterceptor(
    private var baseUrl: String
) : Interceptor {
    
    companion object {
        private const val TAG = "DynamicUrlInterceptor"
    }
    
    /**
     * Update the base URL
     */
    fun setBaseUrl(newBaseUrl: String) {
        this.baseUrl = newBaseUrl.trimEnd('/')
        Log.d(TAG, "Base URL updated to: $baseUrl")
    }
    
    override fun intercept(chain: Interceptor.Chain): Response {
        val originalRequest = chain.request()
        val originalUrl = originalRequest.url.toString()
        
        // If the URL is already absolute, don't modify it
        if (originalUrl.startsWith("http://") || originalUrl.startsWith("https://")) {
            return chain.proceed(originalRequest)
        }
        
        // Build the new URL with the dynamic base
        val newUrl = "$baseUrl/$originalUrl".replace("//", "/").replace(":/", "://")
        
        val newRequest = originalRequest.newBuilder()
            .url(newUrl)
            .build()
        
        Log.d(TAG, "Rewriting URL: $originalUrl -> $newUrl")
        return chain.proceed(newRequest)
    }
}

/**
 * API Endpoint Helper
 * Constructs full URLs from base URL and endpoint paths
 */
object ApiEndpoints {
    
    /**
     * Build full URL from base URL and path
     */
    fun buildUrl(baseUrl: String, path: String): String {
        val normalizedBase = baseUrl.trimEnd('/')
        val normalizedPath = path.trimStart('/')
        return "$normalizedBase/$normalizedPath"
    }
    
    /**
     * OpenAI-compatible endpoints
     */
    object OpenAiCompatible {
        const val CHAT_COMPLETIONS = "chat/completions"
        const val COMPLETIONS = "completions"
        const val EMBEDDINGS = "embeddings"
        const val AUDIO_TRANSCRIPTIONS = "audio/transcriptions"
        const val AUDIO_TRANSLATIONS = "audio/translations"
        const val MODELS = "models"
    }
    
    /**
     * Anthropic endpoints
     */
    object Anthropic {
        const val MESSAGES = "messages"
    }
    
    /**
     * Gemini endpoints (using v1beta)
     */
    object Gemini {
        fun generateContent(model: String) = "models/$model:generateContent"
        fun streamGenerateContent(model: String) = "models/$model:streamGenerateContent"
    }
}
