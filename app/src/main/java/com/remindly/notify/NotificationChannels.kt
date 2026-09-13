package com.remindly.notify

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build

object NotificationChannels {
    const val TIME_CHANNEL_ID = "reminders_time"
    const val PLACE_CHANNEL_ID = "reminders_place"
    const val DIAGNOSTIC_CHANNEL_ID = "diagnostic_gps"

    fun createChannels(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            val timeChannel = NotificationChannel(
                TIME_CHANNEL_ID,
                "Rappels (Temps)",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Notifications pour les rappels programmés dans le temps."
                enableVibration(true)
                enableLights(true)
                lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
            }

            val placeChannel = NotificationChannel(
                PLACE_CHANNEL_ID,
                "Rappels (Lieu)",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Notifications pour les rappels basés sur la localisation."
                enableVibration(true)
                enableLights(true)
                lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
            }

            val diagnosticChannel = NotificationChannel(
                DIAGNOSTIC_CHANNEL_ID,
                "Diagnostic GPS en direct",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Affichage en direct de la distance et précision GPS pour diagnostic."
                enableVibration(false)
                enableLights(false)
                setShowBadge(false)
            }

            notificationManager.createNotificationChannel(timeChannel)
            notificationManager.createNotificationChannel(placeChannel)
            notificationManager.createNotificationChannel(diagnosticChannel)
        }
    }
}
