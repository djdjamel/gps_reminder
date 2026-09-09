package com.remindly.location

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GeofenceFilterUtilsTest {

    @Test
    fun testBearingCalculation() {
        // Point A (0, 0), Point B (1, 0) -> North -> 0 degrees
        val bearingNorth = GeofenceFilterUtils.computeBearingDegrees(0.0, 0.0, 1.0, 0.0)
        assertEquals(0f, bearingNorth, 0.5f)

        // Point A (0, 0), Point B (0, 1) -> East -> 90 degrees
        val bearingEast = GeofenceFilterUtils.computeBearingDegrees(0.0, 0.0, 0.0, 1.0)
        assertEquals(90f, bearingEast, 0.5f)

        // Point A (0, 0), Point B (-1, 0) -> South -> 180 degrees
        val bearingSouth = GeofenceFilterUtils.computeBearingDegrees(0.0, 0.0, -1.0, 0.0)
        assertEquals(180f, bearingSouth, 0.5f)

        // Point A (0, 0), Point B (0, -1) -> West -> 270 degrees
        val bearingWest = GeofenceFilterUtils.computeBearingDegrees(0.0, 0.0, 0.0, -1.0)
        assertEquals(270f, bearingWest, 0.5f)
    }

    @Test
    fun testAngleDifference() {
        assertEquals(10f, GeofenceFilterUtils.calculateAngleDifference(0f, 10f), 0.1f)
        assertEquals(10f, GeofenceFilterUtils.calculateAngleDifference(355f, 5f), 0.1f)
        assertEquals(90f, GeofenceFilterUtils.calculateAngleDifference(0f, 90f), 0.1f)
        assertEquals(180f, GeofenceFilterUtils.calculateAngleDifference(0f, 180f), 0.1f)
        assertEquals(170f, GeofenceFilterUtils.calculateAngleDifference(10f, 180f), 0.1f)
    }
}
