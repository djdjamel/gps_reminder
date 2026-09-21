package com.remindly.location

import android.location.Location
import com.google.android.gms.location.DetectedActivity
import com.remindly.data.settings.VoiceAlarmSettingsRepository
import com.remindly.domain.model.Reminder
import javax.inject.Inject
import javax.inject.Singleton

enum class ContextDecision {
    FULL_ALARM,        // Score >= 70 : Alarme vocale TTS + notification sonore complète
    DISCREET_NOTIF,    // Score 45..69 : Notification visuelle silencieuse / informative
    WAIT_AND_MONITOR,  // Score 25..44 : En approche rapide ou incertaine -> temporiser sans sonner
    SUPPRESS           // Score < 25 : Éloignement ou non pertinent -> ignorer
}

data class ScoreFactor(
    val description: String,
    val points: Int
)

data class DistanceSample(
    val distanceM: Float,
    val timestamp: Long = System.currentTimeMillis()
)

data class SpeedSample(
    val speedKmh: Float,
    val timestamp: Long = System.currentTimeMillis()
)

data class ContextEvaluation(
    val score: Int,
    val decision: ContextDecision,
    val reason: String,
    val factors: List<ScoreFactor>
)

@Singleton
class ContextRelevanceEngine @Inject constructor(
    private val userActivityTracker: UserActivityTracker,
    private val settingsRepository: VoiceAlarmSettingsRepository
) {

    fun evaluate(
        reminder: Reminder,
        currentLocation: Location?,
        targetLat: Double?,
        targetLng: Double?,
        currentDistanceM: Float?,
        recentDistances: List<DistanceSample> = emptyList(),
        recentSpeeds: List<SpeedSample> = emptyList(),
        overrideActivity: Int? = null,
        overrideSpeedKmh: Float? = null,
        overrideBearing: Float? = null,
        overrideCurrentLat: Double? = null,
        overrideCurrentLng: Double? = null
    ): ContextEvaluation {
        val factors = mutableListOf<ScoreFactor>()

        // 1. CAS PARTICULIER : Lieu Fixe Spécifique Délibéré (ex: 'Chez Brahimi', 'Petit Prince', etc.)
        // Pour un lieu précis choisi par l'utilisateur, l'intention est forte et explicite.
        val isFixedPlace = reminder.placeCategory == null
        if (isFixedPlace) {
            // Si on a des échantillons de distance récents montrant qu'on s'éloigne déjà nettement (>30m)
            val isClearlyReceding = recentDistances.size >= 2 &&
                    (recentDistances.last().distanceM - recentDistances.first().distanceM) > 30f &&
                    (currentDistanceM ?: 0f) > 300f

            if (isClearlyReceding) {
                factors.add(ScoreFactor("Lieu fixe mais éloignement net déjà engagé", -40))
                return ContextEvaluation(
                    score = 40,
                    decision = ContextDecision.DISCREET_NOTIF,
                    reason = "Éloignement constaté sur lieu fixe",
                    factors = factors
                )
            }

            factors.add(ScoreFactor("Lieu fixe délibéré (priorité garantie)", 85))
            return ContextEvaluation(
                score = 85,
                decision = ContextDecision.FULL_ALARM,
                reason = "Lieu fixe dans le rayon de détection",
                factors = factors
            )
        }

        // 2. CAS GÉNÉRAL : Catégorie / POI (ex: Pharmacie, Boulangerie, etc.)
        // Base neutre pour calcul probabiliste
        var totalScore = 30
        factors.add(ScoreFactor("Base neutre POI", 30))

        // A. Tendance de distance (Δd / Δt)
        if (recentDistances.size >= 2) {
            val oldest = recentDistances.first()
            val newest = recentDistances.last()
            val deltaDistance = newest.distanceM - oldest.distanceM

            if (deltaDistance < -8f) {
                // Rapprochement net
                totalScore += 30
                factors.add(ScoreFactor("Rapprochement net vers le POI (-${(-deltaDistance).toInt()}m)", 30))
            } else if (deltaDistance > 15f) {
                // Éloignement constaté
                totalScore -= 40
                factors.add(ScoreFactor("Éloignement constaté (+${deltaDistance.toInt()}m)", -40))
            }
        }

        // B. Activité physique et Détection d'Arrivée (Stationnement)
        val activity = overrideActivity ?: userActivityTracker.currentActivity.value
        val isPostDriving = userActivityTracker.isPostDrivingArrival()

        if (isPostDriving) {
            totalScore += 30
            factors.add(ScoreFactor("Arrivée post-conduite (voiture garée -> à pied)", 30))
        } else {
            when (activity) {
                DetectedActivity.WALKING, DetectedActivity.ON_FOOT -> {
                    totalScore += 20
                    factors.add(ScoreFactor("Activité piétonne (WALKING)", 20))
                }
                DetectedActivity.ON_BICYCLE -> {
                    totalScore += 15
                    factors.add(ScoreFactor("Activité cycliste (ON_BICYCLE)", 15))
                }
                DetectedActivity.IN_VEHICLE -> {
                    totalScore += 10
                    factors.add(ScoreFactor("En véhicule (IN_VEHICLE)", 10))
                }
                DetectedActivity.STILL -> {
                    totalScore -= 15
                    factors.add(ScoreFactor("Immobile / Stationnaire (STILL)", -15))
                }
            }
        }

        // C. Cap et Cône d'approche (Bearing)
        val effectiveBearing = overrideBearing ?: if (currentLocation != null && currentLocation.hasBearing()) currentLocation.bearing else null
        val effectiveCurrentLat = overrideCurrentLat ?: currentLocation?.latitude
        val effectiveCurrentLng = overrideCurrentLng ?: currentLocation?.longitude
        if (effectiveBearing != null && effectiveCurrentLat != null && effectiveCurrentLng != null && targetLat != null && targetLng != null) {
            val bearingToPoi = GeofenceFilterUtils.computeBearingDegrees(
                fromLat = effectiveCurrentLat,
                fromLng = effectiveCurrentLng,
                toLat = targetLat,
                toLng = targetLng
            )
            val angleDiff = GeofenceFilterUtils.calculateAngleDifference(effectiveBearing, bearingToPoi)

            when {
                angleDiff <= 45f -> {
                    totalScore += 20
                    factors.add(ScoreFactor("Alignement frontal (Angle: ${angleDiff.toInt()}° <= 45°)", 20))
                }
                angleDiff <= 90f -> {
                    totalScore += 10
                    factors.add(ScoreFactor("Approche latérale (Angle: ${angleDiff.toInt()}° <= 90°)", 10))
                }
                angleDiff > 110f -> {
                    totalScore -= 30
                    factors.add(ScoreFactor("Dos au POI / sens inverse (Angle: ${angleDiff.toInt()}°)", -30))
                }
            }
        }

        // D. Vitesse et Décélération (Δv / Δt)
        val speedKmh = overrideSpeedKmh ?: if (currentLocation != null && currentLocation.hasSpeed()) currentLocation.speed * 3.6f else null
        if (speedKmh != null) {
            // Décélération mesurée sur l'historique récent
            if (recentSpeeds.size >= 2) {
                val oldestSpeed = recentSpeeds.first().speedKmh
                val newestSpeed = recentSpeeds.last().speedKmh
                val deltaSpeed = newestSpeed - oldestSpeed

                if (deltaSpeed <= -10f && newestSpeed < 50f) {
                    totalScore += 20
                    factors.add(ScoreFactor("Décélération nette (${oldestSpeed.toInt()} -> ${newestSpeed.toInt()} km/h)", 20))
                }
            }

            when {
                speedKmh > 85f -> {
                    totalScore -= 35
                    factors.add(ScoreFactor("Vitesse autoroutière élevée (${speedKmh.toInt()} km/h)", -35))
                }
                speedKmh > 65f -> {
                    totalScore -= 15
                    factors.add(ScoreFactor("Vitesse de transit rapide (${speedKmh.toInt()} km/h)", -15))
                }
                speedKmh in 10f..45f -> {
                    totalScore += 10
                    factors.add(ScoreFactor("Vitesse d'approche urbaine (${speedKmh.toInt()} km/h)", 10))
                }
            }
        }

        // Clamping entre 0 et 100
        val finalScore = totalScore.coerceIn(0, 100)

        val decision = when {
            finalScore >= 70 -> ContextDecision.FULL_ALARM
            finalScore >= 45 -> ContextDecision.DISCREET_NOTIF
            finalScore >= 25 -> ContextDecision.WAIT_AND_MONITOR
            else -> ContextDecision.SUPPRESS
        }

        val reason = when (decision) {
            ContextDecision.FULL_ALARM -> "Score élevé ($finalScore pts) : alerte vocale et visuelle confirmée"
            ContextDecision.DISCREET_NOTIF -> "Score modéré ($finalScore pts) : notification visuelle discrète"
            ContextDecision.WAIT_AND_MONITOR -> "Score transitoire ($finalScore pts) : attente de confirmation de trajectoire"
            ContextDecision.SUPPRESS -> "Score insuffisant ($finalScore pts) : filtré (éloignement ou inadapté)"
        }

        return ContextEvaluation(
            score = finalScore,
            decision = decision,
            reason = reason,
            factors = factors
        )
    }
}
