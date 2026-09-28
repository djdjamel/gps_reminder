package com.remindly.location

import android.location.Location
import com.google.android.gms.location.DetectedActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger

/**
 * Tests unitaires validant l'anti-course concurrentielle et le garde adaptatif
 * du TriggerCoordinator :
 * - Résolution atomique des déclenchements simultanés (Pulse vs Geofence à la même milliseconde)
 * - Respect du délai de temporisation (cooldown)
 * - Réinitialisation explicite (resetCooldown)
 * - Indépendance des rappels distincts
 * - Garde adaptatif (temps >= 60s ET distance >= 300m en véhicule ou 100m à pied)
 * - Calcul géodésique Haversine pur Kotlin (computeDistanceMeters)
 */
class TriggerCoordinatorTest {

    private lateinit var coordinator: TriggerCoordinator

    private fun createLocation(lat: Double, lng: Double): Location {
        return object : Location("test") {
            override fun getLatitude(): Double = lat
            override fun getLongitude(): Double = lng
        }
    }

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

    @Test
    fun testComputeDistanceMeters_samePointReturnsZero() {
        val dist = coordinator.computeDistanceMeters(36.75, 3.05, 36.75, 3.05)
        assertEquals(0f, dist, 0.01f)
    }

    @Test
    fun testComputeDistanceMeters_knownDeltaLatitude() {
        // 0.001 degré de latitude ~ 111.19 mètres
        val dist = coordinator.computeDistanceMeters(36.75, 3.05, 36.751, 3.05)
        assertTrue("Distance attendue autour de 111m, obtenu: $dist", dist in 110f..113f)
    }

    @Test
    fun testCategoryOpportunity_firstAttemptIsAlwaysAllowed() {
        val reminderId = 201L
        val loc = createLocation(36.75, 3.05)
        val canTrigger = coordinator.canTriggerCategoryOpportunity(
            reminderId = reminderId,
            currentLocation = loc,
            activityType = DetectedActivity.IN_VEHICLE
        )
        assertTrue("Le premier passage pour un rappel catégorie doit toujours être autorisé", canTrigger)
    }

    @Test
    fun testCategoryOpportunity_blocksWhenUnder60Seconds() {
        val reminderId = 202L
        val locA = createLocation(36.75, 3.05)
        // Position B à 1000m de distance (> 300m)
        val locB = createLocation(36.76, 3.05)

        // Déclenchement il y a 20 secondes (< 60s)
        val now = System.currentTimeMillis()
        coordinator.recordCategoryTrigger(reminderId, locA, timestamp = now - 20_000L)

        val canTrigger = coordinator.canTriggerCategoryOpportunity(
            reminderId = reminderId,
            currentLocation = locB,
            activityType = DetectedActivity.IN_VEHICLE
        )
        assertFalse("Moins de 60s écoulées : doit être bloqué même avec grande distance", canTrigger)
    }

    @Test
    fun testCategoryOpportunity_inVehicle_requires300Meters() {
        val reminderId = 203L
        val locA = createLocation(36.75000, 3.05000)
        // locClose: ~111m (< 300m)
        val locClose = createLocation(36.75100, 3.05000)
        // locFar: ~444m (>= 300m)
        val locFar = createLocation(36.75400, 3.05000)

        val now = System.currentTimeMillis()
        // Déclenchement il y a 70 secondes (> 60s)
        coordinator.recordCategoryTrigger(reminderId, locA, timestamp = now - 70_000L)

        // À 111m en véhicule -> bloqué
        val canTriggerClose = coordinator.canTriggerCategoryOpportunity(
            reminderId = reminderId,
            currentLocation = locClose,
            activityType = DetectedActivity.IN_VEHICLE
        )
        assertFalse("À 111m en voiture (< 300m) : doit être bloqué", canTriggerClose)

        // À 444m en véhicule -> accordé
        val canTriggerFar = coordinator.canTriggerCategoryOpportunity(
            reminderId = reminderId,
            currentLocation = locFar,
            activityType = DetectedActivity.IN_VEHICLE
        )
        assertTrue("À 444m en voiture (>= 300m) : doit être accordé", canTriggerFar)
    }

    @Test
    fun testCategoryOpportunity_walking_requires100Meters() {
        val reminderId = 204L
        val locA = createLocation(36.75000, 3.05000)
        // locVeryClose: ~55m (< 100m)
        val locVeryClose = createLocation(36.75050, 3.05000)
        // locMedium: ~166m (>= 100m mais < 300m)
        val locMedium = createLocation(36.75150, 3.05000)

        val now = System.currentTimeMillis()
        // Déclenchement il y a 70 secondes (> 60s)
        coordinator.recordCategoryTrigger(reminderId, locA, timestamp = now - 70_000L)

        // À pied à 55m (< 100m) -> bloqué
        val canTriggerVeryClose = coordinator.canTriggerCategoryOpportunity(
            reminderId = reminderId,
            currentLocation = locVeryClose,
            activityType = DetectedActivity.WALKING
        )
        assertFalse("À 55m à pied (< 100m) : doit être bloqué", canTriggerVeryClose)

        // À pied à 166m (>= 100m) -> accordé (alors qu'en véhicule ce serait bloqué !)
        val canTriggerMedium = coordinator.canTriggerCategoryOpportunity(
            reminderId = reminderId,
            currentLocation = locMedium,
            activityType = DetectedActivity.WALKING
        )
        assertTrue("À 166m à pied (>= 100m) : doit être accordé", canTriggerMedium)
    }

