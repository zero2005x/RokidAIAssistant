package com.example.rokidglasses.service

import android.util.Log
import com.google.ai.client.generativeai.GenerativeModel
import com.google.ai.client.generativeai.type.content
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Gemini AI Service
 * For processing user voice questions and generating responses
 */
class GeminiAIService(
    private val apiKey: String // Must be passed from BuildConfig or settings
) {
    companion object {
        private const val TAG = "GeminiAIService"
    }
    
    private val generativeModel = GenerativeModel(
        modelName = "gemini-2.5-flash",
        apiKey = apiKey
    )
    
    // Conversation history
    private val conversationHistory = mutableListOf<Pair<String, String>>()
    
    /**
     * Send message and get AI response
     * @param userMessage User's message
     * @return AI response text
     */
    suspend fun chat(userMessage: String): String {
        return withContext(Dispatchers.IO) {
            try {
                Log.d(TAG, "Sending message to Gemini: $userMessage")
                
                // Create chat with system prompt
                val chat = generativeModel.startChat(
                    history = buildHistory()
                )
                
                val response = chat.sendMessage(userMessage)
                val responseText = response.text ?: "Sorry, I couldn't understand your question."
                
                // Save conversation history
                conversationHistory.add(Pair(userMessage, responseText))
                
                // Limit history length
                if (conversationHistory.size > 10) {
                    conversationHistory.removeAt(0)
                }
                
                Log.d(TAG, "Gemini response: $responseText")
                responseText
            } catch (e: Exception) {
                Log.e(TAG, "Gemini API error", e)
                "Sorry, an error occurred: ${e.message}"
            }
        }
    }
    
    private fun buildHistory() = listOf(
        content(role = "user") {
            text("You are a friendly AI assistant installed on Rokid smart glasses. Please answer questions concisely in the user's language, with each response not exceeding 100 words.")
        },
        content(role = "model") {
            text("Understood, I am your Rokid AI assistant. I will assist you concisely. How can I help you?")
        }
    ) + conversationHistory.flatMap { (user, model) ->
        listOf(
            content(role = "user") { text(user) },
            content(role = "model") { text(model) }
        )
    }
    
    /**
     * Clear conversation history
     */
    fun clearHistory() {
        conversationHistory.clear()
    }
}
