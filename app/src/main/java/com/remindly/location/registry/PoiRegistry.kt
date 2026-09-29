package com.remindly.location.registry

import android.location.Location
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

/**
 * Registre central en mémoire assurant la gestion des POIs physiques,
 * de leurs liens avec les intentions utilisateurs (Rappels), et des ressources
 * de géofences matérielles actives.
 *
 * Invariants stricts garantis par cette classe :
 * 1. Déduplication : Un commerce physique n'a qu'une seule instance DiscoveredPoi.
 * 2. Unicité matérielle : Un DiscoveredPoi possède AU MAXIMUM UNE SEULE TrackedGeofence active.
 * 3. Indépendance sémantique : Un DiscoveredPoi peut être lié à N rappels simultanément.
 * 4. Rayon matériel adaptatif : materialWakeRadiusM est borné (150m-400m) basé sur les rayons des liens.
 */
@Singleton
class PoiRegistry @Inject constructor() {

    // Index des POIs physiques par identifiant canonique
    private val pois = ConcurrentHashMap<String, DiscoveredPoi>()

    // Index des liens : reminderId -> (poiId -> ReminderPoiLink)
    private val linksByReminder = ConcurrentHashMap<Long, ConcurrentHashMap<String, ReminderPoiLink>>()

    // Index inversé : poiId -> Set<ReminderPoiLink>
    private val linksByPoi = ConcurrentHashMap<String, MutableSet<ReminderPoiLink>>()

    // Index des géofences matérielles actives : poiId -> TrackedGeofence
    private val activeGeofencesByPoi = ConcurrentHashMap<String, TrackedGeofence>()

    // Index des géofences par requestId (pour Google Play Services)
    private val activeGeofencesByRequest = ConcurrentHashMap<String, TrackedGeofence>()

    /**
     * Enregistre ou met à jour un POI physique avec déduplication stricte multi-fournisseurs.
     * Si le même providerId existe, le POI existant est retourné.
     * Si un POI d'un autre fournisseur est situé à moins de 35m avec un nom similaire,
     * il est promu en HYBRID (confiance 1.0f).
     */
    fun registerOrGetPoi(
        provider: PoiProvider,
        providerId: String,
        name: String,
        latitude: Double,
        longitude: Double,
        category: String? = null,
        address: String? = null
    ): DiscoveredPoi {
        val canonicalId = "${provider.name.lowercase()}:$providerId"

        // 1. Vérification par identifiant direct
        pois[canonicalId]?.let { return it }

        // 2. Vérification par proximité spatiale et rapprochement multi-fournisseur (Google vs OSM)
        val distanceBuffer = FloatArray(1)
        for (existing in pois.values) {
            Location.distanceBetween(
                existing.latitude, existing.longitude,
                latitude, longitude,
                distanceBuffer
            )
            val distanceM = distanceBuffer[0]

            // Si même lieu à moins de 35m avec concordance de nom ou de catégorie
            if (distanceM <= 35f && isLikelySamePlace(existing.name, name)) {
                if (existing.provider != provider && existing.provider != PoiProvider.HYBRID) {
                    val hybridPoi = existing.copy(
                        provider = PoiProvider.HYBRID,
                        confidence = 1.0f,
                        category = category ?: existing.category,
                        address = address ?: existing.address
                    )
                    pois[existing.id] = hybridPoi
                    return hybridPoi
                }
                return existing
            }
        }

        // 3. Nouveau POI canonique
        val confidence = if (provider == PoiProvider.HYBRID) 1.0f else 0.85f
        val newPoi = DiscoveredPoi(
            id = canonicalId,
            provider = provider,
            providerId = providerId,
            name = name,
            latitude = latitude,
            longitude = longitude,
            category = category,
            address = address,
            confidence = confidence
        )
        pois[canonicalId] = newPoi
        return newPoi
    }

    /**
     * Associe un rappel à un POI physique avec son rayon sémantique dédié.
     * Met à jour dynamiquement le materialWakeRadiusM du POI sans dupliquer le hardware.
     */
    fun linkReminderToPoi(
        reminderId: Long,
        poiId: String,
        semanticRadiusM: Float,
        activityType: Int = com.google.android.gms.location.DetectedActivity.UNKNOWN
    ): ReminderPoiLink {
        val poi = pois[poiId] ?: throw IllegalArgumentException("POI inconnu dans le registre : $poiId")

        val reminderMap = linksByReminder.getOrPut(reminderId) { ConcurrentHashMap() }
        val link = reminderMap.getOrPut(poiId) {
            ReminderPoiLink(
                reminderId = reminderId,
                poiId = poiId,
                semanticRadiusM = semanticRadiusM
            )
        }
        if (link.state == LinkLifecycleState.COMPLETED) {
            link.state = LinkLifecycleState.CANDIDATE
        }

        val poiLinks = linksByPoi.getOrPut(poiId) { ConcurrentHashMap.newKeySet() }
        poiLinks.add(link)

        // Recalcul du rayon matériel unifié de réveil pour ce POI
        val allSemanticRadii = poiLinks.map { it.semanticRadiusM }
        poi.materialWakeRadiusM = DiscoveredPoi.computeMaterialWakeRadius(allSemanticRadii, activityType = activityType)

        return link
    }

