package com.remindly.location.scheduler

import com.remindly.location.registry.TrackedGeofence
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Tests unitaires validant le moteur différentiel GeofenceDiffEngine :
 * - Invariant 1 : Respect absolu du plafond Android de 100 géofences à CHAQUE étape intermédiaire.
 * - Invariant 2 : Rotation par batchs avec recouvrement (Add avant Remove via la réserve de 15 slots).
 * - Invariant 3 : Priorisation du sacrifice (Coverage Loss : anciens POIs les plus faibles retirés en premier).
 * - Invariant 4 : Résilience au reboot (Transition propre de 0 à N géofences).
 * - Invariant 5 : Réconciliation sur échec partiel (ActualState ne retient que les succès confirmés).
 */
class GeofenceDiffEngineTest {

    private lateinit var diffEngine: GeofenceDiffEngine

    @Before
    fun setUp() {
        diffEngine = GeofenceDiffEngine()
    }

    private fun createGeofence(id: String): TrackedGeofence {
        return TrackedGeofence(
            requestId = id,
            poiId = "poi_$id",
            latitude = 36.75,
            longitude = 3.05,
            radiusMeters = 250f
        )
    }

    @Test
    fun testRollingOverlapRotation_neverExceeds100AtAnyStep() {
        // État initial : 85 géofences actives
        val initialMap = (1..85).associate { i ->
            val id = "geo_old_$i"
            id to createGeofence(id)
        }
        val actualState = GeofenceStateSnapshot(initialMap)

        // État cible : 85 géofences
        // 55 conservées (geo_old_1 à geo_old_55)
        // 30 retirées (geo_old_56 à geo_old_85)
        // 30 nouvelles ajoutées (geo_new_1 à geo_new_30)
        val desiredMap = mutableMapOf<String, TrackedGeofence>()
        for (i in 1..55) {
            val id = "geo_old_$i"
            desiredMap[id] = initialMap[id]!!
        }
        for (i in 1..30) {
            val id = "geo_new_$i"
            desiredMap[id] = createGeofence(id)
        }
        val desiredState = GeofenceStateSnapshot(desiredMap)

        val plan = diffEngine.computeTransitionPlan(actualState, desiredState)

        assertTrue("Le plan de transition doit être valide : ${plan.failureReason}", plan.isValid)
        assertEquals(85, plan.initialActualCount)
        assertEquals(85, plan.targetDesiredCount)
        assertTrue("Le nombre maximal concurrent ne doit JAMAIS dépasser 100 (obtenu: ${plan.maxConcurrentCount})", plan.maxConcurrentCount <= 100)

        // Vérification pas-à-pas de l'exécution
        var runningCount = plan.initialActualCount
        var totalAdded = 0
        var totalRemoved = 0

        for (op in plan.operations) {
            when (op) {
                is DiffOperation.AddBatch -> {
                    runningCount += op.geofencesToAdd.size
                    totalAdded += op.geofencesToAdd.size
                    assertTrue("Dépassement Android détecté en cours de batch: $runningCount > 100", runningCount <= 100)
                }
                is DiffOperation.RemoveBatch -> {
                    runningCount -= op.requestIdsToRemove.size
                    totalRemoved += op.requestIdsToRemove.size
                    assertTrue("Compteur négatif de géofences: $runningCount", runningCount >= 0)
                }
            }
        }

        assertEquals("Exactement 30 géofences doivent être ajoutées", 30, totalAdded)
        assertEquals("Exactement 30 géofences doivent être supprimées", 30, totalRemoved)
        assertEquals("Le compte final doit revenir rigoureusement à 85", 85, runningCount)

        // Vérifier que la première opération était bien un Add (recouvrement doux avant suppression)
        assertTrue("La première opération doit être un AddBatch exploitant la réserve", plan.operations.first() is DiffOperation.AddBatch)
    }

