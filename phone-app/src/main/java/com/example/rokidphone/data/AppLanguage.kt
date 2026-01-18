package com.example.rokidphone.data

import java.util.Locale

/**
 * Supported app languages
 */
enum class AppLanguage(
    val code: String,
    val displayName: String,
    val nativeName: String,
    val locale: Locale
) {
    ENGLISH(
        code = "en",
        displayName = "English",
        nativeName = "English",
        locale = Locale.ENGLISH
    ),
    SIMPLIFIED_CHINESE(
        code = "zh-CN",
        displayName = "Simplified Chinese",
        nativeName = "简体中文",
        locale = Locale.SIMPLIFIED_CHINESE
    ),
    TRADITIONAL_CHINESE(
        code = "zh-TW",
        displayName = "Traditional Chinese",
        nativeName = "繁體中文",
        locale = Locale.TRADITIONAL_CHINESE
    ),
    SPANISH(
        code = "es",
        displayName = "Spanish",
        nativeName = "Español",
        locale = Locale("es")
    ),
    FRENCH(
        code = "fr",
        displayName = "French",
        nativeName = "Français",
        locale = Locale.FRENCH
    ),
    JAPANESE(
        code = "ja",
        displayName = "Japanese",
        nativeName = "日本語",
        locale = Locale.JAPANESE
    ),
    RUSSIAN(
        code = "ru",
        displayName = "Russian",
        nativeName = "Русский",
        locale = Locale("ru")
    ),
    KOREAN(
        code = "ko",
        displayName = "Korean",
        nativeName = "한국어",
        locale = Locale.KOREAN
    ),
    UKRAINIAN(
        code = "uk",
        displayName = "Ukrainian",
        nativeName = "Українська",
        locale = Locale("uk")
    ),
    ARABIC(
        code = "ar",
        displayName = "Arabic",
        nativeName = "العربية",
        locale = Locale("ar")
    ),
    ITALIAN(
        code = "it",
        displayName = "Italian",
        nativeName = "Italiano",
        locale = Locale.ITALIAN
    ),
    VIETNAMESE(
        code = "vi",
        displayName = "Vietnamese",
        nativeName = "Tiếng Việt",
        locale = Locale("vi")
    ),
    THAI(
        code = "th",
        displayName = "Thai",
        nativeName = "ไทย",
        locale = Locale("th")
    );
    
    companion object {
        fun fromCode(code: String): AppLanguage {
            return entries.find { it.code == code } ?: ENGLISH
        }
        
        fun fromLocale(locale: Locale): AppLanguage {
            // Try exact match first
            val exactMatch = entries.find { 
                it.locale.language == locale.language && 
                (it.locale.country.isEmpty() || it.locale.country == locale.country)
            }
            if (exactMatch != null) return exactMatch
            
            // Then try language-only match
            return entries.find { it.locale.language == locale.language } ?: ENGLISH
        }
    }
}
