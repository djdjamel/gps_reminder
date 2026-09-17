package com.remindly.location

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.google.android.gms.location.ActivityRecognition
import com.google.android.gms.location.ActivityTransition
import com.google.android.gms.location.ActivityTransitionRequest
import com.google.android.gms.location.DetectedActivity
import com.remindly.data.repo.ReminderRepository
import com.remindly.data.settings.VoiceAlarmSettingsRepository
import com.remindly.util.AppLogger
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class VehicleModeManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsRepository: VoiceAlarmSettingsRepository,
    private val reminderRepository: ReminderRepository,
    private val appLogger: AppLogger
) {
    private val scope = CoroutineScope(Dispatchers.IO)
    private val activityClient = ActivityRecognition.getClient(context)

    private val transitionPendingIntent: PendingIntent by lazy {
        val intent = Intent(context, ActivityTransitionReceiver::class.java)
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        PendingIntent.getBroadcast(context, REQUEST_CODE, intent, flags)
    }

    @SuppressLint("MissingPermission")
    fun startMonitoring() {
        if (!hasActivityRecognitionPermission()) {
            Log.w(TAG, "Permission ACTIVITY_RECOGNITION non accordée, surveillance des transitions ignorée")
            return
        }

        try {
            val transitions = buildActivityTransitions()
            val request = ActivityTransitionRequest(transitions)

            activityClient.requestActivityTransitionUpdates(request, transitionPendingIntent)
                .addOnSuccessListener {
                    Log.i(TAG, "Surveillance des transitions d'activité enregistrée avec succès")
                    appLogger.i(ActivityTransitionReceiver.TAG_LOG, "Surveillance automatique des transitions d'activité activée")
                }
                .addOnFailureListener { e ->
                    Log.e(TAG, "Erreur enregistrement transitions d'activité: ${e.message}", e)
                    appLogger.e(ActivityTransitionReceiver.TAG_LOG, "Erreur activation détection activité: ${e.message}")
                }
        } catch (e: Exception) {
            Log.e(TAG, "Exception startMonitoring: ${e.message}", e)
        }
    }

    fun stopMonitoring() {
        try {
            activityClient.removeActivityTransitionUpdates(transitionPendingIntent)
                .addOnSuccessListener {
                    Log.i(TAG, "Surveillance des transitions d'activité désactivée")
                }
                .addOnFailureListener { e ->
                    Log.w(TAG, "Erreur désactivation transitions: ${e.message}")
                }
        } catch (e: Exception) {
            Log.w(TAG, "Exception stopMonitoring: ${e.message}")
        }
    }

    fun onVehicleEnter() {
        scope.launch {
            try {
                val settings = settingsRepository.getSettings()
                if (!settings.autoVehicleDetection) {
                    Log.d(TAG, "autoVehicleDetection désactivé dans les paramètres")
                    return@launch
                }

                val activeReminders = reminderRepository.observePersonalActive().first()
                val hasLocationReminders = activeReminders.any {
                    (it.placeLat != null && it.placeLng != null && it.placeLat != 0.0 && it.placeLng != 0.0) ||
                            it.placeCategory != null
                }

                if (!hasLocationReminders) {
                    val msg = "🚗 Trajet en véhicule détecté mais aucun rappel de lieu actif -> Mode veille maintenu (0% batterie)"
                    Log.i(TAG, msg)
                    appLogger.i(ActivityTransitionReceiver.TAG_LOG, msg)
                    return@launch
                }

                val msg = "🚗 Trajet en véhicule détecté avec rappels de lieu actifs -> Démarrage du Pulse Conduite (40s)"
                Log.i(TAG, msg)
                appLogger.i(ActivityTransitionReceiver.TAG_LOG, msg)

                startDrivingPulseService()
            } catch (e: Exception) {
                Log.e(TAG, "Erreur onVehicleEnter: ${e.message}", e)
            }
        }
    }

    fun onVehicleExit() {
        scope.launch {
            try {
                val msg = "🛑 Sortie de véhicule détectée -> Arrêt du Pulse Conduite (retour veille 0%)"
                Log.i(TAG, msg)
                appLogger.i(ActivityTransitionReceiver.TAG_LOG, msg)
                stopDrivingPulseService()
            } catch (e: Exception) {
                Log.e(TAG, "Erreur onVehicleExit: ${e.message}", e)
            }
        }
    }

    fun onStillEnter() {
        // En cas d'arrêt prolongé (ex: garé sans transition EXIT immédiate)
        Log.d(TAG, "Immobilité détectée (STILL)")
    }

    fun pausePulseForZoneTracking() {
        try {
            val intent = Intent(context, DrivingPulseService::class.java).apply {
                action = DrivingPulseService.ACTION_PAUSE
            }
            context.startService(intent)
            Log.d(TAG, "Pulse conduite mis en pause pour suivi de zone 5s")
        } catch (_: Exception) {}
    }

    fun resumePulseAfterZoneTracking() {
        try {
            val intent = Intent(context, DrivingPulseService::class.java).apply {
                action = DrivingPulseService.ACTION_RESUME
            }
            context.startService(intent)
            Log.d(TAG, "Pulse conduite repris après sortie de zone")
        } catch (_: Exception) {}
    }

    private fun startDrivingPulseService() {
        try {
            val intent = Intent(context, DrivingPulseService::class.java).apply {
                action = DrivingPulseService.ACTION_START
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Impossible de démarrer DrivingPulseService: ${e.message}", e)
        }
    }

    private fun stopDrivingPulseService() {
        try {
            val intent = Intent(context, DrivingPulseService::class.java).apply {
                action = DrivingPulseService.ACTION_STOP
            }
            context.startService(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Impossible d'arrêter DrivingPulseService: ${e.message}", e)
        }
    }

    private fun hasActivityRecognitionPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACTIVITY_RECOGNITION
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    private fun buildActivityTransitions(): List<ActivityTransition> {
        val activities = listOf(
            DetectedActivity.IN_VEHICLE,
            DetectedActivity.WALKING,
            DetectedActivity.RUNNING,
            DetectedActivity.ON_BICYCLE,
            DetectedActivity.STILL
        )
        val transitions = mutableListOf<ActivityTransition>()
        for (activity in activities) {
            transitions.add(
                ActivityTransition.Builder()
                    .setActivityType(activity)
                    .setActivityTransition(ActivityTransition.ACTIVITY_TRANSITION_ENTER)
                    .build()
            )
            transitions.add(
                ActivityTransition.Builder()
                    .setActivityType(activity)
                    .setActivityTransition(ActivityTransition.ACTIVITY_TRANSITION_EXIT)
                    .build()
            )
        }
        return transitions
    }

    companion object {
        private const val TAG = "VehicleModeManager"
        private const val REQUEST_CODE = 9283
    }
}
