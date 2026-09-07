package com.remindly.notify

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build

object NotificationChannels {
    const val TIME_CHANNEL_ID = "reminders_time"
    const val PLACE_CHANNEL_ID = "reminders_place"

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

            notificationManager.createNotificationChannel(timeChannel)
            notificationManager.createNotificationChannel(placeChannel)
        }
    }
}
