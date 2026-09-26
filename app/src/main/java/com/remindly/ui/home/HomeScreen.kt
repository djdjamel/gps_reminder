package com.remindly.ui.home

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.remindly.domain.model.AttachmentType
import com.remindly.domain.model.Reminder
import com.remindly.domain.model.ReminderStatus
import com.remindly.domain.model.TriggerType
import android.content.Intent
import android.provider.Settings
import androidx.compose.ui.platform.LocalContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import com.remindly.notify.NotificationChannels
import com.remindly.ui.theme.LocalAppStrings

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onNavigateToCapture: () -> Unit,
    onNavigateToDetail: (Long) -> Unit,
    onNavigateToSettings: () -> Unit = {},
    viewModel: HomeViewModel = hiltViewModel()
) {
    val strings = LocalAppStrings.current
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val currentWorkspaceEmail by viewModel.currentWorkspaceEmail.collectAsStateWithLifecycle()
    val collaborators by viewModel.collaborators.collectAsStateWithLifecycle()
    val currentUser by viewModel.currentUser.collectAsStateWithLifecycle()
    val diagnosticState by viewModel.diagnosticState.collectAsStateWithLifecycle()

    val context = LocalContext.current
    var showWorkspaceMenu by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    var showWorkspaceDialog by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    var showAddCollaboratorDialog by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    var newCollaboratorName by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf("") }
    var newCollaboratorEmail by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf("") }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.clickable { showWorkspaceMenu = true }.padding(4.dp)
                    ) {
                        Text(
                            text = if (currentWorkspaceEmail == null) strings.myWorkspace else strings.workspaceOf(currentWorkspaceEmail!!),
                            style = MaterialTheme.typography.titleLarge
                        )
                        Icon(Icons.Filled.ArrowDropDown, contentDescription = strings.connectToWorkspace)
                    }
                    
                    DropdownMenu(
                        expanded = showWorkspaceMenu,
                        onDismissRequest = { showWorkspaceMenu = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text(strings.personalWorkspace, fontWeight = if (currentWorkspaceEmail == null) FontWeight.Bold else FontWeight.Normal) },
                            onClick = {
                                viewModel.switchToPersonalWorkspace()
                                showWorkspaceMenu = false
                            },
                            leadingIcon = { Icon(Icons.Filled.Home, null) }
                        )
                        HorizontalDivider()
                        DropdownMenuItem(
                            text = { Text(strings.connectToWorkspace) },
                            onClick = {
                                showWorkspaceMenu = false
                                showWorkspaceDialog = true
                            },
                            leadingIcon = { Icon(Icons.Filled.PersonAdd, null) }
                        )
                    }
                },
                actions = {
                    // Bouton déconnexion (visible uniquement si connecté)
                    if (currentUser != null) {
                        IconButton(onClick = { viewModel.signOut() }) {
                            Icon(
                                Icons.Filled.AccountCircle,
                                contentDescription = "${strings.settingsLogout} (${currentUser?.email})"
                            )
                        }
                    }
                    
                    IconButton(onClick = onNavigateToSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = strings.settingsTitle)
                    }
                }
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onNavigateToCapture,
                icon = { Icon(Icons.Filled.Add, contentDescription = strings.newReminderFab) },
                text = { Text(strings.newReminderFab) }
            )
        }
    ) { paddingValues ->
        if (uiState.sections.isEmpty() && !uiState.isLoading) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Filled.Notifications,
                        contentDescription = null,
                        modifier = Modifier.size(64.dp),
                        tint = MaterialTheme.colorScheme.outlineVariant
                    )
                    Spacer(Modifier.height(16.dp))
                    Text(
                        strings.noRemindersTitle,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        strings.noRemindersSubtitle,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            LaunchedEffect(Unit) {
                viewModel.refreshLocation()
            }

            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                contentPadding = PaddingValues(bottom = 80.dp) // pour ne pas cacher le FAB
            ) {
                uiState.sections.forEach { section ->
                    // En-tête de section
                    item(key = "header_${section.dateMillis}_${section.title}") {
                        SectionHeader(
                            title = section.title,
                            remainingCount = section.remainingCount,
                            isNearbySection = section.isNearbySection
                        )
                    }

                    // Rappels de la section
                    items(
                        items = section.reminders,
                        key = { "${section.dateMillis}_${it.id}" }
                    ) { reminder ->
                        val audioAttachment = reminder.attachments.firstOrNull { it.type == AttachmentType.AUDIO }
                        val isDiagActive = diagnosticState.isRunning && diagnosticState.reminderId == reminder.id
                        ReminderItem(
                            reminder = reminder,
                            userLocation = uiState.userLocation,
                            isPlayingAudio = uiState.playingReminderId == reminder.id,
                            isDiagnosticActive = isDiagActive,
                            diagnosticDistanceM = if (isDiagActive) diagnosticState.currentDistanceM else null,
                            diagnosticAccuracyM = if (isDiagActive) diagnosticState.currentAccuracyM else null,
                            onCheckedChange = { isChecked ->
                                viewModel.onReminderCompleted(reminder.id, isChecked)
                            },
                            onClick = { onNavigateToDetail(reminder.id) },
                            onMoveUp = { viewModel.moveUp(reminder) },
                            onMoveDown = { viewModel.moveDown(reminder) },
                            onToggleAudio = {
                                if (audioAttachment != null) {
                                    viewModel.playAudio(reminder.id, audioAttachment.localPath)
                                }
                            },
                            onToggleDiagnostic = {
                                viewModel.toggleDiagnostic(reminder)
                            }
                        )
                    }
                }
            }
        }
    }

    if (showWorkspaceDialog) {
        AlertDialog(
            onDismissRequest = { showWorkspaceDialog = false },
            title = { Text(strings.collaboratorsTitle) },
            text = {
                Column {
                    if (collaborators.isEmpty()) {
                        Text(strings.noCollaborators, style = MaterialTheme.typography.bodyMedium)
                    } else {
                        LazyColumn(
                            modifier = Modifier.heightIn(max = 300.dp)
                        ) {
                            items(collaborators) { collaborator ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            viewModel.switchToCollaboratorWorkspace(collaborator.email)
                                            showWorkspaceDialog = false
                                        }
                                        .padding(vertical = 12.dp, horizontal = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(Icons.Filled.Person, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                    Spacer(Modifier.width(16.dp))
                                    Text(
                                        text = collaborator.name,
                                        style = MaterialTheme.typography.bodyLarge
                                    )
                                }
                                HorizontalDivider()
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        showWorkspaceDialog = false
                        showAddCollaboratorDialog = true
                    }
                ) {
                    Icon(Icons.Filled.Add, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(strings.newReminderFab)
                }
            },
            dismissButton = {
                TextButton(onClick = { showWorkspaceDialog = false }) {
                    Text(strings.close)
                }
            }
        )
    }

    if (showAddCollaboratorDialog) {
        AlertDialog(
            onDismissRequest = { showAddCollaboratorDialog = false },
            title = { Text(strings.newCollaboratorTitle) },
            text = {
                Column {
                    OutlinedTextField(
                        value = newCollaboratorName,
                        onValueChange = { newCollaboratorName = it },
                        label = { Text(strings.collaboratorNameLabel) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = newCollaboratorEmail,
                        onValueChange = { newCollaboratorEmail = it },
                        label = { Text(strings.collaboratorEmailLabel) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (newCollaboratorName.isNotBlank() && newCollaboratorEmail.isNotBlank()) {
                            viewModel.addCollaborator(newCollaboratorName, newCollaboratorEmail)
                            showAddCollaboratorDialog = false
                            newCollaboratorName = ""
                            newCollaboratorEmail = ""
                        }
                    }
                ) {
                    Text(strings.save)
                }
            },
            dismissButton = {
                TextButton(onClick = { showAddCollaboratorDialog = false }) {
                    Text(strings.cancel)
                }
            }
        )
    }
}

@Composable
fun SectionHeader(
    title: String,
    remainingCount: Int,
    isNearbySection: Boolean = false
) {
    val icon = if (isNearbySection) Icons.Filled.NearMe else Icons.Filled.CalendarToday
    val tint = if (isNearbySection) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            icon,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            tint = tint
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = tint
        )
        Spacer(Modifier.weight(1f))
        if (remainingCount > 0) {
            Badge(
                containerColor = if (isNearbySection) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.primaryContainer,
                contentColor = if (isNearbySection) MaterialTheme.colorScheme.onTertiaryContainer else MaterialTheme.colorScheme.onPrimaryContainer
            ) {
                Text("$remainingCount")
            }
        }
    }
    HorizontalDivider(
        modifier = Modifier.padding(horizontal = 16.dp),
        thickness = 0.5.dp,
        color = MaterialTheme.colorScheme.outlineVariant
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ReminderItem(
    reminder: Reminder,
    userLocation: android.location.Location?,
    isPlayingAudio: Boolean,
    isDiagnosticActive: Boolean = false,
    diagnosticDistanceM: Float? = null,
    diagnosticAccuracyM: Float? = null,
    onCheckedChange: (Boolean) -> Unit,
    onClick: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onToggleAudio: () -> Unit,
    onToggleDiagnostic: () -> Unit = {}
) {
    val strings = LocalAppStrings.current
    val isCompleted = reminder.status == ReminderStatus.COMPLETED

    val firstImage = reminder.attachments.firstOrNull { it.type == AttachmentType.IMAGE }
    val audioAttachment = reminder.attachments.firstOrNull { it.type == AttachmentType.AUDIO }

    val distanceText: String? = remember(reminder.placeLat, reminder.placeLng, userLocation, isDiagnosticActive, diagnosticDistanceM) {
        if (isDiagnosticActive && diagnosticDistanceM != null) {
            val d = diagnosticDistanceM
            if (d < 1000f) strings.distanceMeters(d.toInt())
            else strings.distanceKm(d / 1000f)
        } else if (userLocation != null && reminder.placeLat != null && reminder.placeLng != null) {
            val results = FloatArray(1)
            android.location.Location.distanceBetween(
                userLocation.latitude, userLocation.longitude,
                reminder.placeLat, reminder.placeLng,
                results
            )
            val d = results[0]
            if (d < 1000f) strings.distanceMeters(d.toInt())
            else strings.distanceKm(d / 1000f)
        } else null
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 3.dp)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 4.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Checkbox
            Checkbox(
                checked = isCompleted,
                onCheckedChange = onCheckedChange
            )

            // Contenu principal
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 4.dp)
            ) {
                // Texte
                Text(
                    text = reminder.text ?: strings.noTextPlaceholder,
                    style = MaterialTheme.typography.bodyLarge,
                    textDecoration = if (isCompleted) TextDecoration.LineThrough else TextDecoration.None,
                    color = if (isCompleted) MaterialTheme.colorScheme.onSurfaceVariant
                            else MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )

                // Ligne 1 : Nom du lieu détecté ou configuré + Badge de distance en temps réel
                if (!reminder.placeLabel.isNullOrBlank() || distanceText != null) {
                    Row(
                        modifier = Modifier.padding(top = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        if (!reminder.placeLabel.isNullOrBlank()) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.weight(1f, fill = false)
                            ) {
                                Icon(
                                    Icons.Filled.Place,
                                    contentDescription = null,
                                    modifier = Modifier.size(13.dp),
                                    tint = MaterialTheme.colorScheme.primary
                                )
                                Spacer(Modifier.width(2.dp))
                                Text(
                                    text = reminder.placeLabel,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    fontWeight = FontWeight.Medium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }

                        // Badge de distance en temps réel
                        if (distanceText != null) {
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.85f)
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        Icons.Filled.NearMe,
                                        contentDescription = null,
                                        modifier = Modifier.size(10.dp),
                                        tint = MaterialTheme.colorScheme.onSecondaryContainer
                                    )
                                    Spacer(Modifier.width(2.dp))
                                    Text(
                                        text = distanceText,
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 1,
                                        color = MaterialTheme.colorScheme.onSecondaryContainer
                                    )
                                }
                            }
                        }
                    }
                }

                // Ligne 2 : Badges de statut & Déclencheurs (FlowRow : pas de déformation verticale)
                FlowRow(
                    modifier = Modifier.padding(top = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    // Toggle Surveillance Intensive / Diagnostic GPS (Visible uniquement pour les rappels actifs avec lieu précis)
                    if (!isCompleted && reminder.placeLat != null && reminder.placeLng != null) {
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = if (isDiagnosticActive) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                            border = if (isDiagnosticActive) BorderStroke(1.dp, MaterialTheme.colorScheme.tertiary) else BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant),
                            modifier = Modifier.clickable { onToggleDiagnostic() }
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    Icons.Filled.Radar,
                                    contentDescription = if (isDiagnosticActive) strings.diagnosticStop else strings.diagnosticStart,
                                    modifier = Modifier.size(11.dp),
                                    tint = if (isDiagnosticActive) MaterialTheme.colorScheme.onTertiaryContainer else MaterialTheme.colorScheme.primary
                                )
                                Spacer(Modifier.width(3.dp))
                                val label = if (isDiagnosticActive) {
                                    if (diagnosticDistanceM != null) {
                                        val dStr = if (diagnosticDistanceM < 1000f) "${diagnosticDistanceM.toInt()}m" else String.format(Locale.ROOT, "%.1fkm", diagnosticDistanceM / 1000f)
                                        val acc = diagnosticAccuracyM?.toInt() ?: 0
                                        strings.diagnosticBadge(dStr, acc) + "  ✕"
                                    } else {
                                        "📡 Diagnostic 5s..."
                                    }
                                } else {
                                    "📡 " + strings.diagnosticStart
                                }
                                Text(
                                    text = label,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = if (isDiagnosticActive) FontWeight.Bold else FontWeight.Medium,
                                    maxLines = 1,
                                    color = if (isDiagnosticActive) MaterialTheme.colorScheme.onTertiaryContainer else MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }
                    // Badge Activation Différée (Option 1)
                    if (reminder.placeActiveFromMillis != null && reminder.placeActiveFromMillis > System.currentTimeMillis()) {
                        val timeText = SimpleDateFormat("dd/MM HH:mm", Locale.getDefault()).format(Date(reminder.placeActiveFromMillis))
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.85f)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    Icons.Filled.Schedule,
                                    contentDescription = null,
                                    modifier = Modifier.size(11.dp),
                                    tint = MaterialTheme.colorScheme.onTertiaryContainer
                                )
                                Spacer(Modifier.width(3.dp))
                                Text(
                                    text = strings.waitingUntilBadge(timeText),
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Medium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    color = MaterialTheme.colorScheme.onTertiaryContainer
                                )
                            }
                        }
                    }

                    // Badge Habitude / Répété
                    if (reminder.isRepeating) {
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    Icons.Filled.Repeat,
                                    contentDescription = null,
                                    modifier = Modifier.size(10.dp),
                                    tint = MaterialTheme.colorScheme.primary
                                )
                                Spacer(Modifier.width(2.dp))
                                Text(
                                    text = strings.repeatingBadge,
                                    style = MaterialTheme.typography.labelSmall,
                                    maxLines = 1,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }

                    // Heure d'échéance
                    if (reminder.triggerType == TriggerType.TIME || reminder.triggerType == TriggerType.BOTH) {
                        val timeText = reminder.triggerTimeMillis?.let {
                            SimpleDateFormat("dd/MM HH:mm", Locale.getDefault()).format(Date(it))
                        } ?: ""
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Filled.AccessTime, null, Modifier.size(11.dp), tint = MaterialTheme.colorScheme.primary)
                                Spacer(Modifier.width(3.dp))
                                Text(timeText, style = MaterialTheme.typography.labelSmall, maxLines = 1, color = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }

                    // Déclencheur combiné (Option 2 : Lieu OU Échéance)
                    val hasPlace = (!reminder.placeLabel.isNullOrBlank()) || (reminder.placeLat != null && reminder.placeLng != null) || reminder.placeCategory != null
                    if (hasPlace && reminder.triggerTimeMillis != null) {
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.7f)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Filled.Bolt, null, Modifier.size(11.dp), tint = MaterialTheme.colorScheme.onSecondaryContainer)
                                Spacer(Modifier.width(3.dp))
                                Text(strings.combinedTriggerBadge, style = MaterialTheme.typography.labelSmall, maxLines = 1, color = MaterialTheme.colorScheme.onSecondaryContainer)
                            }
                        }
                    }

                    // Lieu (si pas déjà affiché via placeLabel)
                    if ((reminder.triggerType == TriggerType.PLACE || reminder.triggerType == TriggerType.BOTH) && reminder.placeLabel.isNullOrBlank()) {
                        Icon(Icons.Filled.LocationOn, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.secondary)
                    }

                    // Image
                    if (reminder.attachments.any { it.type == AttachmentType.IMAGE } && firstImage == null) {
                        Icon(Icons.Filled.Image, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.tertiary)
                    }

                    // Auteur / Collaborateur
                    if (reminder.authorId != null && reminder.authorName != null) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.Person, null, Modifier.size(12.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.width(2.dp))
                            Text(reminder.authorName, style = MaterialTheme.typography.labelSmall, maxLines = 1, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }

            // Bouton Lecture Audio Directe
            if (audioAttachment != null) {
                IconButton(
                    onClick = onToggleAudio,
                    modifier = Modifier.size(36.dp)
                ) {
                    Surface(
                        shape = CircleShape,
                        color = if (isPlayingAudio) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.primaryContainer,
                        modifier = Modifier.size(30.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = if (isPlayingAudio) Icons.Filled.Stop else Icons.Filled.PlayArrow,
                                contentDescription = if (isPlayingAudio) strings.audioStopTooltip else strings.audioPlayTooltip,
                                modifier = Modifier.size(18.dp),
                                tint = if (isPlayingAudio) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                    }
                }
                Spacer(Modifier.width(4.dp))
            }

            // Miniature d'image (si présente)
            if (firstImage != null) {
                AsyncImage(
                    model = File(firstImage.localPath),
                    contentDescription = strings.photoChipEmpty,
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape),
                    contentScale = ContentScale.Crop
                )
                Spacer(Modifier.width(4.dp))
            }

            // Boutons ↑↓
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                IconButton(onClick = onMoveUp, modifier = Modifier.size(28.dp)) {
                    Icon(
                        Icons.Filled.KeyboardArrowUp,
                        contentDescription = strings.moveUp,
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(onClick = onMoveDown, modifier = Modifier.size(28.dp)) {
                    Icon(
                        Icons.Filled.KeyboardArrowDown,
                        contentDescription = strings.moveDown,
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}
