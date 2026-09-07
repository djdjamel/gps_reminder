package com.remindly.ui.home


import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onNavigateToCapture: () -> Unit,
    onNavigateToDetail: (Long) -> Unit,
    onNavigateToSettings: () -> Unit = {},
    viewModel: HomeViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val currentWorkspaceEmail by viewModel.currentWorkspaceEmail.collectAsStateWithLifecycle()
    val collaborators by viewModel.collaborators.collectAsStateWithLifecycle()
    val currentUser by viewModel.currentUser.collectAsStateWithLifecycle()


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
                            text = if (currentWorkspaceEmail == null) "Mon Espace" else "Espace de $currentWorkspaceEmail",
                            style = MaterialTheme.typography.titleLarge
                        )
                        Icon(Icons.Filled.ArrowDropDown, contentDescription = "Changer d'espace")
                    }
                    
                    DropdownMenu(
                        expanded = showWorkspaceMenu,
                        onDismissRequest = { showWorkspaceMenu = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("Mon Espace (Personnel)", fontWeight = if (currentWorkspaceEmail == null) FontWeight.Bold else FontWeight.Normal) },
                            onClick = {
                                viewModel.switchToPersonalWorkspace()
                                showWorkspaceMenu = false
                            },
                            leadingIcon = { Icon(Icons.Filled.Home, null) }
                        )
                        HorizontalDivider()
                        DropdownMenuItem(
                            text = { Text("Se connecter à un espace...") },
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
                                contentDescription = "Se déconnecter (${currentUser?.email})"
                            )
                        }
                    }
                    
                    IconButton(onClick = onNavigateToSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = "Paramètres")
                    }
                }
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onNavigateToCapture,
                icon = { Icon(Icons.Filled.Add, contentDescription = "Ajouter") },
                text = { Text("Ajouter") }
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
                        "Aucun rappel pour le moment.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                contentPadding = PaddingValues(bottom = 80.dp) // pour ne pas cacher le FAB
            ) {
                uiState.sections.forEach { section ->
                    // En-tête de section
                    item(key = "header_${section.dateMillis}") {
                        SectionHeader(
                            title = section.title,
                            remainingCount = section.remainingCount
                        )
                    }

                    // Rappels de la section
                    items(
                        items = section.reminders,
                        key = { it.id }
                    ) { reminder ->
                        ReminderItem(
                            reminder = reminder,
                            onCheckedChange = { isChecked ->
                                viewModel.onReminderCompleted(reminder.id, isChecked)
                            },
                            onClick = { onNavigateToDetail(reminder.id) },
                            onMoveUp = { viewModel.moveUp(reminder) },
                            onMoveDown = { viewModel.moveDown(reminder) }
                        )
                    }
                }
            }
        }
    }

    if (showWorkspaceDialog) {
        AlertDialog(
            onDismissRequest = { showWorkspaceDialog = false },
            title = { Text("Mes Collaborateurs") },
            text = {
                Column {
                    if (collaborators.isEmpty()) {
                        Text("Aucun collaborateur enregistré.", style = MaterialTheme.typography.bodyMedium)
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
                    Text("Ajouter")
                }
            },
            dismissButton = {
                TextButton(onClick = { showWorkspaceDialog = false }) {
                    Text("Fermer")
                }
            }
        )
    }

    if (showAddCollaboratorDialog) {
        AlertDialog(
            onDismissRequest = { showAddCollaboratorDialog = false },
            title = { Text("Nouveau collaborateur") },
            text = {
                Column {
                    OutlinedTextField(
                        value = newCollaboratorName,
                        onValueChange = { newCollaboratorName = it },
                        label = { Text("Nom (ex: Maman)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = newCollaboratorEmail,
                        onValueChange = { newCollaboratorEmail = it },
                        label = { Text("Adresse E-mail") },
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
                    Text("Enregistrer")
                }
            },
            dismissButton = {
                TextButton(onClick = { showAddCollaboratorDialog = false }) {
                    Text("Annuler")
                }
            }
        )
    }
}

@Composable
fun SectionHeader(title: String, remainingCount: Int) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            Icons.Filled.CalendarToday,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            tint = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.weight(1f))
        if (remainingCount > 0) {
            Badge(containerColor = MaterialTheme.colorScheme.primaryContainer) {
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

@Composable
fun ReminderItem(
    reminder: Reminder,
    onCheckedChange: (Boolean) -> Unit,
    onClick: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit
) {
    val isCompleted = reminder.status == ReminderStatus.COMPLETED

    // Première image jointe (pour la miniature)
    val firstImage = reminder.attachments.firstOrNull { it.type == AttachmentType.IMAGE }

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
                    text = reminder.text ?: "(Sans texte)",
                    style = MaterialTheme.typography.bodyLarge,
                    textDecoration = if (isCompleted) TextDecoration.LineThrough else TextDecoration.None,
                    color = if (isCompleted) MaterialTheme.colorScheme.onSurfaceVariant
                            else MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )

                // Nom du lieu détecté ou configuré
                if (!reminder.placeLabel.isNullOrBlank()) {
                    Row(
                        modifier = Modifier.padding(top = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
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

                // Indicateurs (icônes seules, compactes)
                Row(
                    modifier = Modifier.padding(top = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Heure
                    if (reminder.triggerType == TriggerType.TIME || reminder.triggerType == TriggerType.BOTH) {
                        val timeText = reminder.triggerTimeMillis?.let {
                            SimpleDateFormat("dd/MM HH:mm", Locale.FRANCE).format(Date(it))
                        } ?: ""
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.AccessTime, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(2.dp))
                            Text(timeText, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                        }
                    }

                    // Lieu
                    if (reminder.triggerType == TriggerType.PLACE || reminder.triggerType == TriggerType.BOTH) {
                        Icon(Icons.Filled.LocationOn, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.secondary)
                    }

                    // Image
                    if (reminder.attachments.any { it.type == AttachmentType.IMAGE }) {
                        Icon(Icons.Filled.Image, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.tertiary)
                    }

                    // Auteur / Collaborateur
                    if (reminder.authorId != null) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.Person, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.secondary)
                            if (reminder.authorName != null) {
                                Spacer(Modifier.width(2.dp))
                                Text(reminder.authorName, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary)
                            }
                        }
                    }

                    // Audio
                    if (reminder.attachments.any { it.type == AttachmentType.AUDIO }) {
                        Icon(Icons.Filled.Mic, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.tertiary)
                    }
                }
            }

            // Miniature d'image (si présente)
            if (firstImage != null) {
                AsyncImage(
                    model = File(firstImage.localPath),
                    contentDescription = "Miniature",
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
                        contentDescription = "Monter",
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(onClick = onMoveDown, modifier = Modifier.size(28.dp)) {
                    Icon(
                        Icons.Filled.KeyboardArrowDown,
                        contentDescription = "Descendre",
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}
