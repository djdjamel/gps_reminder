package com.remindly.time

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.remindly.data.repo.ReminderRepository
import com.remindly.domain.model.AttachmentType
import com.remindly.domain.model.ReminderStatus
import com.remindly.domain.model.RepeatRule
import com.remindly.media.AudioAlarmService
import com.remindly.notify.ReminderNotifier
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class AlarmReceiver : BroadcastReceiver() {

    @Inject
    lateinit var reminderRepository: ReminderRepository

    @Inject
    lateinit var alarmScheduler: AlarmScheduler

    @Inject
    lateinit var geofenceManager: com.remindly.location.GeofenceManager

    @Inject
    lateinit var appLogger: com.remindly.util.AppLogger

    override fun onReceive(context: Context, intent: Intent) {
        val reminderId = intent.getLongExtra(EXTRA_REMINDER_ID, -1L)
        if (reminderId == -1L) return

        val pendingResult = goAsync()

        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val reminder = reminderRepository.getById(reminderId) ?: return@launch
                if (reminder.status != ReminderStatus.ACTIVE) return@launch

                // 1. Branche réveil silencieux d'activation différée de géofence
                if (intent.action == AlarmScheduler.ACTION_ARM_DEFERRED_GEOFENCE) {
                    geofenceManager.addGeofence(reminder)
                    val armedMsg = "Heure d'activation différée atteinte pour '${reminder.placeLabel ?: "Lieu"}'. Géofence armé avec succès."
                    android.util.Log.i("AlarmReceiver", armedMsg)
                    appLogger.success("GEOFENCE_DEFERRED_ARMED", armedMsg, reminderId)
                    return@launch
                }

                // 2. Branche échéance temporelle standard
                // Option 2 (Mutual Cancellation) : si le rappel possédait aussi un lieu, désarmer le géofence
                val hasPlace = (reminder.placeLat != null && reminder.placeLng != null) || reminder.placeCategory != null
                if (hasPlace) {
                    val deadlineMsg = "⏰ Échéance temporelle atteinte pour '${reminder.placeLabel ?: "Lieu"}'. Désarmement du géofence."
                    android.util.Log.i("AlarmReceiver", deadlineMsg)
                    appLogger.i("DEADLINE_REACHED", deadlineMsg, reminderId)
                    geofenceManager.removeGeofence(reminderId)
                }

                // 3. Afficher la notification ou jouer l'audio
                val audioAttachment = reminder.attachments
                    .firstOrNull { it.type == AttachmentType.AUDIO }

                if (audioAttachment != null) {
                    // Rappel vocal : jouer l'enregistrement via AudioAlarmService
                    try {
                        AudioAlarmService.start(
                            context = context,
                            audioPath = audioAttachment.localPath,
                            reminderText = reminder.text ?: "Rappel vocal",
                            reminderId = reminder.id
                        )
                    } catch (e: Exception) {
                        android.util.Log.e("AlarmReceiver", "Impossible de démarrer AudioAlarmService en arrière-plan: ${e.message}. Affichage direct de la notification.")
                        val notifier = ReminderNotifier(context)
                        notifier.showTimeReminder(reminder)
                    }
                } else {
                    // Rappel standard : notification classique
                    val notifier = ReminderNotifier(context)
                    notifier.showTimeReminder(reminder)
                }

                // 4. Replanification récurrente ou passage en COMPLETED
                val rule = reminder.repeatRule ?: RepeatRule.NONE
                if (reminder.isRepeating && rule != RepeatRule.NONE) {
                    val nextTime = NextOccurrenceCalculator.calculateNext(
                        currentTimeMillis = System.currentTimeMillis(),
                        triggerTimeMillis = reminder.triggerTimeMillis ?: System.currentTimeMillis(),
                        repeatRule = rule,
                        repeatIntervalMin = reminder.repeatIntervalMin,
                        repeatDaysMask = reminder.repeatDaysMask
                    )
                    if (nextTime != null) {
                        val updated = reminder.copy(triggerTimeMillis = nextTime, status = ReminderStatus.ACTIVE)
                        reminderRepository.save(updated)
                        alarmScheduler.schedule(updated, nextTime)
                        if (hasPlace) {
                            geofenceManager.rearmGeofence(updated)
                        }
                        val rescheduleMsg = "🔁 Rappel récurrent #${reminder.id} replanifié pour le ${java.util.Date(nextTime)}"
                        android.util.Log.i("AlarmReceiver", rescheduleMsg)
                        appLogger.i("ALARM_RESCHEDULED", rescheduleMsg, reminderId)
                    } else {
                        reminderRepository.setStatus(reminderId, ReminderStatus.COMPLETED)
                    }
                } else {
                    reminderRepository.setStatus(reminderId, ReminderStatus.COMPLETED)
                }
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        const val EXTRA_REMINDER_ID = "extra_reminder_id"
    }
}
