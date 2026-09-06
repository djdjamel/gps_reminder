package com.remindly.time

import com.remindly.domain.model.RepeatRule
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

object NextOccurrenceCalculator {

    fun calculateNext(
        currentTimeMillis: Long,
        triggerTimeMillis: Long,
        repeatRule: RepeatRule,
        repeatIntervalMin: Int?,
        repeatDaysMask: Int?
    ): Long? {
        val current = Instant.ofEpochMilli(currentTimeMillis).atZone(ZoneId.systemDefault())
        var next = Instant.ofEpochMilli(triggerTimeMillis).atZone(ZoneId.systemDefault())

        // Si le trigger de base est dans le futur, c'est la prochaine occurrence
        if (next.isAfter(current)) {
            return next.toInstant().toEpochMilli()
        }

        // Sinon, on cherche la prochaine selon la règle de répétition
        when (repeatRule) {
            RepeatRule.NONE -> return null
            RepeatRule.DAILY -> {
                while (!next.isAfter(current)) {
                    next = next.plusDays(1)
                }
            }
            RepeatRule.WEEKLY -> {
                // Pour simplifier en Phase 2 sans mask: juste +1 semaine
                // En vrai, il faut utiliser repeatDaysMask
                while (!next.isAfter(current)) {
                    next = next.plusWeeks(1)
                }
            }
            RepeatRule.MONTHLY -> {
                while (!next.isAfter(current)) {
                    next = next.plusMonths(1)
                }
            }
            RepeatRule.CUSTOM -> {
                val interval = (repeatIntervalMin ?: 0).toLong()
                if (interval <= 0) return null
                while (!next.isAfter(current)) {
                    next = next.plusMinutes(interval)
                }
            }
        }

        return next.toInstant().toEpochMilli()
    }
}
