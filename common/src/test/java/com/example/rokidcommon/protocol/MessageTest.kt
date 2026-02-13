package com.example.rokidcommon.protocol

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.nio.ByteBuffer

/**
 * Unit tests for Message data class.
 * Uses Robolectric because Message.toJson/fromJson uses android.util.Base64.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class MessageTest {

    // ==================== Factory methods ====================

    @Test
    fun `handshake creates HANDSHAKE message with device name`() {
        // Test: Message.handshake() factory
        val msg = Message.handshake("TestDevice")
        assertThat(msg.type).isEqualTo(MessageType.HANDSHAKE)
        assertThat(msg.payload).isEqualTo("TestDevice")
        assertThat(msg.binaryData).isNull()
        assertThat(msg.id).isNotEmpty()
        assertThat(msg.timestamp).isGreaterThan(0)
    }

    @Test
    fun `heartbeat creates HEARTBEAT message`() {
        // Test: Message.heartbeat() factory
        val msg = Message.heartbeat()
        assertThat(msg.type).isEqualTo(MessageType.HEARTBEAT)
        assertThat(msg.payload).isNull()
        assertThat(msg.binaryData).isNull()
    }

    @Test
    fun `voiceStart creates VOICE_START message`() {
        // 測試：Message.voiceStart() 工廠方法
        val msg = Message.voiceStart()
        assertThat(msg.type).isEqualTo(MessageType.VOICE_START)
    }

    @Test
    fun `aiResponse creates AI_RESPONSE_TEXT message`() {
        // 測試：Message.aiResponse(text) 工廠方法
        val msg = Message.aiResponse("hello")
        assertThat(msg.type).isEqualTo(MessageType.AI_RESPONSE_TEXT)
        assertThat(msg.payload).isEqualTo("hello")
    }

    @Test
    fun `photoData creates PHOTO_DATA message with binary data`() {
        // 測試：Message.photoData(bytes) 工廠方法
        val bytes = byteArrayOf(9, 8, 7)
        val msg = Message.photoData(bytes)
        assertThat(msg.type).isEqualTo(MessageType.PHOTO_DATA)
        assertThat(msg.binaryData).isEqualTo(bytes)
    }

    @Test
    fun `voiceData creates VOICE_DATA message with binary data`() {
        // Test: Message.voiceData() factory
        val audio = byteArrayOf(1, 2, 3, 4, 5)
        val msg = Message.voiceData(audio)
        assertThat(msg.type).isEqualTo(MessageType.VOICE_DATA)
        assertThat(msg.binaryData).isEqualTo(audio)
    }

    @Test
    fun `voiceEnd creates VOICE_END message`() {
        // Test: Message.voiceEnd() factory
        val msg = Message.voiceEnd()
        assertThat(msg.type).isEqualTo(MessageType.VOICE_END)
    }

    @Test
    fun `aiProcessing creates AI_PROCESSING message with status`() {
        // Test: Message.aiProcessing() factory
        val msg = Message.aiProcessing("Thinking...")
        assertThat(msg.type).isEqualTo(MessageType.AI_PROCESSING)
        assertThat(msg.payload).isEqualTo("Thinking...")
    }

    @Test
    fun `aiResponseText creates AI_RESPONSE_TEXT message`() {
        // Test: Message.aiResponseText() factory
        val msg = Message.aiResponseText("Hello from AI")
        assertThat(msg.type).isEqualTo(MessageType.AI_RESPONSE_TEXT)
        assertThat(msg.payload).isEqualTo("Hello from AI")
    }

    @Test
    fun `aiResponseTts creates AI_RESPONSE_TTS message with audio`() {
        // Test: Message.aiResponseTts() factory
        val ttsAudio = byteArrayOf(10, 20, 30)
        val msg = Message.aiResponseTts(ttsAudio)
        assertThat(msg.type).isEqualTo(MessageType.AI_RESPONSE_TTS)
        assertThat(msg.binaryData).isEqualTo(ttsAudio)
    }

    @Test
    fun `aiError creates AI_ERROR message`() {
        // Test: Message.aiError() factory
        val msg = Message.aiError("Something failed")
        assertThat(msg.type).isEqualTo(MessageType.AI_ERROR)
        assertThat(msg.payload).isEqualTo("Something failed")
    }

    @Test
    fun `displayText creates DISPLAY_TEXT message`() {
        // Test: Message.displayText() factory
        val msg = Message.displayText("Show this")
        assertThat(msg.type).isEqualTo(MessageType.DISPLAY_TEXT)
        assertThat(msg.payload).isEqualTo("Show this")
    }

    @Test
    fun `displayClear creates DISPLAY_CLEAR message`() {
        // Test: Message.displayClear() factory
        val msg = Message.displayClear()
        assertThat(msg.type).isEqualTo(MessageType.DISPLAY_CLEAR)
    }

    // ==================== JSON serialization ====================

    @Test
    fun `toJson includes id type and timestamp`() {
        // Test: toJson() basic fields
        val msg = Message.heartbeat()
        val json = msg.toJson()
        assertThat(json).contains("\"id\"")
        assertThat(json).contains("\"type\"")
        assertThat(json).contains("\"timestamp\"")
    }

    @Test
    fun `toJson and fromJson roundtrip for text payload`() {
        // Test: JSON roundtrip with payload only
        val original = Message.aiResponseText("Hello 你好 🌍")
        val json = original.toJson()
        val restored = Message.fromJson(json)

        assertThat(restored).isNotNull()
        assertThat(restored!!.type).isEqualTo(MessageType.AI_RESPONSE_TEXT)
        assertThat(restored.payload).isEqualTo("Hello 你好 🌍")
        assertThat(restored.id).isEqualTo(original.id)
    }

    @Test
    fun `toJson and fromJson roundtrip for binary data`() {
        // Test: JSON roundtrip with binaryData (Base64 encoded)
        val audioData = byteArrayOf(0, 1, 127, -128, -1)
        val original = Message.voiceData(audioData)
        val json = original.toJson()
        val restored = Message.fromJson(json)

        assertThat(restored).isNotNull()
        assertThat(restored!!.type).isEqualTo(MessageType.VOICE_DATA)
        assertThat(restored.binaryData).isEqualTo(audioData)
    }

    @Test
    fun `toJson and fromJson roundtrip for message with both payload and binary`() {
        // Test: message with both payload and binaryData
        val original = Message(
            type = MessageType.AI_RESPONSE_TTS,
            payload = "audio description",
            binaryData = byteArrayOf(10, 20, 30)
        )
        val json = original.toJson()
        val restored = Message.fromJson(json)

        assertThat(restored).isNotNull()
        assertThat(restored!!.payload).isEqualTo("audio description")
        assertThat(restored.binaryData).isEqualTo(byteArrayOf(10, 20, 30))
    }

    @Test
    fun `fromJson returns null for invalid JSON`() {
        // Test: fromJson graceful error handling
        assertThat(Message.fromJson("not valid json")).isNull()
        assertThat(Message.fromJson("")).isNull()
        assertThat(Message.fromJson("{}")).isNull() // missing type field
    }

    @Test
    fun `fromJson returns null for unknown type code`() {
        // Test: fromJson with unrecognized type code
        val json = """{"id":"x","type":9999,"timestamp":0}"""
        assertThat(Message.fromJson(json)).isNull()
    }

    @Test
    fun `toJson omits payload when null`() {
        // Test: null payload not serialized
        val msg = Message.heartbeat()
        val json = msg.toJson()
        assertThat(json).doesNotContain("\"payload\"")
    }

    @Test
    fun `toJson omits binaryData when null`() {
        // Test: null binaryData not serialized
        val msg = Message.heartbeat()
        val json = msg.toJson()
        assertThat(json).doesNotContain("\"binaryData\"")
    }

    // ==================== Binary serialization ====================

    @Test
    fun `toBytes and fromBytes roundtrip for binary data`() {
        // Test: binary roundtrip preserves type and data
        val audio = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)
        val original = Message.voiceData(audio)
        val bytes = original.toBytes()
        val restored = Message.fromBytes(bytes)

        assertThat(restored).isNotNull()
        assertThat(restored!!.type).isEqualTo(MessageType.VOICE_DATA)
        assertThat(restored.binaryData).isEqualTo(audio)
    }

    @Test
    fun `toBytes encodes type code and payload length correctly`() {
        // Test: binary format is [4-byte type code][4-byte length][payload]
        val data = byteArrayOf(10, 20)
        val msg = Message(type = MessageType.VOICE_DATA, binaryData = data)
        val bytes = msg.toBytes()

        val buffer = ByteBuffer.wrap(bytes)
        assertThat(buffer.getInt()).isEqualTo(0x11) // VOICE_DATA code
        assertThat(buffer.getInt()).isEqualTo(2) // payload length
    }

    @Test
    fun `toBytes with no binary data produces 8-byte header only`() {
        // Test: message without binaryData produces [type][0]
        val msg = Message.heartbeat()
        val bytes = msg.toBytes()
        assertThat(bytes.size).isEqualTo(8)

        val buffer = ByteBuffer.wrap(bytes)
        assertThat(buffer.getInt()).isEqualTo(0x02) // HEARTBEAT code
        assertThat(buffer.getInt()).isEqualTo(0) // payload length = 0
    }

    @Test
    fun `fromBytes returns null for data too short`() {
        // Test: fewer than 8 bytes should return null
        assertThat(Message.fromBytes(ByteArray(0))).isNull()
        assertThat(Message.fromBytes(ByteArray(4))).isNull()
        assertThat(Message.fromBytes(ByteArray(7))).isNull()
    }

    @Test
    fun `fromBytes returns null for unknown type code`() {
        // Test: unknown type code in binary
        val buffer = ByteBuffer.allocate(8)
        buffer.putInt(9999)
        buffer.putInt(0)
        assertThat(Message.fromBytes(buffer.array())).isNull()
    }

    // ==================== Edge cases ====================

    @Test
    fun `empty payload string is preserved`() {
        // 測試：空字串 payload 序列化/反序列化後保持不變
        val msg = Message(type = MessageType.DISPLAY_TEXT, payload = "")
        val json = msg.toJson()
        val restored = Message.fromJson(json)
        assertThat(restored!!.payload).isEmpty()
    }

    @Test
    fun `very long payload is preserved`() {
        // 測試：超長文字 payload 序列化/反序列化後保持不變
        val longText = "A".repeat(10_000)
        val msg = Message.aiResponseText(longText)
        val json = msg.toJson()
        val restored = Message.fromJson(json)
        assertThat(restored!!.payload).isEqualTo(longText)
    }

    @Test
    fun `special characters in payload are preserved`() {
        // 測試：特殊字元與 emoji 序列化/反序列化後保持不變
        val special = "Hello \"world\" \n\ttab 'quotes' 日本語 🤖🎉"
        val msg = Message.aiResponseText(special)
        val json = msg.toJson()
        val restored = Message.fromJson(json)
        assertThat(restored!!.payload).isEqualTo(special)
    }

    @Test
    fun `large binary data roundtrip via JSON`() {
        // Test: large binary data Base64 roundtrip
        val largeData = ByteArray(50_000) { (it % 256).toByte() }
        val msg = Message.voiceData(largeData)
        val json = msg.toJson()
        val restored = Message.fromJson(json)
        assertThat(restored!!.binaryData).isEqualTo(largeData)
    }

    // ==================== equals / hashCode ====================

    @Test
    fun `equals is based on id and type only`() {
        // Test: custom equality logic
        val msg1 = Message(id = "same-id", type = MessageType.HEARTBEAT, payload = "a")
        val msg2 = Message(id = "same-id", type = MessageType.HEARTBEAT, payload = "b")
        val msg3 = Message(id = "diff-id", type = MessageType.HEARTBEAT, payload = "a")

        assertThat(msg1).isEqualTo(msg2) // same id + type, different payload
        assertThat(msg1).isNotEqualTo(msg3) // different id
    }

    @Test
    fun `hashCode is based on id`() {
        // Test: hashCode consistency
        val msg1 = Message(id = "x", type = MessageType.HEARTBEAT)
        val msg2 = Message(id = "x", type = MessageType.HEARTBEAT, payload = "extra")
        assertThat(msg1.hashCode()).isEqualTo(msg2.hashCode())
    }

    // ==================== Full voice flow simulation ====================

    @Test
    fun `simulated voice flow produces correct message sequence`() {
        // Test: VOICE_START -> VOICE_DATA chunks -> VOICE_END -> AI response
        val messages = listOf(
            Message.voiceStart(),
            Message.voiceData(byteArrayOf(1, 2, 3)),
            Message.voiceData(byteArrayOf(4, 5, 6)),
            Message.voiceEnd(),
            Message.aiProcessing("Processing..."),
            Message.aiResponseText("你好世界")
        )

        assertThat(messages.map { it.type }).containsExactly(
            MessageType.VOICE_START,
            MessageType.VOICE_DATA,
            MessageType.VOICE_DATA,
            MessageType.VOICE_END,
            MessageType.AI_PROCESSING,
            MessageType.AI_RESPONSE_TEXT
        ).inOrder()
    }
}
