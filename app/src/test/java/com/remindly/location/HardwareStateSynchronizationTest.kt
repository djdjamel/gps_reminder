package com.remindly.location

import com.remindly.location.registry.LinkLifecycleState
import com.remindly.domain.model.Reminder
import com.remindly.domain.model.ReminderStatus
import com.remindly.location.registry.*
import com.remindly.location.scheduler.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Tests unitaires validant la synchronisation matérielle atomique de la triade
 * GMS ↔ actualState ↔ PoiRegistry et l'élimination des géofences fantômes lors des rotations.
 * 
 * Les tests ciblent directement les méthodes de production réelles :
 * - HardwareStateReconciler (reconcileGlobalPurge, reconcileReminderRemoval, reconcileSingleRemoval)
 * - GeofenceManager.computeGeofenceIds
 * - GeofenceDiffEngine et ContextualGeofenceScheduler
 */
class HardwareStateSynchronizationTest {

    private lateinit var poiRegistry: PoiRegistry
    private lateinit var diffEngine: GeofenceDiffEngine
    private lateinit var scheduler: ContextualGeofenceScheduler
    private lateinit var reconciler: HardwareStateReconciler

    @Before
    fun setUp() {
        poiRegistry = PoiRegistry()
        diffEngine = GeofenceDiffEngine()
        scheduler = ContextualGeofenceScheduler(poiRegistry)
        reconciler = HardwareStateReconciler(poiRegistry, diffEngine)
    }

