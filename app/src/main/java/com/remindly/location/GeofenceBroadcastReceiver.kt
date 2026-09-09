package com.remindly.location

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.location.Location
import android.util.Base64
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingEvent
import com.remindly.data.repo.ReminderRepository
import com.remindly.data.settings.VoiceAlarmSettingsRepository
import com.remindly.domain.model.AttachmentType
import com.remindly.domain.model.ReminderStatus
import com.remindly.media.AudioAlarmService
import com.remindly.notify.ReminderNotifier
import com.remindly.util.AppLogger
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

    @Inject
    lateinit var settingsRepository: VoiceAlarmSettingsRepository

    @Inject
    lateinit var appLogger: AppLogger

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onReceive(context: Context, intent: Intent) {
        val geofencingEvent = GeofencingEvent.fromIntent(intent)
        if (geofencingEvent == null) {
            android.util.Log.w("GeofenceReceiver", "onReceive: geofencingEvent is null")
            return
        }
        if (geofencingEvent.hasError()) {
            val errCode = geofencingEvent.errorCode
            android.util.Log.e("GeofenceReceiver", "onReceive: geofencingEvent error code = $errCode")
            appLogger.e("GEOFENCE_TRIGGER", "Erreur GeofencingEvent code: $errCode")
            return
        }

        val geofenceTransition = geofencingEvent.geofenceTransition
        val transitionName = when (geofenceTransition) {
            Geofence.GEOFENCE_TRANSITION_ENTER -> "ENTER (Entrée)"
            Geofence.GEOFENCE_TRANSITION_DWELL -> "DWELL (Présence)"
            Geofence.GEOFENCE_TRANSITION_EXIT -> "EXIT (Sortie)"
            else -> "TRANSITION_$geofenceTransition"
        }
        android.util.Log.i("GeofenceReceiver", "onReceive: Transition reçue = $transitionName")

        if (geofenceTransition == Geofence.GEOFENCE_TRANSITION_ENTER ||
            geofenceTransition == Geofence.GEOFENCE_TRANSITION_DWELL ||
            geofenceTransition == Geofence.GEOFENCE_TRANSITION_EXIT) {

            val triggeringGeofences = geofencingEvent.triggeringGeofences ?: return
            android.util.Log.i("GeofenceReceiver", "onReceive: ${triggeringGeofences.size} géofences déclenchées: ${triggeringGeofences.map { it.requestId }}")
            
            val pendingResult = goAsync()
            val prefs = context.getSharedPreferences("geofence_tracking", Context.MODE_PRIVATE)

            scope.launch {
                try {
                    val handledReminderIds = mutableSetOf<Long>()
                    for (geofence in triggeringGeofences) {
                        // 1. Interception de l'étape intermédiaire (Arrivée au travail / destination)
                        if (geofence.requestId.endsWith("_stage_dest")) {
                            val reminderId = geofence.requestId.substringBefore("_").toLongOrNull() ?: continue
                            if (!handledReminderIds.add(reminderId)) continue

                            val reminder = reminderRepository.getById(reminderId) ?: continue
                            val stepMsg = "🎯 ÉTAPE ATTEINTE (Arrivée à destination pour rappel '${reminder.text}'). Armement des POIs de retour !"
                            android.util.Log.i("GeofenceReceiver", stepMsg)
                            appLogger.success("STAGE_DEST_REACHED", stepMsg, reminderId)

                            geofenceManager.removeSingleGeofence(geofence.requestId)
                            geofenceManager.armCategoryPoIs(reminder)
                            continue
                        }

                        // 1b. Interception de la sortie de la zone tampon (Fenêtre Glissante)
                        if (geofence.requestId.endsWith("_exit_zone")) {
                            val reminderId = geofence.requestId.substringBefore("_").toLongOrNull() ?: continue
                            if (!handledReminderIds.add(reminderId)) continue

                            val reminder = reminderRepository.getById(reminderId) ?: continue
                            if (reminder.status == ReminderStatus.ACTIVE) {
                                val exitMsg = "🚗 Sortie de la zone tampon détectée pour rappel #${reminder.id} ('${reminder.text ?: "Catégorie"}'). Actualisation dynamique des POIs !"
                                android.util.Log.i("GeofenceReceiver", exitMsg)
                                appLogger.i("ROLLING_ZONE_EXIT", exitMsg, reminderId)

                                // Désarmement de l'ancienne grappe et armement de la nouvelle grappe avec la position actuelle
                                geofenceManager.removeGeofence(reminderId)
                                geofenceManager.armCategoryPoIs(reminder)
                            }
                            continue
                        }

                        // 2. Déclenchement d'un POI ou lieu fixe
                        val reminderId = geofence.requestId.substringBefore("_").toLongOrNull() ?: continue
                        if (!handledReminderIds.add(reminderId)) continue

                        val reminder = reminderRepository.getById(reminderId) ?: continue

                        val detectedPlaceName = prefs.getString("place_name_${geofence.requestId}", null) ?: if (geofence.requestId.contains("__")) {
                            try {
                                val encoded = geofence.requestId.substringAfter("__")
                                val bytes = Base64.decode(encoded, Base64.URL_SAFE or Base64.NO_WRAP)
                                String(bytes, Charsets.UTF_8).takeIf { it.isNotBlank() }
                            } catch (e: Exception) {
                                null
                            }
                        } else null

                        val placeLat = prefs.getFloat("place_lat_${geofence.requestId}", Float.NaN)
                        val placeLng = prefs.getFloat("place_lng_${geofence.requestId}", Float.NaN)

                        var distInfo = ""
                        val triggerLoc = geofencingEvent.triggeringLocation
                        if (triggerLoc != null && !placeLat.isNaN() && !placeLng.isNaN()) {
                            val results = FloatArray(1)
                            Location.distanceBetween(
                                triggerLoc.latitude,
                                triggerLoc.longitude,
                                placeLat.toDouble(),
                                placeLng.toDouble(),
                                results
                            )
                            distInfo = " | Dist: ${results[0].toInt()}m (±${triggerLoc.accuracy.toInt()}m)"
                        } else if (triggerLoc != null && reminder.placeLat != null && reminder.placeLng != null) {
                            val results = FloatArray(1)
                            Location.distanceBetween(
                                triggerLoc.latitude,
                                triggerLoc.longitude,
                                reminder.placeLat!!,
                                reminder.placeLng!!,
                                results
                            )
                            distInfo = " | Dist: ${results[0].toInt()}m (±${triggerLoc.accuracy.toInt()}m)"
                        }

                        val triggerMsg = "Transition $transitionName sur '${detectedPlaceName ?: reminder.placeLabel ?: "Lieu inconnu"}'$distInfo"
                        android.util.Log.i("GeofenceReceiver", "DÉCLENCHEMENT DU RAPPEL $reminderId: '${reminder.text}' - $triggerMsg")
                        appLogger.success("GEOFENCE_TRIGGERED", triggerMsg, reminderId)
                        
                        if (reminder.status == ReminderStatus.ACTIVE) {
                            val audioAttachment = reminder.attachments
                                .firstOrNull { it.type == AttachmentType.AUDIO }
                            val now = System.currentTimeMillis()
                            val lastTrigger = prefs.getLong("last_trigger_time_${reminderId}", 0L)
                            val isCooldown = (now - lastTrigger) < 90_000L // 90s anti-bounce

                            if (!isCooldown) {
                                prefs.edit().putLong("last_trigger_time_${reminderId}", now).apply()
                                
                                val notifier = ReminderNotifier(context)
                                notifier.showPlaceReminder(reminder, detectedPlaceName = detectedPlaceName)

                                val settings = settingsRepository.getSettings()
                                val shouldStartAudioService = (audioAttachment != null) ||
                                    ((settings.readTextRemindersAloud || settings.announcePlaceByVoice) && !reminder.text.isNullOrBlank())

                                if (shouldStartAudioService) {
                                    try {
                                        AudioAlarmService.start(
                                            context = context,
                                            audioPath = audioAttachment?.localPath,
                                            reminderText = reminder.text ?: "Rappel",
                                            reminderId = reminder.id,
                                            placeName = detectedPlaceName ?: reminder.placeLabel
                                        )
                                        appLogger.success("NOTIFICATION_FIRED", "Alarme vocale/TTS lancée", reminderId)
                                    } catch (e: Exception) {
                                        android.util.Log.e("GeofenceReceiver", "Impossible de démarrer AudioAlarmService: ${e.message}")
                                        appLogger.w("NOTIFICATION_FIRED", "Repli sur notification standard : ${e.message}", reminderId)
                                    }
                                } else {
                                    appLogger.success("NOTIFICATION_FIRED", "Notification affichée pour '${reminder.text}'", reminderId)
                                }
                            } else {
                                val notifier = ReminderNotifier(context)
                                notifier.showPlaceReminder(reminder, detectedPlaceName = detectedPlaceName)
                                appLogger.i("NOTIFICATION_FIRED", "Notification mise à jour pour '${detectedPlaceName ?: reminder.placeLabel}' (Cooldown audio actif)", reminderId)
                            }

                            val updatedReminder = reminder.copy(
                                placeLabel = detectedPlaceName ?: reminder.placeLabel
                            )
                            reminderRepository.save(updatedReminder)

                            // Désarmement UNIQUEMENT du lieu spécifique franchi pour laisser les autres commerces de la catégorie actifs
                            geofenceManager.removeSingleGeofence(geofence.requestId)
                            prefs.edit()
                                .remove("place_name_${geofence.requestId}")
                                .remove("place_lat_${geofence.requestId}")
                                .remove("place_lng_${geofence.requestId}")
                                .apply()
                        }
                    }
                } catch (e: Exception) {
                    appLogger.e("GEOFENCE_TRIGGER", "Erreur traitement broadcast: ${e.message}", e)
                } finally {
                    pendingResult.finish()
                }
            }
        }
    }
}
