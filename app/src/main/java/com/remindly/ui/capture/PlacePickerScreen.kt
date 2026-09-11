package com.remindly.ui.capture

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
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
import com.remindly.ui.theme.LocalAppStrings
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlacePickerScreen(
    onPlaceSelected: (LatLng, String?, String?, String?, String?, Float, Long?) -> Unit,
    onNavigateBack: () -> Unit,
    initialRadiusM: Float? = null,
    initialActiveFromMillis: Long? = null,
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
    var isPanelExpanded by remember { mutableStateOf(true) }

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

    val strings = LocalAppStrings.current
    var selectedRadiusM by remember { mutableFloatStateOf(initialRadiusM ?: userSettings.poiDetectionRadiusM.toFloat()) }
    var selectedActiveFromMillis by remember { mutableStateOf(initialActiveFromMillis) }

    LaunchedEffect(userSettings.poiDetectionRadiusM) {
        if (initialRadiusM == null && selectedRadiusM == 450f && userSettings.poiDetectionRadiusM != 450) {
            selectedRadiusM = userSettings.poiDetectionRadiusM.toFloat()
        }
    }

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = { Text(strings.placePickerTitle) },
                    navigationIcon = {
                        IconButton(onClick = onNavigateBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = strings.back)
                        }
                    }
                )
                TabRow(selectedTabIndex = selectedTabIndex) {
                    Tab(
                        selected = selectedTabIndex == 0,
                        onClick = { selectedTabIndex = 0 },
                        text = { Text(strings.placePickerTabMap) }
                    )
                    Tab(
                        selected = selectedTabIndex == 1,
                        onClick = { selectedTabIndex = 1 },
                        text = { Text(strings.placePickerTabCategory) }
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
                    contentPadding = PaddingValues(
                        top = 80.dp,
                        bottom = if (isPanelExpanded) 280.dp else 120.dp
                    ),
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
                        title = selectedLocationName ?: strings.placePickerSelectedPoint,
                        snippet = "Lat: ${"%.4f".format(selectedLocation.latitude)}, Lng: ${"%.4f".format(selectedLocation.longitude)}"
                    )
                    Circle(
                        center = selectedLocation,
                        radius = selectedRadiusM.toDouble(),
                        fillColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.20f),
                        strokeColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.85f),
                        strokeWidth = 4f
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

                // Conteneur inférieur unifié : Boutons d'action carte + Feuille inférieure (Bottom Sheet)
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.BottomCenter)
                ) {
                    // Boutons d'action flottants sur la carte (Satellite + Ma position)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(end = 16.dp, bottom = 10.dp),
                        horizontalArrangement = Arrangement.End
                    ) {
                        Column(
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                            horizontalAlignment = Alignment.End
                        ) {
                            SmallFloatingActionButton(
                                onClick = { isSatellite = !isSatellite },
                                containerColor = MaterialTheme.colorScheme.surface,
                                contentColor = MaterialTheme.colorScheme.primary,
                                elevation = FloatingActionButtonDefaults.elevation(4.dp)
                            ) {
                                Icon(
                                    imageVector = if (isSatellite) Icons.Filled.Map else Icons.Filled.Satellite,
                                    contentDescription = "Basculer la vue"
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
                                containerColor = MaterialTheme.colorScheme.surface,
                                contentColor = MaterialTheme.colorScheme.primary,
                                elevation = FloatingActionButtonDefaults.elevation(4.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.MyLocation,
                                    contentDescription = "Ma position"
                                )
                            }
                        }
                    }

                    // Panneau inférieur : Réglage du rayon & Validation du lieu
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .animateContentSize(),
                        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
                        tonalElevation = 6.dp,
                        shadowElevation = 8.dp,
                        color = MaterialTheme.colorScheme.surface
                    ) {
                        if (!isPanelExpanded) {
                            // ─── Mode compact replié (Surface de carte maximale ~85%) ───
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 12.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { isPanelExpanded = true }
                                        .padding(vertical = 4.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .width(36.dp)
                                            .height(4.dp)
                                            .clip(RoundedCornerShape(2.dp))
                                            .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f))
                                    )
                                }

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .weight(1f)
                                            .clickable { isPanelExpanded = true },
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(
                                            Icons.Filled.LocationOn,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(22.dp)
                                        )
                                        Spacer(Modifier.width(8.dp))
                                        Column {
                                            Text(
                                                text = selectedLocationName ?: strings.placePickerSelectedPoint,
                                                style = MaterialTheme.typography.titleMedium,
                                                fontWeight = FontWeight.SemiBold,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            Text(
                                                text = "Rayon : ${if (selectedRadiusM < 1000f) strings.radiusFormatMeters(selectedRadiusM.roundToInt()) else strings.radiusFormatKm(selectedRadiusM / 1000f)}" +
                                                        if (selectedActiveFromMillis != null) " • Différé" else "",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }

                                    Spacer(Modifier.width(12.dp))

                                    Button(
                                        onClick = {
                                            val label = selectedLocationName ?: "Lat: ${"%.4f".format(selectedLocation.latitude)}, Lng: ${"%.4f".format(selectedLocation.longitude)}"
                                            onPlaceSelected(selectedLocation, label, null, null, null, selectedRadiusM, selectedActiveFromMillis)
                                        },
                                        shape = RoundedCornerShape(12.dp),
                                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
                                    ) {
                                        Text(strings.placePickerConfirmButton)
                                    }
                                }
                            }
                        } else {
                            // ─── Mode étendu complet avec réglages optimisés ───
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 14.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { isPanelExpanded = false }
                                        .padding(vertical = 4.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .width(36.dp)
                                            .height(4.dp)
                                            .clip(RoundedCornerShape(2.dp))
                                            .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f))
                                    )
                                }

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
                                            text = selectedLocationName ?: strings.placePickerSelectedPoint,
                                            style = MaterialTheme.typography.titleMedium,
                                            fontWeight = FontWeight.Bold,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            text = "Lat: ${"%.4f".format(selectedLocation.latitude)}, Lng: ${"%.4f".format(selectedLocation.longitude)}",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    IconButton(
                                        onClick = { isPanelExpanded = false },
                                        modifier = Modifier.size(32.dp)
                                    ) {
                                        Icon(
                                            Icons.Filled.KeyboardArrowDown,
                                            contentDescription = "Réduire",
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }

                                HorizontalDivider(modifier = Modifier.padding(vertical = 2.dp))

                                // Sélecteur de rayon de détection
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = strings.detectionRadiusLabel,
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Surface(
                                        shape = RoundedCornerShape(8.dp),
                                        color = MaterialTheme.colorScheme.primaryContainer,
                                        modifier = Modifier.padding(start = 8.dp)
                                    ) {
                                        Text(
                                            text = if (selectedRadiusM < 1000f)
                                                strings.radiusFormatMeters(selectedRadiusM.roundToInt())
                                            else
                                                strings.radiusFormatKm(selectedRadiusM / 1000f),
                                            style = MaterialTheme.typography.labelMedium,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                                        )
                                    }
                                }

                                // Puces de raccourcis rapides (Presets)
                                val presets = remember(strings) {
                                    listOf(
                                        150f to strings.presetPedestrian,
                                        450f to strings.presetStandard,
                                        1000f to strings.presetBroad,
                                        2500f to strings.presetTransit
                                    )
                                }
                                LazyRow(
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    contentPadding = PaddingValues(horizontal = 2.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    items(presets) { preset ->
                                        val presetRadius = preset.first
                                        val label = preset.second
                                        val isSelected = (selectedRadiusM.roundToInt() == presetRadius.roundToInt())
                                        FilterChip(
                                            selected = isSelected,
                                            onClick = { selectedRadiusM = presetRadius },
                                            label = { Text(label, style = MaterialTheme.typography.labelSmall) }
                                        )
                                    }
                                }

                                // Slider fluide compact
                                Slider(
                                    value = selectedRadiusM,
                                    onValueChange = {
                                        selectedRadiusM = ((it / 25f).roundToInt() * 25).toFloat().coerceIn(100f, 3000f)
                                    },
                                    valueRange = 100f..3000f,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(32.dp)
                                )

                                // Option 1 : Heure d'activation différée
                                PlaceActivationTimePicker(
                                    activeFromMillis = selectedActiveFromMillis,
                                    onActiveFromMillisChange = { selectedActiveFromMillis = it }
                                )

                                Spacer(Modifier.height(2.dp))

                                Button(
                                    onClick = {
                                        val label = selectedLocationName ?: "Lat: ${"%.4f".format(selectedLocation.latitude)}, Lng: ${"%.4f".format(selectedLocation.longitude)}"
                                        onPlaceSelected(selectedLocation, label, null, null, null, selectedRadiusM, selectedActiveFromMillis)
                                    },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(48.dp),
                                    shape = RoundedCornerShape(12.dp)
                                ) {
                                    Text(strings.placePickerConfirmButton, fontWeight = FontWeight.Bold)
                                }
                            }
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
                activeFromMillis = selectedActiveFromMillis,
                onActiveFromMillisChange = { selectedActiveFromMillis = it },
                onCategoryConfirmed = { category, refType, commuteDirection, activeFromMillis ->
                    val label = "À proximité : ${category.displayName}"
                    val ref = if (refType == CategoryReferenceType.COMMUTE_ROUTE) "COMMUTE_ROUTE" else "CURRENT_LOCATION"
                    val loc = currentLocation ?: selectedLocation
                    onPlaceSelected(loc, label, category.id, ref, commuteDirection.id, userSettings.poiDetectionRadiusM.toFloat(), activeFromMillis)
                }
            )
        }
    }
}

