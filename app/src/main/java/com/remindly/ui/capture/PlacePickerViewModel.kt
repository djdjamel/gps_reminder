package com.remindly.ui.capture

import android.annotation.SuppressLint
import android.content.Context
import android.content.IntentSender
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.android.gms.common.api.ResolvableApiException
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.LocationSettingsRequest
import com.google.android.gms.location.Priority
import com.google.android.gms.maps.model.LatLng
import com.google.android.libraries.places.api.Places
import com.google.android.libraries.places.api.model.AutocompletePrediction
import com.google.android.libraries.places.api.model.Place
import com.google.android.libraries.places.api.net.FetchPlaceRequest
import com.google.android.libraries.places.api.net.FindAutocompletePredictionsRequest
import com.remindly.data.repo.SavedPlaceRepository
import com.remindly.domain.model.SavedPlace
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import javax.inject.Inject

import com.remindly.data.settings.VoiceAlarmSettings
import com.remindly.data.settings.VoiceAlarmSettingsRepository

data class PlacePickerUiState(
    val searchQuery: String = "",
    val isSearching: Boolean = false,
    val searchError: String? = null,
    val predictions: List<AutocompletePrediction> = emptyList()
)

@HiltViewModel
class PlacePickerViewModel @Inject constructor(
    private val savedPlaceRepository: SavedPlaceRepository,
    private val settingsRepository: VoiceAlarmSettingsRepository,
    @ApplicationContext private val context: Context
) : ViewModel() {

    private val _uiState = MutableStateFlow(PlacePickerUiState())
    val uiState: StateFlow<PlacePickerUiState> = _uiState.asStateFlow()

    private val _currentLocation = MutableStateFlow<LatLng?>(null)
    val currentLocation: StateFlow<LatLng?> = _currentLocation.asStateFlow()

    val userSettings: StateFlow<VoiceAlarmSettings> = settingsRepository.settingsFlow
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = VoiceAlarmSettings()
        )

    // Emet un IntentSender quand le GPS est éteint → l'écran affiche le dialogue système
    private val _locationSettingsResolution = MutableStateFlow<IntentSender?>(null)
    val locationSettingsResolution: StateFlow<IntentSender?> = _locationSettingsResolution.asStateFlow()

    val savedPlaces: StateFlow<List<SavedPlace>> = savedPlaceRepository.observeAll()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    private val fusedLocationClient = LocationServices.getFusedLocationProviderClient(context)

    private val placesClient by lazy {
        if (!Places.isInitialized()) {
            Places.initialize(context, "AIzaSyBCv_6Tt9fu9eLQDwIHiFSYjDiRqZkC8eA")
        }
        Places.createClient(context)
    }

    private var searchJob: Job? = null

    init {
        checkLocationSettings()
    }

    /**
     * Vérifie si le GPS est activé.
     * - Si oui  → récupère la position directement
     * - Si non  → émet un IntentSender pour afficher le dialogue système "Activer la localisation"
     */
    fun checkLocationSettings() {
        val locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000).build()
        val settingsRequest = LocationSettingsRequest.Builder()
            .addLocationRequest(locationRequest)
            .setAlwaysShow(true) // Forcer le dialogue même si la localisation est partiellement active
            .build()

        LocationServices.getSettingsClient(context)
            .checkLocationSettings(settingsRequest)
            .addOnSuccessListener {
                // GPS activé → récupérer la position
                fetchCurrentLocation()
            }
            .addOnFailureListener { exception ->
                if (exception is ResolvableApiException) {
                    // GPS éteint → transmettre l'IntentSender à l'écran
                    _locationSettingsResolution.value = exception.resolution.intentSender
                }
            }
    }

    /** Appelé par l'écran après que le dialogue ait été affiché */
    fun clearLocationSettingsResolution() {
        _locationSettingsResolution.value = null
    }

    @SuppressLint("MissingPermission")
    fun fetchCurrentLocation() {
        viewModelScope.launch {
            try {
                val location = fusedLocationClient.lastLocation.await()
                    ?: fusedLocationClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null).await()
                if (location != null) {
                    _currentLocation.value = LatLng(location.latitude, location.longitude)
                }
            } catch (e: Exception) {
                // Permission non accordée ou position indisponible
            }
        }
    }

    fun updateSearchQuery(query: String) {
        _uiState.update { it.copy(searchQuery = query, searchError = null) }
        searchJob?.cancel()
        if (query.length >= 2) {
            searchJob = viewModelScope.launch {
                delay(300) // debounce 300ms
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

    fun selectPrediction(placeId: String, onResult: (LatLng, String) -> Unit) {
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
                        it.copy(isSearching = false, searchQuery = name, predictions = emptyList())
                    }
                    onResult(latLng, name)
                } else {
                    _uiState.update { it.copy(isSearching = false) }
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(isSearching = false, searchError = "Impossible de charger ce lieu")
                }
            }
        }
    }

    fun savePlace(name: String, lat: Double, lng: Double) {
        viewModelScope.launch {
            savedPlaceRepository.save(SavedPlace(name = name.trim(), latitude = lat, longitude = lng))
        }
    }
}
