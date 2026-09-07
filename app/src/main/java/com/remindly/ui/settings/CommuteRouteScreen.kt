package com.remindly.ui.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.LatLngBounds
import com.google.maps.android.compose.*
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CommuteRouteScreen(
    onNavigateBack: () -> Unit,
    viewModel: CommuteRouteViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val coroutineScope = rememberCoroutineScope()

    val fallbackLocation = LatLng(48.8566, 2.3522)
    val cameraPositionState = rememberCameraPositionState {
        position = CameraPosition.fromLatLngZoom(uiState.startLocation ?: fallbackLocation, 12f)
    }

    var isSearchActive by remember { mutableStateOf(false) }

    // Centrer la carte lors du changement d'étape ou de calcul de route
    LaunchedEffect(uiState.currentStep, uiState.routes) {
        if (uiState.currentStep == CommuteStep.CHOOSE_ROUTE && uiState.startLocation != null && uiState.endLocation != null) {
            val builder = LatLngBounds.builder()
            builder.include(uiState.startLocation!!)
            builder.include(uiState.endLocation!!)
            uiState.routes.getOrNull(uiState.selectedRouteIndex)?.points?.forEach { builder.include(it) }
            try {
                cameraPositionState.animate(CameraUpdateFactory.newLatLngBounds(builder.build(), 120))
            } catch (e: Exception) {
                // Ignore if view size not yet computed
            }
        } else if (uiState.currentStep == CommuteStep.SELECT_START && uiState.startLocation != null) {
            cameraPositionState.animate(CameraUpdateFactory.newLatLngZoom(uiState.startLocation!!, 15f))
        } else if (uiState.currentStep == CommuteStep.SELECT_END && uiState.endLocation != null) {
            cameraPositionState.animate(CameraUpdateFactory.newLatLngZoom(uiState.endLocation!!, 15f))
        }
    }

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = { Text("Mon Trajet Habituel") },
                    navigationIcon = {
                        IconButton(onClick = onNavigateBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Retour")
                        }
                    }
                )

                // Indicateur d'étapes
                StepIndicator(
                    currentStep = uiState.currentStep,
                    onStepClick = { step ->
                        if (step == CommuteStep.SELECT_START || (step == CommuteStep.SELECT_END && uiState.startLocation != null) || (step == CommuteStep.CHOOSE_ROUTE && uiState.startLocation != null && uiState.endLocation != null)) {
                            viewModel.setStep(step)
                        }
                    }
                )
            }
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            // Carte Google Maps
            GoogleMap(
                modifier = Modifier.fillMaxSize(),
                cameraPositionState = cameraPositionState,
                properties = MapProperties(isMyLocationEnabled = true),
                uiSettings = MapUiSettings(zoomControlsEnabled = false, myLocationButtonEnabled = false),
                onMapClick = { latLng ->
                    viewModel.onMapClick(latLng)
                }
            ) {
                // Marqueur Départ (Vert)
                uiState.startLocation?.let { start ->
                    Marker(
                        state = MarkerState(position = start),
                        title = "🟢 Départ : ${uiState.startLabel ?: "Point choisi"}",
                        icon = BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_GREEN)
                    )
                }

                // Marqueur Arrivée (Rouge)
                uiState.endLocation?.let { end ->
                    Marker(
                        state = MarkerState(position = end),
                        title = "🔴 Arrivée : ${uiState.endLabel ?: "Point choisi"}",
                        icon = BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_RED)
                    )
                }

                // Tracé des itinéraires
                if (uiState.currentStep == CommuteStep.CHOOSE_ROUTE) {
                    uiState.routes.forEachIndexed { index, route ->
                        val isSelected = index == uiState.selectedRouteIndex
                        Polyline(
                            points = route.points,
                            color = if (isSelected) MaterialTheme.colorScheme.primary else Color.Gray.copy(alpha = 0.6f),
                            width = if (isSelected) 14f else 8f,
                            zIndex = if (isSelected) 2f else 1f,
                            clickable = true,
                            onClick = {
                                viewModel.selectRouteIndex(index)
                            }
                        )
                    }
                }
            }

            // Barre de recherche de lieu (pour Départ et Arrivée)
            if (uiState.currentStep != CommuteStep.CHOOSE_ROUTE) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                        .align(Alignment.TopCenter)
                ) {
                    val searchPlaceholder = if (uiState.currentStep == CommuteStep.SELECT_START)
                        "Rechercher le départ (ex: Domicile)…"
                    else "Rechercher l'arrivée (ex: Bureau)…"

                    SearchBar(
                        query = uiState.searchQuery,
                        onQueryChange = viewModel::updateSearchQuery,
                        onSearch = { isSearchActive = false },
                        active = isSearchActive,
                        onActiveChange = { isSearchActive = it },
                        placeholder = { Text(searchPlaceholder) },
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
                                    headlineContent = { Text(prediction.getPrimaryText(null).toString()) },
                                    supportingContent = { Text(prediction.getSecondaryText(null).toString(), style = MaterialTheme.typography.bodySmall) },
                                    leadingContent = { Icon(Icons.Filled.LocationOn, contentDescription = null) },
                                    modifier = Modifier.clickable {
                                        viewModel.selectPrediction(prediction.placeId) { latLng ->
                                            isSearchActive = false
                                            coroutineScope.launch {
                                                cameraPositionState.animate(CameraUpdateFactory.newLatLngZoom(latLng, 16f))
                                            }
                                        }
                                    }
                                )
                                HorizontalDivider()
                            }
                        }
                    }
                }
            }

            // Panneau inférieur d'action contextuelle
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter)
                    .padding(16.dp),
                shape = RoundedCornerShape(16.dp),
                elevation = CardDefaults.cardElevation(8.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    when (uiState.currentStep) {
                        CommuteStep.SELECT_START -> {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Surface(
                                    shape = CircleShape,
                                    color = Color(0xFF2E7D32),
                                    modifier = Modifier.size(28.dp)
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Text("1", color = Color.White, fontWeight = FontWeight.Bold)
                                    }
                                }
                                Spacer(Modifier.width(10.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("Point de Départ (ex: Domicile)", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                                    Text(
                                        uiState.startLabel ?: "Touchez la carte pour placer le repère",
                                        style = MaterialTheme.typography.bodyLarge,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }

                            Button(
                                onClick = { viewModel.setStep(CommuteStep.SELECT_END) },
                                modifier = Modifier.fillMaxWidth(),
                                enabled = uiState.startLocation != null,
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Text("Valider le départ ➔ Choisir l'arrivée")
                            }
                        }

                        CommuteStep.SELECT_END -> {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Surface(
                                    shape = CircleShape,
                                    color = Color(0xFFC62828),
                                    modifier = Modifier.size(28.dp)
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Text("2", color = Color.White, fontWeight = FontWeight.Bold)
                                    }
                                }
                                Spacer(Modifier.width(10.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("Point d'Arrivée (ex: Bureau)", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error)
                                    Text(
                                        uiState.endLabel ?: "Touchez la carte pour placer le repère",
                                        style = MaterialTheme.typography.bodyLarge,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                OutlinedButton(
                                    onClick = { viewModel.setStep(CommuteStep.SELECT_START) },
                                    modifier = Modifier.weight(1f),
                                    shape = RoundedCornerShape(12.dp)
                                ) {
                                    Text("❮ Départ")
                                }
                                Button(
                                    onClick = { viewModel.setStep(CommuteStep.CHOOSE_ROUTE) },
                                    modifier = Modifier.weight(2f),
                                    enabled = uiState.endLocation != null,
                                    shape = RoundedCornerShape(12.dp)
                                ) {
                                    Text("Calculer l'itinéraire ➔")
                                }
                            }
                        }

                        CommuteStep.CHOOSE_ROUTE -> {
                            if (uiState.isLoadingRoutes) {
                                Box(
                                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        CircularProgressIndicator(modifier = Modifier.size(24.dp))
                                        Spacer(Modifier.width(12.dp))
                                        Text("Calcul des itinéraires Google Maps…")
                                    }
                                }
                            } else {
                                Text(
                                    "Choisissez votre itinéraire habituel :",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold
                                )

                                LazyRow(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    itemsIndexed(uiState.routes) { index, route ->
                                        val isSelected = index == uiState.selectedRouteIndex
                                        Card(
                                            modifier = Modifier
                                                .width(220.dp)
                                                .clickable { viewModel.selectRouteIndex(index) },
                                            shape = RoundedCornerShape(12.dp),
                                            border = if (isSelected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
                                            colors = CardDefaults.cardColors(
                                                containerColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
                                            )
                                        ) {
                                            Column(modifier = Modifier.padding(12.dp)) {
                                                Row(verticalAlignment = Alignment.CenterVertically) {
                                                    Icon(
                                                        Icons.Filled.DirectionsCar,
                                                        contentDescription = null,
                                                        tint = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                                        modifier = Modifier.size(18.dp)
                                                    )
                                                    Spacer(Modifier.width(6.dp))
                                                    Text(
                                                        route.summary,
                                                        style = MaterialTheme.typography.labelLarge,
                                                        fontWeight = FontWeight.Bold,
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis
                                                    )
                                                }
                                                Spacer(Modifier.height(4.dp))
                                                Text(
                                                    "${route.durationText}  •  ${route.distanceText}",
                                                    style = MaterialTheme.typography.bodyMedium,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                        }
                                    }
                                }

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    OutlinedButton(
                                        onClick = { viewModel.setStep(CommuteStep.SELECT_END) },
                                        shape = RoundedCornerShape(12.dp)
                                    ) {
                                        Text("Modifier")
                                    }
                                    Button(
                                        onClick = { viewModel.saveRoute(onNavigateBack) },
                                        modifier = Modifier.weight(1f),
                                        shape = RoundedCornerShape(12.dp),
                                        enabled = !uiState.isSaving
                                    ) {
                                        if (uiState.isSaving) {
                                            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                                        } else {
                                            Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                                            Spacer(Modifier.width(6.dp))
                                            Text("Enregistrer ce trajet")
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StepIndicator(
    currentStep: CommuteStep,
    onStepClick: (CommuteStep) -> Unit
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            StepBadge(
                stepNumber = 1,
                label = "Départ",
                isActive = currentStep == CommuteStep.SELECT_START,
                isCompleted = currentStep != CommuteStep.SELECT_START,
                onClick = { onStepClick(CommuteStep.SELECT_START) }
            )
            Icon(Icons.Filled.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.outlineVariant, modifier = Modifier.size(18.dp))
            StepBadge(
                stepNumber = 2,
                label = "Arrivée",
                isActive = currentStep == CommuteStep.SELECT_END,
                isCompleted = currentStep == CommuteStep.CHOOSE_ROUTE,
                onClick = { onStepClick(CommuteStep.SELECT_END) }
            )
            Icon(Icons.Filled.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.outlineVariant, modifier = Modifier.size(18.dp))
            StepBadge(
                stepNumber = 3,
                label = "Itinéraire",
                isActive = currentStep == CommuteStep.CHOOSE_ROUTE,
                isCompleted = false,
                onClick = { onStepClick(CommuteStep.CHOOSE_ROUTE) }
            )
        }
    }
}

@Composable
private fun StepBadge(
    stepNumber: Int,
    label: String,
    isActive: Boolean,
    isCompleted: Boolean,
    onClick: () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .background(if (isActive) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Surface(
            shape = CircleShape,
            color = when {
                isCompleted -> Color(0xFF2E7D32)
                isActive -> MaterialTheme.colorScheme.primary
                else -> MaterialTheme.colorScheme.outlineVariant
            },
            modifier = Modifier.size(20.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                if (isCompleted) {
                    Icon(Icons.Filled.Check, contentDescription = null, tint = Color.White, modifier = Modifier.size(14.dp))
                } else {
                    Text(
                        "$stepNumber",
                        color = if (isActive) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
        Spacer(Modifier.width(6.dp))
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal,
            color = if (isActive) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
