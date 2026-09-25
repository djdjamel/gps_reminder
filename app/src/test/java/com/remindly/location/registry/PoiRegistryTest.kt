package com.remindly.location.registry

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Tests unitaires validant strictement les invariants de l'Étape 1 :
 * - Invariant 1 : Déduplication stricte par identifiant et fusion multi-fournisseur (Google + OSM).
 * - Invariant 2 : 1 POI physique peut héberger N rappels sans dupliquer l'entité DiscoveredPoi.
 * - Invariant 3 : Calcul borné et adaptatif du rayon matériel materialWakeRadiusM (150m - 400m).
 * - Invariant 4 : Unicité matérielle absolue : 1 POI physique = au maximum 1 seule TrackedGeofence active.
 * - Invariant 5 : Indépendance du cycle de vie des liens : l'achèvement d'un rappel R1 ne détruit pas le POI ni le lien R2.
 */
class PoiRegistryTest {

    private lateinit var registry: PoiRegistry

    @Before
    fun setUp() {
        registry = PoiRegistry()
    }

    @Test
    fun testSameProviderAndId_yieldsSingleDiscoveredPoi() {
        val poi1 = registry.registerOrGetPoi(
            provider = PoiProvider.OPEN_STREET_MAP,
            providerId = "node_987654",
            name = "Pharmacie Centrale",
            latitude = 36.7525,
            longitude = 3.0420,
            category = "pharmacy"
        )

        val poi2 = registry.registerOrGetPoi(
            provider = PoiProvider.OPEN_STREET_MAP,
            providerId = "node_987654",
            name = "Pharmacie Centrale",
            latitude = 36.7525,
            longitude = 3.0420,
            category = "pharmacy"
        )

        assertEquals("Même ID et provider doivent pointer vers exactement le même POI", poi1.id, poi2.id)
        assertEquals("Le registre ne doit contenir qu'un seul POI", 1, registry.getAllPois().size)
    }

    @Test
    fun testMultiProviderMatching_mergesIntoHybridWithHighConfidence() {
        // Enregistrement source OSM
        val osmPoi = registry.registerOrGetPoi(
            provider = PoiProvider.OPEN_STREET_MAP,
            providerId = "osm_101",
            name = "Pharmacie El Amel",
            latitude = 36.7500,
            longitude = 3.0500,
            category = "pharmacy"
        )
        assertEquals(PoiProvider.OPEN_STREET_MAP, osmPoi.provider)
        assertEquals(0.85f, osmPoi.confidence, 0.01f)

        // Découverte du même lieu via Google Places à 10 mètres de distance
        val googlePoi = registry.registerOrGetPoi(
            provider = PoiProvider.GOOGLE_PLACES,
            providerId = "google_ChIJ123",
            name = "Pharmacie El Amel",
            latitude = 36.75008, // ~9 mètres
            longitude = 3.05005,
            category = "pharmacy"
        )

        assertEquals("Doit fusionner avec le POI existant", osmPoi.id, googlePoi.id)
        assertEquals("Le provider doit être promu en HYBRID", PoiProvider.HYBRID, googlePoi.provider)
        assertEquals("La confiance doit atteindre 1.0f pour confirmation croisée", 1.0f, googlePoi.confidence, 0.01f)
        assertEquals(1, registry.getAllPois().size)
    }

    @Test
    fun testOnePoiWithMultipleReminders_createsDistinctLinksSharingSamePoi() {
        val poi = registry.registerOrGetPoi(
            provider = PoiProvider.OPEN_STREET_MAP,
            providerId = "osm_pharmacy_centre",
            name = "Pharmacie de la Mairie",
            latitude = 36.7600,
            longitude = 3.0600
        )

        // Rattachement de 5 rappels distincts à ce même POI physique
        val link1 = registry.linkReminderToPoi(reminderId = 101L, poiId = poi.id, semanticRadiusM = 150f)
        val link2 = registry.linkReminderToPoi(reminderId = 102L, poiId = poi.id, semanticRadiusM = 200f)
        val link3 = registry.linkReminderToPoi(reminderId = 103L, poiId = poi.id, semanticRadiusM = 300f)
        val link4 = registry.linkReminderToPoi(reminderId = 104L, poiId = poi.id, semanticRadiusM = 180f)
        val link5 = registry.linkReminderToPoi(reminderId = 105L, poiId = poi.id, semanticRadiusM = 250f)

        val poiLinks = registry.getLinksForPoi(poi.id)
        assertEquals("Le POI physique doit héberger exactement 5 liens", 5, poiLinks.size)
        assertEquals("Il n'y a toujours qu'un seul POI dans le registre", 1, registry.getAllPois().size)

        // Vérification de l'indépendance sémantique
        assertEquals(150f, link1.semanticRadiusM, 0.01f)
        assertEquals(300f, link3.semanticRadiusM, 0.01f)
    }

