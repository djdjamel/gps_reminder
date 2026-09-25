package com.remindly.location.registry

enum class LinkLifecycleState {
    CANDIDATE,          // Lien découvert, pas encore retenu par l'ordonnanceur
    SCHEDULED,          // Retenu par l'ordonnanceur pour être armé
    ARMED,              // Couvert par une géofence active
    GEOFENCE_ENTERED,   // Franchissement matériel de la géofence détecté par Android
    WAITING,            // Réveillé par le hardware mais pas encore au rayon sémantique ou moment prématuré
    CONTEXT_VALIDATED,  // Validé par le ContextEngine (score et conditions remplis)
    ALERTED,            // Alerte notifiée / TTS joué à l'utilisateur
    COMPLETED,          // Intention satisfaite et marquée terminée par l'utilisateur
    SUPPRESSED_NOW      // Rejeté temporairement dans ce contexte précis (ex: cap opposé, vitesse autoroute)
}

/**
 * Représente la relation contextuelle entre une intention utilisateur (Rappel)
 * et un lieu physique (DiscoveredPoi).
 *
 * Plusieurs ReminderPoiLink peuvent pointer vers le même POI physique sans dupliquer
 * la ressource matérielle de géofence Android.
 */
data class ReminderPoiLink(
    val reminderId: Long,
    val poiId: String,
    val semanticRadiusM: Float,              // Rayon exact exigé par l'utilisateur pour son rappel
    var contextScore: Int = 0,               // Pertinence physique pour CE rappel (0-100 pts)
    var allocationScore: Double = 0.0,       // Score composite d'ordonnancement pour les 85 slots
    var state: LinkLifecycleState = LinkLifecycleState.CANDIDATE,
    var retryDistanceM: Float? = null,       // Distance suggérée pour réévaluation lors d'un état WAITING
    var lastEvaluatedAt: Long = 0L
)
