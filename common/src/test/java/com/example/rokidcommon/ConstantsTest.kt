package com.example.rokidcommon

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Unit tests for Constants object.
 * Verifies that shared constants have expected values.
 */
class ConstantsTest {

    @Test
    fun `Bluetooth UUIDs are not null`() {
        // Test: BT UUIDs are defined
        assertThat(Constants.BT_SERVICE_UUID).isNotNull()
        assertThat(Constants.BT_CHARACTERISTIC_UUID).isNotNull()
    }

    @Test
    fun `Bluetooth UUIDs are distinct`() {
        // Test: service and characteristic UUIDs are different
        assertThat(Constants.BT_SERVICE_UUID).isNotEqualTo(Constants.BT_CHARACTERISTIC_UUID)
    }

    @Test
    fun `connection timing constants are positive`() {
        // Test: connection-related timeouts
        assertThat(Constants.HEARTBEAT_INTERVAL_MS).isGreaterThan(0)
        assertThat(Constants.CONNECTION_TIMEOUT_MS).isGreaterThan(0)
        assertThat(Constants.RECONNECT_DELAY_MS).isGreaterThan(0)
        assertThat(Constants.MAX_RECONNECT_ATTEMPTS).isGreaterThan(0)
    }

    @Test
    fun `audio settings have standard values`() {
        // Test: audio format constants for 16kHz 16-bit mono
        assertThat(Constants.AUDIO_SAMPLE_RATE).isEqualTo(16000)
        assertThat(Constants.AUDIO_BUFFER_SIZE).isGreaterThan(0)
    }

    @Test
    fun `AI settings have reasonable values`() {
        // Test: AI limits
        assertThat(Constants.AI_MAX_RESPONSE_LENGTH).isGreaterThan(0)
        assertThat(Constants.AI_TIMEOUT_MS).isGreaterThan(0)
    }

    @Test
    fun `display settings are positive`() {
        // Test: display constraints
        assertThat(Constants.DISPLAY_MAX_LINES).isGreaterThan(0)
        assertThat(Constants.DISPLAY_FADE_DURATION_MS).isGreaterThan(0)
    }

    @Test
    fun `notification constants are non-blank`() {
        // Test: notification channel is defined
        assertThat(Constants.NOTIFICATION_CHANNEL_ID).isNotEmpty()
        assertThat(Constants.NOTIFICATION_ID).isGreaterThan(0)
    }
}
