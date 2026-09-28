package com.remindly.location

import android.content.Context
import android.content.SharedPreferences
import android.location.Location
import com.google.android.gms.location.DetectedActivity
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Coordinateur d'idempotence thread-safe et persistant garantissant qu'un même rappel
 * ne peut pas être déclenché simultanément par deux composants concurrents
 * (ex: DrivingPulseService et GeofenceBroadcastReceiver à la même milliseconde)
 * et régissant le garde adaptatif (temps + distance) pour les rappels de catégorie opportunistes.
 * Toutes les décisions et horodatages sont persistés dans SharedPreferences pour survivre
 * aux redémarrages de processus en arrière-plan.
 */
@Singleton
class TriggerCoordinator internal constructor(
    private val prefs: SharedPreferences?
) {

    @Inject
    constructor(@ApplicationContext context: Context) : this(
        context.getSharedPreferences("trigger_coordinator_prefs", Context.MODE_PRIVATE)
    )

    constructor() : this(null as SharedPreferences?)

    private val lastTriggerTimes = ConcurrentHashMap<Long, Long>()

    data class OpportunityTriggerRef(
        val timestamp: Long,
        val latitude: Double,
        val longitude: Double
    )

    private val categoryTriggerRefs = ConcurrentHashMap<Long, OpportunityTriggerRef>()

    /**
     * Tente d'acquérir le verrou de déclenchement de manière atomique et persistante.
     * @param reminderId L'identifiant du rappel
     * @param cooldownMs La durée de temporisation anti-rebond en millisecondes
     * @return true si le déclenchement est autorisé (verrou acquis), false si déjà en cooldown
     */
    fun tryAcquireTrigger(reminderId: Long, cooldownMs: Long): Boolean {
        val now = System.currentTimeMillis()
        var acquired = false

        lastTriggerTimes.compute(reminderId) { _, memLastTime ->
            val effectiveLastTime = memLastTime ?: run {
                val diskTime = prefs?.getLong("last_trigger_$reminderId", 0L) ?: 0L
                if (diskTime > 0L) diskTime else null
            }

            if (effectiveLastTime != null && (now - effectiveLastTime) < cooldownMs) {
                // Cooldown encore actif : refus atomique
                acquired = false
                effectiveLastTime
            } else {
                // Cooldown expiré ou premier déclenchement : acquisition atomique
                acquired = true
                now
            }
        }

        if (acquired) {
            prefs?.edit()?.putLong("last_trigger_$reminderId", now)?.apply()
        }

        return acquired
    }

    /**
     * Vérifie si un rappel est actuellement dans sa fenêtre de cooldown.
     */
    fun isCooldownActive(reminderId: Long, cooldownMs: Long): Boolean {
        val lastTime = lastTriggerTimes[reminderId] ?: run {
            val diskTime = prefs?.getLong("last_trigger_$reminderId", 0L) ?: 0L
            if (diskTime > 0L) {
                lastTriggerTimes[reminderId] = diskTime
                diskTime
            } else null
        } ?: return false

        return (System.currentTimeMillis() - lastTime) < cooldownMs
    }

    /**
     * Enregistre manuellement un déclenchement ou un timestamp de référence.
     */
    fun recordTrigger(reminderId: Long, timestamp: Long = System.currentTimeMillis()) {
        lastTriggerTimes[reminderId] = timestamp
        prefs?.edit()?.putLong("last_trigger_$reminderId", timestamp)?.apply()
    }

    /**
     * Réinitialise le cooldown pour ce rappel (ex: sortie de zone confirmée).
     */
    fun resetCooldown(reminderId: Long) {
        lastTriggerTimes.remove(reminderId)
        prefs?.edit()?.remove("last_trigger_$reminderId")?.apply()
    }

    /**
     * Garde adaptatif pour les rappels de catégorie opportunistes.
     * Exige à la fois un temps minimal (60s) ET une progression spatiale minimale
     * (300m en véhicule pour éviter de déclencher dans la même grappe de commerces, 100m à pied).
     * Reste persistant même après un redémarrage du processus Android.
     */
    fun canTriggerCategoryOpportunity(
        reminderId: Long,
        currentLocation: Location?,
        activityType: Int
    ): Boolean {
        val lastRef = categoryTriggerRefs[reminderId] ?: run {
            val t = prefs?.getLong("opp_time_$reminderId", 0L) ?: 0L
            if (t > 0L) {
                val lat = prefs?.getFloat("opp_lat_$reminderId", 0f)?.toDouble() ?: 0.0
                val lng = prefs?.getFloat("opp_lng_$reminderId", 0f)?.toDouble() ?: 0.0
                val restored = OpportunityTriggerRef(t, lat, lng)
                categoryTriggerRefs[reminderId] = restored
                restored
            } else null
        } ?: return true

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
     * Persiste en RAM et sur disque pour survivre au kill de processus.
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
            prefs?.edit()?.apply {
                putLong("opp_time_$reminderId", timestamp)
                putFloat("opp_lat_$reminderId", location.latitude.toFloat())
                putFloat("opp_lng_$reminderId", location.longitude.toFloat())
                apply()
            }
        }
    }

    /**
     * Réinitialise le suivi d'opportunité d'un rappel (ex: complété ou annulé).
     */
    fun resetCategoryOpportunity(reminderId: Long) {
        categoryTriggerRefs.remove(reminderId)
        prefs?.edit()?.apply {
            remove("opp_time_$reminderId")
            remove("opp_lat_$reminderId")
            remove("opp_lng_$reminderId")
            apply()
        }
    }

    /**
     * Purge tous les enregistrements.
     */
    fun clear() {
        lastTriggerTimes.clear()
        categoryTriggerRefs.clear()
        prefs?.edit()?.clear()?.apply()
    }
}
