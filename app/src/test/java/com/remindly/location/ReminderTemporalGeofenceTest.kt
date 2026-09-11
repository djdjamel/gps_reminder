package com.remindly.location

import com.remindly.domain.model.Reminder
import com.remindly.domain.model.ReminderStatus
import com.remindly.domain.model.TriggerType
import org.junit.Assert.*
import org.junit.Test

/**
 * Tests unitaires validant :
 * 1. Option 1 : "Au lieu, à partir de cette heure" (activation différée et garde-fou temporel)
 * 2. Option 2 : "Au premier événement : Lieu OU Échéance au plus tard" (désarmement mutuel et clôture)
 */
class ReminderTemporalGeofenceTest {

    // ── Option 1 : Activation Différée du Lieu ────────────────────────────────

    @Test
    fun testDeferredActivation_suppressesTrigger_whenBeforeActivationTime() {
        val activationTime = 1_800_000_000_000L // Ex: 18h00
        val currentTime = 1_799_999_000_000L    // Ex: 17h45 (Avant)

        val result = GeofenceFilterUtils.evaluateDeferredActivation(
            placeActiveFromMillis = activationTime,
            currentTimeMillis = currentTime
        )

        assertFalse("Le géofence ne doit pas se déclencher avant l'heure d'activation choisie", result.isActivated)
        assertTrue(result.reason.contains("non encore atteinte"))
    }

    @Test
    fun testDeferredActivation_allowsTrigger_whenAtOrAfterActivationTime() {
        val activationTime = 1_800_000_000_000L // Ex: 18h00
        val currentTime = 1_800_000_060_000L    // Ex: 18h01 (Après)

        val result = GeofenceFilterUtils.evaluateDeferredActivation(
            placeActiveFromMillis = activationTime,
            currentTimeMillis = currentTime
        )

        assertTrue("Le géofence doit être autorisé à se déclencher dès que l'heure d'activation est atteinte", result.isActivated)
    }

    @Test
    fun testImmediatePlaceReminder_triggersWithoutTimeRestriction_whenFieldIsEmpty() {
        val currentTime = System.currentTimeMillis()

        val result = GeofenceFilterUtils.evaluateDeferredActivation(
            placeActiveFromMillis = null, // Champ optionnel vide
            currentTimeMillis = currentTime
        )

        assertTrue("Sans heure d'activation différée, le lieu est actif immédiatement", result.isActivated)
    }

    // ── Option 2 : Condition "Lieu OU Échéance" (Désarmement mutuel) ─────────

    @Test
    fun testMutualCancellation_whenArrivingAtPlaceFirst_cancelsDeadlineAlarm() {
        val reminder = Reminder(
            id = 101L,
            text = "Acheter des piles",
            status = ReminderStatus.ACTIVE,
            triggerType = TriggerType.BOTH,
            triggerTimeMillis = System.currentTimeMillis() + 3600_000L, // Échéance dans 1h
            placeLat = 48.8566,
            placeLng = 2.3522,
            placeLabel = "Supermarché",
            isRepeating = false
        )

        val hasPlace = reminder.placeLat != null || reminder.placeCategory != null
        val hasDeadline = reminder.triggerTimeMillis != null

        val action = GeofenceFilterUtils.evaluateMutualCancellation(
            hasPlace = hasPlace,
            hasDeadline = hasDeadline,
            isRepeating = reminder.isRepeating,
            source = GeofenceFilterUtils.TriggerEventSource.GEOFENCE_ENTER
        )

        assertTrue("L'alarme d'échéance doit être annulée car l'utilisateur est arrivé au lieu d'abord", action.shouldCancelAlarm)
        assertTrue("Le géofence doit être retiré après déclenchement pour rappel unique", action.shouldRemoveGeofence)
        assertTrue("Le rappel doit être marqué comme terminé", action.shouldCompleteReminder)
    }

    @Test
    fun testMutualCancellation_whenDeadlineArrivesFirst_disarmsGeofence() {
        val reminder = Reminder(
            id = 102L,
            text = "Passer à la pharmacie",
            status = ReminderStatus.ACTIVE,
            triggerType = TriggerType.BOTH,
            triggerTimeMillis = System.currentTimeMillis(), // Échéance échue
            placeLat = 48.8600,
            placeLng = 2.3400,
            placeLabel = "Pharmacie",
            isRepeating = false
        )

        val hasPlace = reminder.placeLat != null || reminder.placeCategory != null
        val hasDeadline = reminder.triggerTimeMillis != null

        val action = GeofenceFilterUtils.evaluateMutualCancellation(
            hasPlace = hasPlace,
            hasDeadline = hasDeadline,
            isRepeating = reminder.isRepeating,
            source = GeofenceFilterUtils.TriggerEventSource.ALARM_DEADLINE
        )

        assertTrue("Le géofence doit être désarmé car l'échéance est passée sans visite du lieu", action.shouldRemoveGeofence)
        assertFalse("Pas besoin d'annuler l'alarme qui est déjà en cours de déclenchement", action.shouldCancelAlarm)
        assertTrue("Le rappel doit être clôturé", action.shouldCompleteReminder)
    }

    @Test
    fun testMutualCancellation_whenRepeatingHabit_doesNotCompleteReminder() {
        val repeatingReminder = Reminder(
            id = 103L,
            text = "Habitude quotidienne au café",
            status = ReminderStatus.ACTIVE,
            triggerType = TriggerType.BOTH,
            triggerTimeMillis = System.currentTimeMillis() + 7200_000L,
            placeLat = 48.8500,
            placeLng = 2.3500,
            placeLabel = "Café",
            isRepeating = true // Mode habitude
        )

        val action = GeofenceFilterUtils.evaluateMutualCancellation(
            hasPlace = true,
            hasDeadline = true,
            isRepeating = repeatingReminder.isRepeating,
            source = GeofenceFilterUtils.TriggerEventSource.GEOFENCE_ENTER
        )

        assertTrue("L'alarme du jour doit être annulée", action.shouldCancelAlarm)
        assertFalse("Le géofence d'un rappel répété DOIT rester actif pour les prochains jours", action.shouldRemoveGeofence)
        assertFalse("Le rappel répété ne doit PAS être clôturé (statut COMPLETED)", action.shouldCompleteReminder)
    }

    @Test
    fun testReminderModel_preservesPlaceActiveFromMillis() {
        val activeTimestamp = 1_750_000_000_000L
        val reminder = Reminder(
            id = 104L,
            text = "Boulangerie après 17h",
            triggerType = TriggerType.PLACE,
            placeLat = 48.8500,
            placeLng = 2.3500,
            placeLabel = "Boulangerie",
            placeActiveFromMillis = activeTimestamp
        )

        assertEquals(activeTimestamp, reminder.placeActiveFromMillis)
    }
}
