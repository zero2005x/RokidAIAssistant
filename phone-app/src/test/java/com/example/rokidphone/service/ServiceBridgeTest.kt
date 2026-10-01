package com.example.rokidphone.service

import com.example.rokidcommon.protocol.Message
import com.example.rokidcommon.protocol.MessageType
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * [ServiceBridge] is a process-wide singleton, so every test resets it afterwards
 * to keep the persisted connection state from leaking into the next test.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class ServiceBridgeTest {

    @After
    fun tearDown() = ServiceBridge.reset()

    /** Collects the first element, guaranteeing the collector is attached before [emit] runs. */
    private suspend fun <T> firstAfter(flow: Flow<T>, emit: suspend () -> Unit): T =
        kotlinx.coroutines.coroutineScope {
            val received = async { flow.first() }
            yield()
            emit()
            received.await()
        }

    @Test
    fun `ui requests reach the service through one-shot command flows`() = runTest {
        val message = Message(type = MessageType.DISPLAY_TEXT, payload = "hi")
        assertThat(firstAfter(ServiceBridge.sendToGlassesFlow) { ServiceBridge.sendToGlasses(message) })
            .isEqualTo(message)
        assertThat(firstAfter(ServiceBridge.capturePhotoFlow) { ServiceBridge.requestCapturePhoto() })
            .isEqualTo(Unit)
        assertThat(firstAfter(ServiceBridge.startListeningFlow) { ServiceBridge.requestStartListening() })
            .isEqualTo(Unit)
        assertThat(firstAfter(ServiceBridge.disconnectFlow) { ServiceBridge.requestDisconnect() })
            .isEqualTo(Unit)
        assertThat(firstAfter(ServiceBridge.apiKeyMissingFlow) { ServiceBridge.notifyApiKeyMissing() })
            .isEqualTo(Unit)
    }

    @Test
    fun `service events reach the ui through conversation and recording flows`() = runTest {
        val message = Message(type = MessageType.AI_RESPONSE_TEXT, payload = "answer")
        assertThat(firstAfter(ServiceBridge.conversationFlow) { ServiceBridge.emitConversation(message) })
            .isEqualTo(message)
        assertThat(firstAfter(ServiceBridge.startGlassesRecordingFlow) {
            ServiceBridge.requestStartGlassesRecording("rec-1")
        }).isEqualTo("rec-1")
        assertThat(firstAfter(ServiceBridge.stopGlassesRecordingFlow) {
            ServiceBridge.requestStopGlassesRecording()
        }).isEqualTo(Unit)
        assertThat(firstAfter(ServiceBridge.transcribeRecordingFlow) {
            ServiceBridge.requestTranscribeRecording("rec-1", "/tmp/rec-1.wav")
        }).isEqualTo(ServiceBridge.TranscriptionRequest("rec-1", "/tmp/rec-1.wav"))
        assertThat(firstAfter(ServiceBridge.transcriptionCompletedFlow) {
            ServiceBridge.notifyTranscriptionCompleted("rec-1")
        }).isEqualTo("rec-1")
    }

    @Test
    fun `reset clears the state that would otherwise survive service death`() {
        ServiceBridge.updateServiceState(true)
        ServiceBridge.updateConnectionState(true)
        ServiceBridge.updateBluetoothState(BluetoothConnectionState.CONNECTED)
        ServiceBridge.updateConnectedDeviceName("Rokid Glasses")
        ServiceBridge.emitLatestPhotoPath("/photos/latest.jpg")

        assertThat(ServiceBridge.serviceStateFlow.value).isTrue()
        assertThat(ServiceBridge.connectionStateFlow.value).isTrue()
        assertThat(ServiceBridge.bluetoothStateFlow.value).isEqualTo(BluetoothConnectionState.CONNECTED)
        assertThat(ServiceBridge.connectedDeviceNameFlow.value).isEqualTo("Rokid Glasses")
        assertThat(ServiceBridge.latestPhotoPathFlow.value).isEqualTo("/photos/latest.jpg")

        ServiceBridge.reset()

        assertThat(ServiceBridge.serviceStateFlow.value).isFalse()
        assertThat(ServiceBridge.connectionStateFlow.value).isFalse()
        assertThat(ServiceBridge.bluetoothStateFlow.value).isEqualTo(BluetoothConnectionState.DISCONNECTED)
        assertThat(ServiceBridge.connectedDeviceNameFlow.value).isNull()
        assertThat(ServiceBridge.latestPhotoPathFlow.value).isNull()
    }

    @Test
    fun `emitting more commands than the buffer holds never blocks the service`() = runTest {
        // extraBufferCapacity is 16 with DROP_OLDEST, so emitting past it without a
        // collector must drop the oldest request instead of suspending forever.
        repeat(40) { ServiceBridge.requestStartGlassesRecording("rec-$it") }
        repeat(40) { ServiceBridge.requestCapturePhoto() }
        assertThat(firstAfter(ServiceBridge.startGlassesRecordingFlow) {
            ServiceBridge.requestStartGlassesRecording("rec-latest")
        }).isEqualTo("rec-latest")
    }

    @Test
    fun `markdown is flattened for the glasses display`() {
        val markdown = """
            # Heading
            Some **bold**, *italic*, __strong__ and _quiet_ text.
            A [link](https://example.com) and `inline code`.

            ```kotlin
            val secret = 1
            ```

            - first bullet
            * second bullet
            1. numbered item



            snake_case_name survives.
        """.trimIndent()

        val cleaned = ServiceBridge.cleanMarkdown(markdown)

        assertThat(cleaned).contains("Heading")
        assertThat(cleaned).contains("Some bold, italic, strong and quiet text.")
        assertThat(cleaned).contains("A link and inline code.")
        assertThat(cleaned).contains("• first bullet")
        assertThat(cleaned).contains("• second bullet")
        assertThat(cleaned).contains("numbered item")
        // Code blocks are removed before emphasis rules run.
        assertThat(cleaned).doesNotContain("val secret")
        assertThat(cleaned).doesNotContain("```")
        assertThat(cleaned).doesNotContain("#")
        assertThat(cleaned).doesNotContain("https://example.com")
        // The underscore rules require a word boundary, so identifiers keep their
        // underscores instead of being read as emphasis markers.
        assertThat(cleaned).contains("snake_case_name survives.")
        // Runs of blank lines collapse and the result is trimmed.
        assertThat(cleaned).doesNotContain("\n\n\n")
        assertThat(cleaned).isEqualTo(cleaned.trim())
    }
}
