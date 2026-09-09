package com.remindly.media

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Build
import android.os.IBinder
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.remindly.data.settings.VoiceAlarmSettings
import com.remindly.data.settings.VoiceAlarmSettingsRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.util.ArrayDeque
import javax.inject.Inject
import kotlin.coroutines.resume

/**
 * ForegroundService qui joue l'enregistrement vocal de l'utilisateur ou la synthèse vocale au déclenchement d'un rappel.
 * Utilise USAGE_ALARM pour bypasser le mode "Ne pas déranger" et gère l'audio focus correctement.
 * Prend en compte une file d'attente séquentielle (FIFO) avec pause de 1.5s si plusieurs rappels se déclenchent simultanément.
 */
@AndroidEntryPoint
class AudioAlarmService : Service() {

    @Inject
    lateinit var settingsRepository: VoiceAlarmSettingsRepository

    @Inject
    lateinit var ttsManager: TtsManager

    private data class AlarmRequest(
        val reminderId: Long,
        val audioPath: String?,
        val reminderText: String,
        val placeName: String?
    )

    private val alarmQueue = ArrayDeque<AlarmRequest>()
    private var isProcessingQueue = false

    private var mediaPlayer: MediaPlayer? = null
    private lateinit var audioManager: AudioManager
    private var audioFocusRequest: AudioFocusRequest? = null
    private var vibrator: Vibrator? = null

    private val serviceScope = CoroutineScope(Dispatchers.Main + Job())
    private var currentPlayCount = 0
    private var targetRepeatCount = 1

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val audioPath = intent.getStringExtra(EXTRA_AUDIO_PATH)
                val reminderText = intent.getStringExtra(EXTRA_REMINDER_TEXT) ?: "Rappel"
                val reminderId = intent.getLongExtra(EXTRA_REMINDER_ID, -1L)
                val placeName = intent.getStringExtra(EXTRA_PLACE_NAME)

                if (audioPath == null && reminderText.isBlank()) {
                    if (!isProcessingQueue && alarmQueue.isEmpty()) {
                        stopSelf()
                    }
                    return START_NOT_STICKY
                }

                val request = AlarmRequest(
                    reminderId = reminderId,
                    audioPath = audioPath,
                    reminderText = reminderText,
                    placeName = placeName
                )

                alarmQueue.add(request)
                showForegroundNotification(reminderId, reminderText, placeName)

