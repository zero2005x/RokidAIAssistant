package io.github.zero2005x.glassesaicompanion.data

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

class AppLanguageTest {
    @Test
    fun `supported codes and locales round trip`() {
        AppLanguage.entries.forEach { language ->
            assertEquals(language, AppLanguage.fromCode(language.code))
            assertEquals(language, AppLanguage.fromLocale(language.locale))
        }
    }

    @Test
    fun `persisted codes tolerate whitespace casing and separators`() {
        mapOf(
            " zh_CN " to AppLanguage.SIMPLIFIED_CHINESE,
            "ZH-tw" to AppLanguage.TRADITIONAL_CHINESE,
            "zh-Hans-CN" to AppLanguage.SIMPLIFIED_CHINESE,
            "en_US" to AppLanguage.ENGLISH,
            "fr-CA" to AppLanguage.FRENCH,
            "es-MX" to AppLanguage.SPANISH
        ).forEach { (code, expected) -> assertEquals(code, expected, AppLanguage.fromCode(code)) }
    }

    @Test
    fun `Chinese script and traditional regions resolve correctly`() {
        listOf("zh-Hant", "zh-Hant-CN", "zh-TW", "zh-HK", "zh-MO").forEach {
            assertEquals(it, AppLanguage.TRADITIONAL_CHINESE, AppLanguage.fromLocale(Locale.forLanguageTag(it)))
        }
        listOf("zh", "zh-CN", "zh-SG", "zh-Hans").forEach {
            assertEquals(it, AppLanguage.SIMPLIFIED_CHINESE, AppLanguage.fromLocale(Locale.forLanguageTag(it)))
        }
    }

    @Test
    fun `unknown and empty preferences fall back to English`() {
        listOf("", " ", "und", "de-DE", "invalid").forEach {
            assertEquals(it, AppLanguage.ENGLISH, AppLanguage.fromCode(it))
        }
        assertEquals(AppLanguage.ENGLISH, AppLanguage.fromLocale(Locale.ROOT))
    }
}
