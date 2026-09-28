package com.remindly.location

import android.location.Location
import com.google.android.gms.location.DetectedActivity
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Coordinateur d'idempotence thread-safe garantissant qu'un même rappel
 * ne peut pas être déclenché simultanément par deux composants concurrents
 * (ex: DrivingPulseService et GeofenceBroadcastReceiver à la même milliseconde)
 * et régissant le garde adaptatif (temps + distance) pour les rappels de catégorie opportunistes.
 */
@Singleton
class TriggerCoordinator @Inject constructor() {

    private val lastTriggerTimes = ConcurrentHashMap<Long, Long>()

    data class OpportunityTriggerRef(
        val timestamp: Long,
        val latitude: Double,
        val longitude: Double
    )

    private val categoryTriggerRefs = ConcurrentHashMap<Long, OpportunityTriggerRef>()

    /**
     * Tente d'acquérir le verrou de déclenchement de manière atomique.
     * @param reminderId L'identifiant du rappel
     * @param cooldownMs La durée de temporisation anti-rebond en millisecondes
     * @return true si le déclenchement est autorisé (verrou acquis), false si déjà en cooldown
     */
    fun tryAcquireTrigger(reminderId: Long, cooldownMs: Long): Boolean {
        val now = System.currentTimeMillis()
        var acquired = false

        lastTriggerTimes.compute(reminderId) { _, lastTime ->
            if (lastTime != null && (now - lastTime) < cooldownMs) {
                // Cooldown encore actif : refus atomique
                acquired = false
                lastTime
            } else {
                // Cooldown expiré ou premier déclenchement : acquisition atomique
                acquired = true
                now
            }
        }

        return acquired
    }

    /**
     * Vérifie si un rappel est actuellement dans sa fenêtre de cooldown.
     */
    fun isCooldownActive(reminderId: Long, cooldownMs: Long): Boolean {
        val lastTime = lastTriggerTimes[reminderId] ?: return false
        return (System.currentTimeMillis() - lastTime) < cooldownMs
    }

    /**
     * Enregistre manuellement un déclenchement ou un timestamp de référence.
     */
    fun recordTrigger(reminderId: Long, timestamp: Long = System.currentTimeMillis()) {
        lastTriggerTimes[reminderId] = timestamp
    }

    /**
     * Réinitialise le cooldown pour ce rappel (ex: sortie de zone confirmée).
     */
    fun resetCooldown(reminderId: Long) {
        lastTriggerTimes.remove(reminderId)
    }

    /**
     * Garde adaptatif pour les rappels de catégorie opportunistes.
     * Exige à la fois un temps minimal (60s) ET une progression spatiale minimale
     * (300m en véhicule pour éviter de déclencher dans la même grappe de commerces, 100m à pied).
     */
    fun canTriggerCategoryOpportunity(
        reminderId: Long,
        currentLocation: Location?,
        activityType: Int
    ): Boolean {
        val lastRef = categoryTriggerRefs[reminderId] ?: return true
        val now = System.currentTimeMillis()
        val elapsedSec = (now - lastRef.timestamp) / 1000

        // 1. Garde temporel minimal : 60 secondes
        if (elapsedSec < 60) {
            return false
        }

        // 2. Garde spatial minimal selon l'activité de déplacement
        val minDistanceM = if (activityType == DetectedActivity.IN_VEHICLE) 300f else 100f
        if (currentLocation != null) {
            val distM = computeDistanceMeters(
                lastRef.latitude,
                lastRef.longitude,
                currentLocation.latitude,
                currentLocation.longitude
            )
            if (distM < minDistanceM) {
                return false
            }
        }
        return true
    }

    /**
     * Calcul de distance géodésique pure Kotlin (Haversine) garantissant une précision métrique
     * et une testabilité unitaire totale indépendamment de l'environnement Android/Robolectric.
     */
    fun computeDistanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Float {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
                Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
                Math.sin(dLon / 2) * Math.sin(dLon / 2)
        val c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
        return (6371000.0 * c).toFloat()
    }

    /**
     * Enregistre le déclenchement d'un commerce opportuniste pour calculer le garde adaptatif du suivant.
     */
    fun recordCategoryTrigger(
        reminderId: Long,
        location: Location?,
        timestamp: Long = System.currentTimeMillis()
    ) {
        if (location != null) {
            categoryTriggerRefs[reminderId] = OpportunityTriggerRef(
                timestamp = timestamp,
                latitude = location.latitude,
                longitude = location.longitude
            )
        }
    }

    /**
     * Réinitialise le suivi d'opportunité d'un rappel (ex: complété ou annulé).
     */
    fun resetCategoryOpportunity(reminderId: Long) {
        categoryTriggerRefs.remove(reminderId)
    }

    /**
     * Purge tous les enregistrements.
     */
    fun clear() {
        lastTriggerTimes.clear()
        categoryTriggerRefs.clear()
    }
}
