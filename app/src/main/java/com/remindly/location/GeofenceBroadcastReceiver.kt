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
    lateinit var alarmScheduler: com.remindly.time.AlarmScheduler

    @Inject
    lateinit var settingsRepository: VoiceAlarmSettingsRepository

    @Inject
    lateinit var appLogger: AppLogger

    @Inject
    lateinit var diagnosticTracker: DiagnosticLocationTracker

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

            CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
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
                            if (geofenceTransition != Geofence.GEOFENCE_TRANSITION_EXIT) {
                                android.util.Log.d("GeofenceReceiver", "Transition non-EXIT ignorée sur exit_zone: $transitionName")
                                continue
                            }

                            val reminderId = geofence.requestId.substringBefore("_").toLongOrNull() ?: continue
                            if (!handledReminderIds.add(reminderId)) continue

                            val centerLat = prefs.getFloat("exit_center_lat_${reminderId}", Float.NaN)
                            val centerLng = prefs.getFloat("exit_center_lng_${reminderId}", Float.NaN)
                            val exitRadiusM = prefs.getFloat("exit_radius_${reminderId}", 2500f)

                            val triggerLoc = geofencingEvent.triggeringLocation
                            if (triggerLoc != null && !centerLat.isNaN() && !centerLng.isNaN()) {
                                val results = FloatArray(1)
                                Location.distanceBetween(
                                    centerLat.toDouble(),
                                    centerLng.toDouble(),
                                    triggerLoc.latitude,
                                    triggerLoc.longitude,
                                    results
                                )
                                val actualDistanceM = results[0]
                                val accuracyM = if (triggerLoc.hasAccuracy()) triggerLoc.accuracy else null
                                val evaluation = GeofenceFilterUtils.evaluateRollingZoneExit(
                                    actualDistanceM = actualDistanceM,
                                    exitRadiusM = exitRadiusM,
                                    accuracyM = accuracyM
                                )

                                if (!evaluation.isValid) {
                                    val rejectMsg = "🚫 Sortie de zone tampon rejetée (${evaluation.reason}) pour rappel #$reminderId"
                                    android.util.Log.w("GeofenceReceiver", rejectMsg)
                                    appLogger.w("ROLLING_ZONE_REJECTED", rejectMsg, reminderId)
                                    continue
                                }
                            }

                            val reminder = reminderRepository.getById(reminderId) ?: continue
                            if (reminder.status == ReminderStatus.ACTIVE) {
                                val exitMsg = "🚗 Sortie de la zone tampon validée (${exitRadiusM.toInt()}m) pour rappel #${reminder.id} ('${reminder.text ?: "Catégorie"}'). Actualisation dynamique des POIs !"
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

                        // Option 1 : Garde-fou d'activation différée (ne sonne qu'à partir de l'heure choisie)
                        if (reminder.placeActiveFromMillis != null && System.currentTimeMillis() < reminder.placeActiveFromMillis) {
                            val suppressMsg = "🚫 Alerte de lieu ignorée : l'heure d'activation différée n'est pas encore atteinte (${reminder.placeActiveFromMillis}) pour rappel #${reminder.id}"
                            android.util.Log.i("GeofenceReceiver", suppressMsg)
                            appLogger.i("GEOFENCE_SUPPRESSED", suppressMsg, reminderId)
                            continue
                        }

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
                        val targetLat = if (!placeLat.isNaN()) placeLat.toDouble() else reminder.placeLat
                        val targetLng = if (!placeLng.isNaN()) placeLng.toDouble() else reminder.placeLng

                        val settings = settingsRepository.getSettings()
                        val targetRadiusM = reminder.placeRadiusM ?: settings.poiDetectionRadiusM.toFloat()

                        val isFixedPlace = reminder.placeCategory == null
                        if (isFixedPlace) {
                            appLogger.i("GEOFENCE_FILTER_BYPASS", "Filtre intelligent contourné pour lieu fixe '${reminder.placeLabel}'", reminderId)
                        }

                        // Validation de Pertinence et d'Accessibilité (Anti-autoroute, Cap de déplacement, Précision GPS proportionnelle à 30% du rayon)
                        val triggerLoc = geofencingEvent.triggeringLocation
                        val relevance = GeofenceFilterUtils.evaluateRelevance(
                            location = triggerLoc,
                            poiLat = targetLat,
                            poiLng = targetLng,
                            radiusM = targetRadiusM,
                            enabled = settings.smartGeofenceFiltering && reminder.placeCategory != null,
                            maxSpeedKmh = settings.maxFilterSpeedKmh.toFloat(),
                            maxHeadingAngle = 75f
                        )

                        if (!relevance.isRelevant) {
                            val filterMsg = "🚫 Alerte filtrée (${relevance.reason}) pour '${detectedPlaceName ?: reminder.placeLabel ?: "Commerce"}'"
                            android.util.Log.w("GeofenceReceiver", filterMsg)
                            appLogger.i("GEOFENCE_FILTERED", filterMsg, reminderId)
                            // On ignore ce déclenchement sans désarmer la géofence (si l'utilisateur ralentit ou prend la bretelle plus tard)
                            continue
                        }

                        var distInfo = ""
                        if (triggerLoc != null && targetLat != null && targetLng != null) {
                            val results = FloatArray(1)
                            Location.distanceBetween(
                                triggerLoc.latitude,
                                triggerLoc.longitude,
                                targetLat,
                                targetLng,
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
                            val settings = settingsRepository.getSettings()
                            val cooldownMs = settings.geofenceCooldownSeconds * 1000L
                            val isCooldown = (now - lastTrigger) < cooldownMs

                            if (!isCooldown) {
                                prefs.edit().putLong("last_trigger_time_${reminderId}", now).apply()
                                
                                val notifier = ReminderNotifier(context)
                                notifier.showPlaceReminder(reminder, detectedPlaceName = detectedPlaceName)

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
                                appLogger.i("NOTIFICATION_FIRED", "Notification mise à jour pour '${detectedPlaceName ?: reminder.placeLabel}' (Cooldown de ${settings.geofenceCooldownSeconds}s actif)", reminderId)
                            }

                            val updatedReminder = reminder.copy(
                                placeLabel = detectedPlaceName ?: reminder.placeLabel,
                                status = ReminderStatus.ACTIVE
                            )
                            reminderRepository.save(updatedReminder)

                            // Démarrage automatique du suivi live 5s dans la zone (avec arrêt automatique à la sortie)
                            if (reminder.placeLat != null && reminder.placeLng != null) {
                                diagnosticTracker.startLiveZoneTracking(context, reminder)
                            }

                            // Option 2 (Mutual Cancellation) : Le lieu s'étant déclenché, annuler l'alarme d'échéance programmée
                            if (reminder.triggerType == com.remindly.domain.model.TriggerType.BOTH || reminder.triggerTimeMillis != null) {
                                alarmScheduler.cancel(reminderId)
                                val cancelMsg = "Arrivée au lieu validée : alarme d'échéance annulée pour rappel #$reminderId"
                                android.util.Log.i("GeofenceReceiver", cancelMsg)
                                appLogger.i("MUTUAL_CANCELLATION", cancelMsg, reminderId)
                            }

                            if (reminder.isRepeating) {
                                // Mode HABITUDE / RÉPÉTITIF :
                                // On maintient le geofence armé pour les prochains passages.
                                appLogger.i(
                                    "HABIT_KEPT_ACTIVE",
                                    "Rappel récurrent/habitude #${reminder.id} maintenu ACTIF pour les prochains passages.",
                                    reminderId
                                )
                            } else {
                                // Mode STANDARD : le rappel reste ACTIF dans la liste (l'utilisateur cochera manuellement).
                                // Pour les catégories POI, on désarme le POI spécifique déclenché pour éviter de sonner en boucle.
                                if (reminder.placeCategory != null) {
                                    geofenceManager.removeSingleGeofence(geofence.requestId)
                                    prefs.edit()
                                        .remove("place_name_${geofence.requestId}")
                                        .remove("place_lat_${geofence.requestId}")
                                        .remove("place_lng_${geofence.requestId}")
                                        .apply()
                                }
                            }
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
