package com.remindly.location

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger

/**
 * Tests unitaires validant l'anti-course concurrentielle du TriggerCoordinator :
 * - Résolution atomique des déclenchements simultanés (Pulse vs Geofence à la même milliseconde)
 * - Respect du délai de temporisation (cooldown)
 * - Réinitialisation explicite (resetCooldown)
 * - Indépendance des rappels distincts
 */
class TriggerCoordinatorTest {

    private lateinit var coordinator: TriggerCoordinator

    @Before
    fun setUp() {
        coordinator = TriggerCoordinator()
    }

    @Test
    fun testAtomicSingleWinnerOnConcurrentTriggers() {
        val reminderId = 42L
        val cooldownMs = 60_000L
        val threadCount = 20
        val latch = CountDownLatch(1)
        val successCount = AtomicInteger(0)
        val failureCount = AtomicInteger(0)

        val threads = (1..threadCount).map {
            Thread {
                latch.await() // Attente pour départ synchronisé à la même microseconde
                if (coordinator.tryAcquireTrigger(reminderId, cooldownMs)) {
                    successCount.incrementAndGet()
                } else {
                    failureCount.incrementAndGet()
                }
            }
        }

        threads.forEach { it.start() }
        latch.countDown() // Départ simultané
        threads.forEach { it.join() }

        assertEquals("Exactement 1 seul thread doit acquérir le déclenchement", 1, successCount.get())
        assertEquals("Tous les autres threads (19) doivent être rejetés", 19, failureCount.get())
    }

    @Test
    fun testCooldownExpirationAllowsNextTrigger() {
        val reminderId = 10L
        val shortCooldownMs = 40L

        // 1er déclenchement réussi
        assertTrue(coordinator.tryAcquireTrigger(reminderId, shortCooldownMs))

        // Tentative immédiate : rejetée
        assertFalse(coordinator.tryAcquireTrigger(reminderId, shortCooldownMs))
        assertTrue(coordinator.isCooldownActive(reminderId, shortCooldownMs))

        // Attente de l'expiration du cooldown
        Thread.sleep(50L)

        // Nouvelle tentative : acceptée
        assertTrue(coordinator.tryAcquireTrigger(reminderId, shortCooldownMs))
    }

    @Test
    fun testResetCooldownAllowsImmediateTrigger() {
        val reminderId = 25L
        val cooldownMs = 60_000L

        assertTrue(coordinator.tryAcquireTrigger(reminderId, cooldownMs))
        assertFalse(coordinator.tryAcquireTrigger(reminderId, cooldownMs))

        // Sortie de zone : reset explicite
        coordinator.resetCooldown(reminderId)

        // Deuxième passage immédiatement autorisé
        assertTrue(coordinator.tryAcquireTrigger(reminderId, cooldownMs))
    }

    @Test
    fun testIndependentRemindersDoNotBlockEachOther() {
        val cooldownMs = 60_000L

        assertTrue(coordinator.tryAcquireTrigger(101L, cooldownMs))
        assertTrue(coordinator.tryAcquireTrigger(102L, cooldownMs))

        assertFalse(coordinator.tryAcquireTrigger(101L, cooldownMs))
        assertFalse(coordinator.tryAcquireTrigger(102L, cooldownMs))
    }
}
