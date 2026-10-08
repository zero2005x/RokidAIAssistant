package io.github.zero2005x.glassesaicompanion.data

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
        locale = Locale.forLanguageTag("es")
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
        locale = Locale.forLanguageTag("ru")
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
        locale = Locale.forLanguageTag("uk")
    ),
    ARABIC(
        code = "ar",
        displayName = "Arabic",
        nativeName = "العربية",
        locale = Locale.forLanguageTag("ar")
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
        locale = Locale.forLanguageTag("vi")
    ),
    THAI(
        code = "th",
        displayName = "Thai",
        nativeName = "ไทย",
        locale = Locale.forLanguageTag("th")
    );
    
    companion object {
        fun fromCode(code: String): AppLanguage {
            // Normalise separators/casing so realistic inputs ("zh_CN", "ZH-CN",
            // "zh-Hans-CN", "en-US") still resolve instead of silently resetting
            // the persisted preference; fall back to locale-based resolution.
            val normalized = code.trim().replace('_', '-')
            return entries.find { it.code.equals(normalized, ignoreCase = true) }
                ?: fromLocale(Locale.forLanguageTag(normalized))
        }

        fun fromLocale(locale: Locale): AppLanguage {
            // Chinese must be disambiguated by script/region, not declaration order
            if (locale.language == "zh") {
                val isTraditional = locale.script == "Hant" ||
                    locale.country in setOf("TW", "HK", "MO")
                return if (isTraditional) TRADITIONAL_CHINESE else SIMPLIFIED_CHINESE
            }

            // Try exact match first (strict country comparison)
            val exactMatch = entries.find {
                it.locale.language == locale.language && it.locale.country == locale.country
            }
            if (exactMatch != null) return exactMatch

            // Then try language-only match
            return entries.find { it.locale.language == locale.language } ?: ENGLISH
        }
    }
}
