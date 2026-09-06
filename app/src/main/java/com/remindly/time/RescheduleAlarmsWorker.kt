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
        try {
            // Note: En Phase 2, nous n'avons pas encore ajouté le TriggerType et TriggerTime au Reminder de domaine.
            // Le repository observePersonalActive retourne tous les actifs.
            // On devrait filtrer par triggerType == TIME. 
            // Pour le scaffold, on considère que cela sera ajouté dans l'intégration UI.
            
            /* Code simulé pour la reprogrammation
            val activeReminders = reminderRepository.observePersonalActive().first()
            val timeReminders = activeReminders.filter { it.triggerType == TriggerType.TIME }

            for (reminder in timeReminders) {
                // Roll-forward pour les répétitions si la date est dépassée
                val triggerTime = reminder.triggerTimeMillis ?: continue
                if (triggerTime > System.currentTimeMillis()) {
                    alarmScheduler.schedule(reminder, triggerTime)
                } else if (reminder.isRepeating) {
                    val nextTime = NextOccurrenceCalculator.calculateNext(
                        System.currentTimeMillis(),
                        triggerTime,
                        reminder.repeatRule!!,
                        reminder.repeatIntervalMin,
                        reminder.repeatDaysMask
                    )
                    if (nextTime != null) {
                        // TODO: Save new triggerTime
                        alarmScheduler.schedule(reminder, nextTime)
                    } else {
                        reminderRepository.setStatus(reminder.id, ReminderStatus.COMPLETED)
                    }
                } else {
                    // Notifier en retard pour les one-shots manqués
                    val notifier = ReminderNotifier(applicationContext)
                    notifier.showTimeReminder(reminder)
                }
            }
            */
            
            return Result.success()
        } catch (e: Exception) {
            return Result.retry()
        }
    }
}
