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
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.google.android.gms.location.*
import com.remindly.MainActivity
import com.remindly.R
import com.remindly.notify.NotificationChannels
import com.remindly.util.AppLogger
import dagger.hilt.android.AndroidEntryPoint
import java.util.Locale
import javax.inject.Inject

@AndroidEntryPoint
class DiagnosticLocationService : Service() {

    @Inject
    lateinit var tracker: DiagnosticLocationTracker

    @Inject
    lateinit var appLogger: AppLogger

    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var notificationManager: NotificationManager

    private var reminderId: Long = -1L
    private var reminderText: String = "Rappel"
    private var targetLat: Double = 0.0
    private var targetLng: Double = 0.0
    private var targetRadiusM: Float = 450f

    private var isTracking = false

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            val loc = result.lastLocation ?: return
            handleLocationUpdate(loc)
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
                reminderId = intent.getLongExtra(EXTRA_REMINDER_ID, -1L)
                reminderText = intent.getStringExtra(EXTRA_REMINDER_TEXT) ?: "Rappel"
                targetLat = intent.getDoubleExtra(EXTRA_TARGET_LAT, 0.0)
                targetLng = intent.getDoubleExtra(EXTRA_TARGET_LNG, 0.0)
                targetRadiusM = intent.getFloatExtra(EXTRA_TARGET_RADIUS_M, 450f)

                if (reminderId != -1L && targetLat != 0.0 && targetLng != 0.0) {
                    startTracking()
                } else {
                    stopSelf()
                }
            }
            ACTION_STOP -> {
                stopTracking()
                stopSelf()
            }
            else -> {
                if (!isTracking) stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    @SuppressLint("MissingPermission")
    private fun startTracking() {
        isTracking = true

        val initialNotification = buildNotification("Connexion au signal GPS...", 0f, 0f, 0f)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                initialNotification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            )
        } else {
            startForeground(NOTIFICATION_ID, initialNotification)
        }

        val startMsg = "Démarrage surveillance intensive (5s) pour rappel #$reminderId ('$reminderText') vers ($targetLat, $targetLng), rayon: ${targetRadiusM.toInt()}m"
        android.util.Log.i(TAG, startMsg)
        appLogger.i("DIAG_GPS", startMsg, reminderId)

        val locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 5000L)
            .setMinUpdateIntervalMillis(3000L)
            .setMaxUpdateDelayMillis(5000L)
            .build()

        fusedLocationClient.requestLocationUpdates(
            locationRequest,
            locationCallback,
            Looper.getMainLooper()
        )
    }

    private fun stopTracking() {
        if (isTracking) {
            isTracking = false
            fusedLocationClient.removeLocationUpdates(locationCallback)
            val stopMsg = "Arrêt de la surveillance intensive pour rappel #$reminderId"
            android.util.Log.i(TAG, stopMsg)
            appLogger.i("DIAG_GPS", stopMsg, reminderId)
            tracker.stopDiagnostic(this)
            stopForeground(STOP_FOREGROUND_REMOVE)
        }
    }

    private fun handleLocationUpdate(loc: Location) {
        if (!isTracking) return

        val results = FloatArray(1)
        Location.distanceBetween(loc.latitude, loc.longitude, targetLat, targetLng, results)
        val distanceM = results[0]
        val accuracyM = if (loc.hasAccuracy()) loc.accuracy else 0f
        val speedKmh = if (loc.hasSpeed()) loc.speed * 3.6f else 0f

        tracker.updateMeasurement(distanceM, accuracyM, speedKmh)

        val distFormatted = if (distanceM < 1000f) "${distanceM.toInt()} m" else String.format(Locale.ROOT, "%.2f km", distanceM / 1000f)
        val notifText = "Distance : $distFormatted (±${accuracyM.toInt()}m) | Vitesse : ${speedKmh.toInt()} km/h"

        notificationManager.notify(NOTIFICATION_ID, buildNotification(notifText, distanceM, accuracyM, speedKmh))

        val inRadius = distanceM <= targetRadiusM
        val statusNote = if (inRadius) " 🎯 [DANS LE RAYON ${targetRadiusM.toInt()}m - Déclenchement passif confié à GeofenceBroadcastReceiver]" else ""
        val logMsg = "Dist: ${distanceM.toInt()}m (±${accuracyM.toInt()}m)$statusNote | GPS: (${String.format(Locale.ROOT, "%.5f", loc.latitude)}, ${String.format(Locale.ROOT, "%.5f", loc.longitude)}) | Vit: ${speedKmh.toInt()} km/h"
        
        android.util.Log.d(TAG, logMsg)
        appLogger.i("DIAG_GPS", logMsg, reminderId)
    }

    private fun buildNotification(
        statusText: String,
        distanceM: Float,
        accuracyM: Float,
        speedKmh: Float
    ): android.app.Notification {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openPendingIntent = PendingIntent.getActivity(
            this,
            0,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, DiagnosticLocationService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, NotificationChannels.DIAGNOSTIC_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentTitle("📡 Surveillance active : $reminderText")
            .setContentText(statusText)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(openPendingIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Arrêter", stopPendingIntent)
            .build()
    }

    override fun onDestroy() {
        stopTracking()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "DiagnosticLocationSvc"
        private const val NOTIFICATION_ID = 4040

        const val ACTION_START = "com.remindly.action.DIAGNOSTIC_START"
        const val ACTION_STOP = "com.remindly.action.DIAGNOSTIC_STOP"

        const val EXTRA_REMINDER_ID = "extra_reminder_id"
        const val EXTRA_REMINDER_TEXT = "extra_reminder_text"
        const val EXTRA_TARGET_LAT = "extra_target_lat"
        const val EXTRA_TARGET_LNG = "extra_target_lng"
        const val EXTRA_TARGET_RADIUS_M = "extra_target_radius_m"
    }
}