    @Test
    fun testCoverageLossPrioritization_removesWeakestPoisFirst() {
        val actualMap = mapOf(
            "geo_weak" to createGeofence("geo_weak"),     // Score faible (éloigné)
            "geo_medium" to createGeofence("geo_medium"), // Score moyen
            "geo_strong" to createGeofence("geo_strong")  // Score fort
        )
        val actualState = GeofenceStateSnapshot(actualMap)

        // DesiredState ne conserve que geo_strong (geo_weak et geo_medium doivent être retirés)
        val desiredMap = mapOf(
            "geo_strong" to actualMap["geo_strong"]!!
        )
        val desiredState = GeofenceStateSnapshot(desiredMap)

        // Attribution des scores de perte de couverture
        val coverageScores = mapOf(
            "geo_weak" to 15.0,
            "geo_medium" to 55.0,
            "geo_strong" to 95.0
        )

        val plan = diffEngine.computeTransitionPlan(actualState, desiredState, coverageScores)

        assertTrue(plan.isValid)
        val removeOps = plan.operations.filterIsInstance<DiffOperation.RemoveBatch>()
        val removedIds = removeOps.flatMap { it.requestIdsToRemove }

        assertEquals(2, removedIds.size)
        // Le premier supprimé doit être geo_weak (score 15.0 < 55.0)
        assertEquals("geo_weak", removedIds[0])
        assertEquals("geo_medium", removedIds[1])
    }

    @Test
    fun testRebootRecovery_transitionsFromZeroToDesired() {
        // Après redémarrage du smartphone, Google Play Services a 0 géofence enregistrée
        val actualState = GeofenceStateSnapshot(emptyMap())

        // Remindly restaure 50 géofences depuis sa base SQLite Room
        val desiredMap = (1..50).associate { i ->
            val id = "geo_restored_$i"
            id to createGeofence(id)
        }
        val desiredState = GeofenceStateSnapshot(desiredMap)

        val plan = diffEngine.computeTransitionPlan(actualState, desiredState)

        assertTrue(plan.isValid)
        assertEquals(0, plan.initialActualCount)
        assertEquals(50, plan.targetDesiredCount)
        assertTrue(plan.operations.all { it is DiffOperation.AddBatch })

        val totalAdded = plan.operations.filterIsInstance<DiffOperation.AddBatch>().sumOf { it.geofencesToAdd.size }
        assertEquals(50, totalAdded)
    }

    @Test
    fun testPartialExecutionReconciliation_reflectsOnlyConfirmedState() {
        val initialMap = mapOf(
            "geo_1" to createGeofence("geo_1"),
            "geo_2" to createGeofence("geo_2"),
            "geo_3" to createGeofence("geo_3")
        )
        val actualState = GeofenceStateSnapshot(initialMap)

        // Supposons une opération où on voulait supprimer geo_1 et ajouter geo_4 et geo_5
        // Mais Play Services confirme la suppression de geo_1 et l'ajout de geo_4, tandis que geo_5 a échoué
        val succeededAdds = listOf(createGeofence("geo_4"))
        val succeededRemoves = listOf("geo_1")

        val reconciled = diffEngine.reconcileActualState(actualState, succeededAdds, succeededRemoves)

        assertEquals("Le compte doit être 2 (initiaux) - 1 (retiré) + 1 (ajouté) = 3", 3, reconciled.count)
        assertFalse("geo_1 doit être retiré", reconciled.contains("geo_1"))
        assertTrue("geo_2 doit être conservé", reconciled.contains("geo_2"))
        assertTrue("geo_3 doit être conservé", reconciled.contains("geo_3"))
        assertTrue("geo_4 doit être présent", reconciled.contains("geo_4"))
        assertFalse("geo_5 (qui a échoué) ne doit PAS être dans ActualState", reconciled.contains("geo_5"))
    }

    @Test
    fun testExcessiveDesiredState_rejectedByValidation() {
        val actualState = GeofenceStateSnapshot(emptyMap())
        // Tentative illégale de demander 105 géofences
        val excessiveMap = (1..105).associate { i ->
            val id = "geo_$i"
            id to createGeofence(id)
        }
        val desiredState = GeofenceStateSnapshot(excessiveMap)

        val plan = diffEngine.computeTransitionPlan(actualState, desiredState)

        assertFalse("Le plan doit être rejeté car 105 > 100", plan.isValid)
        assertNotNull(plan.failureReason)
    }
}
