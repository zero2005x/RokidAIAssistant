package com.example.rokidphone.viewmodel

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.example.rokidcommon.protocol.ConnectionState
import com.example.rokidcommon.protocol.Message
import com.example.rokidcommon.protocol.MessageType
import com.example.rokidphone.data.db.RecordingEntity
import com.example.rokidphone.data.db.RecordingRepository
import com.example.rokidphone.data.db.RecordingSource
import com.example.rokidphone.data.db.RecordingState
import com.example.rokidphone.service.BluetoothConnectionState
import com.example.rokidphone.service.ServiceBridge
import com.example.rokidphone.testutil.returnsFailure
import com.example.rokidphone.testutil.returnsSuccess
import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkAll
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class PhoneViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private val scope = TestScope(dispatcher)
    private val repository = mockk<RecordingRepository>(relaxed = true)
    private val recordingState = MutableStateFlow<RecordingState>(RecordingState.Idle)
    /** Unconfined so a collector registers synchronously before any emission. */
    private val collectors = CoroutineScope(UnconfinedTestDispatcher())
    private lateinit var application: Application

    private fun recording(
        id: String, source: RecordingSource = RecordingSource.PHONE, filePath: String = "/rec.m4a"
    ) = RecordingEntity(id = id, title = id, filePath = filePath, source = source)

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        application = ApplicationProvider.getApplicationContext()
        mockkObject(RecordingRepository.Companion)
        every { RecordingRepository.getInstance(any()) } returns repository
        every { repository.recordingState } returns recordingState
    }

    @After
    fun tearDown() {
        collectors.cancel()
        ServiceBridge.reset()
        Dispatchers.resetMain()
        unmockkAll()
    }

    private fun viewModel() = PhoneViewModel(application)

    @Test
    fun `service, bluetooth and photo state are mirrored from the service bridge`() = scope.runTest {
        val model = viewModel()
        advanceUntilIdle()

        ServiceBridge.updateServiceState(true)
        ServiceBridge.updateConnectedDeviceName("Rokid Glasses")
        ServiceBridge.emitLatestPhotoPath("/photos/latest.jpg")
        advanceUntilIdle()

        assertThat(model.uiState.value.isServiceRunning).isTrue()
        assertThat(model.uiState.value.connectedGlassesName).isEqualTo("Rokid Glasses")
        assertThat(model.uiState.value.latestPhotoPath).isEqualTo("/photos/latest.jpg")
    }

    @Test
    fun `each bluetooth state maps to the connection state the ui shows`() = scope.runTest {
        val model = viewModel()
        advanceUntilIdle()

        val expected = mapOf(
            BluetoothConnectionState.LISTENING to ConnectionState.DISCONNECTED,
            BluetoothConnectionState.CONNECTING to ConnectionState.CONNECTING,
            BluetoothConnectionState.CONNECTED to ConnectionState.CONNECTED,
            BluetoothConnectionState.DISCONNECTED to ConnectionState.DISCONNECTED
        )
        for ((bluetooth, connection) in expected) {
            ServiceBridge.updateBluetoothState(bluetooth)
            advanceUntilIdle()
            assertThat(model.uiState.value.bluetoothState).isEqualTo(bluetooth)
            assertThat(model.uiState.value.connectionState).isEqualTo(connection)
        }
    }

    @Test
    fun `the recording state is mirrored from the repository`() = scope.runTest {
        val model = viewModel()
        advanceUntilIdle()

        val active = RecordingState.Recording(RecordingSource.GLASSES, startTime = 1, durationMs = 2)
        recordingState.value = active
        advanceUntilIdle()

        assertThat(model.uiState.value.recordingState).isEqualTo(active)
    }

    @Test
    fun `conversation messages become transcript entries and a processing status`() = scope.runTest {
        val model = viewModel()
        advanceUntilIdle()

        ServiceBridge.emitConversation(
            Message(type = MessageType.USER_TRANSCRIPT, payload = "what is this?")
        )
        ServiceBridge.emitConversation(
            Message(type = MessageType.AI_RESPONSE_TEXT, payload = "a bicycle")
        )
        ServiceBridge.emitConversation(
            Message(type = MessageType.AI_PROCESSING, payload = "thinking")
        )
        // Messages with no payload, and types the screen does not render, are ignored.
        ServiceBridge.emitConversation(Message(type = MessageType.USER_TRANSCRIPT))
        ServiceBridge.emitConversation(Message(type = MessageType.HEARTBEAT, payload = "ping"))
        advanceUntilIdle()

        assertThat(model.uiState.value.conversations.map { it.role to it.content })
            .containsExactly("user" to "what is this?", "assistant" to "a bicycle").inOrder()
        assertThat(model.uiState.value.processingStatus).isEqualTo("thinking")
    }

    @Test
    fun `the transcript keeps only the most recent entries`() = scope.runTest {
        val model = viewModel()
        advanceUntilIdle()

        repeat(205) { model.addConversation("user", "message $it") }

        assertThat(model.uiState.value.conversations).hasSize(200)
        assertThat(model.uiState.value.conversations.first().content).isEqualTo("message 5")
        assertThat(model.uiState.value.conversations.last().content).isEqualTo("message 204")

        model.clearConversations()
        assertThat(model.uiState.value.conversations).isEmpty()
    }

    @Test
    fun `an api key warning is raised by the service and dismissed by the user`() = scope.runTest {
        val model = viewModel()
        advanceUntilIdle()

        ServiceBridge.notifyApiKeyMissing()
        advanceUntilIdle()
        assertThat(model.uiState.value.showApiKeyWarning).isTrue()

        model.dismissApiKeyWarning()
        assertThat(model.uiState.value.showApiKeyWarning).isFalse()
    }

    @Test
    fun `initial setup is offered only when no key is configured`() = scope.runTest {
        val model = viewModel()

        model.checkInitialSetup(hasAnyApiKey = true)
        assertThat(model.uiState.value.showInitialSetup).isFalse()

        model.checkInitialSetup(hasAnyApiKey = false)
        assertThat(model.uiState.value.showInitialSetup).isTrue()

        model.dismissInitialSetup()
        assertThat(model.uiState.value.showInitialSetup).isFalse()
    }

    @Test
    fun `service and processing status can be set directly by the screen`() = scope.runTest {
        val model = viewModel()

        model.updateServiceStatus(true)
        assertThat(model.uiState.value.isServiceRunning).isTrue()
        model.updateProcessingStatus("uploading")
        assertThat(model.uiState.value.processingStatus).isEqualTo("uploading")
        model.updateProcessingStatus(null)
        assertThat(model.uiState.value.processingStatus).isNull()
    }

    @Test
    fun `scanning, disconnecting and photo capture are requested through the bridge`() = scope.runTest {
        val listening = mutableListOf<Unit>()
        val disconnects = mutableListOf<Unit>()
        val captures = mutableListOf<Unit>()
        collectors.launch { ServiceBridge.startListeningFlow.collect { listening += it } }
        collectors.launch { ServiceBridge.disconnectFlow.collect { disconnects += it } }
        collectors.launch { ServiceBridge.capturePhotoFlow.collect { captures += it } }
        val model = viewModel()
        advanceUntilIdle()

        model.startScanning()
        advanceUntilIdle()
        assertThat(model.uiState.value.connectionState).isEqualTo(ConnectionState.CONNECTING)
        assertThat(listening).hasSize(1)

        model.disconnect()
        model.requestCapturePhoto()
        advanceUntilIdle()
        assertThat(disconnects).hasSize(1)
        assertThat(captures).hasSize(1)
    }

    @Test
    fun `starting a glasses recording forwards its id, and failures are swallowed`() = scope.runTest {
        val started = mutableListOf<String>()
        collectors.launch { ServiceBridge.startGlassesRecordingFlow.collect { started += it } }
        coEvery { repository.startGlassesRecording() } returnsSuccess "rec-1"
        coEvery { repository.startPhoneRecording() } returnsFailure IOException("mic busy")
        val model = viewModel()
        advanceUntilIdle()

        model.startGlassesRecording()
        advanceUntilIdle()
        assertThat(started).containsExactly("rec-1")

        // A failed phone recording is logged, not surfaced on this screen.
        model.startPhoneRecording()
        advanceUntilIdle()
        coVerify { repository.startPhoneRecording() }

        coEvery { repository.startGlassesRecording() } returnsFailure IOException("not connected")
        model.startGlassesRecording()
        advanceUntilIdle()
        assertThat(started).containsExactly("rec-1")

        model.pauseRecording()
        advanceUntilIdle()
        coVerify { repository.pauseRecording() }
    }

    @Test
    fun `stopping a phone recording asks for transcription`() = scope.runTest {
        val requests = mutableListOf<ServiceBridge.TranscriptionRequest>()
        collectors.launch { ServiceBridge.transcribeRecordingFlow.collect { requests += it } }
        coEvery { repository.stopRecording() } returnsSuccess recording("rec-1")
        val model = viewModel()
        advanceUntilIdle()

        model.stopRecording()
        advanceUntilIdle()

        assertThat(requests).containsExactly(ServiceBridge.TranscriptionRequest("rec-1", "/rec.m4a"))
    }

    @Test
    fun `stopping a glasses recording tells the glasses to stop instead`() = scope.runTest {
        val stops = mutableListOf<Unit>()
        val requests = mutableListOf<ServiceBridge.TranscriptionRequest>()
        collectors.launch { ServiceBridge.stopGlassesRecordingFlow.collect { stops += it } }
        collectors.launch { ServiceBridge.transcribeRecordingFlow.collect { requests += it } }
        coEvery { repository.stopRecording() } returnsSuccess
            recording("rec-2", source = RecordingSource.GLASSES)
        val model = viewModel()
        advanceUntilIdle()

        model.stopRecording()
        advanceUntilIdle()

        assertThat(stops).hasSize(1)
        assertThat(requests).isEmpty()
    }

    @Test
    fun `a phone recording with no file, no recording at all, or a failure asks for nothing`() =
        scope.runTest {
            val requests = mutableListOf<ServiceBridge.TranscriptionRequest>()
            collectors.launch { ServiceBridge.transcribeRecordingFlow.collect { requests += it } }
            val model = viewModel()
            advanceUntilIdle()

            coEvery { repository.stopRecording() } returnsSuccess recording("rec-3", filePath = "")
            model.stopRecording()
            advanceUntilIdle()

            coEvery { repository.stopRecording() } returnsSuccess null
            model.stopRecording()
            advanceUntilIdle()

            coEvery { repository.stopRecording() } returnsFailure IOException("save failed")
            model.stopRecording()
            advanceUntilIdle()

            assertThat(requests).isEmpty()
        }
}
