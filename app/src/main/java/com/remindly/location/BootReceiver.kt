package com.remindly.location

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.remindly.time.RescheduleAlarmsWorker

class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            
            // Enqueue work to reschedule alarms
            val requestTime = OneTimeWorkRequestBuilder<RescheduleAlarmsWorker>().build()
            WorkManager.getInstance(context).enqueue(requestTime)
            
            // Enqueue work to re-register geofences
            val requestLocation = OneTimeWorkRequestBuilder<ReRegisterGeofencesWorker>().build()
            WorkManager.getInstance(context).enqueue(requestLocation)
        }
    }
}
