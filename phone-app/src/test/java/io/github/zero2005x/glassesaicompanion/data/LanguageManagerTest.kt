package io.github.zero2005x.glassesaicompanion.data

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = android.app.Application::class)
class LanguageManagerTest {
    private lateinit var context: Context
    private lateinit var originalLocale: Locale

    @Before
    fun setUp() {
        originalLocale = Locale.getDefault()
        context = ApplicationProvider.getApplicationContext()
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.getEmptyLocaleList())
        context.getSharedPreferences("language_prefs", 0).edit().clear().commit()
    }

    @After
    fun tearDown() {
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.getEmptyLocaleList())
        Locale.setDefault(originalLocale)
    }

    @Test
    fun `unset preference follows system locale`() {
        Locale.setDefault(Locale.JAPAN)
        assertEquals(AppLanguage.JAPANESE, LanguageManager.getCurrentLanguage(context))
    }

    @Test
    fun `saved preference overrides system locale and initializes app locales`() {
        Locale.setDefault(Locale.US)
        context.getSharedPreferences("language_prefs", 0).edit().putString("language_code", "zh_HK").commit()
        assertEquals(AppLanguage.TRADITIONAL_CHINESE, LanguageManager.getCurrentLanguage(context))
        LanguageManager.initialize(context)
        assertEquals("zh-TW", AppCompatDelegate.getApplicationLocales().toLanguageTags())
        LanguageManager.initialize(context)
        assertEquals(AppLanguage.TRADITIONAL_CHINESE, LanguageManager.getCurrentLanguage(context))
    }

    @Test
    fun `app locales take precedence over saved preference`() {
        context.getSharedPreferences("language_prefs", 0).edit().putString("language_code", "en").commit()
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags("fr-CA,ja"))
        assertEquals(AppLanguage.FRENCH, LanguageManager.getCurrentLanguage(context))
    }

    @Test
    fun `switching language persists and applies selection`() {
        LanguageManager.setLanguage(context, AppLanguage.KOREAN)
        assertEquals("ko", context.getSharedPreferences("language_prefs", 0).getString("language_code", null))
        assertEquals("ko", AppCompatDelegate.getApplicationLocales().toLanguageTags())
        assertEquals(Locale.KOREAN, LanguageManager.getLocale(AppLanguage.KOREAN))
        LanguageManager.setLanguage(context, AppLanguage.KOREAN)
        assertEquals(AppLanguage.KOREAN, LanguageManager.getCurrentLanguage(context))
    }
}
