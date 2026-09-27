package com.remindly.location

import android.location.Location
import com.google.android.gms.location.DetectedActivity

data class GeofenceRelevanceResult(
    val isRelevant: Boolean,
    val reason: String,
    val speedKmh: Float? = null,
    val accuracyM: Float? = null,
    val angleDiffDegrees: Float? = null
)

object GeofenceFilterUtils {

    /**
     * Calcule le rayon de pré-réveil matériel (WakeRadius / Coup de sonnette) adaptatif (Point 7-9 ChatGPT).
     *
     * Formule :
     *   WakeRadius = SemanticRadius + AnticipationDistance + LocationUncertainty
     *
     * Profils adaptatifs :
     * 1. Si la vitesse est connue (> 5 km/h) :
     *    - Anticipation de 50 secondes : (vitesse m/s) * 50s + 100m incertitude.
     * 2. Si la vitesse est inconnue, basée sur le type d'activité détecté :
     *    - WALKING / ON_FOOT / RUNNING : ~5 km/h -> buffer = 150m (borné entre 250m et 600m)
     *    - ON_BICYCLE : ~18 km/h -> buffer = 300m (borné entre 350m et 850m)
     *    - IN_VEHICLE : ~50 km/h -> buffer = 600m (borné entre 900m et 1500m)
     *    - Par défaut (STILL / UNKNOWN) : buffer = 450m (borné entre 400m et 1200m, seuil plancher véhicule 900m pour lieu fixe)
     */
    fun computeDynamicWakeRadius(
        semanticRadius: Float,
        activityType: Int = DetectedActivity.UNKNOWN,
        speedKmh: Float? = null,
        isFixedPlace: Boolean = false
    ): Float {
        val baseBuffer = if (speedKmh != null && speedKmh > 5f) {
            val speedMs = speedKmh / 3.6f
            val reactionDist = speedMs * 50f // 50 secondes d'anticipation
            reactionDist + 100f // Marge d'incertitude GPS
        } else {
            when (activityType) {
                DetectedActivity.WALKING,
                DetectedActivity.ON_FOOT,
                DetectedActivity.RUNNING -> 150f

                DetectedActivity.ON_BICYCLE -> 300f

                DetectedActivity.IN_VEHICLE -> 600f

                else -> 450f
            }
        }

        val rawWake = semanticRadius + baseBuffer

        return when {
            speedKmh != null && speedKmh > 70f -> {
                // Voie rapide / Autoroute : jusqu'à 1500m
                rawWake.coerceIn(900f, 1500f)
            }
            activityType == DetectedActivity.WALKING ||
            activityType == DetectedActivity.ON_FOOT ||
            activityType == DetectedActivity.RUNNING -> {
                // Mode piéton : éviter le réveil prématuré (min 250m, max 600m)
                rawWake.coerceIn(250f, 600f)
            }
            activityType == DetectedActivity.ON_BICYCLE -> {
                // Mode vélo : réveil modéré (min 350m, max 850m)
                rawWake.coerceIn(350f, 850f)
            }
            isFixedPlace -> {
                // Lieu fixe avec activité indéterminée ou véhicule : minimum 900m garanti (testé sur le terrain)
                maxOf(rawWake, 900f).coerceIn(400f, 1500f)
            }
            else -> {
                // POI général / veille standard : borné entre 400m et 1200m
                rawWake.coerceIn(400f, 1200f)
            }
        }
    }

    /**
     * Calcule le relèvement (bearing en degrés 0..360) entre deux coordonnées GPS.
     */
    fun computeBearingDegrees(fromLat: Double, fromLng: Double, toLat: Double, toLng: Double): Float {
        val lat1 = Math.toRadians(fromLat)
        val lat2 = Math.toRadians(toLat)
        val dLng = Math.toRadians(toLng - fromLng)

        val y = Math.sin(dLng) * Math.cos(lat2)
        val x = Math.cos(lat1) * Math.sin(lat2) - Math.sin(lat1) * Math.cos(lat2) * Math.cos(dLng)

        val brng = Math.toDegrees(Math.atan2(y, x)).toFloat()
        return (brng + 360f) % 360f
    }

