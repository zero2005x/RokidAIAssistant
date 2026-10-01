package com.example.rokidphone.service.ai

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockkConstructor
import io.mockk.slot
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Lifecycle and event routing for [GeminiLiveSession], which coordinates the WebSocket
 * service, the audio manager and the tool router. All three are substituted here, and
 * the session's own scope runs on real IO threads, so waits are bounded rather than
 * driven by a test scheduler. The sibling GeminiLiveSessionTest covers the guard
 * clauses and pure helpers that need no components at all.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class GeminiLiveSessionLifecycleTest {

    private lateinit var context: Context
    private lateinit var session: GeminiLiveSession
    private val serviceErrors = MutableStateFlow<String?>(null)
    private val collectors = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())

    private val onConnectionState = slot<(GeminiLiveService.ConnectionState) -> Unit>()
    private val onAudioReceived = slot<(ByteArray) -> Unit>()
    private val onTurnComplete = slot<() -> Unit>()
    private val onInterrupted = slot<() -> Unit>()
    private val onToolCall = slot<(List<GeminiLiveService.ToolCall>) -> Unit>()
    private val onInputTranscription = slot<(String) -> Unit>()
    private val onOutputTranscription = slot<(String) -> Unit>()
    private val onAudioChunk = slot<(ByteArray) -> Unit>()
    private val onAudioError = slot<(String) -> Unit>()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        mockkConstructor(GeminiLiveService::class, LiveAudioManager::class)
        every { anyConstructed<GeminiLiveService>().errorMessage } returns serviceErrors
        every {
            anyConstructed<GeminiLiveService>().onConnectionStateChanged = capture(onConnectionState)
        } just Runs
        every { anyConstructed<GeminiLiveService>().onAudioReceived = capture(onAudioReceived) } just Runs
        every { anyConstructed<GeminiLiveService>().onTurnComplete = capture(onTurnComplete) } just Runs
        every { anyConstructed<GeminiLiveService>().onInterrupted = capture(onInterrupted) } just Runs
        every { anyConstructed<GeminiLiveService>().onToolCall = capture(onToolCall) } just Runs
        every {
            anyConstructed<GeminiLiveService>().onInputTranscription = capture(onInputTranscription)
        } just Runs
        every {
            anyConstructed<GeminiLiveService>().onOutputTranscription = capture(onOutputTranscription)
        } just Runs
        every { anyConstructed<LiveAudioManager>().onAudioChunk = capture(onAudioChunk) } just Runs
        every { anyConstructed<LiveAudioManager>().onError = capture(onAudioError) } just Runs
        every { anyConstructed<LiveAudioManager>().onPlaybackComplete = any() } just Runs
        every { anyConstructed<LiveAudioManager>().requestAudioFocus() } returns true
        every { anyConstructed<LiveAudioManager>().startRecording() } returns true
        session = GeminiLiveSession(context, apiKey = "fixture-key")
    }

    @After
    fun tearDown() {
        collectors.cancel()
        session.release()
        unmockkAll()
    }

    private fun connectionState(state: GeminiLiveService.ConnectionState) {
        onConnectionState.captured.invoke(state)
    }

    /** The session emits on its own IO scope, so wait for the state rather than assume it. */
    private fun awaitState(state: GeminiLiveSession.SessionState) {
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline && session.sessionState.value != state) {
            Thread.sleep(5)
        }
        assertThat(session.sessionState.value).isEqualTo(state)
    }

    @Test
    fun `starting a session wires the components up and connects`() {
        assertThat(session.sessionState.value).isEqualTo(GeminiLiveSession.SessionState.IDLE)

        assertThat(session.start()).isTrue()

        assertThat(session.sessionState.value).isEqualTo(GeminiLiveSession.SessionState.CONNECTING)
        assertThat(session.errorMessage.value).isNull()
        verify { anyConstructed<GeminiLiveService>().updateVadSettings(any(), any(), any(), any()) }
        verify { anyConstructed<LiveAudioManager>().requestAudioFocus() }
        verify { anyConstructed<GeminiLiveService>().connect(any()) }
        assertThat(session.getToolCallRouter()).isNotNull()
    }

    @Test
    fun `a session that is already running is not started again`() {
        session.start()

        assertThat(session.start()).isFalse()

        verify(exactly = 1) { anyConstructed<GeminiLiveService>().connect(any()) }
    }

    @Test
    fun `a failure while connecting leaves the session in error and releases everything`() {
        every { anyConstructed<GeminiLiveService>().connect(any()) } throws
            IllegalStateException("no network")

        assertThat(session.start()).isFalse()

        assertThat(session.sessionState.value).isEqualTo(GeminiLiveSession.SessionState.ERROR)
        assertThat(session.errorMessage.value).isEqualTo("no network")
        assertThat(session.getToolCallRouter()).isNull()
        verify { anyConstructed<LiveAudioManager>().release() }
    }

    @Test
    fun `the custom VAD configuration is forwarded to the service`() {
        val config = GeminiLiveSession.VadConfig(
            startSensitivity = GeminiLiveService.StartOfSpeechSensitivity.START_SENSITIVITY_LOW,
            endSensitivity = GeminiLiveService.EndOfSpeechSensitivity.END_SENSITIVITY_HIGH,
            silenceDurationMs = 1_200,
            activityHandling = GeminiLiveService.ActivityHandling.NO_INTERRUPT
        )

        session.start(config)

        verify {
            anyConstructed<GeminiLiveService>().updateVadSettings(
                GeminiLiveService.StartOfSpeechSensitivity.START_SENSITIVITY_LOW,
                GeminiLiveService.EndOfSpeechSensitivity.END_SENSITIVITY_HIGH,
                1_200,
                GeminiLiveService.ActivityHandling.NO_INTERRUPT
            )
        }
    }

    @Test
    fun `each connection state maps onto the session state`() {
        session.start()

        connectionState(GeminiLiveService.ConnectionState.CONNECTING)
        assertThat(session.sessionState.value).isEqualTo(GeminiLiveSession.SessionState.CONNECTING)
        connectionState(GeminiLiveService.ConnectionState.SETTING_UP)
        assertThat(session.sessionState.value).isEqualTo(GeminiLiveSession.SessionState.CONNECTING)

        connectionState(GeminiLiveService.ConnectionState.READY)
        assertThat(session.sessionState.value).isEqualTo(GeminiLiveSession.SessionState.ACTIVE)
        // Recording starts after a short settle delay.
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline &&
            runCatching { verify { anyConstructed<LiveAudioManager>().startRecording() } }.isFailure
        ) {
            Thread.sleep(5)
        }
        verify { anyConstructed<LiveAudioManager>().startRecording() }
    }

    @Test
    fun `an unexpected disconnection is an error, but a requested one is not`() {
        session.start()
        connectionState(GeminiLiveService.ConnectionState.READY)

        connectionState(GeminiLiveService.ConnectionState.DISCONNECTED)
        assertThat(session.sessionState.value).isEqualTo(GeminiLiveSession.SessionState.ERROR)
        assertThat(session.errorMessage.value).isEqualTo("Connection lost")

        // Stopping puts the session back to idle rather than error.
        session.stop()
        assertThat(session.sessionState.value).isEqualTo(GeminiLiveSession.SessionState.IDLE)
    }

    @Test
    fun `a connection error carries the service's message`() {
        session.start()
        serviceErrors.value = "handshake rejected"

        connectionState(GeminiLiveService.ConnectionState.ERROR)

        assertThat(session.sessionState.value).isEqualTo(GeminiLiveSession.SessionState.ERROR)
        assertThat(session.errorMessage.value).isEqualTo("handshake rejected")
    }

    @Test
    fun `pausing and resuming only work from the matching state`() {
        session.start()

        // Not active yet.
        session.pause()
        assertThat(session.sessionState.value).isEqualTo(GeminiLiveSession.SessionState.CONNECTING)

        connectionState(GeminiLiveService.ConnectionState.READY)
        session.resume() // already active
        assertThat(session.sessionState.value).isEqualTo(GeminiLiveSession.SessionState.ACTIVE)

        session.pause()
        assertThat(session.sessionState.value).isEqualTo(GeminiLiveSession.SessionState.PAUSED)
        verify { anyConstructed<LiveAudioManager>().stopRecording() }

        session.resume()
        assertThat(session.sessionState.value).isEqualTo(GeminiLiveSession.SessionState.ACTIVE)
    }

    @Test
    fun `video frames are only sent while the session is active`() {
        session.start()

        session.sendVideoFrame(byteArrayOf(1))
        verify(exactly = 0) { anyConstructed<GeminiLiveService>().sendVideoFrame(any()) }

        connectionState(GeminiLiveService.ConnectionState.READY)
        session.sendVideoFrame(byteArrayOf(1, 2))
        verify { anyConstructed<GeminiLiveService>().sendVideoFrame(byteArrayOf(1, 2)) }
    }

    @Test
    fun `recorded audio is forwarded and received audio is queued for playback`() {
        session.start()

        onAudioChunk.captured.invoke(byteArrayOf(1, 2))
        verify { anyConstructed<GeminiLiveService>().sendAudio(byteArrayOf(1, 2)) }

        onAudioReceived.captured.invoke(byteArrayOf(3, 4))
        verify { anyConstructed<LiveAudioManager>().playAudio(byteArrayOf(3, 4)) }
    }

    @Test
    fun `an audio error is surfaced on the session`() {
        session.start()

        onAudioError.captured.invoke("microphone unavailable")

        assertThat(session.errorMessage.value).isEqualTo("microphone unavailable")
    }

    @Test
    fun `a completed turn finishes playback and is published`() = runBlocking {
        session.start()
        val event = async(start = CoroutineStart.UNDISPATCHED) { session.turnComplete.first() }

        onTurnComplete.captured.invoke()

        withTimeout(5_000) { event.await() }
        verify { anyConstructed<LiveAudioManager>().finishPlayback() }
    }

    @Test
    fun `an interruption stops playback and is published`() = runBlocking {
        session.start()
        val event = async(start = CoroutineStart.UNDISPATCHED) { session.interrupted.first() }

        onInterrupted.captured.invoke()

        withTimeout(5_000) { event.await() }
        verify { anyConstructed<LiveAudioManager>().stopPlayback() }
    }

    @Test
    fun `transcriptions are published to the screen`() = runBlocking {
        session.start()
        val spoken = async(start = CoroutineStart.UNDISPATCHED) { session.inputTranscription.first() }
        onInputTranscription.captured.invoke("what is this?")
        assertThat(withTimeout(5_000) { spoken.await() }).isEqualTo("what is this?")

        val answered = async(start = CoroutineStart.UNDISPATCHED) { session.outputTranscription.first() }
        onOutputTranscription.captured.invoke("a bicycle")
        assertThat(withTimeout(5_000) { answered.await() }).isEqualTo("a bicycle")
    }

    @Test
    fun `tool calls are published and routed for execution`() = runBlocking {
        session.start()
        // check_schedule is handled locally; an unknown tool falls through to the
        // router's default answer. Both complete without the main dispatcher, which
        // make_call would need in order to open the dialer.
        val calls = listOf(
            GeminiLiveService.ToolCall("t1", "check_schedule", JSONObject()),
            GeminiLiveService.ToolCall("t2", "teleport", JSONObject())
        )
        val event = async(start = CoroutineStart.UNDISPATCHED) { session.toolCalls.first() }

        onToolCall.captured.invoke(calls)

        assertThat(withTimeout(5_000) { event.await() }.map { it.id }).containsExactly("t1", "t2")
        // The router answers both, and each answer goes back over the WebSocket.
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline &&
            runCatching {
                verify(atLeast = 2) {
                    anyConstructed<GeminiLiveService>().sendToolResponse(any(), any())
                }
            }.isFailure
        ) {
            Thread.sleep(10)
        }
        verify(atLeast = 2) { anyConstructed<GeminiLiveService>().sendToolResponse(any(), any()) }
    }

    @Test
    fun `tool responses and end of turn are passed straight through`() {
        session.start()
        val result = JSONObject().put("ok", true)

        session.sendToolResponse("t1", result)
        session.endOfTurn()

        verify { anyConstructed<GeminiLiveService>().sendToolResponse("t1", result) }
        verify { anyConstructed<GeminiLiveService>().endOfTurn() }
    }

    @Test
    fun `stopping a session that never started does nothing`() {
        session.stop()

        assertThat(session.sessionState.value).isEqualTo(GeminiLiveSession.SessionState.IDLE)
        verify(exactly = 0) { anyConstructed<GeminiLiveService>().disconnect() }
    }

    @Test
    fun `stopping releases the service, the audio manager and the router`() {
        session.start()

        session.stop()

        assertThat(session.sessionState.value).isEqualTo(GeminiLiveSession.SessionState.IDLE)
        verify { anyConstructed<GeminiLiveService>().disconnect() }
        verify { anyConstructed<LiveAudioManager>().release() }
        assertThat(session.getToolCallRouter()).isNull()
    }

    @Test
    fun `a session in error can be started again`() {
        every { anyConstructed<GeminiLiveService>().connect(any()) } throws IllegalStateException("boom")
        session.start()
        assertThat(session.sessionState.value).isEqualTo(GeminiLiveSession.SessionState.ERROR)

        every { anyConstructed<GeminiLiveService>().connect(any()) } just Runs
        assertThat(session.start()).isTrue()
        assertThat(session.sessionState.value).isEqualTo(GeminiLiveSession.SessionState.CONNECTING)
    }

}
