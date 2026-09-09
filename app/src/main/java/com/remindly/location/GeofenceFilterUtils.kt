package com.remindly.location

import android.location.Location

data class GeofenceRelevanceResult(
    val isRelevant: Boolean,
    val reason: String,
    val speedKmh: Float? = null,
    val accuracyM: Float? = null,
    val angleDiffDegrees: Float? = null
)

object GeofenceFilterUtils {

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
     * Évalue si la position actuelle au moment du franchissement de géofence est pertinente
     * pour déclencher l'alerte du commerce de proximité.
     *
     * @param location Position GPS au déclenchement
     * @param poiLat Latitude du commerce ou POI
     * @param poiLng Longitude du commerce ou POI
     * @param enabled Si le filtrage intelligent est activé dans les réglages
     * @param maxSpeedKmh Vitesse maximale autorisée (défaut : 65 km/h, au-dessus = transit rapide)
     * @param maxAccuracyM Précision GPS maximale acceptable (défaut : 50m)
     * @param maxHeadingAngle Angle maximal entre le déplacement et le POI (défaut : 75°)
     */
    fun evaluateRelevance(
        location: Location?,
        poiLat: Double?,
        poiLng: Double?,
        enabled: Boolean = true,
        maxSpeedKmh: Float = 65f,
        maxAccuracyM: Float = 50f,
        maxHeadingAngle: Float = 75f
    ): GeofenceRelevanceResult {
        if (!enabled || location == null) {
            return GeofenceRelevanceResult(isRelevant = true, reason = "Filtrage désactivé ou position non fournie")
        }

        val accuracy = if (location.hasAccuracy()) location.accuracy else null
        val speedKmh = if (location.hasSpeed()) location.speed * 3.6f else null

        // 1. Contrôle de précision GPS (Accuracy Guard)
        if (accuracy != null && accuracy > maxAccuracyM) {
            return GeofenceRelevanceResult(
                isRelevant = false,
                reason = "Précision GPS trop faible (${accuracy.toInt()}m > ${maxAccuracyM.toInt()}m)",
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
}