    /**
     * Crée et enregistre la ressource de géofence matérielle pour un POI.
     * INVARIANT FONDAMENTAL : Une seule TrackedGeofence active autorisée par POI physique.
     * Si une géofence est déjà armée pour ce POI, elle est retournée sans en créer une nouvelle.
     */
    fun allocateGeofenceForPoi(
        poiId: String,
        requestId: String
    ): TrackedGeofence {
        val poi = pois[poiId] ?: throw IllegalArgumentException("Impossible d'allouer une géofence pour un POI inexistant : $poiId")

        // Invariant : vérifier si ce POI a déjà une géofence active
        activeGeofencesByPoi[poiId]?.let { existing ->
            return existing
        }

        val geofence = TrackedGeofence(
            requestId = requestId,
            poiId = poiId,
            latitude = poi.latitude,
            longitude = poi.longitude,
            radiusMeters = poi.materialWakeRadiusM
        )

        activeGeofencesByPoi[poiId] = geofence
        activeGeofencesByRequest[requestId] = geofence
        poi.hardwareStatus = PoiHardwareStatus.ALLOCATED

        // Les liens rattachés passent au statut SCHEDULED en attente de confirmation matérielle GMS
        linksByPoi[poiId]?.forEach { link ->
            if (link.state == LinkLifecycleState.CANDIDATE) {
                link.state = LinkLifecycleState.SCHEDULED
            }
        }

        return geofence
    }

    /**
     * Marque la géofence et ses liens comme effectivement armés suite à la confirmation de Google Play Services.
     */
    fun markGeofenceArmed(requestId: String) {
        val geofence = activeGeofencesByRequest[requestId] ?: return
        pois[geofence.poiId]?.hardwareStatus = PoiHardwareStatus.ARMED

        linksByPoi[geofence.poiId]?.forEach { link ->
            if (link.state == LinkLifecycleState.CANDIDATE || link.state == LinkLifecycleState.SCHEDULED) {
                link.state = LinkLifecycleState.ARMED
            }
        }
    }

    /**
     * Libère la géofence matérielle associée à un POI (par exemple lors d'une rotation).
     */
    fun releaseGeofence(requestId: String): TrackedGeofence? {
        val geofence = activeGeofencesByRequest.remove(requestId) ?: return null
        activeGeofencesByPoi.remove(geofence.poiId)
        pois[geofence.poiId]?.hardwareStatus = PoiHardwareStatus.RETIRED
        return geofence
    }

    fun getPoi(poiId: String): DiscoveredPoi? = pois[poiId]

    fun getLinksForPoi(poiId: String): List<ReminderPoiLink> = linksByPoi[poiId]?.toList() ?: emptyList()

    fun getLinksForReminder(reminderId: Long): List<ReminderPoiLink> = linksByReminder[reminderId]?.values?.toList() ?: emptyList()

    /**
     * Supprime définitivement les liaisons d'un rappel (par exemple lors de sa suppression ou complétion finale).
     */
    fun unregisterReminder(reminderId: Long) {
        val removedLinks = linksByReminder.remove(reminderId) ?: return
        for ((poiId, link) in removedLinks) {
            linksByPoi[poiId]?.remove(link)
        }
    }

    fun getActiveGeofenceForPoi(poiId: String): TrackedGeofence? = activeGeofencesByPoi[poiId]

    fun getAllActiveGeofences(): List<TrackedGeofence> = activeGeofencesByRequest.values.toList()

    fun getActiveGeofenceCount(): Int = activeGeofencesByRequest.size

    fun getAllPois(): List<DiscoveredPoi> = pois.values.toList()

    fun getAllLinks(): List<ReminderPoiLink> = linksByReminder.values.flatMap { it.values }

    fun clear() {
        pois.clear()
        linksByReminder.clear()
        linksByPoi.clear()
        activeGeofencesByPoi.clear()
        activeGeofencesByRequest.clear()
    }

    private fun isLikelySamePlace(name1: String, name2: String): Boolean {
        val n1 = name1.trim().lowercase()
        val n2 = name2.trim().lowercase()
        if (n1 == n2) return true
        if (n1.contains(n2) || n2.contains(n1)) return true
        return false
    }
}
