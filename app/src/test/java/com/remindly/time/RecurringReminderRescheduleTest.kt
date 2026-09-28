package com.remindly.time

import com.remindly.domain.model.RepeatRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

class RecurringReminderRescheduleTest {

    @Test
    fun testDailyReminder_rescheduledToNextDay() {
        val zone = ZoneId.systemDefault()
        val now = ZonedDateTime.of(2026, 9, 28, 18, 0, 0, 0, zone).toInstant().toEpochMilli()
        val triggerTime = now // Déclenché exactement maintenant à 18h00

        val nextTime = NextOccurrenceCalculator.calculateNext(
            currentTimeMillis = now,
            triggerTimeMillis = triggerTime,
            repeatRule = RepeatRule.DAILY,
            repeatIntervalMin = null,
            repeatDaysMask = null
        )

        assertNotNull(nextTime)
        val nextZdt = Instant.ofEpochMilli(nextTime!!).atZone(zone)
        assertEquals(2026, nextZdt.year)
        assertEquals(9, nextZdt.monthValue)
        assertEquals(29, nextZdt.dayOfMonth)
        assertEquals(18, nextZdt.hour)
        assertEquals(0, nextZdt.minute)
    }

    @Test
    fun testWeeklyReminder_rescheduledToNextWeek() {
        val zone = ZoneId.systemDefault()
        val now = ZonedDateTime.of(2026, 9, 28, 10, 0, 0, 0, zone).toInstant().toEpochMilli()
        val triggerTime = now

        val nextTime = NextOccurrenceCalculator.calculateNext(
            currentTimeMillis = now,
            triggerTimeMillis = triggerTime,
            repeatRule = RepeatRule.WEEKLY,
            repeatIntervalMin = null,
            repeatDaysMask = null
        )

        assertNotNull(nextTime)
        val nextZdt = Instant.ofEpochMilli(nextTime!!).atZone(zone)
        assertEquals(2026, nextZdt.year)
        assertEquals(10, nextZdt.monthValue)
        assertEquals(5, nextZdt.dayOfMonth) // 28 sept + 7 jours = 5 oct
        assertEquals(10, nextZdt.hour)
    }

    @Test
    fun testCustomIntervalReminder_rescheduledCorrectly() {
        val now = 1000000L
        val triggerTime = now
        val intervalMin = 45

        val nextTime = NextOccurrenceCalculator.calculateNext(
            currentTimeMillis = now,
            triggerTimeMillis = triggerTime,
            repeatRule = RepeatRule.CUSTOM,
            repeatIntervalMin = intervalMin,
            repeatDaysMask = null
        )

        assertNotNull(nextTime)
        assertEquals(now + 45 * 60 * 1000L, nextTime)
    }

    @Test
    fun testNonRepeatingReminder_returnsNull() {
        val now = System.currentTimeMillis()
        val nextTime = NextOccurrenceCalculator.calculateNext(
            currentTimeMillis = now,
            triggerTimeMillis = now,
            repeatRule = RepeatRule.NONE,
            repeatIntervalMin = null,
            repeatDaysMask = null
        )

        assertNull(nextTime)
    }

    @Test
    fun testMissedOccurrencesDuringReboot_catchesUpToFuture() {
        val zone = ZoneId.systemDefault()
        // Le rappel quotidien était prévu il y a 3 jours (25 sept à 14h)
        val triggerTime = ZonedDateTime.of(2026, 9, 25, 14, 0, 0, 0, zone).toInstant().toEpochMilli()
        // Le téléphone redémarre aujourd'hui (28 sept à 16h)
        val rebootTime = ZonedDateTime.of(2026, 9, 28, 16, 0, 0, 0, zone).toInstant().toEpochMilli()

        val nextTime = NextOccurrenceCalculator.calculateNext(
            currentTimeMillis = rebootTime,
            triggerTimeMillis = triggerTime,
            repeatRule = RepeatRule.DAILY,
            repeatIntervalMin = null,
            repeatDaysMask = null
        )

        assertNotNull(nextTime)
        val nextZdt = Instant.ofEpochMilli(nextTime!!).atZone(zone)
        // La prochaine occurrence doit être le lendemain 29 sept à 14h
        assertEquals(2026, nextZdt.year)
        assertEquals(9, nextZdt.monthValue)
        assertEquals(29, nextZdt.dayOfMonth)
        assertEquals(14, nextZdt.hour)
        assertTrue(nextTime > rebootTime)
    }
}
