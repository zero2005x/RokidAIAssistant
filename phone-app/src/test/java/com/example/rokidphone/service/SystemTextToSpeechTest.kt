package com.example.rokidphone.service

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import com.example.rokidphone.data.ApiSettings
import com.example.rokidphone.data.SettingsRepository
import com.example.rokidphone.data.TtsProvider
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkAll
import io.mockk.verify
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.spyk
import io.mockk.Runs
import io.mockk.just
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import com.google.common.truth.Truth.assertThat
import java.io.IOException
import okhttp3.Request
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@OptIn(ExperimentalCoroutinesApi::class)
class SystemTextToSpeechTest {
    private val context = mockk<Context>(relaxed = true)
    private val engine = mockk<TextToSpeech>(relaxed = true)
    private lateinit var service: TextToSpeechService
    private lateinit var settings: ApiSettings

    @Before
    fun setUp() {
        settings = ApiSettings(ttsProvider = TtsProvider.SYSTEM_TTS,
            systemTtsSpeechRate = 1.25f, systemTtsPitch = 0.75f)
        val repository = mockk<SettingsRepository>()
        mockkObject(SettingsRepository.Companion)
        every { SettingsRepository.getInstance(context) } returns repository
        every { repository.getSettings() } answers { settings }
        service = TextToSpeechService(context)
        field("tts", engine)
        field("systemTtsReady", true)
        every { engine.setLanguage(any()) } returns TextToSpeech.LANG_AVAILABLE
        every { engine.voices } returns emptySet()
    }

    private fun field(name: String, value: Any?) {
        TextToSpeechService::class.java.getDeclaredField(name).apply { isAccessible = true }.set(service, value)
    }

    @After
    fun tearDown() {
        service.shutdown()
        unmockkAll()
    }

    @Test
    fun `system speech detects scripts sanitizes Korean and applies user controls`() {
        val cases = listOf("안녕 你好" to Locale.KOREAN, "你好" to Locale.TRADITIONAL_CHINESE,
            "こんにちは" to Locale.JAPANESE, "hello" to Locale.getDefault(), " " to Locale.getDefault())
        for ((text, locale) in cases) {
            service.speak(text) { error("System TTS must not emit encoded audio") }
            verify { engine.setLanguage(locale) }
        }
        verify { engine.setSpeechRate(1.25f) }
        verify { engine.setPitch(0.75f) }
        verify { engine.speak("안녕", TextToSpeech.QUEUE_FLUSH, null, null) }
    }

    @Test
    fun `offline voice is preferred but a network voice remains a valid fallback`() {
        val network = Voice("network", Locale.KOREAN, 400, 200, true, emptySet())
        val offline = Voice("offline", Locale.KOREAN, 400, 200, false, emptySet())
        val unrelated = Voice("english", Locale.ENGLISH, 400, 200, false, emptySet())
        every { engine.voices } returns setOf(network, unrelated, offline)
        service.speak("안녕") { }
        verify { engine.voice = offline }
        every { engine.voices } returns setOf(network, unrelated)
        service.speak("안녕") { }
        verify { engine.voice = network }
    }

    @Test
    fun `missing and unsupported locales fall back to the device locale`() {
        for (status in listOf(TextToSpeech.LANG_MISSING_DATA, TextToSpeech.LANG_NOT_SUPPORTED)) {
            every { engine.setLanguage(Locale.JAPANESE) } returns status
            service.speak("こんにちは") { }
        }
        verify(atLeast = 2) { engine.setLanguage(Locale.getDefault()) }
    }

    @Test
    fun `unready or released engines never receive speech`() {
        field("systemTtsReady", false)
        service.speak("hello") { }
        field("systemTtsReady", true)
        field("tts", null)
        service.speak("hello") { }
        verify(exactly = 0) { engine.speak(any<CharSequence>(), any(), any(), any()) }
    }

    @Test
    fun `translate option uses system speech and shutdown releases the engine`() {
        settings = settings.copy(ttsProvider = TtsProvider.GOOGLE_TRANSLATE_TTS)
        service.speak("hello") { }
        verify { engine.speak("hello", TextToSpeech.QUEUE_FLUSH, null, null) }
        service.shutdown()
        verify(exactly = 1) { engine.stop() }
        verify(exactly = 1) { engine.shutdown() }
    }