    @Test
    fun testRemoveGeofenceReconciliation_preventsGhostGeofencesOnRotation() {
        val reminder = Reminder(
            id = 2L,
            text = "Pharmacie test",
            placeCategory = "PHARMACY",
            status = ReminderStatus.ACTIVE
        )

        val exitReqId = "${reminder.id}_exit_zone"
        val p1 = poiRegistry.registerOrGetPoi(PoiProvider.GOOGLE_PLACES, "g1", "Pharmacie Zahaf", 35.530, 6.105)
        val p2 = poiRegistry.registerOrGetPoi(PoiProvider.GOOGLE_PLACES, "g2", "Pharmacie Kercha", 35.531, 6.165)

        val link1 = poiRegistry.linkReminderToPoi(reminder.id, p1.id, 450f)
        val link2 = poiRegistry.linkReminderToPoi(reminder.id, p2.id, 450f)

        // 1. État initial : les 2 POIs et la exit zone sont armés matériellement
        val reqId1 = "geo_google_places_g1"
        val reqId2 = "geo_google_places_g2"
        val tgExit = TrackedGeofence(exitReqId, exitReqId, 35.530, 6.100, 900f, isExitZone = true)
        val tg1 = poiRegistry.allocateGeofenceForPoi(p1.id, reqId1)
        val tg2 = poiRegistry.allocateGeofenceForPoi(p2.id, reqId2)
        poiRegistry.markGeofenceArmed(reqId1)
        poiRegistry.markGeofenceArmed(reqId2)

        var actualState = GeofenceStateSnapshot(mapOf(
            exitReqId to tgExit,
            reqId1 to tg1,
            reqId2 to tg2
        ))

        assertEquals(3, actualState.count)
        assertEquals(2, poiRegistry.getActiveGeofenceCount())
        assertNotNull(poiRegistry.getActiveGeofenceForPoi(p2.id))

        // 2. L'utilisateur sort de la rolling zone -> Appel réel de la méthode de réconciliation
        val idsToRemove = listOf(exitReqId, reqId1, reqId2)
        actualState = reconciler.reconcileReminderRemoval(
            currentActualState = actualState,
            idsToRemove = idsToRemove,
            removedFromGms = true,
            reminder = reminder
        )

        // Vérification de la cohérence interne après suppression confirmée par GMS :
        assertEquals("actualState doit être vidé des géofences supprimées", 0, actualState.count)
        assertEquals("PoiRegistry ne doit plus avoir de géofence active", 0, poiRegistry.getActiveGeofenceCount())
        assertNull("p2 ne doit plus avoir de TrackedGeofence active", poiRegistry.getActiveGeofenceForPoi(p2.id))
        assertEquals(PoiHardwareStatus.RETIRED, p2.hardwareStatus)
        assertEquals(LinkLifecycleState.CANDIDATE, link1.state)
        assertEquals(LinkLifecycleState.CANDIDATE, link2.state)

        // 3. Nouvelle passe dans la nouvelle fenêtre géographique (Kercha p2 est toujours candidate + nouveau POI p3)
        val p3 = poiRegistry.registerOrGetPoi(PoiProvider.GOOGLE_PLACES, "g3", "Pharmacie Samei", 35.540, 6.128)
        val link3 = poiRegistry.linkReminderToPoi(reminder.id, p3.id, 450f)
        link2.contextScore = 90
        link3.contextScore = 80

        val scheduleResult = scheduler.schedule(listOf(reminder), mapOf(reminder.id to listOf(link2, link3)))
        assertTrue("Kercha doit être ré-allouée", scheduleResult.allocatedPoiIds.contains(p2.id))
        assertTrue("Samei doit être allouée", scheduleResult.allocatedPoiIds.contains(p3.id))

        val newExitReqId = "${reminder.id}_exit_zone"
        val newTgExit = TrackedGeofence(newExitReqId, newExitReqId, 35.535, 6.140, 900f, isExitZone = true)
        val newTg2 = poiRegistry.allocateGeofenceForPoi(p2.id, reqId2)
        val newTg3 = poiRegistry.allocateGeofenceForPoi(p3.id, "geo_google_places_g3")

        val desiredState = GeofenceStateSnapshot(mapOf(
            newExitReqId to newTgExit,
            reqId2 to newTg2,
            "geo_google_places_g3" to newTg3
        ))

        // 4. Calcul du plan de transition par le DiffEngine
        val plan = diffEngine.computeTransitionPlan(actualState, desiredState)
        assertTrue("Le plan doit être valide", plan.isValid)

        // Vérification anti-géofence fantôme :
        // Le DiffEngine DOIT émettre une opération AddBatch contenant reqId2 (Kercha) et reqId3 vers GMS !
        val addOps = plan.operations.filterIsInstance<DiffOperation.AddBatch>()
        val addedReqIds = addOps.flatMap { it.geofencesToAdd }.map { it.requestId }.toSet()

        assertTrue("Kercha (reqId2) DOIT être ajoutée à GMS (pas de fantôme)", addedReqIds.contains(reqId2))
        assertTrue("Samei (reqId3) DOIT être ajoutée à GMS", addedReqIds.contains("geo_google_places_g3"))
        assertTrue("La nouvelle exit zone DOIT être ajoutée à GMS", addedReqIds.contains(newExitReqId))
        assertEquals(3, addedReqIds.size)
    }

