package com.remindly.location.scheduler

import com.remindly.domain.model.Reminder
import com.remindly.location.registry.DiscoveredPoi
import com.remindly.location.registry.LinkLifecycleState
import com.remindly.location.registry.PoiRegistry
import com.remindly.location.registry.ReminderPoiLink
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.min

/**
 * Niveaux de priorité pour les rappels influençant l'attribution des géofences matérielles.
 */
enum class ReminderPriority(val weight: Double) {
    LOW(0.8),
    NORMAL(1.0),
    HIGH(1.2),
    CRITICAL(1.5);

    companion object {
        fun fromReminder(reminder: Reminder): ReminderPriority {
            return if (reminder.pinned) HIGH else NORMAL
        }
    }
}

/**
 * Bilan synthétique d'allocation pour un rappel donné.
 */
data class ReminderAllocationSummary(
    val reminderId: Long,
    val allocatedPoiCount: Int,
    val totalCandidatesCount: Int,
    val floorMet: Boolean
)

/**
 * Résultat d'une passe d'ordonnancement par le ContextualGeofenceScheduler.
 */
data class SchedulerResult(
    val allocatedPoiIds: Set<String>,
    val scheduledLinks: List<ReminderPoiLink>,
    val slotsUsed: Int,
    val reserveAvailable: Int,
    val summaryByReminder: Map<Long, ReminderAllocationSummary>
)

/**
 * Ordonnanceur contextuel centralisant l'allocation du budget rare des géofences Android (100 max).
 *
 * Principes et Invariants :
 * 1. Hard Cap de sécurité : 85 géofences actives maximum (configurable).
 * 2. Réserve transactionnelle : 15 slots non alloués préservés pour les transitions douces.
 * 3. Double Scoring : Séparation entre ContextScore (pertinence locale) et AllocationScore (mérite du slot).
 * 4. Déduplication matérielle : Un DiscoveredPoi partagé entre N rappels ne consomme qu'UN SEUL slot.
 * 5. Allocation en 2 étages (Anti-famine) :
 *    - Étage 1 (Fairness Floor) : Couverture minimale garantie pour chaque rappel ayant des candidats valides.
 *    - Étage 2 (Competitive Pool) : Distribution résiduelle au mérite global (AllocationScore).
 */
