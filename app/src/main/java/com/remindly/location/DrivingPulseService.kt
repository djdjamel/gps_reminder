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
import com.remindly.notify.NotificationChannels
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

        val locationRequest = LocationRequest.Builder(Priority.PRIORITY_BALANCED_POWER_ACCURACY, 40_000L)
            .setMinUpdateIntervalMillis(25_000L)
            .setMaxUpdateDelayMillis(50_000L)
            .build()

        fusedLocationClient.requestLocationUpdates(
            locationRequest,
            locationCallback,
            Looper.getMainLooper()
        )

        val startMsg = "🚗 Mode Conduite : Démarrage du pulse GPS (1 point toutes les 40s)"
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

            val locationRequest = LocationRequest.Builder(Priority.PRIORITY_BALANCED_POWER_ACCURACY, 40_000L)
                .setMinUpdateIntervalMillis(25_000L)
                .setMaxUpdateDelayMillis(50_000L)
                .build()

            fusedLocationClient.requestLocationUpdates(
                locationRequest,
                locationCallback,
                Looper.getMainLooper()
            )

            val msg = "▶️ Mode Conduite repris après sortie de zone"
            Log.i(TAG, msg)
            appLogger.i(ActivityTransitionReceiver.TAG_LOG, msg)
        }
    }

    private fun handlePulseLocation(loc: Location) {
        if (!isRunning || isPaused) return

        val accuracy = if (loc.hasAccuracy()) loc.accuracy else 0f
        val speedKmh = if (loc.hasSpeed()) (loc.speed * 3.6f).toInt() else 0
        val bearing = if (loc.hasBearing()) "${loc.bearing.toInt()}°" else "-"

        // Watchdog d'inactivité : Si vitesse nulle pendant 15 points consécutifs (~10 min), auto-arrêt
        if (speedKmh == 0) {
            consecutiveZeroSpeedCount++
            if (consecutiveZeroSpeedCount >= 15) {
                val autoStopMsg = "🛑 Mode Conduite : Arrêt automatique après 10 min d'immobilité prolongée"
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
                    (it.placeLat != null && it.placeLng != null) || it.placeCategory != null
                }

                var closestInfo = ""
                if (locationReminders.isNotEmpty()) {
                    var minDistance = Float.MAX_VALUE
                    var closestLabel = ""
                    for (r in locationReminders) {
                        val targetLat = r.placeLat
                        val targetLng = r.placeLng
                        if (targetLat != null && targetLng != null && targetLat != 0.0 && targetLng != 0.0) {
                            val results = FloatArray(1)
                            Location.distanceBetween(loc.latitude, loc.longitude, targetLat, targetLng, results)
                            if (results[0] < minDistance) {
                                minDistance = results[0]
                                closestLabel = r.placeLabel ?: r.text ?: "Rappel"
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
