package io.github.zero2005x.glassesaicompanion.data

import android.content.Context
import androidx.annotation.MainThread
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import java.util.Locale

/**
 * Language Manager
 * Responsible for app language switching and persistence
 */
object LanguageManager {
    
    private const val PREFS_NAME = "language_prefs"
    private const val KEY_LANGUAGE_CODE = "language_code"
    
    /**
     * Get current language setting
     */
    fun getCurrentLanguage(context: Context): AppLanguage {
        // On Android 13+ the user can change the per-app language from system
        // Settings; AppCompatDelegate is the source of truth when set.
        val appLocales = AppCompatDelegate.getApplicationLocales()
        if (!appLocales.isEmpty) {
            appLocales[0]?.let { return AppLanguage.fromLocale(it) }
        }

        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val savedCode = prefs.getString(KEY_LANGUAGE_CODE, null)

        return if (savedCode != null) {
            AppLanguage.fromCode(savedCode)
        } else {
            // Use system language
            AppLanguage.fromLocale(Locale.getDefault())
        }
    }
    
    /**
     * Set app language
     */
    fun setLanguage(context: Context, language: AppLanguage) {
        // Save setting
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_LANGUAGE_CODE, language.code)
            .apply()
        
        // Apply language
        applyLanguage(language)
    }
    
    /**
     * Apply language setting.
     * Must be called on the main thread: AppCompatDelegate.setApplicationLocales
     * may recreate the current Activity on pre-API-33 devices.
     */
    @MainThread
    private fun applyLanguage(language: AppLanguage) {
        val localeList = LocaleListCompat.forLanguageTags(language.code)
        // Skip no-op calls to avoid unnecessary Activity recreation
        if (AppCompatDelegate.getApplicationLocales() == localeList) return
        AppCompatDelegate.setApplicationLocales(localeList)
    }
    
    /**
     * Initialize language (call in Application or Activity onCreate)
     */
    fun initialize(context: Context) {
        val currentLanguage = getCurrentLanguage(context)
        applyLanguage(currentLanguage)
    }
    
    /**
     * Get Locale object for language
     */
    fun getLocale(language: AppLanguage): Locale = language.locale
}
