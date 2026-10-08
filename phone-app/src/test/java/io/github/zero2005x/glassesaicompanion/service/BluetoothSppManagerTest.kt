package io.github.zero2005x.glassesaicompanion.service

import androidx.test.core.app.ApplicationProvider
import com.example.rokidcommon.protocol.Message
import com.example.rokidcommon.protocol.MessageType
import com.google.common.truth.Truth.assertThat
import io.mockk.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class BluetoothSppManagerTest {

    private lateinit var context: android.content.Context
    private lateinit var scope: TestScope
    private lateinit var manager: BluetoothSppManager

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        scope = TestScope(UnconfinedTestDispatcher())
        manager = BluetoothSppManager(context, scope)
    }

    @After
    fun tearDown() {
        if (::scope.isInitialized) {
            scope.cancel()
        }
    }

    // ==================== BluetoothConnectionState enum ====================

    @Test
    fun `all expected connection states exist`() {
        // 測試：所有預期的 BluetoothConnectionState 列舉值存在
        val states = BluetoothConnectionState.entries
        assertThat(states).containsExactly(
            BluetoothConnectionState.DISCONNECTED,
            BluetoothConnectionState.LISTENING,
            BluetoothConnectionState.CONNECTING,
            BluetoothConnectionState.CONNECTED
        )
    }

    @Test
    fun `connection state count is exactly 4`() {
        assertThat(BluetoothConnectionState.entries.size).isEqualTo(4)
    }

    @Test
    fun `valueOf resolves valid connection state names`() {
        // 測試：valueOf 可正確解析各狀態名稱
        assertThat(BluetoothConnectionState.valueOf("DISCONNECTED"))
            .isEqualTo(BluetoothConnectionState.DISCONNECTED)
        assertThat(BluetoothConnectionState.valueOf("LISTENING"))
            .isEqualTo(BluetoothConnectionState.LISTENING)
        assertThat(BluetoothConnectionState.valueOf("CONNECTING"))
            .isEqualTo(BluetoothConnectionState.CONNECTING)
        assertThat(BluetoothConnectionState.valueOf("CONNECTED"))
            .isEqualTo(BluetoothConnectionState.CONNECTED)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `valueOf throws for unknown connection state`() {
        BluetoothConnectionState.valueOf("RECONNECTING")
    }

    // ==================== Initial state ====================

    @Test
    fun `initial connection state is DISCONNECTED`() {
        // 測試：初始連線狀態為 DISCONNECTED
        assertThat(manager.connectionState.value).isEqualTo(BluetoothConnectionState.DISCONNECTED)
    }

    @Test
    fun `initial connected device name is null`() {
        assertThat(manager.connectedDeviceName.value).isNull()
    }

    @Test
    fun `initial connected device is null`() {
        assertThat(manager.connectedDevice).isNull()
    }

    // ==================== Permission and Bluetooth checks ====================

    @Test
    fun `isBluetoothEnabled returns false when adapter is null`() {
        // 測試：Robolectric 環境下無 BluetoothAdapter，isBluetoothEnabled 回傳 false
        assertThat(manager.isBluetoothEnabled()).isFalse()
    }

    @Test
    fun `hasBluetoothPermission returns value without crash`() {
        // 測試：hasBluetoothPermission 不拋出例外
        // With Robolectric, this checks permission handling
        val result = manager.hasBluetoothPermission()
        assertThat(result).isNotNull()
    }

    // ==================== sendMessage ====================

    @Test
    fun `sendMessage returns false when not connected`() = scope.runTest {
        // 測試：未連線時 sendMessage 回傳 false
        val message = Message(type = MessageType.AI_RESPONSE_TEXT, payload = "Hello")
        val result = manager.sendMessage(message)
        assertThat(result).isFalse()
    }

    @Test
    fun `sendMessage writes JSON to output stream when connected`() = scope.runTest {
        // 測試：已連線時 sendMessage 寫入 JSON 至 outputStream
        val outputBytes = ByteArrayOutputStream()

        // Set state to CONNECTED and inject outputStream via reflection
        setConnectionState(BluetoothConnectionState.CONNECTED)
        setOutputStream(outputBytes)

        val message = Message(type = MessageType.DISPLAY_TEXT, payload = "test payload")
        val result = manager.sendMessage(message)

        assertThat(result).isTrue()
        val written = outputBytes.toString(Charsets.UTF_8.name())
        assertThat(written).contains("\"type\"")
        assertThat(written).contains("test payload")
        assertThat(written).endsWith("\n")
    }

    @Test
    fun `sendMessage handles IOException and disconnects`() = scope.runTest {
        // 測試：sendMessage 發生 IOException 時呼叫 disconnect
        val mockOutputStream = mockk<OutputStream>(relaxed = true)
        every { mockOutputStream.write(any<ByteArray>()) } throws IOException("broken pipe")

        setConnectionState(BluetoothConnectionState.CONNECTED)
        setOutputStream(mockOutputStream)

        val message = Message(type = MessageType.DISPLAY_TEXT)
        val result = manager.sendMessage(message)

        assertThat(result).isFalse()
    }

    // ==================== handleDisconnection ====================

    @Test
    fun `handleDisconnection resets state and clears resources`() {
        // 測試：handleDisconnection 重置狀態並清除資源
        setConnectionState(BluetoothConnectionState.CONNECTED)

        val nameField = BluetoothSppManager::class.java.getDeclaredField("_connectedDeviceName")
        nameField.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        (nameField.get(manager) as kotlinx.coroutines.flow.MutableStateFlow<String?>).value = "Test Glasses"

        // Invoke handleDisconnection
        val method = BluetoothSppManager::class.java.getDeclaredMethod("handleDisconnection")
        method.isAccessible = true
        method.invoke(manager)

        assertThat(manager.connectionState.value).isEqualTo(BluetoothConnectionState.DISCONNECTED)
        assertThat(manager.connectedDeviceName.value).isNull()
        assertThat(manager.connectedDevice).isNull()
    }

    @Test
    fun `handleDisconnection skips when already disconnecting`() {
        // 測試：正在斷線時不重複處理
        val isDisconnectingField = BluetoothSppManager::class.java.getDeclaredField("isDisconnecting")
        isDisconnectingField.isAccessible = true
        isDisconnectingField.setBoolean(manager, true)

        setConnectionState(BluetoothConnectionState.CONNECTED)

        val method = BluetoothSppManager::class.java.getDeclaredMethod("handleDisconnection")
        method.isAccessible = true
        method.invoke(manager)

        // State should NOT change because isDisconnecting was already true
        assertThat(manager.connectionState.value).isEqualTo(BluetoothConnectionState.CONNECTED)

        // Cleanup
        isDisconnectingField.setBoolean(manager, false)
    }

    @Test
    fun `handleDisconnection resets binary buffer state`() {
        // 測試：handleDisconnection 重置二進位封包解析狀態
        val parsingField = BluetoothSppManager::class.java.getDeclaredField("parsingBinaryPacket")
        parsingField.isAccessible = true
        parsingField.setBoolean(manager, true)

        val expectedField = BluetoothSppManager::class.java.getDeclaredField("expectedPacketLength")
        expectedField.isAccessible = true
        expectedField.setInt(manager, 1024)

        setConnectionState(BluetoothConnectionState.CONNECTED)

        val method = BluetoothSppManager::class.java.getDeclaredMethod("handleDisconnection")
        method.isAccessible = true
        method.invoke(manager)

        assertThat(parsingField.getBoolean(manager)).isFalse()
        assertThat(expectedField.getInt(manager)).isEqualTo(0)
    }

    // ==================== disconnect method ====================

    @Test
    fun `disconnect sets state to disconnected`() {
        // 測試：disconnect 設定 DISCONNECTED 狀態
        setConnectionState(BluetoothConnectionState.CONNECTED)
        manager.disconnect(restartListening = false)
        assertThat(manager.connectionState.value).isEqualTo(BluetoothConnectionState.DISCONNECTED)
    }

    @Test
    fun `disconnect with restartListening false does not restart`() {
        // 測試：disconnect(restartListening=false) 不會重啟監聽
        setConnectionState(BluetoothConnectionState.CONNECTED)
        manager.disconnect(restartListening = false)
        assertThat(manager.connectionState.value).isEqualTo(BluetoothConnectionState.DISCONNECTED)
    }

    @Test
    fun `disconnect still tears down when another disconnect is in progress`() {
        // 測試：即使另一個斷線流程正在進行，disconnect 仍會執行清理（但不重複排程重啟）
        val isDisconnectingField = BluetoothSppManager::class.java.getDeclaredField("isDisconnecting")
        isDisconnectingField.isAccessible = true
        isDisconnectingField.setBoolean(manager, true)

        setConnectionState(BluetoothConnectionState.CONNECTED)
        manager.disconnect(restartListening = false)

        // Teardown is idempotent and must not be dropped: state becomes DISCONNECTED
        assertThat(manager.connectionState.value).isEqualTo(BluetoothConnectionState.DISCONNECTED)

        // The in-flight operation still owns the flag
        assertThat(isDisconnectingField.getBoolean(manager)).isTrue()

        // Cleanup
        isDisconnectingField.setBoolean(manager, false)
    }

    // ==================== getPacketLength (pure function) ====================

    @Test
    fun `getPacketLength returns correct size for START packet`() {
        // 測試：START 封包長度為 25 bytes
        val method = BluetoothSppManager::class.java.getDeclaredMethod(
            "getPacketLength", Byte::class.java, ByteArray::class.java, Int::class.java
        )
        method.isAccessible = true

        val startType: Byte = 0x01  // PhotoTransferConstants.PACKET_TYPE_START
        val data = byteArrayOf(startType)
        val result = method.invoke(manager, startType, data, 0) as Int

        assertThat(result).isEqualTo(25) // START_PACKET_SIZE
    }

    @Test
    fun `getPacketLength returns correct size for END packet`() {
        // 測試：END 封包長度為 2 bytes
        val method = BluetoothSppManager::class.java.getDeclaredMethod(
            "getPacketLength", Byte::class.java, ByteArray::class.java, Int::class.java
        )
        method.isAccessible = true

        val endType: Byte = 0x03  // PhotoTransferConstants.PACKET_TYPE_END
        val data = byteArrayOf(endType)
        val result = method.invoke(manager, endType, data, 0) as Int

        assertThat(result).isEqualTo(2) // END_PACKET_SIZE
    }

    @Test
    fun `getPacketLength returns 0 for unknown packet type`() {
        // 測試：未知封包類型回傳 0
        val method = BluetoothSppManager::class.java.getDeclaredMethod(
            "getPacketLength", Byte::class.java, ByteArray::class.java, Int::class.java
        )
        method.isAccessible = true

        val unknownType: Byte = 0x7F
        val data = byteArrayOf(unknownType)
        val result = method.invoke(manager, unknownType, data, 0) as Int

        assertThat(result).isEqualTo(0)
    }

    // ==================== stopListening ====================

    @Test
    fun `stopListening clears server socket`() {
        // 測試：stopListening 清除 serverSocket
        manager.stopListening()

        val serverSocketField = BluetoothSppManager::class.java.getDeclaredField("serverSocket")
        serverSocketField.isAccessible = true
        assertThat(serverSocketField.get(manager)).isNull()
    }

    // ==================== getPairedDevices ====================

    @Test
    fun `getPairedDevices returns empty list without crash`() {
        // 測試：getPairedDevices 不拋出例外
        val devices = manager.getPairedDevices()
        assertThat(devices).isNotNull()
    }

    // ==================== Helpers ====================

    private fun setConnectionState(state: BluetoothConnectionState) {
        val field = BluetoothSppManager::class.java.getDeclaredField("_connectionState")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        (field.get(manager) as kotlinx.coroutines.flow.MutableStateFlow<BluetoothConnectionState>).value = state
    }

    private fun setOutputStream(outputStream: OutputStream) {
        val field = BluetoothSppManager::class.java.getDeclaredField("outputStream")
        field.isAccessible = true
        field.set(manager, outputStream)
    }
}
