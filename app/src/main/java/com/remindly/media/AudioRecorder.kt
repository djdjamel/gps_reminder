package com.remindly.media

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import java.io.File
import javax.inject.Inject

class AudioRecorder @Inject constructor(
    private val context: Context
) {
    private var mediaRecorder: MediaRecorder? = null
    private var currentOutputFile: File? = null

    fun startRecording(): String? {
        try {
            val outputDir = File(context.cacheDir, "audio_temp").apply { mkdirs() }
            currentOutputFile = File(outputDir, "temp_record.m4a")

            mediaRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(context)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }.apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setOutputFile(currentOutputFile!!.absolutePath)
                prepare()
                start()
            }
            return currentOutputFile!!.absolutePath
        } catch (e: Exception) {
            e.printStackTrace()
            release()
            return null
        }
    }

    fun stopRecording() {
        try {
            mediaRecorder?.stop()
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            release()
        }
    }

    private fun release() {
        mediaRecorder?.release()
        mediaRecorder = null
    }
}
