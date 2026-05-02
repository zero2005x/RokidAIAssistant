package com.example.rokidphone.service

import android.content.Context
import com.google.common.truth.Truth.assertThat
import io.mockk.mockk
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale

/**
 * Unit tests for [TextToSpeechService.sanitizeForTts].
 * Verifies that Han characters are stripped from Korean output but other locales
 * are passed through untouched.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class PhoneAIServiceSanitizeForTtsTest {

    private fun newService(): TextToSpeechService =
        TextToSpeechService(mockk<Context>(relaxed = true))

    @Test
    fun `sanitizeForTts - Korean locale strips embedded Han characters`() {
        val service = newService()
        val result = service.sanitizeForTts("안녕하세요 你好 world", Locale.KOREAN)
        // Han characters removed; collapsed double-space normalized to single space.
        assertThat(result).isEqualTo("안녕하세요 world")
        assertThat(result).doesNotContain("你")
        assertThat(result).doesNotContain("好")
    }

    @Test
    fun `sanitizeForTts - English locale leaves input unchanged`() {
        val service = newService()
        val input = "Hello 你好 world"
        val result = service.sanitizeForTts(input, Locale.ENGLISH)
        assertThat(result).isEqualTo(input)
    }

    @Test
    fun `sanitizeForTts - pure Korean text passes through unchanged`() {
        val service = newService()
        val input = "안녕하세요"
        val result = service.sanitizeForTts(input, Locale.KOREAN)
        assertThat(result).isEqualTo(input)
    }
}
