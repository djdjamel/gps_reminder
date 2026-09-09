package com.remindly.notify

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.remindly.data.repo.ReminderRepository
import com.remindly.domain.model.ReminderStatus
import com.remindly.location.GeofenceManager
import com.remindly.time.AlarmScheduler
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class NotificationActionReceiver : BroadcastReceiver() {

    @Inject
    lateinit var reminderRepository: ReminderRepository

    @Inject
    lateinit var alarmScheduler: AlarmScheduler

    @Inject
    lateinit var geofenceManager: GeofenceManager

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onReceive(context: Context, intent: Intent) {
        val reminderId = intent.getLongExtra(EXTRA_REMINDER_ID, -1L)
        if (reminderId == -1L) return

        val pendingResult = goAsync()

        scope.launch {
            try {
                when (intent.action) {
                    ACTION_COMPLETE -> {
                        reminderRepository.setStatus(reminderId, ReminderStatus.COMPLETED)
                        geofenceManager.removeGeofence(reminderId)
                        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                        notificationManager.cancel(reminderId.toInt())
                    }
                    ACTION_SNOOZE -> {
                        reminderRepository.setStatus(reminderId, ReminderStatus.SNOOZED)
                        
                        val reminder = reminderRepository.getById(reminderId)
                        if (reminder != null) {
                            val snoozeTime = System.currentTimeMillis() + 15 * 60 * 1000
                            alarmScheduler.schedule(reminder, snoozeTime)
                        }
                        
                        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                        notificationManager.cancel(reminderId.toInt())
                    }
                    ACTION_PLAY_AUDIO -> {
                        val audioPath = intent.getStringExtra(EXTRA_AUDIO_PATH)
                        if (audioPath != null) {
                            try {
                                mediaPlayer?.release()
                                mediaPlayer = android.media.MediaPlayer().apply {
                                    setDataSource(audioPath)
                                    prepare()
                                    start()
                                    setOnCompletionListener { 
                                        it.release()
                                        if (mediaPlayer == this) mediaPlayer = null
                                    }
                                }
                            } catch (e: Exception) {
                                e.printStackTrace()
                            }
                        }
                    }
                }
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        const val ACTION_SNOOZE = "com.remindly.action.SNOOZE"
        const val ACTION_COMPLETE = "com.remindly.action.COMPLETE"
        const val ACTION_PLAY_AUDIO = "com.remindly.action.PLAY_AUDIO"
        const val EXTRA_REMINDER_ID = "extra_reminder_id"
        const val EXTRA_AUDIO_PATH = "extra_audio_path"
        
        private var mediaPlayer: android.media.MediaPlayer? = null
    }
}
