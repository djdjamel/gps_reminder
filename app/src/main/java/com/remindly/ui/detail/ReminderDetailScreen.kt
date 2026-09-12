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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.remindly.domain.model.AttachmentType
import com.remindly.ui.components.VoiceRecorderWidget
import com.remindly.ui.theme.LocalAppStrings
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
    val strings = LocalAppStrings.current
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia(),
        onResult = { uri -> if (uri != null) viewModel.addImage(uri) }
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(strings.detailTitle) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = strings.back)
                    }
                },
                actions = {
                    if (uiState.reminder != null) {
                        IconButton(onClick = { viewModel.delete(onNavigateBack) }) {
                            Icon(Icons.Filled.Delete, contentDescription = strings.delete)
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
                        Text(strings.sentBy(authorName), color = MaterialTheme.colorScheme.secondary, style = MaterialTheme.typography.bodyMedium)
                    }
                }

                // Champ texte
                OutlinedTextField(
                    value = uiState.text,
                    onValueChange = viewModel::updateText,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(strings.reminderTextLabel) },
                    singleLine = false,
                    maxLines = 10
                )

                Spacer(Modifier.height(12.dp))

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
                    } else strings.setTime

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
                    val placeLabel = (uiState.placeLabel ?: strings.setPlace) + radiusSuffix + activeFromSuffix
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

                    // Chip Ajouter image
                    FilterChip(
                        selected = false,
                        onClick = {
                            photoPickerLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                        },
                        label = { Text(strings.addImage) },
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
                            .padding(top = 8.dp)
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

                // ── Fréquence du rappel (Lieu : Une seule fois vs Habitude) ──
                if (hasPlace) {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = strings.recurrenceSectionTitle,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.height(6.dp))

                    Card(
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(8.dp)) {
                            // Option 1 : Une seule fois
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { viewModel.setIsRepeating(false) }
                                    .padding(vertical = 6.dp, horizontal = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(
                                    selected = !uiState.isRepeating,
                                    onClick = { viewModel.setIsRepeating(false) }
                                )
                                Spacer(Modifier.width(8.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = strings.recurrenceOnce,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = if (!uiState.isRepeating) FontWeight.Bold else FontWeight.Normal
                                    )
                                    Text(
                                        text = strings.recurrenceOnceSubtitle,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }

                            HorizontalDivider(
                                modifier = Modifier.padding(horizontal = 8.dp),
                                thickness = 0.5.dp,
                                color = MaterialTheme.colorScheme.outlineVariant
                            )

                            // Option 2 : À chaque passage (Habitude)
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { viewModel.setIsRepeating(true) }
                                    .padding(vertical = 6.dp, horizontal = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(
                                    selected = uiState.isRepeating,
                                    onClick = { viewModel.setIsRepeating(true) }
                                )
                                Spacer(Modifier.width(8.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            text = strings.recurrenceHabit,
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = if (uiState.isRepeating) FontWeight.Bold else FontWeight.Normal
                                        )
                                        Spacer(Modifier.width(6.dp))
                                        Icon(
                                            Icons.Filled.Repeat,
                                            contentDescription = null,
                                            modifier = Modifier.size(16.dp),
                                            tint = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                    Text(
                                        text = strings.recurrenceHabitSubtitle,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }

                // ── Images jointes ──
                val images = uiState.reminder?.attachments?.filter { it.type == AttachmentType.IMAGE } ?: emptyList()
                if (images.isNotEmpty()) {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        strings.attachedImages,
                        style = MaterialTheme.typography.titleSmall
                    )
                    Spacer(Modifier.height(4.dp))
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(images) { attachment ->
                            Box {
                                AsyncImage(
                                    model = File(attachment.localPath),
                                    contentDescription = strings.photoChipEmpty,
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
                                        contentDescription = strings.delete,
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
                
                Text(strings.voiceNote, style = MaterialTheme.typography.titleSmall)
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
                        Text(strings.completeReminder)
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
                        Text(strings.save)
                    }
                }
            }
        }
    }
}
