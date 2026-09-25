package com.remindly.location

import com.google.android.gms.location.DetectedActivity
import com.remindly.domain.model.PlaceCategory
import com.remindly.domain.model.Reminder
import com.remindly.domain.model.ReminderStatus
import com.remindly.domain.model.TriggerType
import com.remindly.location.registry.LinkLifecycleState
import com.remindly.location.registry.PoiProvider
import com.remindly.location.registry.PoiRegistry
import com.remindly.location.scheduler.ContextualGeofenceScheduler
import com.remindly.location.scheduler.GeofenceDiffEngine
import com.remindly.location.scheduler.GeofenceStateSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Tests unitaires complémentaires couvrant spécifiquement les points 13, 14 et 15
 * demandés par ChatGPT :
 * - Point 13 : Reboot Recovery (restauration Room -> PoiRegistry -> Scheduler -> GeofenceState)
 * - Point 14 : Cap et approche prévalent sur la simple proximité brute (B/C devant > A derrière)
 * - Point 15 : Différentiel Activity Recognition (même POI, même distance, Walking vs 110 km/h)
 */
class RebootAndContextIntegrityTest {

    private lateinit var registry: PoiRegistry
    private lateinit var scheduler: ContextualGeofenceScheduler
    private lateinit var diffEngine: GeofenceDiffEngine
    private lateinit var contextEngine: ContextRelevanceEngine
    private lateinit var tracker: UserActivityTracker
    private lateinit var settingsRepo: FakeVoiceAlarmSettingsRepository

    @Before
    fun setUp() {
        registry = PoiRegistry()
        scheduler = ContextualGeofenceScheduler(registry)
        diffEngine = GeofenceDiffEngine()
        tracker = UserActivityTracker()
        settingsRepo = FakeVoiceAlarmSettingsRepository()
        contextEngine = ContextRelevanceEngine(tracker, settingsRepo)
    }

    /**
     * Point 13 : Test de Restauration Reboot
     * Simule : redémarrage du smartphone -> lecture de Room -> reconstruction du PoiRegistry ->
     * ordonnancement -> transition DiffEngine vers ActualState.
     */
    @Test
    fun testRebootRecoveryRestoresActiveGeofences() {
        // 1. État avant reboot dans Room : 2 rappels actifs
        val restoredReminders = listOf(
            Reminder(
                id = 1L,
                text = "Pharmacie",
                triggerType = TriggerType.PLACE,
                placeCategory = "pharmacy",
                status = ReminderStatus.ACTIVE
            ),
            Reminder(
                id = 2L,
                text = "Boulangerie",
                triggerType = TriggerType.PLACE,
                placeCategory = "bakery",
                status = ReminderStatus.ACTIVE
            )
        )

        // 2. État physique Android après reboot : vide (0 géofence)
        var actualState = GeofenceStateSnapshot()
        assertEquals(0, actualState.count)

        // 3. Découverte / Restauration des POIs pour chaque rappel
        val poi1 = registry.registerOrGetPoi(
            provider = PoiProvider.GOOGLE_PLACES,
            providerId = "pharmacie_reboot_1",
            name = "Pharmacie Reboot",
            latitude = 35.5500,
            longitude = 6.1400,
            category = "pharmacy"
        )
        val link1 = registry.linkReminderToPoi(reminderId = 1L, poiId = poi1.id, semanticRadiusM = 150f)
        link1.contextScore = 80

        val poi2 = registry.registerOrGetPoi(
            provider = PoiProvider.OPEN_STREET_MAP,
            providerId = "osm_boulangerie_2",
            name = "Boulangerie Reboot",
            latitude = 35.5600,
            longitude = 6.1500,
            category = "bakery"
        )
        val link2 = registry.linkReminderToPoi(reminderId = 2L, poiId = poi2.id, semanticRadiusM = 200f)
        link2.contextScore = 75

        // 4. Ordonnancement par le Scheduler
        val linksMap = mapOf(1L to listOf(link1), 2L to listOf(link2))
        val scheduleResult = scheduler.schedule(restoredReminders, linksMap)

        assertEquals(2, scheduleResult.allocatedPoiIds.size)

        // 5. Calcul de l'état désiré
        val desiredMap = scheduleResult.allocatedPoiIds.associate { id ->
            val reqId = "geo_${id.replace(":", "_")}"
            reqId to registry.allocateGeofenceForPoi(id, reqId)
        }
        val desiredState = GeofenceStateSnapshot(desiredMap)

        // 6. Transition DiffEngine
        val plan = diffEngine.computeTransitionPlan(actualState, desiredState)
        assertTrue(plan.isValid)
        assertEquals(1, plan.operations.size)

        // Réconciliation de l'état matériel
        actualState = diffEngine.reconcileActualState(
            actualState,
            plan.operations.flatMap { (it as com.remindly.location.scheduler.DiffOperation.AddBatch).geofencesToAdd },
            emptyList()
        )

        assertEquals("Après restauration reboot, 2 géofences matérielles doivent être actives", 2, actualState.count)
        val expectedReqId1 = "geo_${poi1.id.replace(":", "_")}"
        val expectedReqId2 = "geo_${poi2.id.replace(":", "_")}"
        assertTrue(actualState.geofences.containsKey(expectedReqId1))
        assertTrue(actualState.geofences.containsKey(expectedReqId2))
    }

