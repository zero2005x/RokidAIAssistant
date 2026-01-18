package com.example.rokidcommon

import java.util.UUID

/**
 * Shared constants
 */
object Constants {
    // Bluetooth UUID
    val BT_SERVICE_UUID: UUID = UUID.fromString("a1b2c3d4-e5f6-7890-abcd-ef1234567890")
    val BT_CHARACTERISTIC_UUID: UUID = UUID.fromString("a1b2c3d4-e5f6-7890-abcd-ef1234567891")
    
    // Connection settings
    const val HEARTBEAT_INTERVAL_MS = 5000L
    const val CONNECTION_TIMEOUT_MS = 10000L
    const val RECONNECT_DELAY_MS = 3000L
    const val MAX_RECONNECT_ATTEMPTS = 5
    
    // Audio settings
    const val AUDIO_SAMPLE_RATE = 16000
    const val AUDIO_CHANNEL_CONFIG = 16 // AudioFormat.CHANNEL_IN_MONO
    const val AUDIO_ENCODING = 2 // AudioFormat.ENCODING_PCM_16BIT
    const val AUDIO_BUFFER_SIZE = 4096
    
    // AI settings
    const val AI_MAX_RESPONSE_LENGTH = 500
    const val AI_TIMEOUT_MS = 30000L
    
    // Display settings
    const val DISPLAY_MAX_LINES = 5
    const val DISPLAY_FADE_DURATION_MS = 300L
    
    // Notification Channel
    const val NOTIFICATION_CHANNEL_ID = "rokid_ai_service"
    const val NOTIFICATION_ID = 1001
}
