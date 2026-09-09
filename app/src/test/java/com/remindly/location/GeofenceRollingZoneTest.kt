package com.remindly.location

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GeofenceRollingZoneTest {

    @Test
    fun testShortDistanceExitIsRejected() {
        // Cas observé dans les logs: 400m parcourus pour une zone de 2500m
        val result = GeofenceFilterUtils.evaluateRollingZoneExit(
            actualDistanceM = 400f,
            exitRadiusM = 2500f,
            accuracyM = 15f
        )
        assertFalse("Une distance de 400m doit être rejetée pour un rayon de 2500m", result.isValid)
        assertTrue(result.reason.contains("Distance réelle insuffisante"))
    }

    @Test
    fun testInaccurateGpsAccuracyIsRejected() {
        // Même si la distance est suffisante (2600m), une précision dégradée (85m > 70m) doit être rejetée
        val result = GeofenceFilterUtils.evaluateRollingZoneExit(
            actualDistanceM = 2600f,
            exitRadiusM = 2500f,
            accuracyM = 85f
        )
        assertFalse("Un signal GPS avec 85m d'imprécision doit être rejeté", result.isValid)
        assertTrue(result.reason.contains("Précision GPS insuffisante"))
    }

    @Test
    fun testValidExitIsAccepted() {
        // Distance au-delà du seuil de 80% (ex: 2100m >= 2000m) avec bonne précision GPS (12m)
        val result = GeofenceFilterUtils.evaluateRollingZoneExit(
            actualDistanceM = 2100f,
            exitRadiusM = 2500f,
            accuracyM = 12f
        )
        assertTrue("Une vraie sortie (2100m / 2500m) avec GPS précis doit être acceptée", result.isValid)
    }

    @Test
    fun testFullExitDistanceIsAccepted() {
        val result = GeofenceFilterUtils.evaluateRollingZoneExit(
            actualDistanceM = 2750f,
            exitRadiusM = 2500f,
            accuracyM = 20f
        )
        assertTrue("Une sortie à 2750m doit être acceptée", result.isValid)
    }
}
