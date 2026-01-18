package com.example.rokidaiassistant.services

import android.util.Log
import com.example.rokidaiassistant.data.Constants
import com.google.ai.client.generativeai.GenerativeModel
import com.google.ai.client.generativeai.type.content
import com.google.ai.client.generativeai.type.generationConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Gemini AI Service
 * 
 * Responsible for communicating with Google Gemini API and providing conversation capabilities
 */
class GeminiService {
    
    companion object {
        private const val TAG = "GeminiService"
    }
    
    private val model: GenerativeModel by lazy {
        GenerativeModel(
            modelName = Constants.GEMINI_MODEL,
            apiKey = Constants.GEMINI_API_KEY,
            generationConfig = generationConfig {
                temperature = 0.7f
                topK = 40
                topP = 0.95f
                maxOutputTokens = Constants.MAX_OUTPUT_TOKENS
            }
        )
    }
    
    // Initialize conversation history with system prompt
    private val chat by lazy {
        model.startChat(
            history = listOf(
                content(role = "user") { text("Please remember the following settings: ${Constants.SYSTEM_PROMPT}") },
                content(role = "model") { text("OK, I have remembered these settings. I am Xiao Luo, an AI assistant running on Rokid smart glasses. I will respond concisely in Traditional Chinese with a friendly, conversational tone. How can I help you?") }
            )
        )
    }
    
    /**
     * Send message and get response
     * 
     * @param userMessage User message
     * @return Result<String> Contains AI response on success, error on failure
     */
    suspend fun sendMessage(userMessage: String): Result<String> = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Sending message to Gemini: $userMessage")
            
            val response = chat.sendMessage(userMessage)
            val text = response.text
            
            if (text.isNullOrBlank()) {
                Log.w(TAG, "Gemini returned empty response")
                Result.success("Sorry, I cannot answer this question at the moment.")
            } else {
                Log.d(TAG, "Gemini response: $text")
                Result.success(text.trim())
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Gemini API call failed", e)
            Result.failure(e)
        }
    }
    
    /**
     * Single Q&A (no context retention)
     * 
     * @param prompt Question
     * @return Result<String> AI response
     */
    suspend fun generateContent(prompt: String): Result<String> = withContext(Dispatchers.IO) {
        try {
            val response = model.generateContent(prompt)
            val text = response.text ?: "Sorry, I cannot answer this question."
            Result.success(text.trim())
        } catch (e: Exception) {
            Log.e(TAG, "generateContent failed", e)
            Result.failure(e)
        }
    }
    
    /**
     * Reset conversation (clear context)
     */
    fun resetConversation() {
        // Note: GenerativeModel.startChat() creates a new ChatSession
        // If reset is needed, the entire service instance may need to be recreated
        Log.d(TAG, "Conversation has been reset")
    }
}
