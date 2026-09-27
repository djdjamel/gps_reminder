package com.remindly.location.registry

enum class PoiProvider {
    GOOGLE_PLACES,
    OPEN_STREET_MAP,
    HYBRID,     // Confirmé simultanément par Google Places et OSM
    MANUAL      // Coordonnées ou adresse saisie manuellement par l'utilisateur
}

enum class PoiHardwareStatus {
    DISCOVERED, // Découvert par les APIs ou le cache, en attente d'ordonnancement
    ALLOCATED,  // Retenu par le scheduler pour recevoir une géofence
    ARMED,      // Géofence matérielle active auprès de Google Play Services
    RETIRED     // Dépassé géographiquement ou non prioritaire, retiré du hardware
}

/**
 * Représente un établissement ou commerce physique dans le monde réel.
 * Cette entité est indépendante des rappels humains qui peuvent lui être rattachés.
 *
 * Invariant strict : Un DiscoveredPoi ne peut donner lieu qu'à au maximum UNE SEULE
 * TrackedGeofence active dans le système Android.
 */
data class DiscoveredPoi(
    val id: String,                         // Clé unique (ex: "osm:node/12345" ou "google:ChIJ...")
    val provider: PoiProvider,
    val providerId: String,                 // Identifiant natif chez le fournisseur
    val name: String,
    val latitude: Double,
    val longitude: Double,
    val category: String? = null,
    val address: String? = null,
    val confidence: Float = 1.0f,           // 1.0f si source croisée (HYBRID), 0.85f si source unique
    var hardwareStatus: PoiHardwareStatus = PoiHardwareStatus.DISCOVERED,
    var materialWakeRadiusM: Float = 850f   // Rayon matériel unifié pour le pré-réveil Android (borné entre 400m et 1200m)
) {
    companion object {
        const val MIN_HARDWARE_WAKE_RADIUS_M = 400f
        const val MAX_HARDWARE_WAKE_RADIUS_M = 1200f
        const val DEFAULT_HARDWARE_WAKE_RADIUS_M = 850f

        /**
         * Calcule le rayon matériel de réveil en fonction des rayons sémantiques souhaités
         * par les différents rappels associés, avec une marge d'anticipation pour la détection
         * et l'embrayage du Pulse GPS avant l'arrivée au lieu (borné entre 400m et 1200m).
         */
        fun computeMaterialWakeRadius(semanticRadii: Collection<Float>): Float {
            if (semanticRadii.isEmpty()) return DEFAULT_HARDWARE_WAKE_RADIUS_M
            val maxRequested = semanticRadii.maxOrNull() ?: DEFAULT_HARDWARE_WAKE_RADIUS_M
            val desiredWake = maxRequested + 450f
            return desiredWake.coerceIn(MIN_HARDWARE_WAKE_RADIUS_M, MAX_HARDWARE_WAKE_RADIUS_M)
        }
    }
}
