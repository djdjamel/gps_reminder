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
            val activeReminders = reminderRepository.observePersonalActive().first()
            val locationReminders = activeReminders.filter { 
                (it.placeLat != null && it.placeLng != null) || it.placeCategory != null 
            }
            
            val now = System.currentTimeMillis()
            for (reminder in locationReminders) {
                if (reminder.placeActiveFromMillis != null && reminder.placeActiveFromMillis > now) {
                    continue
                }
                geofenceManager.addGeofence(reminder)
            }

            return Result.success()
        } catch (e: Exception) {
            e.printStackTrace()
            return Result.retry()
        }
    }
}
