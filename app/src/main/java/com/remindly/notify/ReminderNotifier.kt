package com.remindly.notify

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import androidx.core.app.NotificationCompat
import java.io.File
import com.remindly.MainActivity
import com.remindly.domain.model.Reminder

class ReminderNotifier(private val context: Context) {

    private val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    fun showTimeReminder(reminder: Reminder) {
        showReminder(reminder, false)
    }

    fun showPlaceReminder(reminder: Reminder) {
        showReminder(reminder, true)
    }

    private fun showReminder(reminder: Reminder, isPlace: Boolean) {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            reminder.id.toInt(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Action Snooze
        val snoozeIntent = Intent(context, NotificationActionReceiver::class.java).apply {
            action = NotificationActionReceiver.ACTION_SNOOZE
            putExtra(NotificationActionReceiver.EXTRA_REMINDER_ID, reminder.id)
        }
        val snoozePendingIntent = PendingIntent.getBroadcast(
            context,
            reminder.id.toInt() * 10,
            snoozeIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Action Terminer
        val completeIntent = Intent(context, NotificationActionReceiver::class.java).apply {
            action = NotificationActionReceiver.ACTION_COMPLETE
            putExtra(NotificationActionReceiver.EXTRA_REMINDER_ID, reminder.id)
        }
        val completePendingIntent = PendingIntent.getBroadcast(
            context,
            reminder.id.toInt() * 10 + 1,
            completeIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val channelId = if (isPlace) NotificationChannels.PLACE_CHANNEL_ID else NotificationChannels.TIME_CHANNEL_ID
        val builder = NotificationCompat.Builder(context, channelId)
            // .setSmallIcon(R.mipmap.ic_launcher) // TODO: Mettre une icône vectorielle
            .setSmallIcon(if (isPlace) android.R.drawable.ic_menu_mylocation else android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle("Rappel")
            .setContentText(reminder.text ?: "Rappel sans texte")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .addAction(android.R.drawable.ic_popup_sync, "Reporter (+15m)", snoozePendingIntent)
            .addAction(android.R.drawable.checkbox_on_background, "Terminer", completePendingIntent)
            
        val imageAttachment = reminder.attachments.firstOrNull { it.type == com.remindly.domain.model.AttachmentType.IMAGE }
        val audioAttachment = reminder.attachments.firstOrNull { it.type == com.remindly.domain.model.AttachmentType.AUDIO }
        
        var textContent = reminder.text ?: "Rappel"

        if (imageAttachment != null) {
            val file = File(imageAttachment.localPath)
            if (file.exists()) {
                val options = BitmapFactory.Options().apply {
                    inJustDecodeBounds = true
                    BitmapFactory.decodeFile(file.absolutePath, this)
                    
                    var inSampleSize = 1
                    val reqWidth = 1024
                    val reqHeight = 1024
                    if (outHeight > reqHeight || outWidth > reqWidth) {
                        val halfHeight = outHeight / 2
                        val halfWidth = outWidth / 2
                        while (halfHeight / inSampleSize >= reqHeight && halfWidth / inSampleSize >= reqWidth) {
                            inSampleSize *= 2
                        }
                    }
                    this.inSampleSize = inSampleSize
                    this.inJustDecodeBounds = false
                }
                
                val bitmap = BitmapFactory.decodeFile(file.absolutePath, options)
                if (bitmap != null) {
                    builder.setStyle(
                        NotificationCompat.BigPictureStyle()
                            .bigPicture(bitmap)
                            .setSummaryText(textContent)
                    )
                }
            } else {
                textContent += " [Image]"
                builder.setStyle(NotificationCompat.BigTextStyle().bigText(textContent))
            }
        } else {
            builder.setStyle(NotificationCompat.BigTextStyle().bigText(textContent))
        }

        if (audioAttachment != null) {
            val playIntent = Intent(context, NotificationActionReceiver::class.java).apply {
                action = NotificationActionReceiver.ACTION_PLAY_AUDIO
                putExtra(NotificationActionReceiver.EXTRA_AUDIO_PATH, audioAttachment.localPath)
            }
            val playPendingIntent = PendingIntent.getBroadcast(
                context,
                reminder.id.toInt() * 10 + 2,
                playIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            builder.addAction(android.R.drawable.ic_media_play, "Écouter", playPendingIntent)
            textContent += " [Audio]"
        }

        builder.setContentText(textContent)
        notificationManager.notify(reminder.id.toInt(), builder.build())
    }

    fun cancel(reminderId: Long) {
        notificationManager.cancel(reminderId.toInt())
    }
}
