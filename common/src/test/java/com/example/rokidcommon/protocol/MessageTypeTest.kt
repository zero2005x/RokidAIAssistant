package com.example.rokidcommon.protocol

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Unit tests for MessageType enum.
 * Verifies enum values, codes, and the fromCode() lookup method.
 */
class MessageTypeTest {

    // ==================== Enum completeness ====================

    @Test
    fun `all expected connection management types exist`() {
        // Test: connection management message types (0x00-0x0F)
        assertThat(MessageType.HANDSHAKE.code).isEqualTo(0x00)
        assertThat(MessageType.HANDSHAKE_ACK.code).isEqualTo(0x01)
        assertThat(MessageType.HEARTBEAT.code).isEqualTo(0x02)
        assertThat(MessageType.HEARTBEAT_ACK.code).isEqualTo(0x03)
        assertThat(MessageType.DISCONNECT.code).isEqualTo(0x0F)
    }

    @Test
    fun `all expected voice types exist`() {
        // 測試：語音相關 MessageType 必須存在
        assertThat(MessageType.VOICE_START.code).isEqualTo(0x10)
        assertThat(MessageType.VOICE_DATA.code).isEqualTo(0x11)
        assertThat(MessageType.VOICE_END.code).isEqualTo(0x12)
        assertThat(MessageType.VOICE_CANCEL.code).isEqualTo(0x13)
        assertThat(MessageType.REMOTE_RECORD_START.code).isEqualTo(0x14)
        assertThat(MessageType.REMOTE_RECORD_STOP.code).isEqualTo(0x15)
    }

    @Test
    fun `all expected AI processing types exist`() {
        // 測試：AI 回應相關 MessageType 必須存在
        assertThat(MessageType.AI_PROCESSING.code).isEqualTo(0x20)
        assertThat(MessageType.AI_RESPONSE_TEXT.code).isEqualTo(0x21)
        assertThat(MessageType.AI_RESPONSE_TTS.code).isEqualTo(0x22)
        assertThat(MessageType.USER_TRANSCRIPT.code).isEqualTo(0x23)
        assertThat(MessageType.AI_ERROR.code).isEqualTo(0x2F)
    }

    @Test
    fun `all expected display control types exist`() {
        // Test: display control message types (0x30-0x3F)
        assertThat(MessageType.DISPLAY_TEXT.code).isEqualTo(0x30)
        assertThat(MessageType.DISPLAY_CLEAR.code).isEqualTo(0x31)
        assertThat(MessageType.DISPLAY_STATUS.code).isEqualTo(0x32)
    }

    @Test
    fun `all expected photo transfer types exist`() {
        // 測試：照片傳輸相關 MessageType 必須存在
        assertThat(MessageType.PHOTO_START.code).isEqualTo(0x40)
        assertThat(MessageType.PHOTO_DATA.code).isEqualTo(0x41)
        assertThat(MessageType.PHOTO_END.code).isEqualTo(0x42)
        assertThat(MessageType.PHOTO_ACK.code).isEqualTo(0x43)
        assertThat(MessageType.PHOTO_RETRY.code).isEqualTo(0x44)
        assertThat(MessageType.PHOTO_CANCEL.code).isEqualTo(0x45)
        assertThat(MessageType.PHOTO_ANALYSIS_RESULT.code).isEqualTo(0x46)
        assertThat(MessageType.CAPTURE_PHOTO.code).isEqualTo(0x47)
    }

    @Test
    fun `all expected live mode types exist`() {
        // Test: live mode message types (0x50-0x5F)
        assertThat(MessageType.LIVE_SESSION_START.code).isEqualTo(0x50)
        assertThat(MessageType.LIVE_SESSION_END.code).isEqualTo(0x51)
        assertThat(MessageType.LIVE_TRANSCRIPTION.code).isEqualTo(0x52)
        assertThat(MessageType.VIDEO_FRAME.code).isEqualTo(0x53)
    }

    @Test
    fun `all expected system control types exist`() {
        // Test: system control message types (0xF0-0xFF)
        assertThat(MessageType.SYSTEM_STATUS.code).isEqualTo(0xF0)
        assertThat(MessageType.SYSTEM_CONFIG.code).isEqualTo(0xF1)
        assertThat(MessageType.SYSTEM_ERROR.code).isEqualTo(0xFF)
    }

    // ==================== No duplicate codes ====================

    @Test
    fun `no duplicate message type codes exist`() {
        // 測試：所有 MessageType code 不能重複
        val codes = MessageType.entries.map { it.code }
        assertThat(codes).containsNoDuplicates()
    }

    @Test
    fun `no duplicate message type ordinals exist`() {
        // 測試：所有 MessageType ordinal 不能重複
        val ordinals = MessageType.entries.map { it.ordinal }
        assertThat(ordinals).containsNoDuplicates()
    }

    @Test
    fun `total enum count matches expected`() {
        // Test: total number of MessageType entries
        // Connection(5) + Voice(6) + AI(5) + Display(3) + Photo(8) + Live(4) + System(3) = 34
        assertThat(MessageType.entries.size).isEqualTo(34)
    }

    // ==================== fromCode() ====================

    @Test
    fun `fromCode returns correct type for valid codes`() {
        // Test: fromCode lookup for each known code
        assertThat(MessageType.fromCode(0x00)).isEqualTo(MessageType.HANDSHAKE)
        assertThat(MessageType.fromCode(0x10)).isEqualTo(MessageType.VOICE_START)
        assertThat(MessageType.fromCode(0x21)).isEqualTo(MessageType.AI_RESPONSE_TEXT)
        assertThat(MessageType.fromCode(0x41)).isEqualTo(MessageType.PHOTO_DATA)
        assertThat(MessageType.fromCode(0x50)).isEqualTo(MessageType.LIVE_SESSION_START)
        assertThat(MessageType.fromCode(0xFF)).isEqualTo(MessageType.SYSTEM_ERROR)
    }

    @Test
    fun `fromCode returns null for unknown codes`() {
        // Test: fromCode returns null for codes not in the enum
        assertThat(MessageType.fromCode(-1)).isNull()
        assertThat(MessageType.fromCode(0x05)).isNull()
        assertThat(MessageType.fromCode(0x99)).isNull()
        assertThat(MessageType.fromCode(Int.MAX_VALUE)).isNull()
    }

    @Test
    fun `fromCode handles boundary codes correctly`() {
        // Test: fromCode with edge case codes
        assertThat(MessageType.fromCode(0)).isEqualTo(MessageType.HANDSHAKE)
        assertThat(MessageType.fromCode(255)).isEqualTo(MessageType.SYSTEM_ERROR)
    }
}
