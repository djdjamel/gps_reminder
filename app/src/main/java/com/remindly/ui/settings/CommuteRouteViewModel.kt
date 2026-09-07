package com.remindly.ui.settings

import android.annotation.SuppressLint
import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.android.gms.location.LocationServices
import com.google.android.gms.maps.model.LatLng
import com.google.android.libraries.places.api.Places
import com.google.android.libraries.places.api.model.AutocompletePrediction
import com.google.android.libraries.places.api.model.Place
import com.google.android.libraries.places.api.net.FetchPlaceRequest
import com.google.android.libraries.places.api.net.FindAutocompletePredictionsRequest
import com.remindly.data.settings.VoiceAlarmSettings
import com.remindly.data.settings.VoiceAlarmSettingsRepository
import com.remindly.location.DirectionsService
import com.remindly.location.RouteOption
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import javax.inject.Inject

enum class CommuteStep {
    SELECT_START,
    SELECT_END,
    CHOOSE_ROUTE
}

data class CommuteRouteUiState(
    val currentStep: CommuteStep = CommuteStep.SELECT_START,
    val startLocation: LatLng? = null,
    val startLabel: String? = null,
    val endLocation: LatLng? = null,
    val endLabel: String? = null,
    val routes: List<RouteOption> = emptyList(),
    val selectedRouteIndex: Int = 0,
    val isLoadingRoutes: Boolean = false,
    val searchQuery: String = "",
    val isSearching: Boolean = false,
    val predictions: List<AutocompletePrediction> = emptyList(),
    val isSaving: Boolean = false
)

