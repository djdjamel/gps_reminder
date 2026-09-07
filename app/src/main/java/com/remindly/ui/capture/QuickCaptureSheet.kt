package com.remindly.ui.capture

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.remindly.ui.components.VoiceRecorderWidget
import java.text.SimpleDateFormat
import java.util.*
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun QuickCaptureSheet(
    onDismissRequest: () -> Unit,
    onNavigateToPlacePicker: () -> Unit = {},
    viewModel: CaptureViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val coroutineScope = rememberCoroutineScope()
    val context = LocalContext.current
    val focusRequester = remember { FocusRequester() }

    var isDismissing by remember { mutableStateOf(false) }

    val safeDismiss: () -> Unit = {
        if (!isDismissing) {
            isDismissing = true
            coroutineScope.launch {
                try {
                    sheetState.hide()
                } catch (e: Exception) {
                    // Ignorer les annulations d'animation
                } finally {
                    onDismissRequest()
                }
            }
        }
    }

    // Launcher pour la galerie
    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia(),
        onResult = { uri: Uri? ->
            if (uri != null) {
                viewModel.addImage(uri)
            }
        }
    )

    ModalBottomSheet(
        onDismissRequest = {
            if (!isDismissing) {
                isDismissing = true
                onDismissRequest()
            }
        },
        sheetState = sheetState
    ) {
        if (uiState.errorMessage != null) {
            AlertDialog(
                onDismissRequest = { viewModel.clearError() },
                title = { Text("Erreur de synchronisation") },
                text = { Text(uiState.errorMessage!!) },
                confirmButton = {
                    Button(onClick = { viewModel.clearError() }) {
                        Text("Compris")
                    }
                }
            )
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            // Champ de texte
            OutlinedTextField(
                value = uiState.text,
                onValueChange = viewModel::updateText,
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester),
                placeholder = { Text("Se rappeler de…") },
                singleLine = false,
                maxLines = 5
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
                } else "Ajouter une heure"

                FilterChip(
                    selected = uiState.triggerTimeMillis != null,
                    onClick = {
                        val calendar = Calendar.getInstance()
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
                val hasPlace = uiState.placeLat != null || uiState.placeCategory != null
                val placeLabel = uiState.placeLabel ?: "Ajouter un lieu"
                FilterChip(
                    selected = hasPlace,
                    onClick = { onNavigateToPlacePicker() },
                    label = { Text(placeLabel) },
                    leadingIcon = {
                        val icon = if (uiState.placeCategory != null) Icons.Filled.ShoppingCart else Icons.Filled.LocationOn
                        Icon(icon, null, Modifier.size(18.dp))
                    },
                    trailingIcon = if (hasPlace) {
                        {
                            Icon(
                                Icons.Filled.Close, "Supprimer le lieu",
                                modifier = Modifier.size(18.dp).clickable { viewModel.clearPlace() }
                            )
                        }
                    } else null
                )

                // Chip Image
                FilterChip(
                    selected = uiState.imageUris.isNotEmpty(),
                    onClick = {
                        photoPickerLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    },
                    label = { Text(if (uiState.imageUris.isEmpty()) "Image" else "${uiState.imageUris.size} image(s)") },
                    leadingIcon = { Icon(Icons.Filled.Image, null, Modifier.size(18.dp)) }
                )
            }

            // ── Aperçu des images sélectionnées ──
            if (uiState.imageUris.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(uiState.imageUris) { uri ->
                        Box {
                            AsyncImage(
                                model = uri,
                                contentDescription = "Image jointe",
                                modifier = Modifier
                                    .size(72.dp)
                                    .clip(RoundedCornerShape(8.dp)),
                                contentScale = ContentScale.Crop
                            )
                            IconButton(
                                onClick = { viewModel.removeImage(uri) },
                                modifier = Modifier.align(Alignment.TopEnd).size(24.dp)
                            ) {
                                Icon(
                                    Icons.Filled.Close,
                                    contentDescription = "Retirer",
                                    tint = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    }
                }
            }

            // ── Enregistrement vocal ──
            Spacer(Modifier.height(8.dp))
            VoiceRecorderWidget(
                audioPath = uiState.audioPath,
                isRecording = uiState.isRecording,
                onStartRecording = { viewModel.startRecording() },
                onStopRecording = { viewModel.stopRecording() },
                onDeleteRecording = { viewModel.deleteRecording() }
            )

            // ── Bouton Enregistrer ──
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = { viewModel.saveReminder(safeDismiss) },
                modifier = Modifier.fillMaxWidth(),
                enabled = (uiState.text.isNotBlank() || uiState.imageUris.isNotEmpty() || uiState.audioPath != null) && !uiState.isSaving
            ) {
                if (uiState.isSaving) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                } else {
                    Icon(Icons.Filled.Check, null, Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                }
                Text("Enregistrer")
            }
        }
    }

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }
}