    @Test
    fun testCategoryOpportunity_resetOpportunity_clearsGuard() {
        val reminderId = 205L
        val locA = createLocation(36.75000, 3.05000)
        val locClose = createLocation(36.75020, 3.05000)

        // Déclenchement tout juste maintenant
        coordinator.recordCategoryTrigger(reminderId, locA)

        // Immédiatement après à proximité -> bloqué
        assertFalse(
            coordinator.canTriggerCategoryOpportunity(reminderId, locClose, DetectedActivity.IN_VEHICLE)
        )

        // Reset explicite (ex: action [ C'est fait ] ou réactivation)
        coordinator.resetCategoryOpportunity(reminderId)

        // Doit être immédiatement réautorisé
        assertTrue(
            coordinator.canTriggerCategoryOpportunity(reminderId, locClose, DetectedActivity.IN_VEHICLE)
        )
    }

    private class FakeSharedPreferences : android.content.SharedPreferences {
        private val map = mutableMapOf<String, Any>()

        override fun getAll(): MutableMap<String, *> = map
        override fun getString(key: String?, defValue: String?): String? = map[key] as? String ?: defValue
        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? = map[key] as? MutableSet<String> ?: defValues
        override fun getInt(key: String?, defValue: Int): Int = map[key] as? Int ?: defValue
        override fun getLong(key: String?, defValue: Long): Long = map[key] as? Long ?: defValue
        override fun getFloat(key: String?, defValue: Float): Float = map[key] as? Float ?: defValue
        override fun getBoolean(key: String?, defValue: Boolean): Boolean = map[key] as? Boolean ?: defValue
        override fun contains(key: String?): Boolean = map.containsKey(key)
        override fun registerOnSharedPreferenceChangeListener(listener: android.content.SharedPreferences.OnSharedPreferenceChangeListener?) {}
        override fun unregisterOnSharedPreferenceChangeListener(listener: android.content.SharedPreferences.OnSharedPreferenceChangeListener?) {}

        override fun edit(): android.content.SharedPreferences.Editor = FakeEditor(map)

        private class FakeEditor(private val backingMap: MutableMap<String, Any>) : android.content.SharedPreferences.Editor {
            private val pending = mutableMapOf<String, Any?>()
            private var clearRequested = false

            override fun putString(key: String?, value: String?) = apply { key?.let { pending[it] = value } }
            override fun putStringSet(key: String?, values: MutableSet<String>?) = apply { key?.let { pending[it] = values } }
            override fun putInt(key: String?, value: Int) = apply { key?.let { pending[it] = value } }
            override fun putLong(key: String?, value: Long) = apply { key?.let { pending[it] = value } }
            override fun putFloat(key: String?, value: Float) = apply { key?.let { pending[it] = value } }
            override fun putBoolean(key: String?, value: Boolean) = apply { key?.let { pending[it] = value } }
            override fun remove(key: String?) = apply { key?.let { pending[it] = this } }
            override fun clear() = apply { clearRequested = true }
            override fun commit(): Boolean { apply(); return true }
            override fun apply() {
                if (clearRequested) backingMap.clear()
                for ((k, v) in pending) {
                    if (v === this) backingMap.remove(k) else if (v != null) backingMap[k] = v
                }
                pending.clear()
            }
        }
    }

    @Test
    fun testPersistence_survivesProcessDeathAndRecreation() {
        val sharedPrefs = FakeSharedPreferences()

        // Instance 1 : déclenche un commerce opportuniste
        val instance1 = TriggerCoordinator(sharedPrefs)
        val reminderId = 301L
        val locA = createLocation(36.75000, 3.05000)
        val now = System.currentTimeMillis()
        instance1.recordCategoryTrigger(reminderId, locA, timestamp = now - 70_000L) // > 60s
        assertTrue(instance1.tryAcquireTrigger(reminderId, cooldownMs = 60_000L))

        // Simulation : Android tue le process, instance1 est détruite de la RAM.
        // Instance 2 est créée ultérieurement avec le même SharedPreferences persistant
        val instance2 = TriggerCoordinator(sharedPrefs)

        // 1. Le cooldown de tryAcquireTrigger doit être actif dans instance2 (< 60s écoulées depuis tryAcquireTrigger)
        assertFalse("Le cooldown doit persister sur disque dans la nouvelle instance", instance2.tryAcquireTrigger(reminderId, 60_000L))

        // 2. La contrainte de distance de l'opportunité (300m) doit persister sur disque dans instance2 !
        val locClose = createLocation(36.75100, 3.05000) // ~111m (< 300m)
        val locFar = createLocation(36.75400, 3.05000)   // ~444m (>= 300m)

        assertFalse(
            "À 111m (< 300m), la contrainte d'opportunité restaurée depuis le disque doit bloquer",
            instance2.canTriggerCategoryOpportunity(reminderId, locClose, DetectedActivity.IN_VEHICLE)
        )

        assertTrue(
            "À 444m (>= 300m), la contrainte d'opportunité restaurée depuis le disque doit autoriser",
            instance2.canTriggerCategoryOpportunity(reminderId, locFar, DetectedActivity.IN_VEHICLE)
        )
    }
}

