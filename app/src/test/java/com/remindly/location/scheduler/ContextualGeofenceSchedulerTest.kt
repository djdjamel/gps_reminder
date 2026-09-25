package com.remindly.location.scheduler

import com.remindly.domain.model.Reminder
import com.remindly.location.registry.DiscoveredPoi
import com.remindly.location.registry.LinkLifecycleState
import com.remindly.location.registry.PoiProvider
import com.remindly.location.registry.PoiRegistry
import com.remindly.location.registry.ReminderPoiLink
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Tests unitaires validant l'ordonnanceur ContextualGeofenceScheduler :
 * - Test 1 : Plafonnement strict au Hard Cap (85 max) avec préservation de la réserve (15 slots).
 * - Test 2 : Anti-Famine (Starvation Test) : 150 pharmacies vs 2 boulangeries -> les 2 boulangeries obtiennent leurs slots.
 * - Test 3 : Déduplication matérielle : Un POI partagé par N rappels ne consomme qu'un seul slot physique.
 * - Test 4 : Priorité utilisateur : Un rappel prioritaire (pinned = true) obtient un score d'allocation supérieur.
 * - Test 5 : Robustesse : Les rappels sans candidats valides (score = 0) ne consomment aucun slot fantôme.
 */
class ContextualGeofenceSchedulerTest {

    private lateinit var poiRegistry: PoiRegistry
    private lateinit var scheduler: ContextualGeofenceScheduler

    @Before
    fun setUp() {
        poiRegistry = PoiRegistry()
        scheduler = ContextualGeofenceScheduler(poiRegistry)
    }

    @Test
    fun testHardCapEnforcement_neverExceeds85Slots() {
        // Création de 5 rappels actifs avec 50 candidats chacun (250 candidats au total)
        val reminders = (1L..5L).map { id ->
            Reminder(id = id, text = "Rappel $id", placeCategory = "cat_$id")
        }

        val linksMap = mutableMapOf<Long, List<ReminderPoiLink>>()
        for (reminder in reminders) {
            val links = (1..50).map { i ->
                val poi = poiRegistry.registerOrGetPoi(
                    provider = PoiProvider.OPEN_STREET_MAP,
                    providerId = "poi_${reminder.id}_$i",
                    name = "Commerce ${reminder.id}_$i",
                    latitude = 36.70 + (i * 0.001),
                    longitude = 3.00 + (i * 0.001)
                )
                ReminderPoiLink(
                    reminderId = reminder.id,
                    poiId = poi.id,
                    semanticRadiusM = 200f,
                    contextScore = 60 + (i % 30) // Scores entre 60 et 89
                )
            }
            linksMap[reminder.id] = links
        }

        val result = scheduler.schedule(reminders, linksMap)

        assertTrue(
            "Le nombre total de géofences ne doit JAMAIS dépasser le plafond de 85 (obtenu: ${result.slotsUsed})",
            result.slotsUsed <= 85
        )
        assertEquals("slotsUsed doit correspondre exactement à la taille de allocatedPoiIds", result.allocatedPoiIds.size, result.slotsUsed)
        assertTrue("La réserve disponible doit être d'au moins 15 slots (obtenu: ${result.reserveAvailable})", result.reserveAvailable >= 15)
        assertEquals(100, result.slotsUsed + result.reserveAvailable)
    }

    @Test
    fun testStarvationProtection_minorityReminderGetsServed() {
        // Scénario classique de famine :
        // Rappel 1 (Pharmacie) : 150 POIs avec très bons scores
        // Rappel 2 (Boulangerie) : seulement 2 POIs
        val r1 = Reminder(id = 1L, text = "Pharmacies", placeCategory = "pharmacy")
        val r2 = Reminder(id = 2L, text = "Boulangeries", placeCategory = "bakery")

        val r1Links = (1..150).map { i ->
            val poi = poiRegistry.registerOrGetPoi(
                provider = PoiProvider.OPEN_STREET_MAP,
                providerId = "ph_$i",
                name = "Pharmacie $i",
                latitude = 36.75 + (i * 0.0001),
                longitude = 3.05 + (i * 0.0001)
            )
            ReminderPoiLink(reminderId = 1L, poiId = poi.id, semanticRadiusM = 200f, contextScore = 90)
        }

        val r2Links = (1..2).map { i ->
            val poi = poiRegistry.registerOrGetPoi(
                provider = PoiProvider.OPEN_STREET_MAP,
                providerId = "bakery_$i",
                name = "Boulangerie $i",
                latitude = 36.76 + (i * 0.0001),
                longitude = 3.06 + (i * 0.0001)
            )
            ReminderPoiLink(reminderId = 2L, poiId = poi.id, semanticRadiusM = 200f, contextScore = 75)
        }

        val linksMap = mapOf(1L to r1Links, 2L to r2Links)
        val result = scheduler.schedule(listOf(r1, r2), linksMap)

        val r2Summary = result.summaryByReminder[2L]
        assertNotNull("Le rappel 2 doit avoir un bilan d'ordonnancement", r2Summary)
        assertEquals("Les 2 boulangeries de R2 DOIVENT être allouées sans être écrasées par les 150 pharmacies", 2, r2Summary?.allocatedPoiCount)
        assertTrue("Le floor d'équité doit être atteint pour le rappel minoritaire", r2Summary?.floorMet == true)

        // R1 prend le reste de la capacité compétitive
        val r1Summary = result.summaryByReminder[1L]
        assertTrue("R1 doit occuper la grande majorité des slots restants", (r1Summary?.allocatedPoiCount ?: 0) >= 80)
        assertTrue(result.slotsUsed <= 85)
    }