    @Test
    fun testReconcileReminderRemoval_gmsFailure_preservesLocalStateWithoutPrematureReconciliation() {
        val reminder = Reminder(
            id = 42L,
            text = "Pharmacie test GMS Failure",
            placeCategory = "PHARMACY",
            status = ReminderStatus.ACTIVE
        )
        val p1 = poiRegistry.registerOrGetPoi(PoiProvider.GOOGLE_PLACES, "p42", "Commerce 42", 36.75, 3.05)
        val link = poiRegistry.linkReminderToPoi(reminder.id, p1.id, 300f)
        val reqId = "geo_p42"
        val tg = poiRegistry.allocateGeofenceForPoi(p1.id, reqId)
        poiRegistry.markGeofenceArmed(reqId)
        link.state = LinkLifecycleState.ARMED

        val actualState = GeofenceStateSnapshot(mapOf(reqId to tg))
        assertEquals(1, actualState.count)
        assertEquals(LinkLifecycleState.ARMED, link.state)
        assertEquals(PoiHardwareStatus.ARMED, p1.hardwareStatus)

        // Cas 1 : Échec matériel GMS (removedFromGms = false)
        // La méthode de production réelle est directement invoquée avec le statut d'échec
        val stateAfterFailure = reconciler.reconcileReminderRemoval(
            currentActualState = actualState,
            idsToRemove = listOf(reqId),
            removedFromGms = false,
            reminder = reminder
        )

        // Vérifications formelles : l'état local DOIT être préservé sans modification prématurée
        assertEquals("En cas d'échec GMS, actualState ne doit PAS être altéré", 1, stateAfterFailure.count)
        assertNotNull("Le registre doit conserver la géofence active", poiRegistry.getActiveGeofenceForPoi(p1.id))
        assertEquals("Le statut matériel du POI doit rester ARMED", PoiHardwareStatus.ARMED, p1.hardwareStatus)
        assertEquals("Le lien doit rester ARMED (ne pas rétrograder prématurément à CANDIDATE)", LinkLifecycleState.ARMED, link.state)

        // Cas 2 : Succès matériel GMS (removedFromGms = true)
        val stateAfterSuccess = reconciler.reconcileReminderRemoval(
            currentActualState = actualState,
            idsToRemove = listOf(reqId),
            removedFromGms = true,
            reminder = reminder
        )

        // Vérifications : dès que GMS confirme, la réconciliation s'applique
        assertEquals("Après succès GMS, actualState doit être vidé de l'ID supprimé", 0, stateAfterSuccess.count)
        assertNull("La géofence doit être libérée du registre", poiRegistry.getActiveGeofenceForPoi(p1.id))
        assertEquals("Le POI physique doit passer à RETIRED", PoiHardwareStatus.RETIRED, p1.hardwareStatus)
        assertEquals("Le lien doit repasser à CANDIDATE pour rééligibilité future", LinkLifecycleState.CANDIDATE, link.state)
    }

    @Test
    fun testReconcileGlobalPurge_gmsFailureAndSuccess() {
        val p1 = poiRegistry.registerOrGetPoi(PoiProvider.GOOGLE_PLACES, "p1", "Pharmacie 1", 36.75, 3.05)
        val p2 = poiRegistry.registerOrGetPoi(PoiProvider.GOOGLE_PLACES, "p2", "Pharmacie 2", 36.76, 3.06)
        val tg1 = poiRegistry.allocateGeofenceForPoi(p1.id, "req_1")
        val tg2 = poiRegistry.allocateGeofenceForPoi(p2.id, "req_2")
        poiRegistry.markGeofenceArmed("req_1")
        poiRegistry.markGeofenceArmed("req_2")

        val initialActualState = GeofenceStateSnapshot(mapOf(
            "req_1" to tg1,
            "req_2" to tg2
        ))
        assertEquals(2, initialActualState.count)
        assertEquals(2, poiRegistry.getActiveGeofenceCount())

        // 1. Échec GMS : l'état DOIT rester rigoureusement inchangé
        val stateAfterFailure = reconciler.reconcileGlobalPurge(
            currentActualState = initialActualState,
            removedFromGms = false
        )
        assertEquals("En cas d'échec GMS de purge globale, actualState ne doit PAS être vidé", 2, stateAfterFailure.count)
        assertEquals("Le PoiRegistry doit conserver ses géofences actives", 2, poiRegistry.getActiveGeofenceCount())
        assertEquals(PoiHardwareStatus.ARMED, p1.hardwareStatus)
        assertEquals(PoiHardwareStatus.ARMED, p2.hardwareStatus)

        // 2. Succès GMS : réinitialisation complète de l'état matériel présumé
        val stateAfterSuccess = reconciler.reconcileGlobalPurge(
            currentActualState = initialActualState,
            removedFromGms = true
        )
        assertEquals("Après confirmation GMS, actualState doit être entièrement vidé", 0, stateAfterSuccess.count)
        assertEquals("Le PoiRegistry ne doit plus avoir aucune géofence active", 0, poiRegistry.getActiveGeofenceCount())
        assertEquals(PoiHardwareStatus.RETIRED, p1.hardwareStatus)
        assertEquals(PoiHardwareStatus.RETIRED, p2.hardwareStatus)
        assertNull(poiRegistry.getActiveGeofenceForPoi(p1.id))
        assertNull(poiRegistry.getActiveGeofenceForPoi(p2.id))
    }

