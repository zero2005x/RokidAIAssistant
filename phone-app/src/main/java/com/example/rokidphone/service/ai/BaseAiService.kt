package com.example.rokidphone.service.ai

import android.util.Log
import kotlinx.coroutines.delay
import okhttp3.OkHttpClient
import java.io.ByteArrayOutputStream
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * AI Service Base Class
 * Provides common functionality: network requests, retry mechanism, error handling, etc.
 */
abstract class BaseAiService(
    protected val apiKey: String,
    protected val modelId: String,
    protected val systemPrompt: String
) {
    companion object {
        private const val TAG = "BaseAiService"
        protected const val MAX_RETRIES = 3
        protected const val RETRY_DELAY_MS = 1000L
    }
    
    protected val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .build()
    
    // Conversation history
    protected val conversationHistory = mutableListOf<Pair<String, String>>() // (role, content)
    
    /**
     * Get current date time string
     */
    protected fun getCurrentDateTime(): String {
        val dateFormat = SimpleDateFormat("yyyy-MM-dd EEEE HH:mm", Locale.getDefault())
        return dateFormat.format(Date())
    }
    
    /**
     * Get full system prompt with date
     */
    protected fun getFullSystemPrompt(): String {
        return "$systemPrompt\n\nCurrent date/time: ${getCurrentDateTime()}"
    }
    
    /**
     * Convert PCM audio data to WAV format
     */
    protected fun pcmToWav(
        pcmData: ByteArray, 
        sampleRate: Int = 16000, 
        channels: Int = 1, 
        bitsPerSample: Int = 16
    ): ByteArray {
        val byteRate = sampleRate * channels * bitsPerSample / 8
        val blockAlign = channels * bitsPerSample / 8
        val dataSize = pcmData.size
        val totalSize = 36 + dataSize
        
        val output = ByteArrayOutputStream()
        
        // RIFF header
        output.write("RIFF".toByteArray())
        output.write(intToBytes(totalSize, 4))
        output.write("WAVE".toByteArray())
        
        // fmt chunk
        output.write("fmt ".toByteArray())
        output.write(intToBytes(16, 4))
        output.write(intToBytes(1, 2))
        output.write(intToBytes(channels, 2))
        output.write(intToBytes(sampleRate, 4))
        output.write(intToBytes(byteRate, 4))
        output.write(intToBytes(blockAlign, 2))
        output.write(intToBytes(bitsPerSample, 2))
        
        // data chunk
        output.write("data".toByteArray())
        output.write(intToBytes(dataSize, 4))
        output.write(pcmData)
        
        return output.toByteArray()
    }
    
    private fun intToBytes(value: Int, numBytes: Int): ByteArray {
        val bytes = ByteArray(numBytes)
        for (i in 0 until numBytes) {
            bytes[i] = (value shr (8 * i) and 0xFF).toByte()
        }
        return bytes
    }
    
    /**
     * Check if exception is network related
     */
    protected fun isNetworkException(e: Exception?): Boolean {
        return e is UnknownHostException ||
               e is ConnectException ||
               e is SocketTimeoutException ||
               e?.message?.contains("Network is unreachable") == true ||
               e?.message?.contains("Failed to connect") == true
    }
    
    /**
     * Convert technical errors to user-friendly messages
     */
    protected fun getFriendlyErrorMessage(e: Exception?): String {
        return when {
            e == null -> "An unknown error occurred, please try again"
            e is UnknownHostException -> "Network connection failed, please check your settings"
            e is SocketTimeoutException -> "Connection timed out, please check network speed"
            e is ConnectException -> "Cannot connect to server, please check network settings"
            e.message?.contains("Network is unreachable") == true -> "Network unreachable, please check WiFi or mobile data"
            e.message?.contains("Failed to connect") == true -> "Connection failed, please try again later"
            e.message?.contains("timeout") == true -> "Connection timed out, please try again later"
            else -> "An error occurred while processing, please try again"
        }
    }
    
    /**
     * Execute request with retry mechanism
     */
    protected suspend inline fun <T> executeWithRetry(
        tag: String,
        crossinline action: suspend (attempt: Int) -> T?
    ): T? {
        var lastException: Exception? = null
        
        for (attempt in 1..MAX_RETRIES) {
            try {
                val result = action(attempt)
                if (result != null) return result
            } catch (e: Exception) {
                lastException = e
                val isNetworkError = isNetworkException(e)
                
                if (isNetworkError && attempt < MAX_RETRIES) {
                    Log.w(tag, "Network error on attempt $attempt, retrying in ${RETRY_DELAY_MS * attempt}ms...", e)
                    delay(RETRY_DELAY_MS * attempt)
                    continue
                } else {
                    Log.e(tag, "Request failed on attempt $attempt", e)
                    break
                }
            }
        }
        
        Log.e(tag, "All retry attempts failed", lastException)
        return null
    }
    
    /**
     * Add to conversation history
     */
    protected fun addToHistory(userMessage: String, assistantMessage: String) {
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
    open fun clearHistory() {
        conversationHistory.clear()
    }
}
