package com.remindly.ui.theme

import com.remindly.domain.model.AppLanguage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppLanguageStringsTest {

    @Test
    fun testAppLanguageEnumProperties() {
        assertEquals("fr", AppLanguage.FRENCH.code)
        assertFalse(AppLanguage.FRENCH.isRtl)
        assertEquals("🇫🇷", AppLanguage.FRENCH.flagEmoji)

        assertEquals("ar", AppLanguage.ARABIC.code)
        assertTrue(AppLanguage.ARABIC.isRtl)
        assertEquals("🇩🇿", AppLanguage.ARABIC.flagEmoji)

        assertEquals("en", AppLanguage.ENGLISH.code)
        assertFalse(AppLanguage.ENGLISH.isRtl)
        assertEquals("🇬🇧", AppLanguage.ENGLISH.flagEmoji)

        assertEquals(AppLanguage.FRENCH, AppLanguage.fromCode("fr"))
        assertEquals(AppLanguage.ARABIC, AppLanguage.fromCode("ar"))
        assertEquals(AppLanguage.ENGLISH, AppLanguage.fromCode("en"))
        assertEquals(AppLanguage.FRENCH, AppLanguage.fromCode("unknown"))
    }

    @Test
    fun testAppStringsRetrieval() {
        val french = getAppStrings("fr")
        assertNotNull(french.onboardingWelcomeTitle)
        assertEquals("Paramètres", french.settingsTitle)

        val arabic = getAppStrings("ar")
        assertNotNull(arabic.onboardingWelcomeTitle)
        assertEquals("الإعدادات", arabic.settingsTitle)

        val english = getAppStrings("en")
        assertNotNull(english.onboardingWelcomeTitle)
        assertEquals("Settings", english.settingsTitle)
    }
}
