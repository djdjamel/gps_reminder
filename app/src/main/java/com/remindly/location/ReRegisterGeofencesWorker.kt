package com.remindly.location

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.remindly.data.repo.ReminderRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.first

@HiltWorker
class ReRegisterGeofencesWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted workerParams: WorkerParameters,
    private val reminderRepository: ReminderRepository,
    private val geofenceManager: GeofenceManager
) : CoroutineWorker(context, workerParams) {

    override suspend fun doWork(): Result {
        try {
            android.util.Log.i("GeofenceRefresh", "Réenregistrement périodique des géofences lancé")
            val activeReminders = reminderRepository.observePersonalActive().first()
            val locationReminders = activeReminders.filter { 
                it.status == com.remindly.domain.model.ReminderStatus.ACTIVE &&
                ((it.placeLat != null && it.placeLng != null && it.placeLat != 0.0 && it.placeLng != 0.0) || it.placeCategory != null) 
            }
            
            val now = System.currentTimeMillis()
            var count = 0
            for (reminder in locationReminders) {
                if (reminder.placeActiveFromMillis != null && reminder.placeActiveFromMillis > now) {
                    continue
                }
                geofenceManager.addGeofence(reminder)
                count++
            }

            android.util.Log.i("GeofenceRefresh", "$count géofences réenregistrées avec succès")
            return Result.success()
        } catch (e: Exception) {
            android.util.Log.e("GeofenceRefresh", "Erreur réenregistrement périodique géofences: ${e.message}", e)
            e.printStackTrace()
            return Result.retry()
        }
    }
}
