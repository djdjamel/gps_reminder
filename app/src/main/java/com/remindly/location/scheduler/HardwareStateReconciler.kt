package com.remindly.location.scheduler

import android.util.Log
import com.remindly.location.registry.LinkLifecycleState
import com.remindly.domain.model.Reminder
import com.remindly.domain.model.ReminderStatus
import com.remindly.location.registry.PoiRegistry
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Composant central de réconciliation de l'état matériel (Google Play Services) avec l'état logique :
 * - GeofenceStateSnapshot (actualState)
 * - PoiRegistry (DiscoveredPoi, allocations et statuts matériels)
 * - ReminderPoiLink (cycle de vie des liens contextuels)
 *
 * Principe fondamental d'intégrité :
 * Toute réconciliation locale (libération de géofences, transition des liens vers CANDIDATE, snapshot vidé)
 * est STRICTEMENT conditionnée au succès confirmé de Google Play Services (removedFromGms == true).
 * En cas d'échec matériel, l'état local est conservé intact pour refléter fidèlement le hardware et
 * empêcher l'apparition de géofences fantômes.
 */
@Singleton
class HardwareStateReconciler @Inject constructor(
    private val poiRegistry: PoiRegistry,
    private val diffEngine: GeofenceDiffEngine
) {
    private val tag = "HardwareStateReconciler"

    /**
     * Réconciliation suite à une purge globale de toutes les géofences auprès de GMS.
     *
     * @param currentActualState L'état matériel snapshot actuel avant l'opération.
     * @param removedFromGms Indique si l'appel GMS removeGeofences() a réussi.
     * @return Le nouveau snapshot actualState réconcilié.
     */
    fun reconcileGlobalPurge(
        currentActualState: GeofenceStateSnapshot,
        removedFromGms: Boolean
    ): GeofenceStateSnapshot {
        if (!removedFromGms) {
            Log.w(tag, "reconcileGlobalPurge: Échec matériel GMS. actualState (${currentActualState.count} géofences) et PoiRegistry conservés intacts.")
            return currentActualState
        }

        poiRegistry.clearAllActiveGeofences()
        Log.i(tag, "reconcileGlobalPurge: Purge confirmée par GMS. actualState et PoiRegistry réinitialisés.")
        return GeofenceStateSnapshot()
    }

    /**
     * Réconciliation suite à la suppression ciblée des géofences d'un rappel (ex: sortie de zone glissante ou réarmement).
     *
     * @param currentActualState L'état matériel snapshot actuel avant l'opération.
     * @param idsToRemove Liste des identifiants de géofences ciblées.
     * @param removedFromGms Indique si l'appel GMS removeGeofences(ids) a réussi.
     * @param reminder Le rappel concerné (optionnel, pour ajuster le statut des liens).
     * @return Le nouveau snapshot actualState réconcilié.
     */
    fun reconcileReminderRemoval(
        currentActualState: GeofenceStateSnapshot,
        idsToRemove: List<String>,
        removedFromGms: Boolean,
        reminder: Reminder?
    ): GeofenceStateSnapshot {
        if (!removedFromGms || idsToRemove.isEmpty()) {
            if (!removedFromGms && idsToRemove.isNotEmpty()) {
                Log.w(tag, "reconcileReminderRemoval: Échec matériel GMS pour rappel #${reminder?.id}. Aucune modification de actualState ni de PoiRegistry.")
            }
            return currentActualState
        }

        // 1. Libération matérielle des géofences confirmée par GMS
        idsToRemove.forEach { reqId ->
            poiRegistry.releaseGeofence(reqId)
        }

        // 2. Réconciliation atomique du snapshot actualState
        val newActualState = diffEngine.reconcileActualState(currentActualState, emptyList(), idsToRemove)

        // 3. Gestion du cycle de vie des liens
        if (reminder != null) {
            val isPermanentlyInactive = reminder.status == ReminderStatus.COMPLETED
            val links = poiRegistry.getLinksForReminder(reminder.id)
            if (isPermanentlyInactive) {
                links.forEach { it.state = LinkLifecycleState.COMPLETED }
                poiRegistry.unregisterReminder(reminder.id)
            } else {
                links.forEach { link ->
                    // On préserve les POIs déjà notifiés (ALERTED) ou ignorés (SKIPPED).
                    // Les liens qui étaient planifiés ou armés redeviennent CANDIDATE pour rester
                    // éligibles lors des futures passes de l'ordonnanceur (ContextualGeofenceScheduler).
                    if (link.state == LinkLifecycleState.ARMED || link.state == LinkLifecycleState.SCHEDULED) {
                        link.state = LinkLifecycleState.CANDIDATE
                    }
                }
            }
        }

        Log.d(tag, "reconcileReminderRemoval: Succès GMS #${reminder?.id}. ${idsToRemove.size} IDs retirés, reste ${newActualState.count} géofences dans actualState.")
        return newActualState
    }

    /**
     * Réconciliation suite à la suppression d'une géofence unique (ex: étape intermédiaire destination atteinte).
     *
     * @param currentActualState L'état matériel snapshot actuel avant l'opération.
     * @param requestId Identifiant unique de la géofence supprimée.
     * @param removedFromGms Indique si l'appel GMS removeGeofences(listOf(requestId)) a réussi.
     * @return Le nouveau snapshot actualState réconcilié.
     */
    fun reconcileSingleRemoval(
        currentActualState: GeofenceStateSnapshot,
        requestId: String,
        removedFromGms: Boolean
    ): GeofenceStateSnapshot {
        if (!removedFromGms) {
            Log.w(tag, "reconcileSingleRemoval: Échec matériel GMS pour $requestId. actualState conservé intact.")
            return currentActualState
        }

        poiRegistry.releaseGeofence(requestId)
        val newActualState = diffEngine.reconcileActualState(currentActualState, emptyList(), listOf(requestId))
        Log.d(tag, "reconcileSingleRemoval: Succès GMS pour $requestId. Reste ${newActualState.count} géofences.")
        return newActualState
    }
}
