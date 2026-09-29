package com.remindly.ui.capture

import android.app.Activity
import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlacePickerScreen(
    onPlaceSelected: (LatLng, String?, String?, String?, String?, Float?, Long?, String?) -> Unit,
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
    var isSearchExpanded by remember { mutableStateOf(false) }
    var hasAutocentered by remember { mutableStateOf(false) }
    var isPanelExpanded by remember { mutableStateOf(true) }

    val focusRequester = remember { FocusRequester() }
    val cameraPositionState = rememberCameraPositionState {
        position = CameraPosition.fromLatLngZoom(fallbackLocation, 10f)
    }

    val coroutineScope = rememberCoroutineScope()

    LaunchedEffect(isSearchExpanded) {
        if (isSearchExpanded) {
            delay(100)
            try {
                focusRequester.requestFocus()
            } catch (_: Exception) {}
        }
    }

    BackHandler(enabled = isSearchExpanded) {
        isSearchExpanded = false
        viewModel.updateSearchQuery("")
    }

    LaunchedEffect(selectedTabIndex) {
        if (selectedTabIndex != 0) {
            isSearchExpanded = false
            viewModel.updateSearchQuery("")
        }
    }

    LaunchedEffect(currentLocation) {
        if (!hasAutocentered && currentLocation != null) {
            hasAutocentered = true
            selectedLocation = currentLocation!!
            cameraPositionState.animate(CameraUpdateFactory.newLatLngZoom(currentLocation!!, 15f))
        }
    }

    val strings = LocalAppStrings.current
    var isAutoRadius by remember { mutableStateOf(initialRadiusM == null) }
    var selectedRadiusM by remember { mutableFloatStateOf(initialRadiusM ?: userSettings.poiDetectionRadiusM.toFloat()) }
    var selectedActiveFromMillis by remember { mutableStateOf(initialActiveFromMillis) }

    LaunchedEffect(userSettings.poiDetectionRadiusM) {
        if (initialRadiusM == null && selectedRadiusM == 450f && userSettings.poiDetectionRadiusM != 450) {
            selectedRadiusM = userSettings.poiDetectionRadiusM.toFloat()
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        if (selectedTabIndex == 0) {
            // ─── Onglet 1 : Carte classique plein écran ──────────────────────────
            GoogleMap(
                modifier = Modifier.fillMaxSize(),
                cameraPositionState = cameraPositionState,
                contentPadding = PaddingValues(
                    top = 72.dp,
                    bottom = if (isPanelExpanded) 240.dp else 120.dp
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
                    if (isSearchExpanded) {
                        isSearchExpanded = false
                        viewModel.updateSearchQuery("")
                    }
                }
            ) {
                Marker(
                    state = MarkerState(position = selectedLocation),
                    title = selectedLocationName ?: strings.placePickerSelectedPoint
                )
                Circle(
                    center = selectedLocation,
                    radius = selectedRadiusM.toDouble(),
                    fillColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.20f),
                    strokeColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.85f),
                    strokeWidth = 4f
                )
            }

            // Conteneur inférieur unifié : Boutons d'action carte + Panneau inférieur
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
                            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.90f),
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
                            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.90f),
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

                // Panneau inférieur épuré : Réglage du rayon & Validation compacte
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
                        // ─── Mode compact replié (Surface de carte maximale ~92%) ───
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .navigationBarsPadding()
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
                                        Icons.Filled.NearMe,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    val radiusText = if (isAutoRadius) strings.presetAuto else if (selectedRadiusM < 1000f) strings.radiusFormatMeters(selectedRadiusM.roundToInt()) else strings.radiusFormatKm(selectedRadiusM / 1000f)
                                    Text(
                                        text = "Rayon : $radiusText" +
                                                if (selectedActiveFromMillis != null) " • Différé" else "",
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                }

                                FilledIconButton(
                                    onClick = {
                                        val label = selectedLocationName ?: "Lat: ${"%.4f".format(selectedLocation.latitude)}, Lng: ${"%.4f".format(selectedLocation.longitude)}"
                                        val finalRadius = if (isAutoRadius) null else selectedRadiusM
                                        onPlaceSelected(selectedLocation, label, null, null, null, finalRadius, selectedActiveFromMillis, null)
                                    },
                                    modifier = Modifier.size(44.dp),
                                    shape = RoundedCornerShape(12.dp),
                                    colors = IconButtonDefaults.filledIconButtonColors(
                                        containerColor = MaterialTheme.colorScheme.primary,
                                        contentColor = MaterialTheme.colorScheme.onPrimary
                                    )
                                ) {
                                    Icon(
                                        Icons.Filled.Check,
                                        contentDescription = strings.placePickerConfirmButton,
                                        modifier = Modifier.size(24.dp)
                                    )
                                }
                            }
                        }
                    } else {
                        // ─── Mode étendu optimisé et épuré (Sans 'Point sélectionné' ni Lat/Lng) ───
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .navigationBarsPadding()
                                .padding(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 16.dp),
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

                            // En-tête du panneau : Rayon de détection + Badge + Bouton Validation compact [ ✓ ]
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = strings.detectionRadiusLabel,
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Surface(
                                        shape = RoundedCornerShape(8.dp),
                                        color = MaterialTheme.colorScheme.primaryContainer
                                    ) {
                                        val radiusBadge = if (isAutoRadius) strings.presetAuto else if (selectedRadiusM < 1000f)
                                            strings.radiusFormatMeters(selectedRadiusM.roundToInt())
                                        else
                                            strings.radiusFormatKm(selectedRadiusM / 1000f)
                                        Text(
                                            text = radiusBadge,
                                            style = MaterialTheme.typography.labelMedium,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                                        )
                                    }
                                }

                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
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

                                    FilledIconButton(
                                        onClick = {
                                            val label = selectedLocationName ?: "Lat: ${"%.4f".format(selectedLocation.latitude)}, Lng: ${"%.4f".format(selectedLocation.longitude)}"
                                            val finalRadius = if (isAutoRadius) null else selectedRadiusM
                                            onPlaceSelected(selectedLocation, label, null, null, null, finalRadius, selectedActiveFromMillis, null)
                                        },
                                        modifier = Modifier.size(44.dp),
                                        shape = RoundedCornerShape(12.dp),
                                        colors = IconButtonDefaults.filledIconButtonColors(
                                            containerColor = MaterialTheme.colorScheme.primary,
                                            contentColor = MaterialTheme.colorScheme.onPrimary
                                        )
                                    ) {
                                        Icon(
                                            Icons.Filled.Check,
                                            contentDescription = strings.placePickerConfirmButton,
                                            modifier = Modifier.size(24.dp)
                                        )
                                    }
                                }
                            }

                            // Puces de raccourcis rapides (Presets)
                            val presets = remember(strings) {
                                listOf(
                                    0f to strings.presetAuto,
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
                                    val isSelected = if (presetRadius == 0f) isAutoRadius else (!isAutoRadius && selectedRadiusM.roundToInt() == presetRadius.roundToInt())
                                    FilterChip(
                                        selected = isSelected,
                                        onClick = {
                                            if (presetRadius == 0f) {
                                                isAutoRadius = true
                                            } else {
                                                isAutoRadius = false
                                                selectedRadiusM = presetRadius
                                            }
                                        },
                                        label = { Text(label, style = MaterialTheme.typography.labelSmall) }
                                    )
                                }
                            }

                            // Slider fluide compact
                            Slider(
                                value = selectedRadiusM,
                                onValueChange = {
                                    isAutoRadius = false
                                    selectedRadiusM = ((it / 25f).roundToInt() * 25).toFloat().coerceIn(100f, 3000f)
                                },
                                valueRange = 100f..3000f,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(32.dp)
                            )

                            if (isAutoRadius) {
                                Text(
                                    text = strings.radiusAutoDescription,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(start = 4.dp, bottom = 2.dp)
                                )
                            }

                            // Option 1 : Heure d'activation différée
                            PlaceActivationTimePicker(
                                activeFromMillis = selectedActiveFromMillis,
                                onActiveFromMillisChange = { selectedActiveFromMillis = it }
                            )
                        }
                    }
                }
            }
        } else {
            // ─── Onglet 2 : Sélection par Catégorie ───────────────────────────
            CategoryPickerTab(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background)
                    .statusBarsPadding()
                    .padding(top = 64.dp)
                    .navigationBarsPadding(),
                userSettings = userSettings,
                activeFromMillis = selectedActiveFromMillis,
                onActiveFromMillisChange = { selectedActiveFromMillis = it },
                onCategoryConfirmed = { category, keyword, refType, commuteDirection, activeFromMillis ->
                    val kwSuffix = if (!keyword.isNullOrBlank()) " · $keyword" else ""
                    val label = "À proximité : ${category.displayName}$kwSuffix"
                    val ref = if (refType == CategoryReferenceType.COMMUTE_ROUTE) "COMMUTE_ROUTE" else "CURRENT_LOCATION"
                    val loc = currentLocation ?: selectedLocation
                    onPlaceSelected(loc, label, category.id, ref, commuteDirection.id, userSettings.poiDetectionRadiusM.toFloat(), activeFromMillis, keyword)
                }
            )
        }

        // ─── Commandes Flottantes Supérieures (Plein Écran Aéré) ───────────
        if (selectedTabIndex == 0 && isSearchExpanded) {
            // Recherche rétractable ouverte (une seule ligne de hauteur)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .align(Alignment.TopCenter)
            ) {
                Surface(
                    shape = RoundedCornerShape(24.dp),
                    color = MaterialTheme.colorScheme.surface,
                    shadowElevation = 6.dp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Search,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        BasicTextField(
                            value = uiState.searchQuery,
                            onValueChange = viewModel::updateSearchQuery,
                            modifier = Modifier
                                .weight(1f)
                                .focusRequester(focusRequester),
                            singleLine = true,
                            textStyle = MaterialTheme.typography.bodyMedium.copy(
                                color = MaterialTheme.colorScheme.onSurface
                            ),
                            decorationBox = { innerTextField ->
                                if (uiState.searchQuery.isEmpty()) {
                                    Text(
                                        "Rechercher une adresse, un commerce…",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                innerTextField()
                            }
                        )
                        if (uiState.searchQuery.isNotEmpty()) {
                            IconButton(
                                onClick = { viewModel.updateSearchQuery("") },
                                modifier = Modifier.size(32.dp)
                            ) {
                                Icon(
                                    Icons.Filled.Clear,
                                    contentDescription = "Effacer",
                                    modifier = Modifier.size(18.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        IconButton(
                            onClick = {
                                isSearchExpanded = false
                                viewModel.updateSearchQuery("")
                            },
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                Icons.Filled.Close,
                                contentDescription = "Fermer",
                                modifier = Modifier.size(20.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                // Suggestions / Prédictions
                if (uiState.isSearching) {
                    Spacer(Modifier.height(8.dp))
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.surface,
                        shadowElevation = 6.dp,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 4.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(24.dp))
                        }
                    }
                } else if (uiState.predictions.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    ElevatedCard(
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 280.dp),
                        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 6.dp),
                        colors = CardDefaults.elevatedCardColors(
                            containerColor = MaterialTheme.colorScheme.surface
                        )
                    ) {
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
                                            isSearchExpanded = false
                                            viewModel.updateSearchQuery("")
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
                } else if (savedPlaces.isNotEmpty() && uiState.searchQuery.isEmpty()) {
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
                                    isSearchExpanded = false
                                    coroutineScope.launch {
                                        cameraPositionState.animate(
                                            CameraUpdateFactory.newLatLngZoom(latLng, 16f)
                                        )
                                    }
                                },
                                label = { Text(place.name) },
                                icon = { Icon(Icons.Filled.Place, contentDescription = null, modifier = Modifier.size(16.dp)) },
                                colors = SuggestionChipDefaults.suggestionChipColors(
                                    containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f)
                                )
                            )
                        }
                    }
                }
            }
        } else {
            // Barre flottante compacte : [ ← Retour ]   [ 📍 Carte | 🏷️ Catégorie ]   [ 🔍 Recherche ]
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .align(Alignment.TopCenter),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                // Bouton retour flottant semi-transparent
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.88f),
                    shadowElevation = 4.dp,
                    modifier = Modifier.size(44.dp)
                ) {
                    IconButton(
                        onClick = onNavigateBack,
                        modifier = Modifier.fillMaxSize()
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = strings.back,
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }

                // Pilule de sélection d'onglets compacte et flottante
                Surface(
                    shape = RoundedCornerShape(24.dp),
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.88f),
                    shadowElevation = 4.dp
                ) {
                    Row(
                        modifier = Modifier.padding(3.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val mapSelected = selectedTabIndex == 0
                        val catSelected = selectedTabIndex == 1

                        Surface(
                            shape = RoundedCornerShape(20.dp),
                            color = if (mapSelected) MaterialTheme.colorScheme.primary else Color.Transparent,
                            modifier = Modifier
                                .clip(RoundedCornerShape(20.dp))
                                .clickable { selectedTabIndex = 0 }
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    Icons.Filled.Place,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                    tint = if (mapSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(Modifier.width(4.dp))
                                Text(
                                    strings.placePickerTabMap,
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = if (mapSelected) FontWeight.Bold else FontWeight.Medium,
                                    color = if (mapSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        Surface(
                            shape = RoundedCornerShape(20.dp),
                            color = if (catSelected) MaterialTheme.colorScheme.primary else Color.Transparent,
                            modifier = Modifier
                                .clip(RoundedCornerShape(20.dp))
                                .clickable { selectedTabIndex = 1 }
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    Icons.Filled.Category,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                    tint = if (catSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(Modifier.width(4.dp))
                                Text(
                                    strings.placePickerTabCategory,
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = if (catSelected) FontWeight.Bold else FontWeight.Medium,
                                    color = if (catSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }

                // Bouton loupe de recherche flottant (visible uniquement sur l'onglet carte)
                if (selectedTabIndex == 0) {
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.88f),
                        shadowElevation = 4.dp,
                        modifier = Modifier.size(44.dp)
                    ) {
                        IconButton(
                            onClick = { isSearchExpanded = true },
                            modifier = Modifier.fillMaxSize()
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Search,
                                contentDescription = "Rechercher",
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                } else {
                    Spacer(Modifier.size(44.dp))
                }
            }
        }
    }
}


@Composable
private fun CategoryPickerTab(
    modifier: Modifier = Modifier,
    userSettings: com.remindly.data.settings.VoiceAlarmSettings,
    activeFromMillis: Long?,
    onActiveFromMillisChange: (Long?) -> Unit,
    onCategoryConfirmed: (PlaceCategory, String?, CategoryReferenceType, CommuteDirection, Long?) -> Unit
) {
    var selectedCategory by remember { mutableStateOf(PlaceCategory.SUPERMARKET) }
    var categoryKeyword by remember { mutableStateOf("") }
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

            // Champ mot-clé / enseigne optionnel
            OutlinedTextField(
                value = categoryKeyword,
                onValueChange = { categoryKeyword = it },
                label = { Text("Enseigne / mot-clé (optionnel)") },
                placeholder = { Text("Ex: Monoprix, Total, Paul…") },
                supportingText = {
                    Text("Seuls les commerces dont le nom contient ce texte seront retenus.")
                },
                singleLine = true,
                leadingIcon = {
                    Icon(Icons.Filled.Store, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                },
                trailingIcon = {
                    if (categoryKeyword.isNotEmpty()) {
                        IconButton(onClick = { categoryKeyword = "" }) {
                            Icon(Icons.Filled.Clear, contentDescription = "Effacer")
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)
            )

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
            onClick = {
                val kw = categoryKeyword.trim().ifBlank { null }
                onCategoryConfirmed(selectedCategory, kw, selectedRefType, selectedDirection, activeFromMillis)
            },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp)
        ) {
            val kwSuffix = if (categoryKeyword.isNotBlank()) " · ${categoryKeyword.trim()}" else ""
            Text("Confirmer cette catégorie (${selectedCategory.displayName}$kwSuffix)")
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
