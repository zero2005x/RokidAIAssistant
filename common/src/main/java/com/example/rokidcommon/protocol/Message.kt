package com.example.rokidcommon.protocol

import android.util.Base64
import org.json.JSONObject
import java.nio.ByteBuffer
import java.util.UUID

/**
 * Communication message base class
 * 
 * binaryData uses Base64 encoding for transmission to avoid performance issues with JSON serialization of large binary data
 */
data class Message(
    val id: String = UUID.randomUUID().toString(),
    val type: MessageType,
    val timestamp: Long = System.currentTimeMillis(),
    val payload: String? = null,
    val binaryData: ByteArray? = null
) {
    companion object {
        
        /**
         * Parse message from JSON string
         * binaryData uses Base64 decoding
         */
        fun fromJson(json: String): Message? {
            return try {
                val jsonObj = JSONObject(json)
                val typeCode = jsonObj.getInt("type")
                val type = MessageType.fromCode(typeCode) ?: return null
                
                val id = jsonObj.optString("id", UUID.randomUUID().toString())
                val timestamp = jsonObj.optLong("timestamp", System.currentTimeMillis())
                val payload = if (jsonObj.has("payload")) jsonObj.getString("payload") else null
                
                // Base64 decode binaryData
                val binaryData = if (jsonObj.has("binaryData")) {
                    try {
                        Base64.decode(jsonObj.getString("binaryData"), Base64.NO_WRAP)
                    } catch (e: Exception) {
                        null
                    }
                } else null
                
                Message(
                    id = id,
                    type = type,
                    timestamp = timestamp,
                    payload = payload,
                    binaryData = binaryData
                )
            } catch (e: Exception) {
                null
            }
        }
        
        /**
         * Parse message from binary data (for audio transmission)
         */
        fun fromBytes(bytes: ByteArray): Message? {
            return try {
                if (bytes.size < 8) return null
                
                val buffer = ByteBuffer.wrap(bytes)
                val typeCode = buffer.getInt()
                val payloadLength = buffer.getInt()
                
                val type = MessageType.fromCode(typeCode) ?: return null
                
                val binaryPayload = if (payloadLength > 0 && bytes.size >= 8 + payloadLength) {
                    ByteArray(payloadLength).also { buffer.get(it) }
                } else null
                
                Message(
                    type = type,
                    binaryData = binaryPayload
                )
            } catch (e: Exception) {
                null
            }
        }
        
        // Convenience creation methods
        fun handshake(deviceName: String) = Message(
            type = MessageType.HANDSHAKE,
            payload = deviceName
        )
        
        fun heartbeat() = Message(type = MessageType.HEARTBEAT)
        
        fun voiceStart() = Message(type = MessageType.VOICE_START)
        
        fun voiceData(audioData: ByteArray) = Message(
            type = MessageType.VOICE_DATA,
            binaryData = audioData
        )
        
        fun voiceEnd() = Message(type = MessageType.VOICE_END)
        
        fun aiProcessing(status: String) = Message(
            type = MessageType.AI_PROCESSING,
            payload = status
        )
        
        fun aiResponseText(text: String) = Message(
            type = MessageType.AI_RESPONSE_TEXT,
            payload = text
        )
        
        fun aiResponseTts(audioData: ByteArray) = Message(
            type = MessageType.AI_RESPONSE_TTS,
            binaryData = audioData
        )
        
        fun aiError(error: String) = Message(
            type = MessageType.AI_ERROR,
            payload = error
        )
        
        fun displayText(text: String) = Message(
            type = MessageType.DISPLAY_TEXT,
            payload = text
        )
        
        fun displayClear() = Message(type = MessageType.DISPLAY_CLEAR)
    }
    
    /**
     * Convert to JSON string
     * binaryData uses Base64 encoding
     */
    fun toJson(): String {
        val jsonObj = JSONObject()
        jsonObj.put("id", id)
        jsonObj.put("type", type.code)
        jsonObj.put("timestamp", timestamp)
        
        if (payload != null) {
            jsonObj.put("payload", payload)
        }
        
        if (binaryData != null) {
            // Base64 encode binaryData
            jsonObj.put("binaryData", Base64.encodeToString(binaryData, Base64.NO_WRAP))
        }
        
        return jsonObj.toString()
    }
    
    /**
     * Convert to binary data (for audio transmission)
     */
    fun toBytes(): ByteArray {
        val payloadBytes = binaryData ?: ByteArray(0)
        val buffer = ByteBuffer.allocate(8 + payloadBytes.size)
        buffer.putInt(type.code)
        buffer.putInt(payloadBytes.size)
        buffer.put(payloadBytes)
        return buffer.array()
    }
    
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Message) return false
        return id == other.id && type == other.type
    }
    
    override fun hashCode(): Int {
        return id.hashCode()
    }
}