    /**
     * Point 14 : Cap et approche prévalent sur la simple proximité brute
     * POI A : 100m, mais derrière (angle 180° par rapport au cap)
     * POI B : 250m, droit devant (angle 0°)
     * Vérifie que B > A.
     */
    @Test
    fun testFavorableBearingFurtherBeatsUnfavorableBearingCloser() {
        val reminderA = Reminder(
            id = 10L,
            text = "Magasin A",
            triggerType = TriggerType.PLACE,
            placeCategory = "supermarket",
            placeLat = 35.5490, // Plein Sud (derrière si cap = 0° Nord)
            placeLng = 6.1400,
            placeRadiusM = 450f,
            status = ReminderStatus.ACTIVE
        )

        val reminderB = Reminder(
            id = 20L,
            text = "Magasin B",
            triggerType = TriggerType.PLACE,
            placeCategory = "supermarket",
            placeLat = 35.5525, // Plein Nord (droit devant si cap = 0° Nord)
            placeLng = 6.1400,
            placeRadiusM = 450f,
            status = ReminderStatus.ACTIVE
        )

        // Utilisateur à (35.5500, 6.1400), roulant vers le Nord (cap 0°)
        val userLat = 35.5500
        val userLng = 6.1400
        val headingNorth = 0f

        // POI A est à 100m au Sud (derrière)
        val evalA = contextEngine.evaluate(
            reminder = reminderA,
            currentLocation = null,
            targetLat = reminderA.placeLat,
            targetLng = reminderA.placeLng,
            currentDistanceM = 100f,
            overrideBearing = headingNorth,
            overrideCurrentLat = userLat,
            overrideCurrentLng = userLng
        )

        // POI B est à 250m au Nord (droit devant)
        val evalB = contextEngine.evaluate(
            reminder = reminderB,
            currentLocation = null,
            targetLat = reminderB.placeLat,
            targetLng = reminderB.placeLng,
            currentDistanceM = 250f,
            overrideBearing = headingNorth,
            overrideCurrentLat = userLat,
            overrideCurrentLng = userLng
        )

        // POI B (devant) doit avoir un score supérieur à POI A (derrière), malgré la distance plus grande
        assertTrue(
            "POI B à 250m droit devant (score=${evalB.score}) doit battre POI A à 100m en sens inverse (score=${evalA.score})",
            evalB.score > evalA.score
        )
    }

    /**
     * Point 15 : Différentiel Activity Recognition
     * Même POI, même distance (250m), même cap :
     * Cas 1 : WALKING à 5 km/h -> score opportunité élevé
     * Cas 2 : IN_VEHICLE à 110 km/h (autoroute) -> score pénalisé / supprimé
     */
    @Test
    fun testSamePoiSameDistanceBearing_walkingVsHighSpeedDriving() {
        val poiReminder = Reminder(
            id = 30L,
            text = "Pharmacie",
            triggerType = TriggerType.PLACE,
            placeCategory = "pharmacy",
            placeLat = 35.5525,
            placeLng = 6.1400,
            placeRadiusM = 450f,
            status = ReminderStatus.ACTIVE
        )

        val userLat = 35.5500
        val userLng = 6.1400
        val distM = 250f
        val bearing = 0f

        // Cas 1 : Marche à pied (5 km/h)
        val evalWalking = contextEngine.evaluate(
            reminder = poiReminder,
            currentLocation = null,
            targetLat = poiReminder.placeLat,
            targetLng = poiReminder.placeLng,
            currentDistanceM = distM,
            overrideActivity = DetectedActivity.WALKING,
            overrideSpeedKmh = 5f,
            overrideBearing = bearing,
            overrideCurrentLat = userLat,
            overrideCurrentLng = userLng
        )

        // Cas 2 : Voiture à haute vitesse (110 km/h)
        val evalHighway = contextEngine.evaluate(
            reminder = poiReminder,
            currentLocation = null,
            targetLat = poiReminder.placeLat,
            targetLng = poiReminder.placeLng,
            currentDistanceM = distM,
            overrideActivity = DetectedActivity.IN_VEHICLE,
            overrideSpeedKmh = 110f,
            overrideBearing = bearing,
            overrideCurrentLat = userLat,
            overrideCurrentLng = userLng
        )

        assertTrue(
            "Le score à pied (score=${evalWalking.score}) doit être nettement supérieur au score à 110 km/h (score=${evalHighway.score})",
            evalWalking.score > evalHighway.score
        )
        assertEquals(ContextDecision.FULL_ALARM, evalWalking.decision)
        assertEquals(ContextDecision.WAIT_AND_MONITOR, evalHighway.decision)
    }
}
