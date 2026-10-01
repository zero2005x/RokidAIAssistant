package com.example.rokidglasses.viewmodel

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.rokidcommon.protocol.ConnectionState
import com.example.rokidcommon.protocol.Message
import com.example.rokidcommon.protocol.MessageType
import com.example.rokidglasses.R
import com.example.rokidglasses.sdk.CxrServiceManager
import com.example.rokidglasses.sdk.UnifiedCameraManager
import com.example.rokidglasses.service.BluetoothClientState
import com.example.rokidglasses.service.BluetoothSppClient
import com.example.rokidglasses.testutil.returnsSuccess
import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockkConstructor
import io.mockk.mockkObject
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Message handling, display pagination and connection state for [GlassesViewModel].
 * The Bluetooth client, camera and CXR service are all substituted, so nothing here
 * touches a radio, a camera or the Rokid SDK.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class GlassesViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private val scope = TestScope(dispatcher)
    private val fromPhone = MutableSharedFlow<Message>(extraBufferCapacity = 32)
    private val bluetoothState = MutableStateFlow(BluetoothClientState.DISCONNECTED)
    private val deviceName = MutableStateFlow<String?>(null)
    private lateinit var context: Context
    private lateinit var model: GlassesViewModel

    private fun string(id: Int, vararg args: Any) = context.getString(id, *args)

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        context = ApplicationProvider.getApplicationContext()
        mockkObject(CxrServiceManager.Companion)
        // The Rokid CXR-S SDK is not on the unit test classpath.
        every { CxrServiceManager.isSdkAvailable() } returns false
        mockkConstructor(BluetoothSppClient::class, UnifiedCameraManager::class)
        every { anyConstructed<BluetoothSppClient>().messageFlow } returns fromPhone
        every { anyConstructed<BluetoothSppClient>().connectionState } returns bluetoothState
        every { anyConstructed<BluetoothSppClient>().connectedDeviceName } returns deviceName
        every { anyConstructed<BluetoothSppClient>().getPairedDevices() } returns emptyList()
        coEvery { anyConstructed<BluetoothSppClient>().sendMessage(any()) } returns true
        coEvery { anyConstructed<UnifiedCameraManager>().initialize() } returnsSuccess Unit
        every { anyConstructed<UnifiedCameraManager>().getCameraTypeName() } returns "Camera2 API"
        model = GlassesViewModel(context)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        unmockkAll()
    }

    private suspend fun TestScope.receive(message: Message) {
        advanceUntilIdle()
        fromPhone.emit(message)
        advanceUntilIdle()
    }

    @Test
    fun `each bluetooth state drives the connection state and the on-glass prompt`() = scope.runTest {
        advanceUntilIdle()

        bluetoothState.value = BluetoothClientState.CONNECTING
        advanceUntilIdle()
        assertThat(model.uiState.value.connectionState).isEqualTo(ConnectionState.CONNECTING)
        assertThat(model.uiState.value.displayText).isEqualTo(string(R.string.connecting_status))
        assertThat(model.uiState.value.hintText).isEqualTo(string(R.string.please_wait))

        bluetoothState.value = BluetoothClientState.CONNECTED
        advanceUntilIdle()
        assertThat(model.uiState.value.isConnected).isTrue()
        assertThat(model.uiState.value.connectionState).isEqualTo(ConnectionState.CONNECTED)
        assertThat(model.uiState.value.displayText).isEqualTo(string(R.string.connected_ready))

        bluetoothState.value = BluetoothClientState.DISCONNECTED
        advanceUntilIdle()
        assertThat(model.uiState.value.isConnected).isFalse()
        assertThat(model.uiState.value.displayText).isEqualTo(string(R.string.not_connected))
        assertThat(model.uiState.value.hintText).isEqualTo(string(R.string.please_connect_phone))
    }

    @Test
    fun `the connected phone name is mirrored onto the screen`() = scope.runTest {
        advanceUntilIdle()

        deviceName.value = "Pixel 8"
        advanceUntilIdle()

        assertThat(model.uiState.value.connectedDeviceName).isEqualTo("Pixel 8")
    }

    @Test
    fun `pairing, connecting and disconnecting are delegated to the client`() = scope.runTest {
        advanceUntilIdle()

        model.refreshPairedDevices()
        assertThat(model.uiState.value.availableDevices).isEmpty()
        verify(atLeast = 1) { anyConstructed<BluetoothSppClient>().getPairedDevices() }

        model.disconnectBluetooth()
        verify { anyConstructed<BluetoothSppClient>().disconnect() }
    }

    @Test
    fun `progress, transcript and error messages are shown as they arrive`() = scope.runTest {
        receive(Message(type = MessageType.AI_PROCESSING, payload = "Thinking..."))
        assertThat(model.uiState.value.isProcessing).isTrue()
        assertThat(model.uiState.value.displayText).isEqualTo("Thinking...")

        // With no payload the localized default is used.
        receive(Message(type = MessageType.AI_PROCESSING))
        assertThat(model.uiState.value.displayText).isEqualTo(string(R.string.processing))

        receive(Message(type = MessageType.USER_TRANSCRIPT, payload = "what is this?"))
        assertThat(model.uiState.value.userTranscript).isEqualTo("what is this?")
        assertThat(model.uiState.value.displayText)
            .isEqualTo(string(R.string.you_said, "what is this?"))

        receive(Message(type = MessageType.AI_ERROR, payload = "quota exceeded"))
        assertThat(model.uiState.value.isProcessing).isFalse()
        assertThat(model.uiState.value.displayText)
            .isEqualTo(string(R.string.error_prefix, "quota exceeded"))
        assertThat(model.uiState.value.hintText).isEqualTo(string(R.string.please_try_again))
    }

    @Test
    fun `display text is shown and cleared on request`() = scope.runTest {
        receive(Message(type = MessageType.DISPLAY_TEXT, payload = "Hello"))
        assertThat(model.uiState.value.displayText).isEqualTo("Hello")

        receive(Message(type = MessageType.DISPLAY_CLEAR))
        assertThat(model.uiState.value.displayText).isEmpty()
        assertThat(model.uiState.value.hintText).isEqualTo(string(R.string.tap_touchpad_start))
    }

    @Test
    fun `a heartbeat is acknowledged back to the phone`() = scope.runTest {
        receive(Message(type = MessageType.HEARTBEAT))

        coVerify {
            anyConstructed<BluetoothSppClient>().sendMessage(
                match { it.type == MessageType.HEARTBEAT_ACK }
            )
        }
    }

    @Test
    fun `acknowledgements and unhandled types change nothing`() = scope.runTest {
        receive(Message(type = MessageType.DISPLAY_TEXT, payload = "Hello"))
        val before = model.uiState.value

        receive(Message(type = MessageType.HEARTBEAT_ACK))
        receive(Message(type = MessageType.VOICE_START))

        assertThat(model.uiState.value).isEqualTo(before)
    }

    @Test
    fun `a short answer is shown whole, with no pagination`() = scope.runTest {
        receive(Message(type = MessageType.AI_RESPONSE_TEXT, payload = "A bicycle."))

        assertThat(model.uiState.value.isProcessing).isFalse()
        assertThat(model.uiState.value.aiResponse).isEqualTo("A bicycle.")
        assertThat(model.uiState.value.displayText).isEqualTo("A bicycle.")
        assertThat(model.uiState.value.isPaginated).isFalse()
        assertThat(model.uiState.value.totalPages).isEqualTo(1)
        assertThat(model.uiState.value.hintText).isEqualTo(string(R.string.tap_continue))
    }

    @Test
    fun `a long answer is paginated and can be paged back and forth`() = scope.runTest {
        val answer = (1..60).joinToString(" ") { "word$it" }

        receive(Message(type = MessageType.AI_RESPONSE_TEXT, payload = answer))

        val paged = model.uiState.value
        assertThat(paged.isPaginated).isTrue()
        assertThat(paged.totalPages).isAtLeast(2)
        assertThat(paged.currentPage).isEqualTo(0)
        assertThat(paged.hintText).isEqualTo(string(R.string.swipe_for_more))
        val firstPage = paged.displayText

        model.nextPage()
        assertThat(model.uiState.value.currentPage).isEqualTo(1)
        assertThat(model.uiState.value.displayText).isNotEqualTo(firstPage)

        model.previousPage()
        assertThat(model.uiState.value.currentPage).isEqualTo(0)
        assertThat(model.uiState.value.displayText).isEqualTo(firstPage)
        assertThat(model.uiState.value.hintText).isEqualTo(string(R.string.swipe_for_more))

        // Paging past either end is a no-op.
        model.previousPage()
        assertThat(model.uiState.value.currentPage).isEqualTo(0)
        repeat(paged.totalPages + 2) { model.nextPage() }
        assertThat(model.uiState.value.currentPage).isEqualTo(paged.totalPages - 1)
        assertThat(model.uiState.value.hintText).isEqualTo(string(R.string.tap_continue))

        model.dismissPagination()
        assertThat(model.uiState.value.isPaginated).isFalse()
        assertThat(model.uiState.value.totalPages).isEqualTo(1)
        assertThat(model.uiState.value.displayText).isEqualTo(string(R.string.tap_touchpad_start))
        assertThat(model.uiState.value.hintText).isEqualTo(string(R.string.tap_touchpad_record))
    }

    @Test
    fun `paging does nothing while a single page is shown`() = scope.runTest {
        receive(Message(type = MessageType.AI_RESPONSE_TEXT, payload = "Short."))

        model.nextPage()
        model.previousPage()

        assertThat(model.uiState.value.currentPage).isEqualTo(0)
        assertThat(model.uiState.value.displayText).isEqualTo("Short.")
    }

    @Test
    fun `a photo analysis result clears the capture state and is paginated`() = scope.runTest {
        receive(Message(type = MessageType.PHOTO_ANALYSIS_RESULT, payload = "A red bicycle."))

        assertThat(model.uiState.value.isCapturingPhoto).isFalse()
        assertThat(model.uiState.value.photoTransferProgress).isEqualTo(0f)
        assertThat(model.uiState.value.isProcessing).isFalse()
        assertThat(model.uiState.value.displayText).isEqualTo("A red bicycle.")
        assertThat(model.uiState.value.isPaginated).isFalse()
        assertThat(model.uiState.value.hintText).isEqualTo(string(R.string.tap_touchpad_start))

        // A long result is paged and says so.
        val long = (1..60).joinToString(" ") { "word$it" }
        receive(Message(type = MessageType.PHOTO_ANALYSIS_RESULT, payload = long))
        assertThat(model.uiState.value.isPaginated).isTrue()
        assertThat(model.uiState.value.displayText)
            .contains("(1/${model.uiState.value.totalPages})")
        assertThat(model.uiState.value.hintText)
            .isEqualTo(string(R.string.swipe_left_right_pages))

        // No payload falls back to the localized "no result" text.
        receive(Message(type = MessageType.PHOTO_ANALYSIS_RESULT))
        assertThat(model.uiState.value.displayText)
            .isEqualTo(string(R.string.photo_analysis_no_result))
    }

    @Test
    fun `live mode is entered and left on the phone's command`() = scope.runTest {
        receive(Message(type = MessageType.LIVE_SESSION_START))
        assertThat(model.uiState.value.isLiveModeActive).isTrue()
        assertThat(model.uiState.value.liveTranscription).isEmpty()

        receive(Message(type = MessageType.LIVE_TRANSCRIPTION, payload = "listening now"))
        assertThat(model.uiState.value.liveTranscription).isEqualTo("listening now")
        assertThat(model.uiState.value.displayText).isEqualTo("listening now")

        receive(Message(type = MessageType.LIVE_SESSION_END))
        assertThat(model.uiState.value.isLiveModeActive).isFalse()
        assertThat(model.uiState.value.liveTranscription).isEmpty()
        assertThat(model.uiState.value.hintText).isEqualTo(string(R.string.tap_touchpad_start))
    }

    @Test
    fun `recording is refused while the phone is not connected`() = scope.runTest {
        advanceUntilIdle()

        model.startRecording()

        assertThat(model.uiState.value.isListening).isFalse()
        assertThat(model.uiState.value.displayText).isEqualTo(string(R.string.please_connect_phone))
        assertThat(model.uiState.value.hintText).isEqualTo(string(R.string.select_paired_device))
    }

    @Test
    fun `a remote stop with nothing recording is ignored`() = scope.runTest {
        receive(Message(type = MessageType.REMOTE_RECORD_STOP))

        assertThat(model.uiState.value.isListening).isFalse()
    }

    @Test
    fun `a remote start while disconnected leaves the connect prompt up`() = scope.runTest {
        receive(Message(type = MessageType.REMOTE_RECORD_START))

        assertThat(model.uiState.value.isListening).isFalse()
        assertThat(model.uiState.value.displayText).isEqualTo(string(R.string.please_connect_phone))
    }

    @Test
    fun `spoken audio from the phone is accepted without changing the screen`() = scope.runTest {
        receive(Message(type = MessageType.DISPLAY_TEXT, payload = "Hello"))
        val before = model.uiState.value

        receive(Message(type = MessageType.AI_RESPONSE_TTS, binaryData = byteArrayOf(1, 2, 3)))
        receive(Message(type = MessageType.AI_RESPONSE_TTS))

        assertThat(model.uiState.value).isEqualTo(before)
    }
}
