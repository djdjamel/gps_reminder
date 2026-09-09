package com.remindly.domain.model

import java.util.Locale

enum class AppLanguage(
    val code: String,
    val displayName: String,
    val nativeName: String,
    val flag: String,
    val locale: Locale,
    val isRtl: Boolean
) {
    FRENCH(
        code = "fr",
        displayName = "Français",
        nativeName = "Français",
        flag = "🇫🇷",
        locale = Locale.FRENCH,
        isRtl = false
    ),
    ARABIC(
        code = "ar",
        displayName = "Arabe / Derja",
        nativeName = "العربية (الدارجة)",
        flag = "🇩🇿",
        locale = Locale("ar", "DZ"),
        isRtl = true
    ),
    ENGLISH(
        code = "en",
        displayName = "Anglais",
        nativeName = "English",
        flag = "🇬🇧",
        locale = Locale.ENGLISH,
        isRtl = false
    );

    val flagEmoji: String get() = flag

    companion object {
        fun fromCode(code: String?): AppLanguage {
            return entries.firstOrNull { it.code.equals(code, ignoreCase = true) } ?: FRENCH
        }
    }
}
