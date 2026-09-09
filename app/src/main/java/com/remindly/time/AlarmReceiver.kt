package com.remindly.time

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.remindly.data.repo.ReminderRepository
import com.remindly.domain.model.AttachmentType
import com.remindly.domain.model.ReminderStatus
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

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onReceive(context: Context, intent: Intent) {
        val reminderId = intent.getLongExtra(EXTRA_REMINDER_ID, -1L)
        if (reminderId == -1L) return

        val pendingResult = goAsync()

        scope.launch {
            try {
                val reminder = reminderRepository.getById(reminderId) ?: return@launch
                if (reminder.status != ReminderStatus.ACTIVE) return@launch

                // Si le rappel a une catégorie de lieu, armer les géofences de POI (fallback temporel)
                if (reminder.placeCategory != null) {
                    geofenceManager.armCategoryPoIs(reminder)
                }

                // 1. Afficher la notification ou jouer l'audio
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

                // Notification affichée / alarme jouée - le rappel reste actif jusqu'à action de l'utilisateur
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        const val EXTRA_REMINDER_ID = "extra_reminder_id"
    }
}