    /**
     * Calcule la différence angulaire minimale entre deux caps (0..180 degrés).
     */
    fun calculateAngleDifference(bearing1: Float, bearing2: Float): Float {
        val diff = Math.abs((bearing1 - bearing2 + 540f) % 360f - 180f)
        return diff
    }

    /**
     * Calcule le seuil de précision GPS acceptable proportionnel au rayon de détection configuré.
     * Par défaut : 30% du rayon (ratio = 0.30f), avec un plancher minimal de 50m
     * pour garantir une tolérance suffisante même sur les petits rayons piétons (ex. 100m - 150m).
     */
    fun calculateMaxAccuracy(
        radiusM: Float?,
        ratio: Float = 0.30f,
        minAccuracyM: Float = 50f
    ): Float {
        val baseRadius = radiusM ?: 450f
        return maxOf(minAccuracyM, baseRadius * ratio)
    }

    /**
     * Valide si la précision GPS constatée est acceptable pour le rayon de détection configuré.
     */
    fun evaluateAccuracy(
        accuracyM: Float?,
        radiusM: Float?,
        ratio: Float = 0.30f,
        minAccuracyM: Float = 50f
    ): Boolean {
        if (accuracyM == null) return true
        val maxAllowed = calculateMaxAccuracy(radiusM, ratio, minAccuracyM)
        return accuracyM <= maxAllowed
    }

    /**
     * Évalue la pertinence d'un déclenchement de géofence.
     *
     * @param location Position GPS au moment du déclenchement
     * @param poiLat Latitude de la cible
     * @param poiLng Longitude de la cible
     * @param radiusM Rayon de la zone de détection configurée (calibre la précision admissible à 30%)
     * @param enabled Si le filtrage intelligent est activé dans les réglages
     * @param maxSpeedKmh Vitesse maximale autorisée (défaut : 65 km/h, au-dessus = transit rapide)
     * @param maxAccuracyM Précision GPS maximale acceptable (si null, calculée proportionnellement à 30% du rayon)
     * @param maxHeadingAngle Angle maximal entre le déplacement et le POI (défaut : 75°)
     */
    fun evaluateRelevance(
        location: Location?,
        poiLat: Double?,
        poiLng: Double?,
        radiusM: Float? = null,
        enabled: Boolean = true,
        maxSpeedKmh: Float = 65f,
        maxAccuracyM: Float? = null,
        maxHeadingAngle: Float = 75f
    ): GeofenceRelevanceResult {
        if (!enabled || location == null) {
            return GeofenceRelevanceResult(isRelevant = true, reason = "Filtrage désactivé ou position non fournie")
        }

        val accuracy = if (location.hasAccuracy()) location.accuracy else null
        val speedKmh = if (location.hasSpeed()) location.speed * 3.6f else null

        // 1. Contrôle de précision GPS (Accuracy Guard) proportionnel au rayon (30%)
        val effectiveMaxAccuracy = maxAccuracyM ?: calculateMaxAccuracy(radiusM, ratio = 0.30f)
        if (accuracy != null && accuracy > effectiveMaxAccuracy) {
            return GeofenceRelevanceResult(
                isRelevant = false,
                reason = "Précision GPS trop faible (${accuracy.toInt()}m > ${effectiveMaxAccuracy.toInt()}m)",
                accuracyM = accuracy,
                speedKmh = speedKmh
            )
        }

        // 2. Contrôle de vitesse autoroutière / transit rapide (Speed Gate)
        if (speedKmh != null && speedKmh > maxSpeedKmh) {
            return GeofenceRelevanceResult(
                isRelevant = false,
                reason = "Vitesse trop élevée pour un arrêt immédiat (${speedKmh.toInt()} km/h > ${maxSpeedKmh.toInt()} km/h)",
                accuracyM = accuracy,
                speedKmh = speedKmh
            )
        }

        // 3. Contrôle d'orientation et de cap (Heading / Bearing Filter)
        var angleDiff: Float? = null
        if (poiLat != null && poiLng != null && location.hasBearing()) {
            val isMoving = speedKmh == null || speedKmh >= 10f // En déplacement significatif (> 10 km/h)
            if (isMoving) {
                val bearingToPoi = computeBearingDegrees(
                    fromLat = location.latitude,
                    fromLng = location.longitude,
                    toLat = poiLat,
                    toLng = poiLng
                )
                val diff = calculateAngleDifference(location.bearing, bearingToPoi)
                angleDiff = diff

                if (diff > maxHeadingAngle) {
                    return GeofenceRelevanceResult(
                        isRelevant = false,
                        reason = "POI hors trajectoire / sens opposé (Angle: ${diff.toInt()}° > ${maxHeadingAngle.toInt()}°)",
                        accuracyM = accuracy,
                        speedKmh = speedKmh,
                        angleDiffDegrees = diff
                    )
                }
            }
        }

        return GeofenceRelevanceResult(
            isRelevant = true,
            reason = "Position et trajectoire validées",
            accuracyM = accuracy,
            speedKmh = speedKmh,
            angleDiffDegrees = angleDiff
        )
    }

