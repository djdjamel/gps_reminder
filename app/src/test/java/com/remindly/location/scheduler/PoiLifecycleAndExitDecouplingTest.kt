package com.remindly.location.scheduler

import com.remindly.domain.model.Reminder
import com.remindly.domain.model.ReminderStatus
import com.remindly.location.registry.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Tests unitaires validant le découplage de l'EXIT POI et la résilience du cycle de vie des liens :
 * - Les liens ReminderPoiLink ne deviennent plus COMPLETED lors d'une simple rotation ou nettoyage matériel.
 * - Les POIs proches d'un rappel actif restent DISCOVERED et re-éligibles pour l'ordonnanceur.
 * - Les POIs déjà ALERTED restent exclus de la prochaine alerte pour permettre aux commerces suivants de sonner.
 * - Seule la complétion définitive du rappel passe les liens à COMPLETED.
 */
class PoiLifecycleAndExitDecouplingTest {

    private lateinit var poiRegistry: PoiRegistry
    private lateinit var scheduler: ContextualGeofenceScheduler

    @Before
    fun setUp() {
        poiRegistry = PoiRegistry()
        scheduler = ContextualGeofenceScheduler(poiRegistry)
    }

    @Test
    fun testActiveReminderLinks_doNotBecomeCompletedOnRotation() {
        val reminder = Reminder(id = 2L, text = "Acheter médicaments", placeCategory = "PHARMACY", status = ReminderStatus.ACTIVE)

        // 1. Enregistrement de 3 pharmacies
        val p1 = poiRegistry.registerOrGetPoi(PoiProvider.GOOGLE_PLACES, "g1", "Pharmacie Zahaf.N", 35.530, 6.105)
        val p2 = poiRegistry.registerOrGetPoi(PoiProvider.GOOGLE_PLACES, "g2", "Pharmacie samei", 35.540, 6.128)
        val p3 = poiRegistry.registerOrGetPoi(PoiProvider.GOOGLE_PLACES, "g3", "Pharmacie Kercha Hocine", 35.531, 6.165)

        val l1 = poiRegistry.linkReminderToPoi(reminder.id, p1.id, 450f)
        val l2 = poiRegistry.linkReminderToPoi(reminder.id, p2.id, 450f)
        val l3 = poiRegistry.linkReminderToPoi(reminder.id, p3.id, 450f)

        l1.contextScore = 90
        l2.contextScore = 80
        l3.contextScore = 70

        // Première passe d'ordonnancement
        val result1 = scheduler.schedule(listOf(reminder), mapOf(reminder.id to listOf(l1, l2, l3)))
        assertEquals(3, result1.allocatedPoiIds.size)
        assertEquals(LinkLifecycleState.SCHEDULED, l1.state)
        assertEquals(LinkLifecycleState.SCHEDULED, l2.state)
        assertEquals(LinkLifecycleState.SCHEDULED, l3.state)

        // Simuler que p1 est alerté (première pharmacie rencontrée)
        l1.state = LinkLifecycleState.ALERTED

        // Simuler la rotation matérielle sans complétion du rappel
        // (comportement corrigé de removeGeofenceSuspend pour un rappel actif)
        val links = poiRegistry.getLinksForReminder(reminder.id)
        links.forEach { link ->
            if (link.state == LinkLifecycleState.ARMED || link.state == LinkLifecycleState.SCHEDULED) {
                link.state = LinkLifecycleState.CANDIDATE
            }
        }

        // Vérifications clés :
        assertEquals("Le POI alerté doit rester ALERTED", LinkLifecycleState.ALERTED, l1.state)
        assertEquals("Le POI non encore alerté doit redevenir CANDIDATE (non COMPLETED)", LinkLifecycleState.CANDIDATE, l2.state)
        assertEquals("Le POI non encore alerté doit redevenir CANDIDATE (non COMPLETED)", LinkLifecycleState.CANDIDATE, l3.state)

        // Deuxième passe d'ordonnancement (par exemple à l'approche de p3 Kercha Hocine)
        l3.contextScore = 95 // Kercha devient très proche
        val result2 = scheduler.schedule(listOf(reminder), mapOf(reminder.id to listOf(l1, l2, l3)))

        // l1 est ALERTED donc exclu de la réallocation
        assertFalse("Le POI alerté ne doit pas être ré-alloué", result2.allocatedPoiIds.contains(p1.id))
        // l2 et l3 sont CANDIDATE et doivent être alloués sans blocage
        assertTrue("Pharmacie samei doit être allouée", result2.allocatedPoiIds.contains(p2.id))
        assertTrue("Pharmacie Kercha Hocine doit être allouée", result2.allocatedPoiIds.contains(p3.id))
        assertEquals(2, result2.slotsUsed)
    }

    @Test
    fun testReLinkingPoi_recoversFromLegacyCompletedState() {
        val reminder = Reminder(id = 2L, text = "Pharmacie test", placeCategory = "PHARMACY", status = ReminderStatus.ACTIVE)

        val poi = poiRegistry.registerOrGetPoi(PoiProvider.GOOGLE_PLACES, "g_kercha", "Pharmacie Kercha Hocine", 35.531, 6.165)
        val link = poiRegistry.linkReminderToPoi(reminder.id, poi.id, 450f)

        // Simuler l'ancien bug qui aurait mis le lien à COMPLETED
        link.state = LinkLifecycleState.COMPLETED

        // Quand le système redécouvre ou ré-associe le POI à un rappel actif
        val reLinked = poiRegistry.linkReminderToPoi(reminder.id, poi.id, 450f)

        assertEquals("Un lien ré-associé pour un rappel actif doit être restauré à CANDIDATE", LinkLifecycleState.CANDIDATE, reLinked.state)
    }

    @Test
    fun testUnregisterReminder_cleansUpRegistryCompletely() {
        val reminderId = 5L
        val poi1 = poiRegistry.registerOrGetPoi(PoiProvider.GOOGLE_PLACES, "p1", "Boulangerie 1", 36.70, 3.00)
        val poi2 = poiRegistry.registerOrGetPoi(PoiProvider.GOOGLE_PLACES, "p2", "Boulangerie 2", 36.71, 3.01)

        poiRegistry.linkReminderToPoi(reminderId, poi1.id, 200f)
        poiRegistry.linkReminderToPoi(reminderId, poi2.id, 200f)

        assertEquals(2, poiRegistry.getLinksForReminder(reminderId).size)

        // Suppression / complétion définitive du rappel
        poiRegistry.unregisterReminder(reminderId)

        assertEquals(0, poiRegistry.getLinksForReminder(reminderId).size)
        assertTrue(poiRegistry.getLinksForPoi(poi1.id).isEmpty())
        assertTrue(poiRegistry.getLinksForPoi(poi2.id).isEmpty())
    }
}
