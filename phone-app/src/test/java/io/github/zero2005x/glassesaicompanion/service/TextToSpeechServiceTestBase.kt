package io.github.zero2005x.glassesaicompanion.service

import android.content.Context
import android.speech.tts.TextToSpeech
import io.github.zero2005x.glassesaicompanion.data.ApiSettings
import io.github.zero2005x.glassesaicompanion.data.SettingsRepository
import io.github.zero2005x.glassesaicompanion.data.TtsProvider
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkAll
import org.junit.After
import org.junit.Before

/** Shared fixture: a [TextToSpeechService] whose Android speech engine is a mock. */
abstract class TextToSpeechServiceTestBase {
    protected val context = mockk<Context>(relaxed = true)
    protected val engine = mockk<TextToSpeech>(relaxed = true)
    protected lateinit var service: TextToSpeechService
    protected lateinit var settings: ApiSettings

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

    protected fun field(name: String, value: Any?) {
        TextToSpeechService::class.java.getDeclaredField(name).apply { isAccessible = true }.set(service, value)
    }

    @After
    fun tearDown() {
        service.shutdown()
        unmockkAll()
    }
}