    data class RollingZoneExitResult(
        val isValid: Boolean,
        val reason: String,
        val actualDistanceM: Float? = null,
        val minRequiredDistanceM: Float? = null
    )

    /**
     * Valide qu'un événement EXIT sur la zone tampon correspond à une sortie réelle
     * et non à un saut GPS erratique.
     */
    fun evaluateRollingZoneExit(
        actualDistanceM: Float?,
        exitRadiusM: Float,
        accuracyM: Float?,
        minDistanceRatio: Float = 0.80f,
        maxAccuracyM: Float = 70f
    ): RollingZoneExitResult {
        if (actualDistanceM != null) {
            val minRequired = exitRadiusM * minDistanceRatio
            if (actualDistanceM < minRequired) {
                return RollingZoneExitResult(
                    isValid = false,
                    reason = "Distance réelle insuffisante (${actualDistanceM.toInt()}m < ${minRequired.toInt()}m)",
                    actualDistanceM = actualDistanceM,
                    minRequiredDistanceM = minRequired
                )
            }
        }
        if (accuracyM != null && accuracyM > maxAccuracyM) {
            return RollingZoneExitResult(
                isValid = false,
                reason = "Précision GPS insuffisante (${accuracyM.toInt()}m > ${maxAccuracyM.toInt()}m)",
                actualDistanceM = actualDistanceM
            )
        }
        return RollingZoneExitResult(
            isValid = true,
            reason = "Sortie de zone tampon validée",
            actualDistanceM = actualDistanceM
        )
    }

    data class DeferredActivationResult(
        val isActivated: Boolean,
        val reason: String
    )

    /**
     * Évalue si un rappel avec lieu est déjà actif temporellement ("Au lieu, à partir de cette heure").
     */
    fun evaluateDeferredActivation(
        placeActiveFromMillis: Long?,
        currentTimeMillis: Long
    ): DeferredActivationResult {
        if (placeActiveFromMillis == null) {
            return DeferredActivationResult(isActivated = true, reason = "Pas de restriction temporelle d'activation")
        }
        return if (currentTimeMillis >= placeActiveFromMillis) {
            DeferredActivationResult(isActivated = true, reason = "Heure d'activation atteinte")
        } else {
            DeferredActivationResult(isActivated = false, reason = "Heure d'activation non encore atteinte (prévue à $placeActiveFromMillis)")
        }
    }

    enum class TriggerEventSource {
        GEOFENCE_ENTER,
        ALARM_DEADLINE
    }

    data class MutualCancellationResult(
        val shouldCancelAlarm: Boolean,
        val shouldRemoveGeofence: Boolean,
        val shouldCompleteReminder: Boolean
    )

    /**
     * Détermine les actions de désarmement mutuel pour un rappel combiné (Lieu OU Échéance).
     */
    fun evaluateMutualCancellation(
        hasPlace: Boolean,
        hasDeadline: Boolean,
        isRepeating: Boolean,
        source: TriggerEventSource
    ): MutualCancellationResult {
        val isCombined = hasPlace && hasDeadline
        return when (source) {
            TriggerEventSource.GEOFENCE_ENTER -> {
                MutualCancellationResult(
                    shouldCancelAlarm = isCombined,
                    shouldRemoveGeofence = !isRepeating,
                    shouldCompleteReminder = !isRepeating
                )
            }
            TriggerEventSource.ALARM_DEADLINE -> {
                MutualCancellationResult(
                    shouldCancelAlarm = false,
                    shouldRemoveGeofence = isCombined,
                    shouldCompleteReminder = !isRepeating
                )
            }
        }
    }
}
