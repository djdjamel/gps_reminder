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

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onReceive(context: Context, intent: Intent) {
        val reminderId = intent.getLongExtra(EXTRA_REMINDER_ID, -1L)
        if (reminderId == -1L) return

        val pendingResult = goAsync()

        scope.launch {
            try {
                val reminder = reminderRepository.getById(reminderId) ?: return@launch
                if (reminder.status != ReminderStatus.ACTIVE) return@launch

                // 1. Afficher la notification ou jouer l'audio
                val audioAttachment = reminder.attachments
                    .firstOrNull { it.type == AttachmentType.AUDIO }

                if (audioAttachment != null) {
                    // Rappel vocal : jouer l'enregistrement via AudioAlarmService
                    AudioAlarmService.start(
                        context = context,
                        audioPath = audioAttachment.localPath,
                        reminderText = reminder.text ?: "Rappel vocal",
                        reminderId = reminder.id
                    )
                } else {
                    // Rappel standard : notification classique
                    val notifier = ReminderNotifier(context)
                    notifier.showTimeReminder(reminder)
                }

                // 2. Gestion de la répétition
                // Note: En Phase 2, nous aurions besoin du RepeatRule sur le Reminder.
                // Pour l'instant, on marque juste COMPLETED car on n'a pas encore ajouté RepeatRule au Reminder domain model.
                // TODO: Ajouter RepeatRule au modèle de domaine et implémenter la reprogrammation.
                reminderRepository.setStatus(reminderId, ReminderStatus.COMPLETED)

            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        const val EXTRA_REMINDER_ID = "extra_reminder_id"
    }
}
