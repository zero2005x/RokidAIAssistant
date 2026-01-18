package com.example.rokidphone.data

import android.app.LocaleManager
import android.content.Context
import android.os.Build
import android.os.LocaleList
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
     * Apply language setting
     */
    private fun applyLanguage(language: AppLanguage) {
        val localeList = LocaleListCompat.forLanguageTags(language.code)
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
    fun getLocale(language: AppLanguage): Locale {
        return when (language) {
            AppLanguage.SIMPLIFIED_CHINESE -> Locale.SIMPLIFIED_CHINESE
            AppLanguage.TRADITIONAL_CHINESE -> Locale.TRADITIONAL_CHINESE
            AppLanguage.JAPANESE -> Locale.JAPANESE
            AppLanguage.KOREAN -> Locale.KOREAN
            AppLanguage.FRENCH -> Locale.FRENCH
            AppLanguage.ITALIAN -> Locale.ITALIAN
            AppLanguage.SPANISH -> Locale("es")
            AppLanguage.RUSSIAN -> Locale("ru")
            AppLanguage.UKRAINIAN -> Locale("uk")
            AppLanguage.ARABIC -> Locale("ar")
            else -> Locale.ENGLISH
        }
    }
}
