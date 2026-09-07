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

    @Inject
    lateinit var geofenceManager: GeofenceManager

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onReceive(context: Context, intent: Intent) {
        val geofencingEvent = GeofencingEvent.fromIntent(intent)
        if (geofencingEvent == null) {
            android.util.Log.w("GeofenceReceiver", "onReceive: geofencingEvent is null")
            return
        }
        if (geofencingEvent.hasError()) {
            android.util.Log.e("GeofenceReceiver", "onReceive: geofencingEvent error code = ${geofencingEvent.errorCode}")
            return
        }

        val geofenceTransition = geofencingEvent.geofenceTransition
        android.util.Log.i("GeofenceReceiver", "onReceive: Transition reçue = $geofenceTransition (1=ENTER, 2=EXIT)")

        if (geofenceTransition == Geofence.GEOFENCE_TRANSITION_ENTER ||
            geofenceTransition == Geofence.GEOFENCE_TRANSITION_EXIT) {

            val triggeringGeofences = geofencingEvent.triggeringGeofences ?: return
            android.util.Log.i("GeofenceReceiver", "onReceive: ${triggeringGeofences.size} géofences déclenchées: ${triggeringGeofences.map { it.requestId }}")
            
            val pendingResult = goAsync()
            scope.launch {
                try {
                    val handledReminderIds = mutableSetOf<Long>()
                    for (geofence in triggeringGeofences) {
                        // Extrait l'ID du rappel (supporte "42" ou "42_geo_3")
                        val reminderId = geofence.requestId.substringBefore("_").toLongOrNull() ?: continue
                        if (!handledReminderIds.add(reminderId)) continue

                        val reminder = reminderRepository.getById(reminderId) ?: continue
                        android.util.Log.i("GeofenceReceiver", "DÉCLENCHEMENT DU RAPPEL $reminderId: '${reminder.text}' (Catégorie: ${reminder.placeCategory})")
                        
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
                            geofenceManager.removeGeofence(reminderId)
                        }
                    }
                } finally {
                    pendingResult.finish()
                }
            }
        }
    }
}
