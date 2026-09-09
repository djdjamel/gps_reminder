package com.remindly.media

import android.content.Context
import android.media.AudioAttributes
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

@Singleton
class TtsManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val tag = "TtsManager"
    private var tts: TextToSpeech? = null
    private var isInitialized = false

    private suspend fun ensureInitialized(): Boolean = withContext(Dispatchers.Main) {
        if (isInitialized && tts != null) return@withContext true

        suspendCancellableCoroutine { continuation ->
            tts = TextToSpeech(context) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    val result = tts?.setLanguage(Locale.getDefault())
                    if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                        tts?.setLanguage(Locale.FRENCH)
                    }

                    // Configurer les attributs audio pour le canal ALARM (bypasse "Ne pas déranger")
                    val audioAttributes = AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                    tts?.setAudioAttributes(audioAttributes)

                    isInitialized = true
                    Log.d(tag, "TextToSpeech initialisé avec succès (Langue: ${tts?.voice?.locale ?: "Default"})")
                    if (continuation.isActive) continuation.resume(true)
                } else {
                    Log.e(tag, "Échec initialisation TextToSpeech: status=$status")
                    isInitialized = false
                    if (continuation.isActive) continuation.resume(false)
                }
            }
        }
    }

    /**
     * Prononce le texte et invoque onDone à la fin de la diction.
     */
    suspend fun speak(
        text: String,
        volume: Float = 1.0f,
        onDone: (() -> Unit)? = null
    ) {
        val ready = ensureInitialized()
        if (!ready || tts == null) {
            Log.w(tag, "TTS non disponible pour prononcer : '$text'")
            onDone?.invoke()
            return
        }

        withContext(Dispatchers.Main) {
            val utteranceId = UUID.randomUUID().toString()
            val params = Bundle().apply {
                putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, volume.coerceIn(0.1f, 1.0f))
                putString(TextToSpeech.Engine.KEY_PARAM_STREAM, AudioAttributes.USAGE_ALARM.toString())
            }

            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(id: String?) {
                    Log.d(tag, "TTS onStart: $id")
                }

                override fun onDone(id: String?) {
                    Log.d(tag, "TTS onDone: $id")
                    if (id == utteranceId) {
                        onDone?.invoke()
                    }
                }

                override fun onError(id: String?) {
                    Log.e(tag, "TTS onError: $id")
                    if (id == utteranceId) {
                        onDone?.invoke()
                    }
                }
            })

            Log.i(tag, "Prononciation TTS : \"$text\"")
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, params, utteranceId)
        }
    }

    fun stop() {
        try {
            tts?.stop()
        } catch (_: Exception) {}
    }

    fun shutdown() {
        try {
            tts?.stop()
            tts?.shutdown()
            tts = null
            isInitialized = false
        } catch (_: Exception) {}
    }
}
