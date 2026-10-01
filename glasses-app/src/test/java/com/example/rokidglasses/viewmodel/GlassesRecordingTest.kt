package com.example.rokidglasses.viewmodel

import android.content.Context
import androidx.test.core.app.ApplicationProvider
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayOutputStream

/**
 * The record/stop path of [GlassesViewModel] and the display pagination fallback.
 *
 * The microphone loop itself needs real audio hardware, so these cover the guards
 * around it and the send path, seeding the audio buffer directly where a real
 * recording would have filled it. Main is unconfined here because stopRecording
 * hops to Dispatchers.IO and back.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class GlassesRecordingTest {

    private val fromPhone = MutableSharedFlow<Message>(extraBufferCapacity = 32)
    private val bluetoothState = MutableStateFlow(BluetoothClientState.DISCONNECTED)
    private val deviceName = MutableStateFlow<String?>(null)
    private lateinit var context: Context
    private lateinit var model: GlassesViewModel

    private fun string(id: Int, vararg args: Any) = context.getString(id, *args)

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        context = ApplicationProvider.getApplicationContext()
        mockkObject(CxrServiceManager.Companion)
        // The Rokid CXR-S SDK native layer is not on the unit test classpath.
        every { CxrServiceManager.isSdkAvailable() } returns false
        mockkConstructor(BluetoothSppClient::class, UnifiedCameraManager::class)
        every { anyConstructed<BluetoothSppClient>().messageFlow } returns fromPhone
        every { anyConstructed<BluetoothSppClient>().connectionState } returns bluetoothState
        every { anyConstructed<BluetoothSppClient>().connectedDeviceName } returns deviceName
        every { anyConstructed<BluetoothSppClient>().getPairedDevices() } returns emptyList()
        coEvery { anyConstructed<BluetoothSppClient>().sendMessage(any()) } returns true
        coEvery { anyConstructed<BluetoothSppClient>().sendVoiceStart() } returns true
        coEvery { anyConstructed<BluetoothSppClient>().sendVoiceEnd(any()) } returns true
        coEvery { anyConstructed<UnifiedCameraManager>().initialize() } returnsSuccess Unit
        every { anyConstructed<UnifiedCameraManager>().getCameraTypeName() } returns "Camera2 API"
        model = GlassesViewModel(context)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        unmockkAll()
    }

    private fun connect() {
        bluetoothState.value = BluetoothClientState.CONNECTED
    }

    /** Fills the buffer a real microphone loop would have written into. */
    private fun seedRecordedAudio(bytes: ByteArray) {
        val field = GlassesViewModel::class.java.getDeclaredField("audioBuffer")
            .apply { isAccessible = true }
        (field.get(model) as ByteArrayOutputStream).write(bytes)
    }

    /** stopRecording finishes on Dispatchers.IO, so wait for the state it leaves behind. */
    private fun awaitState(timeoutMs: Long = 5_000, predicate: (GlassesUiState) -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (predicate(model.uiState.value)) return
            Thread.sleep(10)
        }
        throw AssertionError("Timed out waiting for state; last was ${model.uiState.value}")
    }

    // ==================== Starting ====================

    @Test
    fun `recording is refused until the phone is connected`() {
        model.startRecording()

        assertThat(model.uiState.value.isListening).isFalse()
        assertThat(model.uiState.value.displayText).isEqualTo(string(R.string.please_connect_phone))
        assertThat(model.uiState.value.hintText).isEqualTo(string(R.string.select_paired_device))
    }

    @Test
    fun `recording stops at the microphone permission when it is not granted`() {
        connect()

        model.startRecording()

        // Robolectric grants nothing by default, so the guard fires.
        assertThat(model.uiState.value.displayText)
            .isEqualTo(string(R.string.mic_permission_required))
        assertThat(model.uiState.value.isListening).isFalse()
    }

    @Test
    fun `starting clears the previous turn before it checks anything else`() = runBlocking {
        connect()
        fromPhone.emit(Message(type = MessageType.USER_TRANSCRIPT, payload = "old question"))
        assertThat(model.uiState.value.userTranscript).isEqualTo("old question")

        model.startRecording()

        assertThat(model.uiState.value.userTranscript).isEmpty()
        assertThat(model.uiState.value.aiResponse).isEmpty()
    }

    // ==================== Stopping ====================

    @Test
    fun `stopping with nothing recorded reports that no voice was heard`() {
        connect()

        model.stopRecording()

        awaitState { it.displayText == string(R.string.no_voice_detected) }
        assertThat(model.uiState.value.isProcessing).isFalse()
        assertThat(model.uiState.value.isListening).isFalse()
        assertThat(model.uiState.value.hintText).isEqualTo(string(R.string.please_try_again))
    }

    @Test
    fun `recorded audio is sent to the phone and the glasses then wait`() {
        connect()
        val audio = ByteArray(4_096) { it.toByte() }
        seedRecordedAudio(audio)

        model.stopRecording()

        awaitState { it.displayText == string(R.string.waiting_phone) }
        assertThat(model.uiState.value.hintText).isEqualTo(string(R.string.ai_thinking))
        coVerify { anyConstructed<BluetoothSppClient>().sendVoiceEnd(audio) }
    }

    @Test
    fun `a failed send is reported and processing stops`() {
        connect()
        coEvery { anyConstructed<BluetoothSppClient>().sendVoiceEnd(any()) } returns false
        seedRecordedAudio(ByteArray(2_048))

        model.stopRecording()

        awaitState { it.displayText == string(R.string.send_failed) }
        assertThat(model.uiState.value.isProcessing).isFalse()
        assertThat(model.uiState.value.hintText).isEqualTo(string(R.string.reconnect_try_again))
    }

    @Test
    fun `a send that throws is reported as an error`() {
        connect()
        coEvery { anyConstructed<BluetoothSppClient>().sendVoiceEnd(any()) } throws
            IllegalStateException("socket closed")
        seedRecordedAudio(ByteArray(2_048))

        model.stopRecording()

        awaitState { !it.isProcessing && it.displayText.contains("socket closed") }
        assertThat(model.uiState.value.hintText).isEqualTo(string(R.string.please_try_again))
    }

    @Test
    fun `the buffer is emptied so the next turn starts clean`() {
        connect()
        seedRecordedAudio(ByteArray(2_048))
        model.stopRecording()
        awaitState { it.displayText == string(R.string.waiting_phone) }

        // Nothing was recorded since, so the second stop finds an empty buffer.
        model.stopRecording()

        awaitState { it.displayText == string(R.string.no_voice_detected) }
    }

    // ==================== Remote control from the phone ====================

    @Test
    fun `the phone can start and stop recording remotely`() = runBlocking {
        connect()

        fromPhone.emit(Message(type = MessageType.REMOTE_RECORD_START))

        // The permission guard still applies, so this lands on the same message.
        assertThat(model.uiState.value.displayText)
            .isEqualTo(string(R.string.mic_permission_required))

        fromPhone.emit(Message(type = MessageType.REMOTE_RECORD_STOP))

        // Not listening, so the stop is ignored rather than sending an empty turn.
        assertThat(model.uiState.value.displayText)
            .isEqualTo(string(R.string.mic_permission_required))
    }

    @Test
    fun `toggling follows whichever state the glasses are in`() {
        connect()

        // Not listening yet, so this is a start attempt.
        model.toggleRecording()
        assertThat(model.uiState.value.displayText)
            .isEqualTo(string(R.string.mic_permission_required))
    }

    @Test
    fun `a heartbeat acknowledgement changes nothing on screen`() = runBlocking {
        connect()
        val before = model.uiState.value

        fromPhone.emit(Message(type = MessageType.HEARTBEAT_ACK))

        assertThat(model.uiState.value).isEqualTo(before)
    }

    // ==================== Display pagination ====================

    @Test
    fun `a short reply is shown on a single page`() = runBlocking {
        fromPhone.emit(Message(type = MessageType.AI_RESPONSE_TEXT, payload = "Short answer."))

        assertThat(model.uiState.value.isPaginated).isFalse()
        assertThat(model.uiState.value.totalPages).isEqualTo(1)
        assertThat(model.uiState.value.displayText).isEqualTo("Short answer.")
        assertThat(model.uiState.value.hintText).isEqualTo(string(R.string.tap_continue))
    }

    @Test
    fun `a long reply with no word breaks falls back to splitting on length`() = runBlocking {
        // No spaces or punctuation, so word-based pagination cannot split it.
        val unbroken = "A".repeat(500)

        fromPhone.emit(Message(type = MessageType.AI_RESPONSE_TEXT, payload = unbroken))

        val state = model.uiState.value
        assertThat(state.isPaginated).isTrue()
        assertThat(state.totalPages).isAtLeast(4)
        assertThat(state.currentPage).isEqualTo(0)
        assertThat(state.displayText).isNotEmpty()
        assertThat(state.hintText).isEqualTo(string(R.string.swipe_for_more))
        assertThat(state.aiResponse).isEqualTo(unbroken)
    }

    @Test
    fun `a long reply is split at natural breaks when it has them`() = runBlocking {
        val sentence = "This is a sentence that carries a little weight. "
        val long = sentence.repeat(12)

        fromPhone.emit(Message(type = MessageType.AI_RESPONSE_TEXT, payload = long))

        val state = model.uiState.value
        assertThat(state.isPaginated).isTrue()
        assertThat(state.totalPages).isGreaterThan(1)
        // Pages are trimmed, so no page starts or ends with stray whitespace.
        assertThat(state.displayText).isEqualTo(state.displayText.trim())
    }
}
