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
                    if (reminder.triggerType == TriggerType.TIME) {
                        reminderRepository.setStatus(reminder.id, ReminderStatus.COMPLETED)
                    }
                }
            }
            Result.success()
        } catch (e: Exception) {
            e.printStackTrace()
            Result.retry()
        }
    }
}
