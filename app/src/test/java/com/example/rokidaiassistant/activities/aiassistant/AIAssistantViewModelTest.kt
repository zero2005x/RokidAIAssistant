package com.example.rokidaiassistant.activities.aiassistant

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.example.rokidaiassistant.sdk.AiEventListener
import com.example.rokidaiassistant.sdk.AudioStreamListener
import com.example.rokidaiassistant.sdk.CxrApi
import com.example.rokidaiassistant.services.GeminiService
import com.example.rokidaiassistant.services.SpeechToTextService
import com.example.rokidaiassistant.services.TextToSpeechService
import com.example.rokidaiassistant.testutil.returnsFailure
import com.example.rokidaiassistant.testutil.returnsSuccess
import com.google.common.truth.Truth.assertThat
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.mockkObject
import io.mockk.slot
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
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
class AIAssistantViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private val scope = TestScope(dispatcher)
    private val cxrApi = mockk<CxrApi>(relaxed = true)
    private lateinit var application: Application
    private lateinit var model: AIAssistantViewModel

    private fun aiEvents(): AiEventListener = AIAssistantViewModel::class.java
        .getDeclaredField("aiEventListener").apply { isAccessible = true }
        .get(model) as AiEventListener

    private fun audioStream(): AudioStreamListener = AIAssistantViewModel::class.java
        .getDeclaredField("audioStreamListener").apply { isAccessible = true }
        .get(model) as AudioStreamListener

    /**
     * The heartbeat is a `while (true)` loop that only ends when the view model is
     * cleared. It has to be stopped, and joined, before runTest inspects the scheduler,
     * or every test fails with UncompletedCoroutinesError.
     */
    private suspend fun stopHeartbeat() {
        val field = AIAssistantViewModel::class.java.getDeclaredField("heartbeatJob")
            .apply { isAccessible = true }
        (field.get(model) as? Job)?.cancelAndJoin()
    }

    /** Initialise, then silence the heartbeat so virtual time can be advanced freely. */
    private suspend fun TestScope.initializeQuietly() {
        model.initialize()
        runCurrent()
        stopHeartbeat()
    }

    private fun aiTest(body: suspend TestScope.() -> Unit) = scope.runTest {
        try {
            body()
        } finally {
            stopHeartbeat()
        }
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        application = ApplicationProvider.getApplicationContext()
        mockkObject(CxrApi.Companion)
        every { CxrApi.getInstance() } returns cxrApi
        mockkConstructor(GeminiService::class, SpeechToTextService::class, TextToSpeechService::class)
        every { anyConstructed<TextToSpeechService>().initSystemTts() } just Runs
        every { anyConstructed<TextToSpeechService>().stop() } just Runs
        every { anyConstructed<TextToSpeechService>().release() } just Runs
        coEvery { anyConstructed<TextToSpeechService>().speak(any(), any()) } returnsSuccess Unit
        coEvery { anyConstructed<SpeechToTextService>().transcribe(any()) } returnsSuccess "hello"
        coEvery { anyConstructed<GeminiService>().sendMessage(any()) } returnsSuccess "an answer"
        model = AIAssistantViewModel(application)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        unmockkAll()
    }

    @Test
    fun `initialising wires up the glasses listeners and starts the heartbeat`() = aiTest {
        model.initialize()
        runCurrent()

        verify { anyConstructed<TextToSpeechService>().initSystemTts() }
        verify { cxrApi.setAiEventListener(any()) }
        verify { cxrApi.setAudioStreamListener(any()) }
        verify(atLeast = 1) { cxrApi.sendAi_Heartbeat() }

        advanceTimeBy(11_000)
        runCurrent()
        verify(atLeast = 3) { cxrApi.sendAi_Heartbeat() }
    }

    @Test
    fun `an initialisation failure is reported on the screen`() = aiTest {
        every { anyConstructed<TextToSpeechService>().initSystemTts() } throws
            IllegalStateException("no TTS engine")

        model.initialize()

        assertThat(model.uiState.value.error).isEqualTo("Initialization failed: no TTS engine")
    }

    @Test
    fun `a failing heartbeat does not stop the loop`() = aiTest {
        every { cxrApi.sendAi_Heartbeat() } throws IllegalStateException("link down")

        model.initialize()
        advanceTimeBy(11_000)
        runCurrent()

        verify(atLeast = 3) { cxrApi.sendAi_Heartbeat() }
        assertThat(model.uiState.value.error).isNull()
    }

    @Test
    fun `pressing the key starts listening and buffers only what is recorded`() = aiTest {
        initializeQuietly()

        // Audio arriving before the key press is discarded.
        audioStream().onAudioData(byteArrayOf(9, 9), 2)
        aiEvents().onAiKeyDown()
        assertThat(model.uiState.value.isListening).isTrue()
        assertThat(model.uiState.value.currentTranscript).isEqualTo("Listening...")

        audioStream().onAudioData(byteArrayOf(1, 2, 3), 2)
        audioStream().onAudioData(null, 4)
        aiEvents().onAiKeyUp()
        advanceUntilIdle()

        val sent = slot<ByteArray>()
        coVerify { anyConstructed<SpeechToTextService>().transcribe(capture(sent)) }
        assertThat(sent.captured).isEqualTo(byteArrayOf(1, 2))
    }

    @Test
    fun `a spoken exchange reaches the glasses and the conversation history`() = aiTest {
        initializeQuietly()
        aiEvents().onAiKeyDown()
        audioStream().onAudioData(byteArrayOf(1), 1)

        aiEvents().onAiKeyUp()
        advanceUntilIdle()

        verify { cxrApi.sendAsrContent("hello") }
        verify { cxrApi.notifyAsrEnd() }
        verify { cxrApi.sendTtsContent("an answer") }
        assertThat(model.uiState.value.isProcessing).isFalse()
        assertThat(model.uiState.value.lastResponse).isEqualTo("an answer")
        assertThat(model.uiState.value.currentTranscript).isEmpty()
        assertThat(model.uiState.value.conversationHistory.map { it.isUser to it.text })
            .containsExactly(true to "hello", false to "an answer").inOrder()
    }

    @Test
    fun `the glasses are told when playback finishes`() = aiTest {
        val onComplete = slot<() -> Unit>()
        coEvery {
            anyConstructed<TextToSpeechService>().speak(any(), capture(onComplete))
        } returnsSuccess Unit
        initializeQuietly()
        aiEvents().onAiKeyDown()
        aiEvents().onAiKeyUp()
        advanceUntilIdle()

        onComplete.captured.invoke()
        verify { cxrApi.notifyTtsAudioFinished() }

        // A failure while notifying is swallowed rather than crashing playback.
        every { cxrApi.notifyTtsAudioFinished() } throws IllegalStateException("link down")
        onComplete.captured.invoke()
    }

    @Test
    fun `a failed recognition still asks the model, using a placeholder transcript`() = aiTest {
        coEvery { anyConstructed<SpeechToTextService>().transcribe(any()) } returnsFailure
            IOException("whisper offline")
        initializeQuietly()
        aiEvents().onAiKeyDown()

        aiEvents().onAiKeyUp()
        advanceUntilIdle()

        assertThat(model.uiState.value.conversationHistory.first().text)
            .isEqualTo("(Speech recognition failed)")
        coVerify { anyConstructed<GeminiService>().sendMessage("(Speech recognition failed)") }
    }

    @Test
    fun `a failed model call is reported and clears the spinner`() = aiTest {
        coEvery { anyConstructed<GeminiService>().sendMessage(any()) } returnsFailure
            IOException("quota exceeded")
        initializeQuietly()
        aiEvents().onAiKeyDown()

        aiEvents().onAiKeyUp()
        advanceUntilIdle()

        assertThat(model.uiState.value.isProcessing).isFalse()
        assertThat(model.uiState.value.error).isEqualTo("AI response failed: quota exceeded")
        verify(exactly = 0) { cxrApi.sendTtsContent(any()) }
    }

    @Test
    fun `an unexpected processing failure is reported`() = aiTest {
        coEvery { anyConstructed<SpeechToTextService>().transcribe(any()) } throws
            IllegalStateException("decoder crashed")
        initializeQuietly()
        aiEvents().onAiKeyDown()

        aiEvents().onAiKeyUp()
        advanceUntilIdle()

        assertThat(model.uiState.value.isProcessing).isFalse()
        assertThat(model.uiState.value.error).isEqualTo("Processing failed: decoder crashed")
    }

    @Test
    fun `a failure while forwarding to the glasses does not stop the exchange`() = aiTest {
        every { cxrApi.sendAsrContent(any()) } throws IllegalStateException("link down")
        every { cxrApi.sendTtsContent(any()) } throws IllegalStateException("link down")
        initializeQuietly()
        aiEvents().onAiKeyDown()

        aiEvents().onAiKeyUp()
        advanceUntilIdle()

        assertThat(model.uiState.value.lastResponse).isEqualTo("an answer")
        assertThat(model.uiState.value.error).isNull()
    }

    @Test
    fun `exiting the scene stops playback and clears the screen`() = aiTest {
        initializeQuietly()
        aiEvents().onAiKeyDown()

        aiEvents().onAiExit()

        verify { anyConstructed<TextToSpeechService>().stop() }
        assertThat(model.uiState.value).isEqualTo(AIAssistantUiState())
    }

    @Test
    fun `a typed test message goes straight to the model`() = aiTest {
        model.sendTestMessage("what is this?")
        advanceUntilIdle()

        assertThat(model.uiState.value.isProcessing).isFalse()
        assertThat(model.uiState.value.lastResponse).isEqualTo("an answer")
        assertThat(model.uiState.value.conversationHistory.map { it.text })
            .containsExactly("what is this?", "an answer").inOrder()
        coVerify(exactly = 0) { anyConstructed<SpeechToTextService>().transcribe(any()) }
    }

    @Test
    fun `a failed test message is reported`() = aiTest {
        coEvery { anyConstructed<GeminiService>().sendMessage(any()) } returnsFailure
            IOException("offline")

        model.sendTestMessage("hello")
        advanceUntilIdle()

        assertThat(model.uiState.value.isProcessing).isFalse()
        assertThat(model.uiState.value.error).isEqualTo("AI response failed: offline")
    }

    @Test
    fun `stopping speech is delegated to the tts service`() = aiTest {
        model.stopSpeaking()

        verify { anyConstructed<TextToSpeechService>().stop() }
    }

    @Test
    fun `conversation items are stamped when they are created`() {
        val before = System.currentTimeMillis()
        val item = ConversationItem(isUser = true, text = "hi")
        assertThat(item.timestamp).isAtLeast(before)
        assertThat(item.isUser).isTrue()
        assertThat(item.text).isEqualTo("hi")
    }
}
