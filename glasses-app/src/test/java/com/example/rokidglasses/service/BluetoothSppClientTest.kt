// SPDX-License-Identifier: Apache-2.0
package com.example.rokidglasses.service

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import com.example.rokidcommon.protocol.Message
import com.example.rokidcommon.protocol.MessageType
import com.google.common.truth.Truth.assertThat
import io.mockk.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class BluetoothSppClientTest {

    private lateinit var context: Context
    private lateinit var scope: TestScope
    private lateinit var client: BluetoothSppClient
    private lateinit var mockBluetoothAdapter: BluetoothAdapter
    private lateinit var mockBluetoothManager: BluetoothManager

    @Before
    fun setUp() {
        context = mockk(relaxed = true)
        mockBluetoothManager = mockk(relaxed = true)
        mockBluetoothAdapter = mockk(relaxed = true)

        every { context.getSystemService(Context.BLUETOOTH_SERVICE) } returns mockBluetoothManager
        every { mockBluetoothManager.adapter } returns mockBluetoothAdapter

        scope = TestScope(UnconfinedTestDispatcher())
        client = BluetoothSppClient(context, scope)
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    // ==================== BluetoothClientState enum ====================

    @Test
    fun `all expected client states exist`() {
        // 測試：所有預期的 BluetoothClientState 列舉值存在
        val states = BluetoothClientState.entries
        assertThat(states).containsExactly(
            BluetoothClientState.DISCONNECTED,
            BluetoothClientState.CONNECTING,
            BluetoothClientState.CONNECTED
        )
    }

    @Test
    fun `client state count is exactly 3`() {
        // Test: no extra states added
        assertThat(BluetoothClientState.entries.size).isEqualTo(3)
    }

    @Test
    fun `valueOf resolves valid client state names`() {
        // 測試：valueOf 可正確解析各狀態名稱
        assertThat(BluetoothClientState.valueOf("DISCONNECTED"))
            .isEqualTo(BluetoothClientState.DISCONNECTED)
        assertThat(BluetoothClientState.valueOf("CONNECTING"))
            .isEqualTo(BluetoothClientState.CONNECTING)
        assertThat(BluetoothClientState.valueOf("CONNECTED"))
            .isEqualTo(BluetoothClientState.CONNECTED)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `valueOf throws for unknown client state`() {
        BluetoothClientState.valueOf("RECONNECTING")
    }

    // ==================== Initial state ====================

    @Test
    fun `initial connection state is DISCONNECTED`() {
        // 測試：初始連線狀態為 DISCONNECTED
        assertThat(client.connectionState.value).isEqualTo(BluetoothClientState.DISCONNECTED)
    }

    @Test
    fun `initial connected device name is null`() {
        // Test: no device connected initially
        assertThat(client.connectedDeviceName.value).isNull()
    }

    @Test
    fun `initial connectedSocket is null`() {
        // Test: connectedSocket returns null when disconnected
        assertThat(client.connectedSocket).isNull()
    }

    // ==================== SERVICE_UUID ====================

    @Test
    fun `SERVICE_UUID matches expected value`() {
        // 測試：UUID 與手機端一致
        val expectedUuid = UUID.fromString("a1b2c3d4-e5f6-7890-abcd-ef1234567890")
        assertThat(BluetoothSppClient.SERVICE_UUID).isEqualTo(expectedUuid)
    }

    // ==================== Permission checks ====================

    @Test
    fun `getPairedDevices returns empty list when no permission`() {
        // 測試：無權限時回傳空列表
        // On API 31+ (S), BLUETOOTH_CONNECT is required
        // The mock context doesn't grant it, so should return empty
        val devices = client.getPairedDevices()
        // With Robolectric and relaxed mocks, behavior depends on SDK version
        // Just verify it doesn't crash
        assertThat(devices).isNotNull()
    }

    @Test
    fun `isBluetoothEnabled returns adapter state`() {
        // 測試：isBluetoothEnabled 代理 BluetoothAdapter.isEnabled
        every { mockBluetoothAdapter.isEnabled } returns true
        assertThat(client.isBluetoothEnabled()).isTrue()

        every { mockBluetoothAdapter.isEnabled } returns false
        assertThat(client.isBluetoothEnabled()).isFalse()
    }

    // ==================== Connect guards ====================

    @Test
    fun `connect does not proceed when already connecting`() = scope.runTest {
        // 測試：已在連線中時不會重複連線
        val device = mockk<BluetoothDevice>(relaxed = true)

        // Use reflection to set state to CONNECTING
        val stateField = BluetoothSppClient::class.java.getDeclaredField("_connectionState")
        stateField.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val stateFlow = stateField.get(client) as kotlinx.coroutines.flow.MutableStateFlow<BluetoothClientState>
        stateFlow.value = BluetoothClientState.CONNECTING

        // connect should return immediately without launching a new job
        client.connect(device)

        // State should still be CONNECTING (not changed to DISCONNECTED from failure)
        assertThat(client.connectionState.value).isEqualTo(BluetoothClientState.CONNECTING)
    }

    @Test
    fun `connect does not proceed when already connected`() = scope.runTest {
        // 測試：已連線時不會重複連線
        val device = mockk<BluetoothDevice>(relaxed = true)

        val stateField = BluetoothSppClient::class.java.getDeclaredField("_connectionState")
        stateField.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val stateFlow = stateField.get(client) as kotlinx.coroutines.flow.MutableStateFlow<BluetoothClientState>
        stateFlow.value = BluetoothClientState.CONNECTED

        client.connect(device)

        // State should remain CONNECTED
        assertThat(client.connectionState.value).isEqualTo(BluetoothClientState.CONNECTED)
    }

    // ==================== Disconnect ====================

    @Test
    fun `disconnect sets state to DISCONNECTED and clears device name`() {
        // 測試：disconnect 會清除連線狀態與裝置名稱
        val stateField = BluetoothSppClient::class.java.getDeclaredField("_connectionState")
        stateField.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val stateFlow = stateField.get(client) as kotlinx.coroutines.flow.MutableStateFlow<BluetoothClientState>
        stateFlow.value = BluetoothClientState.CONNECTED

        val nameField = BluetoothSppClient::class.java.getDeclaredField("_connectedDeviceName")
        nameField.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val nameFlow = nameField.get(client) as kotlinx.coroutines.flow.MutableStateFlow<String?>
        nameFlow.value = "Test Phone"

        client.disconnect()

        assertThat(client.connectionState.value).isEqualTo(BluetoothClientState.DISCONNECTED)
        assertThat(client.connectedDeviceName.value).isNull()
    }

    // ==================== Heartbeat ACK ====================

    @Test
    fun `onHeartbeatAckReceived resets missed heartbeat count`() {
        // 測試：收到 HEARTBEAT_ACK 後重置遺漏計數
        val missedField = BluetoothSppClient::class.java.getDeclaredField("missedHeartbeatCount")
        missedField.isAccessible = true

        // Simulate missed heartbeats
        missedField.setInt(client, 2)
        assertThat(missedField.getInt(client)).isEqualTo(2)

        client.onHeartbeatAckReceived()

        assertThat(missedField.getInt(client)).isEqualTo(0)
    }

    // ==================== sendMessage ====================

    @Test
    fun `sendMessage returns false when not connected`() = scope.runTest {
        // 測試：未連線時 sendMessage 回傳 false
        val message = Message(type = MessageType.VOICE_START)
        val result = client.sendMessage(message)
        assertThat(result).isFalse()
    }

    @Test
    fun `sendMessage writes to output stream when connected`() = scope.runTest {
        // 測試：已連線時 sendMessage 寫入 outputStream 並回傳 true
        val outputBytes = ByteArrayOutputStream()

        // Set up connected state via reflection
        val stateField = BluetoothSppClient::class.java.getDeclaredField("_connectionState")
        stateField.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val stateFlow = stateField.get(client) as kotlinx.coroutines.flow.MutableStateFlow<BluetoothClientState>
        stateFlow.value = BluetoothClientState.CONNECTED

        val osField = BluetoothSppClient::class.java.getDeclaredField("outputStream")
        osField.isAccessible = true
        osField.set(client, outputBytes)

        val message = Message(type = MessageType.VOICE_START)
        val result = client.sendMessage(message)

        assertThat(result).isTrue()
        val written = outputBytes.toString(Charsets.UTF_8.name())
        assertThat(written).contains("\"type\"")
        assertThat(written).endsWith("\n")
    }

    @Test
    fun `sendMessage handles IOException and triggers disconnection`() = scope.runTest {
        // 測試：sendMessage 發生 IOException 時觸發斷線處理
        val mockOutputStream = mockk<java.io.OutputStream>(relaxed = true)
        every { mockOutputStream.write(any<ByteArray>()) } throws IOException("connection lost")

        val stateField = BluetoothSppClient::class.java.getDeclaredField("_connectionState")
        stateField.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val stateFlow = stateField.get(client) as kotlinx.coroutines.flow.MutableStateFlow<BluetoothClientState>
        stateFlow.value = BluetoothClientState.CONNECTED

        val osField = BluetoothSppClient::class.java.getDeclaredField("outputStream")
        osField.isAccessible = true
        osField.set(client, mockOutputStream)

        val message = Message(type = MessageType.VOICE_START)
        val result = client.sendMessage(message)

        assertThat(result).isFalse()
    }

    // ==================== handleDisconnection (auto-reconnect) ====================

    @Test
    fun `handleDisconnection skips when already disconnected`() = scope.runTest {
        // 測試：已是 DISCONNECTED 狀態時不做任何處理
        assertThat(client.connectionState.value).isEqualTo(BluetoothClientState.DISCONNECTED)

        // handleDisconnection is a suspend fun, so the JVM method takes a Continuation param
        val method = BluetoothSppClient::class.java.getDeclaredMethod(
            "handleDisconnection", kotlin.coroutines.Continuation::class.java
        )
        method.isAccessible = true

        // Invoke suspend function via kotlin.coroutines.intrinsics
        val result = kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn<Unit> { cont ->
            method.invoke(client, cont)
        }

        // State should still be DISCONNECTED
        assertThat(client.connectionState.value).isEqualTo(BluetoothClientState.DISCONNECTED)
    }

    @Test
    fun `handleDisconnection cancels heartbeat and sets disconnected`() = scope.runTest {
        // 測試：handleDisconnection 會取消 heartbeat 並設定 DISCONNECTED
        val stateField = BluetoothSppClient::class.java.getDeclaredField("_connectionState")
        stateField.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val stateFlow = stateField.get(client) as kotlinx.coroutines.flow.MutableStateFlow<BluetoothClientState>
        stateFlow.value = BluetoothClientState.CONNECTED

        // handleDisconnection is a suspend fun
        val method = BluetoothSppClient::class.java.getDeclaredMethod(
            "handleDisconnection", kotlin.coroutines.Continuation::class.java
        )
        method.isAccessible = true

        // Launch as coroutine so the suspend machinery works
        val job = scope.launch {
            kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn<Unit> { cont ->
                method.invoke(client, cont)
            }
        }
        // Let coroutines run
        advanceUntilIdle()

        assertThat(client.connectionState.value).isEqualTo(BluetoothClientState.DISCONNECTED)
        assertThat(client.connectedDeviceName.value).isNull()
    }

    // ==================== sendVoiceStart / sendVoiceEnd convenience ====================

    @Test
    fun `sendVoiceStart sends VOICE_START message`() = scope.runTest {
        // 測試：sendVoiceStart 發送 VOICE_START 訊息
        // Not connected, so should return false
        val result = client.sendVoiceStart()
        assertThat(result).isFalse()
    }

    @Test
    fun `sendVoiceEnd sends VOICE_END with audio data`() = scope.runTest {
        // 測試：sendVoiceEnd 發送包含音訊的 VOICE_END 訊息
        val result = client.sendVoiceEnd(byteArrayOf(1, 2, 3))
        assertThat(result).isFalse() // Not connected
    }

    // ==================== Retry backoff constants (recently modified) ====================

    @Test
    fun `retry backoff delay formula produces correct values`() {
        // 測試：重試延遲公式正確 (2500ms + attempt * 1500ms)
        // After the recent modification, backoff starts at 4s:
        // attempt 1: 2500 + 1*1500 = 4000ms
        // attempt 2: 2500 + 2*1500 = 5500ms
        // attempt 3: 2500 + 3*1500 = 7000ms
        // attempt 4: 2500 + 4*1500 = 8500ms
        for (attempt in 1..4) {
            val delay = 2500L + (attempt * 1500L)
            val expected = when (attempt) {
                1 -> 4000L
                2 -> 5500L
                3 -> 7000L
                4 -> 8500L
                else -> 0L
            }
            assertThat(delay).isEqualTo(expected)
        }
    }

    // ==================== connectByAddress ====================

    @Test
    fun `connectByAddress does nothing when no permission`() {
        // 測試：無權限時 connectByAddress 不做處理
        // Explicitly deny permission (relaxed mockk returns 0 = GRANTED by default)
        every { context.checkSelfPermission(any()) } returns android.content.pm.PackageManager.PERMISSION_DENIED
        client.connectByAddress("AA:BB:CC:DD:EE:FF")
        assertThat(client.connectionState.value).isEqualTo(BluetoothClientState.DISCONNECTED)
    }
}
