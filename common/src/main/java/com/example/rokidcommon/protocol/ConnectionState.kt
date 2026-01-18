package com.example.rokidcommon.protocol

/**
 * Connection state
 */
enum class ConnectionState {
    DISCONNECTED,      // Not connected
    CONNECTING,        // Connecting
    CONNECTED,         // Connected
    RECONNECTING,      // Reconnecting
    ERROR              // Error
}

/**
 * Device information
 */
data class DeviceInfo(
    val name: String,
    val address: String,
    val type: DeviceType,
    val batteryLevel: Int = -1,
    val firmwareVersion: String = ""
)

/**
 * Device type
 */
enum class DeviceType {
    PHONE,
    GLASSES
}
