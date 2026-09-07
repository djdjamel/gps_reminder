package com.remindly.location

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingClient
import com.google.android.gms.location.GeofencingRequest
import com.google.android.gms.location.LocationServices
import com.remindly.data.settings.VoiceAlarmSettingsRepository
import com.remindly.domain.model.CategoryReferenceType
import com.remindly.domain.model.PlaceCategory
import com.remindly.domain.model.Reminder
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class GeofenceManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val nearbyPlacesService: NearbyPlacesService,
    private val directionsService: DirectionsService,
    private val settingsRepository: VoiceAlarmSettingsRepository
) {
    private val tag = "GeofenceManager"
    private val geofencingClient: GeofencingClient = LocationServices.getGeofencingClient(context)
    private val fusedLocationClient: FusedLocationProviderClient = LocationServices.getFusedLocationProviderClient(context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val geofencePendingIntent: PendingIntent by lazy {
        val intent = Intent(context, GeofenceBroadcastReceiver::class.java)
        PendingIntent.getBroadcast(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        )
    }

    @SuppressLint("MissingPermission")
    fun addGeofence(reminder: Reminder) {
        scope.launch {
            try {
                // 1. Rappel par Catégorie (POI)
                if (reminder.placeCategory != null) {
                    val category = PlaceCategory.fromId(reminder.placeCategory) ?: return@launch
                    val settings = settingsRepository.getSettings()

                    Log.d(tag, "addGeofence: Début de recherche pour rappel ${reminder.id} - Catégorie ${category.displayName}")

                    val places = if (reminder.categoryRefType == CategoryReferenceType.COMMUTE_ROUTE.id && settings.hasCommuteRoute) {
                        if (settings.commuteRoutePolyline != null) {
                            val allPoints = directionsService.decodePolyline(settings.commuteRoutePolyline)
                            Log.d(tag, "addGeofence: Recherche le long de la polyline (${allPoints.size} points)")
                            nearbyPlacesService.searchAlongWaypoints(allPoints, category, radiusPerPointMeters = 1000)
                        } else {
                            Log.d(tag, "addGeofence: Recherche le long du trajet direct (${settings.commuteStartLabel} -> ${settings.commuteEndLabel})")
                            nearbyPlacesService.searchAlongRoute(
                                startLat = settings.commuteStartLat!!,
                                startLng = settings.commuteStartLng!!,
                                endLat = settings.commuteEndLat!!,
                                endLng = settings.commuteEndLng!!,
                                category = category,
                                radiusPerPointMeters = 1000
                            )
                        }
                    } else {
                        // Recherche autour de la position actuelle
                        val location = try {
                            fusedLocationClient.lastLocation.await()
                        } catch (e: Exception) {
                            null
                        }

                        val centerLat = location?.latitude ?: reminder.placeLat
                        val centerLng = location?.longitude ?: reminder.placeLng

                        if (centerLat != null && centerLng != null && centerLat != 0.0 && centerLng != 0.0) {
                            val radiusMeters = settings.poiSearchRadiusKm * 1000
                            Log.d(tag, "addGeofence: Recherche autour de ($centerLat, $centerLng) sur un rayon de $radiusMeters m")
                            nearbyPlacesService.searchNearby(
                                centerLat = centerLat,
                                centerLng = centerLng,
                                radiusMeters = radiusMeters,
                                category = category
                            )
                        } else {
                            Log.w(tag, "addGeofence: Position actuelle inconnue pour la recherche de catégorie")
                            emptyList()
                        }
                    }

                    if (places.isNotEmpty()) {
                        val geofences = places.mapIndexed { index, place ->
                            Log.d(tag, "-> Géofence [${reminder.id}_geo_$index] posée sur: ${place.name} (${place.latitude}, ${place.longitude}) - Rayon 250m")
                            Geofence.Builder()
                                .setRequestId("${reminder.id}_geo_${index}")
                                .setCircularRegion(
                                    place.latitude,
                                    place.longitude,
                                    250f // Rayon augmenté à 250m pour détection automobile/piétonne fluide
                                )
                                .setExpirationDuration(Geofence.NEVER_EXPIRE)
                                .setTransitionTypes(Geofence.GEOFENCE_TRANSITION_ENTER)
                                .setNotificationResponsiveness(5000) // Réactivité 5 secondes
                                .build()
                        }

                        val request = GeofencingRequest.Builder()
                            .setInitialTrigger(GeofencingRequest.INITIAL_TRIGGER_ENTER)
                            .addGeofences(geofences)
                            .build()

                        geofencingClient.addGeofences(request, geofencePendingIntent)
                            .addOnSuccessListener {
                                Log.i(tag, "SUCCÈS: ${geofences.size} géofences enregistrées pour le rappel ${reminder.id}")
                            }
                            .addOnFailureListener { e ->
                                Log.e(tag, "ÉCHEC enregistrement géofences: ${e.message}", e)
                            }
                    } else {
                        Log.w(tag, "ATTENTION: Aucun POI trouvé pour la catégorie ${category.displayName}. Aucune géofence enregistrée.")
                    }
                } else if (reminder.placeLat != null && reminder.placeLng != null && reminder.placeLat != 0.0 && reminder.placeLng != 0.0) {
                    // 2. Rappel à adresse fixe unique
                    Log.d(tag, "addGeofence: Enregistrement géofence unique pour ${reminder.id} à (${reminder.placeLat}, ${reminder.placeLng})")
                    registerSingleGeofence(
                        reminder.id.toString(),
                        reminder.placeLat,
                        reminder.placeLng,
                        reminder.placeRadiusM ?: 200f
                    )
                }
            } catch (t: Throwable) {
                Log.e(tag, "Exception dans addGeofence: ${t.message}", t)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun registerSingleGeofence(requestId: String, lat: Double, lng: Double, radius: Float) {
        val geofence = Geofence.Builder()
            .setRequestId(requestId)
            .setCircularRegion(lat, lng, radius)
            .setExpirationDuration(Geofence.NEVER_EXPIRE)
            .setTransitionTypes(Geofence.GEOFENCE_TRANSITION_ENTER)
            .setNotificationResponsiveness(5000)
            .build()

        val request = GeofencingRequest.Builder()
            .setInitialTrigger(GeofencingRequest.INITIAL_TRIGGER_ENTER)
            .addGeofence(geofence)
            .build()

        geofencingClient.addGeofences(request, geofencePendingIntent)
            .addOnSuccessListener {
                Log.i(tag, "SUCCÈS: Géofence unique $requestId enregistrée (${lat}, ${lng})")
            }
            .addOnFailureListener { e ->
                Log.e(tag, "ÉCHEC géofence unique $requestId: ${e.message}", e)
            }
    }

    fun removeGeofence(reminderId: Long) {
        val idsToRemove = listOf(reminderId.toString()) + (0..35).map { "${reminderId}_geo_$it" }
        geofencingClient.removeGeofences(idsToRemove)
            .addOnSuccessListener {
                Log.d(tag, "Géofences du rappel $reminderId supprimées")
            }
            .addOnFailureListener { e ->
                Log.w(tag, "Erreur suppression géofences $reminderId: ${e.message}")
            }
    }
}