                processQueueIfNeeded()
            }
            ACTION_STOP -> {
                alarmQueue.clear()
                ttsManager.stop()
                mediaPlayer?.apply {
                    if (isPlaying) stop()
                    release()
                }
                mediaPlayer = null
                stopVibration()
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private fun processQueueIfNeeded() {
        if (isProcessingQueue) return
        isProcessingQueue = true

        serviceScope.launch {
            try {
                while (alarmQueue.isNotEmpty()) {
                    val currentReq = alarmQueue.removeFirst()
                    val settings = settingsRepository.getSettings()

                    showForegroundNotification(
                        currentReq.reminderId,
                        currentReq.reminderText,
                        currentReq.placeName
                    )

                    if (settings.vibrate) {
                        startVibration()
                    }

                    val shouldAnnouncePlace = settings.announcePlaceByVoice && !currentReq.placeName.isNullOrBlank()
                    val shouldReadText = settings.readTextRemindersAloud && currentReq.audioPath == null

                    if (shouldAnnouncePlace || shouldReadText) {
                        val ttsPhrase = when {
                            currentReq.audioPath != null && shouldAnnouncePlace -> "Rappel à proximité de ${currentReq.placeName}."
                            currentReq.audioPath == null && shouldAnnouncePlace -> "Rappel : ${currentReq.reminderText}, à proximité de ${currentReq.placeName}."
                            else -> "Rappel : ${currentReq.reminderText}."
                        }

                        requestAudioFocus()
                        ttsManager.speakAwait(ttsPhrase, volume = settings.volume)
                    }

                    if (currentReq.audioPath != null) {
                        playAudioAwait(currentReq.audioPath, settings)
                    }

                    stopVibration()

                    // S'il reste d'autres rappels dans la file (ex: deux magasins simultanés), insérer un délai de respiration de 1.5s
                    if (alarmQueue.isNotEmpty()) {
                        delay(1500L)
                    }
                }
            } finally {
                isProcessingQueue = false
                stopSelf()
            }
        }
    }

    // ─── Notification foreground ────────────────────────────────────────────────

    private fun showForegroundNotification(reminderId: Long, reminderText: String, placeName: String? = null) {
        val stopIntent = Intent(this, AudioAlarmService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            0,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle("🔔 Rappel vocal")
            .setContentText(reminderText)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .addAction(android.R.drawable.ic_media_pause, "Arrêter", stopPendingIntent)

        if (!placeName.isNullOrBlank()) {
            builder.setSubText("📍 $placeName")
            builder.setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText("$reminderText\n📍 Détecté à : $placeName")
            )
        }

        val notification = builder.build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    // ─── Lecture audio suspendue ────────────────────────────────────────────────

    private suspend fun playAudioAwait(filePath: String, settings: VoiceAlarmSettings) = withContext(Dispatchers.Main) {
        val file = File(filePath)
        if (!file.exists()) return@withContext

        suspendCancellableCoroutine { continuation ->
            try {
                requestAudioFocus()

                val alarmAudioAttributes = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)          // Bypasse "Ne pas déranger"
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()

                targetRepeatCount = settings.repeatCount
                currentPlayCount = 1

                mediaPlayer?.release()
                mediaPlayer = MediaPlayer().apply {
                    setAudioAttributes(alarmAudioAttributes)
                    setDataSource(filePath)
                    prepare()
                    setVolume(settings.volume, settings.volume)

                    if (targetRepeatCount == VoiceAlarmSettings.REPEAT_LOOP) {
                        isLooping = true
                    } else {
                        isLooping = false
                        setOnCompletionListener { mp ->
                            if (currentPlayCount < targetRepeatCount) {
                                currentPlayCount++
                                try {
                                    mp.seekTo(0)
                                    mp.start()
                                } catch (e: Exception) {
                                    if (continuation.isActive) continuation.resume(Unit)
                                }
                            } else {
                                if (continuation.isActive) continuation.resume(Unit)
                            }
                        }
                    }
                    setOnErrorListener { _, _, _ ->
                        if (continuation.isActive) continuation.resume(Unit)
                        true
                    }
                    start()
                }

                continuation.invokeOnCancellation {
                    mediaPlayer?.apply {
                        if (isPlaying) stop()
                        release()
                    }
                    mediaPlayer = null
                }
            } catch (e: Exception) {
                e.printStackTrace()
                if (continuation.isActive) continuation.resume(Unit)
            }
        }
    }

    private fun requestAudioFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val alarmAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .build()
            audioFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(alarmAttributes)
                .setOnAudioFocusChangeListener { /* le rappel vocal est prioritaire */ }
                .build()
            audioManager.requestAudioFocus(audioFocusRequest!!)
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(
                null,
                AudioManager.STREAM_ALARM,
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT
            )
        }
    }

    private fun startVibration() {
        try {
            vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vibratorManager = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                vibratorManager?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }

            val pattern = longArrayOf(0, 600, 400) // 0ms attente, 600ms vibration, 400ms pause
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val effect = VibrationEffect.createWaveform(pattern, 0) // 0 = répéter depuis index 0
                vibrator?.vibrate(effect)
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(pattern, 0)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun stopVibration() {
        try {
            vibrator?.cancel()
            vibrator = null
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    // ─── Nettoyage ──────────────────────────────────────────────────────────────

    override fun onDestroy() {
        serviceScope.cancel()
        ttsManager.stop()
        stopVibration()

        // Libérer l'audio focus
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(null)
        }

        // Libérer MediaPlayer
        mediaPlayer?.apply {
            if (isPlaying) stop()
            release()
        }
        mediaPlayer = null

        super.onDestroy()
    }

    // ─── Canal de notification ──────────────────────────────────────────────────

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Rappels vocaux",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Lecture de vos enregistrements vocaux au moment du rappel"
                setSound(null, null) // Pas de son système — MediaPlayer joue directement
                enableVibration(true)
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    // ─── Constantes ─────────────────────────────────────────────────────────────

    companion object {
        const val ACTION_START = "com.remindly.action.AUDIO_ALARM_START"
        const val ACTION_STOP  = "com.remindly.action.AUDIO_ALARM_STOP"
        const val EXTRA_AUDIO_PATH    = "extra_audio_path"
        const val EXTRA_REMINDER_TEXT = "extra_reminder_text"
        const val EXTRA_REMINDER_ID   = "extra_reminder_id"
        const val EXTRA_PLACE_NAME    = "extra_place_name"
        const val CHANNEL_ID          = "audio_alarm_channel"
        const val NOTIFICATION_ID     = 9999

        /** Démarre le service de lecture audio / TTS */
        fun start(
            context: Context,
            audioPath: String? = null,
            reminderText: String,
            reminderId: Long,
            placeName: String? = null
        ) {
            val intent = Intent(context, AudioAlarmService::class.java).apply {
                action = ACTION_START
                if (audioPath != null) {
                    putExtra(EXTRA_AUDIO_PATH, audioPath)
                }
                putExtra(EXTRA_REMINDER_TEXT, reminderText)
                putExtra(EXTRA_REMINDER_ID, reminderId)
                if (placeName != null) {
                    putExtra(EXTRA_PLACE_NAME, placeName)
                }
            }
            context.startForegroundService(intent)
        }

        /** Arrête le service */
        fun stop(context: Context) {
            context.startService(Intent(context, AudioAlarmService::class.java).apply {
                action = ACTION_STOP
            })
        }
    }
}
