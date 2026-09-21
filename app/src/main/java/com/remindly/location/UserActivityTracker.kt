package com.remindly.location

import com.google.android.gms.location.ActivityTransition
import com.google.android.gms.location.DetectedActivity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.ArrayDeque
import javax.inject.Inject
import javax.inject.Singleton

data class ActivityTransitionRecord(
    val activityType: Int,
    val transitionType: Int,
    val timestamp: Long = System.currentTimeMillis()
)

@Singleton
class UserActivityTracker @Inject constructor() {

    private val _currentActivity = MutableStateFlow(DetectedActivity.UNKNOWN)
    val currentActivity: StateFlow<Int> = _currentActivity.asStateFlow()

    private val history = ArrayDeque<ActivityTransitionRecord>()
    private val maxHistorySize = 10

    @Synchronized
    fun recordTransition(activityType: Int, transitionType: Int, timestamp: Long = System.currentTimeMillis()) {
        if (transitionType == ActivityTransition.ACTIVITY_TRANSITION_ENTER) {
            _currentActivity.value = activityType
        }
        val record = ActivityTransitionRecord(activityType, transitionType, timestamp)
        history.addFirst(record)
        while (history.size > maxHistorySize) {
            history.removeLast()
        }
    }

    @Synchronized
    fun isPostDrivingArrival(windowMillis: Long = 5 * 60 * 1000L, now: Long = System.currentTimeMillis()): Boolean {
        val current = _currentActivity.value
        val isPedestrianOrStill = current == DetectedActivity.WALKING ||
                current == DetectedActivity.ON_FOOT ||
                current == DetectedActivity.STILL

        if (!isPedestrianOrStill) return false

        // Vérifier si un événement IN_VEHICLE a eu lieu dans les windowMillis précédents
        return history.any { record ->
            record.activityType == DetectedActivity.IN_VEHICLE &&
                    (now - record.timestamp) <= windowMillis
        }
    }

    @Synchronized
    fun getRecentHistory(): List<ActivityTransitionRecord> {
        return history.toList()
    }

    @Synchronized
    fun resetForTesting() {
        history.clear()
        _currentActivity.value = DetectedActivity.UNKNOWN
    }
}
