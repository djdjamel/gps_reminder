package com.remindly.location

import com.remindly.domain.model.Reminder
import com.remindly.domain.model.TriggerType
import org.junit.Assert.assertEquals
import org.junit.Test

class ReminderPlaceRadiusTest {

    @Test
    fun testCustomPlaceRadiusPreserved() {
        val reminder = Reminder(
            id = 1L,
            text = "Prendre un café",
            triggerType = TriggerType.PLACE,
            placeLat = 35.55,
            placeLng = 6.17,
            placeRadiusM = 150f,
            placeLabel = "Street Coffee"
        )
        assertEquals(150f, reminder.placeRadiusM)
    }

    @Test
    fun testTransitPlaceRadius() {
        val reminder = Reminder(
            id = 2L,
            text = "Réveil gare de Batna",
            triggerType = TriggerType.PLACE,
            placeLat = 35.56,
            placeLng = 6.18,
            placeRadiusM = 2500f,
            placeLabel = "Gare ferroviaire"
        )
        assertEquals(2500f, reminder.placeRadiusM)
    }

    @Test
    fun testFallbackWhenRadiusIsNull() {
        val reminder = Reminder(
            id = 3L,
            text = "Rappel ancien",
            triggerType = TriggerType.PLACE,
            placeLat = 35.55,
            placeLng = 6.17,
            placeRadiusM = null,
            placeLabel = "Lieu sans rayon explicite"
        )
        val defaultSettingsRadius = 450f
        val effectiveRadius = reminder.placeRadiusM ?: defaultSettingsRadius
        assertEquals(450f, effectiveRadius)
    }
}
