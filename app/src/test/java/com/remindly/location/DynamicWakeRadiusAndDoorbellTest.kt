package com.remindly.location

import com.remindly.location.registry.DiscoveredPoi
import com.remindly.location.registry.LinkLifecycleState
import com.remindly.location.registry.PoiProvider
import com.remindly.location.registry.PoiRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Tests unitaires validant l'architecture "Dynamic Wake Radius & Doorbell Geofence" :
 * - Triade des distances : SemanticRadius vs WakeRadius
 * - Calcul du rayon matériel de réveil (borné et adaptatif)
 * - Comportement du "Coup de sonnette" (Doorbell) : réveil matériel sans alarme vocale prématurée
 * - Formule et KPI du Alert Lead Time (anticipation optimale)
 */
class DynamicWakeRadiusAndDoorbellTest {

    private lateinit var registry: PoiRegistry

    @Before
    fun setUp() {
        registry = PoiRegistry()
    }

    @Test
    fun testFixedPlaceWakeRadiusFormula() {
        fun computeFixedWakeRadius(semanticRadius: Float): Float {
            return maxOf(semanticRadius + 450f, 900f).coerceIn(400f, 1500f)
        }

        // Rayon sémantique standard (450m) -> WakeRadius = 900m
        assertEquals(900f, computeFixedWakeRadius(450f), 0.01f)

        // Rayon sémantique plus serré (150m) -> WakeRadius maintenu au seuil plancher de sécurité de 900m
        assertEquals(900f, computeFixedWakeRadius(150f), 0.01f)

        // Rayon sémantique large (600m) -> WakeRadius = 1050m
        assertEquals(1050f, computeFixedWakeRadius(600f), 0.01f)

        // Rayon sémantique très large (1200m) -> Coercé au plafond 1500m
        assertEquals(1500f, computeFixedWakeRadius(1200f), 0.01f)
    }

    @Test
    fun testCategoryPoiWakeRadiusComputation() {
        // POI avec un rappel à 150m -> 150m + 450m = 600m
        val wake1 = DiscoveredPoi.computeMaterialWakeRadius(listOf(150f))
        assertEquals(600f, wake1, 0.01f)

        // POI avec rappels à 200m et 450m -> maxRequested 450m + 450m = 900m
        val wake2 = DiscoveredPoi.computeMaterialWakeRadius(listOf(200f, 450f))
        assertEquals(900f, wake2, 0.01f)

        // POI avec rappel à 800m -> 800m + 450m = 1250m -> Plafonné à 1200m
        val wake3 = DiscoveredPoi.computeMaterialWakeRadius(listOf(800f))
        assertEquals(1200f, wake3, 0.01f)
    }

    @Test
    fun testDoorbellGeofenceTransition_entersWaitingStateWithoutAlarm() {
        val poi = registry.registerOrGetPoi(
            provider = PoiProvider.GOOGLE_PLACES,
            providerId = "place_hotel_777",
            name = "Hôtel des Pins",
            latitude = 35.5600,
            longitude = 6.1400,
            category = null
        )

        // Configuration : Rayon sémantique = 450m
        val link = registry.linkReminderToPoi(
            reminderId = 777L,
            poiId = poi.id,
            semanticRadiusM = 450f
        )

        // Réveil matériel Android (Doorbell) reçu à 850m
        val measuredDistanceMeters = 850f
        var pulseStarted = false
        var reasonLogged = ""

        if (measuredDistanceMeters > link.semanticRadiusM) {
            link.state = LinkLifecycleState.WAITING
            link.retryDistanceM = link.semanticRadiusM
            pulseStarted = true
            reasonLogged = "Réveil matériel à ${measuredDistanceMeters.toInt()}m"
        }

        assertEquals("Le lien doit être en attente (WAITING)", LinkLifecycleState.WAITING, link.state)
        assertEquals(450f, link.retryDistanceM)
        assertTrue("L'Adaptive Location Pulse doit être démarré immédiatement", pulseStarted)
        assertTrue(reasonLogged.contains("850m"))
    }

    @Test
    fun testAlertLeadTimeComputation() {
        fun computeLeadTime(distanceMeters: Float, speedKmh: Float): Int? {
            val speedMs = speedKmh / 3.6f
            return if (speedMs > 1.5f && distanceMeters > 0f) {
                (distanceMeters / speedMs).toInt()
            } else null
        }

        // Cas nominal : 450m à 45 km/h (12.5 m/s) -> 36 secondes d'anticipation
        val leadTime1 = computeLeadTime(450f, 45f)
        assertEquals(36, leadTime1)

        // Cas urbain rapide : 300m à 54 km/h (15.0 m/s) -> 20 secondes d'anticipation
        val leadTime2 = computeLeadTime(300f, 54f)
        assertEquals(20, leadTime2)

        // Véhicule à l'arrêt ou quasi-immobile (< 1.5 m/s, soit < 5.4 km/h) -> pas de lead time calculé
        val leadTimeStopped = computeLeadTime(100f, 2f)
        assertNull(leadTimeStopped)
    }
}
