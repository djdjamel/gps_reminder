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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.maps.android.compose.*
import com.remindly.domain.model.CategoryReferenceType
import com.remindly.domain.model.CommuteDirection
import com.remindly.domain.model.PlaceCategory
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlacePickerScreen(
    onPlaceSelected: (LatLng, String?, String?, String?, String?) -> Unit,
    onNavigateBack: () -> Unit,
    viewModel: PlacePickerViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val savedPlaces by viewModel.savedPlaces.collectAsStateWithLifecycle()
    val currentLocation by viewModel.currentLocation.collectAsStateWithLifecycle()
    val userSettings by viewModel.userSettings.collectAsStateWithLifecycle()
    val locationSettingsResolution by viewModel.locationSettingsResolution.collectAsStateWithLifecycle()

    var selectedTabIndex by remember { mutableIntStateOf(0) } // 0 = Carte, 1 = Catégorie

    // Launcher pour le dialogue système "Activer la localisation"
    val gpsSettingsLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        viewModel.clearLocationSettingsResolution()
        if (result.resultCode == Activity.RESULT_OK) {
            viewModel.fetchCurrentLocation()
        }
    }

    LaunchedEffect(locationSettingsResolution) {
        locationSettingsResolution?.let { intentSender ->
            gpsSettingsLauncher.launch(
                IntentSenderRequest.Builder(intentSender).build()
            )
        }
    }

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

    LaunchedEffect(currentLocation) {
        if (!hasAutocentered && currentLocation != null) {
            hasAutocentered = true
            selectedLocation = currentLocation!!
            cameraPositionState.animate(CameraUpdateFactory.newLatLngZoom(currentLocation!!, 15f))
        }
    }

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = { Text("Sélectionner un lieu") },
                    navigationIcon = {
                        IconButton(onClick = onNavigateBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Retour")
                        }
                    }
                )
                TabRow(selectedTabIndex = selectedTabIndex) {
                    Tab(
                        selected = selectedTabIndex == 0,
                        onClick = { selectedTabIndex = 0 },
                        text = { Text("📍 Sur la carte") }
                    )
                    Tab(
                        selected = selectedTabIndex == 1,
                        onClick = { selectedTabIndex = 1 },
                        text = { Text("🏷️ Par catégorie") }
                    )
                }
            }
        }
    ) { paddingValues ->
        if (selectedTabIndex == 0) {
            // ─── Onglet 1 : Carte classique ──────────────────────────────────
            Box(modifier = Modifier.fillMaxSize().padding(paddingValues)) {
                GoogleMap(
                    modifier = Modifier.fillMaxSize(),
                    cameraPositionState = cameraPositionState,
                    properties = MapProperties(
                        mapType = if (isSatellite) MapType.HYBRID else MapType.NORMAL,
                        isMyLocationEnabled = true
                    ),
                    uiSettings = MapUiSettings(
                        zoomControlsEnabled = false,
                        myLocationButtonEnabled = false,
                        compassEnabled = true
                    ),
                    onMapClick = { latLng ->
                        selectedLocation = latLng
                        selectedLocationName = null
                    }
                ) {
                    Marker(
                        state = MarkerState(position = selectedLocation),
                        title = selectedLocationName ?: "Point sélectionné",
                        snippet = "Lat: ${"%.4f".format(selectedLocation.latitude)}, Lng: ${"%.4f".format(selectedLocation.longitude)}"
                    )
                }

                // Barre de recherche
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                        .align(Alignment.TopCenter)
                ) {
                    SearchBar(
                        query = uiState.searchQuery,
                        onQueryChange = viewModel::updateSearchQuery,
                        onSearch = { isSearchActive = false },
                        active = isSearchActive,
                        onActiveChange = { isSearchActive = it },
                        placeholder = { Text("Rechercher une adresse, un commerce…") },
                        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                        trailingIcon = {
                            if (uiState.searchQuery.isNotEmpty()) {
                                IconButton(onClick = { viewModel.updateSearchQuery("") }) {
                                    Icon(Icons.Filled.Close, contentDescription = "Effacer")
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        if (uiState.isSearching) {
                            Box(modifier = Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator(modifier = Modifier.size(24.dp))
                            }
                        }

                        LazyColumn {
                            items(uiState.predictions) { prediction ->
                                ListItem(
                                    headlineContent = {
                                        Text(prediction.getPrimaryText(null).toString())
                                    },
                                    supportingContent = {
                                        Text(
                                            prediction.getSecondaryText(null).toString(),
                                            style = MaterialTheme.typography.bodySmall
                                        )
                                    },
                                    leadingContent = {
                                        Icon(Icons.Filled.LocationOn, contentDescription = null)
                                    },
                                    modifier = Modifier.clickable {
                                        viewModel.selectPrediction(prediction.placeId) { latLng, name ->
                                            selectedLocation = latLng
                                            selectedLocationName = name
                                            isSearchActive = false
                                            coroutineScope.launch {
                                                cameraPositionState.animate(
                                                    CameraUpdateFactory.newLatLngZoom(latLng, 16f)
                                                )
                                            }
                                        }
                                    }
                                )
                                HorizontalDivider()
                            }
                        }
                    }

                    if (!isSearchActive && savedPlaces.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            contentPadding = PaddingValues(horizontal = 4.dp)
                        ) {
                            items(savedPlaces) { place ->
                                SuggestionChip(
                                    onClick = {
                                        val latLng = LatLng(place.latitude, place.longitude)
                                        selectedLocation = latLng
                                        selectedLocationName = place.name
                                        coroutineScope.launch {
                                            cameraPositionState.animate(
                                                CameraUpdateFactory.newLatLngZoom(latLng, 16f)
                                            )
                                        }
                                    },
                                    label = { Text(place.name) },
                                    icon = { Icon(Icons.Filled.Place, contentDescription = null, modifier = Modifier.size(16.dp)) }
                                )
                            }
                        }
                    }
                }

                // Boutons d'action sur la carte
                Column(
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .padding(end = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FloatingActionButton(
                        onClick = { isSatellite = !isSatellite },
                        modifier = Modifier.size(48.dp),
                        containerColor = MaterialTheme.colorScheme.surface
                    ) {
                        Icon(
                            imageVector = if (isSatellite) Icons.Filled.Map else Icons.Filled.Satellite,
                            contentDescription = "Basculer la vue",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }

                    FloatingActionButton(
                        onClick = {
                            viewModel.checkLocationSettings()
                            currentLocation?.let { loc ->
                                selectedLocation = loc
                                selectedLocationName = null
                                coroutineScope.launch {
                                    cameraPositionState.animate(CameraUpdateFactory.newLatLngZoom(loc, 16f))
                                }
                            }
                        },
                        modifier = Modifier.size(48.dp),
                        containerColor = MaterialTheme.colorScheme.surface
                    ) {
                        Icon(
                            imageVector = Icons.Filled.MyLocation,
                            contentDescription = "Ma position",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }

                // Panneau inférieur : Validation du lieu
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.BottomCenter)
                        .padding(16.dp),
                    elevation = CardDefaults.cardElevation(8.dp),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(
                                Icons.Filled.LocationOn,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(24.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = selectedLocationName ?: "Point sélectionné",
                                    style = MaterialTheme.typography.titleMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = "Lat: ${"%.4f".format(selectedLocation.latitude)}, Lng: ${"%.4f".format(selectedLocation.longitude)}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        Button(
                            onClick = {
                                val label = selectedLocationName ?: "Lat: ${"%.4f".format(selectedLocation.latitude)}, Lng: ${"%.4f".format(selectedLocation.longitude)}"
                                onPlaceSelected(selectedLocation, label, null, null, null)
                            },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text("Confirmer ce lieu")
                        }
                    }
                }
            }
        } else {
            // ─── Onglet 2 : Sélection par Catégorie ───────────────────────────
            CategoryPickerTab(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                userSettings = userSettings,
                onCategoryConfirmed = { category, refType, commuteDirection ->
                    val label = "À proximité : ${category.displayName}"
                    val ref = if (refType == CategoryReferenceType.COMMUTE_ROUTE) "COMMUTE_ROUTE" else "CURRENT_LOCATION"
                    val loc = currentLocation ?: LatLng(0.0, 0.0)
                    onPlaceSelected(loc, label, category.id, ref, commuteDirection.id)
                }
            )
        }
    }
}

@Composable
private fun CategoryPickerTab(
    modifier: Modifier = Modifier,
    userSettings: com.remindly.data.settings.VoiceAlarmSettings,
    onCategoryConfirmed: (PlaceCategory, CategoryReferenceType, CommuteDirection) -> Unit
) {
    var selectedCategory by remember { mutableStateOf(PlaceCategory.SUPERMARKET) }
    var selectedRefType by remember { mutableStateOf(CategoryReferenceType.CURRENT_LOCATION) }
    var selectedDirection by remember { mutableStateOf(CommuteDirection.RETURN) }

    Column(
        modifier = modifier
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text(
                "Choisissez un type de commerce",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Text(
                "Le rappel sonnera dès que vous passerez près de n'importe quel établissement de ce type.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // Grille des catégories
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.weight(1f, fill = false)
            ) {
                items(PlaceCategory.entries) { category ->
                    val isSelected = selectedCategory == category
                    ElevatedCard(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .clickable { selectedCategory = category },
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.elevatedCardColors(
                            containerColor = if (isSelected)
                                MaterialTheme.colorScheme.primaryContainer
                            else MaterialTheme.colorScheme.surface
                        )
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                                modifier = Modifier.size(36.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    val icon = getCategoryIcon(category)
                                    Icon(
                                        imageVector = icon,
                                        contentDescription = null,
                                        tint = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }
                            Spacer(Modifier.width(12.dp))
                            Text(
                                text = category.displayName,
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.weight(1f)
                            )
                            if (isSelected) {
                                Icon(
                                    Icons.Filled.CheckCircle,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(22.dp)
                                )
                            }
                        }
                    }
                }
            }

            HorizontalDivider()

            // Sélecteur de référence
            Text(
                "Zone de détection",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { selectedRefType = CategoryReferenceType.CURRENT_LOCATION }
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                RadioButton(
                    selected = selectedRefType == CategoryReferenceType.CURRENT_LOCATION,
                    onClick = { selectedRefType = CategoryReferenceType.CURRENT_LOCATION }
                )
                Spacer(Modifier.width(8.dp))
                Column {
                    Text("Autour de ma position actuelle", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                    Text("Détecte dans un rayon de ${userSettings.poiSearchRadiusKm} km", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            val hasCommute = userSettings.hasCommuteRoute
            Column(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(enabled = hasCommute) {
                            if (hasCommute) selectedRefType = CategoryReferenceType.COMMUTE_ROUTE
                        }
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(
                        selected = selectedRefType == CategoryReferenceType.COMMUTE_ROUTE,
                        onClick = { if (hasCommute) selectedRefType = CategoryReferenceType.COMMUTE_ROUTE },
                        enabled = hasCommute
                    )
                    Spacer(Modifier.width(8.dp))
                    Column {
                        Text(
                            "Sur mon trajet habituel",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                            color = if (hasCommute) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                        )
                        Text(
                            if (hasCommute)
                                "${userSettings.commuteStartLabel ?: "Départ"} ➔ ${userSettings.commuteEndLabel ?: "Arrivée"}"
                            else "Non configuré (à définir dans Paramètres ⚙️)",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                // Sous-options de direction (Aller / Retour / Les deux)
                if (selectedRefType == CategoryReferenceType.COMMUTE_ROUTE && hasCommute) {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 32.dp, top = 4.dp, bottom = 4.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                        ),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Column(modifier = Modifier.padding(10.dp)) {
                            Text(
                                "Moment du déclenchement :",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Spacer(Modifier.height(4.dp))

                            CommuteDirection.entries.forEach { direction ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(6.dp))
                                        .clickable { selectedDirection = direction }
                                        .padding(vertical = 3.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    RadioButton(
                                        selected = selectedDirection == direction,
                                        onClick = { selectedDirection = direction }
                                    )
                                    Spacer(Modifier.width(4.dp))
                                    Column {
                                        Text(
                                            direction.displayName,
                                            style = MaterialTheme.typography.bodySmall,
                                            fontWeight = FontWeight.SemiBold
                                        )
                                        Text(
                                            direction.description,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        Button(
            onClick = { onCategoryConfirmed(selectedCategory, selectedRefType, selectedDirection) },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp)
        ) {
            Text("Confirmer cette catégorie (${selectedCategory.displayName})")
        }
    }
}

private fun getCategoryIcon(category: PlaceCategory): ImageVector {
    return when (category) {
        PlaceCategory.SUPERMARKET -> Icons.Filled.ShoppingCart
        PlaceCategory.PHARMACY -> Icons.Filled.LocalPharmacy
        PlaceCategory.BAKERY -> Icons.Filled.BakeryDining
        PlaceCategory.GAS_STATION -> Icons.Filled.LocalGasStation
        PlaceCategory.ATM -> Icons.Filled.Atm
        PlaceCategory.RESTAURANT -> Icons.Filled.Restaurant
    }
}
