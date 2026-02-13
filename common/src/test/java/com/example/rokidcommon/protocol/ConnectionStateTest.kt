package com.example.rokidcommon.protocol

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Unit tests for ConnectionState, DeviceType, and DeviceInfo.
 * Verifies enum values and data class behavior.
 */
class ConnectionStateTest {

    // ==================== ConnectionState enum ====================

    @Test
    fun `all expected connection states exist`() {
        // 測試：連線狀態列舉值完整
        val states = ConnectionState.entries
        assertThat(states).containsExactly(
            ConnectionState.DISCONNECTED,
            ConnectionState.CONNECTING,
            ConnectionState.CONNECTED,
            ConnectionState.RECONNECTING,
            ConnectionState.ERROR
        )
    }

    @Test
    fun `connection state count is exactly 5`() {
        // Test: no extra states sneaked in
        assertThat(ConnectionState.entries.size).isEqualTo(5)
    }

    @Test
    fun `valueOf resolves valid state names`() {
        // 測試：valueOf 可正確解析所有狀態名稱
        assertThat(ConnectionState.valueOf("DISCONNECTED")).isEqualTo(ConnectionState.DISCONNECTED)
        assertThat(ConnectionState.valueOf("CONNECTING")).isEqualTo(ConnectionState.CONNECTING)
        assertThat(ConnectionState.valueOf("CONNECTED")).isEqualTo(ConnectionState.CONNECTED)
        assertThat(ConnectionState.valueOf("RECONNECTING")).isEqualTo(ConnectionState.RECONNECTING)
        assertThat(ConnectionState.valueOf("ERROR")).isEqualTo(ConnectionState.ERROR)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `valueOf throws for unknown state name`() {
        // Test: valueOf throws for unknown names
        ConnectionState.valueOf("UNKNOWN")
    }

    // ==================== DeviceType enum ====================

    @Test
    fun `all expected device types exist`() {
        // 測試：DeviceType 應包含 PHONE 與 GLASSES
        assertThat(DeviceType.entries).containsExactly(
            DeviceType.PHONE,
            DeviceType.GLASSES
        )
    }

    // ==================== DeviceInfo data class ====================

    @Test
    fun `DeviceInfo creates with required fields`() {
        // Test: DeviceInfo minimum construction
        val device = DeviceInfo(
            name = "Rokid Max Pro",
            address = "AA:BB:CC:DD:EE:FF",
            type = DeviceType.GLASSES
        )
        assertThat(device.name).isEqualTo("Rokid Max Pro")
        assertThat(device.address).isEqualTo("AA:BB:CC:DD:EE:FF")
        assertThat(device.type).isEqualTo(DeviceType.GLASSES)
    }

    @Test
    fun `DeviceInfo has correct default values`() {
        // Test: defaults for optional fields
        val device = DeviceInfo(
            name = "Test Phone",
            address = "11:22:33:44:55:66",
            type = DeviceType.PHONE
        )
        assertThat(device.batteryLevel).isEqualTo(-1)
        assertThat(device.firmwareVersion).isEmpty()
    }

    @Test
    fun `DeviceInfo creates with all fields`() {
        // Test: full construction with optional fields
        val device = DeviceInfo(
            name = "Rokid Air",
            address = "AA:BB:CC:DD:EE:FF",
            type = DeviceType.GLASSES,
            batteryLevel = 85,
            firmwareVersion = "2.1.0"
        )
        assertThat(device.batteryLevel).isEqualTo(85)
        assertThat(device.firmwareVersion).isEqualTo("2.1.0")
    }

    @Test
    fun `DeviceInfo equality is based on all fields`() {
        // Test: data class equals/hashCode
        val device1 = DeviceInfo("Dev", "AA:BB:CC:DD:EE:FF", DeviceType.PHONE, 50, "1.0")
        val device2 = DeviceInfo("Dev", "AA:BB:CC:DD:EE:FF", DeviceType.PHONE, 50, "1.0")
        val device3 = DeviceInfo("Dev", "AA:BB:CC:DD:EE:FF", DeviceType.PHONE, 60, "1.0")

        assertThat(device1).isEqualTo(device2)
        assertThat(device1.hashCode()).isEqualTo(device2.hashCode())
        assertThat(device1).isNotEqualTo(device3)
    }

    @Test
    fun `DeviceInfo copy changes specific fields`() {
        // Test: data class copy
        val original = DeviceInfo("Dev", "AA:BB:CC:DD:EE:FF", DeviceType.PHONE)
        val updated = original.copy(batteryLevel = 42)

        assertThat(updated.name).isEqualTo("Dev")
        assertThat(updated.batteryLevel).isEqualTo(42)
    }
}
