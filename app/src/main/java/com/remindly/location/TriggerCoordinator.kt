package com.remindly.location

import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Coordinateur d'idempotence thread-safe garantissant qu'un même rappel
 * ne peut pas être déclenché simultanément par deux composants concurrents
 * (ex: DrivingPulseService et GeofenceBroadcastReceiver à la même milliseconde).
 */
@Singleton
class TriggerCoordinator @Inject constructor() {

    private val lastTriggerTimes = ConcurrentHashMap<Long, Long>()

    /**
     * Tente d'acquérir le verrou de déclenchement de manière atomique.
     * @param reminderId L'identifiant du rappel
     * @param cooldownMs La durée de temporisation anti-rebond en millisecondes
     * @return true si le déclenchement est autorisé (verrou acquis), false si déjà en cooldown
     */
    fun tryAcquireTrigger(reminderId: Long, cooldownMs: Long): Boolean {
        val now = System.currentTimeMillis()
        var acquired = false

        lastTriggerTimes.compute(reminderId) { _, lastTime ->
            if (lastTime != null && (now - lastTime) < cooldownMs) {
                // Cooldown encore actif : refus atomique
                acquired = false
                lastTime
            } else {
                // Cooldown expiré ou premier déclenchement : acquisition atomique
                acquired = true
                now
            }
        }

        return acquired
    }

    /**
     * Vérifie si un rappel est actuellement dans sa fenêtre de cooldown.
     */
    fun isCooldownActive(reminderId: Long, cooldownMs: Long): Boolean {
        val lastTime = lastTriggerTimes[reminderId] ?: return false
        return (System.currentTimeMillis() - lastTime) < cooldownMs
    }

    /**
     * Enregistre manuellement un déclenchement ou un timestamp de référence.
     */
    fun recordTrigger(reminderId: Long, timestamp: Long = System.currentTimeMillis()) {
        lastTriggerTimes[reminderId] = timestamp
    }

    /**
     * Réinitialise le cooldown pour ce rappel (ex: sortie de zone confirmée).
     */
    fun resetCooldown(reminderId: Long) {
        lastTriggerTimes.remove(reminderId)
    }

    /**
     * Purge tous les enregistrements.
     */
    fun clear() {
        lastTriggerTimes.clear()
    }
}
