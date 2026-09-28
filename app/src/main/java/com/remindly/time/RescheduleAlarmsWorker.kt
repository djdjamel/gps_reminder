package com.remindly.time

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.remindly.data.repo.ReminderRepository
import com.remindly.domain.model.ReminderStatus
import com.remindly.domain.model.TriggerType
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.first

import com.remindly.domain.model.RepeatRule

@HiltWorker
class RescheduleAlarmsWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted workerParams: WorkerParameters,
    private val reminderRepository: ReminderRepository,
    private val alarmScheduler: AlarmScheduler
) : CoroutineWorker(context, workerParams) {

    override suspend fun doWork(): Result {
        return try {
            val activeReminders = reminderRepository.observePersonalActive().first()
            val timeReminders = activeReminders.filter { 
                (it.triggerType == TriggerType.TIME || it.triggerType == TriggerType.BOTH) && 
                it.status == ReminderStatus.ACTIVE && 
                it.triggerTimeMillis != null 
            }

            val now = System.currentTimeMillis()
            for (reminder in timeReminders) {
                val triggerTime = reminder.triggerTimeMillis ?: continue
                if (triggerTime > now) {
                    alarmScheduler.schedule(reminder, triggerTime)
                } else {
                    // Notifier en retard pour les rappels échus pendant l'arrêt du téléphone
                    val notifier = com.remindly.notify.ReminderNotifier(applicationContext)
                    notifier.showTimeReminder(reminder)

                    val rule = reminder.repeatRule ?: RepeatRule.NONE
                    if (reminder.isRepeating && rule != RepeatRule.NONE) {
                        val nextTime = NextOccurrenceCalculator.calculateNext(
                            currentTimeMillis = now,
                            triggerTimeMillis = triggerTime,
                            repeatRule = rule,
                            repeatIntervalMin = reminder.repeatIntervalMin,
                            repeatDaysMask = reminder.repeatDaysMask
                        )
                        if (nextTime != null) {
                            val updated = reminder.copy(triggerTimeMillis = nextTime, status = ReminderStatus.ACTIVE)
                            reminderRepository.save(updated)
                            alarmScheduler.schedule(updated, nextTime)
                        } else if (reminder.triggerType == TriggerType.TIME) {
                            reminderRepository.setStatus(reminder.id, ReminderStatus.COMPLETED)
                        }
                    } else if (reminder.triggerType == TriggerType.TIME) {
                        reminderRepository.setStatus(reminder.id, ReminderStatus.COMPLETED)
                    }
                }
            }

            // Reprogrammer les alarmes d'activation différée de géofence
            val deferredReminders = activeReminders.filter {
                it.placeActiveFromMillis != null && it.placeActiveFromMillis > now
            }
            for (reminder in deferredReminders) {
                alarmScheduler.scheduleDeferredGeofence(reminder, reminder.placeActiveFromMillis!!)
            }

            Result.success()
        } catch (e: Exception) {
            e.printStackTrace()
            Result.retry()
        }
    }
}
