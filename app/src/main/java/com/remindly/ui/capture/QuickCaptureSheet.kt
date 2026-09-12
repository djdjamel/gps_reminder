package com.remindly.ui.capture

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Intent
import android.net.Uri
import android.speech.RecognizerIntent
import android.widget.Toast
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
import androidx.compose.ui.text.font.FontWeight
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
    val strings = com.remindly.ui.theme.LocalAppStrings.current

    val currentLangCode = when (strings) {
        com.remindly.ui.theme.ArabicStrings -> "ar"
        com.remindly.ui.theme.EnglishStrings -> "en"
        else -> "fr"
    }

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

    // Launcher pour la reconnaissance vocale multilingue
    val speechRecognitionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
        onResult = { result ->
            if (result.resultCode == android.app.Activity.RESULT_OK) {
                val matches = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                if (!matches.isNullOrEmpty()) {
                    viewModel.onVoiceTranscribed(matches[0], currentLangCode)
                }
            }
        }
    )

    val launchSpeechRecognition = {
        try {
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_PROMPT, strings.voiceDictationPrompt)
                val langTag = when (currentLangCode) {
                    "ar" -> "ar-DZ"
                    "en" -> "en-US"
                    else -> "fr-FR"
                }
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, langTag)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            }
            speechRecognitionLauncher.launch(intent)
        } catch (e: Exception) {
            Toast.makeText(context, strings.voiceDictationUnavailable, Toast.LENGTH_SHORT).show()
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
                title = { Text(strings.syncErrorTitle) },
                text = { Text(uiState.errorMessage!!) },
                confirmButton = {
                    Button(onClick = { viewModel.clearError() }) {
                        Text(strings.understand)
                    }
                }
            )
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            // Champ de texte avec bouton micro intégré
            OutlinedTextField(
                value = uiState.text,
                onValueChange = viewModel::updateText,
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester),
                placeholder = { Text(strings.capturePlaceholder) },
                trailingIcon = {
                    IconButton(
                        onClick = launchSpeechRecognition,
                        modifier = Modifier.padding(end = 4.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Mic,
                            contentDescription = strings.voiceDictationTooltip,
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                },
                singleLine = false,
                maxLines = 5
            )

            // Bannière de feedback de détection vocale
            if (uiState.voiceFeedbackMessage != null) {
                Spacer(Modifier.height(6.dp))
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.85f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Filled.AutoAwesome,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = uiState.voiceFeedbackMessage!!,
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(
                            onClick = { viewModel.clearVoiceFeedback() },
                            modifier = Modifier.size(20.dp)
                        ) {
                            Icon(
                                Icons.Filled.Close,
                                contentDescription = "Fermer",
                                modifier = Modifier.size(14.dp),
                                tint = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(8.dp))

            // ── Raccourcis ultra-rapides en 1 clic ──
            val savedPlaces by viewModel.savedPlaces.collectAsStateWithLifecycle()
            val settings by viewModel.settings.collectAsStateWithLifecycle()

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Filled.Bolt,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    text = strings.quickShortcutsTitle,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 6.dp)
            ) {
                // 1. Lieux favoris (Maison / Travail ou lieux enregistrés dans Room)
                if (savedPlaces.isNotEmpty()) {
                    items(savedPlaces) { place ->
                        val isSelected = uiState.placeLat == place.latitude && uiState.placeLng == place.longitude
                        FilterChip(
                            selected = isSelected,
                            onClick = {
                                if (isSelected) viewModel.clearPlace()
                                else viewModel.applySavedPlace(place)
                            },
                            label = { Text(place.name) },
                            leadingIcon = {
                                val icon = if (place.name.contains("maison", ignoreCase = true) || place.name.contains("home", ignoreCase = true) || place.name.contains("منزل", ignoreCase = true)) {
                                    Icons.Filled.Home
                                } else if (place.name.contains("travail", ignoreCase = true) || place.name.contains("work", ignoreCase = true) || place.name.contains("عمل", ignoreCase = true)) {
                                    Icons.Filled.Work
                                } else {
                                    Icons.Filled.Place
                                }
                                Icon(icon, null, Modifier.size(16.dp))
                            }
                        )
                    }
                } else {
                    // Si pas de SavedPlace Room enregistré mais commute configuré dans settings
                    if (settings.commuteStartLat != null && settings.commuteStartLng != null) {
                        val isSelected = uiState.placeLat == settings.commuteStartLat && uiState.placeLng == settings.commuteStartLng
                        item {
                            FilterChip(
                                selected = isSelected,
                                onClick = {
                                    if (isSelected) viewModel.clearPlace()
                                    else viewModel.setPlace(
                                        settings.commuteStartLat!!,
                                        settings.commuteStartLng!!,
                                        settings.commuteStartLabel ?: strings.shortcutHome,
                                        radiusM = settings.poiDetectionRadiusM.toFloat()
                                    )
                                },
                                label = { Text(settings.commuteStartLabel ?: strings.shortcutHome) },
                                leadingIcon = { Icon(Icons.Filled.Home, null, Modifier.size(16.dp)) }
                            )
                        }
                    }
                    if (settings.commuteEndLat != null && settings.commuteEndLng != null) {
                        val isSelected = uiState.placeLat == settings.commuteEndLat && uiState.placeLng == settings.commuteEndLng
                        item {
                            FilterChip(
                                selected = isSelected,
                                onClick = {
                                    if (isSelected) viewModel.clearPlace()
                                    else viewModel.setPlace(
                                        settings.commuteEndLat!!,
                                        settings.commuteEndLng!!,
                                        settings.commuteEndLabel ?: strings.shortcutWork,
                                        radiusM = settings.poiDetectionRadiusM.toFloat()
                                    )
                                },
                                label = { Text(settings.commuteEndLabel ?: strings.shortcutWork) },
                                leadingIcon = { Icon(Icons.Filled.Work, null, Modifier.size(16.dp)) }
                            )
                        }
                    }
                }

                // 2. Raccourcis Catégories fréquentes (Supérette, Pharmacie)
                item {
                    val isSupermarket = uiState.placeCategory == "supermarket"
                    FilterChip(
                        selected = isSupermarket,
                        onClick = {
                            if (isSupermarket) viewModel.clearPlace()
                            else viewModel.applyCategoryShortcut("supermarket", strings.shortcutSupermarket)
                        },
                        label = { Text(strings.shortcutSupermarket) },
                        leadingIcon = { Icon(Icons.Filled.ShoppingCart, null, Modifier.size(16.dp)) }
                    )
                }

                item {
                    val isPharmacy = uiState.placeCategory == "pharmacy"
                    FilterChip(
                        selected = isPharmacy,
                        onClick = {
                            if (isPharmacy) viewModel.clearPlace()
                            else viewModel.applyCategoryShortcut("pharmacy", strings.shortcutPharmacy)
                        },
                        label = { Text(strings.shortcutPharmacy) },
                        leadingIcon = { Icon(Icons.Filled.LocalPharmacy, null, Modifier.size(16.dp)) }
                    )
                }

                // 3. Raccourcis Temporels rapides (Ce soir 19h, Demain 8h30)
                item {
                    AssistChip(
                        onClick = { viewModel.applyThisEveningShortcut() },
                        label = { Text(strings.shortcutThisEvening) },
                        leadingIcon = { Icon(Icons.Filled.NightsStay, null, Modifier.size(16.dp)) }
                    )
                }

                item {
                    AssistChip(
                        onClick = { viewModel.applyTomorrowMorningShortcut() },
                        label = { Text(strings.shortcutTomorrowMorning) },
                        leadingIcon = { Icon(Icons.Filled.Alarm, null, Modifier.size(16.dp)) }
                    )
                }
            }

            Spacer(Modifier.height(6.dp))

            val hasPlace = uiState.placeLat != null || uiState.placeCategory != null

            // ── Chips de configuration ──
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                // Chip Heure
                val timeLabel = if (uiState.triggerTimeMillis != null) {
                    val sdf = SimpleDateFormat("dd/MM à HH:mm", Locale.getDefault())
                    sdf.format(Date(uiState.triggerTimeMillis!!))
                } else strings.addTimeChip

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
                                Icons.Filled.Close, strings.removeTimeTooltip,
                                modifier = Modifier.size(18.dp).clickable { viewModel.clearTriggerTime() }
                            )
                        }
                    } else null
                )

                // Chip Lieu
                val radiusSuffix = uiState.placeRadiusM?.let { r -> if (r < 1000f) " • ${r.toInt()}m" else " • ${String.format(java.util.Locale.ROOT, "%.1f", r / 1000f)}km" } ?: ""
                val activeFromSuffix = uiState.placeActiveFromMillis?.let { t ->
                    val calNow = Calendar.getInstance()
                    val calTarget = Calendar.getInstance().apply { timeInMillis = t }
                    val isSameDay = calNow.get(Calendar.YEAR) == calTarget.get(Calendar.YEAR) &&
                            calNow.get(Calendar.DAY_OF_YEAR) == calTarget.get(Calendar.DAY_OF_YEAR)
                    val sdf = if (isSameDay) SimpleDateFormat("HH:mm", Locale.getDefault()) else SimpleDateFormat("dd/MM HH:mm", Locale.getDefault())
                    " • Dès ${sdf.format(Date(t))}"
                } ?: ""
                val placeLabel = (uiState.placeLabel ?: strings.addPlaceChip) + radiusSuffix + activeFromSuffix
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
                                Icons.Filled.Close, strings.removePlaceTooltip,
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
                    label = { Text(if (uiState.imageUris.isEmpty()) strings.photoChipEmpty else strings.photoChipCount(uiState.imageUris.size)) },
                    leadingIcon = { Icon(Icons.Filled.Image, null, Modifier.size(18.dp)) }
                )
            }

            // Bannière explicative Option 2 (Déclenchement au premier événement)
            if (hasPlace && uiState.triggerTimeMillis != null) {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.65f),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Filled.Bolt,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.secondary
                        )
                        Spacer(Modifier.width(8.dp))
                        Column {
                            Text(
                                text = strings.combinedTriggerBadge,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                            Text(
                                text = strings.combinedTriggerExplanation,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                        }
                    }
                }
            }

            // ── Aperçu des images sélectionnées ──
            if (uiState.imageUris.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(uiState.imageUris) { uri ->
                        Box {
                            AsyncImage(
                                model = uri,
                                contentDescription = "Image",
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
                                    contentDescription = strings.delete,
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
                Text(strings.save)
            }
        }
    }

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }
}
