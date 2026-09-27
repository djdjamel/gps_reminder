package com.remindly.location

import android.annotation.SuppressLint
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.location.Location
import android.os.Build
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.google.android.gms.location.*
import com.remindly.MainActivity
import com.remindly.data.repo.ReminderRepository
import com.remindly.data.settings.VoiceAlarmSettings
import com.remindly.data.settings.VoiceAlarmSettingsRepository
import com.remindly.domain.model.AttachmentType
import com.remindly.domain.model.Reminder
import com.remindly.domain.model.ReminderStatus
import com.remindly.domain.model.TriggerType
import com.remindly.media.AudioAlarmService
import com.remindly.notify.NotificationChannels
import com.remindly.notify.ReminderNotifier
import com.remindly.time.AlarmScheduler
import com.remindly.util.AppLogger
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import java.util.Locale
import javax.inject.Inject

@AndroidEntryPoint
class DrivingPulseService : Service() {

    @Inject
    lateinit var appLogger: AppLogger

    @Inject
    lateinit var reminderRepository: ReminderRepository

    @Inject
    lateinit var settingsRepository: VoiceAlarmSettingsRepository

    @Inject
    lateinit var diagnosticTracker: DiagnosticLocationTracker

    @Inject
    lateinit var alarmScheduler: AlarmScheduler

    @Inject
    lateinit var contextEngine: ContextRelevanceEngine

    @Inject
    lateinit var geofenceManager: GeofenceManager

    @Inject
    lateinit var triggerCoordinator: TriggerCoordinator

    private val lastDistanceByReminder = mutableMapOf<String, DistanceSample>()

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var notificationManager: NotificationManager

