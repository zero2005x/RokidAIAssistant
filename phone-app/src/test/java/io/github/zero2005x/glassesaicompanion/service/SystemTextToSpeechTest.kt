package io.github.zero2005x.glassesaicompanion.service

import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import io.github.zero2005x.glassesaicompanion.data.TtsProvider
import io.mockk.every
import io.mockk.verify
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
class SystemTextToSpeechTest : TextToSpeechServiceTestBase() {
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
}
