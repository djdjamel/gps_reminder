package com.remindly.location.scheduler

import com.remindly.location.registry.TrackedGeofence
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max
import kotlin.math.min

/**
 * Snapshot immuable représentant l'état des géofences à un instant t.
 */
data class GeofenceStateSnapshot(
    val geofences: Map<String, TrackedGeofence> = emptyMap(), // requestId -> TrackedGeofence
    val timestamp: Long = System.currentTimeMillis()
) {
    val count: Int get() = geofences.size
    val requestIds: Set<String> get() = geofences.keys

    fun contains(requestId: String): Boolean = geofences.containsKey(requestId)
    fun get(requestId: String): TrackedGeofence? = geofences[requestId]
}

/**
 * Opération unitaire appliquée au matériel Google Play Services.
 */
sealed class DiffOperation {
    data class AddBatch(val geofencesToAdd: List<TrackedGeofence>) : DiffOperation()
    data class RemoveBatch(val requestIdsToRemove: List<String>) : DiffOperation()
}

/**
 * Plan de transition ordonné généré par le GeofenceDiffEngine.
 */
data class TransitionPlan(
    val initialActualCount: Int,
    val targetDesiredCount: Int,
    val operations: List<DiffOperation>,
    val maxConcurrentCount: Int,
    val isValid: Boolean,
    val failureReason: String? = null
)

/**
 * Moteur différentiel assurant la réconciliation sûre entre l'état souhaité (DesiredState)
 * et l'état matériel réel d'Android (ActualState).
 *
 * Invariants stricts garantis par cette classe :
 * 1. Limite absolue Android : À AUCUN INSTANT intermédiaire, le nombre de géofences ne dépasse 100.
 * 2. Recouvrement progressif (Rolling Overlap) : Utilise la réserve (15 slots) pour insérer
 *    les nouveaux POIs en approche AVANT de retirer les anciens POIs dépassés.
 * 3. Sacrifice ordonné (Coverage Loss) : Les anciens POIs retirés en premier sont ceux ayant
 *    la plus faible valeur de couverture résiduelle (distance la plus grande, score le plus faible).
 * 4. Gestion des échecs partiels : Met à jour ActualState uniquement sur confirmation réelle.
 */
@Singleton
class GeofenceDiffEngine @Inject constructor() {

    companion object {
        const val ANDROID_SYSTEM_LIMIT = 100
        const val MAX_REMINDLY_ACTIVE = 85
        const val BATCH_RESERVE_LIMIT = 15
    }