@Composable
private fun CategoryPickerTab(
    modifier: Modifier = Modifier,
    userSettings: com.remindly.data.settings.VoiceAlarmSettings,
    activeFromMillis: Long?,
    onActiveFromMillisChange: (Long?) -> Unit,
    onCategoryConfirmed: (PlaceCategory, CategoryReferenceType, CommuteDirection, Long?) -> Unit
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

        // Option 1 : Heure d'activation différée
        PlaceActivationTimePicker(
            activeFromMillis = activeFromMillis,
            onActiveFromMillisChange = onActiveFromMillisChange
        )

        Spacer(Modifier.height(8.dp))

        Button(
            onClick = { onCategoryConfirmed(selectedCategory, selectedRefType, selectedDirection, activeFromMillis) },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp)
        ) {
            Text("Confirmer cette catégorie (${selectedCategory.displayName})")
        }
    }
}

@Composable
private fun PlaceActivationTimePicker(
    activeFromMillis: Long?,
    onActiveFromMillisChange: (Long?) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val strings = LocalAppStrings.current
    var isExpanded by remember { mutableStateOf(activeFromMillis != null) }

    LaunchedEffect(activeFromMillis) {
        if (activeFromMillis != null) {
            isExpanded = true
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
            .padding(8.dp)
    ) {
        if (!isExpanded && activeFromMillis == null) {
            // Mode compact : ligne simple déclencheur
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { isExpanded = true }
                    .padding(horizontal = 4.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Filled.Schedule,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = strings.placeActivationTitle,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = MaterialTheme.colorScheme.surface
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = strings.placeActivationImmediate,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(Modifier.width(4.dp))
                        Icon(
                            Icons.Filled.KeyboardArrowDown,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
        } else {
            // Mode ouvert / configuré
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Filled.Schedule,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = strings.placeActivationTitle,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
                if (activeFromMillis != null) {
                    IconButton(
                        onClick = {
                            onActiveFromMillisChange(null)
                            isExpanded = false
                        },
                        modifier = Modifier.size(24.dp)
                    ) {
                        Icon(
                            Icons.Filled.Close,
                            contentDescription = strings.placeActivationClear,
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.error
                        )
                    }
                } else {
                    IconButton(
                        onClick = { isExpanded = false },
                        modifier = Modifier.size(24.dp)
                    ) {
                        Icon(
                            Icons.Filled.KeyboardArrowUp,
                            contentDescription = "Replier",
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            Spacer(Modifier.height(4.dp))

            if (activeFromMillis != null) {
                val formatted = remember(activeFromMillis) {
                    val sdf = java.text.SimpleDateFormat("dd/MM 'à' HH:mm", java.util.Locale.getDefault())
                    sdf.format(java.util.Date(activeFromMillis))
                }
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Filled.CheckCircle,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = strings.placeActivationActiveFrom(formatted),
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }
                Spacer(Modifier.height(2.dp))
                Text(
                    text = strings.placeActivationHint,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                // Puces de sélection rapide
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    contentPadding = PaddingValues(horizontal = 2.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    item {
                        FilterChip(
                            selected = true,
                            onClick = { isExpanded = false },
                            label = { Text(strings.placeActivationImmediate, style = MaterialTheme.typography.labelSmall) }
                        )
                    }
                    item {
                        AssistChip(
                            onClick = {
                                val cal = java.util.Calendar.getInstance().apply {
                                    if (get(java.util.Calendar.HOUR_OF_DAY) >= 18) {
                                        add(java.util.Calendar.DAY_OF_YEAR, 1)
                                    }
                                    set(java.util.Calendar.HOUR_OF_DAY, 18)
                                    set(java.util.Calendar.MINUTE, 0)
                                    set(java.util.Calendar.SECOND, 0)
                                    set(java.util.Calendar.MILLISECOND, 0)
                                }
                                onActiveFromMillisChange(cal.timeInMillis)
                            },
                            label = { Text(strings.placeActivationThisEvening, style = MaterialTheme.typography.labelSmall) },
                            leadingIcon = { Icon(Icons.Filled.NightsStay, null, Modifier.size(14.dp)) }
                        )
                    }
                    item {
                        AssistChip(
                            onClick = {
                                val cal = java.util.Calendar.getInstance().apply {
                                    add(java.util.Calendar.DAY_OF_YEAR, 1)
                                    set(java.util.Calendar.HOUR_OF_DAY, 8)
                                    set(java.util.Calendar.MINUTE, 0)
                                    set(java.util.Calendar.SECOND, 0)
                                    set(java.util.Calendar.MILLISECOND, 0)
                                }
                                onActiveFromMillisChange(cal.timeInMillis)
                            },
                            label = { Text(strings.placeActivationTomorrowMorning, style = MaterialTheme.typography.labelSmall) },
                            leadingIcon = { Icon(Icons.Filled.Alarm, null, Modifier.size(14.dp)) }
                        )
                    }
                    item {
                        AssistChip(
                            onClick = {
                                val cal = java.util.Calendar.getInstance()
                                android.app.DatePickerDialog(
                                    context,
                                    { _, year, month, dayOfMonth ->
                                        android.app.TimePickerDialog(
                                            context,
                                            { _, hourOfDay, minute ->
                                                val c = java.util.Calendar.getInstance().apply {
                                                    set(year, month, dayOfMonth, hourOfDay, minute, 0)
                                                    set(java.util.Calendar.MILLISECOND, 0)
                                                }
                                                onActiveFromMillisChange(c.timeInMillis)
                                            },
                                            cal.get(java.util.Calendar.HOUR_OF_DAY),
                                            cal.get(java.util.Calendar.MINUTE),
                                            true
                                        ).show()
                                    },
                                    cal.get(java.util.Calendar.YEAR),
                                    cal.get(java.util.Calendar.MONTH),
                                    cal.get(java.util.Calendar.DAY_OF_MONTH)
                                ).show()
                            },
                            label = { Text(strings.placeActivationCustomDate, style = MaterialTheme.typography.labelSmall) },
                            leadingIcon = { Icon(Icons.Filled.CalendarMonth, null, Modifier.size(14.dp)) }
                        )
                    }
                }
            }
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
