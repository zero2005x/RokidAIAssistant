package com.example.rokidaiassistant.data

import android.content.Context
import android.media.AudioFormat
import com.example.rokidaiassistant.BuildConfig
import java.util.UUID

/**
 * Application constants configuration
 */
object Constants {
    
    // ========================================
    // Rokid BLE Configuration
    // ========================================
    
    /** Rokid BLE Service UUID */
    val SERVICE_UUID: UUID = UUID.fromString("0000fe00-0000-1000-8000-00805f9b34fb")
    
    /** 
     * Rokid Client Secret (read from BuildConfig)
     * Note: Hyphens have been removed
     */
    val CLIENT_SECRET: String
        get() = BuildConfig.ROKID_CLIENT_SECRET.replace("-", "")
    
    /**
     * SN authentication file resource ID
     *
     * Single-source strategy: only one file named sn_auth_file.* should exist in res/raw.
     * We resolve by resource name to avoid compile-time dependency on a specific extension.
     */
    fun getSNResource(context: Context): Int {
        return context.resources.getIdentifier("sn_auth_file", "raw", context.packageName)
    }
    
    // ========================================
    // AI Configuration
    // ========================================
    
    /** Gemini API Key */
    val GEMINI_API_KEY: String
        get() = BuildConfig.GEMINI_API_KEY
    
    /** OpenAI API Key (for Whisper STT) */
    val OPENAI_API_KEY: String
        get() = BuildConfig.OPENAI_API_KEY

    /** AI assistant system prompt */
    const val SYSTEM_PROMPT = """
You are an AI assistant running on Rokid smart glasses, named "Xiao Luo".

Response rules:
1. Use Traditional Chinese for responses
2. Keep answers concise, within 2-3 sentences
3. Use a friendly, conversational tone suitable for voice playback
4. If the question is unclear, simply ask for clarification
5. For questions you cannot answer, honestly explain

You can help users with:
- Answering various questions
- Providing information queries
- Daily conversations
- Giving suggestions and assistance
"""
    
    /** Gemini model name */
    const val GEMINI_MODEL = "gemini-2.5-flash"
    
    /** Maximum output tokens */
    const val MAX_OUTPUT_TOKENS = 256
    
    // ========================================
    // Audio Configuration
    // ========================================
    
    /** Audio sample rate */
    const val AUDIO_SAMPLE_RATE = 16000
    
    /** Audio channel configuration */
    const val AUDIO_CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
    
    /** Audio encoding format */
    const val AUDIO_ENCODING = AudioFormat.ENCODING_PCM_16BIT
    
    // ========================================
    // Heartbeat Configuration
    // ========================================
    
    /** AI scene heartbeat interval (milliseconds) */
    const val HEARTBEAT_INTERVAL_MS = 5000L
    
    // ========================================
    // SharedPreferences Keys
    // ========================================
    
    const val PREF_NAME = "rokid_ai_assistant"
    const val PREF_LAST_DEVICE_NAME = "last_device_name"
    const val PREF_LAST_DEVICE_MAC = "last_device_mac"
    const val PREF_LAST_DEVICE_UUID = "last_device_uuid"
}
