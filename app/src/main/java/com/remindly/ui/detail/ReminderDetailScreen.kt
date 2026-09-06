package com.remindly.ui.detail

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.remindly.domain.model.AttachmentType
import com.remindly.ui.components.VoiceRecorderWidget
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ReminderDetailScreen(
    onNavigateBack: () -> Unit,
    onNavigateToPlacePicker: () -> Unit = {},
    viewModel: DetailViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia(),
        onResult = { uri -> if (uri != null) viewModel.addImage(uri) }
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Détails du rappel") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Retour")
                    }
                },
                actions = {
                    if (uiState.reminder != null) {
                        IconButton(onClick = { viewModel.delete(onNavigateBack) }) {
                            Icon(Icons.Filled.Delete, contentDescription = "Supprimer")
                        }
                    }
                }
            )
        }
    ) { paddingValues ->
        if (uiState.isLoading) {
            Box(modifier = Modifier.fillMaxSize().padding(paddingValues), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .padding(16.dp)
            ) {
                // Info Collaborateur
                val authorName = uiState.reminder?.authorName
                if (authorName != null) {
                    Row(
                        modifier = Modifier.padding(bottom = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Filled.Person, null, tint = MaterialTheme.colorScheme.secondary)
                        Spacer(Modifier.width(8.dp))
                        Text("Envoyé par : $authorName", color = MaterialTheme.colorScheme.secondary, style = MaterialTheme.typography.bodyMedium)
                    }
                }

                // Champ texte
                OutlinedTextField(
                    value = uiState.text,
                    onValueChange = viewModel::updateText,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Texte du rappel") },
                    singleLine = false,
                    maxLines = 10
                )

                Spacer(Modifier.height(12.dp))

                // ── Chips de configuration ──
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    // Chip Heure
                    val timeLabel = if (uiState.triggerTimeMillis != null) {
                        val sdf = SimpleDateFormat("dd/MM à HH:mm", Locale.FRANCE)
                        sdf.format(Date(uiState.triggerTimeMillis!!))
                    } else "Définir une heure"

                    FilterChip(
                        selected = uiState.triggerTimeMillis != null,
                        onClick = {
                            val calendar = Calendar.getInstance()
                            uiState.triggerTimeMillis?.let { calendar.timeInMillis = it }
                            DatePickerDialog(
                                context,
                                { _, year, month, dayOfMonth ->
                                    TimePickerDialog(
                                        context,
                                        { _, hourOfDay, minute ->
                                            val cal = Calendar.getInstance().apply {
                                                set(year, month, dayOfMonth, hourOfDay, minute, 0)
                                                set(Calendar.MILLISECOND, 0)
                                            }
                                            viewModel.setTriggerTime(cal.timeInMillis)
                                        },
                                        calendar.get(Calendar.HOUR_OF_DAY),
                                        calendar.get(Calendar.MINUTE),
                                        true
                                    ).show()
                                },
                                calendar.get(Calendar.YEAR),
                                calendar.get(Calendar.MONTH),
                                calendar.get(Calendar.DAY_OF_MONTH)
                            ).show()
                        },
                        label = { Text(timeLabel) },
                        leadingIcon = { Icon(Icons.Filled.AccessTime, null, Modifier.size(18.dp)) },
                        trailingIcon = if (uiState.triggerTimeMillis != null) {
                            {
                                Icon(
                                    Icons.Filled.Close, "Supprimer l'heure",
                                    modifier = Modifier.size(18.dp).clickable { viewModel.clearTriggerTime() }
                                )
                            }
                        } else null
                    )

                    // Chip Lieu
                    val placeLabel = uiState.placeLabel ?: "Définir un lieu"
                    FilterChip(
                        selected = uiState.placeLat != null,
                        onClick = { onNavigateToPlacePicker() },
                        label = { Text(placeLabel) },
                        leadingIcon = { Icon(Icons.Filled.LocationOn, null, Modifier.size(18.dp)) },
                        trailingIcon = if (uiState.placeLat != null) {
                            {
                                Icon(
                                    Icons.Filled.Close, "Supprimer le lieu",
                                    modifier = Modifier.size(18.dp).clickable { viewModel.clearPlace() }
                                )
                            }
                        } else null
                    )

                    // Chip Ajouter image
                    FilterChip(
                        selected = false,
                        onClick = {
                            photoPickerLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                        },
                        label = { Text("Ajouter image") },
                        leadingIcon = { Icon(Icons.Filled.Image, null, Modifier.size(18.dp)) }
                    )
                }

                // ── Images jointes ──
                val images = uiState.reminder?.attachments?.filter { it.type == AttachmentType.IMAGE } ?: emptyList()
                if (images.isNotEmpty()) {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "Images jointes :",
                        style = MaterialTheme.typography.titleSmall
                    )
                    Spacer(Modifier.height(4.dp))
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(images) { attachment ->
                            Box {
                                AsyncImage(
                                    model = File(attachment.localPath),
                                    contentDescription = "Image jointe",
                                    modifier = Modifier
                                        .size(100.dp)
                                        .clip(RoundedCornerShape(8.dp)),
                                    contentScale = ContentScale.Crop
                                )
                                IconButton(
                                    onClick = {
                                        viewModel.removeAttachment(attachment.id, attachment.localPath)
                                    },
                                    modifier = Modifier.align(Alignment.TopEnd).size(24.dp)
                                ) {
                                    Icon(
                                        Icons.Filled.Close,
                                        contentDescription = "Supprimer",
                                        tint = MaterialTheme.colorScheme.error,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                        }
                    }
                }

                // ── Audio ──
                Spacer(Modifier.height(12.dp))
                val audioAttachment = uiState.reminder?.attachments?.firstOrNull { it.type == AttachmentType.AUDIO }
                val audioPath = uiState.audioPath ?: audioAttachment?.localPath
                
                Text("Note vocale :", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(4.dp))
                VoiceRecorderWidget(
                    audioPath = audioPath,
                    isRecording = uiState.isRecording,
                    onStartRecording = { viewModel.startRecording() },
                    onStopRecording = { viewModel.stopRecording() },
                    onDeleteRecording = { viewModel.deleteRecording() }
                )

                // ── Boutons d'action ──
                Spacer(Modifier.weight(1f))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    OutlinedButton(
                        onClick = { viewModel.complete(onNavigateBack) },
                        modifier = Modifier.weight(1f),
                        enabled = uiState.reminder != null
                    ) {
                        Icon(Icons.Filled.Check, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Terminer")
                    }

                    Button(
                        onClick = { viewModel.save(onNavigateBack) },
                        modifier = Modifier.weight(1f),
                        enabled = uiState.text.isNotBlank() && !uiState.isSaving
                    ) {
                        if (uiState.isSaving) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(4.dp))
                        }
                        Text("Sauvegarder")
                    }
                }
            }
        }
    }
}