    @Test
    fun testReconcileSingleRemoval_gmsFailureAndSuccess() {
        val p1 = poiRegistry.registerOrGetPoi(PoiProvider.GOOGLE_PLACES, "p1", "Place 1", 36.75, 3.05)
        val tg1 = poiRegistry.allocateGeofenceForPoi(p1.id, "stage_dest_1")
        poiRegistry.markGeofenceArmed("stage_dest_1")

        val initialActualState = GeofenceStateSnapshot(mapOf("stage_dest_1" to tg1))

        // Échec GMS : préservé
        val stateAfterFailure = reconciler.reconcileSingleRemoval(initialActualState, "stage_dest_1", removedFromGms = false)
        assertEquals(1, stateAfterFailure.count)
        assertEquals(PoiHardwareStatus.ARMED, p1.hardwareStatus)

        // Succès GMS : libéré
        val stateAfterSuccess = reconciler.reconcileSingleRemoval(initialActualState, "stage_dest_1", removedFromGms = true)
        assertEquals(0, stateAfterSuccess.count)
        assertEquals(PoiHardwareStatus.RETIRED, p1.hardwareStatus)
    }

    @Test
    fun testComputeGeofenceIds_includesAllRealSourcesAndZeroSynthetic151Ids() {
        val reminderId = 99L
        val storedIds = setOf("99_pref_1", "99_pref_2")
        val activeIds = setOf("99_active_1")
        val dynamicIds = setOf("99_dyn_place")
        val poiGeofenceIds = setOf("geo_google_places_g99")
        val actualIds = setOf("99_exit_zone", "geo_google_places_g99")

        // Appel direct à la méthode de production GeofenceManager.computeGeofenceIds
        val resultIds = GeofenceManager.computeGeofenceIds(
            reminderId = reminderId,
            storedIds = storedIds,
            activeIds = activeIds,
            dynamicIds = dynamicIds,
            poiGeofenceIds = poiGeofenceIds,
            actualIds = actualIds
        )

        // 1. Tous les IDs réels doivent être inclus
        assertTrue(resultIds.contains("99"))
        assertTrue(resultIds.contains("99_stage_dest"))
        assertTrue(resultIds.contains("99_exit_zone"))
        assertTrue(resultIds.contains("99_pref_1"))
        assertTrue(resultIds.contains("99_pref_2"))
        assertTrue(resultIds.contains("99_active_1"))
        assertTrue(resultIds.contains("99_dyn_place"))
        assertTrue(resultIds.contains("geo_google_places_g99"))

        // 2. Aucun ID artificiel de la série 0..150 ne doit être présent
        val fakeSynthetics = (0..150).map { "${reminderId}_geo_$it" }
        for (fake in fakeSynthetics) {
            assertFalse("La méthode de production ne doit JAMAIS générer d'ID synthétique fictif : $fake", resultIds.contains(fake))
        }

        // 3. Aucun doublon dans la liste retournée
        assertEquals(resultIds.size, resultIds.toSet().size)
    }