    private var isRunning = false
    private var isPaused = false
    private var consecutiveZeroSpeedCount = 0

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            val loc = result.lastLocation ?: return
            handlePulseLocation(loc)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                if (!isRunning) {
                    startPulse()
                }
            }
            ACTION_STOP -> {
                stopPulse()
                stopSelf()
            }
            ACTION_PAUSE -> {
                pausePulse()
            }
            ACTION_RESUME -> {
                resumePulse()
            }
            else -> {
                if (!isRunning) stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    @SuppressLint("MissingPermission")
    private fun startPulse() {
        isRunning = true
        isPaused = false
        consecutiveZeroSpeedCount = 0

        val notification = buildNotification("Surveillance GPS optimisée de vos rappels")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        val locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 15_000L)
            .setMinUpdateIntervalMillis(10_000L)
            .setMaxUpdateDelayMillis(20_000L)
            .build()

        fusedLocationClient.requestLocationUpdates(
            locationRequest,
            locationCallback,
            Looper.getMainLooper()
        )

        val startMsg = "🚗 Mode Conduite : Démarrage du pulse GPS réactif (1 point toutes les 15s - Haute Précision)"
        Log.i(TAG, startMsg)
        appLogger.i(ActivityTransitionReceiver.TAG_LOG, startMsg)
    }

    private fun stopPulse() {
        if (isRunning) {
            isRunning = false
            fusedLocationClient.removeLocationUpdates(locationCallback)
            val stopMsg = "🛑 Mode Conduite : Arrêt du pulse GPS (retour veille 0%)"
            Log.i(TAG, stopMsg)
            appLogger.i(ActivityTransitionReceiver.TAG_LOG, stopMsg)
            stopForeground(STOP_FOREGROUND_REMOVE)
        }
    }

    private fun pausePulse() {
        if (isRunning && !isPaused) {
            isPaused = true
            fusedLocationClient.removeLocationUpdates(locationCallback)
            val msg = "⏸️ Mode Conduite mis en pause (relais pris par le suivi intensif 5s)"
            Log.i(TAG, msg)
            appLogger.i(ActivityTransitionReceiver.TAG_LOG, msg)
        }
    }

    @SuppressLint("MissingPermission")
    private fun resumePulse() {
        if (isRunning && isPaused) {
            isPaused = false
            consecutiveZeroSpeedCount = 0

            val locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 15_000L)
                .setMinUpdateIntervalMillis(10_000L)
                .setMaxUpdateDelayMillis(20_000L)
                .build()

            fusedLocationClient.requestLocationUpdates(
                locationRequest,
                locationCallback,
                Looper.getMainLooper()
            )

            val msg = "▶️ Mode Conduite repris après sortie de zone (Haute Précision 15s)"
            Log.i(TAG, msg)
            appLogger.i(ActivityTransitionReceiver.TAG_LOG, msg)
        }
    }

    private fun handlePulseLocation(loc: Location) {
        if (!isRunning || isPaused) return

        val accuracy = if (loc.hasAccuracy()) loc.accuracy else 0f
        val speedKmh = if (loc.hasSpeed()) (loc.speed * 3.6f).toInt() else 0
        val bearing = if (loc.hasBearing()) "${loc.bearing.toInt()}°" else "-"

        // Watchdog d'inactivité : Si vitesse < 5 km/h pendant 8 points consécutifs (~2 min), auto-arrêt
        if (speedKmh < 5) {
            consecutiveZeroSpeedCount++
            if (consecutiveZeroSpeedCount >= 8) {
                val autoStopMsg = "🛑 Mode Conduite : Arrêt automatique après 2 min d'immobilité prolongée (< 5 km/h)"
                Log.i(TAG, autoStopMsg)
                appLogger.i(ActivityTransitionReceiver.TAG_LOG, autoStopMsg)
                stopPulse()
                stopSelf()
                return
            }
        } else {
            consecutiveZeroSpeedCount = 0
        }

        serviceScope.launch {
            try {
                val activeReminders = reminderRepository.observePersonalActive().first()
                val locationReminders = activeReminders.filter {
                    it.status == com.remindly.domain.model.ReminderStatus.ACTIVE &&
                    ((it.placeLat != null && it.placeLng != null) || it.placeCategory != null)
                }

                // Si plus aucun rappel de lieu n'est actif, arrêter immédiatement le pulse et libérer le GPS
                if (locationReminders.isEmpty()) {
                    val noReminderMsg = "🛑 Mode Conduite : Aucun rappel de lieu actif restant -> Arrêt immédiat du Pulse GPS (retour veille 0%)"
                    Log.i(TAG, noReminderMsg)
                    appLogger.i(ActivityTransitionReceiver.TAG_LOG, noReminderMsg)
                    stopPulse()
                    stopSelf()
                    return@launch
                }

                val settings = settingsRepository.getSettings()
                val prefs = getSharedPreferences("geofence_tracking", Context.MODE_PRIVATE)

                var closestInfo = ""
                if (locationReminders.isNotEmpty()) {
                    var minDistance = Float.MAX_VALUE
                    var closestLabel = ""
                    val now = System.currentTimeMillis()

                    for (r in locationReminders) {
                        val detectionRadius = r.placeRadiusM ?: settings.poiDetectionRadiusM.toFloat()

                        if (r.placeCategory == null) {
                            // 1. Lieu fixe délibéré (ex: 'stade', 'Chez Brahimi')
                            val targetLat = r.placeLat
                            val targetLng = r.placeLng
                            if (targetLat != null && targetLng != null && targetLat != 0.0 && targetLng != 0.0) {
                                val results = FloatArray(1)
                                Location.distanceBetween(loc.latitude, loc.longitude, targetLat, targetLng, results)
                                val dist = results[0]
                                if (dist < minDistance) {
                                    minDistance = dist
                                    closestLabel = r.placeLabel ?: r.text ?: "Lieu fixe"
                                }

                                val sampleKey = "${r.id}_fixed"
                                val prevSample = lastDistanceByReminder[sampleKey]
                                val currentSample = DistanceSample(dist, now)
                                lastDistanceByReminder[sampleKey] = currentSample
                                val sampleList = if (prevSample != null) listOf(prevSample, currentSample) else listOf(currentSample)

                                if (dist <= detectionRadius) {
                                    if (r.placeActiveFromMillis == null || now >= r.placeActiveFromMillis) {
                                        val evaluation = contextEngine.evaluate(
                                            reminder = r,
                                            currentLocation = loc,
                                            targetLat = targetLat,
                                            targetLng = targetLng,
                                            currentDistanceM = dist,
                                            recentDistances = sampleList,
                                            overrideActivity = com.google.android.gms.location.DetectedActivity.IN_VEHICLE
                                        )
                                        if (evaluation.decision != ContextDecision.SUPPRESS) {
                                            triggerReminderFromPulse(r, dist, loc, settings, evaluation)
                                        } else {
                                            Log.d(TAG, "Déclenchement supprimé par ContextEngine: ${evaluation.reason} pour ${r.text}")
                                        }
                                    }
                                }
                            }
                        } else {
                            // 2. Rappel par Catégorie (POIs armés ex: 'ph' / pharmacies)
                            val trackedIds = prefs.getStringSet("geofences_${r.id}", emptySet()) ?: emptySet()
                            for (reqId in trackedIds) {
                                if (reqId.endsWith("_exit_zone") || reqId.endsWith("_stage_dest")) continue

                                val poiLat = prefs.getFloat("place_lat_$reqId", Float.NaN)
                                val poiLng = prefs.getFloat("place_lng_$reqId", Float.NaN)
                                val poiName = prefs.getString("place_name_$reqId", null) ?: r.placeLabel ?: "Commerce"

                                if (!poiLat.isNaN() && !poiLng.isNaN()) {
                                    val results = FloatArray(1)
                                    Location.distanceBetween(loc.latitude, loc.longitude, poiLat.toDouble(), poiLng.toDouble(), results)
                                    val dist = results[0]
                                    if (dist < minDistance) {
                                        minDistance = dist
                                        closestLabel = poiName
                                    }

                                    val sampleKey = "${r.id}_$reqId"
                                    val prevSample = lastDistanceByReminder[sampleKey]
                                    val currentSample = DistanceSample(dist, now)
                                    lastDistanceByReminder[sampleKey] = currentSample
                                    val sampleList = if (prevSample != null) listOf(prevSample, currentSample) else listOf(currentSample)

                                    if (dist <= detectionRadius) {
                                        if (r.placeActiveFromMillis == null || now >= r.placeActiveFromMillis) {
                                            val evaluation = contextEngine.evaluate(
                                                reminder = r,
                                                currentLocation = loc,
                                                targetLat = poiLat.toDouble(),
                                                targetLng = poiLng.toDouble(),
                                                currentDistanceM = dist,
                                                recentDistances = sampleList,
                                                overrideActivity = com.google.android.gms.location.DetectedActivity.IN_VEHICLE
                                            )
                                            if (evaluation.decision != ContextDecision.SUPPRESS) {
                                                triggerReminderFromPulse(
                                                    reminder = r,
                                                    distanceMeters = dist,
                                                    loc = loc,
                                                    settings = settings,
                                                    evaluation = evaluation,
                                                    poiName = poiName,
                                                    poiLat = poiLat.toDouble(),
                                                    poiLng = poiLng.toDouble(),
                                                    poiReqId = reqId
                                                )
                                                break // Éviter de déclencher 2 POIs sur le même pulse
                                            } else {
                                                Log.d(TAG, "POI '$poiName' filtré par ContextEngine: ${evaluation.reason}")
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    if (minDistance != Float.MAX_VALUE) {
                        val distFormatted = if (minDistance < 1000f) {
                            "${minDistance.toInt()}m"
                        } else {
                            String.format(Locale.ROOT, "%.1fkm", minDistance / 1000f)
                        }
                        closestInfo = " | Cible: '$closestLabel' à $distFormatted"
                    }
                }

                val logMsg = "🚗 [Pulse Conduite] ±${accuracy.toInt()}m | $speedKmh km/h | Cap: $bearing$closestInfo"
                Log.i(TAG, logMsg)
                appLogger.i("PASSIVE_LOC", logMsg)

                // Mettre à jour la notification avec la cible la plus proche si disponible
                if (closestInfo.isNotEmpty()) {
                    val notif = buildNotification("Surveillance active$closestInfo")
                    notificationManager.notify(NOTIFICATION_ID, notif)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Erreur handlePulseLocation: ${e.message}", e)
            }
        }
    }

    private fun triggerReminderFromPulse(
        reminder: Reminder,
        distanceMeters: Float,
        loc: Location,
        settings: VoiceAlarmSettings,
        evaluation: ContextEvaluation,
        poiName: String? = null,
        poiLat: Double? = null,
        poiLng: Double? = null,
        poiReqId: String? = null
    ) {
        val reminderId = reminder.id
        if (reminder.status != com.remindly.domain.model.ReminderStatus.ACTIVE) {
            Log.d(TAG, "triggerReminderFromPulse ignoré: rappel #${reminder.id} n'est pas actif (status=${reminder.status})")
            geofenceManager.removeGeofence(reminderId)
            return
        }
        val prefs = getSharedPreferences("geofence_tracking", Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val cooldownMs = settings.geofenceCooldownSeconds * 1000L

        // Double verrouillage atomique thread-safe : TriggerCoordinator + SharedPreferences
        val acquiredInCoordinator = triggerCoordinator.tryAcquireTrigger(reminderId, cooldownMs)
        val lastTrigger = prefs.getLong("last_trigger_time_${reminderId}", 0L)
        val isCooldown = !acquiredInCoordinator || ((now - lastTrigger) < cooldownMs)

        if (isCooldown) {
            Log.i(TAG, "Déclenchement Pulse Conduite ignoré pour #$reminderId : cooldown actif ou déclenchement concurrent.")
            return
        }

        prefs.edit().putLong("last_trigger_time_${reminderId}", now).apply()

        val placeName = poiName ?: reminder.placeLabel ?: reminder.text ?: "Lieu"
        val speedKmh = if (loc.hasSpeed()) (loc.speed * 3.6f) else 0f
        val speedMs = if (loc.hasSpeed() && loc.speed > 0f) loc.speed else (speedKmh / 3.6f)
        val leadTimeSec = if (speedMs > 1.5f && distanceMeters > 0f) (distanceMeters / speedMs).toInt() else null
        val leadTimeMsg = if (leadTimeSec != null) " | Lead Time: +${leadTimeSec}s (Anticipation)" else ""
        val firedDetail = " | Dist: ${distanceMeters.toInt()}m | Vitesse: ${speedKmh.toInt()} km/h$leadTimeMsg"

        val triggerMsg = "🎯 [PULSE_TRIGGERED] Déclenchement autonome par Pulse Conduite pour '$placeName'$firedDetail (Score: ${evaluation.score}pts - ${evaluation.decision})"
        Log.i(TAG, triggerMsg)
        appLogger.i("CONTEXT_SCORE", "Score: ${evaluation.score}pts [${evaluation.decision}] | ${evaluation.factors.joinToString { "${it.description} (${it.points}p)" }}", reminderId)

        val notifier = ReminderNotifier(this@DrivingPulseService)
        appLogger.success("GEOFENCE_TRIGGERED", triggerMsg, reminderId)

        notifier.showPlaceReminder(
            reminder = reminder,
            detectedPlaceName = placeName,
            distanceMeters = distanceMeters
        )

        val audioAttachment = reminder.attachments.firstOrNull { it.type == AttachmentType.AUDIO }
            val shouldStartAudioService = (evaluation.decision == ContextDecision.FULL_ALARM ||
                (evaluation.decision == ContextDecision.DISCREET_NOTIF && evaluation.score >= 45)) &&
                ((audioAttachment != null) || ((settings.readTextRemindersAloud || settings.announcePlaceByVoice) && !reminder.text.isNullOrBlank()))

            if (shouldStartAudioService) {
                try {
                    AudioAlarmService.start(
                        context = this@DrivingPulseService,
                        audioPath = audioAttachment?.localPath,
                        reminderText = reminder.text ?: "Rappel",
                        reminderId = reminderId,
                        placeName = placeName,
                        distanceMeters = distanceMeters
                    )
                    appLogger.success("NOTIFICATION_FIRED", "Alarme vocale/TTS lancée (Pulse Conduite)$firedDetail", reminderId)
                } catch (e: Exception) {
                    Log.e(TAG, "Impossible de démarrer AudioAlarmService: ${e.message}")
                    appLogger.w("NOTIFICATION_FIRED", "Repli sur notification standard : ${e.message}", reminderId)
                }
            } else {
                appLogger.success("NOTIFICATION_FIRED", "Notification affichée pour '${reminder.text}' (Pulse Conduite)$firedDetail", reminderId)
            }

            // Option 2 (Mutual Cancellation) : Le lieu s'étant déclenché, annuler l'alarme d'échéance programmée si applicable
            if (reminder.triggerType == TriggerType.BOTH || reminder.triggerTimeMillis != null) {
                alarmScheduler.cancel(reminderId)
                val cancelMsg = "Arrivée au lieu validée (Pulse Conduite) : alarme d'échéance annulée pour rappel #$reminderId"
                Log.i(TAG, cancelMsg)
                appLogger.i("MUTUAL_CANCELLATION", cancelMsg, reminderId)
            }

            // Mode standard (non répétitif) : marquer COMPLETED et désarmer toutes les zones
            if (!reminder.isRepeating) {
                val completeMsg = "Rappel non répétitif #${reminder.id} ('$placeName') validé par Pulse Conduite et marqué TERMINÉ. Désarmement total."
                Log.i(TAG, completeMsg)
                appLogger.i("REMINDER_COMPLETED", completeMsg, reminderId)
                serviceScope.launch {
                    val updated = reminder.copy(
                        placeLabel = placeName,
                        placeLat = poiLat ?: reminder.placeLat,
                        placeLng = poiLng ?: reminder.placeLng,
                        status = ReminderStatus.COMPLETED
                    )
                    reminderRepository.save(updated)
                }
                geofenceManager.removeGeofence(reminderId)
            }

        if (reminder.isRepeating) {
            val targetReminder = if (poiLat != null && poiLng != null) {
                reminder.copy(placeLabel = placeName, placeLat = poiLat, placeLng = poiLng)
            } else reminder

            // Démarrage automatique du suivi live 5s dans la zone
            diagnosticTracker.startLiveZoneTracking(this@DrivingPulseService, targetReminder)

            // Handoff immédiat : mettre le pulse en pause pour céder la place au suivi intensif 5s
            pausePulse()
        }
    }

    private fun buildNotification(contentText: String): android.app.Notification {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openPendingIntent = PendingIntent.getActivity(
            this,
            0,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, DrivingPulseService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, NotificationChannels.DRIVING_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_directions)
            .setContentTitle("🚗 Mode Conduite Actif")
            .setContentText(contentText)
            .setContentIntent(openPendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Arrêter", stopPendingIntent)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopPulse()
        serviceScope.cancel()
    }

    companion object {
        private const val TAG = "DrivingPulseService"
        private const val NOTIFICATION_ID = 8842

        const val ACTION_START = "com.remindly.action.DRIVING_PULSE_START"
        const val ACTION_STOP = "com.remindly.action.DRIVING_PULSE_STOP"
        const val ACTION_PAUSE = "com.remindly.action.DRIVING_PULSE_PAUSE"
        const val ACTION_RESUME = "com.remindly.action.DRIVING_PULSE_RESUME"
    }
}
