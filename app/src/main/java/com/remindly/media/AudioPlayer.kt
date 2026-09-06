package com.remindly.media

import android.media.MediaPlayer
import javax.inject.Inject

class AudioPlayer @Inject constructor() {
    private var mediaPlayer: MediaPlayer? = null
    
    var onCompletionListener: (() -> Unit)? = null

    fun play(filePath: String) {
        release()
        try {
            mediaPlayer = MediaPlayer().apply {
                setDataSource(filePath)
                prepare()
                setOnCompletionListener { 
                    onCompletionListener?.invoke()
                    release()
                }
                start()
            }
        } catch (e: Exception) {
            e.printStackTrace()
            release()
        }
    }

    fun stop() {
        try {
            if (mediaPlayer?.isPlaying == true) {
                mediaPlayer?.stop()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            release()
        }
    }

    fun release() {
        mediaPlayer?.release()
        mediaPlayer = null
    }
}
