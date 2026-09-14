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

    @Test
    fun testAppThemeEnum() {
        assertEquals(com.remindly.domain.model.AppTheme.LIGHT, com.remindly.domain.model.AppTheme.fromKey("LIGHT"))
        assertEquals(com.remindly.domain.model.AppTheme.DARK, com.remindly.domain.model.AppTheme.fromKey("DARK"))
        assertEquals(com.remindly.domain.model.AppTheme.SYSTEM, com.remindly.domain.model.AppTheme.fromKey("SYSTEM"))
        assertEquals(com.remindly.domain.model.AppTheme.SYSTEM, com.remindly.domain.model.AppTheme.fromKey("unknown"))
    }

    @Test
    fun testAppStringsThemeRetrieval() {
        val french = getAppStrings("fr")
        assertEquals("Thème et Apparence", french.settingsThemeSectionTitle)
        assertEquals("Clair", french.themeLight)
        assertEquals("Sombre", french.themeDark)
        assertEquals("Système", french.themeSystem)

        val arabic = getAppStrings("ar")
        assertEquals("المظهر والسمة", arabic.settingsThemeSectionTitle)
        assertEquals("فاتح", arabic.themeLight)
        assertEquals("داكن", arabic.themeDark)
        assertEquals("تلقائي (النظام)", arabic.themeSystem)

        val english = getAppStrings("en")
        assertEquals("Theme & Appearance", english.settingsThemeSectionTitle)
        assertEquals("Light", english.themeLight)
        assertEquals("Dark", english.themeDark)
        assertEquals("System", english.themeSystem)
    }
}
