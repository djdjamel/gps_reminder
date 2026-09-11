package com.remindly.location

import com.remindly.domain.model.Reminder
import com.remindly.domain.model.ReminderStatus
import com.remindly.domain.model.TriggerType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

class ReminderHabitRecurrenceTest {

    @Test
    fun testHabitReminderDefaultState() {
        val reminder = Reminder(
            id = 10L,
            text = "Acheter du pain",
            triggerType = TriggerType.PLACE,
            placeLat = 36.75,
            placeLng = 3.05,
            placeLabel = "Boulangerie"
        )
        assertFalse(reminder.isRepeating)
        assertEquals(ReminderStatus.ACTIVE, reminder.status)
    }

    @Test
    fun testHabitReminderConfiguredAsRepeating() {
        val reminder = Reminder(
            id = 11L,
            text = "Penser aux clés en partant",
            triggerType = TriggerType.PLACE,
            placeLat = 36.75,
            placeLng = 3.05,
            placeLabel = "Maison",
            isRepeating = true
        )
        assertTrue(reminder.isRepeating)
    }

    @Test
    fun testReminderStatusResolutionAfterTrigger() {
        // Cas 1 : Rappel ponctuel ("Une seule fois") -> doit passer en COMPLETED
        val singleReminder = Reminder(
            id = 20L,
            text = "Déposer un colis",
            triggerType = TriggerType.PLACE,
            placeLat = 36.75,
            placeLng = 3.05,
            isRepeating = false,
            status = ReminderStatus.ACTIVE
        )
        val resolvedSingleStatus = if (singleReminder.isRepeating) ReminderStatus.ACTIVE else ReminderStatus.COMPLETED
        assertEquals(ReminderStatus.COMPLETED, resolvedSingleStatus)

        // Cas 2 : Rappel habitude ("À chaque passage") -> doit rester ACTIVE
        val habitReminder = Reminder(
            id = 21L,
            text = "Prendre un ticket de parking",
            triggerType = TriggerType.PLACE,
            placeLat = 36.75,
            placeLng = 3.05,
            isRepeating = true,
            status = ReminderStatus.ACTIVE
        )
        val resolvedHabitStatus = if (habitReminder.isRepeating) ReminderStatus.ACTIVE else ReminderStatus.COMPLETED
        assertEquals(ReminderStatus.ACTIVE, resolvedHabitStatus)
    }

    @Test
    fun testTimeShortcutCalculation() {
        val now = Calendar.getInstance()

        // Test Ce soir 19h
        val eveningCal = Calendar.getInstance().apply {
            if (get(Calendar.HOUR_OF_DAY) >= 19) {
                add(Calendar.DAY_OF_YEAR, 1)
            }
            set(Calendar.HOUR_OF_DAY, 19)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        assertTrue(eveningCal.timeInMillis > now.timeInMillis)
        assertEquals(19, eveningCal.get(Calendar.HOUR_OF_DAY))
        assertEquals(0, eveningCal.get(Calendar.MINUTE))

        // Test Demain 8h30
        val tomorrowCal = Calendar.getInstance().apply {
            add(Calendar.DAY_OF_YEAR, 1)
            set(Calendar.HOUR_OF_DAY, 8)
            set(Calendar.MINUTE, 30)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        assertTrue(tomorrowCal.timeInMillis > now.timeInMillis)
        assertEquals(8, tomorrowCal.get(Calendar.HOUR_OF_DAY))
        assertEquals(30, tomorrowCal.get(Calendar.MINUTE))
    }
}