@HiltViewModel
class CommuteRouteViewModel @Inject constructor(
    private val settingsRepository: VoiceAlarmSettingsRepository,
    private val directionsService: DirectionsService,
    @ApplicationContext private val context: Context
) : ViewModel() {

    private val _uiState = MutableStateFlow(CommuteRouteUiState())
    val uiState: StateFlow<CommuteRouteUiState> = _uiState.asStateFlow()

    private val fusedLocationClient = LocationServices.getFusedLocationProviderClient(context)

    private val placesClient by lazy {
        if (!Places.isInitialized()) {
            Places.initialize(context, "AIzaSyBCv_6Tt9fu9eLQDwIHiFSYjDiRqZkC8eA")
        }
        Places.createClient(context)
    }

    private var searchJob: Job? = null

    init {
        loadExistingRoute()
    }

    private fun loadExistingRoute() {
        viewModelScope.launch {
            val settings = settingsRepository.getSettings()
            if (settings.hasCommuteRoute) {
                val start = LatLng(settings.commuteStartLat!!, settings.commuteStartLng!!)
                val end = LatLng(settings.commuteEndLat!!, settings.commuteEndLng!!)
                _uiState.update {
                    it.copy(
                        startLocation = start,
                        startLabel = settings.commuteStartLabel ?: "Départ",
                        endLocation = end,
                        endLabel = settings.commuteEndLabel ?: "Arrivée",
                        currentStep = CommuteStep.CHOOSE_ROUTE
                    )
                }
                calculateRoutes(start, end)
            } else {
                fetchCurrentLocationForStart()
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun fetchCurrentLocationForStart() {
        viewModelScope.launch {
            try {
                val location = fusedLocationClient.lastLocation.await()
                if (location != null && _uiState.value.startLocation == null) {
                    val latLng = LatLng(location.latitude, location.longitude)
                    _uiState.update {
                        it.copy(startLocation = latLng, startLabel = "Ma position actuelle")
                    }
                }
            } catch (e: Exception) {
                // Ignore
            }
        }
    }

    fun setStep(step: CommuteStep) {
        _uiState.update { it.copy(currentStep = step, searchQuery = "", predictions = emptyList()) }
        if (step == CommuteStep.CHOOSE_ROUTE) {
            val start = _uiState.value.startLocation
            val end = _uiState.value.endLocation
            if (start != null && end != null) {
                calculateRoutes(start, end)
            }
        }
    }

    fun onMapClick(latLng: LatLng) {
        val label = "Lat: ${"%.4f".format(latLng.latitude)}, Lng: ${"%.4f".format(latLng.longitude)}"
        when (_uiState.value.currentStep) {
            CommuteStep.SELECT_START -> {
                _uiState.update { it.copy(startLocation = latLng, startLabel = label) }
            }
            CommuteStep.SELECT_END -> {
                _uiState.update { it.copy(endLocation = latLng, endLabel = label) }
            }
            CommuteStep.CHOOSE_ROUTE -> {
                // Pas de modification au clic carte
            }
        }
    }

    fun selectRouteIndex(index: Int) {
        if (index in _uiState.value.routes.indices) {
            _uiState.update { it.copy(selectedRouteIndex = index) }
        }
    }

    fun calculateRoutes(start: LatLng, end: LatLng) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoadingRoutes = true) }
            val routes = directionsService.getRoutes(
                startLat = start.latitude,
                startLng = start.longitude,
                endLat = end.latitude,
                endLng = end.longitude
            )
            _uiState.update {
                it.copy(
                    routes = routes,
                    selectedRouteIndex = 0,
                    isLoadingRoutes = false
                )
            }
        }
    }

    fun updateSearchQuery(query: String) {
        _uiState.update { it.copy(searchQuery = query) }
        searchJob?.cancel()
        if (query.length >= 2) {
            searchJob = viewModelScope.launch {
                delay(300)
                fetchPredictions(query)
            }
        } else {
            _uiState.update { it.copy(predictions = emptyList()) }
        }
    }

    private suspend fun fetchPredictions(query: String) {
        _uiState.update { it.copy(isSearching = true) }
        try {
            val request = FindAutocompletePredictionsRequest.builder()
                .setQuery(query)
                .build()
            val response = placesClient.findAutocompletePredictions(request).await()
            _uiState.update {
                it.copy(isSearching = false, predictions = response.autocompletePredictions)
            }
        } catch (e: Exception) {
            _uiState.update { it.copy(isSearching = false, predictions = emptyList()) }
        }
    }

    fun selectPrediction(placeId: String, onLocationResolved: (LatLng) -> Unit) {
        @Suppress("DEPRECATION")
        val placeFields = listOf(Place.Field.LAT_LNG, Place.Field.NAME)
        val request = FetchPlaceRequest.newInstance(placeId, placeFields)
        viewModelScope.launch {
            _uiState.update { it.copy(isSearching = true) }
            try {
                val response = placesClient.fetchPlace(request).await()
                val place = response.place
                val latLng = place.latLng
                @Suppress("DEPRECATION")
                val name = place.name ?: ""
                if (latLng != null) {
                    _uiState.update {
                        val updated = when (it.currentStep) {
                            CommuteStep.SELECT_START -> it.copy(startLocation = latLng, startLabel = name)
                            CommuteStep.SELECT_END -> it.copy(endLocation = latLng, endLabel = name)
                            CommuteStep.CHOOSE_ROUTE -> it
                        }
                        updated.copy(isSearching = false, searchQuery = "", predictions = emptyList())
                    }
                    onLocationResolved(latLng)
                } else {
                    _uiState.update { it.copy(isSearching = false) }
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(isSearching = false) }
            }
        }
    }

    fun saveRoute(onSuccess: () -> Unit) {
        val state = _uiState.value
        val start = state.startLocation ?: return
        val end = state.endLocation ?: return
        val selectedRoute = state.routes.getOrNull(state.selectedRouteIndex)

        viewModelScope.launch {
            _uiState.update { it.copy(isSaving = true) }
            settingsRepository.setCommuteRoute(
                startLat = start.latitude,
                startLng = start.longitude,
                startLabel = state.startLabel ?: "Départ",
                endLat = end.latitude,
                endLng = end.longitude,
                endLabel = state.endLabel ?: "Arrivée",
                polyline = selectedRoute?.encodedPolyline
            )
            _uiState.update { it.copy(isSaving = false) }
            onSuccess()
        }
    }
}