    /**
     * Drives [TextToSpeechService] against a real [EdgeTtsClient] wired to a fake
     * WebSocket. The client returns `Result<ByteArray>`, and mockk re-boxes value
     * classes it hands back, so a stubbed client would deliver a `Result` where the
     * caller expects the byte array; serving the frames instead keeps the contract
     * honest and exercises the client's own parsing.
     */
    private var edgeResponse: (WebSocketListener, WebSocket) -> Unit = { _, _ -> }
    private val sentFrames = mutableListOf<String>()

    private fun audioFrame(payload: ByteArray): ByteString =
        ("Path:audio".encodeToByteArray() + byteArrayOf(0, 0x82.toByte()) + payload).toByteString()

    private fun useEdge(scope: TestScope) {
        service = spyk(service)
        val factory = object : WebSocket.Factory {
            override fun newWebSocket(request: Request, listener: WebSocketListener): WebSocket {
                val socket = mockk<WebSocket>(relaxed = true)
                every { socket.send(capture(sentFrames)) } returns true
                listener.onOpen(socket, mockk(relaxed = true))
                edgeResponse(listener, socket)
                return socket
            }
        }
        field("edgeTtsClient", EdgeTtsClient(Dispatchers.Unconfined, factory))
        field("ttsScope", scope)
        field("mainDispatcher", Dispatchers.Unconfined)
        settings = settings.copy(ttsProvider = TtsProvider.EDGE_TTS)
        coEvery { service.playAudioData(any()) } just Runs
    }

    @Test
    fun `Edge speech applies the selected Korean voice and delivers encoded audio before playback`() {
        val scope = TestScope()
        useEdge(scope)
        // ttsSpeechRate is a multiplier (1.25 -> +25%); ttsPitch is an offset (0.2 -> +12Hz).
        settings = settings.copy(ttsVoiceOverride = "ko-KR-SunHiNeural", ttsSpeechRate = 1.25f, ttsPitch = 0.2f)
        edgeResponse = { listener, socket ->
            listener.onMessage(socket, audioFrame(byteArrayOf(1, 2)))
            listener.onMessage(socket, audioFrame(byteArrayOf(3)))
            listener.onMessage(socket, "Path:turn.end")
        }
        val chunks = mutableListOf<ByteArray>()
        service.speak("안녕 你好") { chunks += it }
        scope.runCurrent()
        assertThat(chunks.single()).isEqualTo(byteArrayOf(1, 2, 3))
        val ssml = sentFrames.last()
        assertThat(ssml).contains("xml:lang='ko-KR'")
        assertThat(ssml).contains("name='ko-KR-SunHiNeural'")
        assertThat(ssml).contains("rate='+25%'")
        assertThat(ssml).contains("pitch='+12Hz'")
        // Han characters are dropped so a Korean voice does not read them aloud.
        assertThat(ssml).contains("안녕")
        assertThat(ssml).doesNotContain("你好")
        coVerify(exactly = 1) { service.playAudioData(byteArrayOf(1, 2, 3)) }
        verify(exactly = 0) { engine.speak(any<CharSequence>(), any(), any(), any()) }
    }

    @Test
    fun `empty truncated and failed Edge requests fall back to system speech`() {
        val scope = TestScope()
        useEdge(scope)
        settings = settings.copy(ttsVoiceOverride = "", speechLanguage = "en-US")
        // Turn end with no audio frames.
        edgeResponse = { listener, socket -> listener.onMessage(socket, "Path:turn.end") }
        service.speak("hello") { error("Empty audio must not be delivered") }
        scope.runCurrent()
        // Socket closed before the server signalled turn end.
        edgeResponse = { listener, socket -> listener.onClosed(socket, 1006, "interrupted") }
        service.speak("hello") { error("Truncated audio must not be delivered") }
        scope.runCurrent()
        // Transport failure.
        edgeResponse = { listener, socket -> listener.onFailure(socket, IOException("offline"), null) }
        service.speak("hello") { error("Failed audio must not be delivered") }
        scope.runCurrent()
        assertThat(sentFrames.last()).contains("name='en-US-JennyNeural'")
        verify(exactly = 3) { engine.speak("hello", TextToSpeech.QUEUE_FLUSH, null, null) }
        coVerify(exactly = 0) { service.playAudioData(any()) }
    }
}