    /**
     * Calcule un plan de transition pas-à-pas garantissant le respect de la limite matérielle
     * à chaque étape intermédiaire.
     *
     * @param actualState État actuellement confirmé sur l'appareil.
     * @param desiredState État cible souhaité par le ContextualGeofenceScheduler (taille <= 85).
     * @param coverageScores Scores d'opportunité des POIs pour prioriser les ajouts et suppressions.
     */
    fun computeTransitionPlan(
        actualState: GeofenceStateSnapshot,
        desiredState: GeofenceStateSnapshot,
        coverageScores: Map<String, Double> = emptyMap()
    ): TransitionPlan {
        if (desiredState.count > ANDROID_SYSTEM_LIMIT) {
            return TransitionPlan(
                initialActualCount = actualState.count,
                targetDesiredCount = desiredState.count,
                operations = emptyList(),
                maxConcurrentCount = actualState.count,
                isValid = false,
                failureReason = "DesiredState dépasse la limite Android de 100 géofences (${desiredState.count} demandées)"
            )
        }

        val actualIds = actualState.requestIds
        val desiredIds = desiredState.requestIds

        // Éléments à supprimer : présents dans actual mais absents de desired
        // Triés par score de couverture croissant : les moins couvrants sont retirés en premier
        val toRemove = (actualIds - desiredIds)
            .sortedBy { coverageScores[it] ?: 0.0 }

        // Éléments à ajouter : présents dans desired mais absents de actual
        // Triés par score de couverture décroissant : les plus prioritaires sont ajoutés en premier
        val toAdd = desiredState.geofences.filterKeys { it !in actualIds }
            .values
            .sortedByDescending { coverageScores[it.requestId] ?: 0.0 }

        val operations = mutableListOf<DiffOperation>()
        var currentCount = actualState.count
        var maxConcurrent = currentCount

        val pendingToAdd = toAdd.toMutableList()
        val pendingToRemove = toRemove.toMutableList()

        // Boucle de rotation par batchs avec recouvrement
        while (pendingToAdd.isNotEmpty() || pendingToRemove.isNotEmpty()) {
            val availableRoom = ANDROID_SYSTEM_LIMIT - currentCount

            if (availableRoom > 0 && pendingToAdd.isNotEmpty()) {
                // Étape A : Insérer un lot de nouveaux POIs en utilisant la réserve disponible
                val batchSize = min(availableRoom, min(BATCH_RESERVE_LIMIT, pendingToAdd.size))
                val batch = pendingToAdd.take(batchSize)
                pendingToAdd.subList(0, batchSize).clear()

                operations.add(DiffOperation.AddBatch(batch))
                currentCount += batchSize
                maxConcurrent = max(maxConcurrent, currentCount)
            } else if (pendingToRemove.isNotEmpty()) {
                // Étape B : Libérer des slots en supprimant un lot des anciens POIs les moins couvrants
                val batchSize = min(BATCH_RESERVE_LIMIT, pendingToRemove.size)
                val batch = pendingToRemove.take(batchSize)
                pendingToRemove.subList(0, batchSize).clear()

                operations.add(DiffOperation.RemoveBatch(batch))
                currentCount -= batchSize
            } else {
                break
            }
        }

        // Validation formelle du plan de transition
        val validation = validatePlan(actualState.count, desiredState.count, operations)
        if (!validation.first) {
            return TransitionPlan(
                initialActualCount = actualState.count,
                targetDesiredCount = desiredState.count,
                operations = emptyList(),
                maxConcurrentCount = maxConcurrent,
                isValid = false,
                failureReason = validation.second
            )
        }

        return TransitionPlan(
            initialActualCount = actualState.count,
            targetDesiredCount = desiredState.count,
            operations = operations,
            maxConcurrentCount = maxConcurrent,
            isValid = true
        )
    }

    /**
     * Applique une exécution réelle (même partielle) et retourne le nouvel état ActualState cohérent.
     */
    fun reconcileActualState(
        currentActual: GeofenceStateSnapshot,
        succeededAdds: List<TrackedGeofence>,
        succeededRemoves: List<String>
    ): GeofenceStateSnapshot {
        val updatedMap = currentActual.geofences.toMutableMap()

        // 1. Retrait des suppressions confirmées
        for (removeId in succeededRemoves) {
            updatedMap.remove(removeId)
        }

        // 2. Ajout des ajouts confirmés
        for (addGeo in succeededAdds) {
            updatedMap[addGeo.requestId] = addGeo
        }

        return GeofenceStateSnapshot(
            geofences = updatedMap,
            timestamp = System.currentTimeMillis()
        )
    }

    /**
     * Vérifie qu'à CHAQUE étape du plan, le compteur intermédiaire reste <= 100
     * et aboutit exactement à la cible désirée.
     */
    private fun validatePlan(
        startCount: Int,
        targetCount: Int,
        operations: List<DiffOperation>
    ): Pair<Boolean, String?> {
        var count = startCount
        for ((index, op) in operations.withIndex()) {
            when (op) {
                is DiffOperation.AddBatch -> {
                    count += op.geofencesToAdd.size
                    if (count > ANDROID_SYSTEM_LIMIT) {
                        return Pair(false, "Violation de la limite Android à l'opération #$index: $count > $ANDROID_SYSTEM_LIMIT")
                    }
                }
                is DiffOperation.RemoveBatch -> {
                    count -= op.requestIdsToRemove.size
                    if (count < 0) {
                        return Pair(false, "Compteur négatif de géofences à l'opération #$index: $count < 0")
                    }
                }
            }
        }

        if (count != targetCount) {
            return Pair(false, "Le plan n'aboutit pas au compte cible : obtenu $count, attendu $targetCount")
        }

        return Pair(true, null)
    }
}