    @Test
    fun testMaterialWakeRadiusBounds() {
        // Cas 1 : Rayon demandé très petit (100m) -> Borné au minimum matériel 150m
        val r1 = DiscoveredPoi.computeMaterialWakeRadius(listOf(100f))
        assertEquals(150f, r1, 0.01f)

        // Cas 2 : Rayons intermédiaires (180m, 280m) -> Prend le max (280m)
        val r2 = DiscoveredPoi.computeMaterialWakeRadius(listOf(180f, 280f))
        assertEquals(280f, r2, 0.01f)

        // Cas 3 : Rayon sémantique géant (1000m) -> Plafonné au maximum matériel 400m
        val r3 = DiscoveredPoi.computeMaterialWakeRadius(listOf(1000f))
        assertEquals(400f, r3, 0.01f)
    }

    @Test
    fun testSingleHardwareGeofencePerPoi_invariantStrict() {
        val poi = registry.registerOrGetPoi(
            provider = PoiProvider.OPEN_STREET_MAP,
            providerId = "osm_ph_1",
            name = "Pharmacie Centrale",
            latitude = 36.7525,
            longitude = 3.0420
        )

        // Rattachement de 3 rappels
        registry.linkReminderToPoi(reminderId = 1L, poiId = poi.id, semanticRadiusM = 150f)
        registry.linkReminderToPoi(reminderId = 2L, poiId = poi.id, semanticRadiusM = 250f)
        registry.linkReminderToPoi(reminderId = 3L, poiId = poi.id, semanticRadiusM = 300f)

        // Première allocation de géofence pour R1
        val geofence1 = registry.allocateGeofenceForPoi(poiId = poi.id, requestId = "geo_ph_1")
        assertNotNull(geofence1)
        assertEquals(1, registry.getActiveGeofenceCount())

        // Tentative d'allocation pour R2 sur le même POI
        val geofence2 = registry.allocateGeofenceForPoi(poiId = poi.id, requestId = "geo_ph_1_duplicate")
        assertSame("La deuxième allocation doit retourner la même instance existante", geofence1, geofence2)
        assertEquals("Le nombre de géofences actives doit rester STRICTEMENT ÉGAL À 1", 1, registry.getActiveGeofenceCount())

        // Tentative d'allocation pour R3 sur le même POI
        val geofence3 = registry.allocateGeofenceForPoi(poiId = poi.id, requestId = "geo_ph_1_third")
        assertSame("La troisième allocation doit également retourner la même instance", geofence1, geofence3)
        assertEquals("Toujours exactement 1 géofence matérielle active", 1, registry.getActiveGeofenceCount())

        // Vérification de l'état des liens
        val links = registry.getLinksForPoi(poi.id)
        links.forEach { link ->
            assertEquals(LinkLifecycleState.ARMED, link.state)
        }
    }

    @Test
    fun testReminderCompletion_preservesPoiAndOtherActiveLinks() {
        val poi = registry.registerOrGetPoi(
            provider = PoiProvider.OPEN_STREET_MAP,
            providerId = "osm_market",
            name = "Supermarché Express",
            latitude = 36.7500,
            longitude = 3.0500
        )

        val linkA = registry.linkReminderToPoi(reminderId = 10L, poiId = poi.id, semanticRadiusM = 200f)
        val linkB = registry.linkReminderToPoi(reminderId = 20L, poiId = poi.id, semanticRadiusM = 250f)

        registry.allocateGeofenceForPoi(poiId = poi.id, requestId = "geo_market")

        assertEquals(LinkLifecycleState.ARMED, linkA.state)
        assertEquals(LinkLifecycleState.ARMED, linkB.state)
        assertEquals(PoiHardwareStatus.ARMED, poi.hardwareStatus)

        // L'utilisateur complète le rappel A (ex: il a acheté le lait)
        linkA.state = LinkLifecycleState.COMPLETED

        // Le POI et le rappel B doivent rester ARMED et surveillés
        assertEquals(LinkLifecycleState.COMPLETED, linkA.state)
        assertEquals(LinkLifecycleState.ARMED, linkB.state)
        assertEquals("Le POI physique doit rester ARMED tant qu'un lien reste actif", PoiHardwareStatus.ARMED, poi.hardwareStatus)
        assertEquals(1, registry.getActiveGeofenceCount())
    }

    @Test
    fun testGeofenceRelease_retiresPoiHardwareStatus() {
        val poi = registry.registerOrGetPoi(
            provider = PoiProvider.OPEN_STREET_MAP,
            providerId = "osm_bakery",
            name = "Boulangerie Tradition",
            latitude = 36.7510,
            longitude = 3.0520
        )
        registry.linkReminderToPoi(reminderId = 55L, poiId = poi.id, semanticRadiusM = 200f)

        registry.allocateGeofenceForPoi(poiId = poi.id, requestId = "geo_bakery")
        assertEquals(PoiHardwareStatus.ARMED, poi.hardwareStatus)
        assertEquals(1, registry.getActiveGeofenceCount())

        // Libération (par exemple lors d'une rotation différentielle)
        val released = registry.releaseGeofence("geo_bakery")
        assertNotNull(released)
        assertEquals(0, registry.getActiveGeofenceCount())
        assertEquals(PoiHardwareStatus.RETIRED, poi.hardwareStatus)
    }
}
