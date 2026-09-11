package com.remindly.time

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.remindly.domain.model.Reminder
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AlarmScheduler @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    companion object {
        const val ACTION_FIRE_ALARM = "com.remindly.action.FIRE_ALARM"
        const val ACTION_ARM_DEFERRED_GEOFENCE = "com.remindly.action.ARM_DEFERRED_GEOFENCE"
        private const val DEFERRED_OFFSET = 1_000_000
    }

    /**
     * Planifie l'alarme temporelle standard (ou échéance au plus tard).
     */
    fun schedule(reminder: Reminder, triggerTimeMillis: Long) {
        val intent = Intent(context, AlarmReceiver::class.java).apply {
            action = ACTION_FIRE_ALARM
            putExtra(AlarmReceiver.EXTRA_REMINDER_ID, reminder.id)
        }
        
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            reminder.id.toInt(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        scheduleExactOrInexact(triggerTimeMillis, pendingIntent)
    }

    /**
     * Planifie le réveil silencieux pour armer le géofence à l'heure d'activation souhaitée
     * ("Au lieu, à partir de cette heure").
     */
    fun scheduleDeferredGeofence(reminder: Reminder, activeFromMillis: Long) {
        val intent = Intent(context, AlarmReceiver::class.java).apply {
            action = ACTION_ARM_DEFERRED_GEOFENCE
            putExtra(AlarmReceiver.EXTRA_REMINDER_ID, reminder.id)
        }

        val deferredRequestCode = (reminder.id + DEFERRED_OFFSET).toInt()
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            deferredRequestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        scheduleExactOrInexact(activeFromMillis, pendingIntent)
    }

    private fun scheduleExactOrInexact(triggerTimeMillis: Long, pendingIntent: PendingIntent) {
        val canScheduleExact = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            alarmManager.canScheduleExactAlarms()
        } else {
            true
        }

        if (canScheduleExact) {
            alarmManager.setExactAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                triggerTimeMillis,
                pendingIntent
            )
        } else {
            alarmManager.setAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                triggerTimeMillis,
                pendingIntent
            )
        }
    }

    /**
     * Annule les alarmes actives (alarme principale et alarme silencieuse d'activation différée).
     */
    fun cancel(reminderId: Long) {
        // 1. Annulation alarme principale
        val mainIntent = Intent(context, AlarmReceiver::class.java).apply {
            action = ACTION_FIRE_ALARM
        }
        val mainPendingIntent = PendingIntent.getBroadcast(
            context,
            reminderId.toInt(),
            mainIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        alarmManager.cancel(mainPendingIntent)

        // 2. Annulation alarme différée géofence
        val deferredIntent = Intent(context, AlarmReceiver::class.java).apply {
            action = ACTION_ARM_DEFERRED_GEOFENCE
        }
        val deferredPendingIntent = PendingIntent.getBroadcast(
            context,
            (reminderId + DEFERRED_OFFSET).toInt(),
            deferredIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        alarmManager.cancel(deferredPendingIntent)
    }
}
