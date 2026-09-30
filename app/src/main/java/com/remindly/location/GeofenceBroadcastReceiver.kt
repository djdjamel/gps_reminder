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
import com.remindly.domain.model.Reminder
import com.remindly.domain.model.ReminderStatus
import com.remindly.location.registry.LinkLifecycleState
import com.remindly.location.registry.PoiRegistry
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

    @Inject
    lateinit var contextEngine: ContextRelevanceEngine

    @Inject
    lateinit var poiRegistry: PoiRegistry

    @Inject
    lateinit var triggerCoordinator: TriggerCoordinator

    @Inject
    lateinit var vehicleModeManager: VehicleModeManager

    @Inject
    lateinit var userActivityTracker: UserActivityTracker

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
                            if (reminder.status != ReminderStatus.ACTIVE) {
                                geofenceManager.removeGeofence(reminderId)
                                continue
                            }
                            val stepMsg = "🎯 ÉTAPE ATTEINTE (Arrivée à destination pour rappel '${reminder.text}'). Armement des POIs de retour !"
                            android.util.Log.i("GeofenceReceiver", stepMsg)
                            appLogger.success("STAGE_DEST_REACHED", stepMsg, reminderId)

                            geofenceManager.completeStageDestinationAndArmPoIs(geofence.requestId, reminder)
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
                            val exitRadiusM = prefs.getFloat("exit_radius_${reminderId}", 900f)

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
                            if (reminder.status != ReminderStatus.ACTIVE) {
                                geofenceManager.removeGeofence(reminderId)
                                continue
                            }
                            val exitMsg = "🚗 Sortie de la zone tampon validée (${exitRadiusM.toInt()}m) pour rappel #${reminder.id} ('${reminder.text ?: "Catégorie"}'). Actualisation dynamique des POIs !"
                            android.util.Log.i("GeofenceReceiver", exitMsg)
                            appLogger.i("ROLLING_ZONE_EXIT", exitMsg, reminderId)

                            // Actualisation dynamique atomique de la fenêtre glissante (REMOVE -> SEARCH -> ARM -> SYNC sous un seul Mutex)
                            geofenceManager.refreshCategoryWindow(reminder)
                            continue
                        }

                        // 2. Déclenchement d'un POI (potentiellement multi-rappels) ou lieu fixe
                        val poiId = prefs.getString("poi_id_${geofence.requestId}", null)
                        val associatedLinks = if (poiId != null) poiRegistry.getLinksForPoi(poiId) else emptyList()

                        val remindersToProcess = if (associatedLinks.isNotEmpty()) {
                            associatedLinks.mapNotNull { link ->
                                val rem = reminderRepository.getById(link.reminderId)
                                if (rem != null && rem.status == ReminderStatus.ACTIVE) {
                                    Pair(rem, link)
                                } else null
                            }
                        } else {
                            val reminderId = geofence.requestId.substringBefore("_").toLongOrNull()
                            if (reminderId != null) {
                                val rem = reminderRepository.getById(reminderId)
                                if (rem != null && rem.status == ReminderStatus.ACTIVE) {
                                    listOf(Pair(rem, null))
                                } else emptyList()
                            } else emptyList()
                        }

                        if (remindersToProcess.isEmpty()) {
                            val suppressedMsg = "🚫 Alerte de lieu ignorée : aucun rappel actif rattaché à la géofence ${geofence.requestId}."
                            android.util.Log.i("GeofenceReceiver", suppressedMsg)
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

                        // 1c. Traitement spécifique de la transition SORTIE (EXIT) sur lieu fixe ou POI
                        if (geofenceTransition == Geofence.GEOFENCE_TRANSITION_EXIT) {
                            val placeName = detectedPlaceName ?: "Lieu"
                            remindersToProcess.forEach { (reminder, link) ->
                                val isCategoryReminder = reminder.placeCategory != null

                                if (isCategoryReminder) {
                                    // Pour un rappel de catégorie opportuniste : la sortie d'un commerce individuel
                                    // ne doit JAMAIS réarmer tout le rappel ni relancer de recherche POI.
                                    // Le renouvellement de grappe géographique est réservé exclusivement à la Rolling Exit Zone (900m).
                                    val exitMsg = "🚗 Sortie de la zone du commerce '$placeName' pour rappel #${reminder.id}. Surveillance locale relâchée."
                                    android.util.Log.i("GeofenceReceiver", exitMsg)
                                    appLogger.i("POI_EXIT", exitMsg, reminder.id)

                                    // Si le lien n'a pas été alerté ni ignoré, il redevient CANDIDATE pour le prochain ordonnancement
                                    if (link != null && link.state != LinkLifecycleState.ALERTED && link.state != LinkLifecycleState.SKIPPED) {
                                        link.state = LinkLifecycleState.CANDIDATE
                                    }
                                } else {
                                    // Pour un rappel à lieu fixe unique : réarmement classique pour la prochaine visite
                                    link?.state = LinkLifecycleState.ARMED
                                    val exitMsg = "🚗 Sortie de zone détectée pour '$placeName' (Rappel #${reminder.id}). Réarmement automatique pour le prochain passage."
                                    android.util.Log.i("GeofenceReceiver", exitMsg)
                                    appLogger.i("GEOFENCE_EXIT", exitMsg, reminder.id)

                                    if (diagnosticTracker.isDiagnosticActiveFor(reminder.id)) {
                                        diagnosticTracker.stopDiagnostic(context)
                                    }
                                    geofenceManager.resetCooldown(reminder.id)
                                    if (reminder.status == ReminderStatus.ACTIVE) {
                                        geofenceManager.rearmGeofence(reminder)
                                    }
                                }
                            }
                            continue
                        }

                        val placeLat = prefs.getFloat("place_lat_${geofence.requestId}", Float.NaN)
                        val placeLng = prefs.getFloat("place_lng_${geofence.requestId}", Float.NaN)
                        val targetLat = if (!placeLat.isNaN()) placeLat.toDouble() else remindersToProcess.first().first.placeLat
                        val targetLng = if (!placeLng.isNaN()) placeLng.toDouble() else remindersToProcess.first().first.placeLng

                        var distanceMeters: Float? = null
                        val locForDistance = geofencingEvent.triggeringLocation ?: try {
                            val fusedClient = com.google.android.gms.location.LocationServices.getFusedLocationProviderClient(context)
                            com.google.android.gms.tasks.Tasks.await(
                                fusedClient.lastLocation,
                                1500,
                                java.util.concurrent.TimeUnit.MILLISECONDS
                            )
                        } catch (_: Exception) { null }

                        if (locForDistance != null && targetLat != null && targetLng != null) {
                            val results = FloatArray(1)
                            Location.distanceBetween(
                                locForDistance.latitude,
                                locForDistance.longitude,
                                targetLat,
                                targetLng,
                                results
                            )
                            distanceMeters = results[0]
                        }

                        val settings = settingsRepository.getSettings()
                        val validatedReminders = mutableListOf<Pair<Reminder, ContextEvaluation>>()

                        for ((reminder, link) in remindersToProcess) {
                            if (!handledReminderIds.add(reminder.id)) continue

                            // Garde-fou d'activation différée
                            if (reminder.placeActiveFromMillis != null && System.currentTimeMillis() < reminder.placeActiveFromMillis) {
                                val suppressMsg = "🚫 Alerte de lieu ignorée : heure différée (${reminder.placeActiveFromMillis}) non atteinte pour rappel #${reminder.id}"
                                android.util.Log.i("GeofenceReceiver", suppressMsg)
                                appLogger.i("GEOFENCE_SUPPRESSED", suppressMsg, reminder.id)
                                continue
                            }

                            // SÉPARATION SÉMANTIQUE vs RÉVEIL MATÉRIEL (DOORBELL & ADAPTIVE PULSE)
                            // Si la géofence matérielle a réveillé le téléphone (ex: 850m), mais que le rappel exige 450m ou 150m
                            val configuredSemanticRadius = link?.semanticRadiusM
                                ?: prefs.getFloat("semantic_radius_${reminder.id}", reminder.placeRadiusM ?: settings.poiDetectionRadiusM.toFloat())

                            // Garde-fou précision GPS : si l'incertitude dépasse le seuil proportionnel (> 30% du rayon), passer en attente et lancer le Pulse
                            if (locForDistance != null && locForDistance.hasAccuracy()) {
                                val isAccuracyAcceptable = GeofenceFilterUtils.evaluateAccuracy(
                                    accuracyM = locForDistance.accuracy,
                                    radiusM = configuredSemanticRadius
                                )
                                if (!isAccuracyAcceptable) {
                                    link?.state = LinkLifecycleState.WAITING
                                    val accMsg = "⏳ [ACCURACY_WAIT] Précision GPS transitoirement faible (±${locForDistance.accuracy.toInt()}m > 30% de ${configuredSemanticRadius.toInt()}m) pour '${reminder.text}' -> Démarrage de l'Adaptive Pulse"
                                    android.util.Log.w("GeofenceReceiver", accMsg)
                                    appLogger.i("GEOFENCE_WAITING", accMsg, reminder.id)
                                    vehicleModeManager.startPulseForApproach("Attente d'un fix GPS précis (±${locForDistance.accuracy.toInt()}m)")
                                    continue
                                }
                            }

                            if (distanceMeters == null || distanceMeters > configuredSemanticRadius) {
                                link?.state = LinkLifecycleState.WAITING
                                link?.retryDistanceM = configuredSemanticRadius
                                val distStr = if (distanceMeters != null) "${distanceMeters.toInt()}m" else "inconnue"
                                val waitMsg = "⏳ [DOORBELL] Réveil matériel à distance $distStr. En attente du rayon sémantique (${configuredSemanticRadius.toInt()}m) pour '${reminder.text}' -> Démarrage de l'Adaptive Pulse"
                                android.util.Log.d("GeofenceReceiver", waitMsg)
                                appLogger.i("GEOFENCE_WAITING", waitMsg, reminder.id)
                                vehicleModeManager.startPulseForApproach("Réveil matériel ($distStr) pour '${reminder.text}' (Rayon: ${configuredSemanticRadius.toInt()}m)")
                                continue
                            }

                            val evaluation = contextEngine.evaluate(
                                reminder = reminder,
                                currentLocation = locForDistance,
                                targetLat = targetLat,
                                targetLng = targetLng,
                                currentDistanceM = distanceMeters
                            )

                            if (evaluation.decision == ContextDecision.SUPPRESS) {
                                link?.state = LinkLifecycleState.SUPPRESSED_NOW
                                val filterMsg = "🚫 Alerte filtrée (${evaluation.reason}) pour '${detectedPlaceName ?: reminder.placeLabel ?: "Commerce"}'"
                                android.util.Log.w("GeofenceReceiver", filterMsg)
                                appLogger.i("GEOFENCE_FILTERED", filterMsg, reminder.id)
                                continue
                            }

                            if (evaluation.decision == ContextDecision.WAIT_AND_MONITOR) {
                                link?.state = LinkLifecycleState.WAITING
                                val waitMsg = "⏳ [CONTEXT_WAIT] En attente de confirmation contextuelle (${evaluation.reason}, Score: ${evaluation.score}pts) pour '${detectedPlaceName ?: reminder.placeLabel ?: "Lieu"}' -> Maintien de l'Adaptive Pulse"
                                android.util.Log.i("GeofenceReceiver", waitMsg)
                                appLogger.i("GEOFENCE_WAITING", waitMsg, reminder.id)
                                vehicleModeManager.startPulseForApproach("Attente confirmation contextuelle (Score: ${evaluation.score}pts)")
                                continue
                            }

                            val isOpportunisticCategory = reminder.placeCategory != null && !reminder.isRepeating
                            if (isOpportunisticCategory) {
                                val currentActivity = userActivityTracker.currentActivity.value
                                val canTrigger = triggerCoordinator.canTriggerCategoryOpportunity(reminder.id, locForDistance, currentActivity)
                                if (!canTrigger) {
                                    val oppMsg = "⏳ [OPPORTUNITY_GUARD] Prochain commerce trop proche (< 300m) ou délai minimal (< 60s) non atteint pour #${reminder.id}"
                                    android.util.Log.i("GeofenceReceiver", oppMsg)
                                    appLogger.i("GEOFENCE_WAITING", oppMsg, reminder.id)
                                    continue
                                }
                            }

                            link?.state = LinkLifecycleState.CONTEXT_VALIDATED
                            validatedReminders.add(Pair(reminder, evaluation))
                        }

                        if (validatedReminders.isEmpty()) {
                            continue
                        }

                        // DÉCLENCHEMENT DE L'ALERTE : UNICITAIRE OU GROUPÉ (UX ChatGPT)
                        val now = System.currentTimeMillis()
                        val cooldownMs = settings.geofenceCooldownSeconds * 1000L

                        // 1. Filtrage strict des rappels éligibles et non soumis au cooldown (Autorité unique TriggerCoordinator)
                        val actuallyTriggeredReminders = mutableListOf<Pair<Reminder, ContextEvaluation>>()
                        for ((reminder, evaluation) in validatedReminders) {
                            val freshReminder = reminderRepository.getById(reminder.id)
                            if (freshReminder == null || freshReminder.status != ReminderStatus.ACTIVE) {
                                val suppressedMsg = "🚫 Alerte de lieu ignorée : rappel #${reminder.id} déjà terminé ou inactif."
                                android.util.Log.i("GeofenceReceiver", suppressedMsg)
                                continue
                            }

                            if (!triggerCoordinator.tryAcquireTrigger(reminder.id, cooldownMs)) {
                                val cdMsg = "⏳ Alerte ignorée : rappel #${reminder.id} en cooldown actif."
                                android.util.Log.i("GeofenceReceiver", cdMsg)
                                continue
                            }

                            if (reminder.placeCategory != null && !reminder.isRepeating) {
                                triggerCoordinator.recordCategoryTrigger(reminder.id, locForDistance, now)
                            }

                            actuallyTriggeredReminders.add(Pair(freshReminder, evaluation))
                        }

                        if (actuallyTriggeredReminders.isEmpty()) {
                            continue
                        }

                        val notifier = ReminderNotifier(context)
                        val placeLabel = detectedPlaceName ?: "ce commerce"

                        if (actuallyTriggeredReminders.size == 1) {
                            val (reminder, evaluation) = actuallyTriggeredReminders.first()
                            val distInfo = if (distanceMeters != null) " | Dist: ${distanceMeters.toInt()}m" else ""
                            val speedKmh = if (locForDistance != null && locForDistance.hasSpeed()) (locForDistance.speed * 3.6f) else 0f
                            val speedMs = if (locForDistance != null && locForDistance.hasSpeed() && locForDistance.speed > 0f) locForDistance.speed else (speedKmh / 3.6f)
                            val leadTimeSec = if (speedMs > 1.5f && distanceMeters != null && distanceMeters > 0f) (distanceMeters / speedMs).toInt() else null
                            val leadTimeMsg = if (leadTimeSec != null) " | Lead Time: +${leadTimeSec}s (Anticipation)" else ""
                            val triggerMsg = "Transition $transitionName sur '${detectedPlaceName ?: reminder.placeLabel ?: "Lieu"}'$distInfo$leadTimeMsg (Score: ${evaluation.score}pts)"
                            android.util.Log.i("GeofenceReceiver", "DÉCLENCHEMENT DU RAPPEL ${reminder.id}: '${reminder.text}' - $triggerMsg")
                            appLogger.success("GEOFENCE_TRIGGERED", triggerMsg, reminder.id)

                            notifier.showPlaceReminder(
                                reminder = reminder,
                                detectedPlaceName = detectedPlaceName,
                                distanceMeters = distanceMeters,
                                poiId = poiId
                            )

                            val audioAttachment = reminder.attachments.firstOrNull { it.type == AttachmentType.AUDIO }
                            val shouldStartAudioService = (evaluation.decision == ContextDecision.FULL_ALARM ||
                                (evaluation.decision == ContextDecision.DISCREET_NOTIF && evaluation.score >= 45)) &&
                                ((audioAttachment != null) || ((settings.readTextRemindersAloud || settings.announcePlaceByVoice) && !reminder.text.isNullOrBlank()))

                            if (shouldStartAudioService) {
                                try {
                                    AudioAlarmService.start(
                                        context = context,
                                        audioPath = audioAttachment?.localPath,
                                        reminderText = reminder.text ?: "Rappel",
                                        reminderId = reminder.id,
                                        placeName = detectedPlaceName ?: reminder.placeLabel,
                                        distanceMeters = distanceMeters
                                    )
                                    val firedMsg = "Alarme vocale/TTS lancée$distInfo | Vitesse: ${speedKmh.toInt()} km/h$leadTimeMsg"
                                    appLogger.success("NOTIFICATION_FIRED", firedMsg, reminder.id)
                                } catch (e: Exception) {
                                    android.util.Log.e("GeofenceReceiver", "Impossible de démarrer AudioAlarmService: ${e.message}")
                                }
                            }
                        } else {
                            // ANNONCE GROUPÉE INTELLIGENTE : Plusieurs rappels RÉELLEMENT déclenchés sur le même POI physique
                            val itemsSummary = actuallyTriggeredReminders.joinToString(", ") { it.first.text ?: "Rappel" }
                            val groupedText = "Vous avez ${actuallyTriggeredReminders.size} rappels à proximité de $placeLabel : $itemsSummary"

                            actuallyTriggeredReminders.forEach { (reminder, _) ->
                                notifier.showPlaceReminder(
                                    reminder = reminder,
                                    detectedPlaceName = placeLabel,
                                    distanceMeters = distanceMeters,
                                    poiId = poiId
                                )
                            }

                            try {
                                AudioAlarmService.start(
                                    context = context,
                                    reminderText = groupedText,
                                    reminderId = actuallyTriggeredReminders.first().first.id,
                                    placeName = placeLabel,
                                    distanceMeters = distanceMeters
                                )
                                appLogger.success("GROUPED_NOTIFICATION_FIRED", groupedText)
                            } catch (e: Exception) {
                                android.util.Log.e("GeofenceReceiver", "Erreur AudioAlarmService groupé: ${e.message}")
                            }
                        }

                        // Post-traitement pour chaque rappel RÉELLEMENT déclenché (persistance, annulation mutuelle, opportuniste/habitude/fin)
                        for ((reminder, _) in actuallyTriggeredReminders) {
                            val isOpportunistic = reminder.placeCategory != null && !reminder.isRepeating
                            val updatedReminder = reminder.copy(
                                placeLabel = detectedPlaceName ?: reminder.placeLabel,
                                placeLat = targetLat,
                                placeLng = targetLng,
                                status = if (isOpportunistic || reminder.isRepeating) ReminderStatus.ACTIVE else ReminderStatus.COMPLETED
                            )
                            reminderRepository.save(updatedReminder)

                            // Option 2 (Mutual Cancellation) : Le lieu s'étant déclenché, annuler l'alarme d'échéance programmée
                            if (reminder.triggerType == com.remindly.domain.model.TriggerType.BOTH || reminder.triggerTimeMillis != null) {
                                alarmScheduler.cancel(reminder.id)
                                val cancelMsg = "Arrivée au lieu validée : alarme d'échéance annulée pour rappel #${reminder.id}"
                                android.util.Log.i("GeofenceReceiver", cancelMsg)
                                appLogger.i("MUTUAL_CANCELLATION", cancelMsg, reminder.id)
                            }

                            if (isOpportunistic) {
                                // Mode Catégorie Opportuniste :
                                // Le rappel reste ACTIVE. Le lien du POI déclenché passe en ALERTED (sourdine).
                                if (poiId != null) {
                                    val links = poiRegistry.getLinksForReminder(reminder.id)
                                    links.find { it.poiId == poiId }?.state = LinkLifecycleState.ALERTED
                                }
                                val oppMsg = "🎯 Opportunité présentée pour #${reminder.id} ('${detectedPlaceName ?: reminder.placeLabel}'). Rappel maintenu ACTIF pour les prochains commerces."
                                android.util.Log.i("GeofenceReceiver", oppMsg)
                                appLogger.success("OPPORTUNITY_ALERTED", oppMsg, reminder.id)

                                CoroutineScope(Dispatchers.IO).launch {
                                    geofenceManager.synchronizeGeofences()
                                }
                            } else if (reminder.isRepeating) {
                                // Mode HABITUDE / RÉPÉTITIF :
                                if (targetLat != null && targetLng != null) {
                                    diagnosticTracker.startLiveZoneTracking(context, updatedReminder)
                                }
                                appLogger.i(
                                    "HABIT_KEPT_ACTIVE",
                                    "Rappel récurrent/habitude #${reminder.id} maintenu ACTIF pour les prochains passages.",
                                    reminder.id
                                )
                            } else {
                                // Mode STANDARD (non répétitif) :
                                val completeMsg = "Rappel non répétitif #${reminder.id} ('${reminder.text ?: "Lieu"}') validé et marqué TERMINÉ. Désarmement total de toutes les zones."
                                android.util.Log.i("GeofenceReceiver", completeMsg)
                                appLogger.i("REMINDER_COMPLETED", completeMsg, reminder.id)
                                geofenceManager.removeGeofence(reminder.id)
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
