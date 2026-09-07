package com.remindly.location

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Base64
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
                        // 1. Interception de l'étape intermédiaire (Arrivée au travail / destination)
                        if (geofence.requestId.endsWith("_stage_dest")) {
                            val reminderId = geofence.requestId.substringBefore("_").toLongOrNull() ?: continue
                            if (!handledReminderIds.add(reminderId)) continue

                            val reminder = reminderRepository.getById(reminderId) ?: continue
                            android.util.Log.i("GeofenceReceiver", "🎯 ÉTAPE ATTEINTE (Arrivée à destination pour rappel $reminderId: '${reminder.text}'). Armement des POIs pour le retour !")

                            geofenceManager.removeSingleGeofence(geofence.requestId)
                            geofenceManager.armCategoryPoIs(reminder)
                            continue
                        }

                        // 2. Déclenchement d'un POI ou lieu fixe
                        val reminderId = geofence.requestId.substringBefore("_").toLongOrNull() ?: continue
                        if (!handledReminderIds.add(reminderId)) continue

                        val reminder = reminderRepository.getById(reminderId) ?: continue

                        val detectedPlaceName = if (geofence.requestId.contains("__")) {
                            try {
                                val encoded = geofence.requestId.substringAfter("__")
                                val bytes = Base64.decode(encoded, Base64.URL_SAFE or Base64.NO_WRAP)
                                String(bytes, Charsets.UTF_8).takeIf { it.isNotBlank() }
                            } catch (e: Exception) {
                                null
                            }
                        } else null

                        android.util.Log.i("GeofenceReceiver", "DÉCLENCHEMENT DU RAPPEL $reminderId: '${reminder.text}' (Catégorie: ${reminder.placeCategory}, Détecté: '$detectedPlaceName')")
                        
                        if (reminder.status == ReminderStatus.ACTIVE) {
                            val audioAttachment = reminder.attachments
                                .firstOrNull { it.type == AttachmentType.AUDIO }

                            if (audioAttachment != null) {
                                AudioAlarmService.start(
                                    context = context,
                                    audioPath = audioAttachment.localPath,
                                    reminderText = reminder.text ?: "Rappel vocal",
                                    reminderId = reminder.id,
                                    placeName = detectedPlaceName ?: reminder.placeLabel
                                )
                            } else {
                                val notifier = ReminderNotifier(context)
                                notifier.showPlaceReminder(reminder, detectedPlaceName = detectedPlaceName)
                            }

                            val updatedReminder = reminder.copy(
                                placeLabel = detectedPlaceName ?: reminder.placeLabel,
                                status = ReminderStatus.COMPLETED
                            )
                            reminderRepository.save(updatedReminder)
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
