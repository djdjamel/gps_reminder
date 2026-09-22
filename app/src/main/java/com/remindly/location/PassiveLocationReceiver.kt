package com.remindly.location

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.location.Location
import com.google.android.gms.location.LocationResult
import com.remindly.data.repo.ReminderRepository
import com.remindly.util.AppLogger
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.Locale
import javax.inject.Inject

@AndroidEntryPoint
class PassiveLocationReceiver : BroadcastReceiver() {

    @Inject
    lateinit var appLogger: AppLogger

    @Inject
    lateinit var reminderRepository: ReminderRepository

    @Inject
    lateinit var vehicleModeManager: VehicleModeManager

    override fun onReceive(context: Context, intent: Intent) {
        if (!LocationResult.hasResult(intent)) return
        val locationResult = LocationResult.extractResult(intent) ?: return
        val locations = locationResult.locations
        if (locations.isNullOrEmpty()) return

        val now = System.currentTimeMillis()
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val lastTime = prefs.getLong(KEY_LAST_TIME, 0L)
        prefs.edit().putLong(KEY_LAST_TIME, now).apply()

        val deltaMs = if (lastTime > 0L) now - lastTime else 0L
        val deltaStr = formatDeltaTime(deltaMs, lastTime == 0L)
        val isBlackHole = lastTime > 0L && deltaMs > 120_000L // Plus de 2 minutes sans aucun calcul GMS
        val blackHoleWarning = if (isBlackHole) " ⚠️ [TROU NOIR GMS: $deltaStr sans calcul]" else ""

        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                // Recherche des rappels de lieu actifs pour évaluer la distance à la cible la plus proche
                val activeReminders = reminderRepository.observePersonalActive().first()
                val locationReminders = activeReminders.filter {
                    it.status == com.remindly.domain.model.ReminderStatus.ACTIVE &&
                    ((it.placeLat != null && it.placeLng != null && it.placeLat != 0.0 && it.placeLng != 0.0) || it.placeCategory != null)
                }

                for (loc in locations) {
                    val accuracy = if (loc.hasAccuracy()) loc.accuracy else 0f
                    val speedKmh = if (loc.hasSpeed()) (loc.speed * 3.6f).toInt() else 0
                    val bearing = if (loc.hasBearing()) "${loc.bearing.toInt()}°" else "-"

                    if (speedKmh >= 25 && locationReminders.isNotEmpty()) {
                        vehicleModeManager.onVehicleEnter()
                    }

                    val (sourceIcon, sourceName) = classifyLocationSource(loc.provider, accuracy)

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

                    val logMessage = "$sourceIcon $sourceName (±${accuracy.toInt()}m) | Δt: $deltaStr$blackHoleWarning | $speedKmh km/h | Cap: $bearing$closestInfo"
                    android.util.Log.i("PassiveLoc", logMessage)

                    val lastDbLogTime = prefs.getLong("last_db_log_time", 0L)
                    val shouldWriteToDb = isBlackHole || (now - lastDbLogTime >= 15_000L)

                    if (shouldWriteToDb) {
                        prefs.edit().putLong("last_db_log_time", now).apply()
                        if (isBlackHole) {
                            appLogger.w("PASSIVE_LOC", logMessage)
                        } else {
                            appLogger.i("PASSIVE_LOC", logMessage)
                        }
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("PassiveLoc", "Erreur traitement passive location: ${e.message}", e)
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        private const val PREFS_NAME = "passive_location_tracker"
        private const val KEY_LAST_TIME = "last_passive_loc_time"

        fun classifyLocationSource(provider: String?, accuracy: Float): Pair<String, String> {
            return when {
                provider == "gps" || accuracy < 15f -> "🛰️" to "GPS (Satellites)"
                accuracy in 15f..150f -> "📶" to "Wi-Fi (Triangulation)"
                else -> "🗼" to "Cellulaire (Antennes-relais)"
            }
        }

        fun formatDeltaTime(deltaMs: Long, isFirstPoint: Boolean): String {
            if (isFirstPoint) return "1er point"
            val totalSeconds = deltaMs / 1000
            val minutes = totalSeconds / 60
            val seconds = totalSeconds % 60
            return when {
                minutes == 0L -> "${seconds}s"
                else -> "${minutes}m ${seconds}s"
            }
        }
    }
}