@Singleton
class ContextualGeofenceScheduler @Inject constructor(
    private val poiRegistry: PoiRegistry
) {
    companion object {
        const val ANDROID_SYSTEM_LIMIT = 100
        const val DEFAULT_MAX_ACTIVE_GEOFENCES = 85
        const val DEFAULT_ROTATION_RESERVE = 15
    }

    var maxActiveGeofences: Int = DEFAULT_MAX_ACTIVE_GEOFENCES
        set(value) {
            require(value in 1..ANDROID_SYSTEM_LIMIT) { "maxActiveGeofences doit être compris entre 1 et $ANDROID_SYSTEM_LIMIT" }
            field = value
        }

    /**
     * Calcule et attribue les slots de géofences disponibles pour l'ensemble des rappels actifs.
     *
     * @param activeReminders Liste des rappels actuellement actifs dans l'application.
     * @param linksByReminder Liens candidats groupés par identifiant de rappel.
     * @param overrideMaxSlots Permet de tester ou de forcer temporairement un quota spécifique (ex: tests unitaires).
     */
    fun schedule(
        activeReminders: List<Reminder>,
        linksByReminder: Map<Long, List<ReminderPoiLink>>,
        overrideMaxSlots: Int? = null
    ): SchedulerResult {
        val targetCapacity = (overrideMaxSlots ?: maxActiveGeofences).coerceIn(1, ANDROID_SYSTEM_LIMIT)
        val allocatedPois = mutableSetOf<String>()
        val scheduledLinks = mutableListOf<ReminderPoiLink>()
        val reminderSummaries = mutableMapOf<Long, ReminderAllocationSummary>()

        if (activeReminders.isEmpty()) {
            return SchedulerResult(
                allocatedPoiIds = emptySet(),
                scheduledLinks = emptyList(),
                slotsUsed = 0,
                reserveAvailable = ANDROID_SYSTEM_LIMIT,
                summaryByReminder = emptyMap()
            )
        }

        val reminderMap = activeReminders.associateBy { it.id }

        // 1. Calcul des AllocationScores pour chaque lien candidat
        for ((reminderId, links) in linksByReminder) {
            val reminder = reminderMap[reminderId] ?: continue
            val priority = ReminderPriority.fromReminder(reminder)
            val candidateCount = links.size

            // Bonus d'équité : donne un avantage aux rappels ayant très peu de candidats (ex: 2 boulangeries vs 150 pharmacies)
            val fairnessFactor = 1.0 + (1.0 / (candidateCount + 1).toDouble()).coerceAtMost(0.5)

            for (link in links) {
                val poi = poiRegistry.getPoi(link.poiId)
                val confidence = poi?.confidence?.toDouble() ?: 0.85

                // AllocationScore = ContextScore * PoidsPriorité * FacteurÉquité * ConfianceSource
                val calculatedScore = link.contextScore.toDouble() * priority.weight * fairnessFactor * confidence
                link.allocationScore = calculatedScore
            }
        }

        // 2. ÉTAGE 1 : Fairness Floor (Couverture minimale dynamique par rappel)
        val remindersWithCandidates = activeReminders.filter { reminder ->
            val links = linksByReminder[reminder.id] ?: emptyList()
            links.any { it.contextScore > 0 }
        }.sortedWith(
            compareByDescending<Reminder> { ReminderPriority.fromReminder(it).weight }
                .thenByDescending { reminder ->
                    linksByReminder[reminder.id]?.maxOfOrNull { it.allocationScore } ?: 0.0
                }
        )

        val nCategories = remindersWithCandidates.size
        val floorSlotsPerReminder = if (nCategories > 0) {
            (targetCapacity / nCategories).coerceIn(1, 10)
        } else {
            1
        }

        val allocatedPerReminderCount = mutableMapOf<Long, Int>()

        for (reminder in remindersWithCandidates) {
            val candidateLinks = linksByReminder[reminder.id]
                ?.filter { it.contextScore > 0 && it.state != LinkLifecycleState.COMPLETED }
                ?.sortedByDescending { it.allocationScore }
                ?: emptyList()

            var countForThisReminder = 0
            for (link in candidateLinks) {
                if (countForThisReminder >= floorSlotsPerReminder) break

                // Si le POI est déjà alloué par un autre rappel (déduplication), on associe le lien sans incrémenter les slots
                if (allocatedPois.contains(link.poiId)) {
                    link.state = LinkLifecycleState.SCHEDULED
                    scheduledLinks.add(link)
                    countForThisReminder++
                } else if (allocatedPois.size < targetCapacity) {
                    allocatedPois.add(link.poiId)
                    link.state = LinkLifecycleState.SCHEDULED
                    scheduledLinks.add(link)
                    countForThisReminder++
                }
            }
            allocatedPerReminderCount[reminder.id] = countForThisReminder
        }

        // 3. ÉTAGE 2 : Competitive Pool (Distribution des slots restants au mérite global)
        val remainingCapacity = targetCapacity - allocatedPois.size
        if (remainingCapacity > 0) {
            // Rassemblement de tous les liens restants non encore planifiés
            val remainingLinks = linksByReminder.values.flatten()
                .filter { it.state != LinkLifecycleState.SCHEDULED && it.state != LinkLifecycleState.COMPLETED && it.contextScore > 0 }
                .sortedByDescending { it.allocationScore }

            for (link in remainingLinks) {
                if (allocatedPois.contains(link.poiId)) {
                    // POI déjà dans les géofences allouées -> gratuit pour le budget matériel !
                    link.state = LinkLifecycleState.SCHEDULED
                    scheduledLinks.add(link)
                    allocatedPerReminderCount[link.reminderId] = (allocatedPerReminderCount[link.reminderId] ?: 0) + 1
                } else if (allocatedPois.size < targetCapacity) {
                    allocatedPois.add(link.poiId)
                    link.state = LinkLifecycleState.SCHEDULED
                    scheduledLinks.add(link)
                    allocatedPerReminderCount[link.reminderId] = (allocatedPerReminderCount[link.reminderId] ?: 0) + 1
                }

                if (allocatedPois.size >= targetCapacity && !allocatedPois.contains(link.poiId)) {
                    // Capacité maximale atteinte pour de nouveaux POIs physiques
                    break
                }
            }
        }

        // 4. Synthèse par rappel
        for (reminder in activeReminders) {
            val totalCandidates = linksByReminder[reminder.id]?.size ?: 0
            val allocatedCount = allocatedPerReminderCount[reminder.id] ?: 0
            val floorMet = allocatedCount >= min(floorSlotsPerReminder, totalCandidates)

            reminderSummaries[reminder.id] = ReminderAllocationSummary(
                reminderId = reminder.id,
                allocatedPoiCount = allocatedCount,
                totalCandidatesCount = totalCandidates,
                floorMet = floorMet
            )
        }

        val slotsUsed = allocatedPois.size
        val reserveAvailable = (ANDROID_SYSTEM_LIMIT - slotsUsed).coerceAtLeast(0)

        return SchedulerResult(
            allocatedPoiIds = allocatedPois,
            scheduledLinks = scheduledLinks,
            slotsUsed = slotsUsed,
            reserveAvailable = reserveAvailable,
            summaryByReminder = reminderSummaries
        )
    }
}
