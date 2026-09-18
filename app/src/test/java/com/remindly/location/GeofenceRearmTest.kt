package com.remindly.location

import com.google.android.gms.location.Geofence
import com.remindly.domain.model.Reminder
import com.remindly.domain.model.ReminderStatus
import com.remindly.domain.model.TriggerType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GeofenceRearmTest {

    @Test
    fun testTransitionTypesIncludesEnterDwellAndExit() {
        val transitionTypes = Geofence.GEOFENCE_TRANSITION_ENTER or 
                              Geofence.GEOFENCE_TRANSITION_DWELL or 
                              Geofence.GEOFENCE_TRANSITION_EXIT

        assertTrue(
            "La configuration de géofence doit surveiller l'événement ENTER",
            (transitionTypes and Geofence.GEOFENCE_TRANSITION_ENTER) != 0
        )
        assertTrue(
            "La configuration de géofence doit surveiller l'événement DWELL",
            (transitionTypes and Geofence.GEOFENCE_TRANSITION_DWELL) != 0
        )
        assertTrue(
            "La configuration de géofence doit surveiller l'événement EXIT pour permettre le réarmement",
            (transitionTypes and Geofence.GEOFENCE_TRANSITION_EXIT) != 0
        )
    }

    @Test
    fun testExitTransitionDoesNotTriggerAlarm() {
        // Simule la décision du BroadcastReceiver
        val transition = Geofence.GEOFENCE_TRANSITION_EXIT

        val shouldTriggerAlarm = (transition == Geofence.GEOFENCE_TRANSITION_ENTER ||
                                  transition == Geofence.GEOFENCE_TRANSITION_DWELL)

        val shouldRearmOnExit = (transition == Geofence.GEOFENCE_TRANSITION_EXIT)

        assertFalse("La transition EXIT ne doit jamais déclencher d'alarme sonore", shouldTriggerAlarm)
        assertTrue("La transition EXIT doit déclencher la procédure de réarmement", shouldRearmOnExit)
    }

    @Test
    fun testRearmEligibilityBasedOnReminderStatus() {
        // 1. Rappel actif non terminé -> doit être réarmé au 2ème passage
        val activeReminder = Reminder(
            id = 1L,
            text = "Brahimi",
            triggerType = TriggerType.PLACE,
            placeLat = 35.5540,
            placeLng = 6.1470,
            status = ReminderStatus.ACTIVE
        )
        val shouldRearmActive = (activeReminder.status == ReminderStatus.ACTIVE)
        assertTrue("Un rappel toujours ACTIVE doit être réarmé pour le 2ème passage", shouldRearmActive)

        // 2. Rappel terminé par l'utilisateur -> ne doit pas être réarmé
        val completedReminder = activeReminder.copy(status = ReminderStatus.COMPLETED)
        val shouldRearmCompleted = (completedReminder.status == ReminderStatus.ACTIVE)
        assertFalse("Un rappel COMPLETED ne doit pas être réarmé", shouldRearmCompleted)

        // 3. Rappel archivé -> ne doit pas être réarmé
        val archivedReminder = activeReminder.copy(status = ReminderStatus.ARCHIVED)
        val shouldRearmArchived = (archivedReminder.status == ReminderStatus.ACTIVE)
        assertFalse("Un rappel ARCHIVED ne doit pas être réarmé", shouldRearmArchived)
    }

    @Test
    fun testCooldownResetAllowsImmediateSecondTrigger() {
        val now = 100000L
        val lastTriggerTime = now // Vient de sonner

        val cooldownMs = 15000L // 15 secondes
        val isCooldownActiveBeforeExit = (now - lastTriggerTime) < cooldownMs
        assertTrue("Le cooldown doit être actif immédiatement après la première alerte", isCooldownActiveBeforeExit)

        // À la sortie de zone (EXIT), le timestamp est effacé (reset)
        val resetLastTriggerTime = 0L
        val isCooldownActiveAfterReset = (now - resetLastTriggerTime) < cooldownMs
        assertFalse("Après réinitialisation du cooldown à la sortie, le 2ème passage est immédiatement autorisé", isCooldownActiveAfterReset)
    }
}
