package com.remindly.util

import com.remindly.domain.model.PlaceCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class VoiceIntentParserTest {

    @Test
    fun testFrenchPhrases() {
        val result1 = VoiceIntentParser.parse("Rappelle-moi d'acheter du pain à la boulangerie")
        assertEquals(PlaceCategory.BAKERY, result1.detectedCategory)
        assertEquals("Acheter du pain", result1.cleanedReminderText)

        val result2 = VoiceIntentParser.parse("Prendre du doliprane à la pharmacie")
        assertEquals(PlaceCategory.PHARMACY, result2.detectedCategory)
        assertEquals("Prendre du doliprane", result2.cleanedReminderText)

        val result3 = VoiceIntentParser.parse("Faire des courses au supermarché")
        assertEquals(PlaceCategory.SUPERMARKET, result3.detectedCategory)
        assertEquals("Faire des courses", result3.cleanedReminderText)

        val result4 = VoiceIntentParser.parse("Mettre de l'essence à la station-service")
        assertEquals(PlaceCategory.GAS_STATION, result4.detectedCategory)
        assertEquals("Mettre de l'essence", result4.cleanedReminderText)

        val result5 = VoiceIntentParser.parse("Retirer des espèces au distributeur")
        assertEquals(PlaceCategory.ATM, result5.detectedCategory)
        assertEquals("Retirer des espèces", result5.cleanedReminderText)
    }

    @Test
    fun testArabicAndDerjaPhrases() {
        val result1 = VoiceIntentParser.parse("فكرني نشري الخبز من الكوشة")
        assertEquals(PlaceCategory.BAKERY, result1.detectedCategory)
        assertEquals("نشري الخبز", result1.cleanedReminderText)

        val result2 = VoiceIntentParser.parse("نشري دواء من لافارماسي")
        assertEquals(PlaceCategory.PHARMACY, result2.detectedCategory)
        assertEquals("نشري دواء", result2.cleanedReminderText)

        val result3 = VoiceIntentParser.parse("نشري قهوة وسكر مالسوبيرات")
        assertEquals(PlaceCategory.SUPERMARKET, result3.detectedCategory)
        assertEquals("نشري قهوة وسكر", result3.cleanedReminderText)

        val result4 = VoiceIntentParser.parse("نعمر ليسانس من البومبة")
        assertEquals(PlaceCategory.GAS_STATION, result4.detectedCategory)
        assertEquals("نعمر ليسانس", result4.cleanedReminderText)

        val result5 = VoiceIntentParser.parse("نجبد دراهم مالداب")
        assertEquals(PlaceCategory.ATM, result5.detectedCategory)
        assertEquals("نجبد دراهم", result5.cleanedReminderText)
    }

    @Test
    fun testEnglishPhrases() {
        val result1 = VoiceIntentParser.parse("Remind me to buy bread at the bakery")
        assertEquals(PlaceCategory.BAKERY, result1.detectedCategory)
        assertEquals("Buy bread", result1.cleanedReminderText)

        val result2 = VoiceIntentParser.parse("Get medicine from the pharmacy")
        assertEquals(PlaceCategory.PHARMACY, result2.detectedCategory)
        assertEquals("Get medicine", result2.cleanedReminderText)

        val result3 = VoiceIntentParser.parse("Buy milk at the supermarket")
        assertEquals(PlaceCategory.SUPERMARKET, result3.detectedCategory)
        assertEquals("Buy milk", result3.cleanedReminderText)

        val result4 = VoiceIntentParser.parse("Get gas at the gas station")
        assertEquals(PlaceCategory.GAS_STATION, result4.detectedCategory)
        assertEquals("Get gas", result4.cleanedReminderText)

        val result5 = VoiceIntentParser.parse("Withdraw cash from the atm")
        assertEquals(PlaceCategory.ATM, result5.detectedCategory)
        assertEquals("Withdraw cash", result5.cleanedReminderText)
    }
}
