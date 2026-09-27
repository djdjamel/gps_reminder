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
    @Volatile
    private var isMonitoring = false

    private val transitionPendingIntent: PendingIntent by lazy {
        val intent = Intent(context, ActivityTransitionReceiver::class.java).apply {
            action = "com.remindly.action.ACTION_ACTIVITY_TRANSITION_EVENT"
        }
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
            val warnMsg = "⚠️ Permission 'Activité physique' non accordée : la détection de véhicule est en attente d'autorisation système."
            Log.w(TAG, warnMsg)
            appLogger.w(ActivityTransitionReceiver.TAG_LOG, warnMsg)
            return
        }

        if (isMonitoring) {
            Log.d(TAG, "Surveillance des transitions d'activité déjà active")
            return
        }

        try {
            val transitions = buildActivityTransitions()
            val request = ActivityTransitionRequest(transitions)

            activityClient.requestActivityTransitionUpdates(request, transitionPendingIntent)
                .addOnSuccessListener {
                    isMonitoring = true
                    Log.i(TAG, "Surveillance des transitions d'activité enregistrée avec succès")
                    appLogger.i(ActivityTransitionReceiver.TAG_LOG, "Surveillance automatique des transitions d'activité activée")
                }
                .addOnFailureListener { e ->
                    isMonitoring = false
                    Log.e(TAG, "Erreur enregistrement transitions d'activité: ${e.message}", e)
                    appLogger.e(ActivityTransitionReceiver.TAG_LOG, "Erreur activation détection activité: ${e.message}")
                }
        } catch (e: Exception) {
            isMonitoring = false
            Log.e(TAG, "Exception startMonitoring: ${e.message}", e)
        }
    }

    fun stopMonitoring() {
        isMonitoring = false
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
                    it.status == com.remindly.domain.model.ReminderStatus.ACTIVE &&
                    (((it.placeLat != null && it.placeLng != null && it.placeLat != 0.0 && it.placeLng != 0.0) ||
                            it.placeCategory != null))
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

    /**
     * Démarrage immédiat du Pulse GPS lors d'un réveil matériel (Doorbell Geofence).
     * Permet d'embrayer la haute précision (15s) dès l'entrée dans la zone de pré-réveil (WakeRadius),
     * sans avoir à attendre une détection d'Activity Recognition (qui peut être différée de plusieurs minutes).
     */
    fun startPulseForApproach(reason: String = "Zone de pré-réveil atteinte") {
        scope.launch {
            try {
                val msg = "⚡ [WAKE_PULSE] $reason -> Démarrage de l'Adaptive Location Pulse"
                Log.i(TAG, msg)
                appLogger.i(ActivityTransitionReceiver.TAG_LOG, msg)
                startDrivingPulseService()
            } catch (e: Exception) {
                Log.e(TAG, "Erreur startPulseForApproach: ${e.message}", e)
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

    fun hasActivityRecognitionPermission(): Boolean {
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
