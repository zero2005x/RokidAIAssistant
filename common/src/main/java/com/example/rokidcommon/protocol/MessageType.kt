package com.example.rokidcommon.protocol

/**
 * Message types for communication between phone and glasses
 */
enum class MessageType(val code: Int) {
    // Connection management (0x00-0x0F)
    HANDSHAKE(0x00),
    HANDSHAKE_ACK(0x01),
    HEARTBEAT(0x02),
    HEARTBEAT_ACK(0x03),
    DISCONNECT(0x0F),
    
    // Voice related (0x10-0x1F)
    VOICE_START(0x10),           // Glasses -> Phone: Start recording
    VOICE_DATA(0x11),            // Glasses -> Phone: Audio data
    VOICE_END(0x12),             // Glasses -> Phone: End recording
    VOICE_CANCEL(0x13),          // Glasses -> Phone: Cancel recording
    
    // AI processing (0x20-0x2F)
    AI_PROCESSING(0x20),         // Phone -> Glasses: Processing
    AI_RESPONSE_TEXT(0x21),      // Phone -> Glasses: Text response
    AI_RESPONSE_TTS(0x22),       // Phone -> Glasses: TTS audio
    USER_TRANSCRIPT(0x23),       // Internal: User speech recognition result (for syncing to phone UI)
    AI_ERROR(0x2F),              // Phone -> Glasses: Error message
    
    // Display control (0x30-0x3F)
    DISPLAY_TEXT(0x30),          // Phone -> Glasses: Display text
    DISPLAY_CLEAR(0x31),         // Phone -> Glasses: Clear display
    DISPLAY_STATUS(0x32),        // Phone -> Glasses: Status update
    
    // System control (0xF0-0xFF)
    SYSTEM_STATUS(0xF0),         // Bidirectional: System status
    SYSTEM_CONFIG(0xF1),         // Phone -> Glasses: Config update
    SYSTEM_ERROR(0xFF);          // Bidirectional: System error
    
    companion object {
        fun fromCode(code: Int): MessageType? = entries.find { it.code == code }
    }
}
