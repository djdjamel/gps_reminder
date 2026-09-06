package com.remindly.ui.components

import android.media.MediaPlayer
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import java.io.File

enum class RecorderState { IDLE, RECORDING, DONE }

@Composable
fun VoiceRecorderWidget(
    audioPath: String?,
    isRecording: Boolean,
    onStartRecording: () -> Unit,
    onStopRecording: () -> Unit,
    onDeleteRecording: () -> Unit,
    modifier: Modifier = Modifier
) {
    val state = when {
        isRecording -> RecorderState.RECORDING
        audioPath != null -> RecorderState.DONE
        else -> RecorderState.IDLE
    }

    // Animation timer pour l'indicateur d'enregistrement
    var elapsed by remember { mutableIntStateOf(0) }
    LaunchedEffect(isRecording) {
        elapsed = 0
        while (isRecording) {
            delay(1000)
            elapsed++
        }
    }

    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        when (state) {
            RecorderState.IDLE -> {
                FilterChip(
                    selected = false,
                    onClick = onStartRecording,
                    label = { Text("Voix") },
                    leadingIcon = { Icon(Icons.Filled.Mic, contentDescription = null, modifier = Modifier.size(18.dp)) }
                )
            }

            RecorderState.RECORDING -> {
                val animatedColor by animateColorAsState(
                    targetValue = Color.Red,
                    label = "rec_color"
                )
                Icon(
                    Icons.Filled.Mic,
                    contentDescription = null,
                    tint = animatedColor,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "Enregistrement… ${elapsed}s",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error
                )
                Spacer(Modifier.weight(1f))
                FilledTonalButton(onClick = onStopRecording) {
                    Icon(Icons.Filled.Stop, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Stop")
                }
            }

            RecorderState.DONE -> {
                var isPlaying by remember { mutableStateOf(false) }
                val player = remember { MediaPlayer() }

                DisposableEffect(Unit) {
                    onDispose { player.release() }
                }

                FilterChip(
                    selected = true,
                    onClick = {
                        if (!isPlaying && audioPath != null && File(audioPath).exists()) {
                            try {
                                player.reset()
                                player.setDataSource(audioPath)
                                player.prepare()
                                player.start()
                                isPlaying = true
                                player.setOnCompletionListener { isPlaying = false }
                            } catch (_: Exception) {
                                isPlaying = false
                            }
                        } else if (isPlaying) {
                            player.stop()
                            isPlaying = false
                        }
                    },
                    label = { Text(if (isPlaying) "Lecture…" else "Écouter") },
                    leadingIcon = {
                        Icon(
                            if (isPlaying) Icons.Filled.Stop else Icons.Filled.PlayArrow,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                )
                Spacer(Modifier.width(8.dp))
                IconButton(onClick = {
                    if (isPlaying) { player.stop(); isPlaying = false }
                    onDeleteRecording()
                }) {
                    Icon(Icons.Filled.Close, contentDescription = "Supprimer l'enregistrement")
                }
            }
        }
    }
}
