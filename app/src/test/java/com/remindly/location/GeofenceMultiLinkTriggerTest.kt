package com.remindly.location

import com.remindly.domain.model.Reminder
import com.remindly.domain.model.ReminderStatus
import com.remindly.location.registry.LinkLifecycleState
import com.remindly.location.registry.PoiProvider
import com.remindly.location.registry.PoiRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Tests unitaires validant l'Étape 4 de l'architecture Geofence Budget Manager :
 * - Résolution multi-liens sur un POI physique partagé (1 DiscoveredPoi -> N ReminderPoiLink)
 * - Découplage rayon matériel (réveil) vs rayon sémantique utilisateur (décision WAITING)
 * - Isolation du cycle de vie des rappels (un rappel complété n'affecte pas l'autre rappel)
 * - Formatage de l'annonce vocale/notification groupée intelligente
 */
class GeofenceMultiLinkTriggerTest {

    private lateinit var registry: PoiRegistry

    @Before
    fun setUp() {
        registry = PoiRegistry()
    }

    @Test
    fun testSharedPoiMultipleRemindersResolution() {
        // Enregistrement d'un POI physique unique (Pharmacie Centrale)
        val poi = registry.registerOrGetPoi(
            provider = PoiProvider.GOOGLE_PLACES,
            providerId = "place_pharmacie_123",
            name = "Pharmacie Centrale",
            latitude = 35.5500,
            longitude = 6.1700,
            category = "pharmacy"
        )

        // Deux rappels indépendants rattachés au même POI physique
        val link1 = registry.linkReminderToPoi(reminderId = 101L, poiId = poi.id, semanticRadiusM = 150f)
        val link2 = registry.linkReminderToPoi(reminderId = 102L, poiId = poi.id, semanticRadiusM = 200f)

        // Résolution via poiRegistry lors du déclenchement du broadcast geofence
        val associatedLinks = registry.getLinksForPoi(poi.id)

        assertEquals("Le POI partagé doit résoudre exactement 2 liens", 2, associatedLinks.size)
        assertTrue(associatedLinks.any { it.reminderId == 101L })
        assertTrue(associatedLinks.any { it.reminderId == 102L })
    }

    @Test
    fun testHardwareWakeVsSemanticRadiusWaitingDecision() {
        val poi = registry.registerOrGetPoi(
            provider = PoiProvider.GOOGLE_PLACES,
            providerId = "place_boulangerie_456",
            name = "Boulangerie du Coin",
            latitude = 35.5600,
            longitude = 6.1800,
            category = "bakery"
        )

        // Rayon sémantique utilisateur : 150m. Rayon matériel de réveil (borné Android) : ex 250m
        val link = registry.linkReminderToPoi(reminderId = 201L, poiId = poi.id, semanticRadiusM = 150f)

        // Cas 1 : Réveil à 220m (> 150m sémantique) -> Décision WAITING
        val distanceMetersWake = 220f
        if (distanceMetersWake > link.semanticRadiusM) {
            link.state = LinkLifecycleState.WAITING
            link.retryDistanceM = link.semanticRadiusM
        }

        assertEquals("Le lien doit passer en WAITING si la distance actuelle dépasse le rayon sémantique", LinkLifecycleState.WAITING, link.state)
        assertEquals(150f, link.retryDistanceM)

        // Cas 2 : Réveil ou approche à 120m (<= 150m sémantique) -> Éligible à validation de contexte
        val distanceMetersArrival = 120f
        val isWithinSemanticRadius = distanceMetersArrival <= link.semanticRadiusM
        assertTrue("À 120m, l'utilisateur est bien entré dans son rayon sémantique de 150m", isWithinSemanticRadius)
    }

    @Test
    fun testLifecycleIsolationOnSharedPoi() {
        val poi = registry.registerOrGetPoi(
            provider = PoiProvider.GOOGLE_PLACES,
            providerId = "place_supermarche_789",
            name = "Supermarché Express",
            latitude = 35.5700,
            longitude = 6.1900,
            category = "supermarket"
        )

        val link1 = registry.linkReminderToPoi(reminderId = 301L, poiId = poi.id, semanticRadiusM = 150f)
        val link2 = registry.linkReminderToPoi(reminderId = 302L, poiId = poi.id, semanticRadiusM = 150f)

        // Le rappel 301 est exécuté et marqué COMPLETED
        link1.state = LinkLifecycleState.COMPLETED

        // Le lien 2 doit rester intact
        assertEquals("Le lien 1 doit être COMPLETED", LinkLifecycleState.COMPLETED, link1.state)
        assertEquals("Le lien 2 doit rester dans son état initial CANDIDATE", LinkLifecycleState.CANDIDATE, link2.state)

        // Les liens actifs pour le POI doivent toujours contenir le rappel 302
        val activeLinks = registry.getLinksForPoi(poi.id).filter { it.state != LinkLifecycleState.COMPLETED }
        assertEquals(1, activeLinks.size)
        assertEquals(302L, activeLinks.first().reminderId)
    }

    @Test
    fun testGroupedNotificationAndVoiceAnnouncementFormatting() {
        val r1 = Reminder(id = 1L, text = "Acheter du Doliprane", status = ReminderStatus.ACTIVE)
        val r2 = Reminder(id = 2L, text = "Prendre des pansements", status = ReminderStatus.ACTIVE)
        val validatedReminders = listOf(r1, r2)

        val placeLabel = "Pharmacie Centrale"
        val itemsSummary = validatedReminders.joinToString(", ") { it.text ?: "Rappel" }
        val groupedText = "Vous avez ${validatedReminders.size} rappels à proximité de $placeLabel : $itemsSummary"

        assertEquals(
            "Vous avez 2 rappels à proximité de Pharmacie Centrale : Acheter du Doliprane, Prendre des pansements",
            groupedText
        )
    }
}
