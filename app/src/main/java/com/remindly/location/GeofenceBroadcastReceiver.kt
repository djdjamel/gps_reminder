package com.remindly.location

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingEvent
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
class GeofenceBroadcastReceiver : BroadcastReceiver() {

    @Inject
    lateinit var reminderRepository: ReminderRepository

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onReceive(context: Context, intent: Intent) {
        val geofencingEvent = GeofencingEvent.fromIntent(intent)
        if (geofencingEvent == null || geofencingEvent.hasError()) return

        val geofenceTransition = geofencingEvent.geofenceTransition
        if (geofenceTransition == Geofence.GEOFENCE_TRANSITION_ENTER ||
            geofenceTransition == Geofence.GEOFENCE_TRANSITION_EXIT) {

            val triggeringGeofences = geofencingEvent.triggeringGeofences ?: return
            
            val pendingResult = goAsync()
            scope.launch {
                try {
                    for (geofence in triggeringGeofences) {
                        val reminderId = geofence.requestId.toLongOrNull() ?: continue
                        val reminder = reminderRepository.getById(reminderId) ?: continue
                        
                        if (reminder.status == ReminderStatus.ACTIVE) {
                            val audioAttachment = reminder.attachments
                                .firstOrNull { it.type == AttachmentType.AUDIO }

                            if (audioAttachment != null) {
                                AudioAlarmService.start(
                                    context = context,
                                    audioPath = audioAttachment.localPath,
                                    reminderText = reminder.text ?: "Rappel vocal",
                                    reminderId = reminder.id
                                )
                            } else {
                                val notifier = ReminderNotifier(context)
                                notifier.showPlaceReminder(reminder)
                            }
                            reminderRepository.setStatus(reminderId, ReminderStatus.COMPLETED)
                        }
                    }
                } finally {
                    pendingResult.finish()
                }
            }
        }
    }
}
