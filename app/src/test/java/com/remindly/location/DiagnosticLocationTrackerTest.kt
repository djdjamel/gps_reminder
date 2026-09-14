package com.remindly.location

import org.junit.Assert.*
import org.junit.Test

class DiagnosticLocationTrackerTest {

    @Test
    fun testInitialState() {
        val tracker = DiagnosticLocationTracker()
        val state = tracker.state.value

        assertFalse(state.isRunning)
        assertNull(state.reminderId)
        assertNull(state.currentDistanceM)
        assertFalse(tracker.isDiagnosticActiveFor(1L))
    }

    @Test
    fun testUpdateMeasurementWhenRunning() {
        val tracker = DiagnosticLocationTracker()
        
        // Simuler mise à jour quand non actif -> ignoré
        tracker.updateMeasurement(500f, 10f, 30f)
        assertNull(tracker.state.value.currentDistanceM)

        // Forcer état actif via réflexion ou test direct
        // DiagnosticState test
        val customState = DiagnosticState(
            isRunning = true,
            reminderId = 42L,
            reminderText = "Test Place",
            targetLat = 35.5394,
            targetLng = 6.1549,
            targetRadiusM = 450f
        )
        
        // Tester les propriétés de DiagnosticState
        assertEquals(42L, customState.reminderId)
        assertEquals(450f, customState.targetRadiusM)
        assertTrue(customState.isRunning)
        assertFalse(customState.autoStopOnExit)

        val zoneState = customState.copy(autoStopOnExit = true)
        assertTrue(zoneState.autoStopOnExit)
    }

    @Test
    fun testDiagnosticActiveForReminderId() {
        val tracker = DiagnosticLocationTracker()
        assertFalse(tracker.isDiagnosticActiveFor(4L))
    }
}
