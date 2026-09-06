package com.remindly.media

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import java.io.File
import java.util.UUID

class AudioRecorderManager(private val context: Context) {

    private var recorder: MediaRecorder? = null
    private var currentFilePath: String? = null

    val isRecording: Boolean
        get() = recorder != null

    fun startRecording(): String {
        val audioDir = File(context.filesDir, "audio").apply { mkdirs() }
        val fileName = "rec_${UUID.randomUUID()}.m4a"
        val file = File(audioDir, fileName)
        currentFilePath = file.absolutePath

        recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(context)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }.apply {
            setAudioSource(MediaRecorder.AudioSource.MIC)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            setAudioEncodingBitRate(128000)
            setAudioSamplingRate(44100)
            setOutputFile(file.absolutePath)
            prepare()
            start()
        }

        return file.absolutePath
    }

    fun stopRecording(): String? {
        return try {
            recorder?.apply {
                stop()
                release()
            }
            recorder = null
            currentFilePath
        } catch (e: Exception) {
            e.printStackTrace()
            recorder?.release()
            recorder = null
            // Si l'enregistrement est trop court, le fichier peut être corrompu
            currentFilePath?.let { File(it).delete() }
            null
        }
    }

    fun cancelRecording() {
        try {
            recorder?.apply {
                stop()
                release()
            }
        } catch (_: Exception) {
            recorder?.release()
        }
        recorder = null
        currentFilePath?.let { File(it).delete() }
        currentFilePath = null
    }
}
