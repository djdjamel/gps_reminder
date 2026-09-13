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

    @Test
    fun testCalculateMaxAccuracyProportionalToRadius() {
        // Pour un rayon de 450m : 450 * 0.30 = 135m
        assertEquals(135f, GeofenceFilterUtils.calculateMaxAccuracy(450f), 0.1f)

        // Pour un rayon de 1000m : 1000 * 0.30 = 300m
        assertEquals(300f, GeofenceFilterUtils.calculateMaxAccuracy(1000f), 0.1f)

        // Pour un rayon de 2500m : 2500 * 0.30 = 750m
        assertEquals(750f, GeofenceFilterUtils.calculateMaxAccuracy(2500f), 0.1f)

        // Pour un petit rayon (150m) : 150 * 0.30 = 45m -> le plancher de sécurité de 50m s'applique
        assertEquals(50f, GeofenceFilterUtils.calculateMaxAccuracy(150f), 0.1f)

        // Si radius est null, le rayon par défaut (450m) s'applique -> 135m
        assertEquals(135f, GeofenceFilterUtils.calculateMaxAccuracy(null), 0.1f)
    }

    @Test
    fun testEvaluateAccuracy_validatesHistoriqueJsonCase() {
        // Cas réel du fichier historique.json :
        // Rayon = 450m, précision GPS relevée = 61m
        // 61m <= 135m (30% de 450m) -> doit être validé avec succès !
        val isValid = GeofenceFilterUtils.evaluateAccuracy(
            accuracyM = 61f,
            radiusM = 450f
        )
        assertTrue("Une précision de 61m pour un rayon de 450m doit être acceptée", isValid)
    }

    @Test
    fun testEvaluateAccuracy_rejectsAberrantAccuracy() {
        // Si la précision est de 200m pour un rayon de 450m (200m > 135m) -> doit être rejeté
        val isValid = GeofenceFilterUtils.evaluateAccuracy(
            accuracyM = 200f,
            radiusM = 450f
        )
        assertFalse("Une précision aberrante de 200m pour un rayon de 450m doit être rejetée", isValid)
    }

    @Test
    fun testEvaluateAccuracy_allowsWhenAccuracyNull() {
        // Si le terminal ne fournit pas l'exactitude, on ne bloque pas
        val isValid = GeofenceFilterUtils.evaluateAccuracy(
            accuracyM = null,
            radiusM = 450f
        )
        assertTrue("Si la précision n'est pas rapportée, elle ne doit pas bloquer", isValid)
    }
}
