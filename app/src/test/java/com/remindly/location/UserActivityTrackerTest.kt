package com.remindly.location

import com.google.android.gms.location.ActivityTransition
import com.google.android.gms.location.DetectedActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class UserActivityTrackerTest {

    private lateinit var tracker: UserActivityTracker

    @Before
    fun setUp() {
        tracker = UserActivityTracker()
    }

    @Test
    fun testInitialStateIsUnknown() {
        assertEquals(DetectedActivity.UNKNOWN, tracker.currentActivity.value)
        assertTrue(tracker.getRecentHistory().isEmpty())
        assertFalse(tracker.isPostDrivingArrival())
    }

    @Test
    fun testRecordTransitionUpdatesCurrentActivityOnEnter() {
        // Enregistrer entrée en véhicule
        tracker.recordTransition(DetectedActivity.IN_VEHICLE, ActivityTransition.ACTIVITY_TRANSITION_ENTER)
        assertEquals(DetectedActivity.IN_VEHICLE, tracker.currentActivity.value)

        // Enregistrer sortie de véhicule (ne doit pas écraser l'activité courante)
        tracker.recordTransition(DetectedActivity.IN_VEHICLE, ActivityTransition.ACTIVITY_TRANSITION_EXIT)
        assertEquals(DetectedActivity.IN_VEHICLE, tracker.currentActivity.value)

        // Enregistrer entrée en marche à pied
        tracker.recordTransition(DetectedActivity.WALKING, ActivityTransition.ACTIVITY_TRANSITION_ENTER)
        assertEquals(DetectedActivity.WALKING, tracker.currentActivity.value)
    }

    @Test
    fun testHistoryTracksEntriesCappedAtMax() {
        for (i in 1..15) {
            tracker.recordTransition(DetectedActivity.STILL, ActivityTransition.ACTIVITY_TRANSITION_ENTER, i * 1000L)
        }
        val history = tracker.getRecentHistory()
        assertEquals(10, history.size)
        // Le plus récent doit être en tête (ArrayDeque addFirst)
        assertEquals(15000L, history.first().timestamp)
    }

    @Test
    fun testIsPostDrivingArrival_trueWhenWalkingShortlyAfterDriving() {
        val now = 1_000_000L
        val drivingTime = now - 120_000L // Il y a 2 minutes (dans la fenêtre de 5 min)

        // L'utilisateur était en voiture
        tracker.recordTransition(DetectedActivity.IN_VEHICLE, ActivityTransition.ACTIVITY_TRANSITION_ENTER, drivingTime)
        // Puis est descendu et marche à pied
        tracker.recordTransition(DetectedActivity.WALKING, ActivityTransition.ACTIVITY_TRANSITION_ENTER, now)

        val isPostDriving = tracker.isPostDrivingArrival(windowMillis = 5 * 60 * 1000L, now = now)
        assertTrue("Après avoir conduit il y a 2 min, passer à pied doit être détecté comme une arrivée post-conduite", isPostDriving)
    }

    @Test
    fun testIsPostDrivingArrival_falseWhenWindowExpired() {
        val now = 1_000_000L
        val drivingTime = now - 600_000L // Il y a 10 minutes (dépassant la fenêtre de 5 min)

        tracker.recordTransition(DetectedActivity.IN_VEHICLE, ActivityTransition.ACTIVITY_TRANSITION_ENTER, drivingTime)
        tracker.recordTransition(DetectedActivity.WALKING, ActivityTransition.ACTIVITY_TRANSITION_ENTER, now)

        val isPostDriving = tracker.isPostDrivingArrival(windowMillis = 5 * 60 * 1000L, now = now)
        assertFalse("Après 10 minutes, la fenêtre post-conduite doit être expirée", isPostDriving)
    }

    @Test
    fun testIsPostDrivingArrival_falseWhenStillInVehicle() {
        val now = 1_000_000L
        tracker.recordTransition(DetectedActivity.IN_VEHICLE, ActivityTransition.ACTIVITY_TRANSITION_ENTER, now)

        val isPostDriving = tracker.isPostDrivingArrival(windowMillis = 5 * 60 * 1000L, now = now)
        assertFalse("Tant que l'utilisateur est encore dans le véhicule, il ne s'agit pas d'une arrivée", isPostDriving)
    }

    @Test
    fun testResetForTesting() {
        tracker.recordTransition(DetectedActivity.IN_VEHICLE, ActivityTransition.ACTIVITY_TRANSITION_ENTER)
        assertEquals(DetectedActivity.IN_VEHICLE, tracker.currentActivity.value)

        tracker.resetForTesting()
        assertEquals(DetectedActivity.UNKNOWN, tracker.currentActivity.value)
        assertTrue(tracker.getRecentHistory().isEmpty())
    }
}
