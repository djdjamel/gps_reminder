package com.remindly.location

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
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

                    val places = if (reminder.categoryRefType == CategoryReferenceType.COMMUTE_ROUTE.id && settings.hasCommuteRoute) {
                        if (settings.commuteRoutePolyline != null) {
                            val allPoints = directionsService.decodePolyline(settings.commuteRoutePolyline)
                            // Échantillonner 6 à 8 waypoints équidistants le long de la polyline
                            val step = (allPoints.size / 6).coerceAtLeast(1)
                            val sampledWaypoints = allPoints.filterIndexed { idx, _ -> idx % step == 0 }
                            nearbyPlacesService.searchAlongWaypoints(sampledWaypoints, category)
                        } else {
                            nearbyPlacesService.searchAlongRoute(
                                startLat = settings.commuteStartLat!!,
                                startLng = settings.commuteStartLng!!,
                                endLat = settings.commuteEndLat!!,
                                endLng = settings.commuteEndLng!!,
                                category = category
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

                        if (centerLat != null && centerLng != null) {
                            val radiusMeters = settings.poiSearchRadiusKm * 1000
                            nearbyPlacesService.searchNearby(
                                centerLat = centerLat,
                                centerLng = centerLng,
                                radiusMeters = radiusMeters,
                                category = category
                            )
                        } else {
                            emptyList()
                        }
                    }

                    if (places.isNotEmpty()) {
                        val geofences = places.mapIndexed { index, place ->
                            Geofence.Builder()
                                .setRequestId("${reminder.id}_geo_${index}")
                                .setCircularRegion(
                                    place.latitude,
                                    place.longitude,
                                    120f // Rayon de 120m
                                )
                                .setExpirationDuration(Geofence.NEVER_EXPIRE)
                                .setTransitionTypes(Geofence.GEOFENCE_TRANSITION_ENTER or Geofence.GEOFENCE_TRANSITION_EXIT)
                                .build()
                        }

                        val request = GeofencingRequest.Builder()
                            .setInitialTrigger(GeofencingRequest.INITIAL_TRIGGER_ENTER)
                            .addGeofences(geofences)
                            .build()

                        geofencingClient.addGeofences(request, geofencePendingIntent)
                            .addOnFailureListener { e ->
                                e.printStackTrace()
                            }
                    } else if (reminder.placeLat != null && reminder.placeLng != null) {
                        // Fallback vers géofence unique si aucun POI trouvé
                        registerSingleGeofence(reminder.id.toString(), reminder.placeLat, reminder.placeLng, reminder.placeRadiusM ?: 100f)
                    }
                } else if (reminder.placeLat != null && reminder.placeLng != null) {
                    // 2. Rappel à adresse fixe unique
                    registerSingleGeofence(reminder.id.toString(), reminder.placeLat, reminder.placeLng, reminder.placeRadiusM ?: 100f)
                }
            } catch (t: Throwable) {
                t.printStackTrace()
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun registerSingleGeofence(requestId: String, lat: Double, lng: Double, radius: Float) {
        val geofence = Geofence.Builder()
            .setRequestId(requestId)
            .setCircularRegion(lat, lng, radius)
            .setExpirationDuration(Geofence.NEVER_EXPIRE)
            .setTransitionTypes(Geofence.GEOFENCE_TRANSITION_ENTER or Geofence.GEOFENCE_TRANSITION_EXIT)
            .build()

        val request = GeofencingRequest.Builder()
            .setInitialTrigger(GeofencingRequest.INITIAL_TRIGGER_ENTER)
            .addGeofence(geofence)
            .build()

        geofencingClient.addGeofences(request, geofencePendingIntent)
            .addOnFailureListener { e ->
                e.printStackTrace()
            }
    }

    fun removeGeofence(reminderId: Long) {
        val idsToRemove = listOf(reminderId.toString()) + (0..35).map { "${reminderId}_geo_$it" }
        geofencingClient.removeGeofences(idsToRemove)
    }
}
