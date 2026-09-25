package com.remindly.location.registry

/**
 * Représente une ressource de géofence matérielle enregistrée auprès de Google Play Services.
 *
 * Invariant strict du système :
 * - Le nombre total de TrackedGeofence actives ne doit JAMAIS dépasser la limite Android (100).
 * - L'ordonnanceur Remindly cible un maximum de 85 géofences armées, préservant 15 slots de
 *   capacité disponible pour les rotations différentielles sans interruption.
 * - Une TrackedGeofence correspond à exactement UN SEUL DiscoveredPoi (ou zone de sortie).
 */
data class TrackedGeofence(
    val requestId: String,          // Identifiant passé à Google Play Services GeofencingClient
    val poiId: String,              // ID du DiscoveredPoi associé (ou exit_zone)
    val latitude: Double,
    val longitude: Double,
    val radiusMeters: Float,        // Rayon physique materialWakeRadiusM
    val isExitZone: Boolean = false
)
