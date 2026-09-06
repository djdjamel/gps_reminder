package com.remindly.ui.capture

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.maps.android.compose.*
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlacePickerScreen(
    onPlaceSelected: (LatLng) -> Unit,
    onNavigateBack: () -> Unit,
    viewModel: PlacePickerViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val savedPlaces by viewModel.savedPlaces.collectAsStateWithLifecycle()
    val currentLocation by viewModel.currentLocation.collectAsStateWithLifecycle()
    val locationSettingsResolution by viewModel.locationSettingsResolution.collectAsStateWithLifecycle()

    // Launcher pour le dialogue système "Activer la localisation"
    val gpsSettingsLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        viewModel.clearLocationSettingsResolution()
        if (result.resultCode == Activity.RESULT_OK) {
            // L'utilisateur a activé le GPS → récupérer la position
            viewModel.fetchCurrentLocation()
        }
    }

    // Déclencher le dialogue dès qu'un IntentSender est disponible
    LaunchedEffect(locationSettingsResolution) {
        locationSettingsResolution?.let { intentSender ->
            gpsSettingsLauncher.launch(
                IntentSenderRequest.Builder(intentSender).build()
            )
        }
    }

    // Paris comme fallback si la position n'est pas encore disponible
    val fallbackLocation = LatLng(48.8566, 2.3522)
    var selectedLocation by remember { mutableStateOf(fallbackLocation) }
    var selectedLocationName by remember { mutableStateOf<String?>(null) }

    var isSatellite by remember { mutableStateOf(false) }
    var showSaveDialog by remember { mutableStateOf(false) }
    var newPlaceName by remember { mutableStateOf("") }
    var isSearchActive by remember { mutableStateOf(false) }
    var hasAutocentered by remember { mutableStateOf(false) }

    val cameraPositionState = rememberCameraPositionState {
        position = CameraPosition.fromLatLngZoom(fallbackLocation, 10f)
    }

    val coroutineScope = rememberCoroutineScope()

    // Centrer automatiquement sur la position actuelle dès qu'elle arrive (une seule fois)
    LaunchedEffect(currentLocation) {
        if (!hasAutocentered && currentLocation != null) {
            hasAutocentered = true
            selectedLocation = currentLocation!!
            cameraPositionState.animate(CameraUpdateFactory.newLatLngZoom(currentLocation!!, 15f))
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Sélectionner un lieu") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Retour")
                    }
                }
            )
        }
    ) { paddingValues ->
        Box(modifier = Modifier.fillMaxSize().padding(paddingValues)) {

            // Carte Google Maps
            GoogleMap(
                modifier = Modifier.fillMaxSize(),
                cameraPositionState = cameraPositionState,
                properties = MapProperties(
                    isMyLocationEnabled = true,
                    mapType = if (isSatellite) MapType.SATELLITE else MapType.NORMAL
                ),
                uiSettings = MapUiSettings(
                    myLocationButtonEnabled = false, // On utilise notre propre bouton
                    zoomControlsEnabled = false
                ),
                onMapClick = { latLng ->
                    selectedLocation = latLng
                    selectedLocationName = null
                }
            ) {
                Marker(
                    state = MarkerState(position = selectedLocation),
                    title = selectedLocationName ?: "Lieu sélectionné",
                    snippet = "Appuyez sur Confirmer"
                )
            }

            // Barre de recherche (haut)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp)
                    .align(Alignment.TopCenter)
            ) {
                DockedSearchBar(
                    inputField = {
                        SearchBarDefaults.InputField(
                            query = uiState.searchQuery,
                            onQueryChange = viewModel::updateSearchQuery,
                            onSearch = { /* suggestions gèrent la recherche */ },
                            expanded = isSearchActive,
                            onExpandedChange = { isSearchActive = it },
                            placeholder = { Text("Rechercher un lieu...") },
                            leadingIcon = {
                                if (uiState.isSearching) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(20.dp),
                                        strokeWidth = 2.dp
                                    )
                                } else {
                                    Icon(Icons.Filled.Search, contentDescription = null)
                                }
                            },
                            trailingIcon = {
                                if (uiState.searchQuery.isNotEmpty()) {
                                    IconButton(onClick = { viewModel.updateSearchQuery("") }) {
                                        Icon(Icons.Filled.Clear, contentDescription = "Effacer")
                                    }
                                }
                            }
                        )
                    },
                    expanded = isSearchActive,
                    onExpandedChange = { isSearchActive = it },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    // Suggestions Places API
                    if (uiState.predictions.isNotEmpty()) {
                        LazyColumn(modifier = Modifier.fillMaxWidth()) {
                            items(uiState.predictions) { prediction ->
                                ListItem(
                                    headlineContent = {
                                        Text(
                                            prediction.getPrimaryText(null).toString(),
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    },
                                    supportingContent = {
                                        Text(
                                            prediction.getSecondaryText(null).toString(),
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    },
                                    leadingContent = {
                                        Icon(
                                            Icons.Filled.LocationOn,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary
                                        )
                                    },
                                    modifier = Modifier.clickable {
                                        viewModel.selectPrediction(prediction.placeId) { latLng, name ->
                                            selectedLocation = latLng
                                            selectedLocationName = name
                                            isSearchActive = false
                                            coroutineScope.launch {
                                                cameraPositionState.animate(
                                                    CameraUpdateFactory.newLatLngZoom(latLng, 15f)
                                                )
                                            }
                                        }
                                    }
                                )
                                HorizontalDivider()
                            }
                        }
                    } else if (uiState.searchQuery.isNotBlank() && !uiState.isSearching) {
                        // Aucun résultat
                        ListItem(
                            headlineContent = { Text("Aucun résultat trouvé") },
                            leadingContent = {
                                Icon(Icons.Filled.SearchOff, contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        )
                    }

                    // Lieux sauvegardés (quand la recherche est vide)
                    if (savedPlaces.isNotEmpty() && uiState.searchQuery.isBlank()) {
                        Text(
                            text = "Lieux sauvegardés",
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                        LazyColumn(modifier = Modifier.fillMaxWidth()) {
                            items(savedPlaces) { place ->
                                ListItem(
                                    headlineContent = { Text(place.name) },
                                    leadingContent = {
                                        Icon(Icons.Filled.Star, contentDescription = null,
                                            tint = MaterialTheme.colorScheme.secondary)
                                    },
                                    modifier = Modifier.clickable {
                                        val latLng = LatLng(place.latitude, place.longitude)
                                        selectedLocation = latLng
                                        selectedLocationName = place.name
                                        isSearchActive = false
                                        viewModel.updateSearchQuery(place.name)
                                        coroutineScope.launch {
                                            cameraPositionState.animate(
                                                CameraUpdateFactory.newLatLngZoom(latLng, 15f)
                                            )
                                        }
                                    }
                                )
                                HorizontalDivider()
                            }
                        }
                    }
                }

                // Message d'erreur
                if (uiState.searchError != null) {
                    Text(
                        text = uiState.searchError!!,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(start = 8.dp, top = 4.dp)
                    )
                }

                // Chips des lieux sauvegardés (quand la barre est fermée)
                if (!isSearchActive && savedPlaces.isNotEmpty()) {
                    LazyRow(
                        modifier = Modifier.padding(top = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(savedPlaces) { place ->
                            FilterChip(
                                selected = false,
                                onClick = {
                                    val latLng = LatLng(place.latitude, place.longitude)
                                    selectedLocation = latLng
                                    selectedLocationName = place.name
                                    coroutineScope.launch {
                                        cameraPositionState.animate(
                                            CameraUpdateFactory.newLatLngZoom(latLng, 15f)
                                        )
                                    }
                                },
                                label = { Text(place.name) },
                                leadingIcon = { Icon(Icons.Filled.Star, null, Modifier.size(16.dp)) }
                            )
                        }
                    }
                }
            }

            // FABs (satellite + ma position) — bas droite
            Column(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 16.dp, bottom = 100.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                horizontalAlignment = Alignment.End
            ) {
                // Toggle satellite
                SmallFloatingActionButton(
                    onClick = { isSatellite = !isSatellite },
                    containerColor = MaterialTheme.colorScheme.surface,
                    contentColor = MaterialTheme.colorScheme.onSurface
                ) {
                    Icon(
                        if (isSatellite) Icons.Filled.Map else Icons.Filled.Satellite,
                        contentDescription = "Changer vue"
                    )
                }

                // Ma position
                FloatingActionButton(
                    onClick = {
                        val loc = currentLocation
                        if (loc != null) {
                            coroutineScope.launch {
                                cameraPositionState.animate(
                                    CameraUpdateFactory.newLatLngZoom(loc, 15f)
                                )
                            }
                        } else {
                            viewModel.fetchCurrentLocation()
                        }
                    },
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                ) {
                    Icon(Icons.Filled.MyLocation, contentDescription = "Ma position")
                }
            }

            // Boutons Sauvegarder + Confirmer (bas centre)
            Row(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(horizontal = 24.dp, vertical = 24.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                FilledTonalButton(
                    onClick = { showSaveDialog = true },
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Filled.BookmarkAdd, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Sauvegarder")
                }

                Button(
                    onClick = {
                        onPlaceSelected(selectedLocation)
                        onNavigateBack()
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Confirmer")
                }
            }
        }
    }

    // Dialog sauvegarder lieu
    if (showSaveDialog) {
        AlertDialog(
            onDismissRequest = { showSaveDialog = false },
            title = { Text("Sauvegarder ce lieu") },
            text = {
                OutlinedTextField(
                    value = newPlaceName,
                    onValueChange = { newPlaceName = it },
                    label = { Text("Nom du lieu (ex: Maison)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (newPlaceName.isNotBlank()) {
                            viewModel.savePlace(
                                newPlaceName,
                                selectedLocation.latitude,
                                selectedLocation.longitude
                            )
                            showSaveDialog = false
                            newPlaceName = ""
                        }
                    },
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("Enregistrer")
                }
            },
            dismissButton = {
                TextButton(onClick = { showSaveDialog = false }) {
                    Text("Annuler")
                }
            }
        )
    }
}