    @Test
    fun testSharedPoiDeduplication_consumesSingleHardwareSlot() {
        val r1 = Reminder(id = 10L, text = "Acheter Doliprane", placeCategory = "pharmacy")
        val r2 = Reminder(id = 20L, text = "Prendre Pansements", placeCategory = "pharmacy")
        val r3 = Reminder(id = 30L, text = "Demander Ordonnance", placeCategory = "pharmacy")

        // Un même POI physique découvert
        val sharedPoi = poiRegistry.registerOrGetPoi(
            provider = PoiProvider.OPEN_STREET_MAP,
            providerId = "shared_ph_central",
            name = "Pharmacie Centrale",
            latitude = 36.7525,
            longitude = 3.0420
        )

        // Les 3 rappels s'y rattachent
        val link1 = ReminderPoiLink(reminderId = 10L, poiId = sharedPoi.id, semanticRadiusM = 150f, contextScore = 85)
        val link2 = ReminderPoiLink(reminderId = 20L, poiId = sharedPoi.id, semanticRadiusM = 250f, contextScore = 85)
        val link3 = ReminderPoiLink(reminderId = 30L, poiId = sharedPoi.id, semanticRadiusM = 300f, contextScore = 85)

        val linksMap = mapOf(
            10L to listOf(link1),
            20L to listOf(link2),
            30L to listOf(link3)
        )

        val result = scheduler.schedule(listOf(r1, r2, r3), linksMap)

        assertEquals("Les 3 rappels doivent être servis", 3, result.scheduledLinks.size)
        assertEquals("La Pharmacie Centrale partagée ne doit consommer qu'UN SEUL slot matériel !", 1, result.slotsUsed)
        assertTrue("Le POI partagé doit être dans les IDs alloués", result.allocatedPoiIds.contains(sharedPoi.id))
        assertEquals(LinkLifecycleState.SCHEDULED, link1.state)
        assertEquals(LinkLifecycleState.SCHEDULED, link2.state)
        assertEquals(LinkLifecycleState.SCHEDULED, link3.state)
    }

    @Test
    fun testReminderPriority_boostsAllocationScore() {
        val normalReminder = Reminder(id = 1L, text = "Normal", pinned = false)
        val highPriorityReminder = Reminder(id = 2L, text = "Urgent", pinned = true) // Pinned = High (weight 1.2)

        val poiA = poiRegistry.registerOrGetPoi(PoiProvider.OPEN_STREET_MAP, "poi_a", "Magasin A", 36.7, 3.0)
        val poiB = poiRegistry.registerOrGetPoi(PoiProvider.OPEN_STREET_MAP, "poi_b", "Magasin B", 36.8, 3.1)

        val linkNormal = ReminderPoiLink(reminderId = 1L, poiId = poiA.id, semanticRadiusM = 200f, contextScore = 70)
        val linkUrgent = ReminderPoiLink(reminderId = 2L, poiId = poiB.id, semanticRadiusM = 200f, contextScore = 70)

        // Limite artificielle à 1 seul slot pour forcer la compétition directe
        val linksMap = mapOf(1L to listOf(linkNormal), 2L to listOf(linkUrgent))
        val result = scheduler.schedule(listOf(normalReminder, highPriorityReminder), linksMap, overrideMaxSlots = 1)

        assertTrue(
            "Le rappel urgent (poids 1.2) doit avoir un AllocationScore supérieur (${linkUrgent.allocationScore}) au rappel normal (${linkNormal.allocationScore})",
            linkUrgent.allocationScore > linkNormal.allocationScore
        )
        assertEquals("Le seul slot disponible doit être remporté par le POI du rappel urgent", 1, result.slotsUsed)
        assertTrue(result.allocatedPoiIds.contains(poiB.id))
    }

    @Test
    fun testReminderWithZeroContextScore_doesNotConsumeGhostSlots() {
        val rValid = Reminder(id = 1L, text = "Proche")
        val rIrrelevant = Reminder(id = 2L, text = "Éloigné dans la mauvaise direction")

        val poiValid = poiRegistry.registerOrGetPoi(PoiProvider.OPEN_STREET_MAP, "p_valid", "Proche", 36.7, 3.0)
        val poiIrrelevant = poiRegistry.registerOrGetPoi(PoiProvider.OPEN_STREET_MAP, "p_bad", "Mauvaise direction", 36.0, 3.0)

        val linkValid = ReminderPoiLink(reminderId = 1L, poiId = poiValid.id, semanticRadiusM = 200f, contextScore = 80)
        val linkIrrelevant = ReminderPoiLink(reminderId = 2L, poiId = poiIrrelevant.id, semanticRadiusM = 200f, contextScore = 0) // Rejeté par le contexte

        val linksMap = mapOf(1L to listOf(linkValid), 2L to listOf(linkIrrelevant))
        val result = scheduler.schedule(listOf(rValid, rIrrelevant), linksMap)

        assertEquals("Seul le POI pertinent doit recevoir un slot", 1, result.slotsUsed)
        assertTrue(result.allocatedPoiIds.contains(poiValid.id))
        assertFalse(result.allocatedPoiIds.contains(poiIrrelevant.id))
        assertEquals(LinkLifecycleState.CANDIDATE, linkIrrelevant.state)
    }
}
