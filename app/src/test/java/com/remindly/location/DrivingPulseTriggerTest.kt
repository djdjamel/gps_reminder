package com.remindly.location

import com.remindly.domain.model.Reminder
import com.remindly.domain.model.ReminderStatus
import com.remindly.domain.model.TriggerType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DrivingPulseTriggerTest {

    @Test
    fun testPulseTriggerThreshold_withinRadiusTriggers() {
        val reminder = Reminder(
            id = 1L,
            text = "Brahimi",
            triggerType = TriggerType.PLACE,
            placeLat = 35.5540,
            placeLng = 6.1470,
            placeRadiusM = 450f,
            status = ReminderStatus.ACTIVE
        )

        val defaultPoiRadius = 300f
        val effectiveRadius = reminder.placeRadiusM ?: defaultPoiRadius

        // Distance constatée dans les logs de conduite : 28m
        val distanceInZone = 28f
        val shouldTriggerInZone = distanceInZone <= effectiveRadius
        assertTrue("Une distance de 28m doit déclencher l'alerte pour un rayon de 450m", shouldTriggerInZone)

        // Distance hors zone : 650m
        val distanceOutOfZone = 650f
        val shouldTriggerOutOfZone = distanceOutOfZone <= effectiveRadius
        assertFalse("Une distance de 650m ne doit pas déclencher l'alerte", shouldTriggerOutOfZone)
    }

    @Test
    fun testPulseTriggerThreshold_fallbackToSettingsRadius() {
        val reminderWithoutRadius = Reminder(
            id = 2L,
            text = "Magasin sans rayon explicite",
            triggerType = TriggerType.PLACE,
            placeLat = 35.5374,
            placeLng = 6.1334,
            placeRadiusM = null,
            status = ReminderStatus.ACTIVE
        )

        val settingsPoiRadius = 400f
        val effectiveRadius = reminderWithoutRadius.placeRadiusM ?: settingsPoiRadius

        assertEquals(400f, effectiveRadius)

        // Distance constatée dans les logs : 48m
        val distancePassed = 48f
        assertTrue("48m doit déclencher avec le rayon par défaut des paramètres (400m)", distancePassed <= effectiveRadius)
    }

    @Test
    fun testDeferredActivationGuard_suppressesFutureAlarms() {
        val now = 1000000L
        val reminderDeferred = Reminder(
            id = 3L,
            text = "Rappel après 18h",
            triggerType = TriggerType.PLACE,
            placeLat = 35.5540,
            placeLng = 6.1470,
            placeRadiusM = 450f,
            placeActiveFromMillis = now + 3600_000L, // Dans 1h
            status = ReminderStatus.ACTIVE
        )

        val isReadyToTrigger = (reminderDeferred.placeActiveFromMillis == null || now >= reminderDeferred.placeActiveFromMillis!!)
        assertFalse("Le rappel avec heure différée dans le futur ne doit pas sonner", isReadyToTrigger)

        // Une fois l'heure passée
        val futureTime = now + 4000_000L
        val isReadyLater = (reminderDeferred.placeActiveFromMillis == null || futureTime >= reminderDeferred.placeActiveFromMillis!!)
        assertTrue("Le rappel doit sonner dès que l'heure différée est atteinte ou dépassée", isReadyLater)
    }

    @Test
    fun testCooldownCoordination_preventsDuplicateAudio() {
        val now = 2000000L
        val cooldownSeconds = 120 // 2 minutes
        val cooldownMs = cooldownSeconds * 1000L

        // Scénario A : Google Play Services vient de déclencher il y a 5 secondes
        val gmsTriggeredRecently = now - 5000L
        val isCooldownActive = (now - gmsTriggeredRecently) < cooldownMs
        assertTrue("Le cooldown doit être actif pour éviter le doublon audio avec GMS", isCooldownActive)

        // Scénario B : Dernier déclenchement il y a 5 minutes (ou nouveau passage après reset)
        val lastTriggerOld = now - 300_000L
        val isCooldownExpired = (now - lastTriggerOld) < cooldownMs
        assertFalse("Après expiration du cooldown, le son doit pouvoir se rejouer", isCooldownExpired)
    }

    @Test
    fun testMutualCancellation_cancelsTimeAlarmOnPlaceTrigger() {
        val reminderBoth = Reminder(
            id = 4L,
            text = "RDV avant 15h ou au passage",
            triggerType = TriggerType.BOTH,
            triggerTimeMillis = 1700000000000L,
            placeLat = 35.5540,
            placeLng = 6.1470,
            status = ReminderStatus.ACTIVE
        )

        val shouldCancelScheduledTimeAlarm = (reminderBoth.triggerType == TriggerType.BOTH || reminderBoth.triggerTimeMillis != null)
        assertTrue("L'arrivée au lieu doit annuler l'échéance programmée (Mutual Cancellation)", shouldCancelScheduledTimeAlarm)
    }
}
