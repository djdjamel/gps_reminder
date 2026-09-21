package com.remindly.location

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.google.android.gms.location.ActivityTransition
import com.google.android.gms.location.ActivityTransitionResult
import com.google.android.gms.location.DetectedActivity
import com.remindly.util.AppLogger
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class ActivityTransitionReceiver : BroadcastReceiver() {

    @Inject
    lateinit var appLogger: AppLogger

    @Inject
    lateinit var vehicleModeManager: VehicleModeManager

    @Inject
    lateinit var userActivityTracker: UserActivityTracker

    override fun onReceive(context: Context, intent: Intent) {
        if (!ActivityTransitionResult.hasResult(intent)) return
        val result = ActivityTransitionResult.extractResult(intent) ?: return

        for (event in result.transitionEvents) {
            userActivityTracker.recordTransition(event.activityType, event.transitionType)
            val logMessage = formatTransitionLog(event.activityType, event.transitionType)
            Log.i(TAG, logMessage)
            appLogger.i(TAG_LOG, logMessage)

            // Déclenchement ou arrêt du mode conduite
            if (event.activityType == DetectedActivity.IN_VEHICLE) {
                when (event.transitionType) {
                    ActivityTransition.ACTIVITY_TRANSITION_ENTER -> {
                        vehicleModeManager.onVehicleEnter()
                    }
                    ActivityTransition.ACTIVITY_TRANSITION_EXIT -> {
                        vehicleModeManager.onVehicleExit()
                    }
                }
            } else if (event.activityType == DetectedActivity.STILL && event.transitionType == ActivityTransition.ACTIVITY_TRANSITION_ENTER) {
                vehicleModeManager.onStillEnter()
            }
        }
    }

    companion object {
        private const val TAG = "ActivityTransition"
        const val TAG_LOG = "ACTIVITY_REC"

        fun getActivityLabel(activityType: Int): Pair<String, String> {
            return when (activityType) {
                DetectedActivity.IN_VEHICLE -> "🚗" to "En véhicule (IN_VEHICLE)"
                DetectedActivity.ON_BICYCLE -> "🚲" to "À vélo (ON_BICYCLE)"
                DetectedActivity.WALKING -> "🚶" to "Marche à pied (WALKING)"
                DetectedActivity.RUNNING -> "🏃" to "Course à pied (RUNNING)"
                DetectedActivity.STILL -> "🧘" to "Immobile / À l'arrêt (STILL)"
                DetectedActivity.ON_FOOT -> "👣" to "À pied (ON_FOOT)"
                else -> "📍" to "Activité ($activityType)"
            }
        }

        fun formatTransitionLog(activityType: Int, transitionType: Int): String {
            val (icon, label) = getActivityLabel(activityType)
            val transText = when (transitionType) {
                ActivityTransition.ACTIVITY_TRANSITION_ENTER -> "ENTRÉE"
                ActivityTransition.ACTIVITY_TRANSITION_EXIT -> "SORTIE"
                else -> "TRANSITION ($transitionType)"
            }
            return "$icon Transition $transText : $label"
        }
    }
}
