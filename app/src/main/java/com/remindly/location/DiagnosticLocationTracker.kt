package com.remindly.location

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.remindly.domain.model.Reminder
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

data class DiagnosticState(
    val isRunning: Boolean = false,
    val reminderId: Long? = null,
    val reminderText: String? = null,
    val targetLat: Double? = null,
    val targetLng: Double? = null,
    val targetRadiusM: Float? = null,
    val currentDistanceM: Float? = null,
    val currentAccuracyM: Float? = null,
    val currentSpeedKmh: Float? = null,
    val lastUpdateTimestamp: Long = 0L
)

@Singleton
class DiagnosticLocationTracker @Inject constructor() {

    private val _state = MutableStateFlow(DiagnosticState())
    val state: StateFlow<DiagnosticState> = _state.asStateFlow()

    fun startDiagnostic(context: Context, reminder: Reminder) {
        val lat = reminder.placeLat ?: return
        val lng = reminder.placeLng ?: return
        val radiusM = reminder.placeRadiusM ?: 450f
        val reminderText = reminder.text ?: reminder.placeLabel ?: "Rappel"

        _state.value = DiagnosticState(
            isRunning = true,
            reminderId = reminder.id,
            reminderText = reminderText,
            targetLat = lat,
            targetLng = lng,
            targetRadiusM = radiusM,
            lastUpdateTimestamp = System.currentTimeMillis()
        )

        val intent = Intent(context, DiagnosticLocationService::class.java).apply {
            action = DiagnosticLocationService.ACTION_START
            putExtra(DiagnosticLocationService.EXTRA_REMINDER_ID, reminder.id)
            putExtra(DiagnosticLocationService.EXTRA_REMINDER_TEXT, reminderText)
            putExtra(DiagnosticLocationService.EXTRA_TARGET_LAT, lat)
            putExtra(DiagnosticLocationService.EXTRA_TARGET_LNG, lng)
            putExtra(DiagnosticLocationService.EXTRA_TARGET_RADIUS_M, radiusM)
        }
        ContextCompat.startForegroundService(context, intent)
    }

    fun stopDiagnostic(context: Context) {
        _state.value = DiagnosticState(isRunning = false)

        val intent = Intent(context, DiagnosticLocationService::class.java).apply {
            action = DiagnosticLocationService.ACTION_STOP
        }
        try {
            context.startService(intent)
        } catch (e: Exception) {
            // Service potentiellement déjà arrêté
        }
    }

    fun updateMeasurement(distanceM: Float, accuracyM: Float, speedKmh: Float) {
        val current = _state.value
        if (current.isRunning) {
            _state.value = current.copy(
                currentDistanceM = distanceM,
                currentAccuracyM = accuracyM,
                currentSpeedKmh = speedKmh,
                lastUpdateTimestamp = System.currentTimeMillis()
            )
        }
    }

    fun isDiagnosticActiveFor(reminderId: Long): Boolean {
        return _state.value.isRunning && _state.value.reminderId == reminderId
    }
}