    @Test
    fun testPruneDistantCandidateLinks_preservesAlertedAndSkippedMemory() {
        val reminderId = 10L
        val centerLat = 35.550
        val centerLng = 6.170

        // p1 : à ~10 km, ALERTED -> doit être conservé
        val p1 = poiRegistry.registerOrGetPoi(PoiProvider.GOOGLE_PLACES, "p1", "Pharmacie Loin Notifiée", 35.450, 6.170)
        val l1 = poiRegistry.linkReminderToPoi(reminderId, p1.id, 400f)
        l1.state = LinkLifecycleState.ALERTED

        // p2 : à ~10 km, SKIPPED -> doit être conservé
        val p2 = poiRegistry.registerOrGetPoi(PoiProvider.GOOGLE_PLACES, "p2", "Pharmacie Loin Ignorée", 35.451, 6.170)
        val l2 = poiRegistry.linkReminderToPoi(reminderId, p2.id, 400f)
        l2.state = LinkLifecycleState.SKIPPED

        // p3 : à ~10 km, CANDIDATE -> DOIT ÊTRE ÉLAGUÉ (vieux résidu de rolling window)
        val p3 = poiRegistry.registerOrGetPoi(PoiProvider.GOOGLE_PLACES, "p3", "Pharmacie Loin Ancienne", 35.452, 6.170)
        val l3 = poiRegistry.linkReminderToPoi(reminderId, p3.id, 400f)
        l3.state = LinkLifecycleState.CANDIDATE

        // p4 : à ~500 m, CANDIDATE -> DOIT ÊTRE CONSERVÉ (dans la fenêtre active)
        val p4 = poiRegistry.registerOrGetPoi(PoiProvider.GOOGLE_PLACES, "p4", "Pharmacie Proche Active", 35.552, 6.172)
        val l4 = poiRegistry.linkReminderToPoi(reminderId, p4.id, 400f)
        l4.state = LinkLifecycleState.CANDIDATE

        assertEquals(4, poiRegistry.getLinksForReminder(reminderId).size)

        // Exécution du pruning à seuil 5 000 m
        val prunedCount = poiRegistry.pruneDistantCandidateLinks(reminderId, centerLat, centerLng, 5000f)

        assertEquals("Un seul lien lointain non-alerté doit être élagué", 1, prunedCount)

        val remainingLinks = poiRegistry.getLinksForReminder(reminderId)
        assertEquals(3, remainingLinks.size)

        val remainingPoiIds = remainingLinks.map { it.poiId }.toSet()
        assertTrue("p1 (ALERTED) doit être conservé pour mémoire", remainingPoiIds.contains(p1.id))
        assertTrue("p2 (SKIPPED) doit être conservé pour mémoire", remainingPoiIds.contains(p2.id))
        assertFalse("p3 (CANDIDATE lointain) doit avoir été purgé", remainingPoiIds.contains(p3.id))
        assertTrue("p4 (CANDIDATE proche) doit être conservé", remainingPoiIds.contains(p4.id))
    }

    @Test
    fun testClearAllActiveGeofences_resetsHardwareTrackingCompletely() {
        val p1 = poiRegistry.registerOrGetPoi(PoiProvider.GOOGLE_PLACES, "p1", "Place 1", 36.0, 3.0)
        val p2 = poiRegistry.registerOrGetPoi(PoiProvider.GOOGLE_PLACES, "p2", "Place 2", 36.1, 3.1)

        poiRegistry.allocateGeofenceForPoi(p1.id, "req_1")
        poiRegistry.allocateGeofenceForPoi(p2.id, "req_2")
        poiRegistry.markGeofenceArmed("req_1")
        poiRegistry.markGeofenceArmed("req_2")

        assertEquals(2, poiRegistry.getActiveGeofenceCount())
        assertEquals(PoiHardwareStatus.ARMED, p1.hardwareStatus)
        assertEquals(PoiHardwareStatus.ARMED, p2.hardwareStatus)

        poiRegistry.clearAllActiveGeofences()

        assertEquals(0, poiRegistry.getActiveGeofenceCount())
        assertEquals(PoiHardwareStatus.RETIRED, p1.hardwareStatus)
        assertEquals(PoiHardwareStatus.RETIRED, p2.hardwareStatus)
        assertNull(poiRegistry.getActiveGeofenceForPoi(p1.id))
        assertNull(poiRegistry.getActiveGeofenceForPoi(p2.id))
    }
}
