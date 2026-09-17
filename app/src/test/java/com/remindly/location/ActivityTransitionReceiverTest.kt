package com.remindly.location

import com.google.android.gms.location.ActivityTransition
import com.google.android.gms.location.DetectedActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ActivityTransitionReceiverTest {

    @Test
    fun testGetActivityLabel_inVehicle() {
        val (icon, label) = ActivityTransitionReceiver.getActivityLabel(DetectedActivity.IN_VEHICLE)
        assertEquals("🚗", icon)
        assertTrue(label.contains("IN_VEHICLE"))
    }

    @Test
    fun testGetActivityLabel_walking() {
        val (icon, label) = ActivityTransitionReceiver.getActivityLabel(DetectedActivity.WALKING)
        assertEquals("🚶", icon)
        assertTrue(label.contains("WALKING"))
    }

    @Test
    fun testGetActivityLabel_running() {
        val (icon, label) = ActivityTransitionReceiver.getActivityLabel(DetectedActivity.RUNNING)
        assertEquals("🏃", icon)
        assertTrue(label.contains("RUNNING"))
    }

    @Test
    fun testGetActivityLabel_bicycle() {
        val (icon, label) = ActivityTransitionReceiver.getActivityLabel(DetectedActivity.ON_BICYCLE)
        assertEquals("🚲", icon)
        assertTrue(label.contains("ON_BICYCLE"))
    }

    @Test
    fun testGetActivityLabel_still() {
        val (icon, label) = ActivityTransitionReceiver.getActivityLabel(DetectedActivity.STILL)
        assertEquals("🧘", icon)
        assertTrue(label.contains("STILL"))
    }

    @Test
    fun testFormatTransitionLog_enterVehicle() {
        val log = ActivityTransitionReceiver.formatTransitionLog(
            DetectedActivity.IN_VEHICLE,
            ActivityTransition.ACTIVITY_TRANSITION_ENTER
        )
        assertTrue("Log should contain vehicle emoji", log.contains("🚗"))
        assertTrue("Log should contain ENTRÉE", log.contains("ENTRÉE"))
        assertTrue("Log should contain IN_VEHICLE", log.contains("IN_VEHICLE"))
    }

    @Test
    fun testFormatTransitionLog_exitVehicle() {
        val log = ActivityTransitionReceiver.formatTransitionLog(
            DetectedActivity.IN_VEHICLE,
            ActivityTransition.ACTIVITY_TRANSITION_EXIT
        )
        assertTrue("Log should contain vehicle emoji", log.contains("🚗"))
        assertTrue("Log should contain SORTIE", log.contains("SORTIE"))
        assertTrue("Log should contain IN_VEHICLE", log.contains("IN_VEHICLE"))
    }

    @Test
    fun testFormatTransitionLog_enterWalking() {
        val log = ActivityTransitionReceiver.formatTransitionLog(
            DetectedActivity.WALKING,
            ActivityTransition.ACTIVITY_TRANSITION_ENTER
        )
        assertTrue("Log should contain walking emoji", log.contains("🚶"))
        assertTrue("Log should contain ENTRÉE", log.contains("ENTRÉE"))
        assertTrue("Log should contain WALKING", log.contains("WALKING"))
    }
}
